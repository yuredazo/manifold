use super::Controls;
use super::encode::{AudioEncoder, SAMPLE_RATE, SAMPLES_PER_FRAME};
use crate::capture::{Captured, emit};
use libpulse_binding::def::BufferAttr;
use libpulse_binding::sample::{Format, Spec};
use libpulse_binding::stream::Direction;
use libpulse_simple_binding::Simple;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Instant;

const BYTES_PER_FRAME: usize = SAMPLES_PER_FRAME * 2 * 4;

// The default output's monitor carries the whole desktop's sound, so a window is shared with all of it, not its own.
pub fn run(handle: i64, epoch: Instant, controls: Arc<Controls>) {
    let spec = Spec { format: Format::F32le, channels: 2, rate: SAMPLE_RATE as u32 };
    let buffering = BufferAttr { maxlength: u32::MAX, tlength: u32::MAX, prebuf: u32::MAX, minreq: u32::MAX, fragsize: BYTES_PER_FRAME as u32 };
    let Ok(recording) = Simple::new(None, "Manifold", Direction::Record, Some("@DEFAULT_MONITOR@"), "Shared sound", &spec, None, Some(&buffering)) else {
        eprintln!("sound: cannot record the default output");
        return;
    };
    let Some(mut encoder) = AudioEncoder::open() else {
        eprintln!("sound: no AAC encoder");
        return;
    };
    let mut bytes = vec![0u8; BYTES_PER_FRAME];
    let mut samples = vec![0f32; SAMPLES_PER_FRAME * 2];
    let mut first_sample_at: Option<i64> = None;
    while !controls.stop.load(Ordering::Relaxed) {
        if recording.read(&mut bytes).is_err() {
            break;
        }
        let start = *first_sample_at.get_or_insert_with(|| epoch.elapsed().as_nanos() as i64 / 100 - frame_ticks(SAMPLES_PER_FRAME as i64));
        for (sample, raw) in samples.iter_mut().zip(bytes.as_chunks::<4>().0) {
            *sample = f32::from_le_bytes(*raw);
        }
        for (position, data) in encoder.encode(&samples) {
            emit(Captured::Audio { handle, timestamp: start + frame_ticks(position), data });
        }
    }
}

fn frame_ticks(samples: i64) -> i64 {
    samples * 10_000_000 / SAMPLE_RATE as i64
}
