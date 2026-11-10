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
}
