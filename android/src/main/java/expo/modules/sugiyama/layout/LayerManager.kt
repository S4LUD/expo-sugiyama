package expo.modules.sugiyama.layout

/**
 * A single layer in the hierarchical layout (spec §11.4).
 */
data class Layer(
  val rank: Int,
  val nodes: List<String>,
)

/**
 * Adjacent-layer lookup direction (mirrors `layer-manager.ts`).
 */
enum class Direction {
  ABOVE,
  BELOW,
}

/**
 * Layer manager for hierarchical layout: groups nodes by rank and tracks
 * ordering within layers (port of `features/layout-engine/layer-manager.ts`,
 * spec §11.4).
 *
 * Initial ordering within a layer is sorted by node ID for determinism.
 */
class LayerManager(
  val layers: Map<Int, Layer>,
  val ranks: Map<String, Int>,
  val ordering: Map<String, Int>,
  val sortedRanks: List<Int>,
  val maxRank: Int,
) {
  companion object {
    /**
     * Build the layer groups for every ranked node. [exclude] removes nodes
     * from the layer membership while keeping their ranks (used to hide
     * stacked targets from the ordering/width/X passes, which position them
     * via `StackPlanner` instead).
     */
    fun build(
      graph: LayoutGraph,
      ranks: Map<String, Int>,
      exclude: Set<String> = emptySet(),
    ): LayerManager {
      val layerNodes = LinkedHashMap<Int, MutableList<String>>()

      for (nodeId in graph.nodes.keys) {
        if (nodeId in exclude) continue
        val rank = ranks[nodeId] ?: 0
        layerNodes.getOrPut(rank) { mutableListOf() }.add(nodeId)
      }

      val sortedRanks = layerNodes.keys.sorted()
      val maxRank = sortedRanks.lastOrNull() ?: 0

      val layers = LinkedHashMap<Int, Layer>()
      val ordering = LinkedHashMap<String, Int>()

      for (rank in sortedRanks) {
        val nodes = (layerNodes[rank] ?: emptyList()).sorted()

        for ((index, nodeId) in nodes.withIndex()) {
          ordering[nodeId] = index
        }

        layers[rank] = Layer(rank, nodes.toList())
      }

      return LayerManager(
        layers = LinkedHashMap(layers),
        ranks = LinkedHashMap(ranks),
        ordering = LinkedHashMap(ordering),
        sortedRanks = sortedRanks.toList(),
        maxRank = maxRank,
      )
    }

    /**
     * Re-sort every layer by a new ordering map. The sort is stable
     * (matches the JS Array.prototype.sort stability).
     */
    fun updateOrdering(manager: LayerManager, newOrdering: Map<String, Int>): LayerManager {
      val layers = LinkedHashMap<Int, Layer>()

      for ((rank, layer) in manager.layers) {
        val nodes = layer.nodes.sortedBy { newOrdering[it] ?: 0 }
        layers[rank] = Layer(rank, nodes.toList())
      }

      return LayerManager(
        layers = LinkedHashMap(layers),
        ranks = manager.ranks,
        ordering = LinkedHashMap(newOrdering),
        sortedRanks = manager.sortedRanks,
        maxRank = manager.maxRank,
      )
    }

    fun getLayerNodes(manager: LayerManager, rank: Int): List<String> =
      manager.layers[rank]?.nodes ?: emptyList()

    fun getNodeRank(manager: LayerManager, nodeId: String): Int? = manager.ranks[nodeId]

    fun getNodeOrder(manager: LayerManager, nodeId: String): Int? = manager.ordering[nodeId]

    fun getAdjacentLayer(manager: LayerManager, rank: Int, direction: Direction): List<String> {
      val targetRank = if (direction == Direction.ABOVE) rank - 1 else rank + 1
      return getLayerNodes(manager, targetRank)
    }
  }
}
