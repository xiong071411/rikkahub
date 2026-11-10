package me.rerere.rikkahub.data.ai.prompts

internal val CHEMICAL_STRUCTURE_PROMPT = """
When a molecular structure would make a chemistry explanation clearer, add a complete `chemical-structure` fenced JSON block near the relevant text. Do not use LaTeX chemfig, TikZ, or ASCII drawings.

For a molecule, use this format:
```chemical-structure
{"version":1,"type":"molecule","name":"Benzene","smiles":"c1ccccc1"}
```

For a simple one-step reaction scheme, use this format:
```chemical-structure
{"version":1,"type":"reaction","name":"Example reaction","reactants":[{"smiles":"CCBr"},{"smiles":"[OH-]"}],"products":[{"smiles":"CCO"},{"smiles":"[Br-]"}],"conditions":"NaOH"}
```

Use valid SMILES and include every reactant and product. Use one block per molecule when a standalone structure is being explained. Do not guess a structure when the source information is insufficient. Put explanations and formulas in normal Markdown text; write formula digits as Unicode subscripts and avoid `\ce{}`. Only include a structure block when it helps answer the question. Reaction mechanism arrows are not supported in the block.
""".trimIndent()
