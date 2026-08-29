package expo.modules.sugiyama.layout

/**
 * A single stacking placement: [target] is placed beside [source] in the same
 * rank column, [direction] steps in flow space (+1 right, -1 left) at
 * [XCoordinateAssigner]'s stack gap.
 */
data class StackItem(
  val target: String,
  val source: String,
  val direction: Int,
)

/**
 * Stage 5.5 - Stack planning for stacking edges (declared perpendicular port
 * sides). For every stacking edge whose endpoints share a rank, the target is
 * taken out of the flow column and stacked beside its source: `source edge +
 * gap` in the port side's direction. Chains (a stacked target that is itself
 * the source of another stacking edge) are processed source-first, so each
 * placement is relative to an already-final position.
 *
 * Plan determinism: items keep edge declaration order, ties in the topological
 * order break by declaration order, and stacking cycles fall back to a
 * declaration-order tail (their positions then use the caller's defensive
 * fallback anchor).
 */
object StackPlanner {

  fun plan(
    graph: LayoutGraph,
    ranks: Map<String, Int>,
    stackedTargets: Set<String>,
  ): Map<Int, List<StackItem>> {
    val byRank = LinkedHashMap<Int, MutableList<StackItem>>()

    for (edge in graph.stackingEdges) {
      val sourceRank = ranks[edge.source] ?: continue
      val targetRank = ranks[edge.target] ?: continue
      // Same column only: a stacking edge whose endpoints span ranks renders
      // as a regular edge (the target's column is decided by its rank).
      if (sourceRank != targetRank) continue
      if (edge.target !in stackedTargets) continue
      byRank.getOrPut(targetRank) { mutableListOf() }
        .add(StackItem(edge.target, edge.source, edge.direction))
    }

    val result = LinkedHashMap<Int, List<StackItem>>()
    for ((rank, items) in byRank) {
      result[rank] = topoSort(items)
    }
    return result
  }

  /**
   * Order stack items so every target is placed after its source. Items are
   * vertices; item A precedes item B when A.target == B.source. Kahn's
   * algorithm with a declaration-order ready queue; cycle leftovers keep
   * declaration order (they fall back to the caller's anchor).
   */
  private fun topoSort(items: List<StackItem>): List<StackItem> {
    if (items.size <= 1) return items
    val n = items.size
    val inDegree = IntArray(n)
    val dependents = Array(n) { mutableListOf<Int>() }
    for (j in items.indices) {
      val source = items[j].source
      for (i in items.indices) {
        if (i == j) continue
        if (items[i].target == source) {
          inDegree[j] += 1
          dependents[i].add(j)
        }
      }
    }

    val ready = ArrayDeque<Int>()
    for (i in items.indices) {
      if (inDegree[i] == 0) ready.add(i)
    }

    val ordered = ArrayList<StackItem>(n)
    val placed = BooleanArray(n)
    while (ready.isNotEmpty()) {
      val i = ready.removeFirst()
      if (placed[i]) continue
      placed[i] = true
      ordered.add(items[i])
      for (j in dependents[i]) {
        inDegree[j] -= 1
        if (inDegree[j] == 0) ready.add(j)
      }
    }
    for (i in items.indices) {
      if (!placed[i]) ordered.add(items[i])
    }
    return ordered
  }
}
