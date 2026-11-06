package me.rerere.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri

/**
 * 旋转整张图片，不裁剪；角度相对于按 EXIF 摆正后的图片。
 * 与单图编辑一样限制长边，调用方须在后台线程执行，并在保存后回收返回的位图。
 */
fun rotateImage(context: Context, uri: Uri, quarterTurns: Int, maxSize: Int = 4096): Bitmap {
    val source = decodeUprightBitmap(context, uri, maxSize)
    val turns = Math.floorMod(quarterTurns, 4)
    if (turns == 0) return source
    return try {
        val matrix = Matrix().apply { postRotate(turns * 90f) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false).also { rotated ->
            if (rotated !== source) source.recycle()
        }
    } catch (error: Throwable) {
        source.recycle()
        throw error
    }
}
