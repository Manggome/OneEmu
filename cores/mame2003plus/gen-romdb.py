#!/usr/bin/env python3
"""Builds assets/romdb.tsv.gz (the arcade ROM-doctor database) from src/metadata/mame2003-plus.xml.

Usage: python3 gen-romdb.py  (run from anywhere; paths are relative to this script)

Output: gzip'd TSV, one line per <game>, columns (tab separated):
  name  description  cloneof  romof  bios  sampleof  needsSamples  disks  runnable  roms  status  sourcefile  emulation  color  sound  graphic
  - status: normalised <driver status>: "good" | "imperfect" | "preliminary" | "" (no <driver>, i.e. BIOS sets).
      MAME 0.78 only knows good/preliminary/protection: "protection" (unemulated protection = game does not work)
      becomes "preliminary"; "good" with any imperfect/preliminary color/sound/graphic flag becomes "imperfect".
      The router (library/ArcadeCoreRouter.kt) sends "preliminary" games to MAME 2010 when that DAT rates them better.
  - sourcefile: <game sourcefile> (driver file, e.g. "stv.c") — lets the router avoid drivers known to crash on arm64.
  - emulation: the raw 0.78 <driver status> ("good"/"preliminary"/"protection"); color/sound/graphic: raw sub-flags.
  - bios: the BIOS set zip (e.g. "neogeo") found by following the romof chain; "" if none.
  - sampleof: zip under <system>/mame2003-plus/samples/ this game loads samples from ("" if none).
  - needsSamples: 1 when the game lists <sample> entries (samples are optional; sound is incomplete without them).
  - disks: number of <disk> (CHD) entries; > 0 means the game cannot run on MAME 2003-Plus (no CHD support built).
  - runnable: 0 for BIOS sets (runnable="no"), 1 otherwise.
  - roms: ';'-separated tokens "name:size:crc[:merge[:bios]]"
      merge = "" own file, "=" merged from the romof set under the same name, else the file name in the romof set.
      bios  = <biosset> name this file belongs to ("" for normal files); only one bios file per game is needed.
    <rom> entries without a crc (status="nodump") are omitted — MAME does not require them.
Lines starting with '#' are comments. The Kotlin reader is library/ArcadeRomCheck.kt.
"""
import gzip
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src", "metadata", "mame2003-plus.xml")
OUT = os.path.join(HERE, "assets", "romdb.tsv.gz")


def esc(s: str) -> str:
    """Rom tokens use ':' and ';' as separators; escape them (MAME names practically never contain them)."""
    return s.replace("%", "%25").replace(":", "%3A").replace(";", "%3B")


def main() -> int:
    games = {}
    order = []
    for _, el in ET.iterparse(SRC, events=("end",)):
        if el.tag != "game":
            continue
        name = el.get("name")
        roms = []
        for r in el.findall("rom"):
            crc = (r.get("crc") or "").lower()
            if not crc:
                continue  # nodump: not required
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
        raw_status = (drv.get("status") or "") if drv is not None else ""
        flags = {k: ((drv.get(k) or "") if drv is not None else "") for k in ("color", "sound", "graphic")}
        if raw_status in ("preliminary", "protection"):
            status = "preliminary"
        elif raw_status == "good":
            status = "imperfect" if any(v and v != "good" for v in flags.values()) else "good"
        else:
            status = ""
        games[name] = {
            "name": name,
            "description": (el.findtext("description") or "").replace("\t", " ").replace("\n", " "),
            "sourcefile": el.get("sourcefile") or "",
            "status": status,
            "emulation": raw_status,
            **flags,
            "cloneof": el.get("cloneof") or "",
            "romof": el.get("romof") or "",
            "sampleof": sampleof,
            "needsSamples": 1 if samples else 0,
            "disks": len(el.findall("disk")),
            "runnable": 0 if el.get("runnable") == "no" else 1,
            "roms": roms,
        }
        order.append(name)
        el.clear()

    # A BIOS set is a romof target that is not the game's parent (mslug romof=neogeo, cloneof="") or runnable="no".
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
        f.write("# generated by cores/mame2003plus/gen-romdb.py from metadata/mame2003-plus.xml\n")
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

    size = os.path.getsize(OUT)
    missing_bios = sorted(b for b in bios_sets if b not in games)
    by_status = {k: sum(1 for g in games.values() if g["status"] == k) for k in ("good", "imperfect", "preliminary", "")}
    print(f"{len(games)} games, {rom_count} roms, {len(bios_sets)} bios sets -> {OUT} ({size / 1024:.0f} KB gzipped)")
    print(f"driver status: {by_status}")
    if missing_bios:
        print(f"note: bios sets referenced but absent from the DAT: {missing_bios}")
    return 0 if size < 2 * 1024 * 1024 else 1


if __name__ == "__main__":
    sys.exit(main())
