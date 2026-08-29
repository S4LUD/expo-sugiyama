package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Stage 2 tests (spec A.7): chain, fork, join, diamond, disconnected
 * components, multiple roots, and both cycle policies.
 */
class LayerAssignerTest {
  private fun graph(nodes: List<LayoutNode>, edges: List<LayoutEdge>) =
    LayoutGraph.build(nodes, edges)

  @Test
  fun assignsChainRanks() {
    val g = graph(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["n0"])
    assertEquals(1, ranks["n1"])
    assertEquals(2, ranks["n2"])
  }

  @Test
  fun assignsForkRanks() {
    val g = graph(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["r"])
    assertEquals(1, ranks["a"])
    assertEquals(1, ranks["b"])
  }

  @Test
  fun assignsJoinRanks() {
    val g = graph(
      listOf(node("a"), node("b"), node("s")),
      listOf(edge("e0", "a", "s"), edge("e1", "b", "s")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["a"])
    assertEquals(0, ranks["b"])
    assertEquals(1, ranks["s"])
  }

  @Test
  fun assignLongestPathForMultiParents() {
    val g = graph(
      listOf(node("a"), node("b"), node("c"), node("d")),
      listOf(edge("e0", "a", "b"), edge("e1", "a", "c"), edge("e2", "c", "d"), edge("e3", "b", "d")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["a"])
    assertEquals(1, ranks["b"])
    assertEquals(1, ranks["c"])
    assertEquals(2, ranks["d"])
  }

  @Test
  fun handlesDisconnectedComponents() {
    val g = graph(
      listOf(node("p0"), node("p1"), node("q0"), node("q1")),
      listOf(edge("e0", "p0", "p1"), edge("e1", "q0", "q1")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["p0"])
    assertEquals(1, ranks["p1"])
    assertEquals(0, ranks["q0"])
    assertEquals(1, ranks["q1"])
  }

  @Test
  fun producesDeterministicRanks() {
    val g = graph(
      listOf(node("a"), node("b"), node("c"), node("d")),
      listOf(edge("e0", "a", "b"), edge("e1", "b", "d"), edge("e2", "a", "c")),
    )
    val first = LayerAssigner.assignLayers(g)
    val second = LayerAssigner.assignLayers(g)
    assertEquals(first, second)
  }

  @Test
  fun skipsMissingNodeAndSelfLoopEdges() {
    val g = graph(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n0"), edge("e1", "n1", "ghost")),
    )
    val ranks = LayerAssigner.assignLayers(g)
    assertEquals(0, ranks["n0"])
    assertEquals(0, ranks["n1"])
  }

  @Test
  fun treatsCyclesAsRankZeroInTolerantMode() {
    val g = graph(
      listOf(node("a"), node("b")),
      listOf(edge("e0", "a", "b"), edge("e1", "b", "a")),
    )
    val ranks = LayerAssigner.assignLayers(g, CyclePolicy.TOLERANT)
    // DFS longest-path: b = max(rank(a)=0)+1 = 1; a = max(rank(b)=1)+1 = 2
    assertEquals(2, ranks["a"])
    assertEquals(1, ranks["b"])
  }

  @Test
  fun throwsOnCycleInStrictMode() {
    val g = graph(
      listOf(node("a"), node("b"), node("c")),
      listOf(edge("e0", "a", "b"), edge("e1", "b", "c"), edge("e2", "c", "a")),
    )
    val exception = assertThrows(LayoutCycleException::class.java) {
      LayerAssigner.assignLayers(g, CyclePolicy.THROW)
    }
    assertEquals(true, exception.message!!.contains("Cycle detected during layer assignment"))
  }
}