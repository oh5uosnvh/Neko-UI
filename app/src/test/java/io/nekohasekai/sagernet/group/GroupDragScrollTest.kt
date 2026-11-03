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
    @Test fun speedFactorIsUniformPerLevelAndDifferentiatesLevels() {
        // REQ-04：同一档位下整个拖动过程恒速——多次调用同输入同输出（也与
        // 停留时间无关，本函数不接收 dwell 参数）；不同档位（0.3 vs 0.5）输出不同。
        val max=40
        for(factor in listOf(0.3f,0.5f)) {
            for(distance in listOf(1,10,100,999)) {
                val first=GroupDragScroll.pixels(max,distance,factor)
                repeat(5){assertEquals(first,GroupDragScroll.pixels(max,distance,factor))}
                assertEquals(-first,GroupDragScroll.pixels(max,-distance,factor))
                assertEquals(0,GroupDragScroll.pixels(max,0,factor))
            }
        }
        assertTrue(GroupDragScroll.pixels(max,100,0.3f)!=GroupDragScroll.pixels(max,100,0.5f))
    }
    @Test fun prefLevelOutOfRangeIsClampedToBoundaries() {
        // REQ-04：偏好越界（0/11）钳制到 1/10 对应系数（V/10.0f）
        assertEquals(0.1f,GroupDragScroll.speedFactorOf(0),0f)
        assertEquals(0.1f,GroupDragScroll.speedFactorOf(1),0f)
        assertEquals(0.5f,GroupDragScroll.speedFactorOf(5),0f)
        assertEquals(1.0f,GroupDragScroll.speedFactorOf(10),0f)
        assertEquals(1.0f,GroupDragScroll.speedFactorOf(11),0f)
        // 钳制后的档位产生与边界系数一致的恒速输出
        assertEquals(
            GroupDragScroll.pixels(40,100,0.1f),
            GroupDragScroll.pixels(40,100,GroupDragScroll.speedFactorOf(0)),
        )
        assertEquals(
            GroupDragScroll.pixels(40,100,1.0f),
            GroupDragScroll.pixels(40,100,GroupDragScroll.speedFactorOf(11)),
        )
    }
}
