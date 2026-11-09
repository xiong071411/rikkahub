package me.rerere.ui.charts

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

private const val MAX_SPEC_LENGTH = 8192
private const val MAX_EXPRESSION_LENGTH = 256
private const val MAX_FUNCTIONS = 8
private const val MAX_TICKS = 16
private const val MAX_EXPRESSION_DEPTH = 32
private const val MAX_EXPRESSION_NODES = 256
private const val MAX_RANGE_MAGNITUDE = 1_000_000.0
private const val PI_SYMBOL = "\u03C0"
private const val TAU_SYMBOL = "\u03C4"

@Immutable
class FunctionPlotTick internal constructor(
    val value: Double,
    val label: String?,
)

@Immutable
class FunctionPlotFunction internal constructor(
    val expression: String,
    val label: String?,
    private val compiled: MathExpression,
) {
    fun evaluate(x: Double): Double = compiled.evaluate(x)
}

@Immutable
class FunctionPlotSpec internal constructor(
    val title: String?,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double?,
    val yMax: Double?,
    val xTicks: List<FunctionPlotTick>,
    val yTicks: List<FunctionPlotTick>,
    val functions: List<FunctionPlotFunction>,
) {
    companion object {
        /** Parses the bounded JSON format used by `function-plot` Markdown fences. */
        fun fromJson(source: String): FunctionPlotSpec? = runCatching {
            require(source.length <= MAX_SPEC_LENGTH)
            val root = Json.parseToJsonElement(source) as? JsonObject ?: error("Expected an object")
            val xRange = root["xRange"]?.let(::parseRange) ?: (-10.0 to 10.0)
            val yRange = root["yRange"]?.let(::parseRange)
            require(xRange.first < xRange.second)
            require(xRange.first >= -MAX_RANGE_MAGNITUDE && xRange.second <= MAX_RANGE_MAGNITUDE)
            require(yRange == null || yRange.first < yRange.second)
            require(yRange == null || (yRange.first >= -MAX_RANGE_MAGNITUDE && yRange.second <= MAX_RANGE_MAGNITUDE))

            val functionElements = root["functions"] as? JsonArray ?: error("Missing functions")
            require(functionElements.isNotEmpty() && functionElements.size <= MAX_FUNCTIONS)
            val functions = functionElements.map { element ->
                val obj = element as? JsonObject ?: error("Function must be an object")
                val expression = obj.string("expression")?.trim().orEmpty()
                require(expression.isNotEmpty() && expression.length <= MAX_EXPRESSION_LENGTH)
                val compiled = ExpressionParser(expression, allowVariable = true).parse()
                FunctionPlotFunction(
                    expression = expression,
                    label = obj.string("label")?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
                    compiled = compiled,
                )
            }
            FunctionPlotSpec(
                title = root.string("title")?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
                xMin = xRange.first,
                xMax = xRange.second,
                yMin = yRange?.first,
                yMax = yRange?.second,
                xTicks = root["xTicks"]?.let(::parseTicks).orEmpty(),
                yTicks = root["yTicks"]?.let(::parseTicks).orEmpty(),
                functions = functions,
            )
        }.getOrNull()
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun parseRange(element: JsonElement): Pair<Double, Double> {
    val range = element as? JsonArray ?: error("Range must be an array")
    require(range.size == 2)
    val min = parseConstant(range[0])
    val max = parseConstant(range[1])
    require(min.isFinite() && max.isFinite())
    return min to max
}

private fun parseTicks(element: JsonElement): List<FunctionPlotTick> {
    val ticks = element as? JsonArray ?: error("Ticks must be an array")
    require(ticks.size <= MAX_TICKS)
    return ticks.map { tick ->
        val valueElement: JsonElement
        val explicitLabel: String?
        if (tick is JsonObject) {
            valueElement = tick["value"] ?: error("Tick value is required")
            explicitLabel = tick.string("label")?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
        } else {
            valueElement = tick
            explicitLabel = null
        }
        val value = parseConstant(valueElement)
        require(value.isFinite() && abs(value) <= MAX_RANGE_MAGNITUDE)
        FunctionPlotTick(value, explicitLabel ?: (valueElement as? JsonPrimitive)?.let { primitive ->
            if (primitive.isString) normalizeMathLabel(primitive.content) else null
        })
    }.distinctBy { it.value }.sortedBy { it.value }
}

private fun parseConstant(element: JsonElement): Double {
    val primitive = element as? JsonPrimitive ?: error("Expected a number or math expression")
    if (!primitive.isString) return primitive.doubleOrNull ?: error("Expected a finite number")
    val source = primitive.content.trim()
    require(source.isNotEmpty() && source.length <= MAX_EXPRESSION_LENGTH)
    return ExpressionParser(source, allowVariable = false).parse().evaluate(0.0)
}

private fun normalizeMathLabel(source: String): String = source.trim()
    .replace("tau", TAU_SYMBOL, ignoreCase = true)
    .replace("pi", PI_SYMBOL, ignoreCase = true)
    .replace("*", "")
    .replace("-", "\u2212")

@Immutable
internal sealed interface MathExpression {
    fun evaluate(x: Double): Double

    data class Constant(val value: Double) : MathExpression {
        override fun evaluate(x: Double): Double = value
    }

    data object Variable : MathExpression {
        override fun evaluate(x: Double): Double = x
    }

    data class Unary(val operator: Char, val operand: MathExpression) : MathExpression {
        override fun evaluate(x: Double): Double = when (operator) {
            '-' -> -operand.evaluate(x)
            else -> operand.evaluate(x)
        }
    }

    data class Binary(
        val operator: Char,
        val left: MathExpression,
        val right: MathExpression,
    ) : MathExpression {
        override fun evaluate(x: Double): Double {
            val a = left.evaluate(x)
            val b = right.evaluate(x)
            return when (operator) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '%' -> a % b
                '^' -> a.pow(b)
                else -> Double.NaN
            }
        }
    }

    data class Call(val name: String, val arguments: List<MathExpression>) : MathExpression {
        override fun evaluate(x: Double): Double {
            val values = arguments.map { it.evaluate(x) }
            return when (name) {
                "sin" -> values.single().let(::sin)
                "cos" -> values.single().let(::cos)
                "tan" -> values.single().let(::tan)
                "asin", "arcsin" -> values.single().let(::asin)
                "acos", "arccos" -> values.single().let(::acos)
                "atan", "arctan" -> values.single().let(::atan)
                "atan2" -> atan2(values[0], values[1])
                "sinh" -> values.single().let(::sinh)
                "cosh" -> values.single().let(::cosh)
                "tanh" -> values.single().let(::tanh)
                "asinh" -> values.single().let { sign(it) * ln(abs(it) + sqrt(it * it + 1.0)) }
                "acosh" -> values.single().let { ln(it + sqrt(it * it - 1.0)) }
                "atanh" -> values.single().let { 0.5 * ln((1.0 + it) / (1.0 - it)) }
                "sec" -> 1.0 / cos(values.single())
                "csc" -> 1.0 / sin(values.single())
                "cot" -> cos(values.single()) / sin(values.single())
                "sqrt" -> values.single().let(::sqrt)
                "cbrt" -> values.single().let { Math.cbrt(it) }
                "abs" -> abs(values.single())
                "exp" -> values.single().let(::exp)
                "expm1" -> values.single().let(::expm1)
                "ln" -> values.single().let(::ln)
                "log" -> if (values.size == 1) log10(values[0]) else ln(values[0]) / ln(values[1])
                "log10" -> values.single().let(::log10)
                "log2" -> values.single().let(::log2)
                "log1p" -> values.single().let(::ln1p)
                "floor" -> values.single().let(::floor)
                "ceil" -> values.single().let(::ceil)
                "round" -> values.single().let { Math.rint(it) }
                "sign" -> values.single().let(::sign)
                "min" -> values.minOrNull() ?: Double.NaN
                "max" -> values.maxOrNull() ?: Double.NaN
                "pow" -> values[0].pow(values[1])
                "hypot" -> hypot(values[0], values[1])
                else -> Double.NaN
            }
        }
    }
}

private class ExpressionParser(
    source: String,
    private val allowVariable: Boolean,
) {
    private val source = source.trim()
        .replace("\u03C0", "pi", ignoreCase = true)
        .replace("\u03C4", "tau", ignoreCase = true)
        .replace("\u2212", "-")
        .replace("\u00D7", "*")
        .replace("\u00B7", "*")
        .replace("\u00F7", "/")
    private var position = 0
    private var nodeCount = 0

    fun parse(): MathExpression {
        val expression = parseExpression(0)
        skipWhitespace()
        require(position == source.length) { "Unexpected token" }
        return expression
    }

    private fun parseExpression(depth: Int): MathExpression {
        checkDepth(depth)
        var expression = parseTerm(depth + 1)
        while (true) {
            skipWhitespace()
            val operator = peek()
            if (operator != '+' && operator != '-') return expression
            position++
            expression = newNode(MathExpression.Binary(operator, expression, parseTerm(depth + 1)))
        }
    }

    private fun parseTerm(depth: Int): MathExpression {
        var expression = parseUnary(depth + 1)
        while (true) {
            skipWhitespace()
            val operator = peek()
            if (operator == '*' || operator == '/' || operator == '%') {
                position++
                expression = newNode(MathExpression.Binary(operator, expression, parseUnary(depth + 1)))
            } else if (startsImplicitFactor()) {
                expression = newNode(MathExpression.Binary('*', expression, parseUnary(depth + 1)))
            } else {
                return expression
            }
        }
    }

    private fun parseUnary(depth: Int): MathExpression {
        checkDepth(depth)
        skipWhitespace()
        val operator = peek()
        if (operator == '+' || operator == '-') {
            position++
            val operand = parseUnary(depth + 1)
            return if (operator == '+') operand else newNode(MathExpression.Unary(operator, operand))
        }
        return parsePower(depth + 1)
    }

    private fun parsePower(depth: Int): MathExpression {
        var expression = parsePrimary(depth + 1)
        skipWhitespace()
        if (peek() == '^') {
            position++
            expression = newNode(MathExpression.Binary('^', expression, parseUnary(depth + 1)))
        }
        return expression
    }

    private fun parsePrimary(depth: Int): MathExpression {
        checkDepth(depth)
        skipWhitespace()
        return when {
            peek() == '(' -> {
                position++
                val expression = parseExpression(depth + 1)
                skipWhitespace()
                require(peek() == ')') { "Missing closing parenthesis" }
                position++
                expression
            }
            peek()?.isDigit() == true || peek() == '.' -> newNode(MathExpression.Constant(parseNumber()))
            peek()?.isLetter() == true -> parseIdentifier(depth + 1)
            else -> error("Expected a value")
        }
    }

    private fun parseIdentifier(depth: Int): MathExpression {
        val start = position
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) position++
        val name = source.substring(start, position).lowercase()
        skipWhitespace()
        if (peek() == '(') {
            require(name in FUNCTIONS) { "Unsupported function" }
            position++
            val arguments = mutableListOf<MathExpression>()
            skipWhitespace()
            if (peek() != ')') {
                do {
                    arguments += parseExpression(depth + 1)
                    skipWhitespace()
                    if (peek() != ',') break
                    position++
                } while (true)
            }
            require(peek() == ')') { "Missing function parenthesis" }
            position++
            validateFunctionArguments(name, arguments.size)
            return newNode(MathExpression.Call(name, arguments))
        }
        return when (name) {
            "x" -> {
                require(allowVariable) { "Variable is not allowed here" }
                newNode(MathExpression.Variable)
            }
            "pi" -> newNode(MathExpression.Constant(PI))
            "e" -> newNode(MathExpression.Constant(Math.E))
            "tau" -> newNode(MathExpression.Constant(2.0 * PI))
            else -> error("Unsupported symbol")
        }
    }

    private fun parseNumber(): Double {
        val start = position
        var digits = 0
        while (peek()?.isDigit() == true) {
            position++
            digits++
        }
        if (peek() == '.') {
            position++
            while (peek()?.isDigit() == true) {
                position++
                digits++
            }
        }
        require(digits > 0) { "Invalid number" }
        if (peek() == 'e' || peek() == 'E') {
            var exponent = position + 1
            if (source.getOrNull(exponent) == '+' || source.getOrNull(exponent) == '-') exponent++
            if (source.getOrNull(exponent)?.isDigit() == true) {
                position = exponent + 1
                while (peek()?.isDigit() == true) position++
            }
        }
        return source.substring(start, position).toDouble()
    }

    private fun startsImplicitFactor(): Boolean = when (val char = peek()) {
        '(' -> true
        else -> char?.isLetter() == true
    }

    private fun newNode(expression: MathExpression): MathExpression {
        nodeCount++
        require(nodeCount <= MAX_EXPRESSION_NODES) { "Expression is too complex" }
        return expression
    }

    private fun checkDepth(depth: Int) {
        require(depth <= MAX_EXPRESSION_DEPTH) { "Expression is too deeply nested" }
    }

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) position++
    }

    private fun peek(): Char? = source.getOrNull(position)

    private fun validateFunctionArguments(name: String, count: Int) {
        val valid = when (name) {
            "atan2", "hypot", "pow" -> count == 2
            "log" -> count == 1 || count == 2
            "min", "max" -> count >= 2
            else -> count == 1
        }
        require(valid) { "Invalid function arguments" }
    }

    private companion object {
        val FUNCTIONS = setOf(
            "sin", "cos", "tan", "asin", "arcsin", "acos", "arccos", "atan", "arctan", "atan2",
            "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "sec", "csc", "cot",
            "sqrt", "cbrt", "abs", "exp", "expm1", "ln", "log", "log10", "log2", "log1p",
            "floor", "ceil", "round", "sign", "min", "max", "pow", "hypot",
        )
    }
}
