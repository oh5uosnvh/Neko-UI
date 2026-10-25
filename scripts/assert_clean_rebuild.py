#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
for p in [r/'app/src/main/java',r/'app/src/main/res']:
 for f in p.rglob('*'):
  if f.is_file() and f.suffix in {'.kt','.java','.xml'}:
   s=f.read_text(errors='ignore')
   assert not any(x in s for x in ['IpQuality','OutboundIp','NetworkPrivacy','bar_ip_info','outbound_ip','TrayNetworkLayout']),f
assert 'ProfileBatchStore.append' in (r/'app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt').read_text()
print('PASS clean official rebuild: no IP; batch import present')
