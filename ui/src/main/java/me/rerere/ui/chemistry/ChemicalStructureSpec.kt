package me.rerere.ui.chemistry

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import com.actelion.research.chem.AbstractDepictor
import com.actelion.research.chem.IsomericSmilesCreator
import com.actelion.research.chem.Molecule
import com.actelion.research.chem.MolfileCreator
import com.actelion.research.chem.MolfileParser
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
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val MAX_SPEC_LENGTH = 8192
private const val MAX_SMILES_LENGTH = 512
private const val MAX_ATOMS = 80
private const val MAX_MOLECULES_PER_SIDE = 4
private const val DEPICT_VIEW_WIDTH = 960.0
private const val DEPICT_VIEW_HEIGHT = 720.0
private const val DEPICT_MARGIN = 20.0
private const val EMPTY_FALLBACK_WIDTH = 240f
private const val EMPTY_FALLBACK_HEIGHT = 160f
private const val TARGET_MAX_AVBL = 30
private const val DIAGRAM_PADDING = 14f
private const val MIN_DIAGRAM_SIZE = 48f
private const val POLYMER_BRACKET_OFFSET_X = 22f
private const val POLYMER_BRACKET_PAD_Y = 4f
private const val POLYMER_LABEL_MARGIN_X = 20f
private const val POLYMER_LABEL_MARGIN_Y = 4f
private const val CLEAN_LAYOUT_PENALTY_THRESHOLD = 0.5
private const val BRIDGED_CROSSING_PENALTY_THRESHOLD = 50.0
private const val MAX_BRIDGED_PERMUTATION_ATOMS = 40
private const val MAX_BFS_CANDIDATES = 16
private const val STEREO_MISMATCH_GUARD_EPS = 1e-6

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
    val width: Float = EMPTY_FALLBACK_WIDTH,
    val height: Float = EMPTY_FALLBACK_HEIGHT,
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
    val parsedMolecule = StereoMolecule()
    SmilesParser().parse(parsedMolecule, spec.smiles.toByteArray(StandardCharsets.UTF_8), false, true)
    require(parsedMolecule.allAtoms in 1..MAX_ATOMS)
    val repeatLeft = spec.leftContinuationAtom
    val repeatRight = spec.rightContinuationAtom
    require((repeatLeft == null) == (repeatRight == null))
    repeatLeft?.let { require(it in 0 until parsedMolecule.allAtoms) }
    repeatRight?.let { require(it in 0 until parsedMolecule.allAtoms) }
    repeatLeft?.let { require(parsedMolecule.getAllConnAtoms(it) == 1) }
    repeatRight?.let { require(parsedMolecule.getAllConnAtoms(it) == 1) }

    val molecule = arrangeReadableCoordinates(parsedMolecule, spec.smiles, isPolymer = repeatLeft != null)
    // 重置为中性色并清理显式原子染色，避免外部输入带入杂色
    molecule.setMoleculeColor(Molecule.cMoleculeColorNeutral)
    molecule.removeAtomColors()
    molecule.ensureHelperArrays(Molecule.cHelperNeighbours)

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

    val context = ChemicalDepictContext()
    // 必须包含 cDModeNoStereoProblem：否则未标 @/@@ 或顺反构型的立体中心/双键会被 OpenChemLib 标成洋红色 (0xFFC000FF)
    val displayMode = AbstractDepictor.cDModeNoImplicitAtomLabelColors or
        AbstractDepictor.cDModeSuppressCIPParity or
        AbstractDepictor.cDModeSuppressESR or
        AbstractDepictor.cDModeSuppressChiralText or
        AbstractDepictor.cDModeNoColorOnESRAndCIP or
        AbstractDepictor.cDModeNoStereoProblem
    val depictor = GenericDepictor(molecule, displayMode)
    depictor.setFactorTextSize(0.84)
    val marginLeft = if (repeatLeft == null) DEPICT_MARGIN else 44.0
    val marginRight = if (repeatRight == null) DEPICT_MARGIN else 54.0
    val viewWidth = DEPICT_VIEW_WIDTH - marginLeft - marginRight
    depictor.updateCoords(
        context,
        GenericRectangle(marginLeft, DEPICT_MARGIN, viewWidth, DEPICT_VIEW_HEIGHT - DEPICT_MARGIN * 2.0),
        AbstractDepictor.cModeInflateToMaxAVBL or TARGET_MAX_AVBL,
    )
    depictor.paint(context)

    val rawRepeatBounds = repeatLeft?.let { leftAtom ->
        val rightAtom = requireNotNull(repeatRight)
        require(leftAtom != rightAtom)
        val bounds = depictor.boundingRect
        ChemicalRepeatBounds(
            top = bounds.y.toFloat(),
            bottom = (bounds.y + bounds.height).toFloat(),
            leftAtom = Offset(
                depictor.getAtomX(leftAtom).toFloat(),
                depictor.getAtomY(leftAtom).toFloat(),
            ),
            rightAtom = Offset(
                depictor.getAtomX(rightAtom).toFloat(),
                depictor.getAtomY(rightAtom).toFloat(),
            ),
        )
    }
    val fitted = fitDiagramToContentBounds(context.primitives, rawRepeatBounds)
    return ChemicalMoleculeDiagram(
        label = spec.label,
        atoms = diagramAtoms,
        bonds = diagramBonds,
        primitives = fitted.primitives,
        width = fitted.width,
        height = fitted.height,
        repeatBounds = fitted.repeatBounds,
    )
}

/**
 * 在 OpenChemLib 默认坐标存在键交叉或支链内折重叠（如大环肽类、桥环笼状分子）时，
 * 自动通过非环单键支链翻转/旋转及 BFS 拓扑遍历搜寻无交叉布局，并在提交前以 Molfile 往返验证手性与顺反构型完全一致。
 */
private fun arrangeReadableCoordinates(
    orig: StereoMolecule,
    smiles: String,
    isPolymer: Boolean,
): StereoMolecule {
    orig.ensureHelperArrays(Molecule.cHelperParities)
    CoordinateInventor().apply { setRandomSeed(0L) }.invent(orig)
    val pen0 = layoutPenalty(orig)
    if (pen0 < CLEAN_LAYOUT_PENALTY_THRESHOLD) {
        if (!isPolymer && orig.allAtoms >= 24) {
            orientHorizontalIfTall(orig, aspectThreshold = 1.35)
        }
        return orig
    }

    val targetStereo = IsomericSmilesCreator(orig).getSmiles()
    var best = orig
    var bestPen = pen0

    val initialOpt = orig.cloneMolecule()
    initialOpt.ensureHelperArrays(Molecule.cHelperParities)
    if (optimizeExocyclicBranches(initialOpt)) {
        val optPen = layoutPenalty(initialOpt)
        if (optPen < bestPen - 0.1 && verifyStereoPreserved(initialOpt, targetStereo)) {
            best = initialOpt
            bestPen = optPen
        }
    }

    if (!isPolymer && bestPen >= CLEAN_LAYOUT_PENALTY_THRESHOLD) {
        val inputs = if (targetStereo != smiles) listOf(smiles, targetStereo) else listOf(smiles)
        for (candidateSmiles in inputs) {
            for (seed in longArrayOf(0L, 1L, 2L)) {
                if (candidateSmiles == smiles && seed == 0L) continue
                val cand = StereoMolecule()
                SmilesParser().parse(cand, candidateSmiles.toByteArray(StandardCharsets.UTF_8), false, true)
                cand.ensureHelperArrays(Molecule.cHelperParities)
                CoordinateInventor().apply { setRandomSeed(seed) }.invent(cand)
                optimizeExocyclicBranches(cand)
                val candPen = layoutPenalty(cand)
                if (candPen < bestPen - 0.1 && verifyStereoPreserved(cand, targetStereo)) {
                    best = cand
                    bestPen = candPen
                }
                if (bestPen < CLEAN_LAYOUT_PENALTY_THRESHOLD) break
            }
            if (bestPen < CLEAN_LAYOUT_PENALTY_THRESHOLD) break
        }
    }

    // 针对仍存在环桥键交叉的多环/桥环（如青蒿素、樟脑等），按不同起始原子 BFS 顺序重排并择优
    if (!isPolymer && bestPen >= BRIDGED_CROSSING_PENALTY_THRESHOLD && orig.allAtoms <= MAX_BRIDGED_PERMUTATION_ATOMS) {
        val molfileBase = runCatching {
            val base = StereoMolecule()
            MolfileParser().parse(base, MolfileCreator(orig).getMolfile())
            base.ensureHelperArrays(Molecule.cHelperNeighbours)
            base
        }.getOrNull()
        if (molfileBase != null) {
            val atomCount = molfileBase.allAtoms
            val step = max(1, atomCount / MAX_BFS_CANDIDATES)
            var tries = 0
            var start = 0
            while (start < atomCount && tries < MAX_BFS_CANDIDATES && bestPen >= CLEAN_LAYOUT_PENALTY_THRESHOLD) {
                tries++
                val cand = inventWithBfsOrder(molfileBase, start)
                cand.ensureHelperArrays(Molecule.cHelperParities)
                optimizeExocyclicBranches(cand)
                val candPen = layoutPenalty(cand)
                if (candPen < bestPen - 0.1 && verifyStereoPreserved(cand, targetStereo)) {
                    best = cand
                    bestPen = candPen
                }
                start += step
            }
        }
    }

    if (!isPolymer && best.allAtoms >= 24) {
        orientHorizontalIfTall(best, aspectThreshold = 1.12)
    }
    return best
}

private fun StereoMolecule.cloneMolecule(): StereoMolecule =
    StereoMolecule(allAtoms, allBonds).also { copyMolecule(it) }

private fun verifyStereoPreserved(mol: StereoMolecule, targetStereo: String): Boolean = runCatching {
    val molfile = MolfileCreator(mol).getMolfile()
    val check = StereoMolecule()
    MolfileParser().parse(check, molfile)
    IsomericSmilesCreator(check).getSmiles() == targetStereo
}.getOrDefault(false)

private fun inventWithBfsOrder(base: StereoMolecule, startAtom: Int): StereoMolecule {
    val atomCount = base.allAtoms
    val visited = BooleanArray(atomCount)
    val oldToNew = IntArray(atomCount)
    val order = ArrayList<Int>(atomCount)
    val queue = ArrayDeque<Int>()
    fun enqueueComponent(root: Int) {
        queue.add(root)
        visited[root] = true
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            order += u
            for (k in 0 until base.getConnAtoms(u)) {
                val v = base.getConnAtom(u, k)
                if (!visited[v]) {
                    visited[v] = true
                    queue.add(v)
                }
            }
        }
    }
    enqueueComponent(startAtom)
    for (i in 0 until atomCount) {
        if (!visited[i]) enqueueComponent(i)
    }

    val permuted = StereoMolecule(atomCount, base.allBonds)
    order.forEach { oldIdx ->
        val newIdx = permuted.addAtom(base.getAtomicNo(oldIdx))
        permuted.setAtomCharge(newIdx, base.getAtomCharge(oldIdx))
        permuted.setAtomMass(newIdx, base.getAtomMass(oldIdx))
        permuted.setAtomRadical(newIdx, base.getAtomRadical(oldIdx))
        oldToNew[oldIdx] = newIdx
    }
    for (b in 0 until base.allBonds) {
        permuted.addBond(
            oldToNew[base.getBondAtom(0, b)],
            oldToNew[base.getBondAtom(1, b)],
            base.getBondType(b),
        )
    }
    for (oldIdx in 0 until atomCount) {
        permuted.setAtomX(oldToNew[oldIdx], base.getAtomX(oldIdx))
        permuted.setAtomY(oldToNew[oldIdx], base.getAtomY(oldIdx))
    }
    permuted.ensureHelperArrays(Molecule.cHelperParities)
    CoordinateInventor().apply { setRandomSeed(0L) }.invent(permuted)
    return permuted
}

private fun orientHorizontalIfTall(mol: StereoMolecule, aspectThreshold: Double) {
    val atomCount = mol.allAtoms
    if (atomCount < 3) return
    var meanX = 0.0
    var meanY = 0.0
    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (i in 0 until atomCount) {
        val x = mol.getAtomX(i)
        val y = mol.getAtomY(i)
        meanX += x
        meanY += y
        minX = min(minX, x)
        maxX = max(maxX, x)
        minY = min(minY, y)
        maxY = max(maxY, y)
    }
    meanX /= atomCount
    meanY /= atomCount
    if ((maxY - minY) > (maxX - minX) * aspectThreshold) {
        for (i in 0 until atomCount) {
            val dx = mol.getAtomX(i) - meanX
            val dy = mol.getAtomY(i) - meanY
            mol.setAtomX(i, meanX - dy)
            mol.setAtomY(i, meanY + dx)
        }
    }
}

private data class ExocyclicBranch(
    val anchorAtom: Int,
    val tipAtom: Int,
    val subtreeAtoms: IntArray,
    val stereoProtectedAnchor: Boolean,
)

private fun optimizeExocyclicBranches(mol: StereoMolecule): Boolean {
    if (layoutPenalty(mol) < CLEAN_LAYOUT_PENALTY_THRESHOLD) return false
    val atomCount = mol.allAtoms
    val bondCount = mol.allBonds
    val branches = mutableListOf<ExocyclicBranch>()
    for (b in 0 until bondCount) {
        if (mol.isRingBond(b) || mol.getBondOrder(b) != 1) continue
        val u = mol.getBondAtom(0, b)
        val v = mol.getBondAtom(1, b)
        val compV = findBridgeSubtree(mol, rootAtom = u, startAtom = v) ?: continue
        val (anchor, tip, comp) = if (compV.size <= atomCount - compV.size) {
            Triple(u, v, compV)
        } else {
            val compU = findBridgeSubtree(mol, rootAtom = v, startAtom = u) ?: continue
            Triple(v, u, compU)
        }
        val protectedAnchor = mol.getAtomParity(anchor) != Molecule.cAtomParityNone || hasStereoDoubleBond(mol, anchor)
        branches += ExocyclicBranch(anchor, tip, comp, protectedAnchor)
    }
    if (branches.isEmpty()) return false
    branches.sortByDescending { it.subtreeAtoms.size }

    var changed = false
    var best = layoutPenalty(mol)
    // 第一轮：沿非环单键轴做 180° 镜像翻转（保持原有键角比例，同步翻转子树内部楔形键保持构型）
    for (pass in 0 until 4) {
        if (best < CLEAN_LAYOUT_PENALTY_THRESHOLD) break
        var improved = false
        for (branch in branches) {
            if (branch.subtreeAtoms.size <= 1 && mol.getAtomicNo(branch.subtreeAtoms[0]) == 6) continue
            val ux = mol.getAtomX(branch.anchorAtom)
            val uy = mol.getAtomY(branch.anchorAtom)
            val vx = mol.getAtomX(branch.tipAtom)
            val vy = mol.getAtomY(branch.tipAtom)
            reflectAcrossAxis(mol, branch.subtreeAtoms, ux, uy, vx, vy)
            val cand = layoutPenalty(mol)
            if (cand < best - 0.1) {
                toggleWedgesWithinSet(mol, branch.subtreeAtoms.toSet())
                best = cand
                improved = true
                changed = true
            } else {
                reflectAcrossAxis(mol, branch.subtreeAtoms, ux, uy, vx, vy)
            }
        }
        if (!improved) break
    }

    // 第二轮：若大环内部仍有重排碰撞，围绕非手性且非双键顺反锚点做支链角度外摆
    if (best > 5.0) {
        val candidateAnglesDeg = doubleArrayOf(30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 120.0, -120.0, 150.0, -150.0, 180.0)
        for (pass in 0 until 4) {
            if (best < 2.0) break
            var improved = false
            for (branch in branches) {
                if (branch.stereoProtectedAnchor) continue
                val ux = mol.getAtomX(branch.anchorAtom)
                val uy = mol.getAtomY(branch.anchorAtom)
                var bestAngleRad = 0.0
                var bestLocal = best
                for (deg in candidateAnglesDeg) {
                    val rad = Math.toRadians(deg)
                    rotateAtomsAround(mol, branch.subtreeAtoms, ux, uy, rad)
                    val cand = layoutPenalty(mol)
                    if (cand < bestLocal - 0.5) {
                        bestLocal = cand
                        bestAngleRad = rad
                    }
                    rotateAtomsAround(mol, branch.subtreeAtoms, ux, uy, -rad)
                }
                if (bestAngleRad != 0.0) {
                    rotateAtomsAround(mol, branch.subtreeAtoms, ux, uy, bestAngleRad)
                    best = bestLocal
                    improved = true
                    changed = true
                }
            }
            if (!improved) break
        }
    }
    return changed
}

private fun hasStereoDoubleBond(mol: StereoMolecule, atom: Int): Boolean {
    for (k in 0 until mol.getConnAtoms(atom)) {
        val b = mol.getConnBond(atom, k)
        if (mol.getBondOrder(b) == 2 && mol.getBondParity(b) != Molecule.cBondParityNone) return true
    }
    return false
}

private fun findBridgeSubtree(mol: StereoMolecule, rootAtom: Int, startAtom: Int): IntArray? {
    val atomCount = mol.allAtoms
    val visited = BooleanArray(atomCount)
    visited[rootAtom] = true
    visited[startAtom] = true
    val result = ArrayList<Int>(atomCount)
    val queue = ArrayDeque<Int>()
    queue.add(startAtom)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        result += cur
        for (k in 0 until mol.getConnAtoms(cur)) {
            val nxt = mol.getConnAtom(cur, k)
            if (nxt == rootAtom && cur == startAtom) continue
            if (nxt == rootAtom) return null
            if (!visited[nxt]) {
                visited[nxt] = true
                queue.add(nxt)
            }
        }
    }
    return result.toIntArray()
}

private fun reflectAcrossAxis(mol: StereoMolecule, atoms: IntArray, x1: Double, y1: Double, x2: Double, y2: Double) {
    val dx = x2 - x1
    val dy = y2 - y1
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-9) return
    val invLen = 1.0 / sqrt(len2)
    val ux = dx * invLen
    val uy = dy * invLen
    for (atom in atoms) {
        val px = mol.getAtomX(atom) - x1
        val py = mol.getAtomY(atom) - y1
        val dot = px * ux + py * uy
        mol.setAtomX(atom, x1 + 2.0 * dot * ux - px)
        mol.setAtomY(atom, y1 + 2.0 * dot * uy - py)
    }
}

private fun rotateAtomsAround(mol: StereoMolecule, atoms: IntArray, cx: Double, cy: Double, radians: Double) {
    val c = cos(radians)
    val s = sin(radians)
    for (atom in atoms) {
        val dx = mol.getAtomX(atom) - cx
        val dy = mol.getAtomY(atom) - cy
        mol.setAtomX(atom, cx + dx * c - dy * s)
        mol.setAtomY(atom, cy + dx * s + dy * c)
    }
}

private fun toggleWedgesWithinSet(mol: StereoMolecule, atoms: Set<Int>) {
    val upBits = Molecule.cBondTypeUp and Molecule.cBondTypeMaskStereo
    val downBits = Molecule.cBondTypeDown and Molecule.cBondTypeMaskStereo
    for (b in 0 until mol.allBonds) {
        val u = mol.getBondAtom(0, b)
        val v = mol.getBondAtom(1, b)
        if (u in atoms && v in atoms) {
            val bondType = mol.getBondType(b)
            when (bondType and Molecule.cBondTypeMaskStereo) {
                upBits -> mol.setBondType(b, (bondType and Molecule.cBondTypeMaskStereo.inv()) or Molecule.cBondTypeDown)
                downBits -> mol.setBondType(b, (bondType and Molecule.cBondTypeMaskStereo.inv()) or Molecule.cBondTypeUp)
            }
        }
    }
}

private fun layoutPenalty(mol: StereoMolecule): Double {
    val na = mol.allAtoms
    val nb = mol.allBonds
    val xs = DoubleArray(na) { mol.getAtomX(it) }
    val ys = DoubleArray(na) { mol.getAtomY(it) }
    val bonded = BooleanArray(na * na)
    val b0 = IntArray(nb)
    val b1 = IntArray(nb)
    for (b in 0 until nb) {
        val u = mol.getBondAtom(0, b)
        val v = mol.getBondAtom(1, b)
        b0[b] = u
        b1[b] = v
        bonded[u * na + v] = true
        bonded[v * na + u] = true
    }

    var penalty = 0.0
    for (i in 0 until nb) {
        val a1 = b0[i]
        val a2 = b1[i]
        val ax = xs[a1]
        val ay = ys[a1]
        val bx = xs[a2]
        val by = ys[a2]
        for (j in i + 1 until nb) {
            val c1 = b0[j]
            val c2 = b1[j]
            if (a1 == c1 || a1 == c2 || a2 == c1 || a2 == c2) continue
            if (segmentsIntersect(ax, ay, bx, by, xs[c1], ys[c1], xs[c2], ys[c2])) {
                penalty += 150.0
            }
        }
    }
    for (i in 0 until na) {
        val xi = xs[i]
        val yi = ys[i]
        val row = i * na
        for (j in i + 1 until na) {
            if (bonded[row + j]) continue
            val d = hypot(xi - xs[j], yi - ys[j])
            if (d < 0.78) {
                val diff = 0.78 - d
                penalty += diff * diff * 100.0
            }
        }
    }
    for (a in 0 until na) {
        val ax = xs[a]
        val ay = ys[a]
        val req = if (mol.getAtomicNo(a) != 6) 0.56 else 0.44
        for (b in 0 until nb) {
            val u = b0[b]
            val v = b1[b]
            if (a == u || a == v) continue
            val d = pointToSegmentDistance(ax, ay, xs[u], ys[u], xs[v], ys[v])
            if (d < req) {
                val diff = req - d
                penalty += diff * diff * 90.0
            }
        }
    }
    return penalty
}

private fun segmentsIntersect(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    dx: Double,
    dy: Double,
): Boolean {
    if (max(ax, bx) < min(cx, dx) || max(cx, dx) < min(ax, bx)) return false
    if (max(ay, by) < min(cy, dy) || max(cy, dy) < min(ay, by)) return false
    val d1 = crossProduct(cx, cy, dx, dy, ax, ay)
    val d2 = crossProduct(cx, cy, dx, dy, bx, by)
    val d3 = crossProduct(ax, ay, bx, by, cx, cy)
    val d4 = crossProduct(ax, ay, bx, by, dx, dy)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
        ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    ) {
        return true
    }
    return (abs(d1) < STEREO_MISMATCH_GUARD_EPS && isOnSegment(cx, cy, dx, dy, ax, ay)) ||
        (abs(d2) < STEREO_MISMATCH_GUARD_EPS && isOnSegment(cx, cy, dx, dy, bx, by)) ||
        (abs(d3) < STEREO_MISMATCH_GUARD_EPS && isOnSegment(ax, ay, bx, by, cx, cy)) ||
        (abs(d4) < STEREO_MISMATCH_GUARD_EPS && isOnSegment(ax, ay, bx, by, dx, dy))
}

private fun crossProduct(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
    (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

private fun isOnSegment(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Boolean =
    px >= min(ax, bx) - STEREO_MISMATCH_GUARD_EPS &&
        px <= max(ax, bx) + STEREO_MISMATCH_GUARD_EPS &&
        py >= min(ay, by) - STEREO_MISMATCH_GUARD_EPS &&
        py <= max(ay, by) + STEREO_MISMATCH_GUARD_EPS

private fun pointToSegmentDistance(
    px: Double,
    py: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-9) return hypot(px - x1, py - y1)
    val t = ((px - x1) * dx + (py - y1) * dy) / len2
    val clamped = t.coerceIn(0.0, 1.0)
    return hypot(px - (x1 + clamped * dx), py - (y1 + clamped * dy))
}

private data class FittedDiagramLayout(
    val primitives: List<ChemicalDrawPrimitive>,
    val width: Float,
    val height: Float,
    val repeatBounds: ChemicalRepeatBounds?,
)

/**
 * 将 OpenChemLib 生成的图元裁剪平移到紧凑包络盒，避免小分子在固定 360×240 画布中占比较小、四周留白过多。
 */
private fun fitDiagramToContentBounds(
    primitives: List<ChemicalDrawPrimitive>,
    repeatBounds: ChemicalRepeatBounds?,
): FittedDiagramLayout {
    if (primitives.isEmpty()) {
        return FittedDiagramLayout(
            primitives = primitives,
            width = EMPTY_FALLBACK_WIDTH,
            height = EMPTY_FALLBACK_HEIGHT,
            repeatBounds = repeatBounds,
        )
    }
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    fun include(left: Float, top: Float, right: Float, bottom: Float) {
        minX = min(minX, left)
        minY = min(minY, top)
        maxX = max(maxX, right)
        maxY = max(maxY, bottom)
    }

    primitives.forEach { primitive ->
        when (primitive) {
            is ChemicalDrawPrimitive.Line -> {
                val halfStroke = primitive.width * 0.5f
                include(
                    min(primitive.x1, primitive.x2) - halfStroke,
                    min(primitive.y1, primitive.y2) - halfStroke,
                    max(primitive.x1, primitive.x2) + halfStroke,
                    max(primitive.y1, primitive.y2) + halfStroke,
                )
            }

            is ChemicalDrawPrimitive.Polygon -> primitive.points.forEach { point ->
                include(point.x, point.y, point.x, point.y)
            }

            is ChemicalDrawPrimitive.Circle -> {
                val radius = primitive.diameter * 0.5f
                include(
                    primitive.x - radius,
                    primitive.y - radius,
                    primitive.x + radius,
                    primitive.y + radius,
                )
            }

            is ChemicalDrawPrimitive.Text -> {
                val halfWidth = (estimateTextWidth(primitive.text, primitive.size.toInt()).toFloat() * 0.5f)
                    .coerceAtLeast(primitive.size * 0.32f)
                val halfHeight = primitive.size * 0.62f
                include(
                    primitive.x - halfWidth,
                    primitive.y - halfHeight,
                    primitive.x + halfWidth,
                    primitive.y + halfHeight,
                )
            }
        }
    }

    // 聚合物的中括号和角标会在分子左右外侧延展，包络盒需一并计入以免贴边裁剪
    repeatBounds?.let { bounds ->
        val leftX = min(bounds.leftAtom.x, bounds.rightAtom.x) - POLYMER_BRACKET_OFFSET_X
        val rightX = max(bounds.leftAtom.x, bounds.rightAtom.x) + POLYMER_BRACKET_OFFSET_X
        include(
            leftX - 2f,
            bounds.top - POLYMER_BRACKET_PAD_Y - 2f,
            rightX + POLYMER_LABEL_MARGIN_X,
            bounds.bottom + POLYMER_BRACKET_PAD_Y + POLYMER_LABEL_MARGIN_Y,
        )
    }

    if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
        return FittedDiagramLayout(
            primitives = primitives,
            width = EMPTY_FALLBACK_WIDTH,
            height = EMPTY_FALLBACK_HEIGHT,
            repeatBounds = repeatBounds,
        )
    }

    val contentWidth = (maxX - minX).coerceAtLeast(1f)
    val contentHeight = (maxY - minY).coerceAtLeast(1f)
    val width = max(contentWidth + DIAGRAM_PADDING * 2f, MIN_DIAGRAM_SIZE)
    val height = max(contentHeight + DIAGRAM_PADDING * 2f, MIN_DIAGRAM_SIZE)
    val shiftX = (width - contentWidth) * 0.5f - minX
    val shiftY = (height - contentHeight) * 0.5f - minY

    val shiftedPrimitives = primitives.map { primitive ->
        when (primitive) {
            is ChemicalDrawPrimitive.Line -> primitive.copy(
                x1 = primitive.x1 + shiftX,
                y1 = primitive.y1 + shiftY,
                x2 = primitive.x2 + shiftX,
                y2 = primitive.y2 + shiftY,
            )

            is ChemicalDrawPrimitive.Polygon -> primitive.copy(
                points = primitive.points.map { Offset(it.x + shiftX, it.y + shiftY) },
            )

            is ChemicalDrawPrimitive.Circle -> primitive.copy(
                x = primitive.x + shiftX,
                y = primitive.y + shiftY,
            )

            is ChemicalDrawPrimitive.Text -> primitive.copy(
                x = primitive.x + shiftX,
                y = primitive.y + shiftY,
            )
        }
    }

    val shiftedRepeatBounds = repeatBounds?.let { bounds ->
        ChemicalRepeatBounds(
            top = bounds.top + shiftY,
            bottom = bounds.bottom + shiftY,
            leftAtom = Offset(bounds.leftAtom.x + shiftX, bounds.leftAtom.y + shiftY),
            rightAtom = Offset(bounds.rightAtom.x + shiftX, bounds.rightAtom.y + shiftY),
        )
    }

    return FittedDiagramLayout(
        primitives = shiftedPrimitives,
        width = width,
        height = height,
        repeatBounds = shiftedRepeatBounds,
    )
}

private class ChemicalDepictContext : GenericDrawContext {
    val primitives = mutableListOf<ChemicalDrawPrimitive>()
    private var lineWidth = 1f
    private var rgb = 0xFF000000.toInt()
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
            (0 until p.size).map { Offset(p.getX(it).toFloat(), p.getY(it).toFloat()) },
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
        estimateTextWidth(s, fontSize),
        fontSize.toDouble(),
    )
    override fun isDarkBackground() = false
    override fun getForegroundRGB() = 0xFF000000.toInt()
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

/**
 * 纯 Kotlin 估算常见化学符号在无衬线字体下的宽度，使拆字标签（如 N/H/2）间距更贴近实际绘制宽度。
 */
private fun estimateTextWidth(text: String, fontSize: Int): Double {
    var factorSum = 0.0
    for (ch in text) {
        factorSum += when (ch) {
            'l', 'i', 'I', '1', '.' -> 0.34
            '+', '−', '-', '·' -> 0.48
            'W', 'M', 'w', 'm' -> 0.86
            in '0'..'9', in '\u2080'..'\u2089', in '\u2070'..'\u2079', '\u00B9', '\u00B2', '\u00B3' -> 0.58
            in 'A'..'Z' -> 0.70
            else -> 0.56
        }
    }
    return factorSum * fontSize
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
