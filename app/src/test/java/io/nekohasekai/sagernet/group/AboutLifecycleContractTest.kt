package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AboutLifecycleContractTest {
    private val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
    private val source=File(app,"src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt").readText()
    @Test fun backgroundListBuildUsesCapturedContextAfterFragmentDetachNotRequireContextGetString() {
        val section=source.substringAfter("override fun getMaterialAboutList(activityContext: Context)")
            .substringBefore("override fun onViewCreated(view: View")
        assertTrue(section.contains("activityContext.getString(R.string.version_x"))
        assertFalse(section.contains(".text(getString("))
        assertTrue(section.contains("activityContext.getString(\n"))
        assertFalse(section.contains("requireContext().launchCustomTab"))
    }
    @Test fun aboutChildAndLicenseResultAreBoundToRealViewLifecycle() {
        val outer=source.substringBefore("class AboutContent")
        assertTrue(outer.contains("childFragmentManager.beginTransaction()"))
        assertTrue(outer.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(outer.contains("this@AboutFragment.view !== view"))
        assertFalse(outer.contains("runOnDefaultDispatcher"))
    }
}
