package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** No Android/Robolectric operations: the formatter is a public deterministic pure function. */
class ControlBarFormatTest {
    @Test fun negativeRatesAreClampedIncludingLongMin() {
        for (rate in listOf(Long.MIN_VALUE, -1_000_000L, -1L, 0L)) {
            assertEquals("rate=$rate", "0.00 kB/s", ControlBar.fmt(rate))
        }
    }

    @Test fun bytesAreDecimalKilobytesWithTwoDecimalsBelowTenK() {
        val cases = mapOf(1L to "0.00 kB/s", 10L to "0.01 kB/s", 999L to "1.00 kB/s",
            1_000L to "1.00 kB/s", 3_050L to "3.05 kB/s", 9_994L to "9.99 kB/s",
            9_999L to "10.00 kB/s")
        cases.forEach { (rate, text) -> assertEquals("rate=$rate", text, ControlBar.fmt(rate)) }
    }

    @Test fun tenKilobyteBoundaryUsesOneDecimal() {
        val cases = mapOf(10_000L to "10.0 kB/s", 10_149L to "10.1 kB/s",
            12_345L to "12.3 kB/s", 99_949L to "99.9 kB/s", 99_999L to "100.0 kB/s")
        cases.forEach { (rate, text) -> assertEquals("rate=$rate", text, ControlBar.fmt(rate)) }
    }

    @Test fun hundredKilobyteBoundaryUsesTruncatedInteger() {
        val cases = mapOf(100_000L to "100 kB/s", 100_999L to "100 kB/s",
            724_567L to "724 kB/s", 999_999L to "999 kB/s")
        cases.forEach { (rate, text) -> assertEquals("rate=$rate", text, ControlBar.fmt(rate)) }
    }

    @Test fun megabyteBoundaryUsesTwoDecimals() {
        val cases = mapOf(1_000_000L to "1.00 MB/s", 1_005_100L to "1.01 MB/s",
            10_123_456L to "10.12 MB/s", 999_999_999L to "1000.00 MB/s")
        cases.forEach { (rate, text) -> assertEquals("rate=$rate", text, ControlBar.fmt(rate)) }
    }

    @Test fun hugeRatesUseReadableLargerDecimalUnitsWithoutOverflow() {
        val cases = mapOf(1_000_000_000L to "1.00 GB/s", 1_000_000_000_000L to "1.00 TB/s",
            1_000_000_000_000_000L to "1.00 PB/s", 1_000_000_000_000_000_000L to "1.00 EB/s",
            Long.MAX_VALUE to "9.22 EB/s")
        cases.forEach { (rate, text) -> assertEquals("rate=$rate", text, ControlBar.fmt(rate)) }
        for (rate in listOf(1_000_000L, 1_000_000_000L, Long.MAX_VALUE)) {
            assertTrue(ControlBar.fmt(rate).matches(Regex("[0-9]+\\.[0-9]{2} [MGTPE]B/s")))
        }
    }

    @Test fun formattingDoesNotDependOnTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale("ar", "EG"),
                Locale.SIMPLIFIED_CHINESE, Locale.TAIWAN)) {
                Locale.setDefault(locale)
                assertEquals("3.05 kB/s", ControlBar.fmt(3_050L))
                assertEquals("12.3 kB/s", ControlBar.fmt(12_345L))
                assertEquals("724 kB/s", ControlBar.fmt(724_567L))
                assertEquals("10.12 MB/s", ControlBar.fmt(10_123_456L))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun formattingIsRepeatableAndHasNoThousandsGrouping() {
        val rates = listOf(0L, 12_345L, 999_999L, 1_000_000_000_000L)
        rates.forEach { rate ->
            val result = ControlBar.fmt(rate)
            repeat(20) { assertEquals(result, ControlBar.fmt(rate)) }
            assertFalse(result.contains(','))
            assertFalse(result.contains('E'))
        }
    }
}
