package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import me.rerere.ui.crop.rotateImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 35], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchImageRotationBitmapTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `quarter turns rotate actual pixels without cropping or changing original file`() {
        val source = temporary.newFile("source.png")
        val pixels = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)
        val bitmap = Bitmap.createBitmap(pixels, 2, 3, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val originalBytes = source.readBytes()
        val expected = listOf(
            intArrayOf(Color.MAGENTA, Color.BLUE, Color.RED, Color.CYAN, Color.YELLOW, Color.GREEN),
            intArrayOf(Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            intArrayOf(Color.GREEN, Color.YELLOW, Color.CYAN, Color.RED, Color.BLUE, Color.MAGENTA),
        )
        for (turns in 1..3) {
            val result = rotateImage(context, source.toUri(), turns)
            try {
                assertEquals(if (turns % 2 == 0) 2 else 3, result.width)
                assertEquals(if (turns % 2 == 0) 3 else 2, result.height)
                val actual = IntArray(6)
                result.getPixels(actual, 0, result.width, 0, 0, result.width, result.height)
                assertArrayEquals(expected[turns - 1], actual)
            } finally {
                result.recycle()
            }
        }
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun `rotation is relative to EXIF orientation and exports upright pixels`() {
        val source = temporary.newFile("exif.jpg")
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 0 until 15) for (x in 0 until 20) bitmap.setPixel(x, y, Color.RED)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_180.toString())
            saveAttributes()
        }
        val raw = BitmapFactory.decodeFile(source.path)
        val result = rotateImage(context, source.toUri(), 2)
        try {
            assertEquals(raw.width, result.width)
            assertEquals(raw.height, result.height)
            assertEquals(raw.getPixel(10, 5), result.getPixel(10, 5))
            assertEquals(raw.getPixel(10, 25), result.getPixel(10, 25))
        } finally {
            raw.recycle()
            result.recycle()
        }
    }

    @Test
    fun `large transparent images stay within memory limit and keep alpha`() {
        val source = temporary.newFile("transparent.png")
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val result = rotateImage(context, source.toUri(), 1, maxSize = 128)
        try {
            assertTrue(maxOf(result.width, result.height) <= 128)
            assertEquals(result.width * 2, result.height)
            assertTrue(result.hasAlpha())
            assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        } finally {
            result.recycle()
        }
    }
}
