package expo.modules.sugiyama.layout

fun node(id: String, width: Double = 150.0, height: Double = 64.0): LayoutNode =
  LayoutNode(id, width, height)

fun edge(
  id: String,
  source: String,
  target: String,
  sourcePortIndex: Int = 0,
  sourcePortSide: PortSide? = null,
): LayoutEdge =
  LayoutEdge(id, source, target, sourcePortIndex, sourcePortSide)