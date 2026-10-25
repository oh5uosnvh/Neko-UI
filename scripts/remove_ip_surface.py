#!/usr/bin/env python3
from pathlib import Path
import re,sys
r=Path(sys.argv[1]).resolve()

def remove_method(s, marker):
    pos=s.find(marker)
    if pos<0:return s
    brace=s.find('{',pos)
    if brace<0:return s
    depth=0
    for i in range(brace,len(s)):
        if s[i]=='{':depth+=1
        elif s[i]=='}':
            depth-=1
            if depth==0:return s[:pos]+s[i+1:]
    raise SystemExit('unbalanced method '+marker)

# Remove bottom network card from both official and custom layouts.
for p in (r/'app/src/main/res').rglob('layout_connect_bar.xml'):
    s=p.read_text()
    s=re.sub(r'\s*<io\.nekohasekai\.sagernet\.widget\.TrayNetworkLayout\b.*?</io\.nekohasekai\.sagernet\.widget\.TrayNetworkLayout>','',s,flags=re.S)
    s=re.sub(r'\s*<!-- ⓘ 出站 IP 查询.*?</androidx\.appcompat\.widget\.AppCompatImageView>','',s,flags=re.S)
    p.write_text(s)

# Delete IP-only classes/resources.
for rel in [
 'app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt',
 'app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt',
 'app/src/main/java/io/nekohasekai/sagernet/ui/NetworkPrivacy.kt',
 'app/src/main/java/io/nekohasekai/sagernet/widget/TrayNetworkLayout.kt',
 'app/src/main/java/io/nekohasekai/sagernet/widget/TrayTapTargets.kt',
 'app/src/main/res/layout/layout_outbound_ip_dialog.xml',
 'app/src/main/res/drawable/ic_outbound_ip.xml']:
    p=r/rel
    if p.exists():p.unlink()

# Remove IP methods/imports from MainActivity without touching handshake/import methods.
p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';s=p.read_text()
for marker in ['    private fun observeNetworkResults()', '    private fun refreshNetworkCard()']:
    s=remove_method(s,marker)
s=re.sub(r'^\s*runCatching \{ IpQualityLookup\.prefetch\([^\n]*\}\s*\n','',s,flags=re.M)
s=re.sub(r'^\s*IpQualityLookup\.[A-Za-z0-9_]+\([^\n]*\)\s*\n','',s,flags=re.M)
s=re.sub(r'^\s*(?:observeNetworkResults|refreshNetworkCard)\(\)\s*\n','',s,flags=re.M)
s=s.replace('import kotlinx.coroutines.flow.collect\n','')
s=re.sub(r'^\s*binding\.connectBar\.renderNetwork\([^\n]*\)\s*\n','',s,flags=re.M)
p.write_text(s)

# Remove IP members/listeners/dialog code from every ConnectBar variant.
for p in list((r/'app/src/main/java').rglob('ConnectBar.kt')):
    s=p.read_text()
    s=s.replace('import androidx.appcompat.widget.AppCompatImageView\n','').replace('import androidx.fragment.app.FragmentActivity\n','').replace('import io.nekohasekai.sagernet.ui.OutboundIpDialogFragment\n','')
    s=s.replace(', SharedPreferences.OnSharedPreferenceChangeListener','')
    s=re.sub(r'\n\s*private val (ipInfo|ipValue|flagValue):[^\n]*','',s)
    s=re.sub(r'\n\s*(ipInfo|ipValue|flagValue)\s*=\s*findViewById\([^\n]*','',s)
    for marker in ['    private fun openNetworkDialog()', '    override fun onAttachedToWindow()', '    override fun onDetachedFromWindow()', '    override fun onSharedPreferenceChanged(', '    fun renderNetwork(']:
        s=remove_method(s,marker)
    s=s.replace('onInfo = { openNetworkDialog() },','')
    s=re.sub(r'^\s*[^\n]*renderNetwork\([^\n]*\)\s*\n','',s,flags=re.M)
    s=s.replace('TrayTapTargets.bind(this,\n            onTest = { if (DataStore.serviceState.connected) onTestConnection?.invoke() },\n            \n        )','')
    p.write_text(s)

# Remove IP-only strings.
drop={'outbound_ip','ip_info_basic','ip_info_network','ip_address','ip_country','ip_level','ip_type','ip_flags','ip_org','ip_asn','ip_source','ip_source_ipapicom','ip_level_good','ip_level_normal','ip_type_residential','ip_type_mobile','ip_type_hosting','ip_flag_proxy','ip_none','ip_loading','ip_query_failed','ip_action_refresh','ip_level_risky','ip_type_business','ip_flag_tor','ip_flag_vpn','ip_flag_abuser'}
for p in (r/'app/src/main/res').rglob('strings.xml'):
    p.write_text(''.join(line for line in p.read_text().splitlines(True) if not any(f'<string name="{x}"' in line for x in drop)))

print('PASS: IP query source, dialog, bottom panel, listeners, resources removed')
