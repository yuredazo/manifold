use crate::capture::{ShareKind, Shareable};
use crate::hub::{Command, DeviceView, Input, Pairing, PublicAddress, Snapshot, UpdateState};
use crate::sharing::ShareOptions;
use crate::{AppWindow, Choice, DeviceDetail, FeedRow, Ui, NearbyRow, PairedRow, ShareRow, WatchGroup};
use manifold_protocol::stream::StreamProfile;
use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};
use std::collections::HashMap;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc::Sender;

#[derive(Default)]
struct Shown {
    snapshot: Option<Snapshot>,
    device: String,
    choices: Vec<Shareable>,
}

// The lists are changed row by row rather than replaced, so a snapshot every 50 ms does not rebuild every row
// and drop its hover state or replay its transitions.
#[derive(Default)]
struct Lists {
    watch_groups: Rc<VecModel<WatchGroup>>,
    shares: Rc<VecModel<ShareRow>>,
    activity: Rc<VecModel<SharedString>>,
    paired: Rc<VecModel<PairedRow>>,
    nearby: Rc<VecModel<NearbyRow>>,
    addresses: Rc<VecModel<SharedString>>,
    choices: Rc<VecModel<Choice>>,
    refused: Rc<VecModel<SharedString>>,
}

thread_local! {
    static SHOWN: RefCell<Shown> = RefCell::new(Shown::default());
    static LISTS: Lists = Lists::default();
    static FEEDS: RefCell<HashMap<String, Rc<VecModel<FeedRow>>>> = RefCell::new(HashMap::new());
}

fn fill<T: Clone + PartialEq + 'static>(model: &VecModel<T>, items: Vec<T>) {
    for (index, item) in items.iter().enumerate() {
        if index >= model.row_count() {
            model.push(item.clone());
        } else if model.row_data(index).as_ref() != Some(item) {
            model.set_row_data(index, item.clone());
        }
    }
    while model.row_count() > items.len() {
        model.remove(model.row_count() - 1);
    }
}

fn feeds_of(key: &str, rows: Vec<FeedRow>) -> ModelRc<FeedRow> {
    FEEDS.with_borrow_mut(|feeds| {
        let model = feeds.entry(key.to_string()).or_default().clone();
        fill(&model, rows);
        ModelRc::from(model)
    })
}

fn text(value: impl Into<SharedString>) -> SharedString {
    value.into()
}

fn kind_index(kind: ShareKind) -> i32 {
    match kind {
        ShareKind::Window => 0,
        ShareKind::Display => 1,
        ShareKind::Camera => 2,
        ShareKind::Spout => 3,
    }
}

fn profile_index(profile: StreamProfile) -> i32 {
    match profile {
        StreamProfile::LowLatency => 0,
        StreamProfile::Balanced => 1,
        StreamProfile::Smooth => 2,
    }
}

fn device_summary(device: &DeviceView) -> String {
    let mut parts = vec![if device.online { "Online".to_string() } else { "Offline".to_string() }];
    if let Some(latency) = device.latency {
        parts.push(format!("{latency} ms"));
    }
    if device.receive {
        parts.push("receiving".into());
    }
    if device.send || device.send_camera || device.send_spout {
        parts.push("sharing".into());
    }
    parts.join(" · ")
}

fn device_detail(device: Option<&DeviceView>) -> DeviceDetail {
    let Some(device) = device else { return DeviceDetail::default() };
    let status = match (device.online, device.latency) {
        (true, Some(latency)) => format!("Online · {latency} ms"),
        (true, None) => "Online".into(),
        (false, _) => "Offline".into(),
    };
    let receive_detail = if !device.receive {
        "Its feeds appear on Watch".to_string()
    } else if !device.online {
        "It is offline".into()
    } else if device.offered == 0 {
        "It shares nothing right now".into()
    } else {
        format!("Feeds on offer: {}", device.offered)
    };
    DeviceDetail {
        key: text(device.key.as_str()),
        name: text(device.name.as_str()),
        status: text(status),
        online: device.online,
        fingerprint: text(device.fingerprint.as_str()),
        address: text(device.address.as_str()),
        receive: device.receive,
        receive_detail: text(receive_detail),
        send: device.send,
        send_camera: device.send_camera,
        send_spout: device.send_spout,
        refused: LISTS.with(|lists| {
            fill(&lists.refused, device.refused.iter().map(|line| text(line.as_str())).collect());
            ModelRc::from(lists.refused.clone())
        }),
    }
}

fn choice_detail(choice: &Shareable) -> String {
    match choice.kind {
        ShareKind::Display => format!("{} x {}{}", choice.width, choice.height, if choice.primary { " · main display" } else { "" }),
        ShareKind::Camera => format!("Camera · {} x {}", choice.width, choice.height),
        ShareKind::Spout => format!("Spout · {} x {}", choice.width, choice.height),
        ShareKind::Window => choice.process.clone(),
    }
}

pub fn apply(app: &AppWindow, snapshot: Snapshot) {
    let hub = app.global::<Ui>();
    let (watch_groups, shares, activity, paired, nearby, addresses) =
        LISTS.with(|lists| (lists.watch_groups.clone(), lists.shares.clone(), lists.activity.clone(), lists.paired.clone(), lists.nearby.clone(), lists.addresses.clone()));
    fill(
        &watch_groups,
        snapshot
            .watch
            .iter()
            .map(|group| WatchGroup {
                key: text(group.device_key.as_str()),
                name: text(group.name.as_str()),
                meta: text(match (group.online, group.latency) {
                    (true, Some(latency)) => format!("{latency} ms"),
                    (true, None) => "online".into(),
                    (false, _) => "offline".into(),
                }),
                online: group.online,
                feeds: feeds_of(
                    &group.device_key,
                    group
                        .feeds
                        .iter()
                        .map(|feed| FeedRow {
                            name: text(feed.name.as_str()),
                            label: text(feed.label.as_str()),
                            detail: text(feed.detail.as_str()),
                            sound_only: feed.sound_only,
                            watching: feed.watching,
                        })
                        .collect(),
                ),
            })
            .collect(),
    );
    hub.set_paired_count(snapshot.devices.len() as i32);
    fill(
        &shares,
        snapshot
            .shares
            .iter()
            .map(|share| ShareRow {
                feed: text(share.feed.as_str()),
                label: text(share.label.as_str()),
                detail: text(share.detail.as_str()),
                kind: kind_index(share.kind),
                watching: share.watching > 0,
            })
            .collect(),
    );
    hub.set_can_watch(snapshot.can_watch as i32);
    hub.set_paused(snapshot.paused);
    hub.set_problem(text(snapshot.problem.clone().unwrap_or_default()));
    fill(&activity, snapshot.activity.iter().map(|line| text(line.as_str())).collect());

    hub.set_listening(snapshot.listening);
    hub.set_listen_detail(text(if !snapshot.listening {
        "Off".to_string()
    } else if snapshot.addresses.is_empty() {
        "No network address found".into()
    } else {
        snapshot.addresses.iter().take(2).cloned().collect::<Vec<_>>().join(" · ")
    }));
    hub.set_fingerprint(text(snapshot.fingerprint.as_str()));
    hub.set_profile(profile_index(snapshot.profile));
    fill(
        &paired,
        snapshot
            .devices
            .iter()
            .map(|device| PairedRow { key: text(device.key.as_str()), name: text(device.name.as_str()), detail: text(device_summary(device)), online: device.online })
            .collect(),
    );
    hub.set_any_online(snapshot.devices.iter().any(|device| device.online));
    fill(
        &nearby,
        snapshot
            .nearby
            .iter()
            .map(|found| NearbyRow {
                name: text(found.name.as_str()),
                detail: text(format!("{} · {}", found.host, manifold_protocol::crypto::readable_fingerprint(&found.id))),
                address: text(format!("{}:{}", found.host, found.port)),
            })
            .collect(),
    );
    hub.set_pairing_open(text(if snapshot.pairing_left_ms > 0 {
        let seconds = snapshot.pairing_left_ms / 1000;
        format!("{}:{:02} left", seconds / 60, seconds % 60)
    } else {
        String::new()
    }));
    fill(&addresses, snapshot.addresses.iter().map(|address| text(address.as_str())).collect());
    let (public_state, public_address) = match &snapshot.public_address {
        PublicAddress::Unknown => (0, String::new()),
        PublicAddress::Looking => (1, String::new()),
        PublicAddress::Found(address) => (2, address.clone()),
        PublicAddress::Failed => (3, String::new()),
    };
    hub.set_public_state(public_state);
    hub.set_public_address(text(public_address));

    // With no pairing only the state changes: the dialog keeps its words while it fades out.
    let (state, name, code, body) = match &snapshot.pairing {
        Pairing::None => (0, hub.get_pairing_name().into(), hub.get_pairing_code().into(), hub.get_pairing_text().into()),
        Pairing::Connecting(address) => (1, String::new(), String::new(), format!("Connecting to {address}")),
        Pairing::CodeShown { remote_name, code, confirmed } => {
            let shown = if code.len() == 6 { format!("{} {}", &code[..3], &code[3..]) } else { code.clone() };
            let body = if *confirmed { format!("Waiting for {remote_name} to confirm.") } else { "Check that both screens show this code.".into() };
            (if *confirmed { 3 } else { 2 }, remote_name.clone(), shown, body)
        }
        Pairing::Failed(reason) => (4, String::new(), String::new(), format!("Reason: {reason}.")),
    };
    hub.set_pairing_state(state);
    hub.set_pairing_name(text(name));
    hub.set_pairing_code(text(code));
    hub.set_pairing_text(text(body));

    hub.set_offer_cameras(snapshot.offer_cameras);
    hub.set_autostart(snapshot.autostart);
    hub.set_close_to_tray(snapshot.close_to_tray);
    hub.set_check_on_launch(snapshot.check_on_launch);
    let (title, detail, action, failed) = match &snapshot.update {
        UpdateState::Idle => ("Not checked yet".to_string(), String::new(), "Check now", false),
        UpdateState::Checking => ("Checking for updates".into(), String::new(), "", false),
        UpdateState::UpToDate(at) => ("Up to date".into(), format!("Checked at {at}"), "Check again", false),
        UpdateState::Available(release) => (format!("Version {} is available", release.version), "Manifold restarts to finish".into(), "Update", false),
        UpdateState::Downloading(release, fraction) => (
            format!("Downloading {}", release.version),
            fraction.map(|value| format!("{}%", (value * 100.0).round())).unwrap_or_default(),
            "",
            false,
        ),
        UpdateState::Failed(message) => ("Update check failed".into(), message.clone(), "Try again", true),
        UpdateState::InstallFailed(message) => ("Update failed".into(), message.clone(), "Try again", true),
    };
    hub.set_update_title(text(title));
    hub.set_update_detail(text(detail));
    hub.set_update_action(text(action));
    hub.set_update_failed(failed);
    hub.set_update_ready(matches!(snapshot.update, UpdateState::Available(_)));
    hub.set_update_page(text(match &snapshot.update {
        UpdateState::Available(release) => release.page.clone(),
        _ => String::new(),
    }));

    SHOWN.with_borrow_mut(|shown| {
        let detail = device_detail(snapshot.devices.iter().find(|device| device.key == shown.device));
        if hub.get_device() != detail {
            hub.set_device(detail);
        }
        if hub.get_sheet() == 2 && !snapshot.devices.iter().any(|device| device.key == shown.device) {
            hub.set_sheet(0);
        }
        match &snapshot.picker {
            Some(choices) => {
                hub.set_picker_loading(false);
                LISTS.with(|lists| {
                    fill(
                        &lists.choices,
                        choices.iter().map(|choice| Choice { label: text(choice.label()), detail: text(choice_detail(choice)), kind: kind_index(choice.kind) }).collect(),
                    )
                });
                shown.choices = choices.clone();
            }
            None => {
                hub.set_picker_loading(true);
                shown.choices.clear();
            }
        }
        shown.snapshot = Some(snapshot);
    });
}

pub fn bind(app: &AppWindow, out: Sender<Input>) {
    let hub = app.global::<Ui>();
    hub.set_version(text(env!("CARGO_PKG_VERSION")));
    LISTS.with(|lists| {
        hub.set_watch_groups(ModelRc::from(lists.watch_groups.clone()));
        hub.set_shares(ModelRc::from(lists.shares.clone()));
        hub.set_activity(ModelRc::from(lists.activity.clone()));
        hub.set_paired(ModelRc::from(lists.paired.clone()));
        hub.set_nearby(ModelRc::from(lists.nearby.clone()));
        hub.set_addresses(ModelRc::from(lists.addresses.clone()));
        hub.set_choices(ModelRc::from(lists.choices.clone()));
    });
    let send = move |command: Command| {
        let _ = out.send(Input::Command(command));
    };
    macro_rules! on {
        ($method:ident, |$($arg:ident),*| $command:expr) => {{
            let send = send.clone();
            hub.$method(move |$($arg),*| send($command));
        }};
    }
    on!(on_watch, |key, feed| Command::Watch(key.into(), feed.into()));
    on!(on_stop_watching, |key, feed| Command::StopWatching(key.into(), feed.into()));
    on!(on_unshare, |feed| Command::Unshare(feed.into()));
    on!(on_set_paused, |paused| Command::Pause(paused));
    on!(on_dismiss_problem, | | Command::DismissProblem);
    on!(on_set_listening, |on| Command::Listen(on));
    on!(on_set_receive, |key, on| Command::SetReceive(key.into(), on));
    on!(on_set_send, |key, on| Command::SetSend(key.into(), on));
    on!(on_set_send_camera, |key, on| Command::SetSendCamera(key.into(), on));
    on!(on_set_send_spout, |key, on| Command::SetSendSpout(key.into(), on));
    on!(on_unpair, |key| Command::Unpair(key.into()));
    on!(on_allow_pairing, | | Command::OpenPairing);
    on!(on_stop_pairing, | | Command::ClosePairing);
    on!(on_confirm_pairing, |accept| Command::ConfirmPairing(accept));
    on!(on_cancel_pairing, | | Command::CancelPairing);
    on!(on_dismiss_pairing, | | Command::DismissPairing);
    on!(on_set_profile, |index| Command::SetProfile(match index {
        0 => StreamProfile::LowLatency,
        2 => StreamProfile::Smooth,
        _ => StreamProfile::Balanced,
    }));
    on!(on_set_autostart, |on| Command::SetAutostart(on));
    on!(on_set_close_to_tray, |on| Command::SetCloseToTray(on));
    on!(on_set_offer_cameras, |on| Command::SetOfferCameras(on));
    on!(on_set_check_on_launch, |on| Command::SetCheckOnLaunch(on));
    on!(on_look_up_public, | | Command::LookUpPublic);
    hub.on_open_url(|url| crate::system::open_url(&url));
    let notices = std::env::current_exe().ok().and_then(|exe| Some(exe.parent()?.join("licenses").join("THIRD-PARTY-NOTICES.txt")));
    if let Some(notices) = notices.filter(|path| path.is_file()) {
        hub.set_has_notices(true);
        hub.on_open_notices(move || crate::system::open_url(&notices.display().to_string()));
    }
    hub.on_copy(|value| crate::system::copy_text(&value));

    let weak = app.as_weak();
    let sender = send.clone();
    hub.on_open_pair(move || {
        if let Some(app) = weak.upgrade() {
            app.global::<Ui>().set_sheet(1);
        }
        sender(Command::Discover(true));
    });
    let weak = app.as_weak();
    let sender = send.clone();
    hub.on_close_sheet(move || {
        let Some(app) = weak.upgrade() else { return };
        let hub = app.global::<Ui>();
        if hub.get_sheet() == 1 {
            sender(Command::Discover(false));
        }
        hub.set_sheet(0);
    });
    let weak = app.as_weak();
    let sender = send.clone();
    hub.on_pair_with(move |address| {
        if address.trim().is_empty() {
            return;
        }
        if let Some(app) = weak.upgrade() {
            app.global::<Ui>().set_sheet(0);
        }
        sender(Command::Discover(false));
        sender(Command::Pair(address.into()));
    });
    let weak = app.as_weak();
    hub.on_open_device(move |key| {
        let Some(app) = weak.upgrade() else { return };
        let hub = app.global::<Ui>();
        SHOWN.with_borrow_mut(|shown| {
            shown.device = key.to_string();
            let device = shown.snapshot.as_ref().and_then(|snapshot| snapshot.devices.iter().find(|device| device.key == shown.device));
            hub.set_device(device_detail(device));
        });
        hub.set_sheet(2);
    });
    let weak = app.as_weak();
    let sender = send.clone();
    hub.on_open_picker(move || {
        if let Some(app) = weak.upgrade() {
            let hub = app.global::<Ui>();
            hub.set_picker_loading(true);
            LISTS.with(|lists| fill(&lists.choices, vec![]));
            hub.set_sheet(4);
        }
        sender(Command::ListShareable);
    });
    let weak = app.as_weak();
    let sender = send.clone();
    hub.on_pick(move |index| {
        let Some(app) = weak.upgrade() else { return };
        let hub = app.global::<Ui>();
        let Some(choice) = SHOWN.with_borrow(|shown| shown.choices.get(index as usize).cloned()) else { return };
        let options = ShareOptions {
            audio: hub.get_with_audio(),
            sound_only: !hub.get_with_picture(),
            show_cursor: hub.get_with_cursor(),
            hide_frame: hub.get_without_frame(),
            alpha: hub.get_with_alpha(),
            frame_rate: hub.get_frame_rate(),
        };
        hub.set_sheet(0);
        hub.set_tab(1);
        sender(Command::Share(choice, options));
    });
    let sender = send.clone();
    hub.on_update_pressed(move || {
        let ready = SHOWN.with_borrow(|shown| matches!(shown.snapshot.as_ref().map(|snapshot| &snapshot.update), Some(UpdateState::Available(_))));
        sender(if ready { Command::InstallUpdate } else { Command::CheckUpdates });
    });
}

pub fn close_to_tray() -> bool {
    SHOWN.with_borrow(|shown| shown.snapshot.as_ref().is_some_and(|snapshot| snapshot.close_to_tray))
}
