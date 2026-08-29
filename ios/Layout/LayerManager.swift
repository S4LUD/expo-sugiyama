import Foundation

/// A single layer in the hierarchical layout (spec §11.4).
struct Layer: Equatable {
  let rank: Int
  let nodes: [String]
}

/// Adjacent-layer lookup direction (mirrors `layer-manager.ts`).
enum Direction {
  case above
  case below
}

/// Layer manager for hierarchical layout: groups nodes by rank and tracks
/// ordering within layers (port of `features/layout-engine/layer-manager.ts`,
/// spec §11.4).
///
/// Initial ordering within a layer is sorted by node ID for determinism.
final class LayerManager {
  let layers: [Int: Layer]
  let ranks: [String: Int]
  let ordering: [String: Int]
  let sortedRanks: [Int]
  let maxRank: Int

  init(
    layers: [Int: Layer],
    ranks: [String: Int],
    ordering: [String: Int],
    sortedRanks: [Int],
    maxRank: Int
  ) {
    self.layers = layers
    self.ranks = ranks
    self.ordering = ordering
    self.sortedRanks = sortedRanks
    self.maxRank = maxRank
  }

  /// Build the layer groups for every ranked node. `exclude` removes nodes
  /// from the layer membership while keeping their ranks (used to hide
  /// stacked targets from the ordering/width/X passes, which position them
  /// via `StackPlanner` instead).
  static func build(
    graph: LayoutGraph,
    ranks: [String: Int],
    exclude: Set<String> = []
  ) -> LayerManager {
    var layerNodes: [Int: [String]] = [:]

    for nodeId in graph.nodeIds {
      if exclude.contains(nodeId) { continue }
      let rank = ranks[nodeId] ?? 0
      layerNodes[rank, default: []].append(nodeId)
    }

    let sortedRanks = layerNodes.keys.sorted()
    let maxRank = sortedRanks.last ?? 0

    var layers: [Int: Layer] = [:]
    var ordering: [String: Int] = [:]

    for rank in sortedRanks {
      let nodes = (layerNodes[rank] ?? []).sorted()

      for (index, nodeId) in nodes.enumerated() {
        ordering[nodeId] = index
      }

      layers[rank] = Layer(rank: rank, nodes: nodes)
    }

    return LayerManager(
      layers: layers,
      ranks: ranks,
      ordering: ordering,
      sortedRanks: sortedRanks,
      maxRank: maxRank
    )
  }

  /// Re-sort every layer by a new ordering map. The sort is stable
  /// (matches the JS Array.prototype.sort stability).
  static func updateOrdering(_ manager: LayerManager, newOrdering: [String: Int]) -> LayerManager {
    var layers: [Int: Layer] = [:]

    for rank in manager.sortedRanks {
      guard let layer = manager.layers[rank] else { continue }
      // Stable sort by (newOrdering value, original index)
      let indexed = layer.nodes.enumerated()
      let nodes = indexed.sorted { a, b in
        let va = newOrdering[a.element] ?? 0
        let vb = newOrdering[b.element] ?? 0
        return va == vb ? a.offset < b.offset : va < vb
      }.map { $0.element }

      layers[rank] = Layer(rank: rank, nodes: nodes)
    }

    return LayerManager(
      layers: layers,
      ranks: manager.ranks,
      ordering: newOrdering,
      sortedRanks: manager.sortedRanks,
      maxRank: manager.maxRank
    )
  }

  static func getLayerNodes(_ manager: LayerManager, rank: Int) -> [String] {
    return manager.layers[rank]?.nodes ?? []
  }

  static func getNodeRank(_ manager: LayerManager, nodeId: String) -> Int? {
    return manager.ranks[nodeId]
  }

  static func getNodeOrder(_ manager: LayerManager, nodeId: String) -> Int? {
    return manager.ordering[nodeId]
  }

  static func getAdjacentLayer(_ manager: LayerManager, rank: Int, direction: Direction) -> [String] {
    let targetRank = direction == .above ? rank - 1 : rank + 1
    return getLayerNodes(manager, rank: targetRank)
  }
}
