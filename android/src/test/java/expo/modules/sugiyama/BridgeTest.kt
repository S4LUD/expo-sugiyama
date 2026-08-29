package expo.modules.sugiyama

import expo.modules.kotlin.exception.CodedException
import expo.modules.sugiyama.layout.CyclePolicy
import expo.modules.sugiyama.layout.LayoutRankDir
import expo.modules.sugiyama.layout.PortSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
  private fun node(id: String, width: Double = 150.0, height: Double = 64.0) =
    mapOf("id" to id, "width" to width, "height" to height)

  private fun edge(id: String, source: String, target: String) =
    mapOf("id" to id, "source" to source, "target" to target)

  private fun payload(
    nodes: List<Map<String, Any?>>? = null,
    edges: List<Map<String, Any?>>? = null,
    options: Map<String, Any?>? = null,
  ): Map<String, Any?> {
    return buildMap {
      nodes?.let { put("nodes", it) }
      edges?.let { put("edges", it) }
      options?.let { put("options", it) }
    }
  }

  @Test
  fun completeInputProducesPositionsAndEmptySkipped() {
    val result = Bridge.compute(
      payload(
        nodes = listOf(node("a"), node("b")),
        edges = listOf(edge("e0", "a", "b")),
      ),
    )
    assertEquals(setOf("a", "b"), (result["positions"] as Map<*, *>).keys.toSet())
    assertEquals(emptyList<Any?>(), result["skipped"])
    assertTrue((result["width"] as Double) > 0.0)
    assertTrue((result["height"] as Double) > 0.0)
  }

  @Test
  fun missingNodesResolvesEmptyResultWithDefaultMargins() {
    val result = Bridge.compute(payload())
    assertEquals(0, (result["positions"] as Map<*, *>).size)
    assertEquals(80.0, result["width"])
    assertEquals(80.0, result["height"])
    assertEquals(emptyList<Any?>(), result["skipped"])
  }

  @Test
  fun emptyPayloadIsTreatedAsEmptyGraph() {
    val result = Bridge.compute(emptyMap())
    assertEquals(80.0, result["width"])
    assertEquals(0, (result["positions"] as Map<*, *>).size)
  }

  @Test
  fun invalidNodesAreSkippedAndReported() {
    val parsed = Bridge.parsePayload(
      payload(
        nodes = listOf(
          node("ok"),
          mapOf("id" to ""),
          mapOf("id" to "nan", "width" to Double.NaN, "height" to 64.0),
          mapOf("width" to 10.0, "height" to 10.0),
        ),
      ),
    )
    assertEquals(listOf("ok"), parsed.nodes.map { it.id })
    assertEquals(3, parsed.skipped.size)
    assertTrue(parsed.skipped.all { it.containsKey("id") && it.containsKey("reason") })
  }

  @Test
  fun selfLoopsAndDanglingEdgesAreSkippedAndReported() {
    val parsed = Bridge.parsePayload(
      payload(
        nodes = listOf(node("a"), node("b")),
        edges = listOf(
          edge("sl", "a", "a"),
          edge("dangling", "a", "z"),
          edge("missing-target", "a", ""),
          edge("ok", "a", "b"),
        ),
      ),
    )
    assertEquals(listOf("ok"), parsed.edges.map { it.id })
    assertEquals(3, parsed.skipped.size)
  }

  @Test
  fun sourcePortSideIsParsedAndInvalidValuesFallBackToNull() {
    val parsed = Bridge.parsePayload(
      payload(
        nodes = listOf(node("a"), node("b")),
        edges = listOf(
          mapOf("id" to "bottom", "source" to "a", "target" to "b", "sourcePortSide" to "BOTTOM"),
          mapOf("id" to "right", "source" to "a", "target" to "b", "sourcePortSide" to "RIGHT"),
          mapOf("id" to "diagonal", "source" to "a", "target" to "b", "sourcePortSide" to "DIAGONAL"),
          mapOf("id" to "absent", "source" to "a", "target" to "b"),
        ),
      ),
    )
    assertEquals(
      listOf(PortSide.BOTTOM, PortSide.RIGHT, null, null),
      parsed.edges.map { it.sourcePortSide },
    )
  }

  @Test
  fun stackingEdgesStackBelowInLRThroughTheBridge() {
    val result = Bridge.compute(
      payload(
        nodes = listOf(node("a"), node("s")),
        edges = listOf(
          mapOf("id" to "e0", "source" to "a", "target" to "s", "sourcePortSide" to "BOTTOM"),
        ),
        options = mapOf("rankdir" to "LR"),
      ),
    )
    val positions = result["positions"] as Map<*, *>
    val a = (positions["a"] as Map<*, *>)
    val s = (positions["s"] as Map<*, *>)
    assertEquals(a["x"], s["x"])
    assertEquals((a["y"] as Double) + 64.0 + 30.0, s["y"])
  }

  @Test
  fun omittedOptionsGetDefaults() {
    val parsed = Bridge.parsePayload(payload(nodes = emptyList(), options = emptyMap()))
    assertEquals(LayoutRankDir.TB, parsed.options.rankdir)
    assertNull(parsed.options.nodesep)
    assertNull(parsed.options.ranksep)
    assertEquals(40.0, parsed.options.marginx, 0.0)
    assertEquals(40.0, parsed.options.marginy, 0.0)
    assertEquals(150.0, parsed.options.nodeWidth, 0.0)
    assertEquals(64.0, parsed.options.nodeHeight, 0.0)
    assertEquals(4, parsed.options.orderingPasses)
    assertEquals(false, parsed.options.alignBranches)
    assertEquals(CyclePolicy.TOLERANT, parsed.options.cyclePolicy)
  }

  @Test
  fun optionsAreClampedAndNormalized() {
    val parsed = Bridge.parsePayload(
      payload(
        nodes = emptyList(),
        options = mapOf(
          "nodesep" to -5.0,
          "ranksep" to 0.0,
          "marginx" to 30.0,
          "orderingPasses" to 2.5,
          "rankdir" to "diagonal",
          "cyclePolicy" to "throw",
          "alignBranches" to "yes",
        ),
      ),
    )
    assertEquals(0.0, parsed.options.nodesep ?: 0.0, 0.0)
    assertEquals(0.0, parsed.options.ranksep ?: 0.0, 0.0)
    assertEquals(30.0, parsed.options.marginx, 0.0)
    assertEquals(4, parsed.options.orderingPasses)
    assertEquals(LayoutRankDir.TB, parsed.options.rankdir)
    assertEquals(CyclePolicy.THROW, parsed.options.cyclePolicy)
    assertEquals(false, parsed.options.alignBranches)
  }

  @Test
  fun validRankDirAndPolicyArePreserved() {
    val parsed = Bridge.parsePayload(
      payload(
        nodes = emptyList(),
        options = mapOf(
          "rankdir" to "LR",
          "cyclePolicy" to "tolerant",
          "orderingPasses" to 0,
        ),
      ),
    )
    assertEquals(LayoutRankDir.LR, parsed.options.rankdir)
    assertEquals(CyclePolicy.TOLERANT, parsed.options.cyclePolicy)
    assertEquals(0, parsed.options.orderingPasses)
  }

  @Test
  fun cyclePolicyThrowRejectsWithCYCLE_DETECTED() {
    val error = assertThrows(CodedException::class.java) {
      Bridge.compute(
        payload(
          nodes = listOf(node("a"), node("b")),
          edges = listOf(edge("e0", "a", "b"), edge("e1", "b", "a")),
          options = mapOf("cyclePolicy" to "throw"),
        ),
      )
    }
    assertEquals("CYCLE_DETECTED", error.code)
  }

  @Test
  fun tolerantCyclesStillProduceFullPositions() {
    val result = Bridge.compute(
      payload(
        nodes = listOf(node("a"), node("b")),
        edges = listOf(edge("e0", "a", "b"), edge("e1", "b", "a")),
      ),
    )
    assertEquals(setOf("a", "b"), (result["positions"] as Map<*, *>).keys.toSet())
  }
}
