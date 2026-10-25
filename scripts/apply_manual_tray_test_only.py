#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';s=p.read_text()
old='''        if (state == BaseService.State.Connected && previousState != state) {
            refreshNetworkCard()
            handshakeJob?.cancel()
            lifecycleScope.launch {
                delay(500)
                if (lastObservedState == BaseService.State.Connected && handshakeJob?.isActive != true) runHandshakeTests()
            }
        } else if (state != BaseService.State.Connected && previousState != state) {'''
new='''        if (state == BaseService.State.Connected && previousState != state) {
            handshakeJob?.cancel()
        } else if (state != BaseService.State.Connected && previousState != state) {'''
if old not in s: raise SystemExit('auto tray test block not found')
s=s.replace(old,new,1)
# Selector changes should not start a hidden automatic test either.
s=s.replace('''        refreshConfigurationProfileState()
        refreshNetworkCard()
        runHandshakeTests()
        runOnDefaultDispatcher''','''        refreshConfigurationProfileState()
        runOnDefaultDispatcher''',1)
p.write_text(s);print('PASS: tray urlTest only starts from manual click')
