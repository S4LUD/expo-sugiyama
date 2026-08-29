package expo.modules.sugiyama.layout

/**
 * Layout direction (default: 'TB').
 *
 * The pipeline always computes in "flow space" where ranks are vertical rows
 * (rank 0 first); `computeLayout` runs that pipeline with the axis quantities
 * swapped for LR/RL and then orients the result (see [computeLayout]).
 */
enum class LayoutRankDir {
  TB,
  BT,
  LR,
  RL,
}

/**
 * Layout options (mirrors spec §10.3 defaults; the same values the workflow
 * feature passes today: margins 40, node 150x64, orderingPasses 4).
 *
 * [nodesep]/[ranksep] default to **null**: the engine then derives them from
 * the graph's canonical default node size ([nodeWidth]/[nodeHeight], ~20% of
 * the width between same-rank nodes, ~50% of the height between ranks). The
 * derived gap is composition-independent — a node being added or resized never
 * changes the spacing, so every edge in a graph is the same length and freshly
 * spawned edges match laid-out ones in every rankdir. Explicit values always
 * win.
 *
 * [alignBranches] is reserved for the opt-in alignment improvement (ADR-005);
 * the default `false` preserves exact JS parity and requires no behavior.
 */
data class LayoutOptions(
  val rankdir: LayoutRankDir = LayoutRankDir.TB,
  val nodesep: Double? = null,
  val ranksep: Double? = null,
  val marginx: Double = 40.0,
  val marginy: Double = 40.0,
  val nodeWidth: Double = 150.0,
  val nodeHeight: Double = 64.0,
  val orderingPasses: Int = 4,
  val alignBranches: Boolean = false,
  val cyclePolicy: CyclePolicy = CyclePolicy.TOLERANT,
)

/**
 * A single node position (top-left, graph units).
 */
data class LayoutPosition(
  val x: Double,
  val y: Double,
)

/**
 * Algorithm output (spec §13.5): node positions plus layout dimensions.
 */
class LayoutOutput(
  val positions: Map<String, LayoutPosition>,
  val width: Double,
  val height: Double,
)

/**
 * Orchestrates the six-stage Sugiyama pipeline
 * (line-for-line port of `features/layout-engine/dagre.ts`
 * `computeLayout`, spec §11.1).
 *
 *   1. Build graph (adjacency maps)
 *   2. Assign layers (longest path)
 *   3. Order nodes (median heuristic, orderingPasses sweeps)
 *   4. Allocate subtree widths
 *   5. Assign X coordinates (parent centering + stacking placements)
 *   6. Assign Y coordinates (rank rows)
 *
 * Layout directions: non-TB directions run the same pipeline in "flow space"
 * and then orient the result:
 * - BT: mirror Y (rank 0 at the bottom)
 * - LR: swap nodesep/ranksep, marginx/marginy, node width/height, then
 *   transpose X/Y (ranks become columns)
 * - RL: same as LR, then mirror X (rank 0 at the right)
 * Margins are canvas-space: `marginx` is always left/right and `marginy`
 * always top/bottom, regardless of direction.
 *
 * Stacking edges (declared perpendicular port sides): an edge leaving a port
 * on a side perpendicular to the flow axis stacks its target beside the
 * source in the same column (`StackPlanner`), instead of one rank downstream.
 * The pipeline runs on the flow-only adjacency; stacked targets are placed
 * after each rank's flow members and still get the rank's row (Y), so later
 * ranks center under them like any parent.
 *
 * Deviation from the JS reference (per ADR-3): the reference always throws on
 * cycles; this port defaults to tolerant. The JS `validateLayerAssignment`
 * sanity check is omitted, as tolerant mode intentionally permits non-strict
 * rank gaps on cycle back-edges.
 */
object DagreLayout {

  /**
   * Derive default spacing from the graph's canonical default node size: ~20%
   * of [LayoutOptions.nodeWidth] between same-rank nodes and ~50% of
   * [LayoutOptions.nodeHeight] between ranks. Derived from the canonical size
   * (not the per-node average) so the gap is composition-independent — a node
   * being added, removed or resized never changes the spacing, every edge in a
   * graph keeps the same length, and freshly spawned edges match laid-out ones
   * in every rankdir. Mirrors `deriveSpacing` in dagre.ts.
   *
   * Public so consumers that must reproduce the engine's exact cross-rank gap
   * (e.g. spawning a node at the position the next layout would assign it) can
   * reuse the single source of truth instead of reimplementing the formula.
   */
  fun derivedSpacing(options: LayoutOptions): Pair<Double, Double> {
    return kotlin.math.ceil(0.2 * options.nodeWidth) to kotlin.math.ceil(0.5 * options.nodeHeight)
  }

  /**
   * Classify declared perpendicular port sides into stacking edges with their
   * flow-space direction. In LR/RL the cross axis is Y, so TOP/BOTTOM stack;
   * in TB/BT the cross axis is X, so LEFT/RIGHT stack. Sides along the flow
   * axis and undeclared sides are never stacking.
   */
  fun classifyStacking(
    edges: List<LayoutEdge>,
    rankdir: LayoutRankDir,
  ): List<StackingEdge> {
    val horizontal = rankdir == LayoutRankDir.LR || rankdir == LayoutRankDir.RL
    return edges.mapNotNull { edge ->
      val side = edge.sourcePortSide ?: return@mapNotNull null
      val direction = when {
        !horizontal && (side == PortSide.LEFT || side == PortSide.RIGHT) ->
          if (side == PortSide.RIGHT) 1 else -1
        horizontal && (side == PortSide.TOP || side == PortSide.BOTTOM) ->
          if (side == PortSide.BOTTOM) 1 else -1
        else -> return@mapNotNull null
      }
      StackingEdge(edge.id, edge.source, edge.target, direction)
    }
  }

  fun computeLayout(
    nodes: List<LayoutNode>,
    edges: List<LayoutEdge>,
    options: LayoutOptions = LayoutOptions(),
  ): LayoutOutput {
    val derived = derivedSpacing(options)
    val resolvedNodesep = options.nodesep ?: derived.first
    val resolvedRanksep = options.ranksep ?: derived.second
    val horizontal = options.rankdir == LayoutRankDir.LR || options.rankdir == LayoutRankDir.RL

    // Flow-space quantities: for LR/RL the within-rank axis is Y and the rank
    // axis is X, so the spacing values and node-size defaults swap.
    val flowNodesep = if (horizontal) resolvedRanksep else resolvedNodesep
    val flowRanksep = if (horizontal) resolvedNodesep else resolvedRanksep
    val flowMarginx = if (horizontal) options.marginy else options.marginx
    val flowMarginy = if (horizontal) options.marginx else options.marginy
    val flowNodeWidth = if (horizontal) options.nodeHeight else options.nodeWidth
    val flowNodeHeight = if (horizontal) options.nodeWidth else options.nodeHeight

    // Per-node dimensions in flow space (width along the rank axis).
    val nodeDimensions = LinkedHashMap<String, NodeDimensions>()
    for (node in nodes) {
      nodeDimensions[node.id] = if (horizontal) {
        NodeDimensions(width = node.height, height = node.width)
      } else {
        NodeDimensions(width = node.width, height = node.height)
      }
    }

    // Handle empty graph (canvas-space margins are direction-independent)
    if (nodes.isEmpty()) {
      return LayoutOutput(
        positions = emptyMap(),
        width = options.marginx * 2.0,
        height = options.marginy * 2.0,
      )
    }

    // Step 1: Build graph (full adjacency + flow-only views + stacking edges)
    val stacking = classifyStacking(edges, options.rankdir)
    val graph = LayoutGraph.build(nodes, edges, stacking)

    // Step 2: Assign layers using longest path algorithm over flow edges;
    // stacking-only targets share their source's column.
    val ranks = LayerAssigner.assignLayers(graph, options.cyclePolicy)
    val stackedTargets = graph.nodes.keys.filter { nodeId ->
      (graph.flowInDegree[nodeId] ?: 0) == 0 &&
        (graph.stackingParentMap[nodeId]?.isNotEmpty() ?: false)
    }.toSet()

    // Step 3: Build layer manager and order nodes. Stacked targets are hidden
    // from the flow ranks (they are placed by the stack plan), while the full
    // ranks still give them a row and count their heights.
    val fullLayers = LayerManager.build(graph, ranks)
    val flowLayers = LayerManager.build(graph, ranks, exclude = stackedTargets)
    val ordered = OrderingEngine.orderNodes(graph, flowLayers, options.orderingPasses)

    // Step 4: Allocate widths (flow adjacency, all nodes incl. stacked)
    val widthAllocation = WidthAllocator.allocateWidths(
      graph = graph,
      layerManager = fullLayers,
      nodeDimensions = nodeDimensions,
      nodesep = flowNodesep,
    )

    // The stack plan is shared by the X pass (perpendicular offset beside
    // the source) and the Y pass (sibling row spread along the rank axis).
    val stackPlan = StackPlanner.plan(graph, ranks, stackedTargets)

    // Step 5: Assign X coordinates (parent centering + stacked placements)
    val xPositions = XCoordinateAssigner.assignXCoordinates(
      graph = graph,
      layerManager = ordered,
      widthAllocation = widthAllocation,
      nodesep = flowNodesep,
      marginx = flowMarginx,
      stackPlan = stackPlan,
      stackGap = flowRanksep,
    )

    // Step 6: Assign Y coordinates (full ranks: stacked targets share rows)
    val yPositions = YCoordinateAssigner.assignYCoordinates(
      layerManager = fullLayers,
      widthAllocation = widthAllocation,
      ranksep = flowRanksep,
      marginy = flowMarginy,
      rankdir = options.rankdir,
      stackPlan = stackPlan,
    )

    // Bounds in flow space
    var maxX = 0.0
    var maxY = 0.0
    for (nodeId in graph.nodes.keys) {
      val x = xPositions[nodeId] ?: 0.0
      val y = yPositions[nodeId] ?: 0.0
      val dims = widthAllocation.dimensions[nodeId]
      maxX = maxOf(maxX, x + (dims?.width ?: flowNodeWidth))
      maxY = maxOf(maxY, y + (dims?.height ?: flowNodeHeight))
    }
    val flowHeight = maxY + flowMarginy

    // Orient flow-space coordinates into canvas space.
    val positions = LinkedHashMap<String, LayoutPosition>()
    for (nodeId in graph.nodes.keys) {
      val x = xPositions[nodeId] ?: 0.0
      val y = yPositions[nodeId] ?: 0.0
      val dims = widthAllocation.dimensions[nodeId]
      val h = dims?.height ?: flowNodeHeight

      var px = x
      var py = y
      if (options.rankdir == LayoutRankDir.BT) py = flowHeight - y - h
      if (horizontal) {
        px = y
        py = x
      }
      if (options.rankdir == LayoutRankDir.RL) px = flowHeight - y - h
      positions[nodeId] = LayoutPosition(px, py)
    }

    return LayoutOutput(
      positions = positions,
      width = if (horizontal) maxY + flowMarginy else maxX + flowMarginx,
      height = if (horizontal) maxX + flowMarginx else maxY + flowMarginy,
    )
  }
}