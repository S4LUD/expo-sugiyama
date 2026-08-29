package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 6 tests (spec A.11): rank rows, per-rank max height handling,
 * custom margins/ranksep, and the rank-Y lookup.
 */
class YCoordinateAssignerTest {
  private val RANKSEP = 80.0
  private val MARGINY = 40.0

  private fun positionsOf(
    g: LayoutGraph,
    ranksep: Double = RANKSEP,
    marginy: Double = MARGINY,
  ): Map<String, Double> {
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)
    return YCoordinateAssigner.assignYCoordinates(manager, allocation, ranksep, marginy)
  }

  @Test
  fun rankRowsAdvanceByMaxHeightPlusRanksep() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val positions = positionsOf(g)

    assertEquals(40.0, positions.getValue("n0"), 0.0)
    assertEquals(184.0, positions.getValue("n1"), 0.0) // 40 + 64 + 80
    assertEquals(328.0, positions.getValue("n2"), 0.0) // 184 + 64 + 80
  }

  @Test
  fun maxHeightPerRankDrivesSpacing() {
    val g = LayoutGraph.build(
      listOf(node("n0", height = 100.0), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    // The pipeline uses explicit dimensions when provided (custom node dims)
    val allocation = WidthAllocator.allocateWidths(
      g,
      manager,
      nodeDimensions = mapOf("n0" to NodeDimensions(150.0, 100.0)),
    )
    val positions = YCoordinateAssigner.assignYCoordinates(manager, allocation, RANKSEP, MARGINY)

    // rank 0 max height = 100 -> rank 1 at 40 + 100 + 80 = 220
    assertEquals(220.0, positions.getValue("n1"), 0.0)
    assertEquals(364.0, positions.getValue("n2"), 0.0) // 220 + 64 + 80
  }

  @Test
  fun customRanksepAndMarginyAreHonored() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n1")),
    )
    val positions = positionsOf(g, ranksep = 50.0, marginy = 0.0)

    assertEquals(0.0, positions.getValue("n0"), 0.0)
    assertEquals(114.0, positions.getValue("n1"), 0.0) // 0 + 64 + 50
  }

  @Test
  fun rankYLookupMatchesAssignedRows() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)
    val assigned = YCoordinateAssigner.assignYCoordinates(manager, allocation, RANKSEP, MARGINY)

    for (rank in manager.sortedRanks) {
      val expected = YCoordinateAssigner.getRankYCoordinate(manager, rank, allocation, RANKSEP, MARGINY)
      for (nodeId in LayerManager.getLayerNodes(manager, rank)) {
        assertEquals(expected, assigned.getValue(nodeId), 0.0)
      }
    }
  }

  @Test
  fun layoutHeightAccumulatesAllRanks() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val manager = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val allocation = WidthAllocator.allocateWidths(g, manager)

    // 40 + (64+80) + (64+80) + (64+80) - 80 + 40 = 432
    assertEquals(432.0, YCoordinateAssigner.computeLayoutHeight(manager, allocation), 0.0)
  }
}