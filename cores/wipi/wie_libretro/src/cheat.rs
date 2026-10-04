//! Memory cheats for games with an emulated ARM address space (KTF / LGT): a value search the frontend drives
//! step by step ("치트 찾기"), and `retro_cheat_set` codes that keep values fixed every frame or write them once.
//!
//! Codes (one `retro_cheat_set` string may hold several, joined with '+', ';' or line breaks):
//! - `AAAAAAAA:VV` / `AAAAAAAA:VVVV` / `AAAAAAAA:VVVVVVVV` — raw address, hex value, width from the digit count
//!   (1, 2 or 4 bytes), little-endian like the guest. A space works in place of ':'.
//! - `P:BASE>OFF>OFF:VALUE` — pointer chain for native (WIPI-C) games: start at BASE (hex, an address in the
//!   game binary's data, which stays put between runs); each `>OFF` reads the 32-bit pointer at the current
//!   address and adds OFF (hex, may be negative). `P:BASE:VALUE` writes BASE itself. 4 bytes; `P1:` / `P2:`
//!   write 1 or 2 bytes.
//! - `J:pkg/Class.staticField.field[3].field:VALUE` — Java field path for KTF / LGT Java games, resolved by name
//!   every time: a static field, then instance fields of the objects it references and array elements
//!   (`[index]`). Packages are separated with '/'. Width and type come from the final field's descriptor.
//!   Obfuscated classes can declare several fields of one name with different types: `a(I)`, `a(Lcs)`,
//!   `a([B)` pick one by descriptor (written without the trailing ';'). LGT binaries keep no names for an
//!   application class's static fields: those are `$N`, the class's Nth static word.
//! - VALUE of `P:` / `J:` codes is decimal (`-5` allowed) or `0x` hex; float and double fields take `1.5`.
//! - Prefix `once:` writes the value a single time (as soon as the location resolves) and then leaves it to the
//!   game; without it the value is written after every frame (locked).

use std::collections::BTreeMap;

use wie_backend::{GuestMemory, JavaHeap, JavaSlot};

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

/// A field by name, and by descriptor when the name alone is ambiguous.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FieldRef {
    pub name: String,
    /// Full descriptor (`I`, `Lcs;`, `[Lcs;`).
    pub descriptor: Option<String>,
}

impl FieldRef {
    pub fn matches(&self, slot: &JavaSlot) -> bool {
        slot.name == self.name && self.descriptor.as_ref().is_none_or(|d| *d == slot.descriptor)
    }
}

impl std::fmt::Display for FieldRef {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.descriptor {
            Some(d) => write!(f, "{}({})", self.name, d.trim_end_matches(';')),
            None => f.write_str(&self.name),
        }
    }
}

/// One step of a Java field path after the static field.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PathStep {
    Field(FieldRef),
    Index(u32),
}

/// Where a code writes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Locator {
    Address(u32),
    /// Start at `base`; for each offset, read the 32-bit pointer at the current address and add the offset.
    Pointer {
        base: u32,
        offsets: Vec<i32>,
    },
    /// Static field `field` of `class`, then `path`.
    Java {
        class: String,
        field: FieldRef,
        path: Vec<PathStep>,
    },
}

impl std::fmt::Display for Locator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Address(address) => write!(f, "P:{address:08X}"),
            Self::Pointer { base, offsets } => {
                write!(f, "P:{base:08X}")?;
                for offset in offsets {
                    match *offset < 0 {
                        true => write!(f, ">-{:X}", offset.unsigned_abs())?,
                        false => write!(f, ">{offset:X}")?,
                    }
                }
                Ok(())
            }
            Self::Java { class, field, path } => {
                write!(f, "J:{class}.{field}")?;
                for step in path {
                    match step {
                        PathStep::Field(field) => write!(f, ".{field}")?,
                        PathStep::Index(index) => write!(f, "[{index}]")?,
                    }
                }
                Ok(())
            }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Value {
    Int(i64),
    Float(f64),
}

#[derive(Clone, Debug, PartialEq)]
pub struct Code {
    pub locator: Locator,
    /// Bytes to write; None = taken from the Java field's type.
    pub size: Option<u8>,
    pub value: Value,
    pub once: bool,
}

/// Value type of a resolved location.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Int,
    Float,
}

/// A location a code resolved to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Resolved {
    pub address: u32,
    pub size: u8,
    pub kind: Kind,
}

/// One enabled code's current state, for tools.
#[derive(Clone, Debug, PartialEq)]
pub struct CodeStatus {
    pub index: u32,
    pub code: Code,
    /// A `once:` code that has been written.
    pub done: bool,
    pub at: Option<Resolved>,
    pub now: Option<Value>,
}

#[derive(Default)]
pub struct Cheats {
    size: u8,
    /// (address, value at the last step), in address order.
    candidates: Vec<(u32, u32)>,
    /// retro_cheat_set index -> codes, each with whether it was written (what ends a `once:` code).
    codes: BTreeMap<u32, Vec<(Code, bool)>>,
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

fn parse_hex(text: &str) -> Option<u32> {
    let text = text.trim();
    let text = text.strip_prefix("0x").or_else(|| text.strip_prefix("0X")).unwrap_or(text);
    u32::from_str_radix(text, 16).ok()
}

fn parse_offset(text: &str) -> Option<i32> {
    let text = text.trim();
    match text.strip_prefix('-') {
        Some(rest) => Some(-(parse_hex(rest)? as i64) as i32),
        None => parse_hex(text).map(|x| x as i32),
    }
}

fn parse_value(text: &str) -> Option<Value> {
    let text = text.trim();
    let (negative, digits) = match text.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, text),
    };
    if let Some(hex) = digits.strip_prefix("0x").or_else(|| digits.strip_prefix("0X")) {
        let v = i64::from_str_radix(hex, 16).ok()?;
        return Some(Value::Int(if negative { -v } else { v }));
    }
    if let Ok(v) = text.parse::<i64>() {
        return Some(Value::Int(v));
    }
    text.parse::<f64>().ok().filter(|x| x.is_finite()).map(Value::Float)
}

fn is_identifier(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '$')
}

/// `name(DESC)[1][2]` -> (field, [1, 2]).
fn parse_path_token(token: &str) -> Option<(FieldRef, Vec<u32>)> {
    let (name, mut rest) = token.split_at(token.find(['(', '[']).unwrap_or(token.len()));
    if !is_identifier(name) {
        return None;
    }
    let mut descriptor = None;
    if let Some(inner) = rest.strip_prefix('(') {
        let close = inner.find(')')?;
        let text = inner[..close].trim();
        let element = text.trim_start_matches('[');
        if element.is_empty() {
            return None;
        }
        // object types are written without their ';'
        descriptor = Some(if element.starts_with('L') && !text.ends_with(';') {
            format!("{text};")
        } else {
            text.to_string()
        });
        rest = &inner[close + 1..];
    }
    let field = FieldRef {
        name: name.to_string(),
        descriptor,
    };
    let mut indices = Vec::new();
    while let Some(inner) = rest.strip_prefix('[') {
        let close = inner.find(']')?;
        indices.push(inner[..close].trim().parse().ok()?);
        rest = &inner[close + 1..];
    }
    rest.is_empty().then_some((field, indices))
}

fn parse_java(body: &str) -> Option<(Locator, Value)> {
    let (path, value) = body.rsplit_once(':')?;
    let (class, rest) = path.trim().split_once('.')?;
    if !class.split('/').all(is_identifier) {
        return None;
    }
    let mut tokens = rest.split('.');
    let (field, first_indices) = parse_path_token(tokens.next()?)?;
    let mut path: Vec<PathStep> = first_indices.into_iter().map(PathStep::Index).collect();
    for token in tokens {
        let (field, indices) = parse_path_token(token)?;
        path.push(PathStep::Field(field));
        path.extend(indices.into_iter().map(PathStep::Index));
    }
    let locator = Locator::Java {
        class: class.to_string(),
        field,
        path,
    };
    Some((locator, parse_value(value)?))
}

fn parse_pointer(body: &str) -> Option<(Locator, Value)> {
    let (chain, value) = body.rsplit_once(':')?;
    let mut parts = chain.split('>');
    let base = parse_hex(parts.next()?)?;
    let offsets = parts.map(parse_offset).collect::<Option<Vec<_>>>()?;
    let locator = if offsets.is_empty() {
        Locator::Address(base)
    } else {
        Locator::Pointer { base, offsets }
    };
    Some((locator, parse_value(value)?))
}

fn strip_prefix_ignore_case<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    let head = text.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix).then(|| &text[prefix.len()..])
}

/// Parses one code (no separators).
pub fn parse_one(part: &str) -> Option<Code> {
    let part = part.trim();
    let (once, part) = match strip_prefix_ignore_case(part, "once:") {
        Some(rest) => (true, rest.trim()),
        None => (false, part),
    };
    if let Some(body) = strip_prefix_ignore_case(part, "J:") {
        let (locator, value) = parse_java(body)?;
        return Some(Code {
            locator,
            size: None,
            value,
            once,
        });
    }
    for (prefix, size) in [("P:", 4u8), ("P4:", 4), ("P2:", 2), ("P1:", 1)] {
        if let Some(body) = strip_prefix_ignore_case(part, prefix) {
            let (locator, value) = parse_pointer(body)?;
            return Some(Code {
                locator,
                size: Some(size),
                value,
                once,
            });
        }
    }
    // Raw `AAAAAAAA:HEX`.
    let (address, value) = part.split_once([':', ' '])?;
    let value = value.trim();
    let size = match value.len() {
        1..=2 => 1,
        3..=4 => 2,
        5..=8 => 4,
        _ => return None,
    };
    Some(Code {
        locator: Locator::Address(u32::from_str_radix(address.trim(), 16).ok()?),
        size: Some(size),
        value: Value::Int(i64::from(u32::from_str_radix(value, 16).ok()?)),
        once,
    })
}

/// Parses one `retro_cheat_set` code into its parts (None if any part is malformed).
pub fn parse_codes(code: &str) -> Option<Vec<Code>> {
    let codes = code
        .split(['+', ';', '\n', '\r'])
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .map(parse_one)
        .collect::<Option<Vec<_>>>()?;
    (!codes.is_empty()).then_some(codes)
}

/// Value type and width of a primitive Java slot; None for references.
fn java_kind(slot: &JavaSlot) -> Option<(Kind, u8)> {
    let kind = match slot.descriptor.as_str() {
        "F" | "D" => Kind::Float,
        "B" | "Z" | "C" | "S" | "I" | "J" => Kind::Int,
        _ => return None,
    };
    Some((kind, slot.size.clamp(1, 8) as u8))
}

pub fn is_reference(descriptor: &str) -> bool {
    descriptor.starts_with('L') || descriptor.starts_with('[')
}

/// Element `index` of the array object `array` as a slot.
pub fn array_slot(heap: &dyn JavaHeap, array: u32, index: u32) -> Option<JavaSlot> {
    let array = heap.array(array)?;
    (index < array.length).then(|| JavaSlot {
        name: format!("[{index}]"),
        address: array.address + index * array.element_size,
        descriptor: array.element,
        size: array.element_size,
    })
}

/// Follows a Java path to the slot it names (which may hold a reference).
pub fn java_slot(memory: &dyn GuestMemory, heap: &dyn JavaHeap, class: &str, field: &FieldRef, path: &[PathStep]) -> Option<JavaSlot> {
    let mut slot = heap.static_fields(class).into_iter().find(|x| field.matches(x))?;
    for step in path {
        if !is_reference(&slot.descriptor) {
            return None;
        }
        let object = read_value(memory, slot.address, 4).filter(|x| *x != 0)?;
        slot = match step {
            // a subclass's field hides one of the same name in its superclass
            PathStep::Field(field) => heap.object(object)?.1.into_iter().rev().find(|x| field.matches(x))?,
            PathStep::Index(index) => array_slot(heap, object, *index)?,
        };
    }
    Some(slot)
}

/// Where `code` writes right now, or None while that location doesn't exist (object not created yet, null
/// pointer in the chain, class not loaded).
pub fn resolve(code: &Code, memory: &dyn GuestMemory, heap: Option<&dyn JavaHeap>) -> Option<Resolved> {
    let size = code.size.unwrap_or(4);
    match &code.locator {
        Locator::Address(address) => Some(Resolved {
            address: *address,
            size,
            kind: Kind::Int,
        }),
        Locator::Pointer { base, offsets } => {
            let mut address = *base;
            for offset in offsets {
                let pointer = read_value(memory, address, 4).filter(|x| *x != 0)?;
                address = pointer.wrapping_add(*offset as u32);
            }
            Some(Resolved {
                address,
                size,
                kind: Kind::Int,
            })
        }
        Locator::Java { class, field, path } => {
            let slot = java_slot(memory, heap?, class, field, path)?;
            let (kind, size) = java_kind(&slot)?;
            Some(Resolved {
                address: slot.address,
                size,
                kind,
            })
        }
    }
}

/// Writes `value` at a resolved location in its type (integers are truncated to the width).
pub fn write_resolved(memory: &mut dyn GuestMemory, at: Resolved, value: Value) -> bool {
    let bytes = match (at.kind, value) {
        (Kind::Float, value) => {
            let v = match value {
                Value::Int(i) => i as f64,
                Value::Float(f) => f,
            };
            if at.size == 8 {
                v.to_bits().to_le_bytes()
            } else {
                u64::from((v as f32).to_bits()).to_le_bytes()
            }
        }
        (Kind::Int, Value::Int(i)) => i.to_le_bytes(),
        (Kind::Int, Value::Float(f)) => (f as i64).to_le_bytes(),
    };
    memory.write(at.address, &bytes[..at.size.clamp(1, 8) as usize]).is_ok()
}

/// The value at a resolved location (integers of 4 bytes or less read as unsigned).
pub fn read_resolved(memory: &dyn GuestMemory, at: Resolved) -> Option<Value> {
    let mut b = [0u8; 8];
    let size = at.size.clamp(1, 8) as usize;
    memory.read(at.address, &mut b[..size]).ok()?;
    let raw = u64::from_le_bytes(b);
    Some(match (at.kind, size) {
        (Kind::Float, 8) => Value::Float(f64::from_bits(raw)),
        (Kind::Float, _) => Value::Float(f64::from(f32::from_bits(raw as u32))),
        (Kind::Int, 8) => Value::Int(raw as i64),
        (Kind::Int, _) => Value::Int(raw as i64),
    })
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
        self.codes.remove(&index);
        if !enabled {
            return;
        }
        match parse_codes(code) {
            Some(codes) => {
                self.codes.insert(index, codes.into_iter().map(|c| (c, false)).collect());
            }
            None => tracing::warn!("cheat {index}: unreadable code {code:?} (expected AAAAAAAA:HEX, P:BASE>OFF:VALUE or J:Class.field:VALUE)"),
        }
    }

    pub fn reset_codes(&mut self) {
        self.codes.clear();
    }

    fn pending(&self) -> impl Iterator<Item = &Code> {
        self.codes
            .values()
            .flatten()
            .filter(|(code, done)| !(code.once && *done))
            .map(|(code, _)| code)
    }

    /// Whether any code still has work to do (a lock, or a `once:` code not written yet).
    pub fn has_locks(&self) -> bool {
        self.pending().next().is_some()
    }

    /// Whether resolving the pending codes needs the Java heap.
    pub fn needs_java(&self) -> bool {
        self.pending().any(|code| matches!(code.locator, Locator::Java { .. }))
    }

    /// Writes every pending code whose location resolves (called after each frame's emulation).
    pub fn apply(&mut self, memory: &mut dyn GuestMemory, heap: Option<&dyn JavaHeap>) {
        for (code, done) in self.codes.values_mut().flatten() {
            if code.once && *done {
                continue;
            }
            if let Some(at) = resolve(code, memory, heap)
                && write_resolved(memory, at, code.value)
            {
                *done = true;
            }
        }
    }

    pub fn status(&self, memory: &dyn GuestMemory, heap: Option<&dyn JavaHeap>) -> Vec<CodeStatus> {
        self.codes
            .iter()
            .flat_map(|(index, codes)| codes.iter().map(move |(code, done)| (*index, code, *done)))
            .map(|(index, code, done)| {
                let at = resolve(code, memory, heap);
                CodeStatus {
                    index,
                    code: code.clone(),
                    done,
                    at,
                    now: at.and_then(|at| read_resolved(memory, at)),
                }
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wie_backend::JavaArray;

    pub struct Ram {
        pub base: u32,
        pub bytes: Vec<u8>,
    }

    impl GuestMemory for Ram {
        fn mapped_ranges(&self) -> Vec<(u32, u32)> {
            vec![(self.base, self.bytes.len() as u32)]
        }

        fn read(&self, address: u32, buf: &mut [u8]) -> wie_util::Result<()> {
            let o = address.wrapping_sub(self.base) as usize;
            let bytes = self.bytes.get(o..o + buf.len()).ok_or(wie_util::WieError::InvalidMemoryAccess(address))?;
            buf.copy_from_slice(bytes);
            Ok(())
        }

        fn write(&mut self, address: u32, data: &[u8]) -> wie_util::Result<()> {
            let o = address.wrapping_sub(self.base) as usize;
            let bytes = self
                .bytes
                .get_mut(o..o + data.len())
                .ok_or(wie_util::WieError::InvalidMemoryAccess(address))?;
            bytes.copy_from_slice(data);
            Ok(())
        }
    }

    fn ram() -> Ram {
        Ram {
            base: 0x4000_0000,
            bytes: vec![0; 0x200],
        }
    }

    #[test]
    fn search_narrows_to_the_changing_value() {
        let mut ram = ram();
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
    fn raw_codes_lock_values_by_width() {
        let codes = parse_codes("40000010:05DC+40000020 FF").unwrap();
        assert_eq!(codes[0].locator, Locator::Address(0x4000_0010));
        assert_eq!((codes[0].size, codes[0].value), (Some(2), Value::Int(0x5dc)));
        assert_eq!((codes[1].size, codes[1].value), (Some(1), Value::Int(0xff)));
        assert_eq!(parse_codes("nonsense"), None);
        let mut ram = ram();
        let mut cheats = Cheats::default();
        cheats.set_code(0, true, "40000010:0001869F");
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, 0x4000_0010, 4), Some(99_999));
        cheats.set_code(0, false, "");
        assert!(!cheats.has_locks());
    }

    #[test]
    fn pointer_chains_follow_the_pointers_every_time() {
        let codes = parse_codes("P:40000000>10>-4:2000;P2:40000100:0x7fff").unwrap();
        let chain = Locator::Pointer {
            base: 0x4000_0000,
            offsets: vec![0x10, -4],
        };
        assert_eq!(codes[0].locator, chain);
        assert_eq!(
            (&codes[1].locator, codes[1].size, codes[1].value),
            (&Locator::Address(0x4000_0100), Some(2), Value::Int(0x7fff))
        );

        let mut ram = ram();
        let mut cheats = Cheats::default();
        cheats.set_code(3, true, "P:40000000>10>-4:2000");
        // null pointer in the chain: nothing is written
        cheats.apply(&mut ram, None);
        assert!(ram.bytes.iter().all(|b| *b == 0));
        // [0x40000000] = 0x40000080; [0x40000090] = 0x40000108; value at 0x40000104
        ram.write(0x4000_0000, &0x4000_0080u32.to_le_bytes()).unwrap();
        ram.write(0x4000_0090, &0x4000_0108u32.to_le_bytes()).unwrap();
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, 0x4000_0104, 4), Some(2000));
        // the object moves: the code follows it
        ram.write(0x4000_0090, &0x4000_0188u32.to_le_bytes()).unwrap();
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, 0x4000_0184, 4), Some(2000));
    }

    #[test]
    fn once_writes_a_single_time_after_the_location_appears() {
        let mut ram = ram();
        let mut cheats = Cheats::default();
        cheats.set_code(0, true, "ONCE:P:40000000>8:500");
        cheats.apply(&mut ram, None);
        assert!(cheats.has_locks());
        ram.write(0x4000_0000, &0x4000_0040u32.to_le_bytes()).unwrap();
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, 0x4000_0048, 4), Some(500));
        assert!(!cheats.has_locks());
        // the game spends it: once doesn't put it back
        ram.write(0x4000_0048, &120u32.to_le_bytes()).unwrap();
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, 0x4000_0048, 4), Some(120));
    }

    /// Static `Game.player` (at 0x40000010) -> object 0x40000100 { hp: I @0x40000104, gold: J @0x40000108,
    /// bag: [I @0x40000110 } -> array 0x40000180 of 3 ints from 0x40000188.
    struct Heap;

    fn slot(name: &str, descriptor: &str, address: u32, size: u32) -> JavaSlot {
        JavaSlot {
            name: name.into(),
            descriptor: descriptor.into(),
            address,
            size,
        }
    }

    impl JavaHeap for Heap {
        fn class_names(&self) -> Vec<String> {
            vec!["Game".into()]
        }

        fn static_fields(&self, class: &str) -> Vec<JavaSlot> {
            match class {
                "Game" => vec![slot("player", "Lrpg/Player;", 0x4000_0010, 4)],
                _ => Vec::new(),
            }
        }

        fn object(&self, object: u32) -> Option<(String, Vec<JavaSlot>)> {
            (object == 0x4000_0100).then(|| {
                let fields = vec![
                    slot("hp", "I", 0x4000_0104, 4),
                    slot("gold", "J", 0x4000_0108, 8),
                    slot("bag", "[I", 0x4000_0110, 4),
                ];
                ("rpg/Player".into(), fields)
            })
        }

        fn array(&self, object: u32) -> Option<JavaArray> {
            (object == 0x4000_0180).then(|| JavaArray {
                element: "I".into(),
                length: 3,
                address: 0x4000_0188,
                element_size: 4,
            })
        }
    }

    #[test]
    fn java_paths_resolve_by_name() {
        let codes = parse_codes("J:rpg/Game.player.bag[2]:7").unwrap();
        let field = |name: &str, descriptor: Option<&str>| FieldRef {
            name: name.into(),
            descriptor: descriptor.map(String::from),
        };
        let path = vec![PathStep::Field(field("bag", None)), PathStep::Index(2)];
        let locator = Locator::Java {
            class: "rpg/Game".into(),
            field: field("player", None),
            path,
        };
        assert_eq!(codes[0].locator, locator);
        assert_eq!(locator.to_string(), "J:rpg/Game.player.bag[2]");
        assert_eq!(parse_codes("J:Game:5"), None);
        assert_eq!(parse_codes("J:Game.a[1:5"), None);
        let typed = &parse_codes("J:p.a(Lcs).a([[I)[1].b(I):5").unwrap()[0].locator;
        let Locator::Java { field: first, path, .. } = typed else { panic!() };
        assert_eq!(*first, field("a", Some("Lcs;")));
        assert_eq!(path[0], PathStep::Field(field("a", Some("[[I"))));
        assert_eq!(typed.to_string(), "J:p.a(Lcs).a([[I)[1].b(I)");

        let mut ram = ram();
        let mut cheats = Cheats::default();
        cheats.set_code(
            0,
            true,
            "J:Game.player.hp:-1+J:Game.player.gold:5000000000+J:Game.player.bag[2]:7+J:Game.player.bag[3]:9",
        );
        assert!(cheats.needs_java());
        // not created yet
        cheats.apply(&mut ram, Some(&Heap));
        assert!(ram.bytes.iter().all(|b| *b == 0));
        ram.write(0x4000_0010, &0x4000_0100u32.to_le_bytes()).unwrap();
        ram.write(0x4000_0110, &0x4000_0180u32.to_le_bytes()).unwrap();
        cheats.apply(&mut ram, Some(&Heap));
        assert_eq!(read_value(&ram, 0x4000_0104, 4), Some(u32::MAX));
        let mut gold = [0u8; 8];
        ram.read(0x4000_0108, &mut gold).unwrap();
        assert_eq!(i64::from_le_bytes(gold), 5_000_000_000);
        assert_eq!(read_value(&ram, 0x4000_0190, 4), Some(7));
        // past the end of the array: not written
        assert_eq!(read_value(&ram, 0x4000_0194, 4), Some(0));
    }
}
