import Foundation

/// Sweep direction for an ordering pass (mirrors `ordering-engine.ts`).
enum SweepDirection {
  case down
  case up
}

/// Stage 3 - Within-layer ordering using the median heuristic
/// (line-for-line port of `features/layout-engine/ordering-engine.ts`,
/// spec §11.4).
///
/// Multiple passes alternate sweep direction (down/up). Each layer is sorted
/// by the median position of its neighbors in the adjacent layer; ties break
/// by the connector side of the incoming edge (min source-port index), then by
/// node ID. Nodes without neighbors keep their current position.
enum OrderingEngine {
  private static let defaultPasses = 4

  static func orderNodes(
    graph: LayoutGraph,
    layerManager: LayerManager,
    passes: Int = defaultPasses,
    initialDirection: SweepDirection = .down
  ) -> LayerManager {
    var currentManager = layerManager

    for pass in 0..<passes {
      // Alternate direction each pass
      let goingDown = (pass % 2 == 0) == (initialDirection == .down)
      let ranks: [Int] = goingDown
        ? currentManager.sortedRanks
        : currentManager.sortedRanks.reversed().map { $0 }

      var newOrdering = currentManager.ordering

      for rank in ranks {
        let layerNodes = LayerManager.getLayerNodes(currentManager, rank: rank)
        if layerNodes.count <= 1 { continue }

        // Get adjacent layer
        let adjacentRank = goingDown ? rank - 1 : rank + 1
        let adjacentNodes = LayerManager.getLayerNodes(currentManager, rank: adjacentRank)

        if adjacentNodes.isEmpty { continue }

        // If going down: look at parents (above); if going up: children (below).
        // Flow adjacency only: stacking edges never participate in crossing
        // minimization (their targets are positioned by the stack plan).
        let adjacency = goingDown ? graph.flowParentMap : graph.flowChildMap

        // Compute medians for each node in this layer
        var medians: [String: Double] = [:]
        for nodeId in layerNodes {
          medians[nodeId] = computeMedian(
            nodeId: nodeId,
            adjacentNodes: adjacentNodes,
            adjacency: adjacency,
            ordering: newOrdering
          )
        }

        // Sort by median; ties break by the connector side the node is reached
        // through (min incoming source-port index), then by node ID. Because
        // output ports spread left→right by declaration index, this keeps a
        // node's left/right position consistent with the connectors the user
        // sees, and eliminates fan-out crossings. (Deliberate divergence from
        // the JS reference's ID-only tiebreak.)
        let sorted = layerNodes.sorted { a, b in
          let ma = medians[a] ?? 0.0
          let mb = medians[b] ?? 0.0
          if ma != mb { return ma < mb }
          let ka = graph.incomingPortIndex[a] ?? Int.max
          let kb = graph.incomingPortIndex[b] ?? Int.max
          return ka == kb ? a < b : ka < kb
        }

        // Update ordering
        for (index, nodeId) in sorted.enumerated() {
          newOrdering[nodeId] = index
        }
      }

      currentManager = LayerManager.updateOrdering(currentManager, newOrdering: newOrdering)
    }

    return currentManager
  }

  /// Median position of a node's neighbors in the adjacent layer.
  /// Odd count: middle element; even count: average of the two middles.
  /// No neighbors: current position.
  private static func computeMedian(
    nodeId: String,
    adjacentNodes: [String],
    adjacency: [String: [String]],
    ordering: [String: Int]
  ) -> Double {
    let neighbors = adjacency[nodeId] ?? []
    var adjacentPositions: [Double] = []

    for neighbor in neighbors {
      if adjacentNodes.contains(neighbor) {
        if let pos = ordering[neighbor] {
          adjacentPositions.append(Double(pos))
        }
      }
    }

    if adjacentPositions.isEmpty {
      return Double(ordering[nodeId] ?? 0)
    }

    adjacentPositions.sort()

    let mid = adjacentPositions.count / 2

    // Odd count: return middle element
    if adjacentPositions.count % 2 == 1 {
      return adjacentPositions[mid]
    }

    // Even count: return average of two middle elements
    return (adjacentPositions[mid - 1] + adjacentPositions[mid]) / 2.0
  }

  /// Count edge crossings between two adjacent layers (used for validation
  /// and ordering-quality assertions).
  static func countCrossings(graph: LayoutGraph, layerManager: LayerManager, rank: Int) -> Int {
    let layer1 = LayerManager.getLayerNodes(layerManager, rank: rank)
    let layer2 = LayerManager.getLayerNodes(layerManager, rank: rank + 1)

    if layer1.isEmpty || layer2.isEmpty { return 0 }

    let ordering2 = layerManager.ordering

    var crossings = 0

    // Check each pair of edges
    for i in 0..<layer1.count {
      for j in (i + 1)..<layer1.count {
        let node1 = layer1[i]
        let node2 = layer1[j]

        let children1 = graph.flowChildMap[node1] ?? []
        let children2 = graph.flowChildMap[node2] ?? []

        for child1 in children1 {
          guard let pos1 = ordering2[child1] else { continue }

          for child2 in children2 {
            guard let pos2 = ordering2[child2] else { continue }

            // Crossing if order is reversed
            if pos1 > pos2 {
              crossings += 1
            }
          }
        }
      }
    }

    return crossings
  }

  /// Total edge crossings across all adjacent layer pairs.
  static func countTotalCrossings(graph: LayoutGraph, layerManager: LayerManager) -> Int {
    var total = 0

    for i in 0..<(layerManager.sortedRanks.count - 1) {
      let rank = layerManager.sortedRanks[i]
      total += countCrossings(graph: graph, layerManager: layerManager, rank: rank)
    }

    return total
  }
}
