package expo.modules.sugiyama.layout

/**
 * Cycle handling policy (ADR-003, spec §11.3).
 *
 * - TOLERANT (default): nodes on a cycle in progress are treated as rank 0;
 *   the layout completes. Supports real workflow loops (fetch → retry → fetch).
 * - THROW: rejects with a [LayoutCycleException], matching the JS reference
 *   exactly (for golden parity in strict mode).
 */
enum class CyclePolicy {
  TOLERANT,
  THROW,
}

/**
 * Thrown when [CyclePolicy.THROW] is active and a cycle is encountered.
 */
class LayoutCycleException(message: String) : RuntimeException(message)

/**
 * Stage 2 - Layer assignment using the longest-path algorithm
 * (line-for-line port of `features/layout-engine/layer-assigner.ts`
 * `assignLayers`, spec §11.3).
 *
 * For each node, the rank is the length of the longest path from any root
 * to that node. This ensures every parent has strictly lower rank than
 * every child, handles multiple parents, and is deterministic.
 *
 * Stacking edges (declared perpendicular port sides) do not advance the rank:
 * a node reached only through stacking edges sits in the same column as its
 * stacking parents (rank = max parent rank, no +1), so it is laid out beside
 * them instead of one rank downstream. Nodes with at least one flow parent
 * keep the longest-path rank over flow edges; their stacking edges stay soft.
 */
class LayerAssigner {
  companion object {
    fun assignLayers(
      graph: LayoutGraph,
      cyclePolicy: CyclePolicy = CyclePolicy.TOLERANT,
    ): Map<String, Int> {
      val ranks = LinkedHashMap<String, Int>()
      val visited = HashSet<String>()
      val inProgress = HashSet<String>()

      fun dfs(nodeId: String): Int {
        // Return cached result if already computed
        val cached = ranks[nodeId]
        if (cached != null) {
          return cached
        }

        // Detect cycles during traversal
        if (inProgress.contains(nodeId)) {
          if (cyclePolicy == CyclePolicy.THROW) {
            throw LayoutCycleException("Cycle detected during layer assignment at node: $nodeId")
          }
          return 0
        }

        inProgress.add(nodeId)

        val flowParents = graph.flowParentMap[nodeId] ?: emptyList()
        val stackingParents = graph.stackingParentMap[nodeId] ?: emptyList()

        val rank: Int
        if (flowParents.isEmpty() && stackingParents.isEmpty()) {
          // Root node (no parents) gets rank 0
          rank = 0
        } else if (flowParents.isEmpty()) {
          // Stacked target: same column as its stacking parents (no +1), so
          // the layout places it beside the source instead of downstream.
          var maxParentRank = -1
          for (parentId in stackingParents) {
            val parentRank = dfs(parentId)
            if (parentRank > maxParentRank) {
              maxParentRank = parentRank
            }
          }
          rank = maxOf(0, maxParentRank)
        } else {
          // Compute rank as max(parent ranks) + 1 over flow parents
          var maxParentRank = -1
          for (parentId in flowParents) {
            val parentRank = dfs(parentId)
            if (parentRank > maxParentRank) {
              maxParentRank = parentRank
            }
          }
          rank = maxParentRank + 1
        }

        ranks[nodeId] = rank
        inProgress.remove(nodeId)
        visited.add(nodeId)

        return rank
      }

      // Process all nodes (handles disconnected components)
      for (nodeId in graph.nodes.keys) {
        if (!visited.contains(nodeId)) {
          dfs(nodeId)
        }
      }

      return ranks
    }
  }
}
