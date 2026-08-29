import XCTest
@testable import ExpoSugiyama

/// Performance gate (spec §17.1): a 1000-node layered DAG must lay out in
/// under 250ms. Runs in the SwiftPM harness on the host / simulator; the
/// device-class threshold is benchmarked separately in CI (Appendix D).
/// Mirror of the Android DagreLayoutBenchmarkTest.
final class DagreLayoutBenchmarkTests: XCTestCase {
  func testLayout1000NodesUnder250ms() throws {
    let cols = 25
    let layers = 40
    let id: (Int, Int) -> String = { c, l in "n\(l)_\(c)" }

    var nodes: [LayoutNode] = []
    for l in 0..<layers {
      for c in 0..<cols {
        nodes.append(LayoutNode(id: id(c, l), width: 150.0, height: 64.0))
      }
    }

    var edges: [LayoutEdge] = []
    var edgeIndex = 0
    for l in 0..<(layers - 1) {
      for c in 0..<cols {
        edges.append(LayoutEdge(id: "e\(edgeIndex)", source: id(c, l), target: id(c, l + 1)))
        edgeIndex += 1
        edges.append(
          LayoutEdge(id: "e\(edgeIndex)", source: id(c, l), target: id((c + 1) % cols, l + 1))
        )
        edgeIndex += 1
      }
    }

    // Warm up before measuring
    _ = try DagreLayout.computeLayout(nodes: nodes, edges: edges)

    let start = DispatchTime.now()
    let output = try DagreLayout.computeLayout(nodes: nodes, edges: edges)
    let elapsedMs =
      Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000

    XCTAssertEqual(output.positions.count, 1000)
    XCTAssertTrue(
      elapsedMs < 250.0,
      "1000-node layout took \(elapsedMs) ms (target < 250ms)"
    )
  }
}