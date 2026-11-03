package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Host wiring contract: navigation must not become a fake service acknowledgement or tray layout. */
class ConfigurationOverlayHostContractTest {
    private val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
    private val main=File(app,"src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
    private fun section(start:String,end:String)=main.substringAfter(start).substringBefore(end)

    @Test fun coldRestoreCommitAndPagerCreationAllResyncActualPageVisibility() {
        assertTrue(main.contains("controlOverlay = ConfigurationControlOverlay(binding.fragmentHolder, binding.controlBar)"))
        assertTrue(main.contains("registerFragmentLifecycleCallbacks(navInsetCallbacks, true)"))
        assertTrue(main.contains("transaction.runOnCommit { syncMainControls() }"))
        assertTrue(main.contains("onViewAttachedToWindow"))
        assertTrue(main.contains("onFragmentResumed"))
        assertTrue(main.contains("onFragmentViewDestroyed"))
        assertTrue(section("override fun onPostResume()","fun applyHideFromRecentApps").contains("syncMainControls()"))
    }
    @Test fun navigationOnlyChangesPresentationNeverUnlocksPendingServiceOrTests() {
        val sync=section("private fun syncMainControls()","private fun installControlBarInsets()")
        assertTrue(sync.contains("currentMainFragment as? ConfigurationFragment"))
        assertTrue(sync.contains("ConfigurationControlOverlay.shouldShow"))
        assertTrue(sync.contains("configuration?.select"))
        assertTrue(sync.contains("configuration?.isHidden"))
        assertTrue(sync.contains("root?.isAttachedToWindow"))
        assertFalse(sync.contains("changeState("))
        assertFalse(sync.contains("reset("))
        assertFalse(sync.contains("invalidateControlLatency()"))
        val invalidate=section("private fun invalidateControlLatency()","private fun testControlLatency()")
        assertTrue(invalidate.contains("controlLatencyGate.invalidate()"))
        assertTrue(invalidate.contains("controlLatencyJob?.cancel()"))
        assertTrue(invalidate.contains("controlBar.clearMeasurements()"))
        assertFalse(invalidate.contains("controlBar.reset()"))
    }
    @Test fun actualServiceAndExplicitPermissionCancellationAreTheToggleAcknowledgements() {
        val change=section("private fun changeState(","override fun snackbarInternal")
        assertTrue(change.contains("binding.controlBar.changeState(state)"))
        val permission=section("private val connect = registerForActivityResult","override fun cbSpeedUpdate")
        assertTrue(permission.contains("if (denied)"))
        assertTrue(permission.contains("binding.controlBar.changeState(DataStore.serviceState)"))
        assertTrue(section("override fun onStop()","override fun onDestroy()").contains("invalidateControlLatency()"))
        assertTrue(section("override fun onDestroy()","override fun onSaveInstanceState").contains("invalidateControlLatency()"))
    }
    @Test fun insetsNeverReserveControlHeightAndHiddenControlCannotRequestTests() {
        val insets=section("private fun installControlBarInsets()","private fun invalidateControlLatency()")
        assertTrue(insets.contains("controlOverlay.applyInsets"))
        assertFalse(insets.contains("dp(104)"))
        assertFalse(insets.contains("fragmentHolder.updateLayoutParams"))
        assertTrue(section("private fun testControlLatency()","private fun refreshConfigurationProfileState()")
            .contains("binding.controlBar.visibility != View.VISIBLE"))
        val xml=File(app,"src/main/res/layout/layout_main.xml").readText()
        val holder=xml.substringAfter("android:id=\"@+id/fragment_holder\"").substringBefore("/>")
        assertFalse(holder.contains("layout_marginBottom"))
        val bar=xml.substringAfter("android:id=\"@+id/control_bar\"").substringBefore("/>")
        assertTrue(bar.contains("android:visibility=\"gone\""))
    }
}
