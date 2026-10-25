#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';s=p.read_text()
import re
old='''        if (state == BaseService.State.Connected && previousState != state) {'''
start=s.find(old)
if start < 0: raise SystemExit('state change anchor missing')
end=s.find('''        } else if (state != BaseService.State.Connected && previousState != state) {''',start)
if end < 0: raise SystemExit('state change end anchor missing')
replacement='''        if (state == BaseService.State.Connected && previousState != state) {
            handshakeJob?.cancel()
'''
s=s[:start]+replacement+s[end:]
# Remove every automatic invocation; retain only the callback assignment that
# is reached from the delay-zone click.
s=re.sub(r'^(?!\s*binding\.connectBar\.onTestConnection)(\s*)runHandshakeTests\(\)\s*$', r'\1', s, flags=re.M)
# Selector changes should not start a hidden automatic test either.
s=s.replace('''        refreshConfigurationProfileState()
        refreshNetworkCard()
        runHandshakeTests()
        runOnDefaultDispatcher''','''        refreshConfigurationProfileState()
        runOnDefaultDispatcher''',1)
p.write_text(s);print('PASS: tray urlTest only starts from manual click')
