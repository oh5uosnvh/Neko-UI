#!/usr/bin/env python3
from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]).resolve();root=Path(__file__).resolve().parents[1]/'files/core-probe'
for rel in ['app/src/main/aidl/io/nekohasekai/sagernet/aidl/ISagerNetService.aidl','app/src/main/java/io/nekohasekai/sagernet/bg/CoreOutboundIp.kt']:
 p=root/rel;d=r/rel;d.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,d)
# Service method is intentionally added in a separate script after the base
# class is inspected; do not silently patch the wrong ProxyInstance type.
p=r/'app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt';s=p.read_text()
if 'override fun outboundIp(proxyName: String' not in s:
 anchor='''        override fun urlTest(): Int {'''
 method='''        override fun outboundIp(proxyName: String, urlsJson: String, timeoutMs: Long): String {
            val instance = data?.proxy ?: error("core not started")
            return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                libcore.Libcore.outboundIpJSON(instance.box, proxyName, urlsJson, timeoutMs)
            }
        }

'''
 if anchor not in s: raise SystemExit('missing BaseService urlTest anchor')
 s=s.replace(anchor,method+anchor,1)
 p.write_text(s)
print('PASS: AIDL and BaseService core outbound probe binding installed')
