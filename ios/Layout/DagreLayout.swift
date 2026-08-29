import Foundation

/// Layout direction (default: 'TB').
///
/// The pipeline always computes in "flow space" where ranks are vertical rows
/// (rank 0 first); `computeLayout` runs that pipeline with the axis quantities
/// swapped for LR/RL and then orients the result (see `computeLayout`).
public enum LayoutRankDir: String {
  case tb = "TB"
  case bt = "BT"
  case lr = "LR"
  case rl = "RL"
}

/// Layout options (mirrors spec §10.3 defaults; margins 40, node 150x64,
/// orderingPasses 4).
///
/// `nodesep`/`ranksep` default to **nil**: the engine then derives them from
/// the graph's canonical default node size (`nodeWidth`/`nodeHeight`, ~20% of
/// the width between same-rank nodes, ~50% of the height between ranks). The
/// derived gap is composition-independent — a node being added or resized never
/// changes the spacing, so every edge in a graph keeps the same length and
/// freshly spawned edges match laid-out ones in every rankdir. Explicit values
/// always win.
///
/// `alignBranches` is reserved for the opt-in alignment improvement (ADR-005);
/// the default `false` preserves exact JS parity and requires no behavior.
public struct LayoutOptions {
  public var rankdir: LayoutRankDir = .tb
  public var nodesep: Double?
  public var ranksep: Double?
  public var marginx: Double = 40.0
  public var marginy: Double = 40.0
  public var nodeWidth: Double = 150.0
  public var nodeHeight: Double = 64.0
  public var orderingPasses: Int = 4
  public var alignBranches: Bool = false
  public var cyclePolicy: CyclePolicy = .tolerant

  public init() {}
}

/// A single node position (top-left, graph units).
public struct LayoutPosition: Equatable {
  public let x: Double
  public let y: Double

  public init(x: Double, y: Double) {
    self.x = x
    self.y = y
  }
}

/// Algorithm output (spec §13.5): node positions plus layout dimensions.
public final class LayoutOutput {
  public let positions: [String: LayoutPosition]
  public let width: Double
  public let height: Double

  public init(positions: [String: LayoutPosition], width: Double, height: Double) {
    self.positions = positions
    self.width = width
    self.height = height
  }
}

/// Orchestrates the six-stage Sugiyama pipeline
/// (line-for-line port of `features/layout-engine/dagre.ts`
/// `computeLayout`, spec §11.1).
///
///   1. Build graph (adjacency maps)
///   2. Assign layers (longest path)
///   3. Order nodes (median heuristic, orderingPasses sweeps)
///   4. Allocate subtree widths
///   5. Assign X coordinates (parent centering)
///   6. Assign Y coordinates (rank rows)
///
/// Layout directions: non-TB directions run the same pipeline in "flow space"
/// and then orient the result:
/// - BT: mirror Y (rank 0 at the bottom)
/// - LR: swap nodesep/ranksep, marginx/marginy, node width/height, then
///   transpose X/Y (ranks become columns)
/// - RL: same as LR, then mirror X (rank 0 at the right)
/// Margins are canvas-space: `marginx` is always left/right and `marginy`
/// always top/bottom, regardless of direction.
///
/// Deviation from the JS reference (per ADR-3): the reference always throws on
/// cycles; this port defaults to tolerant. The JS `validateLayerAssignment`
/// sanity check is omitted, as tolerant mode intentionally permits non-strict
/// rank gaps on cycle back-edges.
public enum DagreLayout {
  /// Derive default spacing from the graph's canonical default node size:
  /// ~20% of `LayoutOptions.nodeWidth` between same-rank nodes and ~50% of
  /// `LayoutOptions.nodeHeight` between ranks. Derived from the canonical size
  /// (not the per-node average) so the gap is composition-independent — a node
  /// being added, removed or resized never changes the spacing, every edge in a
  /// graph keeps the same length, and freshly spawned edges match laid-out ones
  /// in every rankdir. Mirrors `deriveSpacing` in dagre.ts.
  ///
  /// Public so consumers that must reproduce the engine's exact cross-rank gap
  /// (e.g. spawning a node at the position the next layout would assign it) can
  /// reuse the single source of truth instead of reimplementing the formula.
  public static func derivedSpacing(options: LayoutOptions) -> (nodesep: Double, ranksep: Double) {
    return (ceil(0.2 * options.nodeWidth), ceil(0.5 * options.nodeHeight))
  }

  /// Classify declared perpendicular port sides into stacking edges with their
  /// flow-space direction. In LR/RL the cross axis is Y, so TOP/BOTTOM stack;
  /// in TB/BT the cross axis is X, so LEFT/RIGHT stack. Sides along the flow
  /// axis and undeclared sides are never stacking.
  static func classifyStacking(
    edges: [LayoutEdge],
    rankdir: LayoutRankDir
  ) -> [StackingEdge] {
    let horizontal = rankdir == .lr || rankdir == .rl
    return edges.compactMap { edge in
      guard let side = edge.sourcePortSide else { return nil }
      let direction: Int
      if !horizontal && (side == .left || side == .right) {
        direction = side == .right ? 1 : -1
      } else if horizontal && (side == .top || side == .bottom) {
        direction = side == .bottom ? 1 : -1
      } else {
        return nil
      }
      return StackingEdge(edgeId: edge.id, source: edge.source, target: edge.target, direction: direction)
    }
  }

  public static func computeLayout(
    nodes: [LayoutNode],
    edges: [LayoutEdge],
    options: LayoutOptions = LayoutOptions()
  ) throws -> LayoutOutput {
    let derived = derivedSpacing(options: options)
    let nodesep = options.nodesep ?? derived.nodesep
    let ranksep = options.ranksep ?? derived.ranksep
    let horizontal = options.rankdir == .lr || options.rankdir == .rl

    // Flow-space options: for LR/RL the within-rank axis is Y and the rank
    // axis is X, so the spacing quantities and node-size defaults swap.
    let flowNodesep = horizontal ? ranksep : nodesep
    let flowRanksep = horizontal ? nodesep : ranksep
    let flowMarginx = horizontal ? options.marginy : options.marginx
    let flowMarginy = horizontal ? options.marginx : options.marginy
    let flowNodeWidth = horizontal ? options.nodeHeight : options.nodeWidth
    let flowNodeHeight = horizontal ? options.nodeWidth : options.nodeHeight

    // Per-node dimensions in flow space (width along the rank axis).
    var nodeDimensions: [String: NodeDimensions] = [:]
    for node in nodes {
      nodeDimensions[node.id] = horizontal
        ? NodeDimensions(width: node.height, height: node.width)
        : NodeDimensions(width: node.width, height: node.height)
    }

    // Handle empty graph (canvas-space margins are direction-independent)
    if nodes.isEmpty {
      return LayoutOutput(
        positions: [:],
        width: options.marginx * 2.0,
        height: options.marginy * 2.0
      )
    }

    // Step 1: Build graph (full adjacency + flow-only views + stacking edges)
    let stacking = classifyStacking(edges: edges, rankdir: options.rankdir)
    let graph = LayoutGraph.build(nodes: nodes, edges: edges, stacking: stacking)

    // Step 2: Assign layers using longest path algorithm over flow edges;
    // stacking-only targets share their source's column.
    let ranks = try LayerAssigner.assignLayers(graph: graph, cyclePolicy: options.cyclePolicy)
    let stackedTargets = Set(
      graph.nodeIds.filter { nodeId in
        (graph.flowInDegree[nodeId] ?? 0) == 0 &&
          !(graph.stackingParentMap[nodeId] ?? []).isEmpty
      }
    )

    // Step 3: Build layer manager and order nodes. Stacked targets are hidden
    // from the flow ranks (they are placed by the stack plan), while the full
    // ranks still give them a row and count their heights.
    let fullLayers = LayerManager.build(graph: graph, ranks: ranks)
    let flowLayers = LayerManager.build(graph: graph, ranks: ranks, exclude: stackedTargets)
    let ordered = OrderingEngine.orderNodes(
      graph: graph,
      layerManager: flowLayers,
      passes: options.orderingPasses
    )

    // Step 4: Allocate widths (flow adjacency, all nodes incl. stacked)
    let widthAllocation = WidthAllocator.allocateWidths(
      graph: graph,
      layerManager: fullLayers,
      nodeDimensions: nodeDimensions,
      nodesep: flowNodesep
    )

    // The stack plan is shared by the X pass (perpendicular offset beside
    // the source) and the Y pass (sibling row spread along the rank axis).
    let stackPlan = StackPlanner.plan(graph: graph, ranks: ranks, stackedTargets: stackedTargets)

    // Step 5: Assign X coordinates (parent centering + stacked placements)
    let xPositions = XCoordinateAssigner.assignXCoordinates(
      graph: graph,
      layerManager: ordered,
      widthAllocation: widthAllocation,
      nodesep: flowNodesep,
      marginx: flowMarginx,
      stackPlan: stackPlan,
      stackGap: flowRanksep
    )

    // Step 6: Assign Y coordinates (full ranks: stacked targets share rows)
    let yPositions = YCoordinateAssigner.assignYCoordinates(
      layerManager: fullLayers,
      widthAllocation: widthAllocation,
      ranksep: flowRanksep,
      marginy: flowMarginy,
      rankdir: options.rankdir,
      stackPlan: stackPlan
    )

    // Bounds in flow space
    var maxX = 0.0
    var maxY = 0.0
    for nodeId in graph.nodeIds {
      let x = xPositions[nodeId] ?? 0.0
      let y = yPositions[nodeId] ?? 0.0
      let dims = widthAllocation.dimensions[nodeId]
      maxX = max(maxX, x + (dims?.width ?? flowNodeWidth))
      maxY = max(maxY, y + (dims?.height ?? flowNodeHeight))
    }
    let flowHeight = maxY + flowMarginy

    // Orient flow-space coordinates into canvas space.
    var positions: [String: LayoutPosition] = [:]
    for nodeId in graph.nodeIds {
      let x = xPositions[nodeId] ?? 0.0
      let y = yPositions[nodeId] ?? 0.0
      let dims = widthAllocation.dimensions[nodeId]
      let h = dims?.height ?? flowNodeHeight

      var px = x
      var py = y
      if options.rankdir == .bt { py = flowHeight - y - h }
      if horizontal {
        px = y
        py = x
      }
      if options.rankdir == .rl { px = flowHeight - y - h }
      positions[nodeId] = LayoutPosition(x: px, y: py)
    }

    return LayoutOutput(
      positions: positions,
      width: horizontal ? maxY + flowMarginy : maxX + flowMarginx,
      height: horizontal ? maxX + flowMarginx : maxY + flowMarginy
    )
  }
}
