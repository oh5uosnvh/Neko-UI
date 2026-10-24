#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
for p in [r/'app/src/main/java',r/'app/src/main/res']:
 for f in p.rglob('*'):
  if f.is_file() and f.suffix in {'.kt','.java','.xml'}:
   s=f.read_text(errors='ignore')
   for token in ['IpQuality','OutboundIp','bar_ip_info','outbound_ip','NetworkPrivacy','ip_country','ip_value']:
    if token in s: raise SystemExit(f'IP residue {token}: {f}')
print('PASS: no IP query source/layout/resource residue')
