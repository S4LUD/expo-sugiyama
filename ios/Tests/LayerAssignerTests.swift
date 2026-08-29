import XCTest
@testable import ExpoSugiyama

/// Stage 2 tests (spec A.7): chain, fork, join, diamond, disconnected
/// components, multiple roots, and both cycle policies.
final class LayerAssignerTests: XCTestCase {
  func testAssignsChainRanks() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["n0"], 0)
    XCTAssertEqual(ranks["n1"], 1)
    XCTAssertEqual(ranks["n2"], 2)
  }

  func testAssignsForkRanks() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["r"], 0)
    XCTAssertEqual(ranks["a"], 1)
    XCTAssertEqual(ranks["b"], 1)
  }

  func testAssignsJoinRanks() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("s")],
      edges: [edge("e0", source: "a", target: "s"), edge("e1", source: "b", target: "s")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["a"], 0)
    XCTAssertEqual(ranks["b"], 0)
    XCTAssertEqual(ranks["s"], 1)
  }

  func testAssignLongestPathForMultiParents() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("c"), node("d")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "a", target: "c"),
        edge("e2", source: "c", target: "d"),
        edge("e3", source: "b", target: "d"),
      ]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["a"], 0)
    XCTAssertEqual(ranks["b"], 1)
    XCTAssertEqual(ranks["c"], 1)
    XCTAssertEqual(ranks["d"], 2)
  }

  func testHandlesDisconnectedComponents() throws {
    let g = LayoutGraph.build(
      nodes: [node("p0"), node("p1"), node("q0"), node("q1")],
      edges: [edge("e0", source: "p0", target: "p1"), edge("e1", source: "q0", target: "q1")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["p0"], 0)
    XCTAssertEqual(ranks["p1"], 1)
    XCTAssertEqual(ranks["q0"], 0)
    XCTAssertEqual(ranks["q1"], 1)
  }

  func testProducesDeterministicRanks() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("c"), node("d")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "b", target: "d"),
        edge("e2", source: "a", target: "c"),
      ]
    )
    let first = try LayerAssigner.assignLayers(graph: g)
    let second = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(first, second)
  }

  func testSkipsMissingNodeAndSelfLoopEdges() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n0"), edge("e1", source: "n1", target: "ghost")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g)
    XCTAssertEqual(ranks["n0"], 0)
    XCTAssertEqual(ranks["n1"], 0)
  }

  func testTreatsCyclesAsRankZeroInTolerantMode() throws {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b")],
      edges: [edge("e0", source: "a", target: "b"), edge("e1", source: "b", target: "a")]
    )
    let ranks = try LayerAssigner.assignLayers(graph: g, cyclePolicy: .tolerant)
    // DFS longest-path: b = max(rank(a)=0)+1 = 1; a = max(rank(b)=1)+1 = 2
    XCTAssertEqual(ranks["a"], 2)
    XCTAssertEqual(ranks["b"], 1)
  }

  func testThrowsOnCycleInStrictMode() {
    let g = LayoutGraph.build(
      nodes: [node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "a", target: "b"),
        edge("e1", source: "b", target: "c"),
        edge("e2", source: "c", target: "a"),
      ]
    )
    XCTAssertThrowsError(try LayerAssigner.assignLayers(graph: g, cyclePolicy: .throwing)) { error in
      guard let cycleError = error as? LayoutCycleException else {
        return XCTFail("Expected LayoutCycleException, got \(error)")
      }
      XCTAssertTrue(cycleError.message.contains("Cycle detected during layer assignment"))
    }
  }
}
