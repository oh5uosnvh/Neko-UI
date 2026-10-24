#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve()
p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/TopBarController.kt';s=p.read_text()
old='''        var x = loc[0]
        // 右边顶出去 → 左移，让右边缘正好留出 margin
'''
new='''        // ProfileSelectActivity（链式代理手动选配置）里的 ☴ 在右侧，
        // 弹层右边缘对齐按钮右边缘；普通配置页仍沿用原来的屏幕边距算法。
        var x = if (host.select) loc[0] + anchor.width - width else loc[0]
        // 右边顶出去 → 左移，让右边缘正好留出 margin
'''
if old not in s: raise SystemExit('missing picker offset anchor')
p.write_text(s.replace(old,new,1));print('PASS: chain profile picker right-edge aligned to right-side ☴ button')
