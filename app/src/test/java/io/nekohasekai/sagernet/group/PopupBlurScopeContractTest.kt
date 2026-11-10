package io.nekohasekai.sagernet.group

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/**
 * Blur is dialog-only: list/choice menus must not blur the activity root or add a
 * dim scrim. Source/resource contracts deliberately need no Android runtime or
 * native Robolectric graphics, so they run with the normal CI JVM defaults.
 * This does not prohibit the separate title-bar/edge blur renderers.
 */
class PopupBlurScopeContractTest {
    private val app = sequenceOf(File("."), File("app")).first {
        File(it, "src/main/res/values/themes.xml").isFile
    }
    private val sources = File(app, "src/main/java")
    private val widgets = File(sources, "io/nekohasekai/sagernet/widget")
    private val dialogOverlay = "ThemeOverlay.MaterialComponents.MaterialAlertDialog.SagerNet"
    private val themes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File(app, "src/main/res/values/themes.xml")).documentElement

    private fun styleNamed(name: String): Element =
        themes.getElementsByTagName("style").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.single { it.getAttribute("name") == name }

    private fun items(style: Element): Map<String, String> =
        style.getElementsByTagName("item").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.associate { it.getAttribute("name") to it.textContent.trim() }

    @Test fun menusAndSharedMotionNeverBlurOrDimTheHost() {
        // Include the shared animation helper: moving the effect there must not
        // bypass the contract on the three popup entry points.
        val forbidden = listOf(
            "setRenderEffect", "RenderEffect", "createBlurEffect",
            "setBackgroundBlurRadius", "setBlurBehindRadius",
            "FLAG_BLUR_BEHIND", "FLAG_DIM_BEHIND", "dimAmount",
        )
        for (name in listOf("AnimatedPopupWindow", "ChoiceMenuPopup", "FlMenu", "PopupEntrance")) {
            val source = File(widgets, "$name.kt").readText()
            for (token in forbidden) {
                assertFalse("$name must not apply host blur/dim via $token", token in source)
            }
            assertFalse("$name must not own a background scrim",
                Regex("\\b\\w*scrim\\w*\\b", RegexOption.IGNORE_CASE).containsMatchIn(source))
        }
    }

    @Test fun removedPopupScrimControllerCannotBeReintroducedOrReferenced() {
        assertFalse("Menus no longer have a root-effect/scrim controller",
            File(widgets, "PopupScrimController.kt").exists())
        sources.walkTopDown().filter { it.isFile && it.extension in listOf("kt", "java") }
            .forEach { file ->
                assertFalse("Stale PopupScrimController reference in ${file.relativeTo(sources)}",
                    "PopupScrimController" in file.readText())
            }
    }

    @Test fun dialogOverlayRetainsPlatformBlurAndLightFallbackDim() {
        val values = items(styleNamed(dialogOverlay))
        assertEquals("Dialogs keep platform backdrop blur", "true",
            values["android:windowBlurBehindEnabled"])
        assertEquals("Dialogs keep the existing blur radius", "12dp",
            values["android:windowBlurBehindRadius"])
        assertEquals("Blur-disabled/older devices still get light dim", "true",
            values["android:backgroundDimEnabled"])
        assertEquals("Do not remove or darken the dialog focus layer", "0.18",
            values["android:backgroundDimAmount"])
    }

    @Test fun bothDialogEntrancesStillReachTheBlurOverlay() {
        val alertOverlay = "ThemeOverlay.SagerNet.AlertDialog"
        val alert = styleNamed(alertOverlay)
        assertEquals("AppCompat dialogs inherit the same blur contract", dialogOverlay,
            alert.getAttribute("parent"))
        val blurAndDim = items(styleNamed(dialogOverlay)).filterKeys {
            it.startsWith("android:windowBlurBehind") || it.startsWith("android:backgroundDim")
        }
        // A child override must not silently turn off the inherited dialog effect.
        for ((key, value) in blurAndDim) {
            assertEquals("$alertOverlay must preserve $key", value, items(alert)[key] ?: value)
        }
        for (name in listOf("Theme.SagerNet.Base", "Theme.SagerNet.Dialog")) {
            val values = items(styleNamed(name))
            assertEquals("$name Material dialogs use the blur overlay", "@style/$dialogOverlay",
                values["materialAlertDialogTheme"])
            assertEquals("$name AppCompat dialogs use its inheriting overlay", "@style/$alertOverlay",
                values["alertDialogTheme"])
        }
    }
}
