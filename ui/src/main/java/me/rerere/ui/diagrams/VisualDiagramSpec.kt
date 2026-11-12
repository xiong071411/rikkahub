package me.rerere.ui.diagrams

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private const val MAX_SPEC_LENGTH = 16_384
private const val MAX_ELEMENTS = 128
private const val MAX_POINTS = 64
private const val MAX_VIEW_SIZE = 1_000f
private const val MIN_LABEL_FONT_SIZE = 12f
private const val MAX_LABEL_FONT_SIZE = 32f

@Immutable
data class VisualDiagramPoint(val x: Float, val y: Float)

enum class VisualDiagramColor {
    TEXT,
    MUTED,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    ERROR,
    RED,
    BLUE,
    GREEN,
    ORANGE,
}

@Immutable
data class VisualDiagramSpec(
    val title: String?,
    val width: Float,
    val height: Float,
    val elements: List<VisualDiagramElement>,
) {
    companion object {
        fun fromJson(source: String): VisualDiagramSpec? = runCatching {
            require(source.length <= MAX_SPEC_LENGTH)
            val root = Json.parseToJsonElement(source) as? JsonObject ?: error("Expected an object")
            require(root.number("version") == 1f)
            val width = root.number("width") ?: error("Missing width")
            val height = root.number("height") ?: error("Missing height")
            require(width in 20f..MAX_VIEW_SIZE && height in 20f..MAX_VIEW_SIZE)
            val sourceElements = root["elements"] as? JsonArray ?: error("Missing elements")
            require(sourceElements.isNotEmpty() && sourceElements.size <= MAX_ELEMENTS)
            val elements = sourceElements.mapNotNull { element ->
                runCatching { parseElement(element, width, height) }.getOrNull()
            }
            require(elements.isNotEmpty())
            VisualDiagramSpec(
                title = root.string("title")?.trim()?.take(120)?.takeIf(String::isNotEmpty),
                width = width,
                height = height,
                elements = elements,
            )
        }.getOrNull()
    }
}

@Immutable
sealed interface VisualDiagramElement {
    data class Segment(
        val from: VisualDiagramPoint,
        val to: VisualDiagramPoint,
        val arrow: Boolean,
        val color: VisualDiagramColor,
        val strokeWidth: Float,
        val dashed: Boolean,
    ) : VisualDiagramElement

    data class Shape(
        val points: List<VisualDiagramPoint>,
        val closed: Boolean,
        val color: VisualDiagramColor,
        val fillColor: VisualDiagramColor?,
        val strokeWidth: Float,
        val dashed: Boolean,
    ) : VisualDiagramElement

    data class Rectangle(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val color: VisualDiagramColor,
        val fillColor: VisualDiagramColor?,
        val strokeWidth: Float,
        val dashed: Boolean,
    ) : VisualDiagramElement

    data class Circle(
        val center: VisualDiagramPoint,
        val radius: Float,
        val color: VisualDiagramColor,
        val fillColor: VisualDiagramColor?,
        val strokeWidth: Float,
        val dashed: Boolean,
    ) : VisualDiagramElement

    data class Label(
        val point: VisualDiagramPoint,
        val text: String,
        val color: VisualDiagramColor,
        val fontSize: Float,
        val alignment: Alignment,
    ) : VisualDiagramElement

    enum class Alignment {
        START,
        CENTER,
        END,
    }
}

private fun parseElement(element: JsonElement, width: Float, height: Float): VisualDiagramElement {
    val obj = element as? JsonObject ?: error("Diagram element must be an object")
    val color = obj.color("color", VisualDiagramColor.TEXT)
    val strokeWidth = obj.number("strokeWidth") ?: 2f
    require(strokeWidth in 0.5f..8f)
    val dashed = obj.boolean("dashed") ?: false
    return when (obj.string("type")) {
        "line", "arrow" -> VisualDiagramElement.Segment(
            from = obj.point("from", width, height),
            to = obj.point("to", width, height),
            arrow = obj.string("type") == "arrow",
            color = color,
            strokeWidth = strokeWidth,
            dashed = dashed,
        )

        "polyline", "polygon" -> {
            val points = obj["points"] as? JsonArray ?: error("Missing points")
            require(points.size in 2..MAX_POINTS)
            val closed = obj.string("type") == "polygon" || obj.boolean("closed") == true
            require(!closed || points.size >= 3)
            VisualDiagramElement.Shape(
                points = points.map { it.point(width, height) },
                closed = closed,
                color = color,
                fillColor = obj.colorOrNull("fill"),
                strokeWidth = strokeWidth,
                dashed = dashed,
            )
        }

        "rect" -> {
            val x = obj.number("x") ?: error("Missing x")
            val y = obj.number("y") ?: error("Missing y")
            val rectWidth = obj.number("width") ?: error("Missing width")
            val rectHeight = obj.number("height") ?: error("Missing height")
            require(rectWidth > 0f && rectHeight > 0f)
            requirePoint(x, y, width, height)
            requirePoint(x + rectWidth, y + rectHeight, width, height)
            VisualDiagramElement.Rectangle(
                x = x,
                y = y,
                width = rectWidth,
                height = rectHeight,
                color = color,
                fillColor = obj.colorOrNull("fill"),
                strokeWidth = strokeWidth,
                dashed = dashed,
            )
        }

        "circle" -> {
            val center = obj.point("center", width, height)
            val radius = obj.number("radius") ?: error("Missing radius")
            require(radius > 0f && center.x - radius >= 0f && center.x + radius <= width)
            require(center.y - radius >= 0f && center.y + radius <= height)
            VisualDiagramElement.Circle(
                center = center,
                radius = radius,
                color = color,
                fillColor = obj.colorOrNull("fill"),
                strokeWidth = strokeWidth,
                dashed = dashed,
            )
        }

        "text" -> {
            val point = obj.point("at", width, height)
            val text = obj.string("text")?.takeIf(String::isNotBlank) ?: error("Missing text")
            require(text.length <= 160)
            val fontSize = obj.number("fontSize") ?: 14f
            require(fontSize > 0f)
            VisualDiagramElement.Label(
                point = point,
                text = text,
                color = color,
                fontSize = fontSize.coerceIn(MIN_LABEL_FONT_SIZE, MAX_LABEL_FONT_SIZE),
                alignment = when (obj.string("align")) {
                    null, "center" -> VisualDiagramElement.Alignment.CENTER
                    "start" -> VisualDiagramElement.Alignment.START
                    "end" -> VisualDiagramElement.Alignment.END
                    else -> error("Unknown text alignment")
                },
            )
        }

        else -> error("Unknown diagram element")
    }
}

private fun JsonObject.point(key: String, width: Float, height: Float): VisualDiagramPoint =
    (this[key] ?: error("Missing $key")).point(width, height)

private fun JsonElement.point(width: Float, height: Float): VisualDiagramPoint {
    val array = this as? JsonArray ?: error("Point must be an array")
    require(array.size == 2)
    val point = VisualDiagramPoint(
        x = array[0].number() ?: error("Point x must be numeric"),
        y = array[1].number() ?: error("Point y must be numeric"),
    )
    requirePoint(point.x, point.y, width, height)
    return point
}

private fun requirePoint(x: Float, y: Float, width: Float, height: Float) {
    require(x.isFinite() && y.isFinite() && x in 0f..width && y in 0f..height)
}

private fun JsonObject.color(key: String, default: VisualDiagramColor): VisualDiagramColor =
    this[key]?.let { parseColor((it as? JsonPrimitive)?.contentOrNull ?: error("$key must be a color")) } ?: default

private fun JsonObject.colorOrNull(key: String): VisualDiagramColor? =
    this[key]?.let { parseColor((it as? JsonPrimitive)?.contentOrNull ?: error("$key must be a color")) }

private fun parseColor(value: String): VisualDiagramColor = when (value.lowercase()) {
    "text" -> VisualDiagramColor.TEXT
    "muted" -> VisualDiagramColor.MUTED
    "primary" -> VisualDiagramColor.PRIMARY
    "secondary" -> VisualDiagramColor.SECONDARY
    "tertiary" -> VisualDiagramColor.TERTIARY
    "error" -> VisualDiagramColor.ERROR
    "red" -> VisualDiagramColor.RED
    "blue" -> VisualDiagramColor.BLUE
    "green" -> VisualDiagramColor.GREEN
    "orange" -> VisualDiagramColor.ORANGE
    else -> error("Unknown diagram color")
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.number(key: String): Float? = this[key]?.number()

private fun JsonElement.number(): Float? =
    (this as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf(Float::isFinite)

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it.content == "true" || it.content == "false" }?.content?.toBoolean()
