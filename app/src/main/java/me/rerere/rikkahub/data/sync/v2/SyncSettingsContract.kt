package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.sync.v2.AttachmentPathNormalizer.toLocalizedAttachmentPaths
import java.io.File

/** 同步范围只有这一份定义；测试用 Settings 序列化描述符检查新增字段必须明确归类。 */
object SyncSettingsContract {
    const val PREFERENCES_KEY = "app_preferences_v2"
    const val COVERAGE_VERSION = "3"

    val collectionFields = linkedMapOf(
        "assistant_tags" to "assistantTags", "lorebooks" to "lorebooks",
        "mode_injections" to "modeInjections", "quick_messages" to "quickMessages",
        "custom_themes" to "customThemes", "media_generation_providers" to "mediaGenerationProviders",
        "tts_providers" to "ttsProviders", "asr_providers" to "asrProviders",
        "search_services" to "searchServices", "mcp_servers" to "mcpServers",
    )
    val legacyAiFields = setOf(
        "favoriteModels", "chatModelId", "fastModelId", "enableAutoTitle", "titlePrompt",
        "translatePrompt", "translateTargetLanguage", "enableSuggestion", "suggestionPrompt",
        "ocrPrompt", "defaultTTSPlaybackSpeed",
    )

    // 独立键兼容 197：旧客户端继续更新 ai_preferences 时，不会覆盖新加入的用户偏好。
    val preferenceFields = setOf(
        "dynamicColor", "themeId", "displaySetting", "fastModelReasoningLevel",
        "memoryModelId", "memoryModelReasoningLevel", "imageGenerationModelId", "translateModeId",
        "translateThinkingBudget", "ocrModelId", "searchCommonOptions", "searchServiceSelected",
        "selectedTTSProviderId", "selectedASRProviderId",
    )
    val sharedFields =
        collectionFields.values.toSet() + legacyAiFields + preferenceFields + setOf("assistants", "providers")
    val localOnlyFields = mapOf(
        "init" to "内存加载状态，不持久化",
        "developerMode" to "设备调试状态", "networkSetting" to "设备网络与代理凭据",
        "assistantId" to "当前设备或标签页的助手选择",
        "webDavConfig" to "备份目标与凭据", "s3Config" to "备份目标与凭据",
        "uploadS3Config" to "临时上传存储凭据",
        "webServerEnabled" to "设备内置服务", "webServerPort" to "设备内置服务",
        "webServerJwtEnabled" to "设备内置服务", "webServerAccessPassword" to "设备访问凭据",
        "webServerLocalhostOnly" to "设备监听范围", "backupReminderConfig" to "本机备份提醒与时间",
        "syncServerConfig" to "同步地址、凭据及本机进度", "sponsorAlertDismissedAt" to "本机提示状态",
    )

    fun buildExtraSettings(settings: Settings, json: Json, filesDir: File?): Map<String, String> {
        val encoded = json.encodeToJsonElement(settings).jsonObject
        return linkedMapOf<String, String>().apply {
            collectionFields.forEach { (key, field) -> put(key, encoded.getValue(field).toString()) }
            put("ai_preferences", JsonObject(encoded.filterKeys { it in legacyAiFields }).toString())
            put(
                PREFERENCES_KEY,
                portablePreferences(JsonObject(encoded.filterKeys { it in preferenceFields }), filesDir).toString()
            )
        }
    }

    data class Change(val entityType: String, val entityId: String, val operation: String, val payload: String)

    /** 在一次 SettingsStore.update 中应用完整批次，关联列表全部到齐后才进行引用清理。 */
    fun applyChanges(current: Settings, changes: List<Change>, json: Json, filesDir: File): Settings {
        val fields = json.encodeToJsonElement(current).jsonObject.toMutableMap()
        changes.forEach { change ->
            when (change.entityType) {
                "assistant", "provider" -> {
                    val field = if (change.entityType == "assistant") "assistants" else "providers"
                    val list = (fields.getValue(field) as JsonArray).toMutableList()
                    val index = list.indexOfFirst { it.jsonObject["id"]?.jsonPrimitive?.content == change.entityId }
                    if (change.operation == "delete") {
                        if (index >= 0) list.removeAt(index)
                    } else {
                        val raw = json.parseToJsonElement(change.payload)
                        require(raw.jsonObject["id"]?.jsonPrimitive?.content == change.entityId) { "同步配置 ID 不匹配" }
                        val value = if (change.entityType == "assistant") {
                            json.encodeToJsonElement(
                                json.decodeFromJsonElement<Assistant>(raw).toLocalizedAttachmentPaths(filesDir)
                            )
                        } else {
                            raw
                        }
                        if (index >= 0) list[index] = value else list.add(value)
                    }
                    fields[field] = JsonArray(list)
                }
                "extra_setting" -> {
                    val collection = collectionFields[change.entityId]
                    if (collection != null) {
                        fields[collection] =
                            if (change.operation ==
                                "delete") {
                                JsonArray(emptyList())
                            } else {
                                json.parseToJsonElement(change.payload)
                            }
                    } else if (change.operation != "delete" &&
                        change.entityId in setOf("ai_preferences", PREFERENCES_KEY)) {
                        val allowed = if (change.entityId == PREFERENCES_KEY) preferenceFields else legacyAiFields
                        val incoming = json.parseToJsonElement(change.payload).jsonObject
                        val localized = if (change.entityId ==
                            PREFERENCES_KEY) {
                            localizePreferences(incoming, filesDir)
                        } else {
                            incoming
                        }
                        // 缺失字段保留本机值；显式的空字符串、null、false 仍正常同步。
                        localized.filterKeys { it in allowed }.forEach { (key, value) ->
                            fields[key] = if (key == "displaySetting" && value is JsonObject) {
                                JsonObject(fields.getValue(key).jsonObject + value)
                            } else {
                                value
                            }
                        }
                    }
                }
                else -> error("不是设置实体：${change.entityType}")
            }
        }
        return json.decodeFromJsonElement(JsonObject(fields))
    }

    fun portablePreferences(value: JsonObject, filesDir: File?): JsonObject = transformDisplay(value) { raw ->
        AttachmentPathNormalizer.normalizeAttachmentUrl(raw, filesDir)
    }

    fun localizePreferences(value: JsonObject, filesDir: File): JsonObject = transformDisplay(value) { raw ->
        AttachmentPathNormalizer.localizeAttachmentUrl(raw, filesDir)
    }

    private fun transformDisplay(value: JsonObject, transform: (String) -> String): JsonObject {
        val display = value["displaySetting"] as? JsonObject ?: return value
        val updated = display.toMutableMap()
        val avatar = display["userAvatar"] as? JsonObject
        if (avatar?.get("url") != null) {
            updated["userAvatar"] =
                JsonObject(
                    avatar +
                        ("url" to
                            kotlinx.serialization.json.JsonPrimitive(
                                transform(avatar.getValue("url").jsonPrimitive.content)
                            ))
                )
        }
        // 字体本身使用相对路径，不能本地化为 file://，字体加载器按 filesDir 解析它。
        display["chatCustomFontPath"]?.jsonPrimitive?.content?.let { path ->
            updated["chatCustomFontPath"] =
                kotlinx.serialization.json.JsonPrimitive(AttachmentPathNormalizer.normalizeAttachmentUrl(path))
        }
        return JsonObject(value + ("displaySetting" to JsonObject(updated)))
    }
}
