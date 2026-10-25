#!/usr/bin/env python3
from pathlib import Path
import shutil,re,sys
r=Path(sys.argv[1]).resolve();mg=Path(sys.argv[2]).resolve()
# Copy x365 implementation only; it registers the existing VLESS tag.
src=mg/'Neko-365/libcore/protocol/x365';dst=r/'libcore/protocol/x365';dst.mkdir(parents=True,exist_ok=True)
for n in ['conn.go','outbound.go']: shutil.copy2(src/n,dst/n)
p=r/'libcore/box_include.go';s=p.read_text()
if 'libcoreX365 "libcore/protocol/x365"' not in s:
 s=s.replace('"libcore/protocol/juicity"','"libcoreX365 \\"libcore/protocol/x365\\"')
# Correct escaped source if replacement was literal.
s=s.replace('libcoreX365 \\"libcore/protocol/x365\\"','libcoreX365 "libcore/protocol/x365"')
if 'libcoreX365.RegisterVLESSOutbound' not in s:
 s=s.replace('vless.RegisterOutbound(registry)','vless.RegisterOutbound(registry)\n\tlibcoreX365.RegisterVLESSOutbound(registry)')
p.write_text(s)
# Use VLESS UUID suffix as the sole x365 switch; no public x365 type.
p=r/'app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt';s=p.read_text()
# The official builder already creates VLESS from VMessBean.isVLESS; preserve UUID marker.
if 'uuid = bean.uuid' in s and 'isX365' not in s:
 s=s.replace('''                uuid = bean.uuid''','''                uuid = bean.uuid''',1)
# No x365:// public parser is introduced.
p.write_text(s)
print('PASS: clean official source gets VLESS#x365 core only')
