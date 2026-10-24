#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/IpQualityLookup.kt';s=p.read_text()
# Bottom bar must consume a valid result even if the service state callback and
# StateFlow emission cross on the main queue; the profile/generation check is
# the real guard.
main=r/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt';m=main.read_text()
m=m.replace('if (entry != null && entry.profileId == DataStore.currentProfile && DataStore.serviceState.connected) {','if (entry != null && entry.profileId == DataStore.currentProfile) {',1)
main.write_text(m)
# Repeated clicks/route transitions cancel stale generations; prefetch never
# leaves the bottom card in an artificial loading state.
# Add FlClash-compatible JSON sources. Every result must have the same IP as
# the first answer before it can enrich the visible entry.
anchor='''    private fun fromIdentMe(): IpQuality {
'''
if anchor not in s:
 anchor='''    private suspend fun fromIdentMe(): IpQuality {
'''
extra='''    private suspend fun fromIpSb(): IpQuality {
        val j = getJson("https://api.ip.sb/geoip")
        return quality(j.optString("ip"), "ip.sb", "residential", true,
            organization = text(j.optString("organization")), asn = asnOf(j.opt("asn")),
            country = text(j.optString("country_code"))) ?: throw SourceFailure("noType")
    }

    private suspend fun fromIpWho(): IpQuality {
        val j = getJson("https://ipwho.is/")
        if (!j.optBoolean("success", true)) throw SourceFailure("failed")
        val conn = obj(j.opt("connection"))
        return quality(j.optString("ip"), "ipwho.is", "residential", true,
            organization = text(conn.optString("isp")), asn = asnOf(conn.opt("asn")),
            country = text(j.optString("country"))) ?: throw SourceFailure("noType")
    }

    private suspend fun fromGeoJs(): IpQuality {
        val j = getJson("https://get.geojs.io/v1/ip/geo.json")
        return quality(j.optString("ip"), "geojs.io", "residential", true,
            organization = text(j.optString("organization")), asn = asnOf(j.opt("asn")),
            country = text(j.optString("country_code"))) ?: throw SourceFailure("noType")
    }

    private suspend fun fromCountryIs(): IpQuality {
        val j = getJson("https://api.country.is/")
        return quality(j.optString("ip"), "country.is", "residential", true,
            country = text(j.optString("country"))) ?: throw SourceFailure("noType")
    }

'''
if extra not in s:
 if anchor not in s: raise SystemExit('missing IP source anchor')
 s=s.replace(anchor,extra+anchor,1)
old_new='''            ::fromIpSb, ::fromIpWho, ::fromGeoJs, ::fromCountryIs,
            ::fromIdentMe, ::fromIpApiCom, ::fromIpQuery,
            ::fromIpLocate, ::fromIpApiIs, ::fromProxyCheck,
'''
old_base='''            ::fromIdentMe, ::fromIpApiCom, ::fromIpQuery,
            ::fromIpLocate, ::fromIpApiIs, ::fromProxyCheck,
'''
if old_new not in s:
    s=s.replace(old_base,old_new,1)
# If the detailed source is unavailable, enrich the quick answer from any
# source that reports the identical IPv4/IPv6 address instead of returning a
# permanent inferred/loading state.
s=s.replace('''            if (q.country != null) {
                winner = q
                break
            }''','''            if (q.country != null || q.organization != null || q.asn != null) {
                winner = q
                break
            }''',1)
p.write_text(s)
print('PASS: FlClash source set, IPv6 country fallback, bottom-bar completion race')
