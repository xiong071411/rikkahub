package me.rerere.rikkahub.data.sync.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemorySyncPayloadTest {
    @Test
    fun `memory hash matches Python and ignores only root timestamp`() {
        val payload = """{"assistantId":"__global__","path":"/preferences.md","content":"偏好：左侧翻页\n正文 /data/user/0/app/files/upload/photo.png","updatedAt":1}"""
        val expected = "7e3b0293811335dbf131f55a7d7eaca886a274c97ab60a217650f0b9e1fe97fa"
        assertEquals(expected, CanonicalSyncHash.computeSemanticHash("memory_file", payload))
        assertEquals(expected, CanonicalSyncHash.computeSemanticHash("memory_file", payload.replace("\"updatedAt\":1", "\"updatedAt\":999")))
        assertNotEquals(expected, CanonicalSyncHash.computeSemanticHash("memory_file", payload.replace("左侧", "右侧")))
    }

    @Test
    fun `display preferences have portable avatar and font references`() {
        val prefs = """{"displaySetting":{"messageJumperOnLeft":true,"userAvatar":{"type":"image","url":"file:///data/user/0/me.rerere.rikkahub/files/upload/me.jpg"},"chatCustomFontPath":"fonts/test.ttf"}}"""
        assertEquals("f4423191735c62c218ba2d8d54a5d3e8fc77ca27e78832a08283b4e9ccf35de6", CanonicalSyncHash.computeSemanticHash("extra_setting", prefs))
        assertEquals(setOf("upload/me.jpg", "fonts/test.ttf"), CanonicalSyncHash.extractReferencedFilesFromEntity("extra_setting", prefs))
    }
}
