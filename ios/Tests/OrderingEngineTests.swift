import XCTest
@testable import ExpoSugiyama

/// Stage 3 tests (spec A.8): crossing reduction on a known bipartite DAG,
/// determinism across input order, pass-count behavior, and ID tie-breaks.
final class OrderingEngineTests: XCTestCase {
  func testMedianOrderingRemovesCrossings() throws {
    // rank0 [a,b,c] x rank1 [d,e,f]; reversed connections create 3 crossings
    let g = graph(("a", "f"), ("b", "e"), ("c", "d"))
    let base = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let initial = OrderingEngine.countTotalCrossings(graph: g, layerManager: base)
    XCTAssertEqual(initial, 3)

    let ordered = OrderingEngine.orderNodes(graph: g, layerManager: base, passes: 4)
    XCTAssertEqual(OrderingEngine.countTotalCrossings(graph: g, layerManager: ordered), 0)
  }

  func testDeterministicAcrossNodeInputOrder() throws {
    let edgePairs: [(String, String)] = [("a", "d"), ("a", "e"), ("b", "e"), ("c", "e"), ("c", "f")]
    let nodeIds = edgePairs.flatMap { [$0.0, $0.1] }.distinctOrdered()
    let edges = edgePairs.enumerated().map { edge("e\($0.offset)", source: $0.element.0, target: $0.element.1) }

    let g1 = LayoutGraph.build(nodes: nodeIds.map { node($0) }, edges: edges)
    let g2 = LayoutGraph.build(
      nodes: nodeIds.reversed().map { node($0) },
      edges: edges.reversed().enumerated().map { edge("e\($0.offset)", source: $0.element.source, target: $0.element.target) }
    )

    let o1 = OrderingEngine.orderNodes(graph: g1, layerManager: LayerManager.build(graph: g1, ranks: try LayerAssigner.assignLayers(graph: g1)))
    let o2 = OrderingEngine.orderNodes(graph: g2, layerManager: LayerManager.build(graph: g2, ranks: try LayerAssigner.assignLayers(graph: g2)))

    XCTAssertEqual(o1.ordering, o2.ordering)
    XCTAssertEqual(o1.layers, o2.layers)
  }

  func testZeroPassesKeepsInitialIdOrdering() throws {
    let g = graph(("a", "f"), ("b", "e"), ("c", "d"))
    let base = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let unchanged = OrderingEngine.orderNodes(graph: g, layerManager: base, passes: 0)

    XCTAssertEqual(base.ordering, unchanged.ordering)
    XCTAssertEqual(base.layers, unchanged.layers)
  }

  func testEqualMediansTieBreakByNodeId() throws {
    // Complete bipartite K2,2: both rank-1 nodes have identical medians
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("x"), node("y")],
      edges: [
        edge("e0", source: "a", target: "x"),
        edge("e1", source: "b", target: "x"),
        edge("e2", source: "a", target: "y"),
        edge("e3", source: "b", target: "y"),
      ]
    )
    let ordered = OrderingEngine.orderNodes(
      graph: g,
      layerManager: LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g)),
      passes: 2
    )

    // Ties resolve by node ID order, not input/edge order
    XCTAssertEqual(ordered.layers[0]?.nodes, ["a", "b"])
    XCTAssertEqual(ordered.layers[1]?.nodes, ["x", "y"])
    // K2,2 has exactly one unavoidable crossing
    XCTAssertEqual(OrderingEngine.countTotalCrossings(graph: g, layerManager: ordered), 1)
  }

  func testUpDownSweepReducesCrossingsInDeepGraph() throws {
    let g = LayoutGraph.build(
      nodes: [
        node("a"), node("b"), node("c"),
        node("d"), node("e"), node("f"),
        node("g"), node("h"), node("i"),
      ],
      edges: [
        edge("e0", source: "a", target: "f"),
        edge("e1", source: "a", target: "e"),
        edge("e2", source: "b", target: "e"),
        edge("e3", source: "c", target: "e"),
        edge("e4", source: "c", target: "d"),
        edge("e5", source: "d", target: "i"),
        edge("e6", source: "e", target: "h"),
        edge("e7", source: "f", target: "g"),
      ]
    )
    let base = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let initial = OrderingEngine.countTotalCrossings(graph: g, layerManager: base)
    XCTAssertEqual(initial, 8)

    let ordered = OrderingEngine.orderNodes(graph: g, layerManager: base, passes: 4)
    let final = OrderingEngine.countTotalCrossings(graph: g, layerManager: ordered)

    XCTAssertEqual(final, 0)
    XCTAssertLessThan(final, initial)
  }

  func testFanOutOrdersTargetsBySourcePortIndex() throws {
    // qual-yes declared before qual-no → its connector is left of qual-no's,
    // so its target must land left of the other (zero crossings, matches the
    // visible connector sides).
    let g = LayoutGraph.build(
      nodes: [node("qualify"), node("score"), node("nurture")],
      edges: [
        edge("e0", source: "qualify", target: "score", sourcePortIndex: 1),
        edge("e1", source: "qualify", target: "nurture", sourcePortIndex: 2),
      ]
    )

    XCTAssertEqual(g.incomingPortIndex["score"], 1, "min incoming source-port index is tracked")
    XCTAssertEqual(g.incomingPortIndex["nurture"], 2)

    let ordered = OrderingEngine.orderNodes(
      graph: g,
      layerManager: LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    )
    XCTAssertEqual(ordered.layers[1]?.nodes, ["score", "nurture"])
    XCTAssertEqual(OrderingEngine.countTotalCrossings(graph: g, layerManager: ordered), 0)
  }

  func testHubTargetsSharePortIndexAndTieBreakByNodeId() throws {
    // Two edges leave the SAME hub connector (notify-out → email + push) so
    // their keys are equal; order falls back to node ID determinism.
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "a", target: "b", sourcePortIndex: 1),
        edge("e1", source: "a", target: "c", sourcePortIndex: 1),
      ]
    )

    let ordered = OrderingEngine.orderNodes(
      graph: g,
      layerManager: LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    )
    XCTAssertEqual(g.incomingPortIndex["b"], 1)
    XCTAssertEqual(ordered.layers[1]?.nodes, ["b", "c"])
  }

  func testDiamondTargetUsesMinIncomingPortIndex() throws {
    // Multi-parent node (diamond): key = min port index across ALL incoming
    // edges, still deterministic.
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("x")],
      edges: [
        edge("e0", source: "a", target: "x", sourcePortIndex: 2),
        edge("e1", source: "b", target: "x", sourcePortIndex: 3),
      ]
    )

    XCTAssertEqual(g.incomingPortIndex["x"], 2)
  }

  func testNodesWithoutIncomingEdgesAbsentFromPortIndexMap() {
    let g = LayoutGraph.build(
      nodes: [node("root"), node("leaf")],
      edges: [edge("e0", source: "root", target: "leaf", sourcePortIndex: 1)]
    )

    XCTAssertEqual(g.incomingPortIndex["leaf"], 1)
    XCTAssertNil(g.incomingPortIndex["root"])
  }
}

extension Array where Element == String {
  func distinctOrdered() -> [String] {
    var result: [String] = []
    var seen: Set<String> = []
    for element in self {
      if !seen.contains(element) {
        seen.insert(element)
        result.append(element)
      }
    }
    return result
  }
}
