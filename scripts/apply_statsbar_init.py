#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
p=r/'app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt'
s=p.read_text()
if 'super.setOnClickListener(null)' not in s:
    anchor='''        inflate(context, R.layout.layout_connect_bar, this)'''
    if anchor not in s: raise SystemExit('ConnectBar inflate anchor missing')
    s=s.replace(anchor,anchor+'''\n        // StatsBar binds tx/rx/status lazily in setOnClickListener; initialize\n        // them before MainActivity.changeState() can call updateSpeed().\n        super.setOnClickListener(null)''',1)
p.write_text(s);print('PASS: StatsBar fields initialized before startup state update')
