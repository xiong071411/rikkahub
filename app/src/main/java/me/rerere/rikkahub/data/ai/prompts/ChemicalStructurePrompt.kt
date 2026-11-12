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

For a polymer, describe the smallest repeat unit and explicitly identify the two atoms connected to neighboring repeat units. Never draw a short alkane as a stand-in for a polymer chain:
```chemical-structure
{"version":1,"type":"polymer","name":"Polyethylene","repeatUnit":{"smiles":"CC","leftAtom":0,"rightAtom":1},"repeatLabel":"n"}
```

Use valid SMILES and include every reactant and product. Check that reaction products match the written chemistry; a valid SMILES can still be the wrong product. Do not invent structures or stereochemistry when the source information is insufficient. Use one block per molecule when a standalone structure is being explained. Put explanations and formulas in normal Markdown text; write formula digits as Unicode subscripts and avoid `\ce{}`. Only include a structure block when it helps answer the question. Reaction mechanism arrows are not supported in the block. The app draws stereochemistry with wedge bonds and omits small CIP labels such as R/S and E/Z from the image; describe them in text if relevant.
""".trimIndent()
