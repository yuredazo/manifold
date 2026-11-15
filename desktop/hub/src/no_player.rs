use crate::hub::Input;
use std::sync::mpsc::Sender;

// Watching feeds is Windows only for now. The Linux window hides the Watch page, and a watch that still arrives is
// closed at once, which ends the stream on the other device.
#[allow(dead_code)]
pub enum PlayerInput {
    Video { data: Vec<u8>, keyframe: bool },
    Audio(Vec<u8>),
    Retitle(String),
    Resumed,
}

#[allow(dead_code)]
pub struct PlayerConfig {
    pub stream_id: u16,
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub alpha: bool,
    pub sound_only: bool,
    pub with_audio: bool,
    pub audio_buffer_ms: usize,
}

pub struct PlayerHandle;

impl PlayerHandle {
    pub fn send(&self, _input: PlayerInput) {}
}

pub fn spawn(config: PlayerConfig, events: Sender<Input>) -> PlayerHandle {
    let _ = events.send(Input::PlayerClosed(config.stream_id));
    PlayerHandle
}
