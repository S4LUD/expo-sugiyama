import Foundation

/// Node snapshot for layout computation (mirrors spec §13.2).
/// Width/height are pre-validated by the bridge; the algorithm layer
/// only consumes ids and dimensions.
public struct LayoutNode {
  public let id: String
  public let width: Double
  public let height: Double

  public init(id: String, width: Double, height: Double) {
    self.id = id
    self.width = width
    self.height = height
  }
}

/// Edge snapshot for layout computation (mirrors spec §13.3).
///
/// `sourcePortIndex` is the index of the source port within the source node's
/// declared port order. It drives the connector-consistent ordering tiebreak
/// (deliberate divergence from the JS reference's ID tiebreak): since the
/// canvas spreads output ports left→right by declaration index, this index is
/// the visual side of the connector the edge leaves from.
///
/// `sourcePortSide` is the declared side of the source port, when any. In
/// LR/RL a source side of TOP/BOTTOM, and in TB/BT a source side of
/// LEFT/RIGHT, marks the edge as a *stacking edge*: its target is placed
/// beside the source in the same column instead of one rank downstream (see
/// `StackPlanner`). Undeclared sides keep the classic flow behavior.
public struct LayoutEdge {
  public let id: String
  public let source: String
  public let target: String
  public let sourcePortIndex: Int
  public let sourcePortSide: PortSide?

  public init(
    id: String,
    source: String,
    target: String,
    sourcePortIndex: Int = 0,
    sourcePortSide: PortSide? = nil
  ) {
    self.id = id
    self.source = source
    self.target = target
    self.sourcePortIndex = sourcePortIndex
    self.sourcePortSide = sourcePortSide
  }
}

/// A declared node-edge side (the side of the node the port sits on).
public enum PortSide: String {
  case top = "TOP"
  case bottom = "BOTTOM"
  case left = "LEFT"
  case right = "RIGHT"
}

/// A classified stacking edge: `edgeId` references the source `LayoutEdge`,
/// `direction` is the flow-space direction the target is placed in (+1 right,
/// -1 left), derived from the source port side and the active rankdir.
public struct StackingEdge {
  public let edgeId: String
  public let source: String
  public let target: String
  public let direction: Int

  public init(edgeId: String, source: String, target: String, direction: Int) {
    self.edgeId = edgeId
    self.source = source
    self.target = target
    self.direction = direction
  }
}

/// Immutable graph snapshot with precomputed adjacency indexes
/// (structural port of `features/layout-engine/graph.ts` `buildGraph`,
/// spec §11.2).
///
/// Determinism: adjacency arrays preserve input edge order; `roots` follows
/// node insertion order (matching the Kotlin LinkedHashMap semantics).
///
/// `incomingPortIndex` maps each node with incoming edges to the MINIMUM
/// `sourcePortIndex` across those edges (the leftmost connector it is reached
/// through). Nodes without incoming edges are absent from the map.
///
/// Alongside the full adjacency (`parentMap`/`childMap`/`inDegree`/`outDegree`)
/// the graph exposes flow-only views (`flowParentMap`/`flowChildMap`/
/// `flowInDegree`) that exclude `stackingEdges` — the hierarchical pipeline
/// (ranking, ordering, width and coordinate assignment) runs on the flow view,
/// while the stack planner consumes `stackingEdges`/`stackingParentMap`.
final class LayoutGraph {
  let nodes: [String: LayoutNode]
  let parentMap: [String: [String]]
  let childMap: [String: [String]]
  let inDegree: [String: Int]
  let outDegree: [String: Int]
  let roots: [String]
  /// Node ids in insertion order (mirrors Kotlin LinkedHashMap key order).
  let nodeIds: [String]
  let incomingPortIndex: [String: Int]
  let stackingEdges: [StackingEdge]
  let flowParentMap: [String: [String]]
  let flowChildMap: [String: [String]]
  let flowInDegree: [String: Int]
  let stackingParentMap: [String: [String]]

  init(
    nodes: [String: LayoutNode],
    parentMap: [String: [String]],
    childMap: [String: [String]],
    inDegree: [String: Int],
    outDegree: [String: Int],
    roots: [String],
    nodeIds: [String],
    incomingPortIndex: [String: Int],
    stackingEdges: [StackingEdge],
    flowParentMap: [String: [String]],
    flowChildMap: [String: [String]],
    flowInDegree: [String: Int],
    stackingParentMap: [String: [String]]
  ) {
    self.nodes = nodes
    self.parentMap = parentMap
    self.childMap = childMap
    self.inDegree = inDegree
    self.outDegree = outDegree
    self.roots = roots
    self.nodeIds = nodeIds
    self.incomingPortIndex = incomingPortIndex
    self.stackingEdges = stackingEdges
    self.flowParentMap = flowParentMap
    self.flowChildMap = flowChildMap
    self.flowInDegree = flowInDegree
    self.stackingParentMap = stackingParentMap
  }

  /// Build a LayoutGraph from flat node/edge lists.
  /// - Duplicate node ids: last occurrence wins
  /// - Edges referencing missing nodes: skipped
  /// - Self-loops: skipped
  static func build(
    nodes: [LayoutNode],
    edges: [LayoutEdge],
    stacking: [StackingEdge] = []
  ) -> LayoutGraph {
    var nodeMap: [String: LayoutNode] = [:]
    var nodeIds: [String] = []
    var seen: Set<String> = []
    for node in nodes {
      nodeMap[node.id] = node
      if !seen.contains(node.id) {
        seen.insert(node.id)
        nodeIds.append(node.id)
      }
    }

    var parentMap: [String: [String]] = [:]
    var childMap: [String: [String]] = [:]
    var inDegree: [String: Int] = [:]
    var outDegree: [String: Int] = [:]
    var incomingPortIndex: [String: Int] = [:]

    for node in nodes {
      parentMap[node.id] = []
      childMap[node.id] = []
      inDegree[node.id] = 0
      outDegree[node.id] = 0
    }

    for edge in edges {
      let sourceId = edge.source
      let targetId = edge.target

      if nodeMap[sourceId] == nil || nodeMap[targetId] == nil {
        continue
      }
      if sourceId == targetId {
        continue
      }

      childMap[sourceId]?.append(targetId)
      parentMap[targetId]?.append(sourceId)

      outDegree[sourceId] = (outDegree[sourceId] ?? 0) + 1
      inDegree[targetId] = (inDegree[targetId] ?? 0) + 1

      if let previous = incomingPortIndex[targetId] {
        if edge.sourcePortIndex < previous {
          incomingPortIndex[targetId] = edge.sourcePortIndex
        }
      } else {
        incomingPortIndex[targetId] = edge.sourcePortIndex
      }
    }

    // Flow-only views: everything the pipeline sees minus stacking edges.
    // Adjacency arrays keep the input edge order.
    let stackingIds = Set(stacking.map { $0.edgeId })
    var flowParentMap: [String: [String]] = [:]
    var flowChildMap: [String: [String]] = [:]
    var flowInDegree: [String: Int] = [:]
    for node in nodes {
      flowParentMap[node.id] = []
      flowChildMap[node.id] = []
      flowInDegree[node.id] = 0
    }
    for edge in edges {
      if stackingIds.contains(edge.id) { continue }
      if nodeMap[edge.source] == nil || nodeMap[edge.target] == nil { continue }
      if edge.source == edge.target { continue }
      flowChildMap[edge.source]?.append(edge.target)
      flowParentMap[edge.target]?.append(edge.source)
      flowInDegree[edge.target] = (flowInDegree[edge.target] ?? 0) + 1
    }

    // Stacking adjacency: per-node list of stacking parents (edge order).
    var stackingParentMap: [String: [String]] = [:]
    var validStacking: [StackingEdge] = []
    for item in stacking {
      if nodeMap[item.source] == nil || nodeMap[item.target] == nil { continue }
      if item.source == item.target { continue }
      validStacking.append(item)
      stackingParentMap[item.target, default: []].append(item.source)
    }

    var roots: [String] = []
    for nodeId in nodeIds {
      if inDegree[nodeId] == 0 {
        roots.append(nodeId)
      }
    }

    return LayoutGraph(
      nodes: nodeMap,
      parentMap: parentMap,
      childMap: childMap,
      inDegree: inDegree,
      outDegree: outDegree,
      roots: roots,
      nodeIds: nodeIds,
      incomingPortIndex: incomingPortIndex,
      stackingEdges: validStacking,
      flowParentMap: flowParentMap,
      flowChildMap: flowChildMap,
      flowInDegree: flowInDegree,
      stackingParentMap: stackingParentMap
    )
  }
}
