package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.GroupDragScroll
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GroupDragScrollTest {
    @Test fun edgeScrollIsFixedHalfSpeedInBothDirectionsAndIndependentOfDwell() {
        for(max in listOf(1,2,20,40,100)) {
            val expected=(max*.5f).toInt().coerceAtLeast(1)
            for(distance in listOf(1,10,100,999)) {
                assertEquals(expected,GroupDragScroll.pixels(max,distance))
                assertEquals(-expected,GroupDragScroll.pixels(max,-distance))
            }
            assertEquals(0,GroupDragScroll.pixels(max,0))
        }
    }
    @Test fun pickerAndGroupPageCallExactlyTheSameScrollAlgorithm() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        for(file in listOf("GroupPickerList.kt","GroupFragment.kt")) {
            val source=File(app,"src/main/java/io/nekohasekai/sagernet/ui/$file").readText()
            val scroll=source.substringAfter("override fun interpolateOutOfBoundsScroll").substringBefore("override fun on")
            assertTrue(scroll.contains("GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)"))
            assertFalse(scroll.contains("super.interpolateOutOfBoundsScroll"))
        }
    }
}
