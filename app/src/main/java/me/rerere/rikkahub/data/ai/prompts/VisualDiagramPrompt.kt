package me.rerere.rikkahub.data.ai.prompts

internal val VISUAL_DIAGRAM_PROMPT = """
When a static educational illustration would clarify an answer, add one complete `visual-diagram` fenced JSON block. This renderer supports labeled 2D diagrams such as free-body diagrams, inclined planes, conveyor belts, coordinate geometry, and projected solids. It does not run code or simulate physics.

Use this format:
```visual-diagram
{"version":1,"title":"Block on an incline","width":100,"height":70,"elements":[{"type":"line","from":[12,58],"to":[88,32],"color":"muted","strokeWidth":2},{"type":"polygon","points":[[42,39],[53,36],[56,47],[45,50]],"color":"text","fill":"secondary","strokeWidth":2},{"type":"arrow","from":[49,43],"to":[49,18],"color":"red","strokeWidth":2},{"type":"text","at":[52,18],"text":"N","align":"start","color":"red"}]}
```

The view is `width` by `height`, with coordinates from 0 to the corresponding dimension. `elements` may contain `line`, `arrow`, `polyline`, `polygon`, `rect`, `circle`, and `text`. Use `dashed:true` for hidden edges or construction lines. Colors are `text`, `muted`, `primary`, `secondary`, `tertiary`, `error`, `red`, `blue`, `green`, and `orange`. Shapes may use a `fill` color and `strokeWidth`; text may use `fontSize` and `align` (`start`, `center`, or `end`). Keep labels short, draw each vector and its label as separate elements, and do not invent measurements or force values.

Use the specialized `function-plot` format for function graphs and `chemical-structure` for molecules and reactions. For polymers, never draw a finite alkane as a substitute for an unbounded chain. Use a `repeatUnit` participant with its two continuation atoms, or omit the structure block if the repeat unit is uncertain. Only include a diagram when it improves the answer.
""".trimIndent()
