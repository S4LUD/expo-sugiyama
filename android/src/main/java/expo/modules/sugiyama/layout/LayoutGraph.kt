package expo.modules.sugiyama.layout

/**
 * Node snapshot for layout computation (mirrors spec §13.2).
 * Width/height are pre-validated by the bridge; the algorithm layer
 * only consumes ids and dimensions.
 */
data class LayoutNode(
  val id: String,
  val width: Double,
  val height: Double,
)

/**
 * Edge snapshot for layout computation (mirrors spec §13.3).
 *
 * [sourcePortIndex] is the index of the source port within the source node's
 * declared port order. It drives the connector-consistent ordering tiebreak
 * (deliberate divergence from the JS reference's ID tiebreak): since the
 * canvas spreads output ports left→right by declaration index, this index is
 * the visual side of the connector the edge leaves from.
 *
 * [sourcePortSide] is the declared side of the source port, when any. In
 * [LayoutRankDir.LR]/[LayoutRankDir.RL] a source side of [PortSide.TOP] or
 * [PortSide.BOTTOM], and in [LayoutRankDir.TB]/[LayoutRankDir.BT] a source
 * side of [PortSide.LEFT] or [PortSide.RIGHT], marks the edge as a
 * *stacking edge*: its target is placed beside the source in the same column
 * instead of one rank downstream (see `StackPlanner`). Undeclared sides keep
 * the classic flow behavior.
 */
data class LayoutEdge(
  val id: String,
  val source: String,
  val target: String,
  val sourcePortIndex: Int = 0,
  val sourcePortSide: PortSide? = null,
)

/** A declared node-edge side (the side of the node the port sits on). */
enum class PortSide {
  TOP,
  BOTTOM,
  LEFT,
  RIGHT,
}

/**
 * A classified stacking edge: [edgeId] references the source [LayoutEdge],
 * [direction] is the flow-space direction the target is placed in (+1 right,
 * -1 left), derived from the source port side and the active rankdir.
 */
data class StackingEdge(
  val edgeId: String,
  val source: String,
  val target: String,
  val direction: Int,
)

/**
 * Immutable graph snapshot with precomputed adjacency indexes
 * (structural port of `features/layout-engine/graph.ts` `buildGraph`,
 * spec §11.2).
 *
 * Determinism: adjacency arrays preserve input edge order; `roots` follows
 * node insertion order (matching the JS Map iteration semantics).
 *
 * [incomingPortIndex] maps each node with incoming edges to the MINIMUM
 * `sourcePortIndex` across those edges (the leftmost connector it is reached
 * through). Nodes without incoming edges are absent from the map.
 *
 * Alongside the full adjacency ([parentMap]/[childMap]/[inDegree]/[outDegree])
 * the graph exposes flow-only views ([flowParentMap]/[flowChildMap]/
 * [flowInDegree]) that exclude [stackingEdges] — the hierarchical pipeline
 * (ranking, ordering, width and coordinate assignment) runs on the flow view,
 * while the stack planner consumes [stackingEdges]/[stackingParentMap].
 */
class LayoutGraph(
  val nodes: Map<String, LayoutNode>,
  val parentMap: Map<String, List<String>>,
  val childMap: Map<String, List<String>>,
  val inDegree: Map<String, Int>,
  val outDegree: Map<String, Int>,
  val roots: List<String>,
  val incomingPortIndex: Map<String, Int>,
  val stackingEdges: List<StackingEdge>,
  val flowParentMap: Map<String, List<String>>,
  val flowChildMap: Map<String, List<String>>,
  val flowInDegree: Map<String, Int>,
  val stackingParentMap: Map<String, List<String>>,
) {
  companion object {
    /**
     * Build a LayoutGraph from flat node/edge lists.
     * - Duplicate node ids: last occurrence wins
     * - Edges referencing missing nodes: skipped
     * - Self-loops: skipped
     */
    fun build(
      nodes: List<LayoutNode>,
      edges: List<LayoutEdge>,
      stacking: List<StackingEdge> = emptyList(),
    ): LayoutGraph {
      val nodeMap = LinkedHashMap<String, LayoutNode>()
      for (node in nodes) {
        nodeMap[node.id] = node
      }

      val parentMap = LinkedHashMap<String, MutableList<String>>()
      val childMap = LinkedHashMap<String, MutableList<String>>()
      val inDegree = LinkedHashMap<String, Int>()
      val outDegree = LinkedHashMap<String, Int>()

      for (node in nodes) {
        parentMap[node.id] = mutableListOf()
        childMap[node.id] = mutableListOf()
        inDegree[node.id] = 0
        outDegree[node.id] = 0
      }

      val incomingPortIndex = HashMap<String, Int>()
      for (edge in edges) {
        val sourceId = edge.source
        val targetId = edge.target

        if (!nodeMap.containsKey(sourceId) || !nodeMap.containsKey(targetId)) {
          continue
        }

        if (sourceId == targetId) {
          continue
        }

        childMap.getValue(sourceId).add(targetId)
        parentMap.getValue(targetId).add(sourceId)

        outDegree[sourceId] = (outDegree[sourceId] ?: 0) + 1
        inDegree[targetId] = (inDegree[targetId] ?: 0) + 1

        val previous = incomingPortIndex[targetId]
        if (previous == null || edge.sourcePortIndex < previous) {
          incomingPortIndex[targetId] = edge.sourcePortIndex
        }
      }

      // Flow-only views: everything the pipeline sees minus stacking edges.
      // Adjacency arrays keep the input edge order.
      val stackingIds = stacking.mapTo(HashSet()) { it.edgeId }
      val flowParentMap = LinkedHashMap<String, MutableList<String>>()
      val flowChildMap = LinkedHashMap<String, MutableList<String>>()
      val flowInDegree = LinkedHashMap<String, Int>()
      for (node in nodes) {
        flowParentMap[node.id] = mutableListOf()
        flowChildMap[node.id] = mutableListOf()
        flowInDegree[node.id] = 0
      }
      for (edge in edges) {
        if (edge.id in stackingIds) continue
        if (!nodeMap.containsKey(edge.source) || !nodeMap.containsKey(edge.target)) continue
        if (edge.source == edge.target) continue
        flowChildMap.getValue(edge.source).add(edge.target)
        flowParentMap.getValue(edge.target).add(edge.source)
        flowInDegree[edge.target] = (flowInDegree[edge.target] ?: 0) + 1
      }

      // Stacking adjacency: per-node list of stacking parents (edge order).
      val stackingParentMap = LinkedHashMap<String, MutableList<String>>()
      val validStacking = mutableListOf<StackingEdge>()
      for (item in stacking) {
        if (!nodeMap.containsKey(item.source) || !nodeMap.containsKey(item.target)) continue
        if (item.source == item.target) continue
        validStacking.add(item)
        stackingParentMap.getOrPut(item.target) { mutableListOf() }.add(item.source)
      }

      val roots = inDegree.filterValues { it == 0 }.keys.toList()

      return LayoutGraph(
        nodes = LinkedHashMap(nodeMap),
        parentMap = LinkedHashMap(parentMap.mapValues { it.value.toList() }),
        childMap = LinkedHashMap(childMap.mapValues { it.value.toList() }),
        inDegree = LinkedHashMap(inDegree),
        outDegree = LinkedHashMap(outDegree),
        roots = roots,
        incomingPortIndex = HashMap(incomingPortIndex),
        stackingEdges = validStacking.toList(),
        flowParentMap = LinkedHashMap(flowParentMap.mapValues { it.value.toList() }),
        flowChildMap = LinkedHashMap(flowChildMap.mapValues { it.value.toList() }),
        flowInDegree = LinkedHashMap(flowInDegree),
        stackingParentMap = LinkedHashMap(stackingParentMap.mapValues { it.value.toList() }),
      )
    }
  }
}
