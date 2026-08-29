import Foundation

/// A single stacking placement: `target` is placed beside `source` in the same
/// rank column, `direction` steps in flow space (+1 right, -1 left) at
/// `XCoordinateAssigner`'s stack gap.
struct StackItem {
  let target: String
  let source: String
  let direction: Int
}

/// Stage 5.5 - Stack planning for stacking edges (declared perpendicular port
/// sides). For every stacking edge whose endpoints share a rank, the target is
/// taken out of the flow column and stacked beside its source: `source edge +
/// gap` in the port side's direction. Chains (a stacked target that is itself
/// the source of another stacking edge) are processed source-first, so each
/// placement is relative to an already-final position.
///
/// Plan determinism: items keep edge declaration order, ties in the topological
/// order break by declaration order, and stacking cycles fall back to a
/// declaration-order tail (their positions then use the caller's defensive
/// fallback anchor).
enum StackPlanner {

  static func plan(
    graph: LayoutGraph,
    ranks: [String: Int],
    stackedTargets: Set<String>
  ) -> [Int: [StackItem]] {
    var byRank: [Int: [StackItem]] = [:]

    for edge in graph.stackingEdges {
      guard let sourceRank = ranks[edge.source], let targetRank = ranks[edge.target] else { continue }
      // Same column only: a stacking edge whose endpoints span ranks renders
      // as a regular edge (the target's column is decided by its rank).
      if sourceRank != targetRank { continue }
      if !stackedTargets.contains(edge.target) { continue }
      byRank[targetRank, default: []].append(
        StackItem(target: edge.target, source: edge.source, direction: edge.direction)
      )
    }

    var result: [Int: [StackItem]] = [:]
    for (rank, items) in byRank {
      result[rank] = topoSort(items)
    }
    return result
  }

  /// Order stack items so every target is placed after its source. Items are
  /// vertices; item A precedes item B when A.target == B.source. Kahn's
  /// algorithm with a declaration-order ready queue; cycle leftovers keep
  /// declaration order (they fall back to the caller's anchor).
  private static func topoSort(_ items: [StackItem]) -> [StackItem] {
    if items.count <= 1 { return items }
    let n = items.count
    var inDegree = [Int](repeating: 0, count: n)
    var dependents = [[Int]](repeating: [], count: n)
    for j in 0..<n {
      let source = items[j].source
      for i in 0..<n where i != j {
        if items[i].target == source {
          inDegree[j] += 1
          dependents[i].append(j)
        }
      }
    }

    var ready: [Int] = []
    for i in 0..<n where inDegree[i] == 0 {
      ready.append(i)
    }

    var ordered: [StackItem] = []
    ordered.reserveCapacity(n)
    var placed = [Bool](repeating: false, count: n)
    var head = 0
    while head < ready.count {
      let i = ready[head]
      head += 1
      if placed[i] { continue }
      placed[i] = true
      ordered.append(items[i])
      for j in dependents[i] {
        inDegree[j] -= 1
        if inDegree[j] == 0 {
          ready.append(j)
        }
      }
    }
    for i in 0..<n where !placed[i] {
      ordered.append(items[i])
    }
    return ordered
  }
}
