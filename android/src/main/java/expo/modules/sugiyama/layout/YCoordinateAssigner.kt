package expo.modules.sugiyama.layout

/**
 * Stage 6 - Y coordinate assignment by rank rows
 * (line-for-line port of `features/layout-engine/y-coordinate-assigner.ts`,
 * spec §11.7).
 *
 * Nodes in the same rank share the same Y. Per-rank spacing uses the rank's
 * maximum node height plus ranksep.
 *
 * Note: [rankdir] is accepted for API fidelity; direction handling lives in
 * `DagreLayout.computeLayout`, which runs this stage in flow space and orients
 * the result (spec §11.7).
 */
class YCoordinateAssigner {
  companion object {
    private const val DEFAULT_RANKSEP = 80.0
    private const val DEFAULT_MARGINY = 40.0

    fun assignYCoordinates(
      layerManager: LayerManager,
      widthAllocation: WidthAllocation,
      ranksep: Double = DEFAULT_RANKSEP,
      marginy: Double = DEFAULT_MARGINY,
      rankdir: LayoutRankDir = LayoutRankDir.TB,
      stackPlan: Map<Int, List<StackItem>> = emptyMap(),
    ): Map<String, Double> {
      val yPositions = LinkedHashMap<String, Double>()

      // Track the maximum height per rank for consistent spacing
      val maxHeightPerRank = LinkedHashMap<Int, Double>()

      // First pass: find max height per rank
      for (rank in layerManager.sortedRanks) {
        val nodesAtRank = LayerManager.getLayerNodes(layerManager, rank)
        var maxHeight = 0.0

        for (nodeId in nodesAtRank) {
          val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)
          maxHeight = maxOf(maxHeight, dims.height)
        }

        maxHeightPerRank[rank] = maxHeight
      }

      // Second pass: assign Y coordinates
      var currentY = marginy

      for (rank in layerManager.sortedRanks) {
        val nodesAtRank = LayerManager.getLayerNodes(layerManager, rank)
        val maxHeight = maxHeightPerRank[rank] ?: 64.0

        for (nodeId in nodesAtRank) {
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
        if (!stackPlan[rank].isNullOrEmpty()) {
          val groups = LinkedHashMap<String, MutableList<StackItem>>()
          for (item in stackPlan.getValue(rank)) {
            groups.getOrPut(item.source) { mutableListOf() }.add(item)
          }
          for ((sourceId, group) in groups) {
            val sourceDims = WidthAllocator.getNodeDimensions(widthAllocation, sourceId)
            val sourceY = yPositions[sourceId] ?: currentY
            val extents = group.map {
              WidthAllocator.getNodeDimensions(widthAllocation, it.target).height
            }
            val total = extents.sum() + ranksep * (group.size - 1)
            val start = sourceY + sourceDims.height / 2.0 - total / 2.0
            var cursor = start
            for ((index, item) in group.withIndex()) {
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

    /**
     * Total layout height (max Y + marginy).
     */
    fun computeLayoutHeight(
      layerManager: LayerManager,
      widthAllocation: WidthAllocation,
      ranksep: Double = DEFAULT_RANKSEP,
      marginy: Double = DEFAULT_MARGINY,
    ): Double {
      var totalHeight = marginy

      for (rank in layerManager.sortedRanks) {
        val nodesAtRank = LayerManager.getLayerNodes(layerManager, rank)
        var maxHeight = 0.0

        for (nodeId in nodesAtRank) {
          val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)
          maxHeight = maxOf(maxHeight, dims.height)
        }

        totalHeight += maxHeight + ranksep
      }

      // Remove the last ranksep
      totalHeight -= ranksep

      return totalHeight + marginy
    }

    /**
     * Y coordinate for a specific rank.
     */
    fun getRankYCoordinate(
      layerManager: LayerManager,
      rank: Int,
      widthAllocation: WidthAllocation,
      ranksep: Double = DEFAULT_RANKSEP,
      marginy: Double = DEFAULT_MARGINY,
    ): Double {
      var y = marginy

      for (r in layerManager.sortedRanks) {
        if (r == rank) return y

        val nodesAtRank = LayerManager.getLayerNodes(layerManager, r)
        var maxHeight = 0.0

        for (nodeId in nodesAtRank) {
          val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)
          maxHeight = maxOf(maxHeight, dims.height)
        }

        y += maxHeight + ranksep
      }

      return y
    }
  }
}