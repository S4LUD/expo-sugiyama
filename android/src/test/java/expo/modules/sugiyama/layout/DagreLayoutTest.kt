package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pipeline tests (spec A.6): empty graphs, full layouts,
 * determinism, bounds, and rank invariants.
 */
class DagreLayoutTest {
  @Test
  fun emptyGraphResolvesWithMarginDimensions() {
    val output = DagreLayout.computeLayout(emptyList(), emptyList())

    assertEquals(0, output.positions.size)
    assertEquals(80.0, output.width, 0.0) // marginx * 2
    assertEquals(80.0, output.height, 0.0) // marginy * 2
  }

  @Test
  fun chainProducesExpectedPositionsAndDimensions() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
    )

    assertEquals(40.0, output.positions.getValue("n0").x, 0.0)
    assertEquals(40.0, output.positions.getValue("n0").y, 0.0)
    assertEquals(40.0, output.positions.getValue("n1").x, 0.0)
    // Spacing derived from node size: ranksep = ceil(0.5 * 64) = 32.
    assertEquals(136.0, output.positions.getValue("n1").y, 0.0)
    assertEquals(40.0, output.positions.getValue("n2").x, 0.0)
    assertEquals(232.0, output.positions.getValue("n2").y, 0.0)

    // maxX = 40+150 = 190 -> width 230; maxY = 232+64 = 296 -> height 336
    assertEquals(230.0, output.width, 0.0)
    assertEquals(336.0, output.height, 0.0)
  }

  @Test
  fun forkLayoutCentersParentsOverChildren() {
    val output = DagreLayout.computeLayout(
      listOf(node("r"), node("a"), node("b"), node("c")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b"), edge("e2", "r", "c")),
    )

    assertEquals(220.0, output.positions.getValue("r").x, 0.0)
    assertEquals(40.0, output.positions.getValue("a").x, 0.0)
    assertEquals(220.0, output.positions.getValue("b").x, 0.0)
    assertEquals(400.0, output.positions.getValue("c").x, 0.0)
  }

  @Test
  fun tolerantCyclesStillProduceFullLayout() {
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("b"), node("c")),
      listOf(edge("e0", "a", "b"), edge("e1", "b", "c"), edge("e2", "c", "a")),
    )

    assertEquals(3, output.positions.size)
    for (position in output.positions.values) {
      assertTrue(position.x >= 40.0)
      assertTrue(position.y >= 40.0)
    }
  }

  @Test
  fun deterministicAcrossRuns() {
    val nodes = listOf(node("a"), node("b"), node("c"), node("d"), node("e"))
    val edges = listOf(edge("e0", "a", "b"), edge("e1", "a", "c"), edge("e2", "b", "d"), edge("e3", "c", "d"), edge("e4", "d", "e"))

    val first = DagreLayout.computeLayout(nodes, edges)
    val second = DagreLayout.computeLayout(nodes, edges)

    assertEquals(first.positions, second.positions)
    assertEquals(first.width, second.width, 0.0)
    assertEquals(first.height, second.height, 0.0)
  }

  @Test
  fun boundsGuaranteeMarginOnEveryAxis() {
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("b"), node("c"), node("d"), node("e")),
      listOf(
        edge("e0", "a", "b"),
        edge("e1", "a", "c"),
        edge("e2", "c", "d"),
        edge("e3", "d", "e"),
      ),
    )

    for (position in output.positions.values) {
      assertTrue("x=${position.x} < marginx", position.x >= 40.0)
      assertTrue("y=${position.y} < marginy", position.y >= 40.0)
    }
  }

  @Test
  fun everyParentHasStrictlyLowerRankThanChild() {
    val nodes = listOf(node("a"), node("b"), node("c"), node("d"), node("e"))
    val edges = listOf(
      edge("e0", "a", "b"),
      edge("e1", "a", "c"),
      edge("e2", "b", "d"),
      edge("e3", "c", "d"),
      edge("e4", "d", "e"),
    )
    val g = LayoutGraph.build(nodes, edges)
    val ranks = LayerAssigner.assignLayers(g)

    for (edgeItem in edges) {
      assertTrue(ranks.getValue(edgeItem.target) > ranks.getValue(edgeItem.source))
    }
  }

  @Test
  fun nodeWidthAndHeightOptionsAreAccepted() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n1")),
      LayoutOptions(nodesep = 20.0, ranksep = 100.0, marginx = 8.0, marginy = 16.0),
    )

    assertEquals(8.0, output.positions.getValue("n0").x, 0.0)
    assertEquals(16.0, output.positions.getValue("n0").y, 0.0)
    // n1 is rank 1: 16 + 64 + ranksep(100) = 180
    assertEquals(180.0, output.positions.getValue("n1").y, 0.0)
  }

  @Test
  fun btMirrorsRanksSoRootsAreAtTheBottom() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
      LayoutOptions(rankdir = LayoutRankDir.BT),
    )

    val root = output.positions.getValue("n0")
    val mid = output.positions.getValue("n1")
    val last = output.positions.getValue("n2")

    assertEquals(40.0, root.x, 0.0)
    assertEquals(40.0, last.x, 0.0)
    // Rank 0 sits at the bottom, rank 2 at the top.
    assertTrue("root y=${root.y} not below mid y=${mid.y}", root.y > mid.y)
    assertTrue("mid y=${mid.y} not below last y=${last.y}", mid.y > last.y)
    // Same bounds as TB (derived ranksep 32).
    assertEquals(336.0, output.height, 0.0)
    assertEquals(230.0, output.width, 0.0)
    // Bottom margin preserved: bottom of root = height - marginy.
    assertEquals(output.height - 40.0, root.y + 64.0, 0.0)
  }

  @Test
  fun lrTransposesRanksIntoColumns() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1"), node("n2")),
      listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2")),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val root = output.positions.getValue("n0")
    val mid = output.positions.getValue("n1")
    val last = output.positions.getValue("n2")

    // Rank 0 leftmost, same row (nodes keep their real height as the
    // within-rank extent in flow space, so all rows sit at y = marginy).
    assertEquals(40.0, root.y, 0.0)
    assertEquals(root.y, mid.y, 0.0)
    assertEquals(mid.y, last.y, 0.0)
    assertTrue("mid x=${mid.x} not right of root x=${root.x}", mid.x > root.x)
    assertTrue("last x=${last.x} not right of mid x=${mid.x}", last.x > mid.x)
    // Rank rows run along X in flow space with node height 150 per rank and
    // ranksep swapped to the derived nodesep (30): x 40 -> 220 -> 400.
    assertEquals(220.0, mid.x, 0.0)
    assertEquals(400.0, last.x, 0.0)
    // Bounds: width = flow height (400 + 150 + 40), height = flow width
    // (40 + 64 + 40).
    assertEquals(590.0, output.width, 0.0)
    assertEquals(144.0, output.height, 0.0)
  }

  @Test
  fun rlMirrorsRanksSoRootsAreAtTheRight() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n1")),
      LayoutOptions(rankdir = LayoutRankDir.RL),
    )

    val root = output.positions.getValue("n0")
    val last = output.positions.getValue("n1")

    assertEquals(root.y, last.y, 0.0)
    assertTrue("root x=${root.x} not right of last x=${last.x}", root.x > last.x)
    // Right margin preserved: right edge of root = width - marginx.
    assertEquals(output.width - 40.0, root.x + 150.0, 0.0)
  }

  @Test
  fun lrKeepsMarginsCanvasSpace() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n1")),
      LayoutOptions(rankdir = LayoutRankDir.LR, marginx = 8.0, marginy = 16.0),
    )

    // marginx (8) is the left/right margin in canvas space...
    assertEquals(8.0, output.positions.getValue("n0").x, 0.0)
    assertEquals(16.0, output.positions.getValue("n0").y, 0.0)
    // ...and marginy (16) the top/bottom margin: bottom of the n0 row =
    // height - marginy.
    assertEquals(output.height - 16.0, output.positions.getValue("n0").y + 64.0, 0.0)
    // n1 sits one rank right of n0: 8 + 150 + nodesep-swapped ranksep (30).
    assertEquals(188.0, output.positions.getValue("n1").x, 0.0)
    // Bounds in flow space: width = maxY(188 + 150) + marginy(8).
    assertEquals(346.0, output.width, 0.0)
  }

  @Test
  fun realNodeDimensionsDriveSpacingInEveryDirection() {
    val nodes = listOf(node("a", width = 120.0, height = 80.0), node("b", width = 200.0, height = 48.0))
    val edges = listOf(edge("e0", "a", "b"))

    val tb = DagreLayout.computeLayout(nodes, edges)
    // Spacing derived from the average node size (160 x 64 -> 32/32):
    // rank heights are a 80, b 48 -> b.y = 40 + 80 + 32 = 152.
    assertEquals(40.0, tb.positions.getValue("a").y, 0.0)
    assertEquals(152.0, tb.positions.getValue("b").y, 0.0)
    // Real dims drive spacing: a's subtree is 200 wide (b), so a is centered
    // in it: 40 + (200 - 120) / 2 = 80; b is centered under a: 40.
    assertEquals(80.0, tb.positions.getValue("a").x, 0.0)
    assertEquals(40.0, tb.positions.getValue("b").x, 0.0)

    val lr = DagreLayout.computeLayout(nodes, edges, LayoutOptions(rankdir = LayoutRankDir.LR))
    // In LR the node heights become the within-rank (Y) extent (a 80, b 48).
    // a sits at y = marginy; b is centered under a's flow row: parent center
    // 40 + 40 = 80, children total 48 -> b.y = 56. Rank separation along X
    // uses the swapped derived ranksep (canonical nodesep 30): 40 + 120 + 30 =
    // 190 (canonical default width 150 -> nodesep 30, not the per-node avg).
    assertEquals(40.0, lr.positions.getValue("a").y, 0.0)
    assertEquals(56.0, lr.positions.getValue("b").y, 0.0)
    assertEquals(190.0, lr.positions.getValue("b").x, 0.0)
    // Canvas bounds: width = maxY (190 + 200) + marginy (40); height = maxX
    // (max of a: 40 + 80 and b: 56 + 48 = 120) + marginx (40).
    assertEquals(430.0, lr.width, 0.0)
    assertEquals(160.0, lr.height, 0.0)
  }

  @Test
  fun spacingIsDerivedFromCanonicalDefaultNodeSize() {
    val nodes = listOf(node("a", width = 100.0, height = 50.0), node("b", width = 200.0, height = 100.0))
    val edges = listOf(edge("e0", "a", "b"))

    // Canonical default dims 150 x 64 -> nodesep ceil(30) = 30, ranksep
    // ceil(32) = 32 regardless of the per-node sizes in the graph.
    val tb = DagreLayout.computeLayout(nodes, edges)
    assertEquals(40.0, tb.positions.getValue("a").y, 0.0)
    // 40 + rank-0 height (a is 50 tall) + ranksep (32) = 122.
    assertEquals(122.0, tb.positions.getValue("b").y, 0.0)

    val lr = DagreLayout.computeLayout(nodes, edges, LayoutOptions(rankdir = LayoutRankDir.LR))
    // Rank separation along X: 40 + a's flow height (real width 100) + the
    // swapped derived ranksep (canonical nodesep 30) = 170.
    assertEquals(170.0, lr.positions.getValue("b").x, 0.0)
  }

  @Test
  fun dimlessNodesUseCanonicalDerivedSpacing() {
    val zero = { id: String -> node(id, width = 0.0, height = 0.0) }

    // Zero-dimension nodes fall back to dims 0 x 0, but the spacing still
    // derives from the canonical default node size: ranksep 32, nodesep 30.
    val chain = DagreLayout.computeLayout(
      listOf(zero("a"), zero("b"), zero("c")),
      listOf(edge("e0", "a", "b"), edge("e1", "b", "c")),
    )
    assertEquals(40.0, chain.positions.getValue("a").y, 0.0)
    assertEquals(72.0, chain.positions.getValue("b").y, 0.0)
    assertEquals(104.0, chain.positions.getValue("c").y, 0.0)
    assertEquals(144.0, chain.height, 0.0)

    // ...and canonical nodesep 30 between siblings.
    val fork = DagreLayout.computeLayout(
      listOf(zero("r"), zero("a"), zero("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    assertEquals(40.0, fork.positions.getValue("a").x, 0.0)
    assertEquals(70.0, fork.positions.getValue("b").x, 0.0)
  }

  @Test
  fun explicitSpacingOverridesDerivedDefaults() {
    val output = DagreLayout.computeLayout(
      listOf(node("n0"), node("n1")),
      listOf(edge("e0", "n0", "n1")),
      LayoutOptions(nodesep = 5.0, ranksep = 10.0),
    )

    // Derived would be 30/32; explicit values must win.
    assertEquals(114.0, output.positions.getValue("n1").y, 0.0)
  }

  // --- Stacking edges (declared perpendicular port sides) ---

  @Test
  fun `LR stacks a bottom-side child directly below the source`() {
    // ai-out style: a BOTTOM-side edge in LR must not push the target into
    // the next column; it stacks in the same column, one gap below.
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s")),
      listOf(edge("e0", "a", "s", sourcePortSide = PortSide.BOTTOM)),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val a = output.positions.getValue("a")
    val s = output.positions.getValue("s")
    assertEquals("stacked child shares the source column", a.x, s.x, 0.0)
    // Canonical nodesep 30 (LR uses nodesep as the cross-rank gap).
    assertEquals("top edge sits one gap below the source bottom", a.y + 64.0 + 30.0, s.y, 0.0)
  }

  @Test
  fun `stacked siblings form a centered row along the flow axis`() {
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s1"), node("s2")),
      listOf(
        edge("e0", "a", "s1", sourcePortSide = PortSide.BOTTOM),
        edge("e1", "a", "s2", sourcePortSide = PortSide.BOTTOM),
      ),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val a = output.positions.getValue("a")
    val s1 = output.positions.getValue("s1")
    val s2 = output.positions.getValue("s2")
    // Direct children of one source keep the SAME perpendicular offset (a
    // row band below the source) and spread along the flow axis as a group
    // CENTERED on the source: the row's midpoint equals the source's center
    // (115), so additions never drift the row off its connection. Along
    // canvas X a node's extent is its width (150); the row gap is the
    // cross-rank gap (canonical nodesep 30 in LR) → total 330, start -50.
    assertEquals("siblings share the row band", s1.y, s2.y, 0.0)
    assertEquals("band sits one gap below the source", a.y + 64.0 + 30.0, s1.y, 0.0)
    assertEquals("second child extends the row", s1.x + 150.0 + 30.0, s2.x, 0.0)
    val rowCenter = (s1.x + s2.x + 150.0) / 2.0
    assertEquals("row centers on the source", a.x + 75.0, rowCenter, 0.0)
  }

  @Test
  fun `TB stacks a right-side child beside the source`() {
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s")),
      listOf(edge("e0", "a", "s", sourcePortSide = PortSide.RIGHT)),
    )

    val a = output.positions.getValue("a")
    val s = output.positions.getValue("s")
    assertEquals("stacked child shares the source row", a.y, s.y, 0.0)
    // Canonical ranksep 32.
    assertEquals("left edge sits one gap right of the source", a.x + 150.0 + 32.0, s.x, 0.0)
  }

  @Test
  fun `TB stacks a left-side child beside the source on the left`() {
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("t")),
      listOf(edge("e0", "a", "t", sourcePortSide = PortSide.LEFT)),
    )

    val a = output.positions.getValue("a")
    val t = output.positions.getValue("t")
    assertEquals(a.y, t.y, 0.0)
    assertEquals("left edge sits one gap left of the source", a.x - 150.0 - 32.0, t.x, 0.0)
  }

  @Test
  fun `flow children of a stacked target center under it`() {
    // A stacked node is a real parent for flow edges: its child must center
    // under the stacked position, not fall back to the margin.
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s"), node("c")),
      listOf(
        edge("e0", "a", "s", sourcePortSide = PortSide.BOTTOM),
        edge("e1", "s", "c"),
      ),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val s = output.positions.getValue("s")
    val c = output.positions.getValue("c")
    // In LR the canvas column is Y (flow X); the child must center under the
    // stacked parent's column, not fall back to its row.
    assertEquals("child centers under its stacked parent", s.y, c.y, 0.0)
  }

  @Test
  fun `a long stacked row does not push the next rank`() {
    // a → b is the flow edge (b one rank downstream); a → s1/s2 is the
    // stacked row. The row lives in its own band beside a and must never
    // widen a's rank band: b keeps its regular one-gap placement no matter
    // how long the row grows (the respond-node regression).
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("b"), node("s1"), node("s2")),
      listOf(
        edge("e0", "a", "b"),
        edge("e1", "a", "s1", sourcePortSide = PortSide.BOTTOM),
        edge("e2", "a", "s2", sourcePortSide = PortSide.BOTTOM),
      ),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val a = output.positions.getValue("a")
    val b = output.positions.getValue("b")
    // Canvas X is the flow rank axis: b sits one max-flow-extent (150) plus
    // the cross-rank gap (30) past a — identical to a graph with no row.
    assertEquals(a.x + 150.0 + 30.0, b.x, 0.0)
  }

  @Test
  fun `stacking chains stack transitively`() {
    // A stacked target with its own bottom-side child stacks both.
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s1"), node("s2")),
      listOf(
        edge("e0", "a", "s1", sourcePortSide = PortSide.BOTTOM),
        edge("e1", "s1", "s2", sourcePortSide = PortSide.BOTTOM),
      ),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val s1 = output.positions.getValue("s1")
    val s2 = output.positions.getValue("s2")
    assertEquals(s1.x, s2.x, 0.0)
    assertEquals(s1.y + 64.0 + 30.0, s2.y, 0.0)
  }

  @Test
  fun `stacking side along the flow axis keeps the downstream placement`() {
    // A RIGHT-side edge in LR is a flow edge: the target stays one rank
    // downstream (the classic LR placement).
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("s")),
      listOf(edge("e0", "a", "s", sourcePortSide = PortSide.RIGHT)),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val a = output.positions.getValue("a")
    val s = output.positions.getValue("s")
    assertEquals("flow child shares the source row", a.y, s.y, 0.0)
    assertEquals(a.x + 150.0 + 30.0, s.x, 0.0)
  }

  @Test
  fun `soft stacking edge leaves a flow-parented target in its flow rank`() {
    // A target with both a flow parent and a stacking parent is ranked by the
    // flow edge; the stacking edge is soft and must not move it below.
    val output = DagreLayout.computeLayout(
      listOf(node("a"), node("b"), node("x")),
      listOf(
        edge("e0", "a", "b"),
        edge("e1", "x", "b", sourcePortSide = PortSide.BOTTOM),
      ),
      LayoutOptions(rankdir = LayoutRankDir.LR),
    )

    val a = output.positions.getValue("a")
    val b = output.positions.getValue("b")
    assertEquals("flow rank wins for a flow-parented target", a.y, b.y, 0.0)
    assertEquals(a.x + 150.0 + 30.0, b.x, 0.0)
  }

  @Test
  fun `stacking layouts are deterministic across runs`() {
    val nodes = listOf(node("a"), node("s1"), node("s2"))
    val edges = listOf(
      edge("e0", "a", "s1", sourcePortSide = PortSide.BOTTOM),
      edge("e1", "a", "s2", sourcePortSide = PortSide.BOTTOM),
    )
    val options = LayoutOptions(rankdir = LayoutRankDir.LR)

    val first = DagreLayout.computeLayout(nodes, edges, options)
    val second = DagreLayout.computeLayout(nodes, edges, options)

    assertEquals(first.positions, second.positions)
    assertEquals(first.width, second.width, 0.0)
    assertEquals(first.height, second.height, 0.0)
  }

  @Test
  fun `stacking cycles resolve without crashing`() {
    // A mutual stacking cycle is user error; the planner must still produce a
    // full, deterministic layout.
    val nodes = listOf(node("a"), node("b"))
    val edges = listOf(
      edge("e0", "a", "b", sourcePortSide = PortSide.BOTTOM),
      edge("e1", "b", "a", sourcePortSide = PortSide.BOTTOM),
    )
    val options = LayoutOptions(rankdir = LayoutRankDir.LR)

    val output = DagreLayout.computeLayout(nodes, edges, options)
    assertEquals(2, output.positions.size)
    val again = DagreLayout.computeLayout(nodes, edges, options)
    assertEquals(output.positions, again.positions)
  }
}