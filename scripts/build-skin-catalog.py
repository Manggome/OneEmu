#!/usr/bin/env python3
"""
Builds the online pad-skin catalog OneEmu shows under 설정 → 패드 레이아웃 → 패드 스킨 → 온라인.

Source: https://github.com/libretro/common-overlays (CC BY 4.0), folder gamepads/.
Output (--out, default build/skin-catalog/dist):
  <skinId>.zip                 cfg (paths rewritten to img/…, legacy pixel coordinates normalised) + skin.json + img/*.png
  <skinId>-portrait.png        480px-tall 9:19.5 phone preview
  <skinId>-landscape.png       480px-wide 19.5:9 phone preview
  catalog.json                 what the app reads (schema 1, see README-skins.md)
Then, with --upload, everything goes to the rolling GitHub release tagged `skins` (prerelease "Pad skins catalog").

The parser below re-implements the subset of RetroArch's overlay format that
app/src/main/java/com/manggome/oneemu/emu/skin/OverlaySkin.kt understands, and the preview renderer mirrors
SkinGeometry.placeOverlay / OverlayCfg.pick, so a preview shows what the pad will look like in the app.

Requires: python3, Pillow, git, gh (for --upload).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Iterable

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install Pillow")

SOURCE_REPO = "https://github.com/libretro/common-overlays"
LICENSE = "CC BY 4.0"
DEFAULT_AUTHOR = "libretro community"

MAX_IMAGE_SIDE = 1024
PREVIEW_LONG = 480
PHONE_ASPECT = 19.5 / 9.0

RETROPAD = {"a", "b", "x", "y", "l", "l1", "r", "r1", "l2", "r2", "l3", "r3", "start", "select", "up", "down", "left", "right"}
BUTTON_CANON = {"l1": "l", "r1": "r"}
MENU_IDS = {"save_state", "load_state", "state_slot_increase", "state_slot_decrease", "rewind", "reset",
            "shader_next", "shader_prev", "toggle_slowmotion", "slowmotion", "hold_slowmotion"}

# Same skin published twice in the source repo: keep the non-"old" copy.
SKIP_CFGS = {"gamepads/old/720-med.cfg"}

# Git author aliases → one display name (people who committed under several names).
AUTHOR_ALIASES = {
    "sergiobenrocha2": "Sérgio Benjamim",
    "hizzlekizzle": "hunterk",
    "nfore": "neil4 (nfore)",
    "neil4": "neil4 (nfore)",
    "Autechre": "twinaphex",
    "LibretroAdmin": "libretro",
}
ACRONYMS = {"nes": "NES", "snes": "SNES", "gba": "GBA", "gb": "GB", "gbc": "GBC", "psx": "PSX", "psp": "PSP", "n64": "N64",
            "nds": "NDS", "ds": "DS", "sms": "SMS", "cps": "CPS", "dos": "DOS", "pc": "PC", "fx": "FX", "3ds": "3DS",
            "neogeo": "Neo Geo", "retropad": "RetroPad", "rgpad": "RGPad", "gamecube": "GameCube", "dualshock": "DualShock",
            "psn": "PSN", "sg": "SG", "vic": "VIC", "pet": "PET", "msx": "MSX", "msx2": "MSX2", "hbmame": "HBMAME", "mame": "MAME",
            "fbneo": "FBNeo", "cd": "CD", "gp32": "GP32", "6x": "6x", "v1": "v1", "v2": "v2", "turbografx": "TurboGrafx",
            "wonderswan": "WonderSwan", "pokemini": "Pokémon Mini", "atari2600": "Atari 2600", "atari7800": "Atari 7800",
            "ab": "AB", "abxy": "ABXY", "cdi": "CD-i", "gameboy": "Game Boy", "frameadvance": "Frame Advance",
            "dpad": "D-pad", "ps": "PS", "sms": "SMS", "genesis3": "Genesis 3-button", "genesis6": "Genesis 6-button"}


# --------------------------------------------------------------------------------------------------------------------
# cfg model (mirrors OverlaySkin.kt)
# --------------------------------------------------------------------------------------------------------------------
@dataclass
class Desc:
    index: int
    id: str
    x: float
    y: float
    shape: str  # "radial" | "rect"
    rx: float
    ry: float
    image: str | None  # skin-relative path (already joined with the cfg dir)
    next_target: str | None

    @property
    def action(self) -> str:
        i = self.id
        if i in ("nul", "null", ""):
            return "image"
        if i in ("dpad_area", "abxy_area", "analog_left", "analog_right", "menu_toggle", "overlay_next",
                 "toggle_fast_forward", "hold_fast_forward"):
            return i
        parts = [p.strip() for p in i.split("|")]
        if parts and all(p in RETROPAD for p in parts):
            return "press"
        return "unsupported"

    @property
    def is_pad(self) -> bool:
        return self.action in ("press", "dpad_area", "abxy_area", "analog_left", "analog_right")

    @property
    def buttons(self) -> set[str]:
        a = self.action
        if a == "press":
            return {BUTTON_CANON.get(p.strip(), p.strip()) for p in self.id.split("|")}
        if a == "dpad_area":
            return {"up", "down", "left", "right"}
        if a == "abxy_area":
            return {"a", "b", "x", "y"}
        return set()


@dataclass
class Overlay:
    index: int
    name: str
    full_screen: bool
    normalized: bool
    background: str | None
    aspect: float | None
    block_x: bool
    block_y: bool
    next: str | None
    descs: list[Desc] = field(default_factory=list)

    @property
    def orientation(self) -> str | None:
        lower = self.name.lower()
        if "portrait" in lower:
            return "portrait"
        if "landscape" in lower:
            return "landscape"
        if self.aspect is not None and self.aspect < 1:
            return "portrait"
        if self.aspect is not None and self.aspect > 1:
            return "landscape"
        return None

    @property
    def has_pad(self) -> bool:
        return any(d.is_pad for d in self.descs)

    @property
    def has_analog(self) -> bool:
        return any(d.action in ("analog_left", "analog_right") for d in self.descs)

    @property
    def hidden(self) -> bool:
        lower = self.name.lower()
        return "hidden" in lower or lower == "hide" or lower.startswith("hide")

    @property
    def menu_like(self) -> bool:
        return not self.has_pad and any(d.action == "unsupported" and d.id in MENU_IDS for d in self.descs)

    def design_aspect(self, landscape: bool) -> float:
        return self.aspect if self.aspect else (16 / 9 if landscape else 9 / 16)

    def inferred_orientation(self, images: "ImageCache") -> str | None:
        """Orientation the app sees, or — for unnamed overlays without aspect — the background image's aspect."""
        o = self.orientation
        if o is not None:
            return o
        bg = images.get(self.background)
        if bg is None:
            return None
        ratio = bg.width / bg.height
        if 0.87 <= ratio <= 1.15:  # square low-res art meant to stretch: no orientation
            return None
        return "landscape" if ratio > 1 else "portrait"


@dataclass
class Cfg:
    overlays: list[Overlay]
    kv: dict[str, str]
    lines: list[str]

    def by_name(self, name: str | None) -> Overlay | None:
        if not name:
            return None
        for o in self.overlays:
            if o.name.lower() == name.lower():
                return o
        return None

    def after(self, o: Overlay) -> Overlay | None:
        return self.overlays[(o.index + 1) % len(self.overlays)] if self.overlays else None

    def usable(self) -> list[Overlay]:
        return [o for o in self.overlays if o.has_pad and not o.hidden and not o.menu_like]

    def pick(self, landscape: bool, screen_aspect: float, prefer_analog: bool = False) -> Overlay | None:
        wanted = "landscape" if landscape else "portrait"
        usable = self.usable()
        oriented = [o for o in usable if o.orientation == wanted] or [o for o in usable if o.orientation is None] or usable
        if not oriented:
            return None
        return sorted(oriented, key=lambda o: (
            (not o.has_analog) if prefer_analog else o.has_analog,
            "popout" in o.name.lower(),
            abs(o.design_aspect(landscape) - screen_aspect),
            o.index,
        ))[0]


def to_bool(v: str | None, default: bool) -> bool:
    if v is None:
        return default
    l = v.lower()
    if l in ("true", "1", "yes"):
        return True
    if l in ("false", "0", "no"):
        return False
    return default


def to_float(v: str | None) -> float | None:
    try:
        return float(v) if v is not None else None
    except ValueError:
        return None


def join_path(d: str, rel: str) -> str:
    parts: list[str] = []
    for seg in (d.replace("\\", "/") + "/" + rel.replace("\\", "/")).split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            if parts:
                parts.pop()
        else:
            parts.append(seg)
    return "/".join(parts)


LINE_RE = re.compile(r"^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$")


def parse_cfg(text: str, cfg_dir: str) -> Cfg:
    kv: dict[str, str] = {}
    lines = text.splitlines()
    for raw in lines:
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        eq = line.find("=")
        if eq <= 0:
            continue
        key = line[:eq].strip()
        value = line[eq + 1:].strip()
        if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        kv[key] = value
    try:
        count = int(kv.get("overlays", "0"))
    except ValueError:
        count = 0
    overlays: list[Overlay] = []
    for i in range(count):
        p = f"overlay{i}_"
        try:
            dcount = int(kv.get(f"{p}descs", "0"))
        except ValueError:
            dcount = 0
        descs: list[Desc] = []
        for j in range(dcount):
            spec = kv.get(f"{p}desc{j}")
            if spec is None:
                continue
            parts = [s.strip() for s in spec.split(",")]
            if len(parts) < 6:
                continue
            x, y, rx, ry = to_float(parts[1]), to_float(parts[2]), to_float(parts[4]), to_float(parts[5])
            if None in (x, y, rx, ry):
                continue
            img = kv.get(f"{p}desc{j}_overlay", "").strip()
            descs.append(Desc(
                index=j, id=parts[0].lower(), x=x, y=y, shape="rect" if parts[3].lower() == "rect" else "radial",
                rx=rx, ry=ry, image=join_path(cfg_dir, img) if img else None,
                next_target=(kv.get(f"{p}desc{j}_next_target") or None),
            ))
        bg = kv.get(f"{p}overlay", "").strip()
        aspect = to_float(kv.get(f"{p}aspect_ratio"))
        overlays.append(Overlay(
            index=i, name=kv.get(f"{p}name", str(i)),
            full_screen=to_bool(kv.get(f"{p}full_screen"), True),
            normalized=to_bool(kv.get(f"{p}normalized"), True),  # same default as the app
            background=join_path(cfg_dir, bg) if bg else None,
            aspect=aspect if aspect and aspect > 0 else None,
            block_x=to_bool(kv.get(f"{p}block_x_separation"), False),
            block_y=to_bool(kv.get(f"{p}block_y_separation"), False),
            next=kv.get(f"{p}next"), descs=descs,
        ))
    return Cfg(overlays, kv, lines)


# --------------------------------------------------------------------------------------------------------------------
# preview rendering (mirrors SkinGeometry.placeOverlay + SkinPad.drawOverlay/resolved)
# --------------------------------------------------------------------------------------------------------------------
class ImageCache:
    def __init__(self, root: Path):
        self.root = root
        self.cache: dict[str, Image.Image | None] = {}

    def get(self, rel: str | None) -> Image.Image | None:
        if not rel:
            return None
        if rel not in self.cache:
            p = self.root / rel
            try:
                im = Image.open(p)
                im.load()
                self.cache[rel] = im.convert("RGBA")
            except Exception:
                self.cache[rel] = None
        return self.cache[rel]


def resolved_drawable(cfg: Cfg, overlay: Overlay, landscape: bool) -> list[Desc]:
    """Descs drawn for this overlay, with overlay_next buttons resolved like SkinPad.resolved."""
    wanted = "landscape" if landscape else "portrait"
    has_menu_button = any(d.action == "menu_toggle" and d.image for d in overlay.descs)
    out: list[Desc] = []
    for d in overlay.descs:
        a = d.action
        if a == "unsupported" or not d.image:
            continue
        if a == "overlay_next":
            target = cfg.by_name(d.next_target or overlay.next) or cfg.after(overlay)
            if target is None:
                continue
            if target.menu_like and has_menu_button:
                continue
            if target.orientation is not None and target.orientation != wanted and not target.menu_like:
                continue
        out.append(d)
    return out


def draw_mock_game(canvas: Image.Image, landscape: bool) -> None:
    from PIL import ImageDraw
    w, h = canvas.size
    aspect = 4 / 3
    dr = ImageDraw.Draw(canvas)
    if landscape:
        gh = h * 0.9
        gw = min(gh * aspect, w * 0.6)
        x0, y0 = (w - gw) / 2, (h - gh) / 2
        dr.rectangle([x0, y0, x0 + gw, y0 + gw / aspect], fill=(43, 43, 43, 255))
    else:
        gw = w
        y0 = h * 0.08
        dr.rectangle([0, y0, gw, y0 + gw / aspect], fill=(43, 43, 43, 255))


def render_preview(cfg: Cfg, images: ImageCache, landscape: bool) -> tuple[Image.Image, Overlay | None]:
    if landscape:
        w, h = PREVIEW_LONG, round(PREVIEW_LONG / PHONE_ASPECT)
    else:
        w, h = round(PREVIEW_LONG / PHONE_ASPECT), PREVIEW_LONG
    canvas = Image.new("RGBA", (w, h), (21, 21, 21, 255))
    draw_mock_game(canvas, landscape)
    overlay = cfg.pick(landscape, w / h)
    if overlay is None:
        return canvas, None
    inferred = overlay.inferred_orientation(images)
    if inferred is not None and inferred != ("landscape" if landscape else "portrait"):
        from PIL import ImageDraw
        dr = ImageDraw.Draw(canvas)
        label = "LANDSCAPE ONLY" if inferred == "landscape" else "PORTRAIT ONLY"
        tw = dr.textlength(label)
        dr.text(((w - tw) / 2, h / 2 - 6), label, fill=(120, 120, 120, 255))
        return canvas, None
    design = overlay.design_aspect(landscape)
    box_w, box_h, sep_x, sep_y = float(w), float(h), 0.0, 0.0
    if w / h > design:
        box_w = h * design
        sep_x = (w - box_w) / 2
    else:
        box_h = w / design
        sep_y = (h - box_h) / 2
    off_x, off_y = (w - box_w) / 2, (h - box_h) / 2
    shift_x = 0.0 if overlay.block_x else sep_x
    shift_y = 0.0 if overlay.block_y else sep_y

    bg = images.get(overlay.background)
    if bg is not None:
        if overlay.full_screen:
            bx, by, bw, bh = 0, 0, w, h
        else:
            bx, by, bw, bh = round(off_x), round(off_y), round(box_w), round(box_h)
        if bw > 0 and bh > 0:
            canvas.alpha_composite(bg.resize((bw, bh), Image.LANCZOS), (bx, by))

    for d in resolved_drawable(cfg, overlay, landscape):
        img = images.get(d.image)
        if img is None:
            continue
        cx = off_x + d.x * box_w + (-shift_x if d.x < 0.5 else shift_x if d.x > 0.5 else 0.0)
        cy = off_y + d.y * box_h + (-shift_y if d.y < 0.5 else shift_y if d.y > 0.5 else 0.0)
        dw, dh = int(d.rx * 2 * box_w), int(d.ry * 2 * box_h)
        if dw <= 0 or dh <= 0:
            continue
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        layer.paste(img.resize((dw, dh), Image.LANCZOS), (int(cx - dw / 2), int(cy - dh / 2)))
        canvas.alpha_composite(layer)
    return canvas, overlay


# --------------------------------------------------------------------------------------------------------------------
# naming / classification
# --------------------------------------------------------------------------------------------------------------------
def slug(s: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "-", s.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s) or "skin"


def humanize(stem: str) -> str:
    words = re.split(r"[\s_\-]+", stem.strip())
    out = []
    for w in words:
        if not w:
            continue
        lw = w.lower()
        out.append(ACRONYMS.get(lw, w if w[:1].isupper() else w.capitalize()))
    return " ".join(out)


def display_name(family: str, stem: str) -> str:
    if family == "Named_Overlays":
        # "Nintendo - Game Boy" style names are already good.
        return re.sub(r"\s+-\s+", " · ", stem.strip()) if " - " in stem else humanize(stem)
    return humanize(stem)


def systems_hint(family: str, stem: str) -> list[str]:
    text = f"{family} {stem}".lower().replace("_", " ").replace("-", " ")
    words = set(text.split())
    joined = text.replace(" ", "")

    def has(*keys: str) -> bool:
        return any(k in words for k in keys)

    if has("3ds") or "nintendo3ds" in joined:
        return ["3ds"]
    if has("nds", "ds", "dsi") or "nintendods" in joined or "neo-ds" in f"{family}".lower():
        return ["nds"]
    if has("psp") or "playstationportable" in joined:
        return ["psp"]
    # A DualShock is the PlayStation pad, and PS1 and PS2 share it. It is not a GameCube controller and
    # not a PSP, both of which have a shape of their own - offering it for those put a Sony pad on the
    # wrong machine and left 플레이스테이션 itself with nothing but the universal skins.
    if has("psx", "ps1", "ps", "playstation", "dualshock", "dual") and "portable" not in joined:
        return ["psx", "ps2"]
    # OneEmu draws two pads for Dolphin, and a "Nintendo - Wii" overlay is a Wii Remote: 1, 2, C, Z and
    # a nunchuk variant, none of which a GameCube controller has. It belongs to the Wii pad (PadProfile
    # key "gc-wii"), which keeps its own skin, and would be unusable on the GameCube one.
    if has("wii") and "wiiu" not in joined:
        return ["gc-wii"]
    if has("gamecube", "wiiu") or "gamecube" in joined:
        return ["gc"]
    if has("gba") or "gameboyadvance" in joined:
        return ["gba"]
    if has("gameboy", "gb", "gbc") or "gameboy" in joined:
        return ["gb", "gbc"]
    if has("nes", "famicom", "fds") or "entertainmentsystem" in joined and "super" not in joined:
        return ["nes"]
    if has("snes") or "supernintendo" in joined:
        return []
    if has("n64", "nintendo64") or "nintendo64" in joined:
        return []
    if "pocket" in joined:  # Neo Geo Pocket is a handheld
        return []
    if has("arcade", "mame", "neogeo", "fbneo", "cps", "hbmame", "fighter") or "neogeo" in joined:
        return ["arcade"]
    # Sega, one machine at a time: these were all dropped together, so 메가드라이브, 마스터 시스템 and
    # 게임 기어 never saw a skin of their own. A "genesis and sms" overlay belongs to both.
    sega = []
    if "genesis" in joined or "megadrive" in joined or has("md"):
        sega.append("md")
    if has("sms") or "mastersystem" in joined or "markiii" in joined:
        sega.append("sms")
    if "gamegear" in joined:
        sega.append("gg")
    if sega:
        return sega
    if has("32x", "saturn", "dreamcast", "sega"):
        return []  # Sega machines OneEmu has no core for
    if has("retropad", "quadpad", "rgpad", "flip", "phone", "720", "med", "one", "handed", "piixel", "basic", "opium", "box"):
        return ["*"]
    return []  # unknown console: not tied to a system, not universal either


def family_label(family: str) -> str:
    return {"Named_Overlays": "named", "Piixel-Gamepads": "piixel", "old": "old"}.get(family, family.lower())


# --------------------------------------------------------------------------------------------------------------------
# git attribution
# --------------------------------------------------------------------------------------------------------------------
def git_authors_by_path(repo: Path) -> dict[str, dict[str, int]]:
    """
    path → {author: weight} for everything under gamepads/. The author who added a file weighs most (10);
    later commits add 1 each, except repo-wide cleanups (more than 300 files in one commit), which are ignored.
    """
    out: dict[str, dict[str, int]] = {}

    def run(extra: list[str]) -> str:
        return subprocess.run(["git", "-C", str(repo), "log", "--name-only", "--format=%x01%an", *extra, "--", "gamepads"],
                              check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

    def ingest(log: str, weight: int, max_files: int) -> None:
        commits: list[tuple[str, list[str]]] = []
        for line in log.splitlines():
            if line.startswith("\x01"):
                commits.append((AUTHOR_ALIASES.get(line[1:].strip(), line[1:].strip()), []))
            elif line.strip() and commits:
                commits[-1][1].append(line.strip())
        for author, paths in commits:
            if not author or len(paths) > max_files:
                continue
            for path in paths:
                d = out.setdefault(path, {})
                d[author] = d.get(author, 0) + weight

    try:
        ingest(run(["--diff-filter=A"]), 10, 10_000)
        ingest(run([]), 1, 300)
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"warning: git log failed ({e}); every skin gets '{DEFAULT_AUTHOR}'", file=sys.stderr)
    return out


def authors_for(paths: Iterable[str], table: dict[str, dict[str, int]]) -> str:
    counts: dict[str, int] = {}
    for p in paths:
        for a, n in table.get(p, {}).items():
            if a.lower() in ("libretro", "github", "github actions") or not a:
                continue
            counts[a] = counts.get(a, 0) + n
    if not counts:
        return DEFAULT_AUTHOR
    ranked = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    return ", ".join(a for a, _ in ranked[:3])


# --------------------------------------------------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------------------------------------------------
@dataclass
class Built:
    entry: dict
    files: list[Path]


def normalize_legacy(cfg: Cfg, images: ImageCache) -> tuple[Cfg, bool, list[str]]:
    """
    Old cfgs (gamepads/old, 720-med …) give desc coordinates in background-image pixels and omit `normalized`
    (RetroArch's default is false, the app's parser defaults to true). Convert them to normalized coordinates
    so the app places them correctly. Returns (cfg, changed, rewritten desc lines).
    """
    changed = False
    replacements: dict[str, str] = {}
    for o in cfg.overlays:
        pixelish = any(d.x > 1.0 or d.y > 1.0 or d.rx > 1.0 or d.ry > 1.0 for d in o.descs)
        explicit_false = cfg.kv.get(f"overlay{o.index}_normalized", "").lower() in ("false", "0", "no")
        if not pixelish and not explicit_false:
            continue
        bg = images.get(o.background)
        if bg is None:
            continue
        w, h = bg.size
        for d in o.descs:
            d.x, d.y, d.rx, d.ry = d.x / w, d.y / h, d.rx / w, d.ry / h
            replacements[f"overlay{o.index}_desc{d.index}"] = f'"{d.id},{d.x:.6f},{d.y:.6f},{d.shape},{d.rx:.6f},{d.ry:.6f}"'
        o.normalized = True
        replacements[f"overlay{o.index}_normalized"] = "true"
        if o.aspect is None and o.background and not (0.87 <= w / h <= 1.15):
            # Pixel-art pads were drawn for the background's aspect: pin it and keep image and hit zones together
            # (background in the design box, no separation shift) instead of stretching to the phone's aspect.
            o.aspect = w / h
            o.full_screen = False
            o.block_x = o.block_y = True
            replacements[f"overlay{o.index}_aspect_ratio"] = f"{w / h:.6f}"
            replacements[f"overlay{o.index}_full_screen"] = "false"
            replacements[f"overlay{o.index}_block_x_separation"] = "true"
            replacements[f"overlay{o.index}_block_y_separation"] = "true"
        changed = True
    if not changed:
        return cfg, False, []
    new_lines: list[str] = []
    seen: set[str] = set()
    for raw in cfg.lines:
        m = LINE_RE.match(raw.split("#", 1)[0])
        key = m.group(1) if m else None
        if key and key in replacements:
            new_lines.append(f"{key} = {replacements[key]}")
            seen.add(key)
        else:
            new_lines.append(raw)
    for key, val in replacements.items():
        if key not in seen:
            new_lines.append(f"{key} = {val}")
    cfg.lines = new_lines
    return cfg, True, new_lines


def rewrite_cfg_text(lines: list[str], path_map: dict[str, str], cfg_dir: str) -> str:
    """Rewrites every *_overlay image reference to its new zip-relative path (img/<file>)."""
    out: list[str] = []
    for raw in lines:
        code, _, comment = raw.partition("#")
        m = LINE_RE.match(code)
        if m and m.group(1).endswith("_overlay"):
            val = m.group(2).strip()
            unq = val[1:-1] if len(val) >= 2 and val.startswith('"') and val.endswith('"') else val
            joined = join_path(cfg_dir, unq)
            if joined in path_map:
                out.append(f"{m.group(1)} = {path_map[joined]}" + (f" #{comment}" if comment else ""))
                continue
        out.append(raw)
    return "\n".join(out) + "\n"


def shrink_png(src: Path) -> bytes:
    im = Image.open(src)
    im.load()
    w, h = im.size
    if max(w, h) > MAX_IMAGE_SIDE:
        s = MAX_IMAGE_SIDE / max(w, h)
        im = im.convert("RGBA").resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        return buf.getvalue()
    return src.read_bytes()


ORIENT_SUFFIX = re.compile(r"^(.*?)[-_ ]?(landscape|portrait)$", re.IGNORECASE)


def group_cfgs(cfgs: list[Path]) -> list[tuple[Path, Path]]:
    """
    (portrait cfg, landscape cfg) per skin. `foo-portrait.cfg` + `foo-landscape.cfg` (or `foo.cfg` +
    `foo_portrait.cfg`) in one directory become a single skin with two cfgs; everything else uses the same cfg
    for both orientations.
    """
    by_key: dict[tuple[Path, str], dict[str, Path]] = {}
    for c in cfgs:
        m = ORIENT_SUFFIX.match(c.stem)
        base, role = (m.group(1), m.group(2).lower()) if m and m.group(1) else (c.stem, "")
        by_key.setdefault((c.parent, base.lower()), {})[role] = c
    out: list[tuple[Path, Path]] = []
    for (_, _), roles in by_key.items():
        port = roles.get("portrait")
        land = roles.get("landscape") or roles.get("")
        if port is not None and land is not None and len(roles) == 2:
            out.append((port, land))
        else:
            for c in roles.values():
                out.append((c, c))
    return sorted(out, key=lambda t: (t[1].as_posix(), t[0].as_posix()))


def skin_identity(family: str, sub: str, stem: str) -> tuple[str, str]:
    """(id, name) without repeating the family in the cfg name (neo-retropad/neo-retropad-clear → neo-retropad-clear)."""
    fam = slug(family_label(family))
    st = slug(stem)
    subs = slug(sub) if sub else ""
    if st == fam or fam.endswith(st):
        sid, name = fam, humanize(family_label(family))
    elif st.startswith(fam):
        sid, name = st, humanize(stem)
    else:
        sid, name = f"{fam}-{st}", humanize(stem)
    if subs:
        sid = f"{fam}-{subs}-{st}" if not st.startswith(fam) else f"{st}-{subs}"
    if family == "Named_Overlays":
        name = display_name(family, stem)
    return sid, name


def build_skin(repo: Path, port_path: Path, land_path: Path, out: Path, base_url: str,
               authors: dict[str, dict[str, int]], used_ids: set[str]) -> tuple[Built | None, str]:
    paired = port_path != land_path
    rel_land = land_path.relative_to(repo).as_posix()  # gamepads/flat/retropad.cfg
    rel_port = port_path.relative_to(repo).as_posix()
    parts = PurePosixPath(rel_land).parts  # ('gamepads', 'flat', 'retropad.cfg')
    family = parts[1] if len(parts) > 2 else "misc"
    sub = "/".join(parts[2:-1])  # 'old' for flat/old/x.cfg, 'Retropad' for Piixel
    if family.lower() in ("example", "scummvm"):
        return None, "not a game pad"
    if rel_land in SKIP_CFGS:
        return None, "duplicate of another skin"
    stem = land_path.stem
    if paired:
        m = ORIENT_SUFFIX.match(stem)
        stem = m.group(1) if m and m.group(1) else stem

    images = ImageCache(repo)
    cfgs: dict[Path, Cfg] = {}
    fixes: list[str] = []
    for path in dict.fromkeys((port_path, land_path)):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            return None, f"unreadable: {e}"
        cfg = parse_cfg(text, PurePosixPath(path.relative_to(repo).as_posix()).parent.as_posix())
        if not cfg.overlays:
            return None, "no overlays"
        cfg, fixed, _ = normalize_legacy(cfg, images)
        if fixed:
            fixes.append(path.name)
        cfgs[path] = cfg
    port_cfg, land_cfg = cfgs[port_path], cfgs[land_path]
    usable = port_cfg.usable() + ([] if port_cfg is land_cfg else land_cfg.usable())
    if not usable:
        return None, "no overlay with RetroPad buttons"

    port_img, port_overlay = render_preview(port_cfg, images, landscape=False)
    land_img, land_overlay = render_preview(land_cfg, images, landscape=True)
    if port_overlay is None and land_overlay is None:
        return None, "nothing to show in either orientation"

    # Referenced images of every overlay (so overlay switching in the app keeps working).
    refs: list[str] = []
    for cfg in cfgs.values():
        for o in cfg.overlays:
            if o.background:
                refs.append(o.background)
            for d in o.descs:
                if d.image:
                    refs.append(d.image)
    refs = list(dict.fromkeys(refs))
    existing = [r for r in refs if (repo / r).is_file()]
    if not existing:
        return None, "no image files"

    buttons: set[str] = set()
    has_analog = False
    for o in usable:
        for d in o.descs:
            buttons |= d.buttons
            has_analog = has_analog or d.action in ("analog_left", "analog_right")
    if len(buttons) < 4:
        return None, f"only {len(buttons)} buttons"

    # Zip-relative names: img/<basename>, disambiguated when two source files share a name.
    path_map: dict[str, str] = {}
    taken: set[str] = set()
    for r in existing:
        base = PurePosixPath(r).name
        cand = f"img/{base}"
        n = 2
        while cand in taken:
            cand = f"img/{PurePosixPath(base).stem}-{n}{PurePosixPath(base).suffix}"
            n += 1
        taken.add(cand)
        path_map[r] = cand

    skin_id, name = skin_identity(family, sub, stem)
    base_id, n = skin_id, 2
    while skin_id in used_ids:
        skin_id = f"{base_id}-{n}"
        n += 1
    used_ids.add(skin_id)

    systems = systems_hint(family, stem)
    author = authors_for([rel_port, rel_land] + existing, authors)
    order = ["a", "b", "x", "y", "l", "r", "l2", "r2", "l3", "r3", "start", "select", "up", "down", "left", "right"]
    buttons_list = [b for b in order if b in buttons]

    port_name = f"{slug(port_path.stem)}.cfg"
    land_name = f"{slug(land_path.stem)}.cfg"
    modified = ["image paths rewritten to img/", "PNGs over 1024px downscaled"]
    if fixes:
        modified.append("legacy pixel coordinates normalised and design aspect pinned (" + ", ".join(fixes) + ")")
    if paired:
        modified.append("portrait and landscape cfgs merged into one skin")
    skin_json = {
        "id": skin_id,
        "name": name,
        "systems": [] if systems == ["*"] else systems,  # [] = neutral/universal for SkinStore
        "author": author,
        "license": LICENSE,
        "source": f"{SOURCE_REPO}/tree/master/{PurePosixPath(rel_land).parent.as_posix()}",
        "sourceCfg": rel_land if not paired else f"{rel_port} + {rel_land}",
        "portraitCfg": port_name,
        "landscapeCfg": land_name,
        "modified": "; ".join(modified),
    }

    out.mkdir(parents=True, exist_ok=True)
    zip_path = out / f"{skin_id}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path, cfg_name in dict.fromkeys(((port_path, port_name), (land_path, land_name))):
            cfg_dir = PurePosixPath(path.relative_to(repo).as_posix()).parent.as_posix()
            z.writestr(cfg_name, rewrite_cfg_text(cfgs[path].lines, path_map, cfg_dir))
        z.writestr("skin.json", json.dumps(skin_json, ensure_ascii=False, indent=2))
        for src_rel, dst in path_map.items():
            z.writestr(dst, shrink_png(repo / src_rel))
    port_file = out / f"{skin_id}-portrait.png"
    land_file = out / f"{skin_id}-landscape.png"
    port_img.convert("RGB").save(port_file, "PNG", optimize=True)
    land_img.convert("RGB").save(land_file, "PNG", optimize=True)

    data = zip_path.read_bytes()
    entry = {
        "id": skin_id,
        "name": name,
        "family": family_label(family),
        "author": author,
        "license": LICENSE,
        "systems": systems,
        "source": skin_json["sourceCfg"],
        "previewPortrait": f"{base_url}/{port_file.name}",
        "previewLandscape": f"{base_url}/{land_file.name}",
        "zip": f"{base_url}/{zip_path.name}",
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "buttons": buttons_list,
        "hasAnalog": has_analog,
        "hasPortrait": port_overlay is not None,
        "hasLandscape": land_overlay is not None,
    }
    return Built(entry, [zip_path, port_file, land_file]), "ok"


def ensure_clone(repo: Path) -> None:
    if (repo / "gamepads").is_dir():
        return
    repo.parent.mkdir(parents=True, exist_ok=True)
    print(f"cloning {SOURCE_REPO} → {repo} (blobless, sparse: gamepads/)")
    subprocess.run(["git", "clone", "--filter=blob:none", "--sparse", SOURCE_REPO, str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "sparse-checkout", "set", "gamepads"], check=True)


def upload(dist: Path, gh_repo: str, tag: str, prune: bool, catalog: dict) -> None:
    def gh(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
        return subprocess.run(["gh", *args], check=check, capture_output=capture, text=True)

    exists = gh("release", "view", tag, "--repo", gh_repo, check=False, capture=True).returncode == 0
    notes = (
        "OneEmu 온라인 패드 스킨 카탈로그. 이 릴리스는 고정 태그 `skins`로 계속 갱신됩니다.\n\n"
        "- 앱이 **설정 → 패드 레이아웃 → 패드 스킨 → 온라인** 에서 `catalog.json`을 읽어 미리보기와 내려받기를 제공합니다.\n"
        "- 모든 스킨은 [libretro/common-overlays](https://github.com/libretro/common-overlays) 저장소에서 가져왔으며 "
        "**CC BY 4.0** 라이선스를 따릅니다. 제작자는 `catalog.json` 과 각 zip 의 `skin.json` 에 표시됩니다.\n"
        "- 변경 사항: 이미지 경로를 `img/` 로 정리, 1024px 초과 PNG 축소, 구형 픽셀 좌표 cfg 는 정규화 좌표로 변환.\n"
        f"- 스킨 {len(catalog['skins'])}개 · 생성 시각 {catalog['generatedAt']}\n"
        "- `scripts/build-skin-catalog.py` 로 다시 생성합니다 (scripts/README-skins.md)."
    )
    if not exists:
        print(f"creating release {tag}")
        gh("release", "create", tag, "--repo", gh_repo, "--prerelease", "--title", "Pad skins catalog", "--notes", notes)
    else:
        gh("release", "edit", tag, "--repo", gh_repo, "--prerelease", "--title", "Pad skins catalog", "--notes", notes)

    files = sorted(p for p in dist.iterdir() if p.is_file() and p.name != "catalog.json")
    batch = 40
    for i in range(0, len(files), batch):
        chunk = files[i:i + batch]
        print(f"uploading {i + 1}-{i + len(chunk)} / {len(files)}")
        for attempt in range(3):
            r = gh("release", "upload", tag, *map(str, chunk), "--repo", gh_repo, "--clobber", check=False)
            if r.returncode == 0:
                break
            print(f"  retry {attempt + 1}")
            time.sleep(5)
        else:
            sys.exit("upload failed")
    # catalog.json last, so a client never sees a catalog pointing at files not yet uploaded.
    gh("release", "upload", tag, str(dist / "catalog.json"), "--repo", gh_repo, "--clobber")

    if prune:
        r = gh("release", "view", tag, "--repo", gh_repo, "--json", "assets", capture=True)
        keep = {p.name for p in files} | {"catalog.json"}
        for a in json.loads(r.stdout).get("assets", []):
            if a["name"] not in keep:
                print(f"deleting stale asset {a['name']}")
                gh("release", "delete-asset", tag, a["name"], "--repo", gh_repo, "--yes", check=False)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo-dir", type=Path, default=Path("build/skin-catalog/common-overlays"),
                    help="libretro/common-overlays checkout (cloned when missing)")
    ap.add_argument("--out", type=Path, default=Path("build/skin-catalog/dist"))
    ap.add_argument("--gh-repo", default="Manggome/OneEmu")
    ap.add_argument("--tag", default="skins")
    ap.add_argument("--upload", action="store_true", help="upload to the rolling GitHub release with gh")
    ap.add_argument("--prune", action="store_true", help="with --upload: delete release assets not produced by this run")
    ap.add_argument("--only", default="", help="substring filter on the cfg path (debugging)")
    ap.add_argument("--max-total-mb", type=float, default=300.0)
    args = ap.parse_args()

    ensure_clone(args.repo_dir)
    repo = args.repo_dir.resolve()
    out = args.out.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    base_url = f"https://github.com/{args.gh_repo}/releases/download/{args.tag}"

    authors = git_authors_by_path(repo)
    cfgs = sorted(p for p in (repo / "gamepads").rglob("*.cfg") if "/src/" not in p.as_posix())
    if args.only:
        cfgs = [p for p in cfgs if args.only in p.as_posix()]
    used_ids: set[str] = set()
    entries: list[dict] = []
    skipped: list[tuple[str, str]] = []
    for port_path, land_path in group_cfgs(cfgs):
        try:
            built, why = build_skin(repo, port_path, land_path, out, base_url, authors, used_ids)
        except Exception as e:  # keep going; one broken cfg must not kill the catalog
            built, why = None, f"error: {e!r}"
        rel = land_path.relative_to(repo).as_posix() + ("" if port_path == land_path else f" + {port_path.name}")
        if built is None:
            skipped.append((rel, why))
            print(f"skip  {rel}: {why}")
        else:
            entries.append(built.entry)
            print(f"ok    {rel} → {built.entry['id']} ({built.entry['size'] // 1024} KB, {len(built.entry['buttons'])} buttons)")

    # Size budget: drop -hires / duplicate-looking variants first when over budget.
    total = sum(p.stat().st_size for p in out.iterdir())
    if total > args.max_total_mb * 1024 * 1024:
        for e in sorted(entries, key=lambda e: -e["size"]):
            if "hires" in e["id"] and any(x["id"] == e["id"].replace("-hires", "") for x in entries):
                for f in out.glob(f"{e['id']}*"):
                    total -= f.stat().st_size
                    f.unlink()
                entries.remove(e)
                if total <= args.max_total_mb * 1024 * 1024:
                    break

    entries.sort(key=lambda e: (e["family"], e["name"]))
    catalog = {
        "schema": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": SOURCE_REPO,
        "license": LICENSE,
        "skins": entries,
    }
    (out / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(p.stat().st_size for p in out.iterdir())
    fams: dict[str, int] = {}
    for e in entries:
        fams[e["family"]] = fams.get(e["family"], 0) + 1
    print(f"\n{len(entries)} skins, {len(skipped)} skipped, {total / 1024 / 1024:.1f} MB in {out}")
    print("families: " + ", ".join(f"{k}={v}" for k, v in sorted(fams.items())))
    (out.parent / "skipped.txt").write_text("\n".join(f"{p}\t{w}" for p, w in skipped) + "\n")

    if args.upload:
        upload(out, args.gh_repo, args.tag, args.prune, catalog)
        print(f"uploaded to https://github.com/{args.gh_repo}/releases/tag/{args.tag}")


if __name__ == "__main__":
    main()
