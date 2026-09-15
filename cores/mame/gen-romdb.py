#!/usr/bin/env python3
"""Builds the current-MAME arcade ROM-doctor assets from a full `mame -listxml` dump of the pinned version:

  assets/romdb.tsv.gz  — same schema as cores/mame2010/assets/romdb.tsv.gz (read by library/ArcadeRomCheck.kt)
  assets/titles.tsv    — name  description  year  manufacturer (read by library/ArcadeTitles.kt)

The built Android .so cannot be run on the host, so the XML comes from progettosnaps' "MAME Dats" pack for the
same MAME version (XML/mame_<n>_0.<ver>.xml = the unmodified `mame -listxml` output of the official build;
redistributable metadata). Version: see MAME_VERSION below — keep it in sync with the version the pinned
libretro/mame commit reports (grep BARE_BUILD_VERSION in src/makefile).

Usage: python3 gen-romdb.py [--xml PATH]
  Without --xml the pack is downloaded to build/dats/ (needs 7z, 7zz or bsdtar to extract). Paths are relative
  to this script. The clone in src/ must exist: src/mame/arcade.flt (the SUBTARGET=arcade driver filter that
  build.sh compiles) decides which machines the core actually contains.

romdb columns (tab separated), one line per <machine> kept:
  name  description  cloneof  romof  bios  sampleof  needsSamples  disks  runnable  roms  status  sourcefile  emulation  color  sound  graphic
  - status/emulation: <driver status="good|imperfect|preliminary" emulation="...">.
  - sourcefile: <machine sourcefile>, e.g. "sega/stv.cpp" (current MAME nests drivers in manufacturer folders;
    0.139 wrote "stv.c"). ArcadeRomCheck.isUnstableDriver compares basenames without extension.
  - color/sound/graphic: modern MAME has no <driver color/sound/graphic>; these hold the <feature> status
    for type palette / sound / graphics ("imperfect" | "unemulated" | "").
  - bios: the BIOS set zip (e.g. "neogeo", "stvbios") found by following the romof chain; "" if none.
  - sampleof: zip under <system>/mame/samples/ this machine loads samples from ("" if none).
  - needsSamples: 1 when the machine lists <sample> entries.
  - disks: number of <disk> (CHD) entries; CHDs go in <romdir>/<machine>/<disk>.chd.
  - runnable: 0 for isbios="yes" or runnable="no", 1 otherwise.
  - roms: ';'-separated tokens "name:size:crc[:merge[:bios]]"
      merge = "" own file, "=" merged from the romof set under the same name, else the file name in the romof set.
      bios  = <rom bios> = the <biosset> this file belongs to ("" for normal files).
    <rom> entries without a crc (status="nodump") and optional="yes" entries are omitted.
Kept machines: not isdevice, and either the machine's sourcefile is included by arcade.flt (minus "-name" exclusions,
plus "+name" inclusions). BIOS sets are kept (runnable=0) so the checker can follow romof chains.
Lines starting with '#' are comments.
"""
import gzip
import os
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

MAME_VERSION = "289"   # MAME 0.289 (the version the pinned libretro/mame commit builds)
PACK_URL = f"https://www.progettosnaps.net/download/?tipo=dat_mame&file=/dats/MAME/packs/MAME_Dats_{MAME_VERSION}.7z"

HERE = os.path.dirname(os.path.abspath(__file__))
FLT = os.path.join(HERE, "src", "src", "mame", "arcade.flt")
DATS_DIR = os.path.join(HERE, "build", "dats")
OUT = os.path.join(HERE, "assets", "romdb.tsv.gz")
TITLES = os.path.join(HERE, "assets", "titles.tsv")
MAX_BYTES = 6 * 1024 * 1024


def esc(s: str) -> str:
    """Rom tokens use ':' and ';' as separators; escape them (MAME names practically never contain them)."""
    return s.replace("%", "%25").replace(":", "%3A").replace(";", "%3B")


def clean(s: str) -> str:
    return (s or "").replace("\t", " ").replace("\n", " ").strip()


def fetch_xml() -> str:
    """Download + extract the progettosnaps pack; returns the XML path."""
    os.makedirs(DATS_DIR, exist_ok=True)
    existing = [f for f in os.listdir(DATS_DIR) if f.endswith(f"_0.{MAME_VERSION}.xml")]
    if existing:
        return os.path.join(DATS_DIR, existing[0])
    pack = os.path.join(DATS_DIR, f"MAME_Dats_{MAME_VERSION}.7z")
    if not os.path.isfile(pack):
        print(f"downloading {PACK_URL}")
        req = urllib.request.Request(PACK_URL, headers={"User-Agent": "Mozilla/5.0 (OneEmu gen-romdb)"})
        with urllib.request.urlopen(req) as r, open(pack + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(pack + ".part", pack)
    member = f"XML/mame_*_0.{MAME_VERSION}.xml"
    if shutil.which("7z") or shutil.which("7zz"):
        tool = shutil.which("7z") or shutil.which("7zz")
        subprocess.run([tool, "e", "-y", f"-o{DATS_DIR}", pack, member], check=True, stdout=subprocess.DEVNULL)
    elif shutil.which("bsdtar"):
        subprocess.run(["bsdtar", "-xf", pack, "-C", DATS_DIR, "--strip-components", "1", member], check=True)
    else:
        sys.exit("need 7z, 7zz or bsdtar to extract the DAT pack")
    existing = [f for f in os.listdir(DATS_DIR) if f.endswith(f"_0.{MAME_VERSION}.xml")]
    if not existing:
        sys.exit(f"XML not found in {pack}")
    return os.path.join(DATS_DIR, existing[0])


def parse_filter(path):
    """src/mame/arcade.flt: source paths to include, '-name' machines to drop, '+name' machines to add."""
    sources, minus, plus = set(), set(), set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            text = line.split("//", 1)[0].strip()
            if not text or text.startswith("#"):
                continue
            if text.startswith("-"):
                minus.add(text[1:].strip())
            elif text.startswith("+"):
                plus.add(text[1:].strip())
            else:
                sources.add(text.strip("\"'"))
    return sources, minus, plus


def main() -> int:
    xml_path = None
    args = sys.argv[1:]
    if args[:1] == ["--xml"] and len(args) >= 2:
        xml_path = args[1]
    elif args:
        print(__doc__)
        return 2
    if xml_path is None:
        xml_path = fetch_xml()
    if not os.path.isfile(xml_path):
        print(f"missing {xml_path}", file=sys.stderr)
        return 2
    if not os.path.isfile(FLT):
        print(f"missing {FLT}: run build.sh (or clone libretro/mame into cores/mame/src) first", file=sys.stderr)
        return 2
    sources, minus, plus = parse_filter(FLT)
    print(f"arcade.flt: {len(sources)} source files, -{len(minus)} +{len(plus)} machines")

    games = {}
    order = []
    build = ""
    total = dropped_dev = dropped_flt = skipped_optional = 0
    for ev, el in ET.iterparse(xml_path, events=("start", "end")):
        if ev == "start":
            if el.tag == "mame":
                build = el.get("build") or ""
            continue
        if el.tag != "machine":
            continue
        total += 1
        name = el.get("name")
        src = el.get("sourcefile") or ""
        keep = el.get("isdevice") != "yes"
        if keep:
            if name in minus:
                keep = False
            elif name not in plus and src not in sources:
                keep = False
                dropped_flt += 1
        else:
            dropped_dev += 1
        if not keep:
            el.clear()
            continue
        roms = []
        for r in el.findall("rom"):
            crc = (r.get("crc") or "").lower()
            if not crc:
                continue  # nodump: not required
            if r.get("optional") == "yes":
                skipped_optional += 1
                continue
            rname = r.get("name") or ""
            merge = r.get("merge")
            if merge is None:
                mcol = ""
            elif merge == rname:
                mcol = "="
            else:
                mcol = esc(merge)
            bios = r.get("bios") or ""
            tok = f"{esc(rname)}:{int(r.get('size') or 0)}:{crc}"
            if mcol or bios:
                tok += f":{mcol}"
            if bios:
                tok += f":{esc(bios)}"
            roms.append(tok)
        samples = el.findall("sample")
        sampleof = el.get("sampleof") or (name if samples else "")
        drv = el.find("driver")
        drv_attr = (lambda k: (drv.get(k) or "") if drv is not None else "")
        feat = {f.get("type"): (f.get("status") or "") for f in el.findall("feature")}
        games[name] = {
            "name": name,
            "description": clean(el.findtext("description")),
            "sourcefile": src,
            "status": drv_attr("status"),
            "emulation": drv_attr("emulation"),
            "color": feat.get("palette", ""),
            "sound": feat.get("sound", ""),
            "graphic": feat.get("graphics", ""),
            "year": clean(el.findtext("year")),
            "manufacturer": clean(el.findtext("manufacturer")),
            "cloneof": el.get("cloneof") or "",
            "romof": el.get("romof") or "",
            "sampleof": sampleof,
            "needsSamples": 1 if samples else 0,
            "disks": len(el.findall("disk")),
            "runnable": 0 if (el.get("isbios") == "yes" or el.get("runnable") == "no") else 1,
            "roms": roms,
        }
        order.append(name)
        el.clear()

    # A BIOS set is a romof target that is not the game's parent (e.g. romof=stvbios, cloneof="") or isbios="yes".
    bios_sets = {g["romof"] for g in games.values() if g["romof"] and g["romof"] != g["cloneof"]}
    bios_sets |= {g["name"] for g in games.values() if g["runnable"] == 0}

    def bios_for(g):
        cur = g["romof"]
        seen = set()
        while cur and cur not in seen:
            seen.add(cur)
            if cur in bios_sets:
                return cur
            cur = games.get(cur, {}).get("romof", "")
        return ""

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    rom_count = 0
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9, newline="\n") as f:
        f.write(f"# generated by cores/mame/gen-romdb.py from `mame -listxml` build \"{build}\" (progettosnaps MAME Dats"
                f" {MAME_VERSION}), filtered by src/mame/arcade.flt (SUBTARGET=arcade)\n")
        f.write("# name\tdescription\tcloneof\tromof\tbios\tsampleof\tneedsSamples\tdisks\trunnable\troms"
                "\tstatus\tsourcefile\temulation\tcolor\tsound\tgraphic\n")
        for name in order:
            g = games[name]
            rom_count += len(g["roms"])
            f.write("\t".join([
                g["name"], g["description"], g["cloneof"], g["romof"], bios_for(g), g["sampleof"],
                str(g["needsSamples"]), str(g["disks"]), str(g["runnable"]), ";".join(g["roms"]),
                g["status"], g["sourcefile"], g["emulation"], g["color"], g["sound"], g["graphic"],
            ]) + "\n")
    with open(TITLES, "w", encoding="utf-8", newline="\n") as f:
        for name in order:
            g = games[name]
            f.write("\t".join([g["name"], g["description"], g["year"], g["manufacturer"]]) + "\n")

    size = os.path.getsize(OUT)
    missing_bios = sorted(b for b in bios_sets if b not in games)
    by_status = {k: sum(1 for g in games.values() if g["status"] == k) for k in ("good", "imperfect", "preliminary", "")}
    with_chd = sum(1 for g in games.values() if g["disks"])
    print(f"listxml build: {build}; {total} machines -> {len(games)} kept ({dropped_dev} devices, {dropped_flt} outside arcade.flt dropped)")
    print(f"{rom_count} roms ({skipped_optional} optional skipped), {len(bios_sets)} bios sets, {with_chd} machines with CHDs")
    print(f"driver status: {by_status}")
    print(f"-> {OUT} ({size / 1024:.0f} KB gzipped), {TITLES} ({os.path.getsize(TITLES) / 1024:.0f} KB)")
    if missing_bios:
        print(f"note: bios sets referenced but absent from the DAT: {missing_bios}")
    return 0 if size < MAX_BYTES else 1


if __name__ == "__main__":
    sys.exit(main())
