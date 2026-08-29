package expo.modules.sugiyama.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 4 tests (spec A.9): leaf vs parent widths, multi-level subtrees,
 * nodesep effects, and custom dimensions.
 */
class WidthAllocatorTest {
  private fun managerOf(g: LayoutGraph) =
    LayerManager.build(g, LayerAssigner.assignLayers(g))

  @Test
  fun leafSubtreeWidthEqualsNodeWidth() {
    val g = LayoutGraph.build(listOf(node("leaf")), emptyList())
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    assertEquals(150.0, allocation.subtreeWidths.getValue("leaf"), 0.0)
  }

  @Test
  fun parentSubtreeWidthSumsChildren() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    // 150 + 150 + nodesep(60) = 360
    assertEquals(360.0, allocation.subtreeWidths.getValue("r"), 0.0)
    assertEquals(150.0, allocation.subtreeWidths.getValue("a"), 0.0)
  }

  @Test
  fun multiLevelSubtreeIsSumOfLeafDescendants() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c")),
      listOf(edge("e0", "r", "a"), edge("e1", "a", "b"), edge("e2", "a", "c")),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    // b, c: 150 each; a: 150 + 150 + 60 = 360; r: 360
    assertEquals(360.0, allocation.subtreeWidths.getValue("a"), 0.0)
    assertEquals(360.0, allocation.subtreeWidths.getValue("r"), 0.0)
  }

  @Test
  fun nodesepIncreasesParentWidth() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val tight = WidthAllocator.allocateWidths(g, managerOf(g), nodesep = 20.0)
    val wide = WidthAllocator.allocateWidths(g, managerOf(g), nodesep = 100.0)

    assertEquals(320.0, tight.subtreeWidths.getValue("r"), 0.0)
    assertEquals(400.0, wide.subtreeWidths.getValue("r"), 0.0)
  }

  @Test
  fun customDimensionsAreApplied() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b")),
    )
    val custom = mapOf(
      "a" to NodeDimensions(200.0, 90.0),
      "b" to NodeDimensions(200.0, 90.0),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g), nodeDimensions = custom)

    assertEquals(200.0, allocation.subtreeWidths.getValue("a"), 0.0)
    // 200 + 200 + 60 = 460
    assertEquals(460.0, allocation.subtreeWidths.getValue("r"), 0.0)
    assertEquals(90.0, allocation.dimensions.getValue("b").height, 0.0)
    // Defaults still apply to the unlisted node
    assertEquals(150.0, allocation.dimensions.getValue("r").width, 0.0)
  }

  @Test
  fun childrenWidthComputesAreaForParent() {
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c")),
      listOf(edge("e0", "r", "a"), edge("e1", "r", "b"), edge("e2", "r", "c")),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    // 150*3 + 2*60 = 570
    assertEquals(570.0, WidthAllocator.computeChildrenWidth(allocation, listOf("a", "b", "c")), 0.0)
    assertEquals(0.0, WidthAllocator.computeChildrenWidth(allocation, emptyList()), 0.0)
  }

  @Test
  fun countsSharedDescendantsOnceInOverlappingDagSubtrees() {
    // r -> a, r -> b; a -> c, a -> d; b -> d, b -> e (d is shared)
    val g = LayoutGraph.build(
      listOf(node("r"), node("a"), node("b"), node("c"), node("d"), node("e")),
      listOf(
        edge("e0", "r", "a"),
        edge("e1", "r", "b"),
        edge("e2", "a", "c"),
        edge("e3", "a", "d"),
        edge("e4", "b", "d"),
        edge("e5", "b", "e"),
      ),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    // a spans its two children: 150 + 60 + 150 = 360.
    assertEquals(360.0, allocation.subtreeWidths.getValue("a"), 0.0)
    // d is placed under a (its first parent), so b's subtree is just
    // itself and its child e.
    assertEquals(150.0, allocation.subtreeWidths.getValue("b"), 0.0)
    // r spans the widest descendant level: {a, b} = 360 or {c, d, e} =
    // 570. The naive children sum (360 + 60 + 360 = 780) would
    // double-count d.
    assertEquals(570.0, allocation.subtreeWidths.getValue("r"), 0.0)
  }

  @Test
  fun countsSharedNodeOnlyInItsFirstParentSubtree() {
    // ra -> s, rb -> s: s is placed under ra (its first parent), so
    // rb's subtree is just itself and its width stays its node width.
    val g = LayoutGraph.build(
      listOf(node("ra"), node("rb"), node("s")),
      listOf(edge("e0", "ra", "s"), edge("e1", "rb", "s")),
    )
    val allocation = WidthAllocator.allocateWidths(g, managerOf(g))

    assertEquals(150.0, allocation.subtreeWidths.getValue("s"), 0.0)
    assertEquals(150.0, allocation.subtreeWidths.getValue("ra"), 0.0)
    assertEquals(150.0, allocation.subtreeWidths.getValue("rb"), 0.0)
  }
}