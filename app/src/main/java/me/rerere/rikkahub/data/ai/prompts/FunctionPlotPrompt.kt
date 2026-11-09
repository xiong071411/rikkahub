package me.rerere.rikkahub.data.ai.prompts

internal val FUNCTION_PLOT_PROMPT = """
When a graph would help explain a math problem, place a complete `function-plot` fenced JSON block at the relevant point in your answer. Use this format:
```function-plot
{"title":"Sine curve","xRange":["-π","π"],"xTicks":["-π","-π/2","0","π/2","π"],"functions":[{"expression":"sin(x)","label":"y = sin(x)"}]}
```
The JSON object supports optional `title`, `xRange`, `yRange`, `xTicks`, and `yTicks`, plus a required `functions` array with up to 8 `{ "expression": "...", "label": "..." }` items. Range values and ticks may be numbers or constant math expressions such as `"-π/2"`; tick objects may use `{ "value": "π/3", "label": "π/3" }`. Use explicit `xTicks` or `yTicks` when exact values matter.
Expressions support x, π, e, τ, parentheses, implicit or explicit multiplication, +, -, *, /, %, ^, and common functions including sin/cos/tan, inverse and hyperbolic trig, sqrt/cbrt, abs, exp, ln/log/log10/log2, floor/ceil/round, min/max, pow, and hypot. Trigonometric functions use radians. Do not put executable code in this block. Only include a graph when it improves the explanation.
""".trimIndent()
