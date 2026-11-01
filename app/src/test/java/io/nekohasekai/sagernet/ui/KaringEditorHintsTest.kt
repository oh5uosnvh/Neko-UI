package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KaringEditorHintsTest {
    @Test fun everyAndroidFieldMatchesKaringIncludingNewlines() {
        val expected = listOf(
            "geosite:fackbook\ngeoip:fackbook\nacl:Fackbook",
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs\nhttps://raw.githubusercontent.com/Toperlock/sing-box-geosite/main/wechat.json",
            "com.google.chrome\ncom.facebook.katana", ".google.com\n.facebook.com",
            "ads.google.com\nad.facebook.com", "google\nfacebook", "^google\\..+\n^facebook\\..+",
            "178.0.55.32/32\n2001:4860:4860::8888/128", "wifi\ncellular\nethernet\nother",
            "tcp\nudp\nicmp", "My WIFI\nWifi ssid", "00:00:00:00:00:00\n0f:0f:0f:0f:0f:0f",
            "443\n53\n", ":3500\n4500:\n20000:25000\n", "quic\nhttp"
        )
        val actual = listOf(KaringEditorHints.BUILTIN, KaringEditorHints.RULE_SET, KaringEditorHints.PACKAGE,
            KaringEditorHints.DOMAIN_SUFFIX, KaringEditorHints.DOMAIN, KaringEditorHints.DOMAIN_KEYWORD,
            KaringEditorHints.DOMAIN_REGEX, KaringEditorHints.IP_CIDR, KaringEditorHints.NETWORK_TYPE,
            KaringEditorHints.NETWORK, KaringEditorHints.WIFI_SSID, KaringEditorHints.WIFI_BSSID,
            KaringEditorHints.PORT, KaringEditorHints.PORT_RANGE, KaringEditorHints.PROTOCOL)
        assertEquals(expected, actual)
    }
}
