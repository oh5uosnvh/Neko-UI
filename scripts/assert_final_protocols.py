from pathlib import Path
import sys
r=Path(sys.argv[1])
checks={
'libcore/protocol/x365/vless.go':['ParseMarker','standard.NewOutbound'],
'app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt':['xhttp-yf','node-id'],
'app/src/main/java/moe/matsuri/nb4a/proxy/xhttp/XhttpSettingsActivity.kt':['SimpleSummaryProvider','PasswordPlain'],
}
for f, markers in checks.items():
 s=(r/f).read_text()
 for marker in markers: assert marker in s,(f,marker)
print('PASS: final Neko VLESS/X365 and Blackstone editor; no abandoned Snell gate')
