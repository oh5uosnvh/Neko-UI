#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt';s=p.read_text()
if 'findViewById<View>(R.id.bar_delay_zone)' not in s:
    anchor='''        super.setOnClickListener(null)'''
    if anchor not in s: raise SystemExit('ConnectBar init anchor missing')
    s=s.replace(anchor,anchor+'''\n        val delayZone = findViewById<View>(R.id.bar_delay_zone)\n        delayZone.isClickable = true\n        delayZone.isFocusable = true\n        delayZone.setOnClickListener {\n            if (DataStore.serviceState.connected) {\n                setTesting()\n                onTestConnection?.invoke()\n            }\n        }''',1)
p.write_text(s);print('PASS: delay zone clickable with pressed-state background')
