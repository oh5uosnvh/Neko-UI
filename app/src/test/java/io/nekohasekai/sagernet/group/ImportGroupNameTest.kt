package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class ImportGroupNameTest {
    @Test fun usesUnicodeAndQuotesWithoutExtension() {
        assertEquals("哦去咯会哦",ImportGroupName.fromFileName("哦去咯会哦.TXT"))
        assertEquals("n'jinli",ImportGroupName.fromFileName("n'jinli.yaml"))
        assertEquals("牛工",ImportGroupName.fromFileName("牛工.JSON"))
    }
    @Test fun onlyFinalExtensionRemovedAndNoExtensionAllowed() {
        assertEquals("机场.backup",ImportGroupName.fromFileName("机场.backup.YAML"))
        assertEquals("机场",ImportGroupName.fromFileName("机场"))
        assertEquals(".hidden",ImportGroupName.fromFileName(".hidden"))
        assertEquals("n'jinli",ImportGroupName.fromFileName("/download/n'jinli.yaml"))
        assertEquals("",ImportGroupName.fromFileName(null))
    }
}
