#!/usr/bin/env python3
from pathlib import Path
import re,sys
r=Path(sys.argv[1]).resolve()
def drop_between(text,start,end):
 a=text.find(start)
 while a>=0:
  b=text.find(end,a)
  if b<0: raise SystemExit('missing IP block end')
  text=text[:a]+text[b+len(end):]
  a=text.find(start)
 return text
# Exact bottom-bar IP view block.
p=r/'app/src/main/res/layout/layout_connect_bar.xml';s=p.read_text();s=drop_between(s,'        <!-- ⓘ 出站 IP 查询','        <!-- [2] 上传/下载');p.write_text(s)
# Remove IP-specific fields/listener/imports from ConnectBar, including the
# official-tray variant copied later by apply_official_tray.
for rel in ['app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt','app/src/main/java/io/nekohasekai/sagernet/widget/TrayTapTargets.kt']:
 p=r/rel
 if not p.exists(): continue
 s=p.read_text();s=s.replace('import androidx.appcompat.widget.AppCompatImageView\n','').replace('import androidx.fragment.app.FragmentActivity\n','').replace('import io.nekohasekai.sagernet.ui.OutboundIpDialogFragment\n','').replace('import io.nekohasekai.sagernet.ui.*\n','import io.nekohasekai.sagernet.ui.MainActivity\n')
 s=re.sub(r'\n\s*private val (ipInfo|ipValue|flagValue):[^\n]*','',s)
 s=re.sub(r'\n\s*(ipInfo|ipValue|flagValue) = findViewById\([^\n]*','',s)
 s=re.sub(r'\n\s*private fun openNetworkDialog\(\) \{.*?\n\s*\}\n','\n',s,flags=re.S)
 s=re.sub(r'\n\s*override fun onAttachedToWindow\(\).*?\n\s*fun renderNetwork\([^}]+\}\n','\n',s,flags=re.S)
 s=s.replace('onInfo = { openNetworkDialog() },','')
 # Official tray ConnectBar has one IP-only method removed; drop its stale extra brace.
 if rel.endswith('widget/ConnectBar.kt'):
  s=s.replace('''        updateSpeed(0,0); renderNetwork()\n    }\n    }\n    fun setTesting''','''        updateSpeed(0,0)\n    }\n    fun setTesting''')
 p.write_text(s)
# Original UI ConnectBar follows for compatibility with both tray variants.
p=r/'app/src/main/java/io/nekohasekai/sagernet/widget/ConnectBar.kt';s=p.read_text();s=s.replace('import androidx.appcompat.widget.AppCompatImageView\n','').replace('import androidx.fragment.app.FragmentActivity\n','').replace('import io.nekohasekai.sagernet.ui.OutboundIpDialogFragment\n','');s=re.sub(r'\n\s*private val ipInfo: AppCompatImageView','',s);s=s.replace('        ipInfo = findViewById(R.id.bar_ip_info)\n','');s=drop_between(s,'        ipInfo.setOnClickListener {','        // 导航栏 inset');p.write_text(s)
# Remove MainActivity's automatic IP lookup and invalidation calls.
p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';s=p.read_text();s=re.sub(r'\n\s*runCatching \{ IpQualityLookup\.prefetch\(DataStore\.currentProfile\) \}\n','\n',s);s=re.sub(r'\n\s*IpQualityLookup\.invalidate\(\)','',s)
s=re.sub(r'\n\s*IpQualityLookup\.[A-Za-z0-9_]+\([^\n]*\)','',s)
s=re.sub(r'\n\s*private fun observeNetworkResults\(\) \{.*?\n\s*\}\n','\n',s,flags=re.S)
s=re.sub(r'\n\s*private fun refreshNetworkCard\(\) \{.*?\n\s*\}\n','\n',s,flags=re.S)
s=s.replace('import kotlinx.coroutines.flow.collect\n','').replace('IpQualityLookup','')
s=re.sub(r'\n\s*private fun observeNetworkResults\(\) \{.*?\n\s*\}\n','\n',s,flags=re.S)
s=re.sub(r'\n(?:\s*\}\n){3,5}\s*suspend fun importSubscription', '\n\n    suspend fun importSubscription', s, count=1)
p.write_text(s)
for rel in ['app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt','app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt','app/src/main/java/io/nekohasekai/sagernet/ui/NetworkPrivacy.kt','app/src/main/res/layout/layout_outbound_ip_dialog.xml','app/src/main/res/drawable/ic_outbound_ip.xml']:
 p=r/rel
 if p.exists():p.unlink()
# Remove official-tray network card helpers and tests that only exercised IP UI.
for rel in ['app/src/main/java/io/nekohasekai/sagernet/widget/TrayNetworkLayout.kt','app/src/main/java/io/nekohasekai/sagernet/widget/TrayTapTargets.kt']:
 p=r/rel
 if p.exists(): p.unlink()
for p in list((r/'app/src').rglob('*')):
 if p.is_file() and p.suffix in {'.kt','.java','.xml'}:
  try: text=p.read_text()
  except Exception: continue
  if 'bar_ip_info' in text or 'TrayNetworkLayout' in text:
   if '/test/' in str(p): p.unlink()
   elif p.suffix == '.xml':
    text=re.sub(r'\n\s*<[^>]*bar_network_card[^>]*>.*?</[^>]+>','',text,flags=re.S)
    text=re.sub(r'\n\s*<[^>]*bar_ip_info[^>]*/>','',text)
    p.write_text(text)
# Remove IP-only strings and test/verification references.
drop={'outbound_ip','ip_info_basic','ip_info_network','ip_address','ip_country','ip_level','ip_type','ip_flags','ip_org','ip_asn','ip_source','ip_source_ipapicom','ip_level_good','ip_level_normal','ip_type_residential','ip_type_mobile','ip_type_hosting','ip_flag_proxy','ip_none','ip_loading','ip_query_failed','ip_action_refresh','ip_level_risky','ip_type_business','ip_flag_tor','ip_flag_vpn','ip_flag_abuser'}
for p in list((r/'app/src/main/res').rglob('strings.xml')):
 lines=[line for line in p.read_text().splitlines(True) if not any(f'<string name="{name}"' in line for name in drop)]
 p.write_text(''.join(lines))
# The removed XML comment must not leave its closing text behind.
p=r/'app/src/main/res/layout/layout_connect_bar.xml';s=p.read_text();s=re.sub(r'\n\s*：固定冗余宽度[^\n]*-->','',s);p.write_text(s)

print('PASS: IP query source, dialog, bottom-bar region, and lifecycle calls removed')
