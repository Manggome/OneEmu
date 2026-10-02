//! Persistent game storage: the WIPI file system and the RMS / WIPI databases, as plain files under the
//! libretro save directory (`<save>/<aid>/fs/...`, `<save>/<app id>/db/<name>/<record>`).
//!
//! Adapted from wie's desktop frontend (`src/filesystem.rs`, `src/database.rs`, MIT, © dlunch) with the
//! panicking paths removed: a missing or unreadable folder reads as empty instead of aborting the game.

use std::{
    fs::{self, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Component, Path, PathBuf},
};

use wie_backend::{Filesystem, RecordId};

fn sanitize_id(id: &str) -> Option<String> {
    let s: String = id.chars().filter(|c| !matches!(c, '/' | '\\' | '\0')).collect();
    if s.is_empty() || s == "." || s == ".." { None } else { Some(s) }
}

pub struct SaveFilesystem {
    base_path: PathBuf,
}

impl SaveFilesystem {
    pub fn new(base_path: PathBuf) -> Self {
        Self { base_path }
    }

    fn path_for(&self, aid: &str, path: &str) -> Option<PathBuf> {
        let Some(aid_dir) = sanitize_id(aid) else {
            tracing::error!(aid, path, "rejected: invalid aid");
            return None;
        };

        let mut normalized = PathBuf::new();
        for component in Path::new(path).components() {
            match component {
                Component::Normal(c) => normalized.push(c),
                Component::CurDir => {}
                Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                    // Guest paths are often absolute ("/save.dat"); only ".." escapes are refused.
                    if matches!(component, Component::ParentDir) {
                        tracing::error!(aid, path, "path traversal attempt rejected");
                        return None;
                    }
                }
            }
        }

        if normalized.as_os_str().is_empty() {
            tracing::error!(aid, path, "rejected: empty normalized path");
            return None;
        }

        Some(self.base_path.join(aid_dir).join("fs").join(normalized))
    }
}

#[async_trait::async_trait]
impl Filesystem for SaveFilesystem {
    async fn exists(&self, aid: &str, path: &str) -> bool {
        self.path_for(aid, path).and_then(|p| p.metadata().ok()).is_some_and(|md| md.is_file())
    }

    async fn size(&self, aid: &str, path: &str) -> Option<usize> {
        let md = self.path_for(aid, path)?.metadata().ok()?;
        md.is_file().then_some(md.len() as usize)
    }

    async fn read(&self, aid: &str, path: &str, offset: usize, count: usize, buf: &mut [u8]) -> Option<usize> {
        let disk_path = self.path_for(aid, path)?;
        let mut file = match OpenOptions::new().read(true).open(&disk_path) {
            Ok(f) => f,
            Err(err) => {
                if err.kind() != std::io::ErrorKind::NotFound {
                    tracing::warn!(aid, path, error = %err, "read: open failed");
                }
                return None;
            }
        };

        let size = file.metadata().map(|m| m.len() as usize).unwrap_or(0);
        if offset >= size {
            return Some(0);
        }
        if let Err(err) = file.seek(SeekFrom::Start(offset as u64)) {
            tracing::warn!(aid, path, error = %err, "read: seek failed");
            return Some(0);
        }

        let to_read = count.min(size - offset).min(buf.len());
        match file.read_exact(&mut buf[..to_read]) {
            Ok(()) => Some(to_read),
            Err(err) => {
                tracing::warn!(aid, path, error = %err, "read: IO error");
                Some(0)
            }
        }
    }

    async fn write(&self, aid: &str, path: &str, offset: usize, data: &[u8]) -> usize {
        let Some(disk_path) = self.path_for(aid, path) else {
            return 0;
        };
        if let Some(parent) = disk_path.parent()
            && let Err(err) = fs::create_dir_all(parent)
        {
            tracing::warn!(aid, path, error = %err, "write: create parent dir failed");
            return 0;
        }

        let mut file = match OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&disk_path) {
            Ok(f) => f,
            Err(err) => {
                tracing::warn!(aid, path, error = %err, "write: open failed");
                return 0;
            }
        };

        let current_size = file.metadata().map(|m| m.len() as usize).unwrap_or(0);
        if offset > current_size
            && let Err(err) = file.set_len(offset as u64)
        {
            tracing::warn!(aid, path, error = %err, "write: set_len extend failed");
            return 0;
        }
        if let Err(err) = file.seek(SeekFrom::Start(offset as u64)) {
            tracing::warn!(aid, path, error = %err, "write: seek failed");
            return 0;
        }
        match file.write_all(data) {
            Ok(()) => data.len(),
            Err(err) => {
                tracing::warn!(aid, path, error = %err, "write: write_all failed");
                0
            }
        }
    }

    async fn truncate(&self, aid: &str, path: &str, len: usize) {
        let Some(disk_path) = self.path_for(aid, path) else {
            return;
        };
        if let Some(parent) = disk_path.parent()
            && let Err(err) = fs::create_dir_all(parent)
        {
            tracing::warn!(aid, path, error = %err, "truncate: create parent dir failed");
            return;
        }
        match OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&disk_path) {
            Ok(file) => {
                if let Err(err) = file.set_len(len as u64) {
                    tracing::warn!(aid, path, error = %err, "truncate: set_len failed");
                }
            }
            Err(err) => tracing::warn!(aid, path, error = %err, "truncate: open failed"),
        }
    }
}

pub struct SaveDatabaseRepository {
    base_path: PathBuf,
}

impl SaveDatabaseRepository {
    pub fn new(base_path: PathBuf) -> Self {
        Self { base_path }
    }

    fn path_for_database(&self, name: &str, app_id: &str) -> PathBuf {
        let app_id = sanitize_id(app_id).unwrap_or_else(|| "_".into());

        let name: String = name.chars().map(|c| if matches!(c, '\\' | '\0') { '_' } else { c }).collect();
        let mut normalized = PathBuf::new();
        for segment in name.trim_start_matches('/').split('/') {
            match segment {
                "" | "." => {}
                ".." => normalized.push("_"),
                segment => normalized.push(segment),
            }
        }
        if normalized.as_os_str().is_empty() {
            normalized.push("_");
        }

        self.base_path.join(app_id).join("db").join(normalized)
    }

    /// Stores `data` as record 1 of database `name` unless that database already exists.
    pub fn seed(&self, name: &str, app_id: &str, data: &[u8]) -> bool {
        let path = self.path_for_database(name, app_id);
        if path.exists() {
            return false;
        }
        fs::create_dir_all(&path).and_then(|_| fs::write(path.join("1"), data)).is_ok()
    }

    fn directory_usage(path: &Path) -> u64 {
        let Ok(entries) = fs::read_dir(path) else {
            return 0;
        };
        entries
            .filter_map(Result::ok)
            .map(|entry| match entry.file_type() {
                Ok(t) if t.is_file() => entry.metadata().map(|m| m.len()).unwrap_or(0),
                Ok(t) if t.is_dir() => Self::directory_usage(&entry.path()),
                _ => 0,
            })
            .sum()
    }
}

#[async_trait::async_trait]
impl wie_backend::DatabaseRepository for SaveDatabaseRepository {
    async fn open(&self, name: &str, app_id: &str) -> Box<dyn wie_backend::Database> {
        let path = self.path_for_database(name, app_id);
        if let Err(err) = fs::create_dir_all(&path) {
            tracing::warn!(?path, error = %err, "database: create dir failed");
        }
        Box::new(SaveDatabase { base_path: path })
    }

    async fn exists(&self, name: &str, app_id: &str) -> bool {
        self.path_for_database(name, app_id).exists()
    }

    async fn delete(&self, name: &str, app_id: &str) -> bool {
        match fs::remove_dir_all(self.path_for_database(name, app_id)) {
            Ok(()) => true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
            Err(e) => {
                tracing::warn!("Failed to delete database: {e}");
                false
            }
        }
    }

    async fn usage(&self, app_id: &str) -> u64 {
        let db_root = self.path_for_database("_", app_id);
        db_root.parent().map(Self::directory_usage).unwrap_or(0)
    }
}

pub struct SaveDatabase {
    base_path: PathBuf,
}

impl SaveDatabase {
    fn record_path(&self, id: RecordId) -> PathBuf {
        self.base_path.join(id.to_string())
    }

    fn first_free_id(&self) -> RecordId {
        // MIDP requires the first record id to be 1.
        let mut id = 1;
        while self.record_path(id).exists() {
            id += 1;
        }
        id
    }
}

#[async_trait::async_trait]
impl wie_backend::Database for SaveDatabase {
    async fn next_id(&self) -> RecordId {
        self.first_free_id()
    }

    async fn add(&mut self, data: &[u8]) -> RecordId {
        let id = self.first_free_id();
        if let Err(err) = fs::create_dir_all(&self.base_path).and_then(|_| fs::write(self.record_path(id), data)) {
            tracing::warn!(id, error = %err, "database: add failed");
        }
        id
    }

    async fn get(&self, id: RecordId) -> Option<Vec<u8>> {
        fs::read(self.record_path(id)).ok()
    }

    async fn set(&mut self, id: RecordId, data: &[u8]) -> bool {
        fs::create_dir_all(&self.base_path).and_then(|_| fs::write(self.record_path(id), data)).is_ok()
    }

    async fn delete(&mut self, id: RecordId) -> bool {
        fs::remove_file(self.record_path(id)).is_ok()
    }

    async fn get_record_ids(&self) -> Vec<RecordId> {
        let Ok(entries) = fs::read_dir(&self.base_path) else {
            return Vec::new();
        };
        let mut ids: Vec<RecordId> = entries
            .filter_map(Result::ok)
            .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
            .filter_map(|e| e.file_name().to_str()?.parse().ok())
            .collect();
        ids.sort_unstable();
        ids
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wie_backend::{Database as _, DatabaseRepository as _};

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("wipi_libretro_test_{name}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn block_on<F: core::future::Future>(f: F) -> F::Output {
        futures::executor::block_on(f)
    }

    #[test]
    fn filesystem_roundtrip_and_traversal() {
        let dir = temp_dir("fs");
        let fs_ = SaveFilesystem::new(dir.clone());
        assert_eq!(block_on(fs_.write("AID1", "/save/slot.dat", 4, b"abcd")), 4);
        assert_eq!(block_on(fs_.size("AID1", "save/slot.dat")), Some(8));
        let mut buf = [0u8; 8];
        assert_eq!(block_on(fs_.read("AID1", "/save/slot.dat", 0, 8, &mut buf)), Some(8));
        assert_eq!(&buf, b"\0\0\0\0abcd");
        assert!(dir.join("AID1/fs/save/slot.dat").is_file());
        assert_eq!(block_on(fs_.write("AID1", "../escape", 0, b"x")), 0);
        assert!(!block_on(fs_.exists("AID2", "/save/slot.dat")));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn database_records() {
        let dir = temp_dir("db");
        let repo = SaveDatabaseRepository::new(dir.clone());
        let mut db = block_on(repo.open("/score", "PD1"));
        assert_eq!(block_on(db.add(b"one")), 1);
        assert_eq!(block_on(db.add(b"two")), 2);
        assert!(block_on(db.delete(1)));
        assert_eq!(block_on(db.get_record_ids()), vec![2]);
        assert_eq!(block_on(db.next_id()), 1);
        assert!(block_on(repo.exists("score", "PD1")));
        assert!(block_on(repo.usage("PD1")) >= 3);
        let _ = fs::remove_dir_all(dir);
    }
}
