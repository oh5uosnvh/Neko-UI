package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GroupDisplayNameTest {
    @Test fun importedAndRemoteKnownFileSuffixesAreTrimmedWithoutRemovingProviderDots() {
        assertEquals("BygCloud",GroupDisplayName.withoutFileSuffix("BygCloud.yaml"))
        assertEquals("机场",GroupDisplayName.withoutFileSuffix("机场.YAML"))
        assertEquals("name.multi",GroupDisplayName.withoutFileSuffix("name.multi.json"))
        assertEquals("provider.example.com",GroupDisplayName.withoutFileSuffix("provider.example.com"))
        assertEquals(".yaml",GroupDisplayName.withoutFileSuffix(".yaml"))
        assertEquals("O'Brien",GroupDisplayName.withoutFileSuffix("O'Brien.yml"))
    }
    @Test fun subscriptionImportAndAllUiDisplaysUseTheSameSuffixNormalizer() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        val group=File(app,"src/main/java/io/nekohasekai/sagernet/database/ProxyGroup.kt").readText()
        val updater=File(app,"src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt").readText()
        assertTrue(group.contains("GroupDisplayName.withoutFileSuffix(raw)"))
        assertTrue(updater.contains("GroupDisplayName.withoutFileSuffix(remoteName)"))
        val picker=File(app,"src/main/java/io/nekohasekai/sagernet/ui/GroupPickerList.kt").readText()
        assertTrue(picker.contains("text = \"≡\""))
        assertTrue(picker.contains("LinearLayout.LayoutParams(dp(48), dp(48))"))
    }
}
