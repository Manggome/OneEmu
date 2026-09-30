//! Maps libretro input (RetroPad, analog sticks, keyboard) onto the 24 feature-phone keys wie knows,
//! and turns the per-frame key set into Keydown / Keyrepeat / Keyup events.

use wie_backend::KeyCode;

use crate::ffi::*;

/// Every wie key, in bit order of [`KeySet`].
pub const ALL_KEYS: [KeyCode; 24] = [
    KeyCode::UP,
    KeyCode::DOWN,
    KeyCode::LEFT,
    KeyCode::RIGHT,
    KeyCode::OK,
    KeyCode::LEFT_SOFT_KEY,
    KeyCode::RIGHT_SOFT_KEY,
    KeyCode::CLEAR,
    KeyCode::CALL,
    KeyCode::HANGUP,
    KeyCode::VOLUME_UP,
    KeyCode::VOLUME_DOWN,
    KeyCode::NUM0,
    KeyCode::NUM1,
    KeyCode::NUM2,
    KeyCode::NUM3,
    KeyCode::NUM4,
    KeyCode::NUM5,
    KeyCode::NUM6,
    KeyCode::NUM7,
    KeyCode::NUM8,
    KeyCode::NUM9,
    KeyCode::HASH,
    KeyCode::STAR,
];

const NUMS: [KeyCode; 10] = [
    KeyCode::NUM0,
    KeyCode::NUM1,
    KeyCode::NUM2,
    KeyCode::NUM3,
    KeyCode::NUM4,
    KeyCode::NUM5,
    KeyCode::NUM6,
    KeyCode::NUM7,
    KeyCode::NUM8,
    KeyCode::NUM9,
];

fn bit(key: KeyCode) -> u32 {
    1 << ALL_KEYS.iter().position(|x| *x == key).unwrap_or(0)
}

#[derive(Clone, Copy, Default, PartialEq, Eq, Debug)]
pub struct KeySet(pub u32);

impl KeySet {
    pub fn press(&mut self, key: KeyCode) {
        self.0 |= bit(key);
    }

    pub fn contains(&self, key: KeyCode) -> bool {
        self.0 & bit(key) != 0
    }

    pub fn keys(&self) -> impl Iterator<Item = KeyCode> + '_ {
        ALL_KEYS.iter().copied().filter(|k| self.contains(*k))
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PadProfile {
    /// D-pad / left stick = arrows, A = OK. For games that read the navigation keys.
    Standard,
    /// D-pad / left stick = 2/4/6/8 (diagonals 1/3/7/9), A = 5. For games that move with the number keys.
    Numpad,
}

/// 8-way direction of a stick, or None inside the dead zone. (dx, dy) in {-1,0,1}, y down.
pub fn stick_direction(x: i16, y: i16, deadzone: f32) -> Option<(i8, i8)> {
    let fx = x as f32 / 32767.0;
    let fy = y as f32 / 32767.0;
    let mag = (fx * fx + fy * fy).sqrt();
    if mag < deadzone {
        return None;
    }
    // sin(22.5°): outside this band on an axis counts as pressed on that axis
    let t = 0.382_683_43 * mag;
    let dx = if fx > t { 1 } else if fx < -t { -1 } else { 0 };
    let dy = if fy > t { 1 } else if fy < -t { -1 } else { 0 };
    if dx == 0 && dy == 0 { None } else { Some((dx, dy)) }
}

/// The number key a direction maps to on a phone keypad (1 2 3 / 4 5 6 / 7 8 9).
pub fn direction_to_number(dx: i8, dy: i8) -> KeyCode {
    let col = (dx + 1) as usize; // 0..=2
    let row = (dy + 1) as usize; // 0..=2
    NUMS[1 + row * 3 + col]
}

pub struct PadState {
    pub buttons: u16,
    pub left: (i16, i16),
    pub right: (i16, i16),
}

impl PadState {
    fn held(&self, id: u32) -> bool {
        self.buttons & (1 << id) != 0
    }
}

/// RetroPad + both sticks -> phone keys.
pub fn map_pad(pad: &PadState, profile: PadProfile, deadzone: f32) -> KeySet {
    let mut set = KeySet::default();

    let mut dx = 0i8;
    let mut dy = 0i8;
    if pad.held(RETRO_DEVICE_ID_JOYPAD_LEFT) {
        dx -= 1;
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_RIGHT) {
        dx += 1;
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_UP) {
        dy -= 1;
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_DOWN) {
        dy += 1;
    }
    if dx == 0
        && dy == 0
        && let Some((sx, sy)) = stick_direction(pad.left.0, pad.left.1, deadzone)
    {
        dx = sx;
        dy = sy;
    }

    match profile {
        PadProfile::Standard => {
            if dx < 0 {
                set.press(KeyCode::LEFT);
            }
            if dx > 0 {
                set.press(KeyCode::RIGHT);
            }
            if dy < 0 {
                set.press(KeyCode::UP);
            }
            if dy > 0 {
                set.press(KeyCode::DOWN);
            }
            if pad.held(RETRO_DEVICE_ID_JOYPAD_A) {
                set.press(KeyCode::OK);
            }
            if pad.held(RETRO_DEVICE_ID_JOYPAD_X) {
                set.press(KeyCode::NUM5);
            }
        }
        PadProfile::Numpad => {
            if dx != 0 || dy != 0 {
                set.press(direction_to_number(dx, dy));
            }
            if pad.held(RETRO_DEVICE_ID_JOYPAD_A) {
                set.press(KeyCode::NUM5);
            }
            if pad.held(RETRO_DEVICE_ID_JOYPAD_X) {
                set.press(KeyCode::OK);
            }
        }
    }

    if pad.held(RETRO_DEVICE_ID_JOYPAD_B) {
        set.press(KeyCode::CLEAR);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_Y) {
        set.press(KeyCode::NUM0);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_L) || pad.held(RETRO_DEVICE_ID_JOYPAD_START) {
        set.press(KeyCode::LEFT_SOFT_KEY);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_R) || pad.held(RETRO_DEVICE_ID_JOYPAD_SELECT) {
        set.press(KeyCode::RIGHT_SOFT_KEY);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_L2) {
        set.press(KeyCode::STAR);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_R2) {
        set.press(KeyCode::HASH);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_L3) {
        set.press(KeyCode::NUM0);
    }
    if pad.held(RETRO_DEVICE_ID_JOYPAD_R3) {
        set.press(KeyCode::NUM5);
    }
    // Right stick: a ring of number keys around 5.
    if let Some((rx, ry)) = stick_direction(pad.right.0, pad.right.1, deadzone) {
        set.press(direction_to_number(rx, ry));
    }

    set
}

/// Keyboard keys this core reads, and what they press.
pub const KEYBOARD_MAP: &[(u32, KeyCode)] = &[
    (RETROK_0, KeyCode::NUM0),
    (RETROK_0 + 1, KeyCode::NUM1),
    (RETROK_0 + 2, KeyCode::NUM2),
    (RETROK_0 + 3, KeyCode::NUM3),
    (RETROK_0 + 4, KeyCode::NUM4),
    (RETROK_0 + 5, KeyCode::NUM5),
    (RETROK_0 + 6, KeyCode::NUM6),
    (RETROK_0 + 7, KeyCode::NUM7),
    (RETROK_0 + 8, KeyCode::NUM8),
    (RETROK_0 + 9, KeyCode::NUM9),
    (RETROK_KP0, KeyCode::NUM0),
    (RETROK_KP0 + 1, KeyCode::NUM1),
    (RETROK_KP0 + 2, KeyCode::NUM2),
    (RETROK_KP0 + 3, KeyCode::NUM3),
    (RETROK_KP0 + 4, KeyCode::NUM4),
    (RETROK_KP0 + 5, KeyCode::NUM5),
    (RETROK_KP0 + 6, KeyCode::NUM6),
    (RETROK_KP0 + 7, KeyCode::NUM7),
    (RETROK_KP0 + 8, KeyCode::NUM8),
    (RETROK_KP0 + 9, KeyCode::NUM9),
    (RETROK_ASTERISK, KeyCode::STAR),
    (RETROK_KP_MULTIPLY, KeyCode::STAR),
    (RETROK_HASH, KeyCode::HASH),
    (RETROK_KP_DIVIDE, KeyCode::HASH),
    (RETROK_UP, KeyCode::UP),
    (RETROK_DOWN, KeyCode::DOWN),
    (RETROK_LEFT, KeyCode::LEFT),
    (RETROK_RIGHT, KeyCode::RIGHT),
    (RETROK_RETURN, KeyCode::OK),
    (RETROK_KP_ENTER, KeyCode::OK),
    (RETROK_SPACE, KeyCode::OK),
    (RETROK_BACKSPACE, KeyCode::CLEAR),
    (RETROK_F1, KeyCode::LEFT_SOFT_KEY),
    (RETROK_F2, KeyCode::RIGHT_SOFT_KEY),
    (RETROK_F3, KeyCode::CALL),
    (RETROK_F4, KeyCode::HANGUP),
    (RETROK_PAGEUP, KeyCode::VOLUME_UP),
    (RETROK_PAGEDOWN, KeyCode::VOLUME_DOWN),
];

pub enum KeyEvent {
    Down(KeyCode),
    Repeat(KeyCode),
    Up(KeyCode),
}

/// Edge detection + auto-repeat, counted in frames so it follows game time (pause / fast-forward).
pub struct KeyTracker {
    held: KeySet,
    /// Frames each held key has been down, indexed like ALL_KEYS.
    frames_held: [u32; 24],
    /// Repeat period in frames; 0 = no repeat.
    pub repeat_frames: u32,
}

impl KeyTracker {
    pub fn new(repeat_frames: u32) -> Self {
        Self {
            held: KeySet::default(),
            frames_held: [0; 24],
            repeat_frames,
        }
    }

    pub fn update(&mut self, now: KeySet, out: &mut Vec<KeyEvent>) {
        for (i, key) in ALL_KEYS.iter().copied().enumerate() {
            let was = self.held.contains(key);
            let is = now.contains(key);
            match (was, is) {
                (false, true) => {
                    out.push(KeyEvent::Down(key));
                    self.frames_held[i] = 0;
                }
                (true, true) => {
                    self.frames_held[i] += 1;
                    if self.repeat_frames > 0 && self.frames_held[i] % self.repeat_frames == 0 {
                        out.push(KeyEvent::Repeat(key));
                    }
                }
                (true, false) => out.push(KeyEvent::Up(key)),
                (false, false) => {}
            }
        }
        self.held = now;
    }

    /// Releases everything (used on reset / unload).
    pub fn release_all(&mut self, out: &mut Vec<KeyEvent>) {
        self.update(KeySet::default(), out);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pad(buttons: &[u32]) -> PadState {
        PadState {
            buttons: buttons.iter().fold(0, |acc, b| acc | (1 << b)),
            left: (0, 0),
            right: (0, 0),
        }
    }

    #[test]
    fn standard_profile() {
        let set = map_pad(&pad(&[RETRO_DEVICE_ID_JOYPAD_UP, RETRO_DEVICE_ID_JOYPAD_A]), PadProfile::Standard, 0.35);
        assert!(set.contains(KeyCode::UP));
        assert!(set.contains(KeyCode::OK));
        assert!(!set.contains(KeyCode::NUM2));
    }

    #[test]
    fn numpad_profile_uses_diagonals() {
        let set = map_pad(&pad(&[RETRO_DEVICE_ID_JOYPAD_UP, RETRO_DEVICE_ID_JOYPAD_LEFT]), PadProfile::Numpad, 0.35);
        assert_eq!(set.keys().collect::<Vec<_>>(), vec![KeyCode::NUM1]);
        let set = map_pad(&pad(&[RETRO_DEVICE_ID_JOYPAD_DOWN]), PadProfile::Numpad, 0.35);
        assert_eq!(set.keys().collect::<Vec<_>>(), vec![KeyCode::NUM8]);
    }

    #[test]
    fn right_stick_is_a_number_ring() {
        let mut p = pad(&[]);
        p.right = (32767, 0);
        assert!(map_pad(&p, PadProfile::Standard, 0.35).contains(KeyCode::NUM6));
        p.right = (-30000, -30000);
        assert!(map_pad(&p, PadProfile::Standard, 0.35).contains(KeyCode::NUM1));
        p.right = (3000, 2000); // inside the dead zone
        assert_eq!(map_pad(&p, PadProfile::Standard, 0.35), KeySet::default());
    }

    #[test]
    fn left_stick_drives_arrows() {
        let mut p = pad(&[]);
        p.left = (0, 32767);
        assert!(map_pad(&p, PadProfile::Standard, 0.35).contains(KeyCode::DOWN));
    }

    #[test]
    fn tracker_edges_and_repeat() {
        let mut t = KeyTracker::new(3);
        let mut out = Vec::new();
        let mut ok = KeySet::default();
        ok.press(KeyCode::OK);

        t.update(ok, &mut out);
        assert!(matches!(out.as_slice(), [KeyEvent::Down(KeyCode::OK)]));
        out.clear();
        t.update(ok, &mut out);
        t.update(ok, &mut out);
        assert!(out.is_empty());
        t.update(ok, &mut out);
        assert!(matches!(out.as_slice(), [KeyEvent::Repeat(KeyCode::OK)]));
        out.clear();
        t.update(KeySet::default(), &mut out);
        assert!(matches!(out.as_slice(), [KeyEvent::Up(KeyCode::OK)]));
    }
}
