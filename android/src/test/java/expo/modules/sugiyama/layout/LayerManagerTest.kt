package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Layer manager tests (spec A.12): grouping by rank, sorted ranks,
 * deterministic initial ordering, and ordering updates.
 */
class LayerManagerTest {
  private fun ranksOf(g: LayoutGraph) = LayerAssigner.assignLayers(g)

  @Test
  fun buildsOrderedLayerNodesSortedById() {
    val g = LayoutGraph.build(
      listOf(node("b"), node("d"), node("a"), node("c"), node("x")),
      listOf(
        edge("e0", "b", "x"),
        edge("e1", "d", "x"),
        edge("e2", "a", "x"),
        edge("e3", "c", "x"),
      ),
    )
    val manager = LayerManager.build(g, ranksOf(g))

    assertEquals(listOf("a", "b", "c", "d"), manager.layers.getValue(0).nodes)
    assertEquals(listOf("x"), manager.layers.getValue(1).nodes)
    assertEquals(2, manager.ordering["c"])
    assertEquals(3, manager.ordering["d"])
  }

  @Test
  fun computesSortedRanksAndMaxRank() {
    val g = LayoutGraph.build(
      listOf(node("n0"), node("n1"), node("n2"), node("n3")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n3"), edge("e2", "n2", "n3")),
    )
    val manager = LayerManager.build(g, ranksOf(g))

    assertEquals(listOf(0, 1, 2), manager.sortedRanks)
    assertEquals(2, manager.maxRank)
    assertEquals(0, manager.ranks["n0"])
    assertEquals(2, manager.ranks["n3"])
  }

  @Test
  fun updateOrderingResortsLayers() {
    val g = LayoutGraph.build(
      listOf(node("a"), node("b"), node("c"), node("s")),
      listOf(edge("e0", "a", "s"), edge("e1", "b", "s"), edge("e2", "c", "s")),
    )
    val manager = LayerManager.build(g, ranksOf(g))

    val reordered = LayerManager.updateOrdering(
      manager,
      mapOf("a" to 2, "b" to 0, "c" to 1, "s" to 0),
    )

    assertEquals(listOf("b", "c", "a"), reordered.layers.getValue(0).nodes)
    assertEquals(0, reordered.ordering["b"])
    assertEquals(2, reordered.ordering["a"])
    assertEquals(manager.ranks, reordered.ranks)
    assertEquals(manager.sortedRanks, reordered.sortedRanks)
  }

  @Test
  fun gettersReturnNodeRankOrderAndAdjacentLayer() {
    val g = LayoutGraph.build(
      listOf(node("a"), node("b")),
      listOf(edge("e0", "a", "b")),
    )
    val manager = LayerManager.build(g, ranksOf(g))

    assertEquals(0, LayerManager.getNodeRank(manager, "a"))
    assertEquals(0, LayerManager.getNodeOrder(manager, "a"))
    assertEquals(1, LayerManager.getNodeRank(manager, "b"))
    assertEquals(listOf("a"), LayerManager.getLayerNodes(manager, 0))
    assertEquals(listOf("b"), LayerManager.getAdjacentLayer(manager, 0, Direction.BELOW))
    assertEquals(emptyList<String>(), LayerManager.getAdjacentLayer(manager, 0, Direction.ABOVE))
  }
}