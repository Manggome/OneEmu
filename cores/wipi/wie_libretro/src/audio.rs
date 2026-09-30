//! Renders wie's audio commands into the libretro sample stream.
//!
//! wie hands over whole parsed sequences (SMAF/MMF -> timed MIDI + PCM events) with `Play`/`Stop` and
//! leaves scheduling to the host. Here every frame renders exactly 1/60 s (735 frames @ 44.1 kHz),
//! dispatching each sequence's events at their sample position:
//!
//! * MIDI goes to a rustysynth synthesizer. Each playing sequence gets its **own** synthesizer (pooled,
//!   sharing one SoundFont), so background music and sound effects that both use channel 0 can't cut
//!   each other's notes off or steal each other's program changes.
//! * PCM (`Wave`) events become independent voices (linear resampling), so overlapping effects mix
//!   instead of queueing behind each other.
//! * A sequence that ends by itself releases its notes and keeps ringing for a short tail; `Stop`
//!   silences it at once.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs::File,
    io::BufReader,
    path::Path,
    sync::Arc,
};

use rustysynth::{SoundFont, Synthesizer, SynthesizerSettings};
use wie_backend::{AudioCommand, AudioEventData, AudioHandle, AudioSequence};

pub const SAMPLE_RATE: u32 = 44_100;
pub const FRAMES_PER_RUN: usize = (SAMPLE_RATE / 60) as usize; // 735
const MAX_PCM_VOICES: usize = 16;
const MAX_SYNTHS: usize = 4;
/// How long a naturally finished sequence keeps rendering so releases ring out (frames of 1/60 s).
const TAIL_RUNS: u32 = 30;

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct AudioSettings {
    pub midi_enabled: bool,
    pub polyphony: usize,
    pub reverb: bool,
    pub midi_gain: f32,
    pub pcm_gain: f32,
}

impl Default for AudioSettings {
    fn default() -> Self {
        Self {
            midi_enabled: true,
            polyphony: 32,
            reverb: false,
            midi_gain: 1.0,
            pcm_gain: 1.0,
        }
    }
}

struct Playback {
    sequence: Arc<AudioSequence>,
    repeat: bool,
    /// Samples rendered since the sequence (re)started.
    pos: u64,
    next_event: usize,
    synth: Option<Synthesizer>,
    active_notes: BTreeSet<(u8, u8)>,
    used_channels: BTreeSet<u8>,
    /// Some(n) once the sequence ended by itself: n more runs of release tail, then it is dropped.
    tail: Option<u32>,
    has_midi: bool,
}

struct PcmVoice {
    handle: AudioHandle,
    sequence: Arc<AudioSequence>,
    event: usize,
    channels: usize,
    /// Position in source frames.
    pos: f64,
    step: f64,
    /// Output samples to wait before starting (event lands mid-frame).
    delay: usize,
}

impl PcmVoice {
    fn samples(&self) -> &[i16] {
        match &self.sequence.events[self.event].data {
            AudioEventData::Wave { samples, .. } => samples,
            _ => &[],
        }
    }
}

pub struct Mixer {
    soundfont: Option<Arc<SoundFont>>,
    settings: AudioSettings,
    playbacks: BTreeMap<AudioHandle, Playback>,
    idle_synths: Vec<Synthesizer>,
    voices: Vec<PcmVoice>,
    left: Vec<f32>,
    right: Vec<f32>,
    tmp_l: Vec<f32>,
    tmp_r: Vec<f32>,
}

fn ms_to_samples(ms: u64) -> u64 {
    ms * SAMPLE_RATE as u64 / 1000
}

impl Mixer {
    pub fn new(soundfont_candidates: &[std::path::PathBuf], settings: AudioSettings) -> Self {
        let soundfont = soundfont_candidates.iter().find_map(|p| load_soundfont(p));
        if soundfont.is_none() {
            tracing::warn!("no SoundFont found ({soundfont_candidates:?}); MIDI music will be silent");
        }
        Self {
            soundfont,
            settings,
            playbacks: BTreeMap::new(),
            idle_synths: Vec::new(),
            voices: Vec::new(),
            left: vec![0.0; FRAMES_PER_RUN],
            right: vec![0.0; FRAMES_PER_RUN],
            tmp_l: vec![0.0; FRAMES_PER_RUN],
            tmp_r: vec![0.0; FRAMES_PER_RUN],
        }
    }

    pub fn has_soundfont(&self) -> bool {
        self.soundfont.is_some()
    }

    pub fn set_settings(&mut self, settings: AudioSettings) {
        if settings.polyphony != self.settings.polyphony || settings.reverb != self.settings.reverb {
            // Synth settings are fixed at construction; rebuild lazily.
            self.idle_synths.clear();
        }
        self.settings = settings;
    }

    fn take_synth(&mut self) -> Option<Synthesizer> {
        if !self.settings.midi_enabled {
            return None;
        }
        let font = self.soundfont.as_ref()?;
        let in_use = self.playbacks.values().filter(|p| p.synth.is_some()).count();
        if in_use >= MAX_SYNTHS {
            // Steal from the oldest finished-tail playback, else give up (the sequence plays without MIDI).
            let victim = self.playbacks.iter().find(|(_, p)| p.tail.is_some() && p.synth.is_some()).map(|(h, _)| *h)?;
            let mut synth = self.playbacks.get_mut(&victim)?.synth.take()?;
            synth.reset();
            return Some(synth);
        }
        if let Some(mut synth) = self.idle_synths.pop() {
            synth.reset();
            return Some(synth);
        }
        let mut settings = SynthesizerSettings::new(SAMPLE_RATE as i32);
        settings.maximum_polyphony = self.settings.polyphony;
        settings.enable_reverb_and_chorus = self.settings.reverb;
        match Synthesizer::new(font, &settings) {
            Ok(s) => Some(s),
            Err(e) => {
                tracing::warn!("synthesizer creation failed: {e}");
                None
            }
        }
    }

    fn release(&mut self, mut playback: Playback) {
        if let Some(synth) = playback.synth.take()
            && self.idle_synths.len() < MAX_SYNTHS
        {
            self.idle_synths.push(synth);
        }
    }

    pub fn command(&mut self, command: AudioCommand) {
        match command {
            AudioCommand::Play { handle, sequence, repeat } => {
                self.stop(handle);
                let has_midi = sequence.events.iter().any(|e| matches!(e.data, AudioEventData::Midi(_)));
                let synth = if has_midi { self.take_synth() } else { None };
                self.playbacks.insert(
                    handle,
                    Playback {
                        sequence,
                        repeat,
                        pos: 0,
                        next_event: 0,
                        synth,
                        active_notes: BTreeSet::new(),
                        used_channels: BTreeSet::new(),
                        tail: None,
                        has_midi,
                    },
                );
            }
            AudioCommand::Stop { handle } => self.stop(handle),
        }
    }

    fn stop(&mut self, handle: AudioHandle) {
        self.voices.retain(|v| v.handle != handle);
        if let Some(playback) = self.playbacks.remove(&handle) {
            self.release(playback);
        }
    }

    pub fn stop_all(&mut self) {
        let handles: Vec<_> = self.playbacks.keys().copied().collect();
        for h in handles {
            self.stop(h);
        }
        self.voices.clear();
    }

    /// Renders one run (1/60 s) as interleaved stereo i16 into `out` (cleared first).
    pub fn render(&mut self, out: &mut Vec<i16>) {
        let n = FRAMES_PER_RUN;
        self.left.iter_mut().for_each(|x| *x = 0.0);
        self.right.iter_mut().for_each(|x| *x = 0.0);

        let midi_gain = self.settings.midi_gain;
        let handles: Vec<AudioHandle> = self.playbacks.keys().copied().collect();
        let mut finished = Vec::new();
        let mut new_voices = Vec::new();

        for handle in handles {
            let Some(pb) = self.playbacks.get_mut(&handle) else { continue };
            let mut offset = 0usize; // samples of this run already rendered for this playback

            if pb.tail.is_none() {
                loop {
                    // Dispatch every event due at or before the current position.
                    while let Some(event) = pb.sequence.events.get(pb.next_event) {
                        let at = ms_to_samples(event.time);
                        if at > pb.pos + offset as u64 {
                            break;
                        }
                        match &event.data {
                            AudioEventData::Midi(data) => {
                                if let Some(synth) = pb.synth.as_mut() {
                                    send_midi(synth, data, &mut pb.active_notes, &mut pb.used_channels);
                                }
                            }
                            AudioEventData::Wave { channels, sampling_rate, samples } => {
                                if *channels > 0 && *sampling_rate > 0 && !samples.is_empty() {
                                    new_voices.push(PcmVoice {
                                        handle,
                                        sequence: pb.sequence.clone(),
                                        event: pb.next_event,
                                        channels: *channels as usize,
                                        pos: 0.0,
                                        step: *sampling_rate as f64 / SAMPLE_RATE as f64,
                                        delay: offset,
                                    });
                                }
                            }
                        }
                        pb.next_event += 1;
                    }

                    // Render up to the next event or the end of this run.
                    let until = pb
                        .sequence
                        .events
                        .get(pb.next_event)
                        .map(|e| ms_to_samples(e.time).saturating_sub(pb.pos))
                        .unwrap_or(u64::MAX)
                        .min(n as u64) as usize;
                    let until = until.max(offset);
                    if until > offset {
                        render_synth(pb.synth.as_mut(), &mut self.tmp_l, &mut self.tmp_r, &mut self.left, &mut self.right, offset, until, midi_gain);
                        offset = until;
                    }
                    // `until` reaches the end of the run once no event is left inside it.
                    if offset >= n {
                        break;
                    }
                }
                pb.pos += n as u64;

                let duration = ms_to_samples(pb.sequence.duration);
                if pb.next_event >= pb.sequence.events.len() && pb.pos >= duration {
                    if pb.repeat && pb.sequence.duration > 0 {
                        silence(pb);
                        pb.pos = 0;
                        pb.next_event = 0;
                    } else {
                        // Natural end: release notes, keep the synth ringing briefly.
                        if let Some(synth) = pb.synth.as_mut() {
                            for (ch, note) in &pb.active_notes {
                                synth.note_off(*ch as i32, *note as i32);
                            }
                        }
                        pb.active_notes.clear();
                        pb.tail = Some(if pb.has_midi { TAIL_RUNS } else { 0 });
                    }
                }
            } else {
                render_synth(pb.synth.as_mut(), &mut self.tmp_l, &mut self.tmp_r, &mut self.left, &mut self.right, 0, n, midi_gain);
                if let Some(t) = pb.tail.as_mut() {
                    if *t == 0 || pb.synth.is_none() {
                        finished.push(handle);
                    } else {
                        *t -= 1;
                    }
                }
            }
        }

        for handle in finished {
            // Keep PCM voices of the finished handle: they are independent and end on their own.
            if let Some(pb) = self.playbacks.remove(&handle) {
                self.release(pb);
            }
        }

        for v in new_voices {
            if self.voices.len() >= MAX_PCM_VOICES {
                self.voices.remove(0);
            }
            self.voices.push(v);
        }

        let pcm_gain = self.settings.pcm_gain;
        let (left, right) = (&mut self.left, &mut self.right);
        self.voices.retain_mut(|v| mix_voice(v, left, right, pcm_gain));

        out.clear();
        out.reserve(n * 2);
        for i in 0..n {
            out.push(to_i16(self.left[i]));
            out.push(to_i16(self.right[i]));
        }
    }
}

fn to_i16(x: f32) -> i16 {
    (x.clamp(-1.0, 1.0) * 32767.0) as i16
}

fn silence(pb: &mut Playback) {
    if let Some(synth) = pb.synth.as_mut() {
        for (ch, note) in &pb.active_notes {
            synth.note_off(*ch as i32, *note as i32);
        }
        for ch in &pb.used_channels {
            for control in [64, 120, 123] {
                synth.process_midi_message(*ch as i32, 0xb0, control, 0);
            }
        }
    }
    pb.active_notes.clear();
    pb.used_channels.clear();
}

fn send_midi(synth: &mut Synthesizer, data: &[u8], active: &mut BTreeSet<(u8, u8)>, used: &mut BTreeSet<u8>) {
    let Some(status) = data.first().copied() else { return };
    if !(0x80..0xf0).contains(&status) {
        return; // SysEx / meta: not meaningful for a GM soft synth
    }
    let channel = status & 0x0f;
    let command = status & 0xf0;
    let d1 = data.get(1).copied().unwrap_or(0);
    let d2 = data.get(2).copied().unwrap_or(0);
    used.insert(channel);
    match command {
        0x80 => {
            active.remove(&(channel, d1));
        }
        0x90 if d2 == 0 => {
            active.remove(&(channel, d1));
        }
        0x90 => {
            active.insert((channel, d1));
        }
        _ => {}
    }
    synth.process_midi_message(channel as i32, command as i32, d1 as i32, d2 as i32);
}

#[allow(clippy::too_many_arguments)]
fn render_synth(
    synth: Option<&mut Synthesizer>,
    tmp_l: &mut [f32],
    tmp_r: &mut [f32],
    left: &mut [f32],
    right: &mut [f32],
    from: usize,
    to: usize,
    gain: f32,
) {
    let Some(synth) = synth else { return };
    if to <= from {
        return;
    }
    let len = to - from;
    synth.render(&mut tmp_l[..len], &mut tmp_r[..len]);
    for i in 0..len {
        left[from + i] += tmp_l[i] * gain;
        right[from + i] += tmp_r[i] * gain;
    }
}

/// Mixes one PCM voice into the run. Returns false once the voice has finished.
fn mix_voice(v: &mut PcmVoice, left: &mut [f32], right: &mut [f32], gain: f32) -> bool {
    let n = left.len();
    let start = v.delay.min(n);
    v.delay -= start;
    let ch = v.channels;
    let step = v.step;
    let mut pos = v.pos;
    let samples = v.samples();
    let frames = samples.len() / ch;
    if frames == 0 {
        return false;
    }
    for i in start..n {
        let idx = pos as usize;
        if idx >= frames {
            v.pos = pos;
            return false;
        }
        let frac = (pos - idx as f64) as f32;
        let next = (idx + 1).min(frames - 1);
        let get = |frame: usize, c: usize| samples[frame * ch + c.min(ch - 1)] as f32 / 32768.0;
        let l = get(idx, 0) + (get(next, 0) - get(idx, 0)) * frac;
        let r = if ch > 1 { get(idx, 1) + (get(next, 1) - get(idx, 1)) * frac } else { l };
        left[i] += l * gain;
        right[i] += r * gain;
        pos += step;
    }
    v.pos = pos;
    (pos as usize) < frames
}

fn load_soundfont(path: &Path) -> Option<Arc<SoundFont>> {
    let file = File::open(path).ok()?;
    match SoundFont::new(&mut BufReader::new(file)) {
        Ok(sf) => {
            tracing::info!("SoundFont loaded: {}", path.display());
            Some(Arc::new(sf))
        }
        Err(e) => {
            tracing::warn!("invalid SoundFont {}: {e}", path.display());
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wie_backend::TimedAudioEvent;

    fn wave_seq(len: usize, at_ms: u64) -> Arc<AudioSequence> {
        Arc::new(AudioSequence {
            duration: at_ms + 50,
            events: vec![TimedAudioEvent {
                time: at_ms,
                data: AudioEventData::Wave {
                    channels: 1,
                    sampling_rate: SAMPLE_RATE,
                    samples: vec![16384; len],
                },
            }],
        })
    }

    #[test]
    fn renders_exactly_one_run() {
        let mut m = Mixer::new(&[], AudioSettings::default());
        let mut out = Vec::new();
        m.render(&mut out);
        assert_eq!(out.len(), FRAMES_PER_RUN * 2);
        assert!(out.iter().all(|x| *x == 0));
    }

    #[test]
    fn pcm_starts_at_its_event_time_and_overlaps() {
        let mut m = Mixer::new(&[], AudioSettings::default());
        m.command(AudioCommand::Play { handle: 1, sequence: wave_seq(10_000, 10), repeat: false });
        m.command(AudioCommand::Play { handle: 2, sequence: wave_seq(10_000, 0), repeat: false });
        let mut out = Vec::new();
        m.render(&mut out);
        let at10ms = ms_to_samples(10) as usize;
        // before 10 ms only voice 2 plays, after it both do
        assert!((out[0] as i32 - 16383).abs() < 4);
        assert!((out[(at10ms + 5) * 2] as i32 - 32767).abs() < 4);
    }

    #[test]
    fn stop_cuts_pcm() {
        let mut m = Mixer::new(&[], AudioSettings::default());
        m.command(AudioCommand::Play { handle: 7, sequence: wave_seq(100_000, 0), repeat: false });
        let mut out = Vec::new();
        m.render(&mut out);
        m.command(AudioCommand::Stop { handle: 7 });
        m.render(&mut out);
        assert!(out.iter().all(|x| *x == 0));
    }

    #[test]
    fn finished_sequence_is_dropped() {
        let mut m = Mixer::new(&[], AudioSettings::default());
        m.command(AudioCommand::Play { handle: 3, sequence: wave_seq(100, 0), repeat: false });
        let mut out = Vec::new();
        for _ in 0..10 {
            m.render(&mut out);
        }
        assert!(m.playbacks.is_empty());
        assert!(m.voices.is_empty());
    }
}
