//! Memory cheats for games with an emulated ARM address space (KTF / LGT): a value search the frontend drives
//! step by step ("치트 찾기"), and `retro_cheat_set` codes that keep values fixed every frame.
//!
//! Code format, one or more joined with '+': `AAAAAAAA:VV` (1 byte), `AAAAAAAA:VVVV` (2 bytes) or
//! `AAAAAAAA:VVVVVVVV` (4 bytes), hex, little-endian like the guest. A space works in place of ':'.

use wie_backend::GuestMemory;

/// Candidates kept by a search; a first search for a very common value (0, 1) stops here.
const MAX_CANDIDATES: usize = 4_000_000;
const CHUNK: usize = 0x10000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SearchOp {
    /// New search for this exact value.
    Start(u32),
    /// Keep addresses that now hold this value.
    Equal(u32),
    Changed,
    Unchanged,
    Increased,
    Decreased,
}

impl SearchOp {
    /// Frontend encoding: op 0 = start, 1 = equal, 2 = changed, 3 = unchanged, 4 = increased, 5 = decreased.
    pub fn from_code(op: i32, value: u32) -> Option<Self> {
        Some(match op {
            0 => Self::Start(value),
            1 => Self::Equal(value),
            2 => Self::Changed,
            3 => Self::Unchanged,
            4 => Self::Increased,
            5 => Self::Decreased,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Lock {
    pub address: u32,
    pub size: u8,
    pub value: u32,
}

#[derive(Default)]
pub struct Cheats {
    size: u8,
    /// (address, value at the last step), in address order.
    candidates: Vec<(u32, u32)>,
    /// retro_cheat_set index -> locks.
    locks: Vec<(u32, Vec<Lock>)>,
}

fn mask(size: u8) -> u32 {
    match size {
        1 => 0xff,
        2 => 0xffff,
        _ => u32::MAX,
    }
}

fn decode(bytes: &[u8], size: u8) -> u32 {
    match size {
        1 => u32::from(bytes[0]),
        2 => u32::from(u16::from_le_bytes([bytes[0], bytes[1]])),
        _ => u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
    }
}

pub fn read_value(memory: &dyn GuestMemory, address: u32, size: u8) -> Option<u32> {
    let mut buf = [0u8; 4];
    memory.read(address, &mut buf[..size as usize]).ok()?;
    Some(decode(&buf, size))
}

pub fn write_value(memory: &mut dyn GuestMemory, address: u32, size: u8, value: u32) -> bool {
    memory.write(address, &value.to_le_bytes()[..size as usize]).is_ok()
}

/// Parses one `retro_cheat_set` code into locks (None if any part is malformed).
pub fn parse_code(code: &str) -> Option<Vec<Lock>> {
    let mut locks = Vec::new();
    for part in code.split('+').map(str::trim).filter(|p| !p.is_empty()) {
        let (address, value) = part.split_once([':', ' '])?;
        let value = value.trim();
        let size = match value.len() {
            1..=2 => 1,
            3..=4 => 2,
            5..=8 => 4,
            _ => return None,
        };
        locks.push(Lock {
            address: u32::from_str_radix(address.trim(), 16).ok()?,
            size,
            value: u32::from_str_radix(value, 16).ok()?,
        });
    }
    (!locks.is_empty()).then_some(locks)
}

impl Cheats {
    /// Runs one search step and returns how many candidates are left.
    pub fn search(&mut self, memory: &dyn GuestMemory, op: SearchOp, size: u8) -> usize {
        if let SearchOp::Start(value) = op {
            self.size = match size {
                1 | 2 => size,
                _ => 4,
            };
            self.candidates.clear();
            let value = value & mask(self.size);
            let step = self.size as usize;
            let mut buf = vec![0u8; CHUNK];
            'ranges: for (start, len) in memory.mapped_ranges() {
                let mut offset = 0usize;
                while offset < len as usize {
                    let n = CHUNK.min(len as usize - offset);
                    let address = start.wrapping_add(offset as u32);
                    if memory.read(address, &mut buf[..n]).is_err() {
                        offset += n;
                        continue;
                    }
                    let mut i = 0;
                    while i + step <= n {
                        if decode(&buf[i..], self.size) == value {
                            self.candidates.push((address.wrapping_add(i as u32), value));
                            if self.candidates.len() >= MAX_CANDIDATES {
                                break 'ranges;
                            }
                        }
                        i += step;
                    }
                    offset += n;
                }
            }
            return self.candidates.len();
        }

        let size = self.size;
        let m = mask(size);
        self.candidates.retain_mut(|(address, last)| {
            let Some(now) = read_value(memory, *address, size) else {
                return false;
            };
            let keep = match op {
                SearchOp::Equal(v) => now == v & m,
                SearchOp::Changed => now != *last,
                SearchOp::Unchanged => now == *last,
                SearchOp::Increased => now > *last,
                SearchOp::Decreased => now < *last,
                SearchOp::Start(_) => unreachable!(),
            };
            *last = now;
            keep
        });
        self.candidates.len()
    }

    /// Up to `max` candidates as (address, current value).
    pub fn results(&self, memory: &dyn GuestMemory, max: usize) -> Vec<(u32, u32)> {
        self.candidates
            .iter()
            .take(max)
            .map(|&(address, last)| (address, read_value(memory, address, self.size).unwrap_or(last)))
            .collect()
    }

    pub fn search_size(&self) -> u8 {
        self.size
    }

    pub fn set_code(&mut self, index: u32, enabled: bool, code: &str) {
        self.locks.retain(|(i, _)| *i != index);
        if !enabled {
            return;
        }
        match parse_code(code) {
            Some(locks) => self.locks.push((index, locks)),
            None => tracing::warn!("cheat {index}: unreadable code {code:?} (expected AAAAAAAA:VALUE)"),
        }
    }

    pub fn reset_codes(&mut self) {
        self.locks.clear();
    }

    /// Writes every enabled lock (called after each frame's emulation).
    pub fn apply(&self, memory: &mut dyn GuestMemory) {
        for lock in self.locks.iter().flat_map(|(_, l)| l) {
            write_value(memory, lock.address, lock.size, lock.value);
        }
    }

    pub fn has_locks(&self) -> bool {
        !self.locks.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Ram {
        base: u32,
        bytes: Vec<u8>,
    }

    impl GuestMemory for Ram {
        fn mapped_ranges(&self) -> Vec<(u32, u32)> {
            vec![(self.base, self.bytes.len() as u32)]
        }

        fn read(&self, address: u32, buf: &mut [u8]) -> wie_util::Result<()> {
            let o = (address - self.base) as usize;
            buf.copy_from_slice(&self.bytes[o..o + buf.len()]);
            Ok(())
        }

        fn write(&mut self, address: u32, data: &[u8]) -> wie_util::Result<()> {
            let o = (address - self.base) as usize;
            self.bytes[o..o + data.len()].copy_from_slice(data);
            Ok(())
        }
    }

    #[test]
    fn search_narrows_to_the_changing_value() {
        let mut ram = Ram { base: 0x4000_0000, bytes: vec![0; 0x100] };
        ram.write(0x4000_0010, &1500u32.to_le_bytes()).unwrap();
        ram.write(0x4000_0040, &1500u32.to_le_bytes()).unwrap();
        let mut cheats = Cheats::default();
        assert_eq!(cheats.search(&ram, SearchOp::Start(1500), 4), 2);
        ram.write(0x4000_0010, &1200u32.to_le_bytes()).unwrap();
        assert_eq!(cheats.search(&ram, SearchOp::Decreased, 4), 1);
        assert_eq!(cheats.results(&ram, 10), vec![(0x4000_0010, 1200)]);
        assert_eq!(cheats.search(&ram, SearchOp::Equal(1200), 4), 1);
    }

    #[test]
    fn codes_lock_values_by_width() {
        assert_eq!(
            parse_code("40000010:05DC+40000020 FF"),
            Some(vec![
                Lock { address: 0x4000_0010, size: 2, value: 0x5dc },
                Lock { address: 0x4000_0020, size: 1, value: 0xff },
            ])
        );
        assert_eq!(parse_code("nonsense"), None);
        let mut ram = Ram { base: 0x4000_0000, bytes: vec![0; 0x40] };
        let mut cheats = Cheats::default();
        cheats.set_code(0, true, "40000010:0001869F");
        cheats.apply(&mut ram);
        assert_eq!(read_value(&ram, 0x4000_0010, 4), Some(99_999));
        cheats.set_code(0, false, "");
        assert!(!cheats.has_locks());
    }
}
