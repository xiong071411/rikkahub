package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.ui.crop.rotateImage
import org.koin.compose.koinInject
import java.io.File

@Composable
internal fun useBatchImageRotationLauncher(
    inputState: ChatInputState,
    onAttachmentAdded: () -> Unit,
): (List<Uri>) -> Unit {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val currentOnAttachmentAdded by rememberUpdatedState(onAttachmentAdded)
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (images.isNotEmpty()) {
        BatchImageRotationDialog(
            images = images,
            saving = saving,
            completed = completed,
            errorMessage = errorMessage,
            onDismiss = { if (!saving) images = emptyList() },
            onConfirm = { edits ->
                if (!saving) {
                    saving = true
                    completed = 0
                    errorMessage = null
                    val sources = images
                    scope.launch {
                        try {
                            processBatchImageRotation(
                                edits = edits,
                                prepare = { index, turns ->
                                    try {
                                        prepareRotatedAttachment(context, filesManager, sources[index], turns)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        throw IllegalStateException("第 ${index + 1} 张图片处理失败，请重试或使用原图", error)
                                    }
                                },
                                discard = filesManager::deleteChatFiles,
                                commit = inputState::addImages,
                                onProgress = { count -> withContext(Dispatchers.Main) { completed = count } },
                            )
                            images = emptyList()
                            currentOnAttachmentAdded()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.e("BatchImageRotation", "Failed to prepare image attachments", error)
                            errorMessage = error.message ?: "图片处理失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            },
        )
    }

    return { selectedImages ->
        if (!saving) {
            images = selectedImages.toList()
            completed = 0
            errorMessage = null
        }
    }
}

// 未旋转的图片只复制原文件；旋转后的图片只编码一次，透明图片保留透明度。
private fun prepareRotatedAttachment(context: Context, filesManager: FilesManager, source: Uri, turns: Int): Uri {
    if (turns == 0) {
        return filesManager.createChatFilesByContents(listOf(source)).singleOrNull()
            ?: error("Failed to import image")
    }
    val bitmap = rotateImage(context, source, turns)
    try {
        val transparent = bitmap.hasAlpha()
        val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val output = File.createTempFile("image_rotation_", if (transparent) ".png" else ".jpg", context.appTempFolder)
        try {
            output.outputStream().use { check(bitmap.compress(format, 90, it)) { "Failed to encode image" } }
            return filesManager.createChatFilesByContents(listOf(output.toUri())).singleOrNull()
                ?: error("Failed to save rotated image")
        } finally {
            output.delete()
        }
    } finally {
        bitmap.recycle()
    }
}
