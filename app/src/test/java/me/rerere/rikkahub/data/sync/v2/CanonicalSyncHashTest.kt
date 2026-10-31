package me.rerere.rikkahub.data.sync.v2

import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.datastore.normalized
import me.rerere.rikkahub.data.datastore.putSettings
import me.rerere.rikkahub.data.datastore.toSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toLocalizedAttachmentPaths
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toPortableAttachmentPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/** 测试用固定助手 ID（Conversation.assistantId 为必填字段）。 */
private val TEST_ASSISTANT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

/**
 * 验证跨端（Kotlin <-> Python `backend/app/canonical.py`）规范化、确定性语义哈希、
 * 跨包名附件路径转换以及遗留硬编码同步令牌自动清理。
 */
class CanonicalSyncHashTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `golden sha256 vectors match python canonical implementation character for character`() {
        val convJson = """
            {
              "messageNodes": [
                {
                  "selectIndex": 99,
                  "id": "node-1",
                  "messages": [
                    {
                      "parts": [
                        {
                          "type": "text",
                          "text": "正文里的 file:///data/user/0/me.rerere.rikkahub.coexist/files/upload/keep_me.jpg 绝不能被篡改"
                        },
                        {
                          "url": "file:///data/user/0/me.rerere.rikkahub.coexist/files/upload/pic_01.jpg",
                          "type": "image"
                        }
                      ],
                      "role": "USER",
                      "id": "msg-1"
                    }
                  ]
                }
              ],
              "config": {
                "mcpServerIds": ["srv-1"],
                "reasoningLevel": "DEFAULT"
              },
              "updateAt": 1700000999999,
              "createAt": 1700000000120,
              "isPinned": false,
              "title": "跨端哈希测试 🚀 A/B",
              "assistantId": "00000000-0000-0000-0000-000000000000",
              "id": "11111111-2222-3333-4444-555555555555"
            }
        """.trimIndent()

        // 与 Python test_canonical_fixtures.py::test_exact_golden_sha256_parity_with_kotlin 完全一致的黄金哈希
        assertEquals(
            "5c708f3e76c360599b19b9d8f4e04c32e0592c93c8a0826e2cc69980da9fa3d7",
            CanonicalSyncHash.computeSemanticHash("conversation", convJson),
        )

        // 修改顶层易变字段 updateAt 且切换 createAt 为 ISO-8601 字符串、使用 \uXXXX 转义表示中文，哈希保持恒定
        val convIsoAndEscaped = """
            {
              "id": "11111111-2222-3333-4444-555555555555",
              "assistantId": "00000000-0000-0000-0000-000000000000",
              "title": "\u8de8\u7aef\u54c8\u5e0c\u6d4b\u8bd5 🚀 A\/B",
              "createAt": "2023-11-14T22:13:20.120Z",
              "updateAt": "2099-01-01T00:00:00Z",
              "isPinned": false,
              "config": {
                "reasoningLevel": "auto",
                "mcpServers": ["srv-1"]
              },
              "messageNodes": [
                {
                  "id": "node-1",
                  "selectIndex": 0,
                  "messages": [
                    {
                      "id": "msg-1",
                      "role": "user",
                      "parts": [
                        {
                          "type": "text",
                          "text": "正文里的 file:///data/user/0/me.rerere.rikkahub.coexist/files/upload/keep_me.jpg 绝不能被篡改"
                        },
                        {
                          "type": "image",
                          "url": "upload/pic_01.jpg"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            "5c708f3e76c360599b19b9d8f4e04c32e0592c93c8a0826e2cc69980da9fa3d7",
            CanonicalSyncHash.computeSemanticHash("conversation", convIsoAndEscaped),
        )

        // 只要业务正文变化一个字符，哈希立即变化
        val convChangedText = convIsoAndEscaped.replace("A\\/B", "A/C")
        assertNotEquals(
            "5c708f3e76c360599b19b9d8f4e04c32e0592c93c8a0826e2cc69980da9fa3d7",
            CanonicalSyncHash.computeSemanticHash("conversation", convChangedText),
        )

        // 整数浮点 1.0 与整数 1 归一到同一黄金哈希，且顶层 updateAt 忽略
        val folderJson = """
            {
              "updateAt": 1700000005000,
              "createAt": 1700000000000,
              "sortIndex": 1.0,
              "name": "工作归档",
              "assistantId": "asst-1",
              "id": "folder-1"
            }
        """.trimIndent()
        assertEquals(
            "29b9d43f7aa0a35e16d63e4a2502b6f62cfb1a415f3725ba889e076369764c7d",
            CanonicalSyncHash.computeSemanticHash("folder", folderJson),
        )

        // 删除墓碑黄金哈希
        assertEquals(
            "23f49c600eed2b2540fedd42bf3c04d128a713ddd8d7e9653aad4ba90d4bb011",
            CanonicalSyncHash.computeSemanticHash("conversation", ""),
        )
    }

    @Test
    fun `cross package attachment path round trip rewrites structured urls without touching user text`() {
        val phoneFilesDir = File(tempFolder.root, "coexist_normal_files").apply { mkdirs() }
        val rawBodyText = "请帮我检查这段日志：file:///data/user/0/me.rerere.rikkahub/files/upload/raw_log_in_text.jpg"
        val githubPkgImageUri = "file:///data/user/0/me.rerere.rikkahub/files/upload/cat_photo.jpg"
        val webApiDocUri = "/api/files/path/upload/report.pdf?download=1"

        val original = Conversation(
            id = Uuid.random(),
            assistantId = TEST_ASSISTANT_ID,
            title = "跨包名附件测试",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text(rawBodyText),
                                UIMessagePart.Image(githubPkgImageUri),
                                UIMessagePart.Document(url = webApiDocUri, fileName = "report.pdf"),
                            ),
                        )
                    )
                )
            ),
        )

        val portable = original.toPortableAttachmentPaths(phoneFilesDir)
        val portableParts = portable.messageNodes.single().messages.single().parts
        assertEquals(rawBodyText, (portableParts[0] as UIMessagePart.Text).text)
        assertEquals("upload/cat_photo.jpg", (portableParts[1] as UIMessagePart.Image).url)
        assertEquals("upload/report.pdf", (portableParts[2] as UIMessagePart.Document).url)

        val localized = portable.toLocalizedAttachmentPaths(phoneFilesDir)
        val localizedParts = localized.messageNodes.single().messages.single().parts
        assertEquals(rawBodyText, (localizedParts[0] as UIMessagePart.Text).text)
        val localizedImgUrl = (localizedParts[1] as UIMessagePart.Image).url
        assertTrue(localizedImgUrl.startsWith("file://"))
        assertTrue(localizedImgUrl.endsWith("/upload/cat_photo.jpg"))

        // 拒绝任何包含 .. 路径穿越的伪造受管路径
        assertFalse(AttachmentPathNormalizer.isManagedRelativePath("upload/../../etc/passwd"))
        assertEquals(
            "upload/../../etc/passwd",
            AttachmentPathNormalizer.localizeAttachmentUrl("upload/../../etc/passwd", phoneFilesDir),
        )
    }

    @Test
    fun `legacy hardcoded sync token is stripped on normalization while real custom token persists`() {
        // 默认配置绝不包含任何内置密钥
        assertEquals("", SyncServerConfig().authToken)

        // 历史盘上如果残留旧版硬编码默认口令，读取/规整时自动清空
        val legacySettings = Settings(
            syncServerConfig = SyncServerConfig(
                serverUrl = "http://45.207.200.189:8765",
                authToken = "rikkahub-sync-secret-2026",
            )
        ).normalized()
        assertEquals("", legacySettings.syncServerConfig.authToken)

        // 用户配置的新随机独立令牌在 Preferences 序列化往返中完整保留（修复重启丢配置问题）
        val realToken = "rk_sync_test_token_9f8e7d6c5b4a3210"
        val configured = Settings(
            syncServerConfig = SyncServerConfig(
                serverUrl = "http://45.207.200.189:8765",
                authToken = realToken,
                deviceName = "RMX3800",
                syncFiles = true,
            )
        ).normalized()
        val prefs = mutablePreferencesOf().apply { putSettings(configured) }
        assertTrue(SettingsStore.SYNC_SERVER_CONFIG in prefs)
        val reloaded = prefs.toSettings()
        assertEquals(realToken, reloaded.syncServerConfig.authToken)
        assertEquals("RMX3800", reloaded.syncServerConfig.deviceName)
    }
}
