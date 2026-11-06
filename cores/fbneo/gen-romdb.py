#!/usr/bin/env python3
"""Builds the FBNeo arcade ROM-doctor assets from the DAT that libretro/FBNeo ships
(src/dats/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat, logiqx XML, Neo Geo/CPS1-2-3/PGM/… included):

  assets/romdb.tsv.gz  — same schema as cores/mame2010/assets/romdb.tsv.gz (read by library/ArcadeRomCheck.kt)
  assets/titles.tsv    — name  description  year  manufacturer (read by library/ArcadeTitles.kt)

Usage: python3 gen-romdb.py  (run from anywhere; paths are relative to this script; needs cores/fbneo/src,
i.e. run build.sh or clone the pinned commit first)

romdb columns (tab separated), one line per <game>:
  name  description  cloneof  romof  bios  sampleof  needsSamples  disks  runnable  roms  status  sourcefile  emulation  color  sound  graphic
  defaultbios  devices
  - status: <driver status> — FBNeo emits only "good" (BDF_GAME_WORKING) or "preliminary" (not working).
    The router (library/ArcadeCoreRouter.kt) runs Neo Geo/CPS games on FBNeo only when this is "good".
  - sourcefile: <game sourcefile>, e.g. "neogeo/d_neogeo.cpp", "capcom/d_cps2.cpp", "cps3/d_cps3.cpp" — the router's
    board test ([ArcadeRomCheck.FBNEO_BOARDS]) uses its file name ("d_neogeo").
  - emulation/color/sound/graphic: always "" (the FBNeo DAT has no such flags).
  - bios: the BIOS set zip (e.g. "neogeo", "pgm") found by following the romof chain; "" if none.
  - sampleof/needsSamples: FBNeo loads samples from <system>/fbneo/samples/<game>.zip.
  - disks: number of <disk> (CHD) entries (Killer Instinct only).
  - runnable: 0 for BIOS sets (isbios="yes"), 1 otherwise.
  - roms: ';'-separated tokens "name:size:crc[:merge]" (merge = "" own file, "=" same name in the romof set, else
    the file name there). FBNeo matches files by CRC across the game, parent and BIOS zips, like the checker.
  - defaultbios/devices: always "". FBNeo has no MAME-style <biosset>s; instead its ROM tables flag the files it
    can do without as BRF_OPT. The DAT drops that flag, so this script reads it back from the driver sources
    (static struct BurnRomInfo <x>RomDesc[] + STD_ROM_PICK / STDROMPICKEXT) and omits optional files, exactly
    as FBNeo's libretro loader only fails on missing non-BRF_OPT files. For neogeo.zip this leaves sp-s3.sp1
    (MVS Asia/Europe ver. 6), sm1.sm1, sfix.sfix and 000-lo.lo required and all other BIOS revisions optional.
  <rom status="nodump"> entries (no CRC) are omitted — FBNeo skips them.
Lines starting with '#' are comments.
"""
import glob
import gzip
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SRC_ROOT = os.path.join(HERE, "src")
DAT = os.path.join(SRC_ROOT, "dats", "FinalBurn Neo (ClrMame Pro XML, Arcade only).dat")
DRV_DIR = os.path.join(SRC_ROOT, "src", "burn", "drv")
OUT = os.path.join(HERE, "assets", "romdb.tsv.gz")
TITLES = os.path.join(HERE, "assets", "titles.tsv")
MAX_BYTES = 3 * 1024 * 1024

RE_ROMDESC = re.compile(r"struct\s+BurnRomInfo\s+(\w+)RomDesc\s*\[\s*\]\s*=\s*\{(.*?)\n\s*\}\s*;", re.S)
RE_ROMENTRY = re.compile(r"\{\s*\"([^\"]*)\"\s*,\s*(0x[0-9a-fA-F]+|\d+)\s*,\s*(0x[0-9a-fA-F]+|\d+)\s*,([^}]*)\}")
RE_PICK = re.compile(r"\bSTD_ROM_PICK\(\s*(\w+)\s*\)")
RE_PICKEXT = re.compile(r"\bSTDROMPICKEXT\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*\)")
RE_DRIVER = re.compile(r"struct\s+BurnDriver[DX]?\s+\w+\s*=\s*\{\s*\"([^\"]+)\"(.*?)\}\s*;", re.S)
RE_ROMINFO = re.compile(r"\b(\w+)RomInfo\b")
RE_DEFINE = re.compile(r"^[ \t]*#define[ \t]+(\w+)[ \t]*((?:[^\n]*\\\n)*[^\n]*)", re.M)
RE_IDENT = re.compile(r"\b([A-Za-z_]\w*)\b")


def esc(s: str) -> str:
    """Rom tokens use ':' and ';' as separators; escape them (FBNeo names practically never contain them)."""
    return s.replace("%", "%25").replace(":", "%3A").replace(";", "%3B")


def clean(s: str) -> str:
    return (s or "").replace("\t", " ").replace("\n", " ").strip()


RE_CTOKEN = re.compile(r"\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'|//[^\n]*|/\*.*?\*/", re.S)


def strip_comments(text: str) -> str:
    """Drops // and /* */ comments in one left-to-right pass, so "/*" inside a string or a // comment is ignored."""
    return RE_CTOKEN.sub(lambda m: " " if m.group(0)[0] == "/" else m.group(0), text)


def optional_crcs_by_game() -> dict:
    """game short name -> CRCs (lowercase hex) that every ROM table of that game flags BRF_OPT."""
    result = {}
    for path in glob.glob(os.path.join(DRV_DIR, "**", "*.cpp"), recursive=True):
        with open(path, encoding="latin-1") as f:
            text = strip_comments(f.read())  # text mode: CRLF sources read as LF
        # ROM rows shared through macros (CPS1's "#define A_BOARD_PLDS { "buf1", …, BRF_OPT }, …"), expanded below.
        macros = {}
        for m in RE_DEFINE.finditer(text):
            body = m.group(2).replace("\\\n", "\n")
            if RE_ROMENTRY.search(body):
                macros[m.group(1)] = body

        def expand(body: str, depth: int = 0) -> str:
            if depth > 3 or not macros:
                return body
            used = [n for n in set(RE_IDENT.findall(body)) if n in macros]
            return body + "".join("\n" + expand(macros[n], depth + 1) for n in used)

        # RomDesc prefix -> {crc: all entries with that crc optional?}
        descs = {}
        for m in RE_ROMDESC.finditer(text):
            table = {}
            for e in RE_ROMENTRY.finditer(expand(m.group(2))):
                crc = int(e.group(3), 0)
                if not e.group(1) or crc == 0:
                    continue
                opt = "BRF_OPT" in e.group(4)
                key = f"{crc:08x}"
                table[key] = table.get(key, True) and opt
            descs[m.group(1)] = table
        picks = {m.group(1): [m.group(1)] for m in RE_PICK.finditer(text)}
        for m in RE_PICKEXT.finditer(text):
            picks[m.group(1)] = [m.group(2), m.group(3)]
        for m in RE_DRIVER.finditer(text):
            info = RE_ROMINFO.search(m.group(2))
            if not info:
                continue
            merged = {}
            for d in picks.get(info.group(1), [info.group(1)]):
                for crc, opt in descs.get(d, {}).items():
                    merged[crc] = merged.get(crc, True) and opt
            opts = {crc for crc, opt in merged.items() if opt}
            if opts:
                result[m.group(1).lower()] = opts
    return result


def main() -> int:
    if not os.path.isfile(DAT):
        print(f"missing {DAT}: run cores/fbneo/build.sh (or clone libretro/FBNeo into cores/fbneo/src) first", file=sys.stderr)
        return 2
    optional = optional_crcs_by_game()
    games = {}
    order = []
    skipped_optional = 0
    for _, el in ET.iterparse(DAT, events=("end",)):
        if el.tag != "game":
            continue
        name = el.get("name")
        opts = optional.get(name.lower(), set())
        roms = []
        for r in el.findall("rom"):
            crc = (r.get("crc") or "").lower()
            if not crc or r.get("status") == "nodump" or int(crc, 16) == 0:
                continue  # nodump: never required
            if crc in opts:
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
            tok = f"{esc(rname)}:{int(r.get('size') or 0)}:{crc}"
            if mcol:
                tok += f":{mcol}"
            roms.append(tok)
        samples = el.findall("sample")
        drv = el.find("driver")
        games[name] = {
            "name": name,
            "description": clean(el.findtext("description")),
            "sourcefile": el.get("sourcefile") or "",
            "status": (drv.get("status") or "") if drv is not None else "",
            "year": clean(el.findtext("year")),
            "manufacturer": clean(el.findtext("manufacturer")),
            "cloneof": el.get("cloneof") or "",
            "romof": el.get("romof") or "",
            "sampleof": el.get("sampleof") or (name if samples else ""),
            "needsSamples": 1 if samples else 0,
            "disks": len(el.findall("disk")),
            "runnable": 0 if (el.get("isbios") == "yes" or el.get("runnable") == "no") else 1,
            "roms": roms,
        }
        order.append(name)
        el.clear()

    # A BIOS set is a romof target that is not the game's parent (mslug romof=neogeo, cloneof="") or isbios="yes".
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
        f.write("# generated by cores/fbneo/gen-romdb.py from dats/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat"
                " + BRF_OPT flags of src/burn/drv\n")
        f.write("# name\tdescription\tcloneof\tromof\tbios\tsampleof\tneedsSamples\tdisks\trunnable\troms"
                "\tstatus\tsourcefile\temulation\tcolor\tsound\tgraphic\tdefaultbios\tdevices\n")
        for name in order:
            g = games[name]
            rom_count += len(g["roms"])
            f.write("\t".join([
                g["name"], g["description"], g["cloneof"], g["romof"], bios_for(g), g["sampleof"],
                str(g["needsSamples"]), str(g["disks"]), str(g["runnable"]), ";".join(g["roms"]),
                g["status"], g["sourcefile"], "", "", "", "", "", "",
            ]) + "\n")
    with open(TITLES, "w", encoding="utf-8", newline="\n") as f:
        for name in order:
            g = games[name]
            f.write("\t".join([g["name"], g["description"], g["year"], g["manufacturer"]]) + "\n")

    size = os.path.getsize(OUT)
    missing_bios = sorted(b for b in bios_sets if b not in games)
    by_status = {k: sum(1 for g in games.values() if g["status"] == k) for k in ("good", "preliminary", "")}
    boards = {}
    for g in games.values():
        b = os.path.splitext(os.path.basename(g["sourcefile"]))[0]
        if b in ("d_neogeo", "d_cps1", "d_cps2", "d_cps3"):
            boards[b] = boards.get(b, 0) + 1
    print(f"{len(games)} games, {rom_count} roms ({skipped_optional} BRF_OPT skipped), {len(bios_sets)} bios sets")
    print(f"driver status: {by_status}; FBNeo-first boards: {boards}")
    print(f"-> {OUT} ({size / 1024:.0f} KB gzipped), {TITLES} ({os.path.getsize(TITLES) / 1024:.0f} KB)")
    if missing_bios:
        print(f"note: bios sets referenced but absent from the DAT: {missing_bios}")
    return 0 if size < MAX_BYTES else 1


if __name__ == "__main__":
    sys.exit(main())
