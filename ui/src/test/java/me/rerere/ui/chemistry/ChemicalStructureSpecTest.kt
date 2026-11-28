package me.rerere.ui.chemistry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChemicalStructureSpecTest {
    @Test
    fun `parses and lays out benzene with alternating bonds`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"molecule","name":"Benzene","smiles":"c1ccccc1"}""",
        )

        assertNotNull(spec)
        val diagram = buildChemicalMoleculeDiagram(spec!!.reactants.single())
        assertEquals(6, diagram.atoms.size)
        assertEquals(6, diagram.bonds.size)
        assertEquals(3, diagram.bonds.count { it.order == 2 })
        assertTrue(diagram.atoms.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun `renders a branched carbonyl molecule and marks hetero atoms`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"molecule","smiles":"CC(=O)O"}""",
        )

        val diagram = buildChemicalMoleculeDiagram(spec!!.reactants.single())
        assertEquals(4, diagram.atoms.size)
        assertEquals(3, diagram.bonds.size)
        assertEquals(2, diagram.atoms.count { it.label?.startsWith("O") == true })
    }

    @Test
    fun `preserves tetrahedral stereochemistry as a wedge bond`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"molecule","smiles":"C[C@H](O)C(=O)O"}""",
        )

        val diagram = buildChemicalMoleculeDiagram(spec!!.reactants.single())
        assertTrue(diagram.bonds.any { it.stereo == ChemicalBondStereo.UP || it.stereo == ChemicalBondStereo.DOWN })
    }

    @Test
    fun `omits CIP annotations while keeping stereochemical bonds`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"molecule","smiles":"C[C@H](O)C(=O)O"}""",
        )

        val diagram = buildChemicalMoleculeDiagram(spec!!.reactants.single())
        val labels = diagram.primitives.filterIsInstance<ChemicalDrawPrimitive.Text>().map { it.text }
        val labelPrimitives = diagram.primitives.filterIsInstance<ChemicalDrawPrimitive.Text>()
        assertTrue(labels.none { it in setOf("R", "S", "r", "s", "E", "Z", "P", "M") })
        assertTrue(labelPrimitives.all { it.color == 0xFF000000.toInt() })
        assertTrue(diagram.bonds.any { it.stereo == ChemicalBondStereo.UP || it.stereo == ChemicalBondStereo.DOWN })
    }

    @Test
    fun `suppresses stereo problem magenta or red highlights on unspecified chiral and alkene centers`() {
        val samples = listOf(
            // 未标立体构型的九肽催产素（多处未定手性碳）
            "CCC(C)C1C(=O)NC(C(=O)NC(C(=O)NC(CSSCC(C(=O)NC(C(=O)N1)CC2=CC=C(C=C2)O)N)C(=O)N3CCCC3C(=O)NC(CC(C)C)C(=O)NCC(=O)N)CC(=O)N)CCC(=O)N",
            // 未标手性磷的有机磷分子
            "CC(C)OP(=O)(C)F",
            // 未标顺反构型的双键
            "CC=CC",
        )
        samples.forEach { smiles ->
            val spec = ChemicalStructureSpec.fromJson("""{"version":1,"type":"molecule","smiles":"$smiles"}""")
            assertNotNull(spec)
            val diagram = buildChemicalMoleculeDiagram(spec!!.reactants.single())
            assertTrue(diagram.primitives.isNotEmpty())
            assertTrue(diagram.primitives.all { primitiveColor(it) == 0xFF000000.toInt() })
        }
    }

    @Test
    fun `fits tight content bounds for small molecules and keeps primitives inside canvas`() {
        val ethene = buildChemicalMoleculeDiagram(
            ChemicalStructureSpec.fromJson("""{"version":1,"type":"molecule","smiles":"C=C"}""")!!.reactants.single(),
        )
        val toluene = buildChemicalMoleculeDiagram(
            ChemicalStructureSpec.fromJson("""{"version":1,"type":"molecule","smiles":"Cc1ccccc1"}""")!!.reactants.single(),
        )

        assertTrue(ethene.width < 120f && ethene.height < 100f)
        assertTrue(toluene.width < 180f && toluene.height < 160f)
        listOf(ethene, toluene).forEach { diagram ->
            assertPrimitivesWithinBounds(diagram)
        }
    }

    @Test
    fun `keeps harmonious stroke weights and resolves complex bridged or macrocycle overlaps`() {
        val artemisinin = buildChemicalMoleculeDiagram(
            ChemicalStructureSpec.fromJson(
                """{"version":1,"type":"molecule","smiles":"C[C@@H]1CC[C@H]2[C@@H](C)C(=O)O[C@@H]3[C@@]24[C@@H]1CC[C@@](O3)(OO4)C"}""",
            )!!.reactants.single(),
        )
        val oxytocin = buildChemicalMoleculeDiagram(
            ChemicalStructureSpec.fromJson(
                """{"version":1,"type":"molecule","smiles":"CCC(C)C1C(=O)NC(C(=O)NC(C(=O)NC(CSSCC(C(=O)NC(C(=O)N1)CC2=CC=C(C=C2)O)N)C(=O)N3CCCC3C(=O)NC(CC(C)C)C(=O)NCC(=O)N)CC(=O)N)CCC(=O)N"}""",
            )!!.reactants.single(),
        )
        assertPrimitivesWithinBounds(artemisinin)
        assertPrimitivesWithinBounds(oxytocin)
        val maxStroke = artemisinin.primitives.filterIsInstance<ChemicalDrawPrimitive.Line>().maxOf { it.width }
        val maxFont = artemisinin.primitives.filterIsInstance<ChemicalDrawPrimitive.Text>().maxOf { it.size }
        assertTrue("Expected balanced line width <= 2.2f, got $maxStroke", maxStroke <= 2.2f)
        assertTrue("Expected balanced atom font <= 17f, got $maxFont", maxFont <= 17f)
        // 大环催产素横向展开后应利用横向空间且字体与线宽清晰可读
        assertTrue(oxytocin.width >= oxytocin.height)
    }

    @Test
    fun `parses simple reaction participants and conditions`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"reaction","reactants":[{"smiles":"CCBr"},{"smiles":"[OH-]"}],"products":[{"smiles":"CCO"},{"smiles":"[Br-]"}],"conditions":"NaOH"}""",
        )

        assertNotNull(spec)
        assertEquals(ChemicalStructureType.REACTION, spec!!.type)
        assertEquals(2, spec.reactants.size)
        assertEquals(2, spec.products.size)
        assertEquals("NaOH", spec.conditions)
        assertEquals(4, (spec.reactants + spec.products).map(::buildChemicalMoleculeDiagram).size)
    }

    @Test
    fun `parses and lays out a polymer repeat unit with continuation atoms`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"polymer","name":"Polyethylene","repeatUnit":{"smiles":"CC","leftAtom":0,"rightAtom":1}}""",
        )

        assertNotNull(spec)
        assertEquals(ChemicalStructureType.POLYMER, spec!!.type)
        assertEquals("n", spec.repeatLabel)
        val diagram = buildChemicalMoleculeDiagram(spec.reactants.single())
        assertNotNull(diagram.repeatBounds)
        assertTrue(diagram.primitives.isNotEmpty())
        assertPrimitivesWithinBounds(diagram)
        val repeatBounds = diagram.repeatBounds!!
        val leftX = minOf(repeatBounds.leftAtom.x, repeatBounds.rightAtom.x)
        val rightX = maxOf(repeatBounds.leftAtom.x, repeatBounds.rightAtom.x)
        assertTrue(leftX - 22f >= 0f)
        assertTrue(rightX + 22f <= diagram.width)
        assertTrue(repeatBounds.top >= 0f && repeatBounds.bottom <= diagram.height)
    }

    @Test
    fun `rejects polymer continuation atoms that are not terminal`() {
        val spec = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"polymer","repeatUnit":{"smiles":"C1CC1","leftAtom":0,"rightAtom":1}}""",
        )
        assertNotNull(spec)
        assertTrue(runCatching { buildChemicalMoleculeDiagram(spec!!.reactants.single()) }.isFailure)
    }

    @Test
    fun `rejects invalid or unbounded specifications and invalid smiles`() {
        assertNull(ChemicalStructureSpec.fromJson("""{"type":"molecule","smiles":"C"}"""))
        assertNull(ChemicalStructureSpec.fromJson("""{"version":1,"type":"molecule","smiles":"C C"}"""))
        assertNull(ChemicalStructureSpec.fromJson("""{"version":1,"type":"reaction","reactants":[],"products":[]}"""))
        assertNull(ChemicalStructureSpec.fromJson("x".repeat(8193)))
        val invalidSmiles = ChemicalStructureSpec.fromJson(
            """{"version":1,"type":"molecule","smiles":"C1CC"}""",
        )
        assertNotNull(invalidSmiles)
        assertTrue(runCatching { buildChemicalMoleculeDiagram(invalidSmiles!!.reactants.single()) }.isFailure)
    }

    private fun primitiveColor(primitive: ChemicalDrawPrimitive): Int = when (primitive) {
        is ChemicalDrawPrimitive.Line -> primitive.color
        is ChemicalDrawPrimitive.Polygon -> primitive.color
        is ChemicalDrawPrimitive.Circle -> primitive.color
        is ChemicalDrawPrimitive.Text -> primitive.color
    }

    private fun assertPrimitivesWithinBounds(diagram: ChemicalMoleculeDiagram) {
        diagram.primitives.forEach { primitive ->
            when (primitive) {
                is ChemicalDrawPrimitive.Line -> {
                    assertTrue(primitive.x1 in 0f..diagram.width && primitive.x2 in 0f..diagram.width)
                    assertTrue(primitive.y1 in 0f..diagram.height && primitive.y2 in 0f..diagram.height)
                }

                is ChemicalDrawPrimitive.Polygon -> primitive.points.forEach { point ->
                    assertTrue(point.x in 0f..diagram.width && point.y in 0f..diagram.height)
                }

                is ChemicalDrawPrimitive.Circle -> {
                    assertTrue(primitive.x in 0f..diagram.width && primitive.y in 0f..diagram.height)
                }

                is ChemicalDrawPrimitive.Text -> {
                    assertTrue(primitive.x in 0f..diagram.width && primitive.y in 0f..diagram.height)
                }
            }
        }
    }
}
