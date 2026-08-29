import XCTest
@testable import ExpoSugiyama

/// Layer manager tests (spec A.12): grouping by rank, sorted ranks,
/// deterministic initial ordering, and ordering updates.
final class LayerManagerTests: XCTestCase {
  private func ranksOf(_ g: LayoutGraph) throws -> [String: Int] {
    return try LayerAssigner.assignLayers(graph: g)
  }

  func testBuildsOrderedLayerNodesSortedById() throws {
    let g = LayoutGraph.build(
      nodes: [node("b"), node("d"), node("a"), node("c"), node("x")],
      edges: [
        edge("e0", source: "b", target: "x"),
        edge("e1", source: "d", target: "x"),
        edge("e2", source: "a", target: "x"),
        edge("e3", source: "c", target: "x"),
      ]
    )
    let manager = LayerManager.build(graph: g, ranks: try ranksOf(g))

    XCTAssertEqual(manager.layers[0]?.nodes, ["a", "b", "c", "d"])
    XCTAssertEqual(manager.layers[1]?.nodes, ["x"])
    XCTAssertEqual(manager.ordering["c"], 2)
    XCTAssertEqual(manager.ordering["d"], 3)
  }

  func testComputesSortedRanksAndMaxRank() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2"), node("n3")],
      edges: [
        edge("e0", source: "n0", target: "n1"),
        edge("e1", source: "n1", target: "n3"),
        edge("e2", source: "n2", target: "n3"),
      ]
    )
    let manager = LayerManager.build(graph: g, ranks: try ranksOf(g))

    XCTAssertEqual(manager.sortedRanks, [0, 1, 2])
    XCTAssertEqual(manager.maxRank, 2)
    XCTAssertEqual(manager.ranks["n0"], 0)
    XCTAssertEqual(manager.ranks["n3"], 2)
  }

  func testUpdateOrderingResortsLayers() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("c"), node("s")],
      edges: [
        edge("e0", source: "a", target: "s"),
        edge("e1", source: "b", target: "s"),
        edge("e2", source: "c", target: "s"),
      ]
    )
    let manager = LayerManager.build(graph: g, ranks: try ranksOf(g))

    let reordered = LayerManager.updateOrdering(
      manager,
      newOrdering: ["a": 2, "b": 0, "c": 1, "s": 0]
    )

    XCTAssertEqual(reordered.layers[0]?.nodes, ["b", "c", "a"])
    XCTAssertEqual(reordered.ordering["b"], 0)
    XCTAssertEqual(reordered.ordering["a"], 2)
    XCTAssertEqual(manager.ranks, reordered.ranks)
    XCTAssertEqual(manager.sortedRanks, reordered.sortedRanks)
  }

  func testGettersReturnNodeRankOrderAndAdjacentLayer() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b")],
      edges: [edge("e0", source: "a", target: "b")]
    )
    let manager = LayerManager.build(graph: g, ranks: try ranksOf(g))

    XCTAssertEqual(LayerManager.getNodeRank(manager, nodeId: "a"), 0)
    XCTAssertEqual(LayerManager.getNodeOrder(manager, nodeId: "a"), 0)
    XCTAssertEqual(LayerManager.getNodeRank(manager, nodeId: "b"), 1)
    XCTAssertEqual(LayerManager.getLayerNodes(manager, rank: 0), ["a"])
    XCTAssertEqual(LayerManager.getAdjacentLayer(manager, rank: 0, direction: .below), ["b"])
    XCTAssertEqual(LayerManager.getAdjacentLayer(manager, rank: 0, direction: .above), [])
  }
}
