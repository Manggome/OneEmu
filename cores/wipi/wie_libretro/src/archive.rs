//! Reads a game file (zip / jar / jad), normalises common dump layouts and decides which wie
//! emulator (carrier) runs it. Detection mirrors wie's own frontends: KTF (`__adf__`) → LGT
//! (`app_info`) → SKT (`*.msd`) for archives; for a bare jar, KTF → LGT → SKT jar checks, then J2ME.

use std::{collections::BTreeMap, fs, path::Path};

use wie_backend::{Emulator, Options, Platform, extract_zip};
use wie_j2me::J2MEEmulator;
use wie_ktf::KtfEmulator;
use wie_lgt::LgtEmulator;
use wie_skt::SktEmulator;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Carrier {
    Ktf,
    Lgt,
    Skt,
    J2me,
}

impl Carrier {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "ktf" => Some(Self::Ktf),
            "lgt" => Some(Self::Lgt),
            "skt" => Some(Self::Skt),
            "j2me" => Some(Self::J2me),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            Self::Ktf => "KTF",
            Self::Lgt => "LGT",
            Self::Skt => "SKT",
            Self::J2me => "J2ME",
        }
    }
}

pub enum GameSource {
    /// A zip holding descriptor + jar (+ resources), already normalised.
    Archive(BTreeMap<String, Vec<u8>>),
    /// A bare jar (file name without directories).
    Jar { filename: String, data: Vec<u8> },
    /// A J2ME jad with its jar.
    JadJar { jad: Vec<u8>, jar_filename: String, jar: Vec<u8> },
}

pub struct Game {
    pub source: GameSource,
    pub carrier: Carrier,
    pub title: Option<String>,
    /// Application id used to match quirks (KTF/LGT PID or AID, SKT id, jar name).
    pub id: Option<String>,
    pub aid: Option<String>,
}

fn file_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

fn lower(s: &str) -> String {
    s.to_ascii_lowercase()
}

/// Strips one folder that wraps every entry ("Game/__adf__" → "__adf__").
fn strip_common_folder(files: BTreeMap<String, Vec<u8>>) -> BTreeMap<String, Vec<u8>> {
    let mut prefix: Option<&str> = None;
    for name in files.keys() {
        let Some((first, _)) = name.split_once('/') else {
            return files;
        };
        match prefix {
            None => prefix = Some(first),
            Some(p) if p == first => {}
            _ => return files,
        }
    }
    let Some(prefix) = prefix.map(|p| format!("{p}/")) else {
        return files;
    };
    files.into_iter().map(|(k, v)| (k[prefix.len()..].to_string(), v)).collect()
}

fn text_field(data: &[u8], key: &str) -> Option<String> {
    data.split(|b| *b == b'\n').find_map(|line| {
        let line = line.strip_suffix(b"\r").unwrap_or(line);
        line.strip_prefix(key.as_bytes()).map(|v| String::from_utf8_lossy(v).trim().to_string())
    })
}

/// Repairs the dump layouts that wie's archive loaders don't accept as-is.
pub fn normalize(files: BTreeMap<String, Vec<u8>>) -> BTreeMap<String, Vec<u8>> {
    let mut files = strip_common_folder(files);

    // The jar inside may carry the same broken Unicode path fields as the outer zip.
    for data in files.iter_mut().filter(|(k, _)| lower(k).ends_with(".jar")).map(|(_, v)| v) {
        if extract_zip(data).is_err() {
            let mut copy = data.clone();
            if neutralize_unicode_path_fields(&mut copy) && extract_zip(&copy).is_ok() {
                *data = copy;
            }
        }
    }

    // KTF resources live under "P/" and wie strips exactly that prefix; some dumps (이노티아 연대기2) use "p/".
    if files.contains_key("__adf__") || files.keys().any(|k| !k.contains('/') && lower(k).ends_with(".adf")) {
        let lower_p: Vec<String> = files.keys().filter(|k| k.starts_with("p/")).cloned().collect();
        for k in lower_p {
            if let Some(v) = files.remove(&k) {
                files.entry(format!("P/{}", &k[2..])).or_insert(v);
            }
        }
    }

    // KTF: descriptor saved as "<name>.adf" instead of "__adf__".
    if !files.contains_key("__adf__")
        && !files.contains_key("app_info")
        && !files.keys().any(|k| lower(k).ends_with(".msd"))
    {
        let adfs: Vec<String> = files.keys().filter(|k| !k.contains('/') && lower(k).ends_with(".adf")).cloned().collect();
        if adfs.len() == 1
            && let Some(data) = files.get(&adfs[0]).cloned()
        {
            files.insert("__adf__".into(), data);
        }
    }

    // KTF: the loader opens "<AID>.jar"; alias the only client.bin jar if it is named differently.
    if let Some(adf) = files.get("__adf__")
        && let Some(aid) = text_field(adf, "AID:").filter(|a| !a.is_empty())
    {
        let wanted = format!("{aid}.jar");
        if !files.contains_key(&wanted) {
            let jars: Vec<String> = files.keys().filter(|k| lower(k).ends_with(".jar")).cloned().collect();
            let candidates: Vec<&String> = jars.iter().filter(|k| KtfEmulator::loadable_jar(&files[*k])).collect();
            let pick = if candidates.len() == 1 { Some(candidates[0].clone()) } else if jars.len() == 1 { Some(jars[0].clone()) } else { None };
            if let Some(src) = pick
                && let Some(data) = files.get(&src).cloned()
            {
                tracing::info!("KTF: aliasing {src} as {wanted}");
                files.insert(wanted, data);
            }
        }
    }

    files
}

/// Renames every Info-ZIP Unicode Path extra field (0x7075) in the central directory and local headers to an
/// id nobody reads. Some re-packed dumps carry one whose CRC doesn't match the plain name (메이플스토리 해적편),
/// and the zip crate rejects the whole archive for it; the plain file name is what WIPI uses anyway.
fn neutralize_unicode_path_fields(data: &mut [u8]) -> bool {
    fn u16_at(d: &[u8], o: usize) -> Option<usize> {
        d.get(o..o + 2).map(|b| u16::from_le_bytes([b[0], b[1]]) as usize)
    }
    fn u32_at(d: &[u8], o: usize) -> Option<usize> {
        d.get(o..o + 4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as usize)
    }
    fn patch_extra(d: &mut [u8], mut at: usize, len: usize) -> bool {
        let end = at + len;
        let mut changed = false;
        while at + 4 <= end && at + 4 <= d.len() {
            let (Some(id), Some(size)) = (u16_at(d, at), u16_at(d, at + 2)) else { break };
            if id == 0x7075 {
                d[at] = 0xff;
                d[at + 1] = 0xff;
                changed = true;
            }
            at += 4 + size;
        }
        changed
    }

    // End of central directory: last "PK\x05\x06" (a trailing comment can follow it).
    let Some(eocd) = (0..data.len().saturating_sub(21)).rev().find(|&i| data[i..].starts_with(b"PK\x05\x06")) else {
        return false;
    };
    let (Some(count), Some(mut at)) = (u16_at(data, eocd + 10), u32_at(data, eocd + 16)) else {
        return false;
    };
    let mut changed = false;
    for _ in 0..count {
        if !data.get(at..).is_some_and(|d| d.starts_with(b"PK\x01\x02")) {
            break;
        }
        let (Some(name_len), Some(extra_len), Some(comment_len), Some(local)) =
            (u16_at(data, at + 28), u16_at(data, at + 30), u16_at(data, at + 32), u32_at(data, at + 42))
        else {
            break;
        };
        changed |= patch_extra(data, at + 46 + name_len, extra_len);
        if data.get(local..).is_some_and(|d| d.starts_with(b"PK\x03\x04"))
            && let (Some(lname), Some(lextra)) = (u16_at(data, local + 26), u16_at(data, local + 28))
        {
            changed |= patch_extra(data, local + 30 + lname, lextra);
        }
        at += 46 + name_len + extra_len + comment_len;
    }
    changed
}

/// wie's zip reader, retried once without Unicode Path extra fields when it rejects the archive.
fn extract_zip_lenient(data: &[u8]) -> anyhow::Result<BTreeMap<String, Vec<u8>>> {
    match extract_zip(data) {
        Ok(files) => Ok(files),
        Err(first) => {
            let mut copy = data.to_vec();
            if neutralize_unicode_path_fields(&mut copy)
                && let Ok(files) = extract_zip(&copy)
            {
                tracing::info!("zip: ignored broken Unicode path fields");
                return Ok(files);
            }
            Err(anyhow::anyhow!("not a zip archive: {first}"))
        }
    }
}

fn detect_archive(files: &BTreeMap<String, Vec<u8>>) -> Option<Carrier> {
    if KtfEmulator::loadable_archive(files) {
        Some(Carrier::Ktf)
    } else if LgtEmulator::loadable_archive(files) {
        Some(Carrier::Lgt)
    } else if SktEmulator::loadable_archive(files) {
        Some(Carrier::Skt)
    } else {
        None
    }
}

fn detect_jar(jar: &[u8]) -> Carrier {
    if KtfEmulator::loadable_jar(jar) {
        Carrier::Ktf
    } else if LgtEmulator::loadable_jar(jar) {
        Carrier::Lgt
    } else if SktEmulator::loadable_jar(jar) {
        Carrier::Skt
    } else {
        Carrier::J2me
    }
}

/// Loads and classifies the file at `path`. `forced` overrides the detected carrier.
pub fn open(path: &Path, forced: Option<Carrier>) -> anyhow::Result<Game> {
    let data = fs::read(path).map_err(|e| anyhow::anyhow!("cannot read {}: {e}", path.display()))?;
    let path_str = path.to_string_lossy().to_string();
    let ext = path.extension().map(|e| lower(&e.to_string_lossy())).unwrap_or_default();

    match ext.as_str() {
        "jad" => {
            let jar_path = path.with_extension("jar");
            let jar = fs::read(&jar_path).map_err(|e| anyhow::anyhow!("jad needs its jar next to it ({}): {e}", jar_path.display()))?;
            let jar_filename = file_name(&jar_path.to_string_lossy()).to_string();
            let title = text_field(&data, "MIDlet-Name:");
            Ok(Game {
                source: GameSource::JadJar { jad: data, jar_filename, jar },
                carrier: Carrier::J2me,
                id: title.clone(),
                aid: None,
                title,
            })
        }
        "jar" => Ok(from_jar(file_name(&path_str).to_string(), data, forced)),
        _ => {
            let files = normalize(extract_zip_lenient(&data)?);
            if let Some(carrier) = forced.filter(|c| *c != Carrier::J2me).or_else(|| detect_archive(&files)) {
                let (title, id) = match carrier {
                    Carrier::Ktf => (KtfEmulator::archive_title(&files), KtfEmulator::archive_id(&files)),
                    Carrier::Lgt => (LgtEmulator::archive_title(&files), LgtEmulator::archive_id(&files)),
                    Carrier::Skt => (SktEmulator::archive_title(&files), SktEmulator::archive_id(&files)),
                    Carrier::J2me => (None, None),
                };
                let aid = files
                    .get("__adf__")
                    .and_then(|d| text_field(d, "AID:"))
                    .or_else(|| files.get("app_info").and_then(|d| text_field(d, "AID:")));
                return Ok(Game {
                    source: GameSource::Archive(files),
                    carrier,
                    title,
                    id,
                    aid,
                });
            }
            // No descriptor: a zip that just wraps a single jar (plus maybe a jad).
            let jars: Vec<&String> = files.keys().filter(|k| lower(k).ends_with(".jar")).collect();
            if jars.len() == 1 {
                let jar_name = jars[0].clone();
                let jad_name = format!("{}.jad", &jar_name[..jar_name.len() - 4]);
                let mut files = files;
                let jar = files.remove(&jar_name).unwrap_or_default();
                if forced.is_none_or(|c| c == Carrier::J2me)
                    && detect_jar(&jar) == Carrier::J2me
                    && let Some(jad) = files.remove(&jad_name)
                {
                    let title = text_field(&jad, "MIDlet-Name:");
                    return Ok(Game {
                        source: GameSource::JadJar { jad, jar_filename: file_name(&jar_name).to_string(), jar },
                        carrier: Carrier::J2me,
                        id: title.clone(),
                        aid: None,
                        title,
                    });
                }
                return Ok(from_jar(file_name(&jar_name).to_string(), jar, forced));
            }
            anyhow::bail!("알 수 없는 WIPI 패키지입니다 (__adf__ / app_info / .msd / .jar 없음)")
        }
    }
}

fn from_jar(filename: String, data: Vec<u8>, forced: Option<Carrier>) -> Game {
    let carrier = forced.unwrap_or_else(|| detect_jar(&data));
    let stem = filename.strip_suffix(".jar").or_else(|| filename.strip_suffix(".JAR")).unwrap_or(&filename).to_string();
    let title = if carrier == Carrier::J2me {
        J2MEEmulator::jar_metadata(&data).ok().flatten().map(|(name, _)| name)
    } else {
        None
    };
    Game {
        source: GameSource::Jar { filename, data },
        carrier,
        title: title.or_else(|| Some(stem.clone())),
        id: Some(stem),
        aid: None,
    }
}

/// Builds the wie emulator for `game`. Consumes the game data.
pub fn create_emulator(game: Game, platform: Box<dyn Platform>, options: Options) -> anyhow::Result<Box<dyn Emulator>> {
    let emulator: Box<dyn Emulator> = match (game.source, game.carrier) {
        (GameSource::Archive(files), Carrier::Ktf) => Box::new(KtfEmulator::from_archive(platform, files, options)?),
        (GameSource::Archive(files), Carrier::Lgt) => Box::new(LgtEmulator::from_archive(platform, files, options)?),
        (GameSource::Archive(files), Carrier::Skt) => Box::new(SktEmulator::from_archive(platform, files)?),
        (GameSource::Archive(_), Carrier::J2me) => anyhow::bail!("J2ME archive without a jar"),
        (GameSource::Jar { filename, data }, carrier) => {
            let stem = filename.strip_suffix(".jar").or_else(|| filename.strip_suffix(".JAR")).unwrap_or(&filename).to_string();
            match carrier {
                Carrier::Ktf => Box::new(KtfEmulator::from_jar(platform, &filename, data, &stem, &stem, None, options)?),
                Carrier::Lgt => Box::new(LgtEmulator::from_jar(platform, &filename, data, &stem, &stem, None, options)?),
                Carrier::Skt => Box::new(SktEmulator::from_jar(platform, &filename, data, &stem, None)?),
                Carrier::J2me => Box::new(J2MEEmulator::from_jar(platform, &filename, data)?),
            }
        }
        (GameSource::JadJar { jad, jar_filename, jar }, _) => Box::new(J2MEEmulator::from_jad_jar(platform, jad, jar_filename, jar)?),
    };
    Ok(emulator)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn map(entries: &[(&str, &[u8])]) -> BTreeMap<String, Vec<u8>> {
        entries.iter().map(|(k, v)| (k.to_string(), v.to_vec())).collect()
    }

    #[test]
    fn strips_single_wrapping_folder() {
        let files = normalize(map(&[("Game/app_info", b"AID:1"), ("Game/a.jar", b"x")]));
        assert!(files.contains_key("app_info"));
        assert!(files.contains_key("a.jar"));
    }

    #[test]
    fn wrapped_ktf_with_lowercase_resource_dir() {
        let files = normalize(map(&[
            ("Game-wipi1.2/__adf__", b"AID:010100D5\n"),
            ("Game-wipi1.2/010100D5.jar", b"x"),
            ("Game-wipi1.2/p/i_pack.dat", b"data"),
        ]));
        assert!(files.contains_key("__adf__"));
        assert!(files.contains_key("P/i_pack.dat"));
        assert!(!files.contains_key("p/i_pack.dat"));
    }

    #[test]
    fn keeps_mixed_layout() {
        let files = normalize(map(&[("app_info", b"AID:1"), ("res/a.png", b"x")]));
        assert!(files.contains_key("res/a.png"));
    }

    #[test]
    fn renames_lone_adf() {
        let files = normalize(map(&[("game.adf", b"AID:ABC\nPID:P1\n"), ("other.jar", b"x")]));
        assert!(files.contains_key("__adf__"));
        // the only jar is aliased to <AID>.jar
        assert!(files.contains_key("ABC.jar"));
        assert_eq!(detect_archive(&files), Some(Carrier::Ktf));
    }

    #[test]
    fn detects_wie_sample_archives() {
        let ktf = normalize(extract_zip(include_bytes!("../../wie-ktf/tests/data/helloworld_ktf.zip")).unwrap());
        assert_eq!(detect_archive(&ktf), Some(Carrier::Ktf));
        let lgt = normalize(extract_zip(include_bytes!("../../wie-lgt/tests/data/helloworld_lgt.zip")).unwrap());
        assert_eq!(detect_archive(&lgt), Some(Carrier::Lgt));
    }

    #[test]
    fn text_fields_handle_crlf() {
        assert_eq!(text_field(b"Name:x\r\nAID:12 \r\n", "AID:").as_deref(), Some("12"));
    }
}
