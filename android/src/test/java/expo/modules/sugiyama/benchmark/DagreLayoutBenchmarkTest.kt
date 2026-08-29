package expo.modules.sugiyama.benchmark

import expo.modules.sugiyama.layout.DagreLayout
import expo.modules.sugiyama.layout.LayoutEdge
import expo.modules.sugiyama.layout.LayoutNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance gate (spec §17.1): a 1000-node layered DAG must lay out in
 * under 250ms. Runs on the JVM test runner; device-class thresholds are
 * benchmarked separately in CI (Appendix D).
 */
class DagreLayoutBenchmarkTest {
  @Test
  fun layout1000NodesUnder250ms() {
    val cols = 25
    val layers = 40
    val id: (Int, Int) -> String = { c, l -> "n${l}_$c" }

    val nodes = mutableListOf<LayoutNode>()
    for (l in 0 until layers) {
      for (c in 0 until cols) {
        nodes.add(LayoutNode(id(c, l), 150.0, 64.0))
      }
    }

    val edges = mutableListOf<LayoutEdge>()
    var edgeIndex = 0
    for (l in 0 until layers - 1) {
      for (c in 0 until cols) {
        edges.add(LayoutEdge("e${edgeIndex++}", id(c, l), id(c, l + 1)))
        edges.add(LayoutEdge("e${edgeIndex++}", id(c, l), id((c + 1) % cols, l + 1)))
      }
    }

    // Warm the JIT before measuring
    DagreLayout.computeLayout(nodes, edges)

    val start = System.nanoTime()
    val output = DagreLayout.computeLayout(nodes, edges)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

    assertEquals(1000, output.positions.size)
    assertTrue("1000-node layout took $elapsedMs ms (target < 250ms)", elapsedMs < 250.0)
  }
}