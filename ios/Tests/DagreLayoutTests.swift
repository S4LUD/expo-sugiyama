import XCTest
@testable import ExpoSugiyama

/// End-to-end pipeline tests (spec A.6): empty graphs, full layouts,
/// determinism, bounds, and rank invariants.
final class DagreLayoutTests: XCTestCase {
  func testEmptyGraphResolvesWithMarginDimensions() throws {
    let output = try DagreLayout.computeLayout(nodes: [], edges: [])

    XCTAssertEqual(output.positions.count, 0)
    XCTAssertEqual(output.width, 80.0, accuracy: 0.0) // marginx * 2
    XCTAssertEqual(output.height, 80.0, accuracy: 0.0) // marginy * 2
  }

  func testChainProducesExpectedPositionsAndDimensions() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )

    XCTAssertEqual(output.positions["n0"]?.x ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n0"]?.y ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n1"]?.x ?? -1, 40.0, accuracy: 0.0)
    // Spacing derived from node size: ranksep = ceil(0.5 * 64) = 32.
    XCTAssertEqual(output.positions["n1"]?.y ?? -1, 136.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n2"]?.x ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n2"]?.y ?? -1, 232.0, accuracy: 0.0)

    // maxX = 40+150 = 190 -> width 230; maxY = 232+64 = 296 -> height 336
    XCTAssertEqual(output.width, 230.0, accuracy: 0.0)
    XCTAssertEqual(output.height, 336.0, accuracy: 0.0)
  }

  func testForkLayoutCentersParentsOverChildren() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("r"), node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "r", target: "a"),
        edge("e1", source: "r", target: "b"),
        edge("e2", source: "r", target: "c"),
      ]
    )

    XCTAssertEqual(output.positions["r"]?.x ?? -1, 220.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["a"]?.x ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["b"]?.x ?? -1, 220.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["c"]?.x ?? -1, 400.0, accuracy: 0.0)
  }

  func testTolerantCyclesStillProduceFullLayout() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "b", target: "c"),
        edge("e2", source: "c", target: "a"),
      ]
    )

    XCTAssertEqual(output.positions.count, 3)
    for position in output.positions.values {
      XCTAssertGreaterThanOrEqual(position.x, 40.0)
      XCTAssertGreaterThanOrEqual(position.y, 40.0)
    }
  }

  func testDeterministicAcrossRuns() throws {
    let nodes = [node("a"), node("b"), node("c"), node("d"), node("e")]
    let edges = [
      edge("e0", source: "a", target: "b"),
      edge("e1", source: "a", target: "c"),
      edge("e2", source: "b", target: "d"),
      edge("e3", source: "c", target: "d"),
      edge("e4", source: "d", target: "e"),
    ]

    let first = try DagreLayout.computeLayout(nodes: nodes, edges: edges)
    let second = try DagreLayout.computeLayout(nodes: nodes, edges: edges)

    XCTAssertEqual(first.positions, second.positions)
    XCTAssertEqual(first.width, second.width, accuracy: 0.0)
    XCTAssertEqual(first.height, second.height, accuracy: 0.0)
  }

  func testBoundsGuaranteeMarginOnEveryAxis() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("b"), node("c"), node("d"), node("e")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "a", target: "c"),
        edge("e2", source: "c", target: "d"),
        edge("e3", source: "d", target: "e"),
      ]
    )

    for position in output.positions.values {
      XCTAssertGreaterThanOrEqual(position.x, 40.0, "x=\(position.x) < marginx")
      XCTAssertGreaterThanOrEqual(position.y, 40.0, "y=\(position.y) < marginy")
    }
  }

  func testEveryParentHasStrictlyLowerRankThanChild() throws {
    let nodes = [node("a"), node("b"), node("c"), node("d"), node("e")]
    let edges = [
      edge("e0", source: "a", target: "b"),
      edge("e1", source: "a", target: "c"),
      edge("e2", source: "b", target: "d"),
      edge("e3", source: "c", target: "d"),
      edge("e4", source: "d", target: "e"),
    ]
    let g = LayoutGraph.build(nodes: nodes, edges: edges)
    let ranks = try LayerAssigner.assignLayers(graph: g)

    for edgeItem in edges {
      let source = ranks[edgeItem.source] ?? 0
      let target = ranks[edgeItem.target] ?? 0
      XCTAssertGreaterThan(target, source)
    }
  }

  func testNodeWidthAndHeightOptionsAreAccepted() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n1")],
      options: LayoutOptions(nodesep: 20.0, ranksep: 100.0, marginx: 8.0, marginy: 16.0)
    )

    XCTAssertEqual(output.positions["n0"]?.x ?? -1, 8.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n0"]?.y ?? -1, 16.0, accuracy: 0.0)
    // n1 is rank 1: 16 + 64 + ranksep(100) = 180
    XCTAssertEqual(output.positions["n1"]?.y ?? -1, 180.0, accuracy: 0.0)
  }

  func testBtMirrorsRanksSoRootsAreAtTheBottom() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")],
      options: LayoutOptions(rankdir: .bt)
    )

    let root = output.positions["n0"]!
    let mid = output.positions["n1"]!
    let last = output.positions["n2"]!

    XCTAssertEqual(root.x, 40.0, accuracy: 0.0)
    XCTAssertEqual(last.x, 40.0, accuracy: 0.0)
    // Rank 0 sits at the bottom, rank 2 at the top.
    XCTAssertGreaterThan(root.y, mid.y)
    XCTAssertGreaterThan(mid.y, last.y)
    // Same bounds as TB (derived ranksep 32).
    XCTAssertEqual(output.height, 336.0, accuracy: 0.0)
    XCTAssertEqual(output.width, 230.0, accuracy: 0.0)
    // Bottom margin preserved: bottom of root = height - marginy.
    XCTAssertEqual(root.y + 64.0, output.height - 40.0, accuracy: 0.0)
  }

  func testLrTransposesRanksIntoColumns() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")],
      options: LayoutOptions(rankdir: .lr)
    )

    let root = output.positions["n0"]!
    let mid = output.positions["n1"]!
    let last = output.positions["n2"]!

    // Rank 0 leftmost, same row (nodes keep their real height as the
    // within-rank extent in flow space, so all rows sit at y = marginy).
    XCTAssertEqual(root.y, 40.0, accuracy: 0.0)
    XCTAssertEqual(mid.y, root.y, accuracy: 0.0)
    XCTAssertEqual(last.y, mid.y, accuracy: 0.0)
    XCTAssertGreaterThan(mid.x, root.x)
    XCTAssertGreaterThan(last.x, mid.x)
    // Rank rows run along X in flow space with node height 150 per rank and
    // ranksep swapped to the derived nodesep (30): x 40 -> 220 -> 400.
    XCTAssertEqual(mid.x, 220.0, accuracy: 0.0)
    XCTAssertEqual(last.x, 400.0, accuracy: 0.0)
    // Bounds: width = flow height (400 + 150 + 40), height = flow width
    // (40 + 64 + 40).
    XCTAssertEqual(output.width, 590.0, accuracy: 0.0)
    XCTAssertEqual(output.height, 144.0, accuracy: 0.0)
  }

  func testRlMirrorsRanksSoRootsAreAtTheRight() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n1")],
      options: LayoutOptions(rankdir: .rl)
    )

    let root = output.positions["n0"]!
    let last = output.positions["n1"]!

    XCTAssertEqual(root.y, last.y, accuracy: 0.0)
    XCTAssertGreaterThan(root.x, last.x)
    // Right margin preserved: right edge of root = width - marginx.
    XCTAssertEqual(root.x + 150.0, output.width - 40.0, accuracy: 0.0)
  }

  func testLrKeepsMarginsCanvasSpace() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n1")],
      options: LayoutOptions(rankdir: .lr, marginx: 8.0, marginy: 16.0)
    )

    // marginx (8) is the left/right margin in canvas space...
    XCTAssertEqual(output.positions["n0"]?.x ?? -1, 8.0, accuracy: 0.0)
    XCTAssertEqual(output.positions["n0"]?.y ?? -1, 16.0, accuracy: 0.0)
    // ...and marginy (16) the top/bottom margin: bottom of the n0 row =
    // height - marginy.
    XCTAssertEqual(
      (output.positions["n0"]?.y ?? 0.0) + 64.0,
      output.height - 16.0,
      accuracy: 0.0
    )
    // n1 sits one rank right of n0: 8 + 150 + nodesep-swapped ranksep (30).
    XCTAssertEqual(output.positions["n1"]?.x ?? -1, 188.0, accuracy: 0.0)
    // Bounds in flow space: width = maxY(188 + 150) + marginy(8).
    XCTAssertEqual(output.width, 346.0, accuracy: 0.0)
  }

  func testRealNodeDimensionsDriveSpacingInEveryDirection() throws {
    let nodes = [node("a", width: 120.0, height: 80.0), node("b", width: 200.0, height: 48.0)]
    let edges = [edge("e0", source: "a", target: "b")]

    let tb = try DagreLayout.computeLayout(nodes: nodes, edges: edges)
    // Spacing derived from the canonical default node size (150 x 64 -> 30/32):
    // rank heights are a 80, b 48 -> b.y = 40 + 80 + 32 = 152.
    XCTAssertEqual(tb.positions["a"]?.y ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(tb.positions["b"]?.y ?? -1, 152.0, accuracy: 0.0)
    // Real dims drive spacing: a's subtree is 200 wide (b), so a is centered
    // in it: 40 + (200 - 120) / 2 = 80; b is centered under a: 40.
    XCTAssertEqual(tb.positions["a"]?.x ?? -1, 80.0, accuracy: 0.0)
    XCTAssertEqual(tb.positions["b"]?.x ?? -1, 40.0, accuracy: 0.0)

    let lr = try DagreLayout.computeLayout(
      nodes: nodes,
      edges: edges,
      options: LayoutOptions(rankdir: .lr)
    )
    // In LR the node heights become the within-rank (Y) extent (a 80, b 48).
    // a sits at y = marginy; b is centered under a's flow row: parent center
    // 40 + 40 = 80, children total 48 -> b.y = 56. Rank separation along X
    // uses the swapped derived ranksep (canonical nodesep 30): 40 + 120 + 30 =
    // 190 (not the per-node average, which produced 192).
    XCTAssertEqual(lr.positions["a"]?.y ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(lr.positions["b"]?.y ?? -1, 56.0, accuracy: 0.0)
    XCTAssertEqual(lr.positions["b"]?.x ?? -1, 190.0, accuracy: 0.0)
    // Canvas bounds: width = maxY (190 + 200) + marginy (40); height = maxX
    // (max of a: 40 + 80 and b: 56 + 48 = 120) + marginx (40).
    XCTAssertEqual(lr.width, 430.0, accuracy: 0.0)
    XCTAssertEqual(lr.height, 160.0, accuracy: 0.0)
  }

  func testSpacingIsDerivedFromCanonicalDefaultNodeSize() throws {
    let nodes = [node("a", width: 100.0, height: 50.0), node("b", width: 200.0, height: 100.0)]
    let edges = [edge("e0", source: "a", target: "b")]

    // Canonical default dims 150 x 64 -> nodesep ceil(30) = 30, ranksep 32
    // regardless of the per-node sizes in the graph.
    let tb = try DagreLayout.computeLayout(nodes: nodes, edges: edges)
    XCTAssertEqual(tb.positions["a"]?.y ?? -1, 40.0, accuracy: 0.0)
    // 40 + rank-0 height (a is 50 tall) + ranksep (32) = 122.
    XCTAssertEqual(tb.positions["b"]?.y ?? -1, 122.0, accuracy: 0.0)

    let lr = try DagreLayout.computeLayout(
      nodes: nodes,
      edges: edges,
      options: LayoutOptions(rankdir: .lr)
    )
    // Rank separation along X: 40 + a's flow height (real width 100) + the
    // swapped derived ranksep (canonical nodesep 30) = 170.
    XCTAssertEqual(lr.positions["b"]?.x ?? -1, 170.0, accuracy: 0.0)
  }

  func testDimlessNodesUseCanonicalDerivedSpacing() throws {
    func zero(_ id: String) -> LayoutNode { node(id, width: 0.0, height: 0.0) }

    // Zero-dimension nodes fall back to dims 0 x 0, but the spacing still
    // derives from the canonical default node size: ranksep 32, nodesep 30.
    let chain = try DagreLayout.computeLayout(
      nodes: [zero("a"), zero("b"), zero("c")],
      edges: [edge("e0", source: "a", target: "b"), edge("e1", source: "b", target: "c")]
    )
    XCTAssertEqual(chain.positions["a"]?.y ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(chain.positions["b"]?.y ?? -1, 72.0, accuracy: 0.0)
    XCTAssertEqual(chain.positions["c"]?.y ?? -1, 104.0, accuracy: 0.0)
    XCTAssertEqual(chain.height, 144.0, accuracy: 0.0)

    // ...and canonical nodesep 30 between siblings.
    let fork = try DagreLayout.computeLayout(
      nodes: [zero("r"), zero("a"), zero("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    XCTAssertEqual(fork.positions["a"]?.x ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(fork.positions["b"]?.x ?? -1, 70.0, accuracy: 0.0)
  }

  func testExplicitSpacingOverridesDerivedDefaults() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n1")],
      options: LayoutOptions(nodesep: 5.0, ranksep: 10.0)
    )

    // Derived would be 30/32; explicit values must win.
    XCTAssertEqual(output.positions["n1"]?.y ?? -1, 114.0, accuracy: 0.0)
  }

  // --- Stacking edges (declared perpendicular port sides) ---

  func testLRStacksBottomSideChildDirectlyBelowSource() throws {
    // ai-out style: a BOTTOM-side edge in LR must not push the target into
    // the next column; it stacks in the same column, one gap below.
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("s")],
      edges: [edge("e0", source: "a", target: "s", sourcePortSide: .bottom)],
      options: LayoutOptions(rankdir: .lr)
    )

    let a = output.positions["a"]!
    let s = output.positions["s"]!
    XCTAssertEqual(a.x, s.x, accuracy: 0.0)
    // Canonical nodesep 30 (LR uses nodesep as the cross-rank gap).
    XCTAssertEqual(a.y + 64.0 + 30.0, s.y, accuracy: 0.0)
  }

  func testStackedSiblingsFormACenteredRowAlongTheFlowAxis() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("s1"), node("s2")],
      edges: [
        edge("e0", source: "a", target: "s1", sourcePortSide: .bottom),
        edge("e1", source: "a", target: "s2", sourcePortSide: .bottom),
      ],
      options: LayoutOptions(rankdir: .lr)
    )

    let a = output.positions["a"]!
    let s1 = output.positions["s1"]!
    let s2 = output.positions["s2"]!
    // Direct children of one source keep the SAME perpendicular offset (a
    // row band below the source) and spread along the flow axis as a group
    // CENTERED on the source: the row's midpoint equals the source's center
    // (115), so additions never drift the row off its connection. Along
    // canvas X a node's extent is its width (150); the row gap is the
    // cross-rank gap (canonical nodesep 30 in LR) → total 330, start -50.
    XCTAssertEqual(s1.y, s2.y, accuracy: 0.0)
    XCTAssertEqual(a.y + 64.0 + 30.0, s1.y, accuracy: 0.0)
    XCTAssertEqual(s1.x + 150.0 + 30.0, s2.x, accuracy: 0.0)
    let rowCenter = (s1.x + s2.x + 150.0) / 2.0
    XCTAssertEqual(a.x + 75.0, rowCenter, accuracy: 0.0)
  }

  func testTBStacksRightSideChildBesideSource() throws {
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("s")],
      edges: [edge("e0", source: "a", target: "s", sourcePortSide: .right)]
    )

    let a = output.positions["a"]!
    let s = output.positions["s"]!
    XCTAssertEqual(a.y, s.y, accuracy: 0.0)
    // Canonical ranksep 32.
    XCTAssertEqual(a.x + 150.0 + 32.0, s.x, accuracy: 0.0)
  }

  func testALongStackedRowDoesNotPushTheNextRank() throws {
    // a → b is the flow edge (b one rank downstream); a → s1/s2 is the
    // stacked row. The row lives in its own band beside a and must never
    // widen a's rank band: b keeps its regular one-gap placement no matter
    // how long the row grows (the respond-node regression).
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("b"), node("s1"), node("s2")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "a", target: "s1", sourcePortSide: .bottom),
        edge("e2", source: "a", target: "s2", sourcePortSide: .bottom),
      ],
      options: LayoutOptions(rankdir: .lr)
    )

    let a = output.positions["a"]!
    let b = output.positions["b"]!
    // Canvas X is the flow rank axis: b sits one max-flow-extent (150) plus
    // the cross-rank gap (30) past a — identical to a graph with no row.
    XCTAssertEqual(a.x + 150.0 + 30.0, b.x, accuracy: 0.0)
  }

  func testStackingChainsStackTransitively() throws {
    // A stacked target with its own bottom-side child stacks both.
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("s1"), node("s2")],
      edges: [
        edge("e0", source: "a", target: "s1", sourcePortSide: .bottom),
        edge("e1", source: "s1", target: "s2", sourcePortSide: .bottom),
      ],
      options: LayoutOptions(rankdir: .lr)
    )

    let s1 = output.positions["s1"]!
    let s2 = output.positions["s2"]!
    XCTAssertEqual(s1.x, s2.x, accuracy: 0.0)
    XCTAssertEqual(s1.y + 64.0 + 30.0, s2.y, accuracy: 0.0)
  }

  func testFlowChildrenOfStackedTargetCenterUnderIt() throws {
    // A stacked node is a real parent for flow edges: its child must center
    // under the stacked position, not fall back to the margin.
    let output = try DagreLayout.computeLayout(
      nodes: [node("a"), node("s"), node("c")],
      edges: [
        edge("e0", source: "a", target: "s", sourcePortSide: .bottom),
        edge("e1", source: "s", target: "c"),
      ],
      options: LayoutOptions(rankdir: .lr)
    )

    let s = output.positions["s"]!
    let c = output.positions["c"]!
    // In LR the canvas column is Y (flow X); the child must center under the
    // stacked parent's column, not fall back to its row.
    XCTAssertEqual(s.y, c.y, accuracy: 0.0)
  }
}
