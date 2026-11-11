package me.rerere.ui.chemistry

import androidx.compose.runtime.Immutable
import com.actelion.research.chem.AbstractDepictor
import com.actelion.research.chem.Molecule
import com.actelion.research.chem.SmilesParser
import com.actelion.research.chem.StereoMolecule
import com.actelion.research.chem.coords.CoordinateInventor
import com.actelion.research.gui.generic.GenericDepictor
import com.actelion.research.gui.generic.GenericDrawContext
import com.actelion.research.gui.generic.GenericImage
import com.actelion.research.gui.generic.GenericPolygon
import com.actelion.research.gui.generic.GenericRectangle
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
private const val DIAGRAM_WIDTH = 360
private const val DIAGRAM_HEIGHT = 240

enum class ChemicalStructureType {
    MOLECULE,
    REACTION,
    POLYMER,
}

@Immutable
data class ChemicalMoleculeSpec(
    val smiles: String,
    val label: String?,
    val leftContinuationAtom: Int? = null,
    val rightContinuationAtom: Int? = null,
)

@Immutable
data class ChemicalStructureSpec(
    val type: ChemicalStructureType,
    val name: String?,
    val reactants: List<ChemicalMoleculeSpec>,
    val products: List<ChemicalMoleculeSpec>,
    val conditions: String?,
    val repeatLabel: String? = null,
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

                "polymer" -> {
                    val repeatUnit = root["repeatUnit"] as? JsonObject ?: error("Missing repeatUnit")
                    val smiles = repeatUnit.string("smiles")?.let(::parseSmiles) ?: error("Missing repeat-unit SMILES")
                    val leftAtom = repeatUnit.int("leftAtom") ?: error("Missing left continuation atom")
                    val rightAtom = repeatUnit.int("rightAtom") ?: error("Missing right continuation atom")
                    require(leftAtom >= 0 && rightAtom >= 0 && leftAtom != rightAtom)
                    ChemicalStructureSpec(
                        type = ChemicalStructureType.POLYMER,
                        name = name,
                        reactants = listOf(ChemicalMoleculeSpec(smiles, null, leftAtom, rightAtom)),
                        products = emptyList(),
                        conditions = root.string("conditions")?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
                        repeatLabel = root.string("repeatLabel")?.trim()?.take(8)?.takeIf { it.isNotEmpty() } ?: "n",
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
    val primitives: List<ChemicalDrawPrimitive> = emptyList(),
    val width: Float = DIAGRAM_WIDTH.toFloat(),
    val height: Float = DIAGRAM_HEIGHT.toFloat(),
    val repeatBounds: ChemicalRepeatBounds? = null,
)

@Immutable
data class ChemicalRepeatBounds(
    val top: Float,
    val bottom: Float,
    val leftAtom: androidx.compose.ui.geometry.Offset,
    val rightAtom: androidx.compose.ui.geometry.Offset,
)

@Immutable
sealed interface ChemicalDrawPrimitive {
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: Int,
        val width: Float,
        val dashed: Boolean,
    ) : ChemicalDrawPrimitive

    data class Polygon(
        val points: List<androidx.compose.ui.geometry.Offset>,
        val color: Int,
    ) : ChemicalDrawPrimitive

    data class Circle(
        val x: Float,
        val y: Float,
        val diameter: Float,
        val color: Int,
    ) : ChemicalDrawPrimitive

    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val color: Int,
        val size: Float,
    ) : ChemicalDrawPrimitive
}

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
    val repeatLeft = spec.leftContinuationAtom
    val repeatRight = spec.rightContinuationAtom
    require((repeatLeft == null) == (repeatRight == null))
    repeatLeft?.let { require(it in 0 until molecule.allAtoms) }
    repeatRight?.let { require(it in 0 until molecule.allAtoms) }
    repeatLeft?.let { require(molecule.getAllConnAtoms(it) == 1) }
    repeatRight?.let { require(molecule.getAllConnAtoms(it) == 1) }

    val context = ChemicalDepictContext()
    val depictor = GenericDepictor(molecule, AbstractDepictor.cDModeNoImplicitAtomLabelColors)
    depictor.setFactorTextSize(0.88)
    val marginLeft = if (repeatLeft == null) 16.0 else 40.0
    val marginRight = if (repeatRight == null) 16.0 else 40.0
    val viewWidth = DIAGRAM_WIDTH - marginLeft - marginRight
    depictor.updateCoords(
        context,
        GenericRectangle(marginLeft, 16.0, viewWidth, DIAGRAM_HEIGHT - 32.0),
        AbstractDepictor.cModeInflateToMaxAVBL or 38,
    )
    depictor.paint(context)

    val repeatBounds = repeatLeft?.let { leftAtom ->
        val rightAtom = requireNotNull(repeatRight)
        require(leftAtom != rightAtom)
        val bounds = depictor.boundingRect
        ChemicalRepeatBounds(
            top = bounds.y.toFloat(),
            bottom = (bounds.y + bounds.height).toFloat(),
            leftAtom = androidx.compose.ui.geometry.Offset(
                depictor.getAtomX(leftAtom).toFloat(),
                depictor.getAtomY(leftAtom).toFloat(),
            ),
            rightAtom = androidx.compose.ui.geometry.Offset(
                depictor.getAtomX(rightAtom).toFloat(),
                depictor.getAtomY(rightAtom).toFloat(),
            ),
        )
    }
    return ChemicalMoleculeDiagram(
        label = spec.label,
        atoms = diagramAtoms,
        bonds = diagramBonds,
        primitives = context.primitives.toList(),
        repeatBounds = repeatBounds,
    )
}

private class ChemicalDepictContext : GenericDrawContext {
    val primitives = mutableListOf<ChemicalDrawPrimitive>()
    private var lineWidth = 1f
    private var rgb = 0xFF202124.toInt()
    private var fontSize = 12

    override fun getLineWidth() = lineWidth
    override fun setLineWidth(lineWidth: Float) { this.lineWidth = lineWidth }
    override fun getRGB() = rgb
    override fun setRGB(rgb: Int) { this.rgb = rgb }
    override fun getFontSize() = fontSize
    override fun setFont(size: Int, isBold: Boolean, isItalic: Boolean) { fontSize = size.coerceAtLeast(1) }
    override fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double) {
        primitives += ChemicalDrawPrimitive.Line(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), rgb, lineWidth, false,
        )
    }
    override fun drawDottedLine(x1: Double, y1: Double, x2: Double, y2: Double) {
        primitives += ChemicalDrawPrimitive.Line(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), rgb, lineWidth, true,
        )
    }
    override fun drawRectangle(x: Double, y: Double, w: Double, h: Double) {
        drawLine(x, y, x + w, y)
        drawLine(x + w, y, x + w, y + h)
        drawLine(x + w, y + h, x, y + h)
        drawLine(x, y + h, x, y)
    }
    override fun fillRectangle(x: Double, y: Double, w: Double, h: Double) = Unit
    override fun drawCircle(x: Double, y: Double, d: Double) = Unit
    override fun fillCircle(x: Double, y: Double, d: Double) {
        primitives += ChemicalDrawPrimitive.Circle((x + d / 2.0).toFloat(), (y + d / 2.0).toFloat(), d.toFloat(), rgb)
    }
    override fun drawPolygon(p: GenericPolygon) = polygon(p)
    override fun fillPolygon(p: GenericPolygon) = polygon(p)
    private fun polygon(p: GenericPolygon) {
        primitives += ChemicalDrawPrimitive.Polygon(
            (0 until p.size).map { androidx.compose.ui.geometry.Offset(p.getX(it).toFloat(), p.getY(it).toFloat()) },
            rgb,
        )
    }
    override fun drawString(x: Double, y: Double, s: String) {
        primitives += ChemicalDrawPrimitive.Text(x.toFloat(), y.toFloat(), s, rgb, fontSize.toFloat())
    }
    override fun drawCenteredString(x: Double, y: Double, s: String) = drawString(x, y, s)
    override fun drawImage(image: GenericImage, x: Double, y: Double) = Unit
    override fun drawImage(
        image: GenericImage,
        sx: Double,
        sy: Double,
        dx: Double,
        dy: Double,
        w: Double,
        h: Double,
    ) = Unit
    override fun drawImage(
        image: GenericImage,
        sx: Double,
        sy: Double,
        sw: Double,
        sh: Double,
        dx: Double,
        dy: Double,
        dw: Double,
        dh: Double,
    ) = Unit
    override fun getBounds(s: String) = GenericRectangle(
        0.0,
        -fontSize * 0.8,
        s.length * fontSize * 0.62,
        fontSize.toDouble(),
    )
    override fun isDarkBackground() = false
    override fun getForegroundRGB() = 0xFF202124.toInt()
    override fun getBackgroundRGB() = 0xFFFFFFFF.toInt()
    override fun getSelectionBackgroundRGB() = 0xFFCCDDFF.toInt()
    override fun createARGBImage(width: Int, height: Int): GenericImage = object : GenericImage {
        private val pixels = IntArray(width * height)
        override fun scale(width: Int, height: Int) = Unit
        override fun get(): Any = pixels
        override fun getWidth() = width
        override fun getHeight() = height
        override fun getRGB(x: Int, y: Int) = pixels[y * width + x]
        override fun setRGB(x: Int, y: Int, rgb: Int) { pixels[y * width + x] = rgb }
    }
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

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun integer(element: JsonElement): Int? =
    (element as? JsonPrimitive)?.intOrNull
