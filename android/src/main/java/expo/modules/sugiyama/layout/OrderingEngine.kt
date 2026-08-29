package expo.modules.sugiyama.layout

/**
 * Sweep direction for an ordering pass (mirrors `ordering-engine.ts`).
 */
enum class SweepDirection {
  DOWN,
  UP,
}

/**
 * Stage 3 - Within-layer ordering using the median heuristic
 * (line-for-line port of `features/layout-engine/ordering-engine.ts`,
 * spec §11.4).
 *
 * Multiple passes alternate sweep direction (down/up). Each layer is sorted
 * by the median position of its neighbors in the adjacent layer; ties break
 * by node ID. Nodes without neighbors keep their current position.
 */
class OrderingEngine {
  companion object {
    private const val DEFAULT_PASSES = 4

    fun orderNodes(
      graph: LayoutGraph,
      layerManager: LayerManager,
      passes: Int = DEFAULT_PASSES,
      initialDirection: SweepDirection = SweepDirection.DOWN,
    ): LayerManager {
      var currentManager = layerManager

      for (pass in 0 until passes) {
        // Alternate direction each pass
        val goingDown = (pass % 2 == 0) == (initialDirection == SweepDirection.DOWN)
        val ranks = if (goingDown) {
          currentManager.sortedRanks
        } else {
          currentManager.sortedRanks.reversed()
        }

        val newOrdering = LinkedHashMap<String, Int>(currentManager.ordering)

        for (rank in ranks) {
          val layerNodes = LayerManager.getLayerNodes(currentManager, rank)
          if (layerNodes.size <= 1) continue

          // Get adjacent layer
          val adjacentRank = if (goingDown) rank - 1 else rank + 1
          val adjacentNodes = LayerManager.getLayerNodes(currentManager, adjacentRank)

          if (adjacentNodes.isEmpty()) continue

          // If going down: look at parents (above); if going up: children (below).
          // Flow adjacency only: stacking edges never participate in crossing
          // minimization (their targets are positioned by the stack plan).
          val adjacency = if (goingDown) graph.flowParentMap else graph.flowChildMap

          // Compute medians for each node in this layer
          val medians = LinkedHashMap<String, Double>()
          for (nodeId in layerNodes) {
            medians[nodeId] = computeMedian(nodeId, adjacentNodes, adjacency, newOrdering)
          }

          // Sort by median; ties break by the connector side the node is reached
          // through (min incoming source-port index), then by node ID. Because
          // output ports spread left→right by declaration index, this keeps a
          // node's left/right position consistent with the connectors the user
          // sees, and eliminates fan-out crossings. (Deliberate divergence from
          // the JS reference's ID-only tiebreak.)
          val sorted = layerNodes.sortedWith(
            compareBy(
              { medians.getValue(it) },
              { graph.incomingPortIndex[it] ?: Int.MAX_VALUE },
              { it },
            ),
          )

          // Update ordering
          for ((index, nodeId) in sorted.withIndex()) {
            newOrdering[nodeId] = index
          }
        }

        currentManager = LayerManager.updateOrdering(currentManager, newOrdering)
      }

      return currentManager
    }

    /**
     * Median position of a node's neighbors in the adjacent layer.
     * Odd count: middle element; even count: average of the two middles.
     * No neighbors: current position.
     */
    private fun computeMedian(
      nodeId: String,
      adjacentNodes: List<String>,
      adjacency: Map<String, List<String>>,
      ordering: Map<String, Int>,
    ): Double {
      val neighbors = adjacency[nodeId] ?: emptyList()
      val adjacentPositions = mutableListOf<Double>()

      for (neighbor in neighbors) {
        if (adjacentNodes.contains(neighbor)) {
          val pos = ordering[neighbor]
          if (pos != null) {
            adjacentPositions.add(pos.toDouble())
          }
        }
      }

      if (adjacentPositions.isEmpty()) {
        return (ordering[nodeId] ?: 0).toDouble()
      }

      adjacentPositions.sort()

      val mid = adjacentPositions.size / 2

      // Odd count: return middle element
      if (adjacentPositions.size % 2 == 1) {
        return adjacentPositions[mid]
      }

      // Even count: return average of two middle elements
      return (adjacentPositions[mid - 1] + adjacentPositions[mid]) / 2.0
    }

    /**
     * Count edge crossings between two adjacent layers (used for validation
     * and ordering-quality assertions).
     */
    fun countCrossings(graph: LayoutGraph, layerManager: LayerManager, rank: Int): Int {
      val layer1 = LayerManager.getLayerNodes(layerManager, rank)
      val layer2 = LayerManager.getLayerNodes(layerManager, rank + 1)

      if (layer1.isEmpty() || layer2.isEmpty()) return 0

      val ordering2 = layerManager.ordering

      var crossings = 0

      // Check each pair of edges
      for (i in layer1.indices) {
        for (j in (i + 1) until layer1.size) {
          val node1 = layer1[i]
          val node2 = layer1[j]

          val children1 = graph.flowChildMap[node1] ?: emptyList()
          val children2 = graph.flowChildMap[node2] ?: emptyList()

          for (child1 in children1) {
            val pos1 = ordering2[child1] ?: continue

            for (child2 in children2) {
              val pos2 = ordering2[child2] ?: continue

              // Crossing if order is reversed
              if (pos1 > pos2) {
                crossings++
              }
            }
          }
        }
      }

      return crossings
    }

    /**
     * Total edge crossings across all adjacent layer pairs.
     */
    fun countTotalCrossings(graph: LayoutGraph, layerManager: LayerManager): Int {
      var total = 0

      for (i in 0 until layerManager.sortedRanks.size - 1) {
        val rank = layerManager.sortedRanks[i]
        total += countCrossings(graph, layerManager, rank)
      }

      return total
    }
  }
}
