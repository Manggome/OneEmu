//! Core options (libretro v0 variables; OneEmu and RetroArch both show them) and how they combine
//! with the per-game quirk table: an option left on "auto" takes the quirk's value, else the default.

use crate::{archive::Carrier, audio::AudioSettings, input::PadProfile, quirks::Quirk};

/// (key, "설명; 값1|값2|..."), first value is the default.
pub const VARIABLES: &[(&str, &str)] = &[
    ("wipi_carrier", "통신사 강제 지정 (다시 시작 필요); auto|ktf|lgt|skt|j2me"),
    ("wipi_pad_profile", "게임패드 배치 (auto: 게임별 설정); auto|standard|numpad"),
    ("wipi_stick_deadzone", "스틱 데드존 (%); 35|20|25|30|40|50"),
    ("wipi_key_repeat", "키 반복 간격 (ms); auto|100|50|150|200|off"),
    ("wipi_speed", "게임 속도 (%, 타이머 기준); auto|100|50|75|90|110|125|150|200"),
    ("wipi_cpu_budget", "프레임당 CPU 시간 (ms, 높을수록 느린 게임이 빨라지지만 배터리 소모); auto|12|8|16|24|33"),
    ("wipi_date", "게임 속 날짜 (auto: 게임별 설정 또는 오늘); auto|system|2005-01-01|2008-01-01|2010-01-01|2012-01-01"),
    ("wipi_midi", "배경음악 (MIDI 신디사이저); on|off"),
    ("wipi_midi_polyphony", "MIDI 동시 발음 수; auto|32|16|24|48|64"),
    ("wipi_reverb", "MIDI 리버브/코러스 (CPU 사용 증가); off|on"),
    ("wipi_midi_volume", "배경음악 볼륨 (%); auto|100|0|25|50|75|125|150|200"),
    ("wipi_pcm_volume", "효과음 볼륨 (%); auto|100|0|25|50|75|125|150|200"),
];

/// Raw option values as reported by the frontend (None = not reported / unknown).
#[derive(Clone, Default, PartialEq, Debug)]
pub struct RawOptions {
    pub values: Vec<(String, String)>,
}

impl RawOptions {
    pub fn get(&self, key: &str) -> Option<&str> {
        self.values.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }

    fn explicit(&self, key: &str) -> Option<&str> {
        self.get(key).filter(|v| *v != "auto")
    }
}

#[derive(Clone, PartialEq, Debug)]
pub struct Config {
    pub carrier: Option<Carrier>,
    pub pad_profile: PadProfile,
    pub deadzone: f32,
    /// 0 = off
    pub key_repeat_ms: u32,
    pub speed_percent: u32,
    pub cpu_budget_ms: u32,
    /// None = today's date (wall clock at load).
    pub fixed_date: Option<String>,
    pub audio: AudioSettings,
}

fn percent(v: Option<u32>, default: u32) -> f32 {
    v.unwrap_or(default) as f32 / 100.0
}

impl Config {
    pub fn resolve(raw: &RawOptions, quirk: Option<&Quirk>) -> Self {
        let q = quirk.cloned().unwrap_or_default();

        let carrier = raw.explicit("wipi_carrier").and_then(Carrier::parse).or_else(|| q.carrier.as_deref().and_then(Carrier::parse));

        let pad_profile = match raw.explicit("wipi_pad_profile").or(q.pad_profile.as_deref()) {
            Some("numpad") => PadProfile::Numpad,
            _ => PadProfile::Standard,
        };

        let deadzone = raw.get("wipi_stick_deadzone").and_then(|v| v.parse::<u32>().ok()).unwrap_or(35) as f32 / 100.0;

        let key_repeat_ms = match raw.explicit("wipi_key_repeat") {
            Some("off") => 0,
            Some(v) => v.parse().unwrap_or(100),
            None => q.key_repeat_ms.unwrap_or(100),
        };

        let speed_percent = raw.explicit("wipi_speed").and_then(|v| v.parse().ok()).or(q.speed).unwrap_or(100).clamp(10, 400);
        let cpu_budget_ms = raw.explicit("wipi_cpu_budget").and_then(|v| v.parse().ok()).or(q.cpu_budget_ms).unwrap_or(12).clamp(2, 50);

        let fixed_date = match raw.explicit("wipi_date") {
            Some("system") => None,
            Some(v) => Some(v.to_string()),
            None => q.fixed_date.clone(),
        };

        let audio = AudioSettings {
            midi_enabled: raw.get("wipi_midi") != Some("off"),
            polyphony: raw.explicit("wipi_midi_polyphony").and_then(|v| v.parse().ok()).or(q.midi_polyphony).unwrap_or(32).clamp(4, 128),
            reverb: raw.get("wipi_reverb") == Some("on"),
            midi_gain: percent(raw.explicit("wipi_midi_volume").and_then(|v| v.parse().ok()).or(q.midi_volume), 100),
            pcm_gain: percent(raw.explicit("wipi_pcm_volume").and_then(|v| v.parse().ok()).or(q.pcm_volume), 100),
        };

        Self {
            carrier,
            pad_profile,
            deadzone,
            key_repeat_ms,
            speed_percent,
            cpu_budget_ms,
            fixed_date,
            audio,
        }
    }

    /// Key repeat period in 60 Hz frames (0 = off).
    pub fn repeat_frames(&self) -> u32 {
        if self.key_repeat_ms == 0 { 0 } else { (self.key_repeat_ms * 60).div_ceil(1000).max(1) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raw(pairs: &[(&str, &str)]) -> RawOptions {
        RawOptions {
            values: pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
        }
    }

    #[test]
    fn defaults() {
        let c = Config::resolve(&RawOptions::default(), None);
        assert_eq!(c.pad_profile, PadProfile::Standard);
        assert_eq!(c.speed_percent, 100);
        assert_eq!(c.cpu_budget_ms, 12);
        assert_eq!(c.repeat_frames(), 6);
        assert!(c.fixed_date.is_none());
        assert!(c.carrier.is_none());
    }

    #[test]
    fn auto_takes_quirk_explicit_wins() {
        let quirk = Quirk {
            pad_profile: Some("numpad".into()),
            speed: Some(90),
            fixed_date: Some("2010-05-01".into()),
            ..Default::default()
        };
        let c = Config::resolve(&raw(&[("wipi_pad_profile", "auto"), ("wipi_speed", "150")]), Some(&quirk));
        assert_eq!(c.pad_profile, PadProfile::Numpad);
        assert_eq!(c.speed_percent, 150);
        assert_eq!(c.fixed_date.as_deref(), Some("2010-05-01"));
        let c = Config::resolve(&raw(&[("wipi_date", "system")]), Some(&quirk));
        assert!(c.fixed_date.is_none());
    }

    #[test]
    fn variables_are_well_formed() {
        for (key, desc) in VARIABLES {
            assert!(key.starts_with("wipi_"));
            let (_, values) = desc.split_once("; ").expect("description; values");
            assert!(values.split('|').count() >= 2, "{key}");
        }
    }
}
