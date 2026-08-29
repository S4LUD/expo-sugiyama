import Foundation

/// Node dimensions (mirrors `width-allocator.ts` `NodeDimensions`).
struct NodeDimensions: Equatable {
  let width: Double
  let height: Double
}

/// Result of width allocation (spec §11.5).
final class WidthAllocation {
  let subtreeWidths: [String: Double]
  let dimensions: [String: NodeDimensions]

  init(subtreeWidths: [String: Double], dimensions: [String: NodeDimensions]) {
    self.subtreeWidths = subtreeWidths
    self.dimensions = dimensions
  }
}

/// Stage 4 - Subtree width allocation, computed bottom-up
/// (line-for-line port of `features/layout-engine/width-allocator.ts`,
/// spec §11.5).
///
/// In a DAG, overlapping subtrees share descendants, so the classic
/// bottom-up sum of children widths counts shared nodes multiple times
/// and explodes horizontally. Instead, each node's subtree width is the
/// widest descendant level's packed width. Each node is placed under its
/// first parent by the assigner, so a child belongs to a parent's subtree
/// only when it is that parent's first-parent child: shared descendants
/// are counted exactly once, matching where they are placed. A subtree
/// is also at least the packed width of its first-parent children
/// (disjoint, so never double-counted), which guarantees every parent's
/// children always fit inside its slot and can never be squeezed into
/// overlapping positions. In a tree every node belongs to exactly one
/// subtree chain, so this is equivalent to the classic sum.
///
/// Dimensions come from the caller (`DagreLayout.computeLayout` passes the
/// input nodes' real sizes, swapped per layout direction).
enum WidthAllocator {
  static let defaultDimensions = NodeDimensions(width: 150.0, height: 64.0)

  static func allocateWidths(
    graph: LayoutGraph,
    layerManager: LayerManager,
    nodeDimensions: [String: NodeDimensions]? = nil,
    nodesep: Double = 60.0
  ) -> WidthAllocation {
    var subtreeWidths: [String: Double] = [:]
    var dimensions: [String: NodeDimensions] = [:]

    // Initialize dimensions
    for nodeId in graph.nodeIds {
      dimensions[nodeId] = nodeDimensions?[nodeId] ?? defaultDimensions
    }

    // Per-node descendant memberships grouped by rank, deduplicated:
    // node ID → rank → set of descendant IDs in that rank.
    var levelMembers: [String: [Int: Set<String>]] = [:]

    // A node is placed under its first parent by the assigner, so a
    // child belongs to this node's subtree only when it is this node's
    // first-parent child. Shared descendants are then counted exactly
    // once, matching where they are placed.

    // Process from bottom to top (reverse rank order)
    let reversedRanks = layerManager.sortedRanks.reversed()

    for rank in reversedRanks {
      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: rank)

      for nodeId in nodesAtRank {
        let dims = dimensions[nodeId] ?? defaultDimensions
        // Flow adjacency only: stacking children hang off the parent and
        // never inflate its subtree slot.
        let children = (graph.flowChildMap[nodeId] ?? []).filter {
          (graph.flowParentMap[$0] ?? []).first == nodeId
        }
        var levels: [Int: Set<String>] = [:]

        if children.count == 1 {
          // Single child: reuse its membership maps without cloning.
          if let childLevels = levelMembers[children[0]] {
            for (childRank, members) in childLevels {
              levels[childRank] = members
            }
          }
        } else if children.count > 1 {
          // Multiple children: union memberships, deduplicating shared
          // descendants so overlapping subtrees are counted once.
          for childId in children {
            guard let childLevels = levelMembers[childId] else { continue }
            for (childRank, members) in childLevels {
              if var own = levels[childRank] {
                own.formUnion(members)
                levels[childRank] = own
              } else {
                levels[childRank] = Set(members)
              }
            }
          }
        }

        // Own level contains only this node.
        levels[rank] = [nodeId]
        levelMembers[nodeId] = levels

        // Subtree width = packed width of the widest descendant level
        // (own level included → at least the node's own width)...
        var widest = dims.width
        for members in levels.values {
          var width = Double(members.count - 1) * nodesep
          for member in members {
            width += (dimensions[member] ?? defaultDimensions).width
          }
          if width > widest { widest = width }
        }

        // ...and at least the packed width of this node's first-parent
        // children. Without this term, a parent whose subtree is wider
        // than any single level (e.g. diagonal chains) could get a slot
        // narrower than what its children need; the assigner would then
        // scale/floor child slots and spill them into the neighbor
        // subtree, overlapping nodes. With the term, every parent's
        // children always fit their slot exactly, so the assigner never
        // has to squeeze them (first-parent subtrees are disjoint, so
        // summing them never double-counts shared descendants).
        var childrenWidth = Double(children.count - 1) * nodesep
        for childId in children {
          childrenWidth += subtreeWidths[childId] ?? 0.0
        }
        if childrenWidth > widest { widest = childrenWidth }

        subtreeWidths[nodeId] = widest
      }
    }

    return WidthAllocation(subtreeWidths: subtreeWidths, dimensions: dimensions)
  }

  static func getSubtreeWidth(_ allocation: WidthAllocation, nodeId: String) -> Double {
    return allocation.subtreeWidths[nodeId] ?? 0.0
  }

  static func getNodeDimensions(_ allocation: WidthAllocation, nodeId: String) -> NodeDimensions {
    return allocation.dimensions[nodeId] ?? defaultDimensions
  }

  /// Total width needed for a parent's children area.
  static func computeChildrenWidth(
    _ allocation: WidthAllocation,
    childrenIds: [String],
    nodesep: Double = 60.0
  ) -> Double {
    if childrenIds.isEmpty { return 0.0 }

    var total = 0.0
    for childId in childrenIds {
      total += getSubtreeWidth(allocation, nodeId: childId)
    }

    return total + Double(childrenIds.count - 1) * nodesep
  }
}
