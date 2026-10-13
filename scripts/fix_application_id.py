#!/usr/bin/env python3
from pathlib import Path
import re,sys
APPLICATION_ID='com.nb4a.qiyiguo112'
root=Path(sys.argv[1]); p=root/'nb4a.properties'; s=p.read_text()
s,n=re.subn(r'(?m)^PACKAGE_NAME\s*=.*$', 'PACKAGE_NAME='+APPLICATION_ID, s)
if n!=1: raise SystemExit('PACKAGE_NAME anchor missing')
p.write_text(s)
for f in (root/'app/src/main/res').glob('values*/strings.xml'):
 t=f.read_text(); t=re.sub(r'(<string\s+name="app_name"[^>]*>).*?(</string>)',r'\1Neko\2',t); f.write_text(t)
print('PASS: package='+APPLICATION_ID+' label=Neko')
