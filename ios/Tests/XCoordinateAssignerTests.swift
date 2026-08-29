import XCTest
@testable import ExpoSugiyama

/// Stage 5 tests (spec A.10): parent centering, multi-child spacing,
/// orphan placement, and the no-overlap property.
final class XCoordinateAssignerTests: XCTestCase {
  private let nodesep = 60.0
  private let marginx = 40.0

  private func positionsOf(
    _ g: LayoutGraph,
    ranks: [String: Int]? = nil
  ) throws -> [String: Double] {
    let manager = LayerManager.build(graph: g, ranks: try ranks ?? LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager)
    return XCoordinateAssigner.assignXCoordinates(
      graph: g,
      layerManager: manager,
      widthAllocation: allocation,
      nodesep: nodesep,
      marginx: marginx
    )
  }

  func testSingleRootStartsAtMargin() throws {
    let g = LayoutGraph.build(nodes: [node("s")], edges: [])
    let positions = try positionsOf(g)

    XCTAssertEqual(positions["s"] ?? -1, marginx, accuracy: 0.0)
  }

  func testChainChildCentersUnderParent() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let positions = try positionsOf(g)

    XCTAssertEqual(positions["n0"] ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(positions["n1"] ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(positions["n2"] ?? -1, 40.0, accuracy: 0.0)
  }

  func testForkRootCentersOverChildren() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "r", target: "a"),
        edge("e1", source: "r", target: "b"),
        edge("e2", source: "r", target: "c"),
      ]
    )
    let positions = try positionsOf(g)

    // subtreeWidth(r) = 150*3 + 2*60 = 570 -> x = 40 + (570-150)/2 = 250
    XCTAssertEqual(positions["r"] ?? -1, 250.0, accuracy: 0.0)
    XCTAssertEqual(positions["a"] ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(positions["b"] ?? -1, 250.0, accuracy: 0.0)
    XCTAssertEqual(positions["c"] ?? -1, 460.0, accuracy: 0.0)
  }

  func testParentlessNodeInLowerRankPlacedAtMargin() throws {
    let g = LayoutGraph.build(nodes: [node("x"), node("y")], edges: [])
    let positions = try positionsOf(g, ranks: ["x": 0, "y": 1])

    XCTAssertEqual(positions["x"] ?? -1, marginx, accuracy: 0.0)
    XCTAssertEqual(positions["y"] ?? -1, marginx, accuracy: 0.0)
  }

  func testSiblingsNeverOverlapWithinRank() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b"), node("c"), node("d")],
      edges: [
        edge("e0", source: "r", target: "a"),
        edge("e1", source: "r", target: "b"),
        edge("e2", source: "r", target: "c"),
        edge("e3", source: "r", target: "d"),
      ]
    )
    let positions = try positionsOf(g)

    let siblings = ["a", "b", "c", "d"]
    for i in 1..<siblings.count {
      let left = positions[siblings[i - 1]] ?? -1
      let right = positions[siblings[i]] ?? -1
      XCTAssertGreaterThanOrEqual(right, left + 150.0)
    }
  }

  func testCustomNodesepAndMarginAreHonored() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager, nodesep: 20.0)
    let positions = XCoordinateAssigner.assignXCoordinates(
      graph: g,
      layerManager: manager,
      widthAllocation: allocation,
      nodesep: 20.0,
      marginx: 8.0
    )

    // subtreeWidth(r) = 150*2 + 20 = 320 -> x = 8 + (320-150)/2 = 93
    XCTAssertEqual(positions["a"] ?? -1, 8.0, accuracy: 0.0)
    XCTAssertEqual(positions["r"] ?? -1, 93.0, accuracy: 0.0)
    XCTAssertEqual(positions["b"] ?? -1, 178.0, accuracy: 0.0)
  }

  func testLayoutWidthIncludesExtentsPlusMargin() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager)

    // widest extent: rightmost child at 250 + 150 = 400 -> 400 + 40
    XCTAssertEqual(
      XCoordinateAssigner.computeLayoutWidth(graph: g, layerManager: manager, widthAllocation: allocation),
      440.0,
      accuracy: 0.0
    )
  }
}
