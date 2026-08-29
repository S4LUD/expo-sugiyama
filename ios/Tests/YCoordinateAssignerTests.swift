import XCTest
@testable import ExpoSugiyama

/// Stage 6 tests (spec A.11): rank rows, per-rank max height handling,
/// custom margins/ranksep, and the rank-Y lookup.
final class YCoordinateAssignerTests: XCTestCase {
  private let ranksep = 80.0
  private let marginy = 40.0

  private func positionsOf(
    _ g: LayoutGraph,
    ranksep: Double = 80.0,
    marginy: Double = 40.0
  ) throws -> [String: Double] {
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager)
    return YCoordinateAssigner.assignYCoordinates(
      layerManager: manager,
      widthAllocation: allocation,
      ranksep: ranksep,
      marginy: marginy
    )
  }

  func testRankRowsAdvanceByMaxHeightPlusRanksep() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let positions = try positionsOf(g)

    XCTAssertEqual(positions["n0"] ?? -1, 40.0, accuracy: 0.0)
    XCTAssertEqual(positions["n1"] ?? -1, 184.0, accuracy: 0.0) // 40 + 64 + 80
    XCTAssertEqual(positions["n2"] ?? -1, 328.0, accuracy: 0.0) // 184 + 64 + 80
  }

  func testMaxHeightPerRankDrivesSpacing() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0", height: 100.0), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    // The pipeline uses explicit dimensions when provided (custom node dims)
    let allocation = WidthAllocator.allocateWidths(
      graph: g,
      layerManager: manager,
      nodeDimensions: ["n0": NodeDimensions(width: 150.0, height: 100.0)]
    )
    let positions = YCoordinateAssigner.assignYCoordinates(
      layerManager: manager,
      widthAllocation: allocation,
      ranksep: ranksep,
      marginy: marginy
    )

    // rank 0 max height = 100 -> rank 1 at 40 + 100 + 80 = 220
    XCTAssertEqual(positions["n1"] ?? -1, 220.0, accuracy: 0.0)
    XCTAssertEqual(positions["n2"] ?? -1, 364.0, accuracy: 0.0) // 220 + 64 + 80
  }

  func testCustomRanksepAndMarginyAreHonored() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1")],
      edges: [edge("e0", source: "n0", target: "n1")]
    )
    let positions = try positionsOf(g, ranksep: 50.0, marginy: 0.0)

    XCTAssertEqual(positions["n0"] ?? -1, 0.0, accuracy: 0.0)
    XCTAssertEqual(positions["n1"] ?? -1, 114.0, accuracy: 0.0) // 0 + 64 + 50
  }

  func testRankYLookupMatchesAssignedRows() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager)
    let assigned = YCoordinateAssigner.assignYCoordinates(
      layerManager: manager,
      widthAllocation: allocation,
      ranksep: ranksep,
      marginy: marginy
    )

    for rank in manager.sortedRanks {
      let expected = YCoordinateAssigner.getRankYCoordinate(
        layerManager: manager,
        rank: rank,
        widthAllocation: allocation,
        ranksep: ranksep,
        marginy: marginy
      )
      for nodeId in LayerManager.getLayerNodes(manager, rank: rank) {
        XCTAssertEqual(assigned[nodeId] ?? -1, expected, accuracy: 0.0)
      }
    }
  }

  func testLayoutHeightAccumulatesAllRanks() throws {
    let g = LayoutGraph.build(
      nodes: [node("n0"), node("n1"), node("n2")],
      edges: [edge("e0", source: "n0", target: "n1"), edge("e1", source: "n1", target: "n2")]
    )
    let manager = LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: manager)

    // 40 + (64+80) + (64+80) + (64+80) - 80 + 40 = 432
    XCTAssertEqual(
      YCoordinateAssigner.computeLayoutHeight(layerManager: manager, widthAllocation: allocation),
      432.0,
      accuracy: 0.0
    )
  }
}
