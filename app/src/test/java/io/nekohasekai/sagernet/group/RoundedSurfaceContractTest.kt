package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RoundedSurfaceContractTest {
    @Test fun cardsAndMenusUseLargeRadiusButInputsAndEditorsAreUnchanged() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/res").isDirectory}
        val dimensions=File(app,"src/main/res/values/dimens.xml").readText()
        for(name in listOf("card_corner_radius","profile_card_corner_radius","popup_corner_radius","choice_corner_radius")) {
            assertTrue(dimensions.contains("name=\"$name\">24dp"))
        }
        assertTrue(dimensions.contains("name=\"input_corner_radius\">12dp"))
        assertTrue(dimensions.contains("name=\"dialog_corner_radius\">24dp"))
        val style=File(app,"src/main/java/io/nekohasekai/sagernet/widget/ThemedChoiceStyle.kt").readText()
        assertTrue(style.contains("R.dimen.choice_corner_radius"))
        assertFalse(style.contains("R.dimen.nav_item_corner_radius"))
        val group=File(app,"src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt").readText()
        assertTrue(group.contains("radius = resources.getDimension(R.dimen.popup_corner_radius)"))
    }
}
