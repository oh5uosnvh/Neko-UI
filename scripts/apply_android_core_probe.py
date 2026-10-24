#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();base='app/src/main/java/io/nekohasekai/sagernet/'
# Add a parser that publishes the core answer into the existing shared flow.
p=r/(base+'ui/IpQualityLookup.kt');s=p.read_text()
if 'fun publishCoreJson' not in s:
    anchor='    private fun publish(profileId: Long, q: IpQualityLookup.IpQuality): IpQualityLookup.IpQuality {'
    if anchor not in s:
        anchor='    private fun publish(profileId: Long, q: IpQuality): IpQuality {'
    if anchor not in s: raise SystemExit('missing IpQuality publish anchor')
    method='''    fun publishCoreJson(profileId: Long, raw: String): Boolean {
        return try {
            val root = JSONObject(raw)
            if (root.optString("error").isNotBlank()) return false
            val body = root.optString("body")
            val ip = Regex("(?<![A-Za-z0-9])(?:[0-9]{1,3}(?:\\\\.[0-9]{1,3}){3}|[0-9A-Fa-f:]{7,})").find(body)?.value ?: return false
            val json = runCatching { JSONObject(body) }.getOrNull()
            val location = json?.optJSONObject("location")
            val country = json?.optString("country").orEmpty().ifBlank { json?.optString("country_code").orEmpty() }.ifBlank { json?.optString("cc").orEmpty() }.ifBlank { location?.optString("country_code").orEmpty() }
            val org = json?.optString("org").orEmpty().ifBlank { json?.optString("organization").orEmpty() }.ifBlank { json?.optString("isp").orEmpty() }
            val asn = Regex("\\\\d+").find(json?.optString("asn").orEmpty())?.value?.toIntOrNull()
            publishIfCurrent(IpQuality(ip, root.optString("url").ifBlank { "core" }, "residential", org.ifBlank { null }, asn, false, false, false, false, country.ifBlank { null }, false), profileId, root.optLong("coreEpoch"))
        } catch (_: Exception) { false }
    }

'''
    s=s.replace(anchor,method+anchor,1);p.write_text(s)
# MainActivity: replace the application-layer lookup job if present; if not,
# inject a routed core probe into the connected-state branch.
p=r/(base+'ui/MainActivity.kt');s=p.read_text()
old='''        networkJob = lifecycleScope.launch {
            try { IpQualityLookup.lookup(profileId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e) }
        }'''
new='''        networkJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urls = org.json.JSONArray(listOf("https://ipinfo.io/json", "https://ipwho.is/", "https://api.country.is/", "https://api.ip.sb/geoip", "https://api.ipquery.io/?format=json", "https://ident.me/json")).toString()
                val raw = connection.service?.outboundIp("proxy", urls, 6000L)
                if (raw != null) IpQualityLookup.publishCoreJson(profileId, raw)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w(e) }
        }'''
if old in s:
    s=s.replace(old,new,1)
else:
    anchor='''            runOnDefaultDispatcher {
                runCatching { IpQualityLookup.prefetch(DataStore.currentProfile) }
            }'''
    routed='''            networkJob?.cancel()
            val profileId = DataStore.currentProfile
            networkJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val urls = org.json.JSONArray(listOf("https://ipinfo.io/json", "https://ipwho.is/", "https://api.country.is/", "https://api.ip.sb/geoip", "https://api.ipquery.io/?format=json", "https://ident.me/json")).toString()
                    val raw = connection.service?.outboundIp("proxy", urls, 6000L)
                    if (raw != null) IpQualityLookup.publishCoreJson(profileId, raw)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Logs.w(e) }
            }'''
    if anchor not in s: raise SystemExit('missing connected probe anchor')
    s=s.replace(anchor,routed,1)
p.write_text(s)
print('PASS: core routed probe parser and Android shared flow binding installed')
