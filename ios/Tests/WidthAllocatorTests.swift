import XCTest
@testable import ExpoSugiyama

/// Stage 4 tests (spec A.9): leaf vs parent widths, multi-level subtrees,
/// nodesep effects, and custom dimensions.
final class WidthAllocatorTests: XCTestCase {
  private func managerOf(_ g: LayoutGraph) throws -> LayerManager {
    return LayerManager.build(graph: g, ranks: try LayerAssigner.assignLayers(graph: g))
  }

  func testLeafSubtreeWidthEqualsNodeWidth() throws {
    let g = LayoutGraph.build(nodes: [node("leaf")], edges: [])
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g))

    XCTAssertEqual(allocation.subtreeWidths["leaf"] ?? -1, 150.0, accuracy: 0.0)
  }

  func testParentSubtreeWidthSumsChildren() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g))

    // 150 + 150 + nodesep(60) = 360
    XCTAssertEqual(allocation.subtreeWidths["r"] ?? -1, 360.0, accuracy: 0.0)
    XCTAssertEqual(allocation.subtreeWidths["a"] ?? -1, 150.0, accuracy: 0.0)
  }

  func testMultiLevelSubtreeIsSumOfLeafDescendants() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "r", target: "a"),
        edge("e1", source: "a", target: "b"),
        edge("e2", source: "a", target: "c"),
      ]
    )
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g))

    // b, c: 150 each; a: 150 + 150 + 60 = 360; r: 360
    XCTAssertEqual(allocation.subtreeWidths["a"] ?? -1, 360.0, accuracy: 0.0)
    XCTAssertEqual(allocation.subtreeWidths["r"] ?? -1, 360.0, accuracy: 0.0)
  }

  func testNodesepIncreasesParentWidth() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let tight = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g), nodesep: 20.0)
    let wide = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g), nodesep: 100.0)

    XCTAssertEqual(tight.subtreeWidths["r"] ?? -1, 320.0, accuracy: 0.0)
    XCTAssertEqual(wide.subtreeWidths["r"] ?? -1, 400.0, accuracy: 0.0)
  }

  func testCustomDimensionsAreApplied() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b")],
      edges: [edge("e0", source: "r", target: "a"), edge("e1", source: "r", target: "b")]
    )
    let custom: [String: NodeDimensions] = [
      "a": NodeDimensions(width: 200.0, height: 90.0),
      "b": NodeDimensions(width: 200.0, height: 90.0),
    ]
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g), nodeDimensions: custom)

    XCTAssertEqual(allocation.subtreeWidths["a"] ?? -1, 200.0, accuracy: 0.0)
    // 200 + 200 + 60 = 460
    XCTAssertEqual(allocation.subtreeWidths["r"] ?? -1, 460.0, accuracy: 0.0)
    XCTAssertEqual(allocation.dimensions["b"]?.height ?? -1, 90.0, accuracy: 0.0)
    // Defaults still apply to the unlisted node
    XCTAssertEqual(allocation.dimensions["r"]?.width ?? -1, 150.0, accuracy: 0.0)
  }

  func testChildrenWidthComputesAreaForParent() throws {
    let g = LayoutGraph.build(
      nodes: [node("r"), node("a"), node("b"), node("c")],
      edges: [
        edge("e0", source: "r", target: "a"),
        edge("e1", source: "r", target: "b"),
        edge("e2", source: "r", target: "c"),
      ]
    )
    let allocation = WidthAllocator.allocateWidths(graph: g, layerManager: try managerOf(g))

    // 150*3 + 2*60 = 570
    XCTAssertEqual(WidthAllocator.computeChildrenWidth(allocation, childrenIds: ["a", "b", "c"]), 570.0, accuracy: 0.0)
    XCTAssertEqual(WidthAllocator.computeChildrenWidth(allocation, childrenIds: []), 0.0, accuracy: 0.0)
  }
}
