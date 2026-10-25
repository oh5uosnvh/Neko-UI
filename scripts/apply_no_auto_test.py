#!/usr/bin/env python3
"""Remove the connect-time auto urlTest block; keep only manual tray click."""
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';s=p.read_text()
old='''            // 027/029: 连接成功自动 ping 一次主连接（延迟区直接出数值，不靠点击）。
            // 先给 500ms 稳定期再测（x365 魔改协议冷启动握手慢，配合 urlTest 内部重试）。
            runOnDefaultDispatcher {
                runCatching {
                    delay(500)
                    val elapsed = urlTest()
                    onMainDispatcher { binding.connectBar.showDelay(elapsed) }
                }.onFailure {
                    onMainDispatcher { binding.connectBar.showDelayError() }
                }
            }
'''
if old in s: s=s.replace(old,'',1)
elif '027/029' not in s: pass
else: raise SystemExit('unexpected auto test block variant')
p.write_text(s);print('PASS: tray delay test runs only from manual click')
