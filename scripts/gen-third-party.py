#!/usr/bin/env python3
"""Regenerates THIRD-PARTY-LICENSES.md from every cores/<id>/core.json.

Run it after adding, removing or updating a core so the licence list cannot drift
away from what is actually shipped:

    python3 scripts/gen-third-party.py
"""
import glob
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'THIRD-PARTY-LICENSES.md')


def load_cores():
    cores = []
    for path in sorted(glob.glob(os.path.join(ROOT, 'cores', '*', 'core.json'))):
        d = json.load(open(path, encoding='utf-8'))
        cores.append({
            'id': d.get('id', ''),
            'name': d.get('displayName', ''),
            'license': d.get('license', '?'),
            'repo': d.get('sourceRepo', ''),
            'commit': (d.get('sourceCommit') or '')[:12],
            'download': d.get('distribution') == 'download',
            'systems': ', '.join(d.get('systems', [])),
        })
    return cores


def is_noncommercial(core):
    return 'non-commercial' in core['license'].lower() or 'noncommercial' in core['license'].lower()


def render(cores):
    out = []
    w = out.append
    w('<!-- scripts/gen-third-party.py 가 만듭니다. 직접 고치지 말고 스크립트를 다시 돌리세요. -->')
    w('# 포함된 오픈 소스 (Third-party licenses)\n')
    w('OneEmu 는 여러 오픈 소스 에뮬레이터 코어를 함께 배포합니다. 각 코어는 원래 프로젝트의')
    w('라이선스를 그대로 따릅니다.\n')

    noncom = [c for c in cores if is_noncommercial(c)]
    if noncom:
        w('## ⚠ 상업적 이용 제한\n')
        w('아래 코어는 **상업적 이용을 금지**합니다. 이 코어가 들어 있는 한 OneEmu 를')
        w('**판매하거나 유료 앱·광고 수익 목적으로 배포할 수 없습니다.** 무료 배포는 허용됩니다.\n')
        for c in noncom:
            where = '내려받기형' if c['download'] else 'APK 에 포함'
            w(f"- **{c['name']}** (`{c['id']}`, {where}) — {c['license']}")
        w('')
        w('상업적으로 배포하려면 이 코어들을 빼거나 상업적 이용이 가능한 코어로 바꿔야 합니다.\n')

    w('## 앱 코드\n')
    w('OneEmu 자체 코드는 **GPL-3.0-or-later** 입니다. GPL 코어와 함께 배포되므로 전체가')
    w('GPL 조건을 따릅니다. 전문은 [`LICENSE`](LICENSE) 에 있습니다.\n')

    w('## 코어\n')
    w('| 코어 | 담당 기종 | 라이선스 | 배포 | 출처 |')
    w('| --- | --- | --- | --- | --- |')
    for c in cores:
        mark = ' ⚠' if is_noncommercial(c) else ''
        where = '내려받기' if c['download'] else 'APK 포함'
        src = '-'
        if c['repo']:
            src = f"[{c['repo'].replace('https://github.com/', '')}]({c['repo']})"
            if c['commit']:
                src += f" `{c['commit']}`"
        w(f"| {c['name']} | {c['systems']} | {c['license']}{mark} | {where} | {src} |")
    w('')

    w('## 그 밖의 포함물\n')
    w('- **패드 스킨** — [libretro/common-overlays](https://github.com/libretro/common-overlays), CC BY 4.0.')
    w('  APK 에 들어간 것은 `app/src/main/assets/skins/CREDITS.md` 에 표기했습니다.')
    w('- **Dolphin 런타임 파일** (`cores/dolphin/assets/`) — Dolphin 이 자기 저장소에서 공개 배포하는')
    w('  파일만 복사했습니다. 폰트는 Droid Sans 기반으로 Dolphin 팀이 직접 만든 것이며')
    w('  (Apache-2.0, `GC/font-licenses.txt` 에 전문 포함), 닌텐도의 파일은 들어 있지 않습니다.\n')

    w('## 포함되지 않은 것\n')
    w('OneEmu 는 **게임 ROM, BIOS, 펌웨어, 암호화 키를 포함하거나 배포하지 않습니다.**')
    w('이 저장소에도, 배포하는 APK 에도 들어 있지 않습니다. 사용자가 합법적으로 소유한')
    w('기기와 게임에서 직접 추출한 파일만 사용해야 합니다.')
    return '\n'.join(out) + '\n'


if __name__ == '__main__':
    cores = load_cores()
    open(OUT, 'w', encoding='utf-8').write(render(cores))
    print(f'{OUT}: {len(cores)} cores, {sum(1 for c in cores if is_noncommercial(c))} non-commercial')
