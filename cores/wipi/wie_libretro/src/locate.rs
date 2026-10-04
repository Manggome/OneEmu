//! Research helpers for cheat presets: given an address found with the memory search, list locators that reach
//! it in a way that survives a restart (heap addresses change between runs; the game binary's data and Java
//! field names don't). Used by `wipi_headless --cheat "F:locate:ADDR"`; candidates must then be checked on other
//! runs (`probe`), since a stale pointer can reach the address by accident.

use std::collections::{HashSet, VecDeque};

use wie_backend::{GuestMemory, JavaHeap, JavaSlot};

use crate::cheat::{Code, FieldRef, Locator, array_slot, is_reference, java_slot, parse_one, read_value};

/// Guest addresses below this hold the game binary (code, data, bss); the heap and thread stacks start here.
pub const IMAGE_END: u32 = wie_core_arm::HEAP_BASE;

const CHUNK: usize = 0x10000;
/// Array elements followed per array when walking references.
const MAX_ARRAY_REFERENCES: u32 = 1024;
/// Objects visited per Java walk.
const MAX_OBJECTS: usize = 200_000;

/// A locator without its value, e.g. `P:0012A4C0>10` or `J:Game.player.gold`, with the value width it has.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub locator: String,
    pub size: u32,
    pub note: String,
}

/// Java field paths (bounded by `max_depth` references after the static field) whose value covers `target`.
pub fn java_paths(memory: &dyn GuestMemory, heap: &dyn JavaHeap, target: u32, max_depth: usize) -> Vec<Found> {
    let mut found = Vec::new();
    let mut visited = HashSet::new();
    // (object, path to the slot holding it, depth)
    let mut queue = VecDeque::new();

    let covers = |address: u32, size: u32| address <= target && target < address.saturating_add(size.max(1));

    for class in heap.class_names() {
        let statics = heap.static_fields(&class);
        for slot in &statics {
            let path = format!("{class}.{}", label(slot, &statics));
            if is_reference(&slot.descriptor) {
                if let Some(object) = read_value(memory, slot.address, 4).filter(|x| *x != 0)
                    && visited.insert(object)
                {
                    queue.push_back((object, path, 0usize));
                }
            } else if covers(slot.address, slot.size) {
                found.push(Found {
                    locator: format!("J:{path}"),
                    size: slot.size,
                    note: format!("static {}", slot.descriptor),
                });
            }
        }
    }

    while let Some((object, path, depth)) = queue.pop_front() {
        if visited.len() > MAX_OBJECTS {
            break;
        }
        let mut children = Vec::new();
        if let Some(array) = heap.array(object) {
            let end = array.address.saturating_add(array.length.saturating_mul(array.element_size));
            if is_reference(&array.element) {
                for index in 0..array.length.min(MAX_ARRAY_REFERENCES) {
                    children.extend(array_slot(heap, object, index).map(|slot| (slot, format!("{path}[{index}]"))));
                }
            } else if array.address <= target && target < end {
                let index = (target - array.address) / array.element_size.max(1);
                found.push(Found {
                    locator: format!("J:{path}[{index}]"),
                    size: array.element_size,
                    note: format!("{} element of {} (length {})", array.element, object_note(object), array.length),
                });
            }
        } else if let Some((class, fields)) = heap.object(object) {
            for slot in &fields {
                let child = format!("{path}.{}", label(slot, &fields));
                if is_reference(&slot.descriptor) {
                    children.push((slot.clone(), child));
                } else if covers(slot.address, slot.size) {
                    found.push(Found {
                        locator: format!("J:{child}"),
                        size: slot.size,
                        note: format!("{} field of {class} {}", slot.descriptor, object_note(object)),
                    });
                }
            }
        }
        if depth >= max_depth {
            continue;
        }
        for (slot, child) in children {
            if let Some(next) = read_value(memory, slot.address, 4).filter(|x| *x != 0)
                && visited.insert(next)
            {
                queue.push_back((next, child, depth + 1));
            }
        }
    }
    found
}

/// One line per value: `name descriptor @address = value` (references as the object address and class).
pub fn browse(memory: &dyn GuestMemory, heap: &dyn JavaHeap, path: &str) -> Vec<String> {
    let path = path.trim();
    if path.is_empty() {
        return heap.class_names();
    }
    let show = |slot: &JavaSlot| {
        let value = match slot.size {
            8 => {
                let mut b = [0u8; 8];
                memory
                    .read(slot.address, &mut b)
                    .map(|_| i64::from_le_bytes(b).to_string())
                    .unwrap_or_else(|_| "?".into())
            }
            1 => read_value(memory, slot.address, 1).map(|v| v.to_string()).unwrap_or_else(|| "?".into()),
            2 => read_value(memory, slot.address, 2).map(|v| v.to_string()).unwrap_or_else(|| "?".into()),
            _ => match read_value(memory, slot.address, 4) {
                Some(v) if is_reference(&slot.descriptor) => match heap.object(v) {
                    Some((class, _)) => format!("@{v:08X} {class}"),
                    None => format!("@{v:08X}"),
                },
                Some(v) => (v as i32).to_string(),
                None => "?".into(),
            },
        };
        format!("{} {} @{:08X} = {value}", slot.name, slot.descriptor, slot.address)
    };
    let Some((class, rest)) = path.split_once('.') else {
        return heap.static_fields(path).iter().map(show).collect();
    };
    let Some(Code {
        locator: Locator::Java { field, path: steps, .. },
        ..
    }) = parse_one(&format!("J:{class}.{rest}:0"))
    else {
        return vec![format!("bad path {path}")];
    };
    let Some(slot) = java_slot(memory, heap, class, &field, &steps) else {
        return vec![format!("{path}: unresolved")];
    };
    if !is_reference(&slot.descriptor) {
        return vec![show(&slot)];
    }
    let Some(object) = read_value(memory, slot.address, 4).filter(|x| *x != 0) else {
        return vec![format!("{path}: null")];
    };
    if let Some(array) = heap.array(object) {
        let mut lines = vec![format!("{path}: {}[{}] @{:08X}", array.element, array.length, array.address)];
        lines.extend((0..array.length.min(64)).filter_map(|i| array_slot(heap, object, i)).map(|s| show(&s)));
        return lines;
    }
    match heap.object(object) {
        Some((class, fields)) => std::iter::once(format!("{path}: {class} @{object:08X}"))
            .chain(fields.iter().map(show))
            .collect(),
        None => vec![format!("{path}: @{object:08X} is not a live object")],
    }
}

/// The field's name, with its descriptor when another field in `fields` has the same name (obfuscated classes).
fn label(slot: &JavaSlot, fields: &[JavaSlot]) -> String {
    let descriptor = (fields.iter().filter(|x| x.name == slot.name).count() > 1).then(|| slot.descriptor.clone());
    FieldRef {
        name: slot.name.clone(),
        descriptor,
    }
    .to_string()
}

fn object_note(object: u32) -> String {
    format!("@{object:08X}")
}

/// Calls `f` with every aligned 32-bit word of the mapped ranges in `[start, end)` as (address, value).
fn for_each_word(memory: &dyn GuestMemory, start: u32, end: u32, mut f: impl FnMut(u32, u32)) {
    let mut buf = vec![0u8; CHUNK];
    for (range_start, len) in memory.mapped_ranges() {
        let lo = range_start.max(start);
        let hi = range_start.saturating_add(len).min(end);
        let mut address = lo;
        while address < hi {
            let n = CHUNK.min((hi - address) as usize) & !3;
            if n == 0 {
                break;
            }
            if memory.read(address, &mut buf[..n]).is_ok() {
                for (i, word) in buf[..n].as_chunks::<4>().0.iter().enumerate() {
                    f(address + i as u32 * 4, u32::from_le_bytes(*word));
                }
            }
            address = address.saturating_add(n as u32);
        }
    }
}

fn hex_offset(offset: u32) -> String {
    format!("{offset:X}")
}

/// Most pointer chains reported per address (closest first).
const MAX_CHAINS: usize = 200;

/// Pointer chains from the game binary's data (below [`IMAGE_END`]) to `target`: the address itself when it is in
/// the binary (nothing else is needed then), else `BASE>OFF` when a word in the binary points up to `max_offset`
/// bytes before it, and only when there is no such word, `BASE>OFF1>OFF2` through one more pointer anywhere.
pub fn pointer_paths(memory: &dyn GuestMemory, target: u32, max_offset: u32) -> Vec<Found> {
    let mut found = Vec::new();
    if target < IMAGE_END {
        found.push(Found {
            locator: format!("P:{target:08X}"),
            size: 4,
            note: "in the game binary: a fixed address".into(),
        });
        return found;
    }
    let near = |pointer: u32, to: u32| pointer != 0 && pointer <= to && to - pointer < max_offset;

    // Words in the binary that look like pointers, sorted by value.
    let mut image = Vec::new();
    for_each_word(memory, 0x1000, IMAGE_END, |address, value| {
        if value >= 0x1000 {
            image.push((address, value));
        }
    });
    image.sort_by_key(|&(_, v)| v);
    let pointing_into = |lo: u32, hi: u32| {
        let start = image.partition_point(|&(_, v)| v < lo);
        image[start..].iter().take_while(move |&&(_, v)| v <= hi).copied()
    };

    for (base, pointer) in pointing_into(target.saturating_sub(max_offset - 1), target) {
        found.push(Found {
            locator: format!("P:{base:08X}>{}", hex_offset(target - pointer)),
            size: 4,
            note: format!("[{base:08X}] = {pointer:08X}"),
        });
    }
    if !found.is_empty() {
        // closest pointer first
        found.sort_by_key(|f| {
            (
                f.locator.len(),
                f.locator.rsplit('>').next().and_then(|x| u32::from_str_radix(x, 16).ok()),
            )
        });
        found.truncate(MAX_CHAINS);
        return found;
    }

    // One more level: any word (heap included) that points near the target, reached from the binary.
    let mut middles = Vec::new();
    for_each_word(memory, 0x1000, u32::MAX, |address, value| {
        if near(value, target) {
            middles.push((address, value));
        }
    });
    let mut level2 = Vec::new();
    for (middle, pointer) in middles {
        for (base, first) in pointing_into(middle.saturating_sub(max_offset - 1), middle) {
            if base == middle {
                continue;
            }
            level2.push(Found {
                locator: format!("P:{base:08X}>{}>{}", hex_offset(middle - first), hex_offset(target - pointer)),
                size: 4,
                note: format!("[{base:08X}] = {first:08X}, [{middle:08X}] = {pointer:08X}"),
            });
        }
    }
    level2.sort_by_key(|f| f.locator.len());
    level2.truncate(MAX_CHAINS);
    found.extend(level2);
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cheat::{Cheats, parse_codes, resolve};

    struct Ram(Vec<(u32, Vec<u8>)>);

    impl Ram {
        fn put(&mut self, address: u32, value: u32) {
            self.write(address, &value.to_le_bytes()).unwrap();
        }
    }

    impl GuestMemory for Ram {
        fn mapped_ranges(&self) -> Vec<(u32, u32)> {
            self.0.iter().map(|(base, bytes)| (*base, bytes.len() as u32)).collect()
        }

        fn read(&self, address: u32, buf: &mut [u8]) -> wie_util::Result<()> {
            for (base, bytes) in &self.0 {
                let o = address.wrapping_sub(*base) as usize;
                if let Some(src) = bytes.get(o..o + buf.len()) {
                    buf.copy_from_slice(src);
                    return Ok(());
                }
            }
            Err(wie_util::WieError::InvalidMemoryAccess(address))
        }

        fn write(&mut self, address: u32, data: &[u8]) -> wie_util::Result<()> {
            for (base, bytes) in &mut self.0 {
                let o = address.wrapping_sub(*base) as usize;
                if let Some(dst) = bytes.get_mut(o..o + data.len()) {
                    dst.copy_from_slice(data);
                    return Ok(());
                }
            }
            Err(wie_util::WieError::InvalidMemoryAccess(address))
        }
    }

    #[test]
    fn finds_pointer_chains_from_the_binary() {
        let mut ram = Ram(vec![(0x1000, vec![0; 0x1000]), (IMAGE_END, vec![0; 0x1000])]);
        let target = IMAGE_END + 0x824;
        // binary word -> struct at +0x800; struct+0x10 -> object at +0x810; value at object+0x14
        ram.put(0x1100, IMAGE_END + 0x800);
        ram.put(IMAGE_END + 0x810, IMAGE_END + 0x810);
        let found = |max_offset| pointer_paths(&ram, target, max_offset).into_iter().map(|f| f.locator).collect::<Vec<_>>();
        assert_eq!(found(0x100), vec!["P:00001100>24".to_string()]);
        // no direct pointer within 0x20 bytes: one more level
        assert_eq!(found(0x20), vec!["P:00001100>10>14".to_string()]);
        assert_eq!(pointer_paths(&ram, 0x1100, 0x20)[0].locator, "P:00001100");

        let code = &parse_codes("P:00001100>10>14:1").unwrap()[0];
        assert_eq!(resolve(code, &ram, None).map(|x| x.address), Some(target));
        let mut cheats = Cheats::default();
        cheats.set_code(0, true, "P:00001100>10>14:1234");
        cheats.apply(&mut ram, None);
        assert_eq!(read_value(&ram, target, 4), Some(1234));
    }
}
