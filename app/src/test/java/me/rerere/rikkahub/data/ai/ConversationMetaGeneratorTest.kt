package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ConversationMetaGeneratorTest {
    @Test
    fun `prepends conversation creation date with single space`() {
        val createAt = Instant.parse("2026-11-22T02:15:00Z")
        val actual = formatTitleWithDatePrefix(
            title = "  同步架构排查  ",
            createAt = createAt,
            zoneId = ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("261122 同步架构排查", actual)
    }

    @Test
    fun `uses local date in target timezone across midnight`() {
        // UTC 2026-11-22 18:00 corresponds to 2026-11-23 02:00 in Asia/Shanghai
        val createAt = Instant.parse("2026-11-22T18:00:00Z")
        val actual = formatTitleWithDatePrefix(
            title = "凌晨对话",
            createAt = createAt,
            zoneId = ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("261123 凌晨对话", actual)
    }

    @Test
    fun `avoids duplicating existing date prefix`() {
        val createAt = Instant.parse("2026-11-22T08:00:00Z")
        val actual = formatTitleWithDatePrefix(
            title = "261122 已经带日期的标题",
            createAt = createAt,
            zoneId = ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("261122 已经带日期的标题", actual)
    }

    @Test
    fun `returns only date prefix when generated title is blank`() {
        val createAt = Instant.parse("2026-11-22T08:00:00Z")
        val actual = formatTitleWithDatePrefix(
            title = "   ",
            createAt = createAt,
            zoneId = ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("261122", actual)
    }

    @Test
    fun `formats ascii digits regardless of default locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            val createAt = Instant.parse("2026-11-22T08:00:00Z")
            val actual = formatTitleWithDatePrefix(
                title = "标题",
                createAt = createAt,
                zoneId = ZoneId.of("UTC"),
            )
            assertEquals("261122 标题", actual)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
