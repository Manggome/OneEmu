//! Runs wie's own "Hello, world!" sample apps (KTF and LGT, MIT, shipped in the wie repo) through the
//! same Session the libretro core uses: detection, platform, virtual clock and tick loop.

use std::path::PathBuf;

use wipi_libretro::{Carrier, FrameOutput, KeySet, LoadRequest, RawOptions, Session};

fn run_sample(zip: &str, expected: Carrier) {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..");
    let tmp = std::env::temp_dir().join(format!("wipi_smoke_{}_{}", expected.name(), std::process::id()));
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    let (mut session, info) = Session::load(LoadRequest {
        path: root.join(zip),
        system_dir: tmp.join("system"),
        save_dir: tmp.join("saves"),
        assets_dir: None,
        options: RawOptions::default(),
    })
    .expect("load");
    assert_eq!(info.carrier, expected);
    assert!(info.width > 0 && info.height > 0);

    let mut exited = false;
    let mut out = FrameOutput::default();
    for _ in 0..600 {
        out = session.run_frame(KeySet::default(), out);
        assert!(out.error.is_none(), "emulation error: {:?}", out.error);
        assert_eq!(out.audio.len(), 735 * 2);
        if out.exit {
            exited = true;
            break;
        }
    }
    let stdout = String::from_utf8_lossy(&session.stdout()).to_string();
    assert_eq!(stdout, "Hello, world!");
    assert!(exited, "app did not exit within 10 s of game time");
    let _ = std::fs::remove_dir_all(&tmp);
}

#[test]
fn ktf_helloworld() {
    run_sample("wie-ktf/tests/data/helloworld_ktf.zip", Carrier::Ktf);
}

#[test]
fn lgt_helloworld() {
    run_sample("wie-lgt/tests/data/helloworld_lgt.zip", Carrier::Lgt);
}
