import Foundation

/// Stage 6 - Y coordinate assignment by rank rows
/// (line-for-line port of `features/layout-engine/y-coordinate-assigner.ts`,
/// spec §11.7).
///
/// Nodes in the same rank share the same Y. Per-rank spacing uses the rank's
/// maximum node height plus ranksep.
///
/// Note: `rankdir` is accepted for API fidelity; direction handling lives in
/// `DagreLayout.computeLayout`, which runs this stage in flow space and
/// orients the result (spec §11.7).
enum YCoordinateAssigner {
  private static let defaultRanksep = 80.0
  private static let defaultMarginy = 40.0

  static func assignYCoordinates(
    layerManager: LayerManager,
    widthAllocation: WidthAllocation,
    ranksep: Double = defaultRanksep,
    marginy: Double = defaultMarginy,
    rankdir: LayoutRankDir = .tb,
    stackPlan: [Int: [StackItem]] = [:]
  ) -> [String: Double] {
    var yPositions: [String: Double] = [:]

    // Track the maximum height per rank for consistent spacing
    var maxHeightPerRank: [Int: Double] = [:]

    // First pass: find max height per rank
    for rank in layerManager.sortedRanks {
      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: rank)
      var maxHeight = 0.0

      for nodeId in nodesAtRank {
        let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)
        maxHeight = max(maxHeight, dims.height)
      }

      maxHeightPerRank[rank] = maxHeight
    }

    // Second pass: assign Y coordinates
    var currentY = marginy

    for rank in layerManager.sortedRanks {
      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: rank)
      let maxHeight = maxHeightPerRank[rank] ?? 64.0

      for nodeId in nodesAtRank {
        yPositions[nodeId] = currentY
      }

      // Stacked rows: the direct children of one source form a row along
      // the rank axis, CENTERED on the source (already placed: a flow
      // member of this rank, or an earlier stack item via the source-first
      // plan order) — the group's total extent (member heights + ranksep
      // gaps) is distributed symmetrically around the source's center, so
      // the row stays centered on its connection however many siblings
      // join. Chains keep their transitive stacking because a chain
      // child's source is the stacked node itself. A row lives in its own
      // band beside its source and must NOT widen the rank band: the next
      // rank keeps its regular gap no matter how long a row grows.
      if let rankItems = stackPlan[rank], !rankItems.isEmpty {
        var groups: [String: [StackItem]] = [:]
        var groupOrder: [String] = []
        for item in rankItems {
          if groups[item.source] == nil {
            groups[item.source] = []
            groupOrder.append(item.source)
          }
          groups[item.source]?.append(item)
        }
        for sourceId in groupOrder {
          let group = groups[sourceId] ?? []
          let sourceDims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: sourceId)
          let sourceY = yPositions[sourceId] ?? currentY
          let extents = group.map {
            WidthAllocator.getNodeDimensions(widthAllocation, nodeId: $0.target).height
          }
          let total = extents.reduce(0.0, +) + ranksep * Double(group.count - 1)
          let start = sourceY + sourceDims.height / 2.0 - total / 2.0
          var cursor = start
          for (index, item) in group.enumerated() {
            yPositions[item.target] = cursor
            cursor += extents[index] + ranksep
          }
        }
      }

      // Move to next rank
      currentY += maxHeight + ranksep
    }

    return yPositions
  }

  /// Total layout height (max Y + marginy).
  static func computeLayoutHeight(
    layerManager: LayerManager,
    widthAllocation: WidthAllocation,
    ranksep: Double = defaultRanksep,
    marginy: Double = defaultMarginy
  ) -> Double {
    var totalHeight = marginy

    for rank in layerManager.sortedRanks {
      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: rank)
      var maxHeight = 0.0

      for nodeId in nodesAtRank {
        let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)
        maxHeight = max(maxHeight, dims.height)
      }

      totalHeight += maxHeight + ranksep
    }

    // Remove the last ranksep
    totalHeight -= ranksep

    return totalHeight + marginy
  }

  /// Y coordinate for a specific rank.
  static func getRankYCoordinate(
    layerManager: LayerManager,
    rank: Int,
    widthAllocation: WidthAllocation,
    ranksep: Double = defaultRanksep,
    marginy: Double = defaultMarginy
  ) -> Double {
    var y = marginy

    for r in layerManager.sortedRanks {
      if r == rank { return y }

      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: r)
      var maxHeight = 0.0

      for nodeId in nodesAtRank {
        let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)
        maxHeight = max(maxHeight, dims.height)
      }

      y += maxHeight + ranksep
    }

    return y
  }
}
