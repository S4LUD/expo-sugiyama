import Foundation
@testable import ExpoSugiyama

func node(_ id: String, width: Double = 150.0, height: Double = 64.0) -> LayoutNode {
  return LayoutNode(id: id, width: width, height: height)
}

func edge(
  _ id: String,
  source: String,
  target: String,
  sourcePortIndex: Int = 0,
  sourcePortSide: PortSide? = nil
) -> LayoutEdge {
  return LayoutEdge(
    id: id,
    source: source,
    target: target,
    sourcePortIndex: sourcePortIndex,
    sourcePortSide: sourcePortSide
  )
}

func graph(_ pairs: (String, String)...) -> LayoutGraph {
  var nodeIds: [String] = []
  var seen: Set<String> = []
  for (source, target) in pairs {
    for id in [source, target] where !seen.contains(id) {
      seen.insert(id)
      nodeIds.append(id)
    }
  }
  let nodes = nodeIds.map { node($0) }
  let edges = pairs.enumerated().map { edge("e\($0.offset)", source: $0.element.0, target: $0.element.1) }
  return LayoutGraph.build(nodes: nodes, edges: edges)
}
