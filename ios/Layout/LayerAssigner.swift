import Foundation

/// Cycle handling policy (ADR-003, spec §11.3).
///
/// - `tolerant` (default): nodes on a cycle in progress are treated as rank 0;
///   the layout completes. Supports real workflow loops (fetch -> retry -> fetch).
/// - `throwing`: rejects with a `LayoutCycleException`, matching the JS reference
///   exactly (for golden parity in strict mode).
public enum CyclePolicy {
  case tolerant
  case throwing
}

/// Thrown when `CyclePolicy.throwing` is active and a cycle is encountered.
public struct LayoutCycleException: Error {
  public let message: String

  public init(message: String) {
    self.message = message
  }
}

/// Stage 2 - Layer assignment using the longest-path algorithm
/// (line-for-line port of `features/layout-engine/layer-assigner.ts`
/// `assignLayers`, spec §11.3).
///
/// For each node, the rank is the length of the longest path from any root
/// to that node. This ensures every parent has strictly lower rank than
/// every child, handles multiple parents, and is deterministic.
///
/// Stacking edges (declared perpendicular port sides) do not advance the rank:
/// a node reached only through stacking edges sits in the same column as its
/// stacking parents (rank = max parent rank, no +1), so it is laid out beside
/// them instead of one rank downstream. Nodes with at least one flow parent
/// keep the longest-path rank over flow edges; their stacking edges stay soft.
enum LayerAssigner {
  static func assignLayers(
    graph: LayoutGraph,
    cyclePolicy: CyclePolicy = .tolerant
  ) throws -> [String: Int] {
    var ranks: [String: Int] = [:]
    var visited: Set<String> = []
    var inProgress: Set<String> = []

    func dfs(_ nodeId: String) throws -> Int {
      // Return cached result if already computed
      if let cached = ranks[nodeId] {
        return cached
      }

      // Detect cycles during traversal
      if inProgress.contains(nodeId) {
        if cyclePolicy == .throwing {
          throw LayoutCycleException(
            message: "Cycle detected during layer assignment at node: \(nodeId)"
          )
        }
        return 0
      }

      inProgress.insert(nodeId)

      let flowParents = graph.flowParentMap[nodeId] ?? []
      let stackingParents = graph.stackingParentMap[nodeId] ?? []

      let rank: Int
      if flowParents.isEmpty && stackingParents.isEmpty {
        // Root node (no parents) gets rank 0
        rank = 0
      } else if flowParents.isEmpty {
        // Stacked target: same column as its stacking parents (no +1), so
        // the layout places it beside the source instead of downstream.
        var maxParentRank = -1
        for parentId in stackingParents {
          let parentRank = try dfs(parentId)
          if parentRank > maxParentRank {
            maxParentRank = parentRank
          }
        }
        rank = max(0, maxParentRank)
      } else {
        // Compute rank as max(parent ranks) + 1 over flow parents
        var maxParentRank = -1
        for parentId in flowParents {
          let parentRank = try dfs(parentId)
          if parentRank > maxParentRank {
            maxParentRank = parentRank
          }
        }
        rank = maxParentRank + 1
      }

      ranks[nodeId] = rank
      inProgress.remove(nodeId)
      visited.insert(nodeId)

      return rank
    }

    // Process all nodes (handles disconnected components)
    for nodeId in graph.nodeIds {
      if !visited.contains(nodeId) {
        _ = try dfs(nodeId)
      }
    }

    return ranks
  }
}
