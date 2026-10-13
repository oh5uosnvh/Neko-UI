#!/usr/bin/env python3
"""Apply only the requested X365 VLESS and Blackstone/VT compatibility.
Snell/OIX overlays are deliberately not applied."""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
mods=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else root.parent/'mods'

def edit(rel, old, new):
 p=root/rel; s=p.read_text()
 if old not in s:
  if new.strip() in s: return
  raise SystemExit(f'missing protocol anchor: {rel}')
 p.write_text(s.replace(old,new,1))

def copy(src,dst):
 src=mods/src; dst=root/dst; dst.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,dst)

copy('compat/files/libcore/protocol/x365/vless.go','libcore/protocol/x365/vless.go')
if (mods/'compat/files/libcore/protocol/x365/vless_test.go').exists():
 copy('compat/files/libcore/protocol/x365/vless_test.go','libcore/protocol/x365/vless_test.go')

# VLESS UUID/password ending #x365 is the sole selector; ordinary VLESS stays standard.
edit('app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/StandardV2RayBean.java',
'''    public boolean isX365() {
        if (this instanceof VMessBean) {
            Integer aid = ((VMessBean) this).alterId;
            return aid != null && aid == -2;
        }
        return false;
    }''',
'''    public boolean isX365() {
        return this instanceof VMessBean && uuid != null && uuid.toLowerCase().endsWith("#x365");
    }''')
edit('app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt',
'''            this is VMessBean && isX365() -> "x365"''',
'''            this is VMessBean && isX365() -> "vless"''')
edit('app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt',
'''                type = "x365"''',
'''                type = "vless"''')
# Legacy x365 links retain the marker when normalized to VLESS.
edit('app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/VMessBean.java',
'''        alterId = alterId != null ? alterId : 0;

        if (isX365())''',
'''        alterId = alterId != null ? alterId : 0;
        if (alterId == -2) {
            if (uuid != null && !uuid.toLowerCase().endsWith("#x365")) uuid += "#x365";
            alterId = -1;
        }

        if (isX365())''')
box=root/'libcore/box_include.go'; s=box.read_text()
if 'libcoreX365.RegisterVLESSOutbound(registry)' not in s:
 s=s.replace('libcoreX365.RegisterOutbound(registry)','libcoreX365.RegisterOutbound(registry)\n\tlibcoreX365.RegisterVLESSOutbound(registry)')
 box.write_text(s)
print('PASS: X365 VLESS suffix overlay applied; Snell/OIX excluded')
