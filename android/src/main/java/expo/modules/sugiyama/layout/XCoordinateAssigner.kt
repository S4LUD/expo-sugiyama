package expo.modules.sugiyama.layout

/**
 * Stage 5 - X coordinate assignment with parent centering
 * (line-for-line port of `features/layout-engine/x-coordinate-assigner.ts`,
 * spec §11.6).
 *
 * Rank 0 (roots): walk left-to-right allocating each root its subtree width
 * plus nodesep; center each node within its slot. Lower ranks: children are
 * laid out left-to-right centered under their first parent. Children are
 * placed inside the parent's subtree width: when children naturally fit
 * (trees and most graphs), each child keeps its full subtree width; when
 * overlapping DAG subtrees spread wider than the parent's slot, children
 * slots are proportionally scaled to fit, so shared descendants are counted
 * once instead of spreading the layout. Orphans (no parents) are placed at
 * marginx.
 *
 * Stacking edges (declared perpendicular port sides): stacked targets are not
 * rank members of the flow pass (see `StackPlanner`); after each rank's flow
 * members are placed, its stacked targets are positioned relative to their
 * already-placed source — `source edge + [stackGap]` in the port side's
 * direction. They keep their own width as slot, so later ranks center under
 * them as any parent.
 */
class XCoordinateAssigner {
  companion object {
    private const val DEFAULT_NODESEP = 60.0
    private const val DEFAULT_MARGINX = 40.0
    private const val DEFAULT_STACK_GAP = 80.0

    fun assignXCoordinates(
      graph: LayoutGraph,
      layerManager: LayerManager,
      widthAllocation: WidthAllocation,
      nodesep: Double = DEFAULT_NODESEP,
      marginx: Double = DEFAULT_MARGINX,
      stackPlan: Map<Int, List<StackItem>> = emptyMap(),
      stackGap: Double = DEFAULT_STACK_GAP,
    ): Map<String, Double> {
      val xPositions = LinkedHashMap<String, Double>()
      // Effective slot width per node: what its children are actually
      // fitted into (may be scaled down from the subtree width).
      val slotWidths = LinkedHashMap<String, Double>()

      // Process from top to bottom
      for (rank in layerManager.sortedRanks) {
        val nodesAtRank = LayerManager.getLayerNodes(layerManager, rank)

        if (rank == 0) {
          // Position root nodes
          var x = marginx

          for (nodeId in nodesAtRank) {
            val subtreeWidth = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId)
            val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)

            // Center node within its allocated subtree width
            xPositions[nodeId] = x + (subtreeWidth - dims.width) / 2.0
            slotWidths[nodeId] = subtreeWidth
            x += subtreeWidth + nodesep
          }
        } else {
          // Position child nodes
          for (nodeId in nodesAtRank) {
            // Flow adjacency only: stacking children are positioned by the
            // stack plan and must not participate in sibling slots.
            val parents = graph.flowParentMap[nodeId] ?: emptyList()
            val parentId = parents.firstOrNull()

            if (parentId == null) {
              // Orphan: position at margin
              xPositions[nodeId] = marginx
              slotWidths[nodeId] = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId)
              continue
            }

            val parentX = xPositions[parentId]
            if (parentX == null) {
              // Parent not yet positioned - only reachable in tolerant mode
              // on a cycle back-edge (the JS reference throws there, so it
              // never observes this). Fall back to orphan placement to keep
              // the spec §10.6 margin guarantee.
              xPositions[nodeId] = marginx
              slotWidths[nodeId] = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId)
              continue
            }

            val parentDims = WidthAllocator.getNodeDimensions(widthAllocation, parentId)
            // Only children this parent actually places (its first-parent
            // children) share its slot; other children are placed under
            // their own first parent and must not push siblings around.
            val siblings = (graph.flowChildMap[parentId] ?: emptyList()).filter {
              (graph.flowParentMap[it] ?: emptyList()).firstOrNull() == parentId
            }
            val siblingIndex = siblings.indexOf(nodeId)
            if (siblingIndex == -1) continue

            // Children must fit inside the parent's effective slot width.
            val parentSlot = slotWidths[parentId]
              ?: WidthAllocator.getSubtreeWidth(widthAllocation, parentId)
            val siblingW = siblings.map { WidthAllocator.getSubtreeWidth(widthAllocation, it) }
            val gap = (siblings.size - 1) * nodesep
            var totalW = 0.0
            for (w in siblingW) totalW += w

            // Scale children slots down when they spread wider than the
            // parent's slot (overlapping DAG subtrees); 1 when they fit.
            val scale = if (gap + totalW <= parentSlot) {
              1.0
            } else {
              (parentSlot - gap) / totalW
            }
            val slots = ArrayList<Double>(siblings.size)
            var slotsTotal = gap
            for (j in siblings.indices) {
              val dims = WidthAllocator.getNodeDimensions(widthAllocation, siblings[j])
              val slot = maxOf(siblingW[j] * scale, dims.width)
              slots.add(slot)
              slotsTotal += slot
            }

            // Center children under parent: anchored to the parent's slot.
            // When children fit inside the slot this matches centering on
            // the node (node center == slot center); when floored slots
            // exceed the slot, the group is left-anchored instead of
            // overflowing left of the slot (which could go negative).
            val slotStart = parentX - (parentSlot - parentDims.width) / 2.0
            val childrenStartX = slotStart + maxOf(0.0, (parentSlot - slotsTotal) / 2.0)

            // Find X position for this sibling
            var cursor = childrenStartX
            for (j in 0..siblingIndex) {
              if (j == siblingIndex) {
                val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)
                xPositions[nodeId] = cursor + (slots[j] - dims.width) / 2.0
                slotWidths[nodeId] = slots[j]
              }
              cursor += slots[j] + nodesep
            }
          }
        }

        // Stacked targets of this rank: beside their source (already placed:
        // a flow member of this rank, or an earlier stack item via the
        // source-first plan order), at the stack gap in the port direction.
        // Every direct child of a source keeps the SAME offset here — the
        // row spread along the rank axis happens in the Y pass — while a
        // chain (a stacked target that is itself a stacking source) anchors
        // off its own source's extent, so it stacks transitively.
        var fallbackIndex = 0
        for (item in stackPlan[rank] ?: emptyList()) {
          val targetDims = WidthAllocator.getNodeDimensions(widthAllocation, item.target)
          val sourceDims = WidthAllocator.getNodeDimensions(widthAllocation, item.source)
          val targetX = when {
            xPositions[item.source] != null -> {
              val sourceX = xPositions.getValue(item.source)
              if (item.direction > 0) sourceX + sourceDims.width + stackGap
              else sourceX - targetDims.width - stackGap
            }
            else -> {
              // Defensive fallback (stacking-cycle remainder): deterministic
              // anchor from the rank margin.
              val x = marginx + fallbackIndex * (targetDims.width + nodesep)
              fallbackIndex += 1
              x
            }
          }
          xPositions[item.target] = targetX
          slotWidths[item.target] = targetDims.width
        }
      }

      return xPositions
    }

    /**
     * Total layout width (max X extent + marginx).
     */
    fun computeLayoutWidth(
      graph: LayoutGraph,
      layerManager: LayerManager,
      widthAllocation: WidthAllocation,
      nodesep: Double = DEFAULT_NODESEP,
      marginx: Double = DEFAULT_MARGINX,
    ): Double {
      val xPositions = assignXCoordinates(graph, layerManager, widthAllocation, nodesep, marginx)

      var maxX = 0.0
      for ((nodeId, x) in xPositions) {
        val dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId)
        maxX = maxOf(maxX, x + dims.width)
      }

      return maxX + marginx
    }
  }
}