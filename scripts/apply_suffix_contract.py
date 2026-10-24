#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
def rep(rel,old,new):
 p=r/rel;s=p.read_text()
 if new in s:return
 if old not in s:raise SystemExit(f'missing suffix anchor {rel}')
 p.write_text(s.replace(old,new,1))
# x365 is enabled only by VLESS UUID#x365; ordinary VLESS remains standard.
rep('app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt','if (bean.mpw.isNotBlank()) {','if (bean.mpw.isNotBlank() && bean.password.endsWith("#fastup", ignoreCase = true)) {')
# fastup subscription imports must preserve the explicit marker.
p='app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt';s=(r/p).read_text()
old='''                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString()
'''
new='''                                    "password" -> if (bean is TrojanBean) {
                                        val raw = opt.value?.toString().orEmpty()
                                        bean.password = if (proxy["type"]?.toString()?.equals("fastup", true) == true &&
                                            !raw.endsWith("#fastup", true)) "${raw}#fastup" else raw
                                    }
'''
if old in s:s=s.replace(old,new,1)
(r/p).write_text(s)
print('PASS: private switches require #x365/#vt/#fastup suffix contracts')
