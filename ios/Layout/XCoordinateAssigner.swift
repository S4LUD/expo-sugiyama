import Foundation

/// Stage 5 - X coordinate assignment with parent centering
/// (line-for-line port of `features/layout-engine/x-coordinate-assigner.ts`,
/// spec §11.6).
///
/// Rank 0 (roots): walk left-to-right allocating each root its subtree width
/// plus nodesep; center each node within its slot. Lower ranks: children are
/// laid out left-to-right centered under their first parent. Children are
/// placed inside the parent's subtree width: when children naturally fit
/// (trees and most graphs), each child keeps its full subtree width; when
/// overlapping DAG subtrees spread wider than the parent's slot, children
/// slots are proportionally scaled to fit, so shared descendants are counted
/// once instead of spreading the layout. Orphans (no parents) are placed at
/// marginx.
///
/// Stacking edges (declared perpendicular port sides): stacked targets are not
/// rank members of the flow pass (see `StackPlanner`); after each rank's flow
/// members are placed, its stacked targets are positioned relative to their
/// already-placed source — `source edge + stackGap` in the port side's
/// direction. They keep their own width as slot, so later ranks center under
/// them as any parent.
enum XCoordinateAssigner {
  private static let defaultNodesep = 60.0
  private static let defaultMarginx = 40.0
  private static let defaultStackGap = 80.0

  static func assignXCoordinates(
    graph: LayoutGraph,
    layerManager: LayerManager,
    widthAllocation: WidthAllocation,
    nodesep: Double = defaultNodesep,
    marginx: Double = defaultMarginx,
    stackPlan: [Int: [StackItem]] = [:],
    stackGap: Double = defaultStackGap
  ) -> [String: Double] {
    var xPositions: [String: Double] = [:]
    // Effective slot width per node: what its children are actually
    // fitted into (may be scaled down from the subtree width).
    var slotWidths: [String: Double] = [:]

    // Process from top to bottom
    for rank in layerManager.sortedRanks {
      let nodesAtRank = LayerManager.getLayerNodes(layerManager, rank: rank)

      if rank == 0 {
        // Position root nodes
        var x = marginx

        for nodeId in nodesAtRank {
          let subtreeWidth = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId: nodeId)
          let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)

          // Center node within its allocated subtree width
          xPositions[nodeId] = x + (subtreeWidth - dims.width) / 2.0
          slotWidths[nodeId] = subtreeWidth
          x += subtreeWidth + nodesep
        }
      } else {
        // Position child nodes
        for nodeId in nodesAtRank {
          // Flow adjacency only: stacking children are positioned by the
          // stack plan and must not participate in sibling slots.
          let parents = graph.flowParentMap[nodeId] ?? []
          let parentId = parents.first

          guard let parentId = parentId else {
            // Orphan: position at margin
            xPositions[nodeId] = marginx
            slotWidths[nodeId] = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId: nodeId)
            continue
          }

          guard let parentX = xPositions[parentId] else {
            // Parent not yet positioned - only reachable in tolerant mode
            // on a cycle back-edge (the JS reference throws there, so it
            // never observes this). Fall back to orphan placement to keep
            // the spec §10.6 margin guarantee.
            xPositions[nodeId] = marginx
            slotWidths[nodeId] = WidthAllocator.getSubtreeWidth(widthAllocation, nodeId: nodeId)
            continue
          }

          let parentDims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: parentId)
          // Only children this parent actually places (its first-parent
          // children) share its slot; other children are placed under
          // their own first parent and must not push siblings around.
          let siblings = (graph.flowChildMap[parentId] ?? []).filter {
            (graph.flowParentMap[$0] ?? []).first == parentId
          }
          guard let siblingIndex = siblings.firstIndex(of: nodeId) else { continue }

          // Children must fit inside the parent's effective slot width.
          let parentSlot = slotWidths[parentId]
            ?? WidthAllocator.getSubtreeWidth(widthAllocation, nodeId: parentId)
          let siblingW = siblings.map {
            WidthAllocator.getSubtreeWidth(widthAllocation, nodeId: $0)
          }
          let gap = Double(siblings.count - 1) * nodesep
          let totalW = siblingW.reduce(0.0, +)

          // Scale children slots down when they spread wider than the
          // parent's slot (overlapping DAG subtrees); 1 when they fit.
          let scale: Double
          if gap + totalW <= parentSlot {
            scale = 1.0
          } else {
            scale = (parentSlot - gap) / totalW
          }
          var slots: [Double] = []
          var slotsTotal = gap
          for j in siblings.indices {
            let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: siblings[j])
            let slot = max(siblingW[j] * scale, dims.width)
            slots.append(slot)
            slotsTotal += slot
          }

          // Center children under parent: anchored to the parent's slot.
          // When children fit inside the slot this matches centering on
          // the node (node center == slot center); when floored slots
          // exceed the slot, the group is left-anchored instead of
          // overflowing left of the slot (which could go negative).
          let slotStart = parentX - (parentSlot - parentDims.width) / 2.0
          let childrenStartX = slotStart + max(0.0, (parentSlot - slotsTotal) / 2.0)

          // Find X position for this sibling
          var cursor = childrenStartX
          for j in 0...siblingIndex {
            if j == siblingIndex {
              let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)
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
      for item in stackPlan[rank] ?? [] {
        let targetDims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: item.target)
        let sourceDims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: item.source)
        let targetX: Double
        if let sourceX = xPositions[item.source] {
          targetX = item.direction > 0
            ? sourceX + sourceDims.width + stackGap
            : sourceX - targetDims.width - stackGap
        } else {
          // Defensive fallback (stacking-cycle remainder): deterministic
          // anchor from the rank margin.
          targetX = marginx + Double(fallbackIndex) * (targetDims.width + nodesep)
          fallbackIndex += 1
        }
        xPositions[item.target] = targetX
        slotWidths[item.target] = targetDims.width
      }
    }

    return xPositions
  }

  /// Total layout width (max X extent + marginx).
  static func computeLayoutWidth(
    graph: LayoutGraph,
    layerManager: LayerManager,
    widthAllocation: WidthAllocation,
    nodesep: Double = defaultNodesep,
    marginx: Double = defaultMarginx
  ) -> Double {
    let xPositions = assignXCoordinates(
      graph: graph,
      layerManager: layerManager,
      widthAllocation: widthAllocation,
      nodesep: nodesep,
      marginx: marginx
    )

    var maxX = 0.0
    for (nodeId, x) in xPositions {
      let dims = WidthAllocator.getNodeDimensions(widthAllocation, nodeId: nodeId)
      maxX = max(maxX, x + dims.width)
    }

    return maxX + marginx
  }
}
