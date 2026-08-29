package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 tests (spec A.8): crossing reduction on a known bipartite DAG,
 * determinism across input order, pass-count behavior, and ID tie-breaks.
 */
class OrderingEngineTest {
  private fun graph(vararg pairs: Pair<String, String>) =
    LayoutGraph.build(
      pairs.flatMap { listOf(it.first, it.second) }.distinct().map { node(it) },
      pairs.mapIndexed { i, (s, t) -> edge("e$i", s, t) },
    )

  @Test
  fun medianOrderingRemovesCrossings() {
    // rank0 [a,b,c] x rank1 [d,e,f]; reversed connections create 3 crossings
    val g = graph("a" to "f", "b" to "e", "c" to "d")
    val base = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val initial = OrderingEngine.countTotalCrossings(g, base)
    assertEquals(3, initial)

    val ordered = OrderingEngine.orderNodes(g, base, passes = 4)
    assertEquals(0, OrderingEngine.countTotalCrossings(g, ordered))
  }

  @Test
  fun deterministicAcrossNodeInputOrder() {
    val edges = listOf("a" to "d", "a" to "e", "b" to "e", "c" to "e", "c" to "f")
    val g1 = LayoutGraph.build(
      edges.flatMap { listOf(it.first, it.second) }.distinct().map { node(it) },
      edges.mapIndexed { i, (s, t) -> edge("e$i", s, t) },
    )
    val g2 = LayoutGraph.build(
      edges.flatMap { listOf(it.first, it.second) }.distinct().reversed().map { node(it) },
      edges.reversed().mapIndexed { i, (s, t) -> edge("e$i", s, t) },
    )

    val o1 = OrderingEngine.orderNodes(g1, LayerManager.build(g1, LayerAssigner.assignLayers(g1)))
    val o2 = OrderingEngine.orderNodes(g2, LayerManager.build(g2, LayerAssigner.assignLayers(g2)))

    assertEquals(o1.ordering, o2.ordering)
    assertEquals(o1.layers, o2.layers)
  }

  @Test
  fun zeroPassesKeepsInitialIdOrdering() {
    val g = graph("a" to "f", "b" to "e", "c" to "d")
    val base = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val unchanged = OrderingEngine.orderNodes(g, base, passes = 0)

    assertEquals(base.ordering, unchanged.ordering)
    assertEquals(base.layers, unchanged.layers)
  }

  @Test
  fun equalMediansTieBreakByNodeId() {
    // Complete bipartite K2,2: both rank-1 nodes have identical medians
    val g = LayoutGraph.build(
      listOf(node("a"), node("b"), node("x"), node("y")),
      listOf(
        edge("e0", "a", "x"),
        edge("e1", "b", "x"),
        edge("e2", "a", "y"),
        edge("e3", "b", "y"),
      ),
    )
    val ordered = OrderingEngine.orderNodes(
      g,
      LayerManager.build(g, LayerAssigner.assignLayers(g)),
      passes = 2,
    )

    // Ties resolve by node ID order, not input/edge order
    assertEquals(listOf("a", "b"), ordered.layers.getValue(0).nodes)
    assertEquals(listOf("x", "y"), ordered.layers.getValue(1).nodes)
    // K2,2 has exactly one unavoidable crossing
    assertEquals(1, OrderingEngine.countTotalCrossings(g, ordered))
  }

  @Test
  fun upDownSweepReducesCrossingsInDeepGraph() {
    val g = LayoutGraph.build(
      listOf(
        node("a"), node("b"), node("c"),
        node("d"), node("e"), node("f"),
        node("g"), node("h"), node("i"),
      ),
      listOf(
        edge("e0", "a", "f"),
        edge("e1", "a", "e"),
        edge("e2", "b", "e"),
        edge("e3", "c", "e"),
        edge("e4", "c", "d"),
        edge("e5", "d", "i"),
        edge("e6", "e", "h"),
        edge("e7", "f", "g"),
      ),
    )
    val base = LayerManager.build(g, LayerAssigner.assignLayers(g))
    val initial = OrderingEngine.countTotalCrossings(g, base)
    assertEquals(8, initial)

    val ordered = OrderingEngine.orderNodes(g, base, passes = 4)
    val finalCrossings = OrderingEngine.countTotalCrossings(g, ordered)

    assertEquals(0, finalCrossings)
    assertTrue(finalCrossings < initial)
  }

  @Test
  fun fanOutOrdersTargetsBySourcePortIndex() {
    // qual-yes declared before qual-no → its connector is left of qual-no's,
    // so its target must land left of the other (zero crossings, matches the
    // visible connector sides).
    val g = LayoutGraph.build(
      listOf(node("qualify"), node("score"), node("nurture")),
      listOf(
        edge("e0", "qualify", "score", sourcePortIndex = 1),
        edge("e1", "qualify", "nurture", sourcePortIndex = 2),
      ),
    )

    assertEquals("min incoming source-port index is tracked", 1, g.incomingPortIndex["score"])
    assertEquals(2, g.incomingPortIndex["nurture"])

    val ordered = OrderingEngine.orderNodes(
      g,
      LayerManager.build(g, LayerAssigner.assignLayers(g)),
    )
    assertEquals(listOf("score", "nurture"), ordered.layers.getValue(1).nodes)
    assertEquals(0, OrderingEngine.countTotalCrossings(g, ordered))
  }

  @Test
  fun hubTargetsSharePortIndexAndTieBreakByNodeId() {
    // Two edges leave the SAME hub connector (notify-out → email + push) so
    // their keys are equal; order falls back to node ID determinism.
    val g = LayoutGraph.build(
      listOf(node("a"), node("b"), node("c")),
      listOf(
        edge("e0", "a", "b", sourcePortIndex = 1),
        edge("e1", "a", "c", sourcePortIndex = 1),
      ),
    )

    val ordered = OrderingEngine.orderNodes(
      g,
      LayerManager.build(g, LayerAssigner.assignLayers(g)),
    )
    assertEquals(1, g.incomingPortIndex["b"])
    assertEquals(listOf("b", "c"), ordered.layers.getValue(1).nodes)
  }

  @Test
  fun diamondTargetUsesMinIncomingPortIndex() {
    // Multi-parent node (diamond): key = min port index across ALL incoming
    // edges, still deterministic.
    val g = LayoutGraph.build(
      listOf(node("a"), node("b"), node("x")),
      listOf(
        edge("e0", "a", "x", sourcePortIndex = 2),
        edge("e1", "b", "x", sourcePortIndex = 3),
      ),
    )

    assertEquals(2, g.incomingPortIndex["x"])
  }

  @Test
  fun nodesWithoutIncomingEdgesAbsentFromPortIndexMap() {
    val g = LayoutGraph.build(
      listOf(node("root"), node("leaf")),
      listOf(edge("e0", "root", "leaf", sourcePortIndex = 1)),
    )

    assertEquals(1, g.incomingPortIndex["leaf"])
    assertTrue(!g.incomingPortIndex.containsKey("root"))
  }
}