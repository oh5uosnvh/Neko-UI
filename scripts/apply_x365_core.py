#!/usr/bin/env python3
from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]).resolve(); mods=Path(sys.argv[2]).resolve()
src=mods/'Neko-365/libcore/protocol/x365'; dst=r/'libcore/protocol/x365';dst.mkdir(parents=True,exist_ok=True)
for n in ['conn.go','outbound.go']:
 shutil.copy2(src/n,dst/n)
p=r/'libcore/box_include.go';s=p.read_text()
if 'libcoreX365 "libcore/protocol/x365"' not in s:
 s=s.replace('"libcore/protocol/juicity"','"libcore/protocol/juicity"\n\tlibcoreX365 "libcore/protocol/x365"')
if 'libcoreX365.RegisterOutbound(registry)' not in s:
 s=s.replace('vless.RegisterOutbound(registry)','vless.RegisterOutbound(registry)\n\tlibcoreX365.RegisterOutbound(registry)')
p.write_text(s);print('PASS: x365 core source and registry installed')
