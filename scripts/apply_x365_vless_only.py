#!/usr/bin/env python3
from pathlib import Path
import re,sys
r=Path(sys.argv[1]).resolve()
p=r/'app/src/main/java/io/nekohasekai/sagernet/ktx/Formats.kt';s=p.read_text()
# x365:// is not a public protocol header anymore; VLESS#x365 is the only input.
s=re.sub(r'''\n\s*} else if \(startsWith\("x365://"\)\) \{.*?\n\s*\}''','',s,count=1,flags=re.S)
p.write_text(s)
p=r/'app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt';s=p.read_text()
s=s.replace('''        if (link.startsWith("x365://")) alterId = -2
''','')
s=s.replace('''.replace("x365://", "https://")''','')
p.write_text(s)
print('PASS: public x365:// header removed; only VLESS UUID#x365 remains')
