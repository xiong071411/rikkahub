package me.rerere.ui.chemistry

import androidx.compose.runtime.Immutable
import com.actelion.research.chem.Molecule
import com.actelion.research.chem.SmilesParser
import com.actelion.research.chem.StereoMolecule
import com.actelion.research.chem.coords.CoordinateInventor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets

private const val MAX_SPEC_LENGTH = 8192
private const val MAX_SMILES_LENGTH = 512
private const val MAX_ATOMS = 80
private const val MAX_MOLECULES_PER_SIDE = 4

enum class ChemicalStructureType {
    MOLECULE,
    REACTION,
}

@Immutable
data class ChemicalMoleculeSpec(
    val smiles: String,
    val label: String?,
)

@Immutable
data class ChemicalStructureSpec(
    val type: ChemicalStructureType,
    val name: String?,
    val reactants: List<ChemicalMoleculeSpec>,
    val products: List<ChemicalMoleculeSpec>,
    val conditions: String?,
) {
    companion object {
        /** Parses the bounded JSON format used by `chemical-structure` Markdown fences. */
        fun fromJson(source: String): ChemicalStructureSpec? = runCatching {
            require(source.length <= MAX_SPEC_LENGTH)
            val root = Json.parseToJsonElement(source) as? JsonObject ?: error("Expected an object")
            require(root["version"]?.let(::integer) == 1)
            val name = root.string("name")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
            when (root.string("type")) {
                "molecule" -> {
                    val smiles = root.string("smiles")?.let(::parseSmiles).orEmpty()
                    require(smiles.isNotEmpty())
                    ChemicalStructureSpec(
                        type = ChemicalStructureType.MOLECULE,
                        name = name,
                        reactants = listOf(ChemicalMoleculeSpec(smiles, null)),
                        products = emptyList(),
                        conditions = null,
                    )
                }

                "reaction" -> {
                    val reactants = root["reactants"]?.let(::parseMoleculeArray).orEmpty()
                    val products = root["products"]?.let(::parseMoleculeArray).orEmpty()
                    require(reactants.isNotEmpty() && products.isNotEmpty())
                    require(reactants.size <= MAX_MOLECULES_PER_SIDE && products.size <= MAX_MOLECULES_PER_SIDE)
                    ChemicalStructureSpec(
                        type = ChemicalStructureType.REACTION,
                        name = name,
                        reactants = reactants,
                        products = products,
                        conditions = root.string("conditions")?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
                    )
                }

                else -> error("Unknown structure type")
            }
        }.getOrNull()
    }
}

@Immutable
data class ChemicalDiagramAtom(
    val x: Float,
    val y: Float,
    val label: String?,
)

@Immutable
data class ChemicalDiagramBond(
    val start: Int,
    val end: Int,
    val order: Int,
    val stereo: ChemicalBondStereo,
)

enum class ChemicalBondStereo {
    NONE,
    UP,
    DOWN,
    UNKNOWN,
}

@Immutable
data class ChemicalMoleculeDiagram(
    val label: String?,
    val atoms: List<ChemicalDiagramAtom>,
    val bonds: List<ChemicalDiagramBond>,
)

/** SMILES parsing and 2D coordinate generation are kept off the Compose thread by the caller. */
internal fun buildChemicalMoleculeDiagram(spec: ChemicalMoleculeSpec): ChemicalMoleculeDiagram {
    val molecule = StereoMolecule()
    SmilesParser().parse(molecule, spec.smiles.toByteArray(StandardCharsets.UTF_8), false, true)
    require(molecule.allAtoms in 1..MAX_ATOMS)
    molecule.ensureHelperArrays(Molecule.cHelperNeighbours)
    CoordinateInventor().apply { setRandomSeed(0L) }.invent(molecule)

    val diagramAtoms = (0 until molecule.allAtoms).map { atom ->
        val symbol = Molecule.cAtomLabel.getOrNull(molecule.getAtomicNo(atom)) ?: "*"
        ChemicalDiagramAtom(
            x = molecule.getAtomX(atom).toFloat().also { require(it.isFinite()) },
            y = molecule.getAtomY(atom).toFloat().also { require(it.isFinite()) },
            label = atomLabel(
                symbol = symbol,
                isotope = molecule.getAtomMass(atom).takeIf { it > 0 },
                charge = molecule.getAtomCharge(atom),
                hydrogens = molecule.getImplicitHydrogens(atom),
                degree = molecule.getAllConnAtoms(atom),
            ),
        )
    }
    val diagramBonds = (0 until molecule.allBonds).map { bond ->
        val type = molecule.getBondType(bond)
        val stereoBits = type and Molecule.cBondTypeMaskStereo
        ChemicalDiagramBond(
            start = molecule.getBondAtom(0, bond),
            end = molecule.getBondAtom(1, bond),
            order = molecule.getBondOrder(bond).coerceIn(1, 3),
            stereo = when {
                stereoBits == (Molecule.cBondTypeUp and Molecule.cBondTypeMaskStereo) -> ChemicalBondStereo.UP
                stereoBits == (Molecule.cBondTypeDown and Molecule.cBondTypeMaskStereo) -> ChemicalBondStereo.DOWN
                stereoBits == (Molecule.cBondTypeCross and Molecule.cBondTypeMaskStereo) -> ChemicalBondStereo.UNKNOWN
                else -> ChemicalBondStereo.NONE
            },
        )
    }
    require(diagramAtoms.size <= MAX_ATOMS && diagramBonds.size <= MAX_ATOMS * 2)
    return ChemicalMoleculeDiagram(spec.label, diagramAtoms, diagramBonds)
}

private fun atomLabel(
    symbol: String,
    isotope: Int?,
    charge: Int?,
    hydrogens: Int?,
    degree: Int,
): String? {
    val actualCharge = charge ?: 0
    if (symbol == "C" && degree > 0 && isotope == null && actualCharge == 0) return null
    return buildString {
        isotope?.let { append(it) }
        append(symbol)
        val hydrogenCount = hydrogens ?: 0
        if (hydrogenCount > 0) {
            append('H')
            if (hydrogenCount > 1) append(subscriptNumber(hydrogenCount))
        }
        if (actualCharge != 0) append(superscriptCharge(actualCharge))
    }
}

private fun subscriptNumber(value: Int): String = value.toString().map { digit ->
    when (digit) {
        '0' -> '\u2080'
        '1' -> '\u2081'
        '2' -> '\u2082'
        '3' -> '\u2083'
        '4' -> '\u2084'
        '5' -> '\u2085'
        '6' -> '\u2086'
        '7' -> '\u2087'
        '8' -> '\u2088'
        else -> '\u2089'
    }
}.joinToString("")

private fun superscriptCharge(charge: Int): String {
    val sign = if (charge > 0) "+" else "−"
    val magnitude = kotlin.math.abs(charge)
    return if (magnitude == 1) sign else magnitude.toString().map { digit ->
        when (digit) {
            '0' -> '\u2070'
            '1' -> '\u00B9'
            '2' -> '\u00B2'
            '3' -> '\u00B3'
            '4' -> '\u2074'
            '5' -> '\u2075'
            '6' -> '\u2076'
            '7' -> '\u2077'
            '8' -> '\u2078'
            else -> '\u2079'
        }
    }.joinToString("") + sign
}

private fun parseSmiles(source: String): String {
    val smiles = source.trim()
    require(smiles.isNotEmpty() && smiles.length <= MAX_SMILES_LENGTH)
    require(smiles.none { it.isWhitespace() })
    return smiles
}

private fun parseMoleculeArray(element: JsonElement): List<ChemicalMoleculeSpec> {
    val array = element as? JsonArray ?: error("Expected a molecule array")
    require(array.isNotEmpty() && array.size <= MAX_MOLECULES_PER_SIDE)
    return array.map { moleculeElement ->
        val molecule = moleculeElement as? JsonObject ?: error("Expected a molecule object")
        ChemicalMoleculeSpec(
            smiles = molecule.string("smiles")?.let(::parseSmiles) ?: error("Missing SMILES"),
            label = molecule.string("label")?.trim()?.take(64)?.takeIf { it.isNotEmpty() },
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun integer(element: JsonElement): Int? =
    (element as? JsonPrimitive)?.intOrNull
