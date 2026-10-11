#!/usr/bin/env python3
import re
import pathlib
import sys

base = pathlib.Path(sys.argv[1])
final = pathlib.Path(sys.argv[2])
langs = ['values', 'values-zh-rCN', 'values-zh-rTW', 'values-fa', 'values-ja', 'values-ko', 'values-ru', 'values-uk']
pat = re.compile(r'<string\s+name="([^"]+)"')
for lang in langs:
    b = base / 'app/src/main/res' / lang / 'strings.xml'
    f = final / 'app/src/main/res' / lang / 'strings.xml'
    if not b.exists() or not f.exists():
        continue
    bt = b.read_text()
    ft = f.read_text()
    have = set(pat.findall(bt))
    missing = []
    for line in ft.splitlines():
        m = pat.search(line)
        if m and m.group(1) not in have:
            missing.append(line)
            have.add(m.group(1))
    if missing:
        pos = bt.rfind('</resources>')
        bt = bt[:pos] + '\n' + '\n'.join(missing) + '\n' + bt[pos:]
        b.write_text(bt)
        print(lang, len(missing))
