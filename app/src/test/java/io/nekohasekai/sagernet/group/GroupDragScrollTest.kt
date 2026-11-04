package io.nekohasekai.sagernet.group
import io.nekohasekai.sagernet.ui.GroupDragScroll
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class GroupDragScrollTest {
 @Test fun decimalSpeedIsConstantInBothDirections() { for (f in listOf(1.5f,2.1f,7.6f)) { val expected=(40*f/10f).toInt(); assertEquals(expected,GroupDragScroll.pixels(40,100,GroupDragScroll.speedFactorOf(f))); assertEquals(-expected,GroupDragScroll.pixels(40,-100,GroupDragScroll.speedFactorOf(f))) }; assertEquals(0,GroupDragScroll.pixels(40,0,2.1f)) }
 @Test fun decimalValuesAreRetainedAndBoundsClamped() { assertEquals(0.1f,GroupDragScroll.speedFactorOf(0f),0f); assertEquals(0.15f,GroupDragScroll.speedFactorOf(1.5f),0f); assertEquals(0.21f,GroupDragScroll.speedFactorOf(2.1f),0f); assertEquals(0.76f,GroupDragScroll.speedFactorOf(7.6f),0f); assertEquals(1f,GroupDragScroll.speedFactorOf(11f),0f) }
 @Test fun everyDragListUsesSharedAlgorithm() { val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}; for (f in listOf("GroupPickerList.kt","GroupFragment.kt","RouteFragment.kt","ConfigurationFragment.kt")) assertTrue(f,File(app,"src/main/java/io/nekohasekai/sagernet/ui/$f").readText().contains("GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)")) }
}
