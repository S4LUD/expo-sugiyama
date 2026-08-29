package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 tests (spec A.10): parent centering, multi-child spacing,
 * orphan placement, and the no-overlap property.
 */
class XCoordinateAssignerTest {
  private val NODESEP = 60.0
  private val MARGINX = 40.0

  private fun positionsOf(g: LayoutGraph, ranks: Map<String, Int> = LayerAssigner.assignLayers(g)): Map<String, Double> {
    val manager = LayerManager.build(g, ranks)
    val allocation = WidthAllocator.allocateWidths(g, manager)
    return XCoordinateAssigner.assignXCoordinates(g, manager, allocation, NODESEP, MARGINX)
  }

  @Test
  fun singleRootStartsAtMargin() {
    val g = LayoutGraph.build(listOf(node("s")), emptyList())
    val positions = positionsOf(g)

    assertEquals(MARGINX, positions.getValue("s"), 0.0)
  }

  @Test
  fun chainChildCentersUnderParent() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val positions = positionsOf(g)

    assertEquals(40.0, positions.getValue("n0"), 0.0)
    assertEquals(40.0, positions.getValue("n1"), 0.0)
    assertEquals(40.0, positions.getValue("n2"), 0.0)
  }

  @Test
  fun forkRootCentersOverChildren() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b"), edge("e2", "r", "c")),
    )
    val positions = positionsOf(g)

    // subtreeWidth(r) = 150*3 + 2*60 = 570 -> x = 40 + (570-150)/2 = 250
    assertEquals(250.0, positions.getValue("r"), 0.0)
    assertEquals(40.0, positions.getValue("a"), 0.0)
    assertEquals(250.0, positions.getValue("b"), 0.0)
    assertEquals(460.0, positions.getValue("c"), 0.0)
  }

  @Test
  fun parentlessNodeInLowerRankPlacedAtMargin() {
    val g = LayoutGraph.build(
      listOf(node("x"), node("y")),
      emptyList(),
    )
    val forcedRanks = mapOf("x" to 0, "y" to 1)
    val positions = positionsOf(g, forcedRanks)

    assertEquals(MARGINX, positions.getValue("x"), 0.0)
    assertEquals(MARGINX, positions.getValue("y"), 0.0)
  }

  @Test
  fun siblingsNeverOverlapWithinRank() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c"), node("d")),
      listOf(
        edge("e0", "r", "a"),
        edge("e1", "r", "b"),
        edge("e2", "r", "c"),
        edge("e3", "r", "d"),
      ),
    )
    val positions = positionsOf(g)

    val siblings = listOf("a", "b", "c", "d")
    for (i in 1 until siblings.size) {
      val left = positions.getValue(siblings[i - 1])
      val right = positions.getValue(siblings[i])
      assertTrue(right >= left + 150.0)
    }
  }

  @Test
  fun customNodesepAndMarginAreHonored() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager, nodesep = 20.0)
    val positions = XCoordinateAssigner.assignXCoordinates(g, manager, allocation, nodesep = 20.0, marginx = 8.0)

    // subtreeWidth(r) = 150*2 + 20 = 320 -> x = 8 + (320-150)/2 = 93
    assertEquals(8.0, positions.getValue("a"), 0.0)
    assertEquals(93.0, positions.getValue("r"), 0.0)
    assertEquals(178.0, positions.getValue("b"), 0.0)
  }

  @Test
  fun layoutWidthIncludesExtentsPlusMargin() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)

    // widest extent: c-like rightmost child at 250 + 150 = 400 -> 400 + 40
    assertEquals(440.0, XCoordinateAssigner.computeLayoutWidth(g, manager, allocation), 0.0)
  }

  @Test
  fun neverOverlapsNodesInNarrowLayerDag() {
    // The flow-test playground shape: layers of 1, 2, 3, 4, 3, 2, 1
    // nodes where every node points at columns [col, col+1] of the next
    // layer. Diagonal chains make many parents' subtrees wider than any
    // single descendant level, and shared descendants are placed under
    // their first parent only.
    val layerSizes = listOf(1, 2, 3, 4, 3, 2, 1)
    val offsetByLayer = ArrayList<Int>()
    var acc = 0
    for (size in layerSizes) {
      offsetByLayer.add(acc)
      acc += size
    }
    val ids = (1..acc).map { "n$it" }
    val nodes = ids.map { node(it) }
    val edges = ArrayList<Pair<String, String>>()
    layerSizes.forEachIndexed { layer, size ->
      if (layer == layerSizes.size - 1) return@forEachIndexed
      val nextSize = layerSizes[layer + 1]
      for (col in 0 until size) {
        for (targetCol in listOf(col, col + 1)) {
          if (targetCol >= nextSize) continue
          val sourceId = "n${offsetByLayer[layer] + col + 1}"
          val targetId = "n${offsetByLayer[layer + 1] + targetCol + 1}"
          edges.add(sourceId to targetId)
        }
      }
    }
    val g = LayoutGraph.build(
      nodes,
      edges.mapIndexed { i, (s, t) -> edge("e$i", s, t) },
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)
    val positions = XCoordinateAssigner.assignXCoordinates(g, manager, allocation, NODESEP, MARGINX)

    for (rank in manager.sortedRanks) {
      val spans = LayerManager.getLayerNodes(manager, rank)
        .map { id ->
          val x = positions.getValue(id)
          x - 75.0 to x + 75.0
        }
        .sortedBy { it.first }
      for (i in 1 until spans.size) {
        assertTrue(spans[i].first >= spans[i - 1].second)
      }
    }
  }

  @Test
  fun overlappingDagChildrenScaleIntoParentSlot() {
    // r -> a, r -> b; a -> c, a -> d; b -> d, b -> e (d is shared).
    // Children of r span 360 + 60 + 360 = 780 but r's subtree width is
    // 570 (widest level), so children are fitted into the 570 slot.
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c"), node("d"), node("e")),
      listOf(
        edge("e0", "r", "a"),
        edge("e1", "r", "b"),
        edge("e2", "a", "c"),
        edge("e3", "a", "d"),
        edge("e4", "b", "d"),
        edge("e5", "b", "e"),
      ),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)
    val positions = XCoordinateAssigner.assignXCoordinates(g, manager, allocation, NODESEP, MARGINX)

    // All positions stay within the root slot [0, margin + width].
    val rootWidth = allocation.subtreeWidths.getValue("r")
    for (x in positions.values) {
      assertTrue(x >= 0.0)
      assertTrue(x + 150.0 <= MARGINX + rootWidth + 0.001)
    }
    // a and b are placed inside r's slot, not at the unscaled 780 span.
    val xA = positions.getValue("a")
    val xB = positions.getValue("b")
    assertTrue(xB - xA < 780.0)
  }
}