package expo.modules.sugiyama.layout

/**
 * Node dimensions (mirrors `width-allocator.ts` `NodeDimensions`).
 */
data class NodeDimensions(
  val width: Double,
  val height: Double,
)

/**
 * Result of width allocation (spec §11.5).
 */
class WidthAllocation(
  val subtreeWidths: Map<String, Double>,
  val dimensions: Map<String, NodeDimensions>,
)

/**
 * Stage 4 - Subtree width allocation, computed bottom-up
 * (line-for-line port of `features/layout-engine/width-allocator.ts`,
 * spec §11.5).
 *
 * In a DAG, overlapping subtrees share descendants, so the classic
 * bottom-up sum of children widths counts shared nodes multiple times
 * and explodes horizontally. Instead, each node's subtree width is the
 * widest descendant level's packed width. Each node is placed under its
 * first parent by the assigner, so a child belongs to a parent's subtree
 * only when it is that parent's first-parent child: shared descendants
 * are counted exactly once, matching where they are placed. A subtree
 * is also at least the packed width of its first-parent children
 * (disjoint, so never double-counted), which guarantees every parent's
 * children always fit inside its slot and can never be squeezed into
 * overlapping positions. In a tree every node belongs to exactly one
 * subtree chain, so this is equivalent to the classic sum.
 *
 * Dimensions come from the caller (`DagreLayout.computeLayout` passes the
 * input nodes' real sizes, swapped per layout direction).
 */
class WidthAllocator {
  companion object {
    private val DEFAULT_DIMENSIONS = NodeDimensions(width = 150.0, height = 64.0)

    fun allocateWidths(
      graph: LayoutGraph,
      layerManager: LayerManager,
      nodeDimensions: Map<String, NodeDimensions>? = null,
      nodesep: Double = 60.0,
    ): WidthAllocation {
      val subtreeWidths = LinkedHashMap<String, Double>()
      val dimensions = LinkedHashMap<String, NodeDimensions>()

      // Initialize dimensions
      for (nodeId in graph.nodes.keys) {
        dimensions[nodeId] = nodeDimensions?.get(nodeId) ?: DEFAULT_DIMENSIONS
      }

      // Per-node descendant memberships grouped by rank, deduplicated:
      // node ID → rank → set of descendant IDs in that rank.
      val levelMembers = LinkedHashMap<String, MutableMap<Int, MutableSet<String>>>()

      // A node is placed under its first parent by the assigner, so a
      // child belongs to this node's subtree only when it is this node's
      // first-parent child. Shared descendants are then counted exactly
      // once, matching where they are placed.

      // Process from bottom to top (reverse rank order)
      val reversedRanks = layerManager.sortedRanks.reversed()

      for (rank in reversedRanks) {
        val nodesAtRank = LayerManager.getLayerNodes(layerManager, rank)

        for (nodeId in nodesAtRank) {
          val dims = dimensions[nodeId] ?: DEFAULT_DIMENSIONS
          // Flow adjacency only: stacking children hang off the parent and
          // never inflate its subtree slot.
          val children = (graph.flowChildMap[nodeId] ?: emptyList()).filter {
            (graph.flowParentMap[it] ?: emptyList()).firstOrNull() == nodeId
          }
          val levels = LinkedHashMap<Int, MutableSet<String>>()

          if (children.size == 1) {
            // Single child: reuse its membership maps without cloning.
            levelMembers[children[0]]?.forEach { (childRank, members) ->
              levels[childRank] = members
            }
          } else if (children.size > 1) {
            // Multiple children: union memberships, deduplicating shared
            // descendants so overlapping subtrees are counted once.
            for (childId in children) {
              levelMembers[childId]?.forEach { (childRank, members) ->
                val own = levels[childRank]
                if (own != null) {
                  own.addAll(members)
                } else {
                  levels[childRank] = HashSet(members)
                }
              }
            }
          }

          // Own level contains only this node.
          levels[rank] = hashSetOf(nodeId)
          levelMembers[nodeId] = levels

          // Subtree width = packed width of the widest descendant level
          // (own level included → at least the node's own width)...
          var widest = dims.width
          for (members in levels.values) {
            var width = (members.size - 1) * nodesep
            for (member in members) {
              width += (dimensions[member] ?: DEFAULT_DIMENSIONS).width
            }
            if (width > widest) widest = width
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
          var childrenWidth = (children.size - 1) * nodesep
          for (childId in children) {
            childrenWidth += subtreeWidths[childId] ?: 0.0
          }
          if (childrenWidth > widest) widest = childrenWidth

          subtreeWidths[nodeId] = widest
        }
      }

      return WidthAllocation(
        subtreeWidths = LinkedHashMap(subtreeWidths),
        dimensions = LinkedHashMap(dimensions),
      )
    }

    fun getSubtreeWidth(allocation: WidthAllocation, nodeId: String): Double =
      allocation.subtreeWidths[nodeId] ?: 0.0

    fun getNodeDimensions(allocation: WidthAllocation, nodeId: String): NodeDimensions =
      allocation.dimensions[nodeId] ?: DEFAULT_DIMENSIONS

    /**
     * Total width needed for a parent's children area.
     */
    fun computeChildrenWidth(
      allocation: WidthAllocation,
      childrenIds: List<String>,
      nodesep: Double = 60.0,
    ): Double {
      if (childrenIds.isEmpty()) return 0.0

      var total = 0.0
      for (childId in childrenIds) {
        total += getSubtreeWidth(allocation, childId)
      }

      return total + (childrenIds.size - 1) * nodesep
    }
  }
}