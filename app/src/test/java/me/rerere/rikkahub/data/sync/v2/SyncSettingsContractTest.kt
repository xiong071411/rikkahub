package me.rerere.rikkahub.data.sync.v2

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class SyncSettingsContractTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every settings field must declare its sync policy`() {
        val descriptor = Settings.serializer().descriptor
        val actual = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet() + "init"
        assertEquals(emptySet<String>(), SyncSettingsContract.sharedFields.intersect(SyncSettingsContract.localOnlyFields.keys))
        assertEquals(actual, SyncSettingsContract.sharedFields + SyncSettingsContract.localOnlyFields.keys)
        assertTrue(SyncSettingsContract.localOnlyFields.values.all { it.isNotBlank() })
    }

    @Test
    fun `new device receives all shared settings while keeping device credentials`() {
        val source = Settings(
            dynamicColor = false, themeId = "my-theme",
            displaySetting = DisplaySetting(messageJumperOnLeft = true, showMessageJumper = false, fontSizeRatio = 1.3f),
            fastModelReasoningLevel = ReasoningLevel.HIGH,
            memoryModelId = Uuid.random(), memoryModelReasoningLevel = ReasoningLevel.HIGH,
            imageGenerationModelId = Uuid.random(), translateModeId = Uuid.random(), translateThinkingBudget = 2048,
            ocrModelId = Uuid.random(), searchServiceSelected = 1,
            selectedTTSProviderId = Uuid.random(), selectedASRProviderId = Uuid.random(),
            titlePrompt = "", webServerAccessPassword = "source-private-password",
        )
        val target = Settings(syncServerConfig = SyncServerConfig(authToken = "target-only-token"), webServerAccessPassword = "target-private")
        val extras = SyncSettingsContract.buildExtraSettings(source, JsonInstant, null)
        val changes = extras.map { (key, payload) -> SyncSettingsContract.Change("extra_setting", key, "upsert", payload) }
        val actual = SyncSettingsContract.applyChanges(target, changes, JsonInstant, File("/device/files"))
        val expectedJson = JsonInstant.encodeToJsonElement(source).jsonObject
        val actualJson = JsonInstant.encodeToJsonElement(actual).jsonObject
        (SyncSettingsContract.sharedFields - setOf("assistants", "providers")).forEach { field ->
            assertEquals(field, expectedJson[field], actualJson[field])
        }
        assertEquals(target.syncServerConfig, actual.syncServerConfig)
        assertEquals("target-private", actual.webServerAccessPassword)
        assertFalse(extras.values.joinToString().contains("source-private-password"))
        assertTrue(actual.displaySetting.messageJumperOnLeft)
    }

    @Test
    fun `partial preferences preserve missing fields and reject local fields`() {
        val current = Settings(themeId = "keep", webServerAccessPassword = "device-secret")
        val result = SyncSettingsContract.applyChanges(current, listOf(
            SyncSettingsContract.Change("extra_setting", SyncSettingsContract.PREFERENCES_KEY, "upsert", """{"dynamicColor":false,"webServerAccessPassword":"injected"}"""),
        ), JsonInstant, File("/files"))
        assertEquals("keep", result.themeId)
        assertEquals("device-secret", result.webServerAccessPassword)
        assertFalse(result.dynamicColor)
    }
}
