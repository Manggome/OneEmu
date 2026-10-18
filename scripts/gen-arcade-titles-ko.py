#!/usr/bin/env python3
"""Fills the clone column of app/src/main/assets/arcade_titles_ko.tsv (read by library/ArcadeTitles.kt).

The file is edited by hand: one line per game, `short name <TAB> Korean title`. The short name is usually the
MAME parent set. This script appends a third column listing every clone/regional set that shares the title, so
the app needs no clone table at runtime:

  short name  <TAB>  Korean title  <TAB>  clone clone clone ...

Clones come from the `cloneof` column of cores/<core>/assets/romdb.tsv.gz for all bundled arcade cores. Parent
links differ between MAME versions (sstriker is mahoudai's clone in 0.78 and its parent in 0.289), so a set
without its own line takes the title of:
  1. its cloneof parent in the first core (MAME 2003-Plus, MAME 2010, MAME) whose parent has a line, else
  2. the one titled set in its clone family (all sets linked by cloneof in any core); none or several → no title.
A set that has its own line never appears in a clone column (use that for regional suffixes, e.g. techromnu).
Bootlegs and hacks that MAME files under a parent but sell under another name ("Dream Land", bootleg of Bubble
Bobble) are left out: a clone whose manufacturer says bootleg/hack must start with the same word as its owner.
Every short name with a line must exist in some core's titles.tsv; the script fails otherwise.

Usage: python3 scripts/gen-arcade-titles-ko.py   (rewrites the file in place; comment lines are kept)
"""
import gzip
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLE = os.path.join(ROOT, "app", "src", "main", "assets", "arcade_titles_ko.tsv")
CORES = ["mame2003plus", "mame2010", "mame"]  # ArcadeCoreRouter.CORE_IDS order


def load_cloneof(core):
    out = {}
    with gzip.open(os.path.join(ROOT, "cores", core, "assets", "romdb.tsv.gz"), "rt", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            out[p[0]] = p[2]
    return out


def load_titles(core):
    """name -> (description, manufacturer)"""
    out = {}
    with open(os.path.join(ROOT, "cores", core, "assets", "titles.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2:
                out[p[0]] = (p[1], p[3] if len(p) > 3 else "")
    return out


def first_word(desc):
    words = ["".join(ch for ch in w if ch.isalnum()) for w in desc.lower().split()]
    words = [w for w in words if w and w != "the"]
    return words[0] if words else ""


def renamed_bootleg(clone, owner, info):
    desc, maker = info[clone]
    maker = maker.lower()
    return ("bootleg" in maker or "hack" in maker) and first_word(desc) != first_word(info[owner][0])


def main():
    cloneof = [load_cloneof(c) for c in CORES]
    info = {}
    for c in reversed(CORES):  # first core wins, like ArcadeTitles
        info.update(load_titles(c))
    games = set(info)

    lines, titled = [], {}
    with open(TABLE, encoding="utf-8") as f:
        for raw in f:
            raw = raw.rstrip("\r\n")
            if not raw.strip() or raw.startswith("#"):
                lines.append(raw)
                continue
            p = raw.split("\t")
            name, ko = p[0].strip(), p[1].strip()
            if name in titled:
                sys.exit(f"duplicate line: {name}")
            if name not in games:
                sys.exit(f"{name}: not in any core's titles.tsv")
            titled[name] = ko
            lines.append(name)

    # Clone families: undirected cloneof links across every core.
    family = {}

    def find(x):
        while family.setdefault(x, x) != x:
            family[x] = family[family[x]]
            x = family[x]
        return x

    for co in cloneof:
        for name, parent in co.items():
            if parent and name in games:
                family[find(name)] = find(parent)
    members = {}
    for t in titled:
        members.setdefault(find(t), []).append(t)

    clones = {t: [] for t in titled}
    for g in sorted(games):
        if g in titled:
            continue
        owner = next((co[g] for co in cloneof if co.get(g) in titled), None)
        if owner is None:
            fam = members.get(find(g), [])
            owner = fam[0] if len(fam) == 1 else None
        if owner is not None and not renamed_bootleg(g, owner, info):
            clones[owner].append(g)

    with open(TABLE, "w", encoding="utf-8", newline="\n") as f:
        for line in lines:
            if line in titled:
                cols = [line, titled[line]] + ([" ".join(clones[line])] if clones[line] else [])
                f.write("\t".join(cols) + "\n")
            else:
                f.write(line + "\n")
    print(f"{len(titled)} titled sets, {sum(len(c) for c in clones.values())} clone sets")


if __name__ == "__main__":
    main()
