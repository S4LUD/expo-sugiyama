package expo.modules.sugiyama.parity

import expo.modules.sugiyama.layout.DagreLayout
import expo.modules.sugiyama.layout.LayoutEdge
import expo.modules.sugiyama.layout.LayoutNode
import expo.modules.sugiyama.layout.LayoutOptions
import expo.modules.sugiyama.layout.LayoutRankDir
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden parity suite (spec §16): every fixture under `fixtures/` was computed
 * by the JS reference (`features/layout-engine`). This test runs the Kotlin
 * port over the exact same input and asserts bit-for-bit equality of every
 * position and dimension.
 */
class ParityTest {
  private fun resolveFixturesDir(): File {
    val candidates = listOf(
      File("../fixtures"),
      File("fixtures"),
      File(File(System.getProperty("user.dir"), ".."), "fixtures"),
    )
    return candidates.firstOrNull { it.isDirectory }
      ?: error("fixtures directory not found; tried ${candidates.joinToString(", ")}")
  }

  private fun parseOptions(json: JSONObject?): LayoutOptions {
    if (json == null) return LayoutOptions()
    fun d(key: String, fallback: Double): Double = if (json.has(key)) json.getDouble(key) else fallback
    fun i(key: String, fallback: Int): Int = if (json.has(key)) json.getInt(key) else fallback

    return LayoutOptions(
      rankdir = if (json.has("rankdir")) {
        parseRankDir(json.getString("rankdir"))
      } else {
        LayoutRankDir.TB
      },
      nodesep = d("nodesep", 60.0),
      ranksep = d("ranksep", 80.0),
      marginx = d("marginx", 40.0),
      marginy = d("marginy", 40.0),
      nodeWidth = d("nodeWidth", 150.0),
      nodeHeight = d("nodeHeight", 64.0),
      orderingPasses = i("orderingPasses", 4),
      alignBranches = if (json.has("alignBranches")) json.getBoolean("alignBranches") else false,
      cyclePolicy = expo.modules.sugiyama.layout.CyclePolicy.TOLERANT,
    )
  }

  private fun parseRankDir(value: String): LayoutRankDir = when (value) {
    "TB" -> LayoutRankDir.TB
    "BT" -> LayoutRankDir.BT
    "LR" -> LayoutRankDir.LR
    "RL" -> LayoutRankDir.RL
    else -> error("unknown rankdir: $value")
  }

  private fun parseNodes(json: JSONObject): List<LayoutNode> {
    val arr = json.getJSONArray("nodes")
    return (0 until arr.length()).map { index ->
      val item = arr.getJSONObject(index)
      LayoutNode(item.getString("id"), item.getDouble("width"), item.getDouble("height"))
    }
  }

  private fun parseEdges(json: JSONObject): List<LayoutEdge> {
    val arr = json.getJSONArray("edges")
    return (0 until arr.length()).map { index ->
      val item = arr.getJSONObject(index)
      LayoutEdge(item.getString("id"), item.getString("source"), item.getString("target"))
    }
  }

  @Test
  fun kotlinPortMatchesJsReferenceForEveryFixture() {
    val dir = resolveFixturesDir()
    val files = dir.listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name }
      ?: error("cannot list fixtures in $dir")
    assertTrue("no fixture .json files in $dir", files.isNotEmpty())

    for (file in files) {
      val json = JSONObject(file.readText())
      val options = parseOptions(json.optJSONObject("options"))
      val output = DagreLayout.computeLayout(parseNodes(json), parseEdges(json), options)

      val expected = json.getJSONObject("output")
      val expectedPositions = expected.getJSONObject("positions")

      for (key in expectedPositions.keys()) {
        val position = expectedPositions.getJSONObject(key)
        val actual = output.positions[key]
          ?: throw AssertionError("${file.name}: missing Kotlin position for $key")
        assertEquals("${file.name}: $key x", position.getDouble("x"), actual.x, 0.0)
        assertEquals("${file.name}: $key y", position.getDouble("y"), actual.y, 0.0)
      }

      assertEquals("${file.name}: position count", expectedPositions.length(), output.positions.size)
      assertEquals("${file.name}: width", expected.getDouble("width"), output.width, 0.0)
      assertEquals("${file.name}: height", expected.getDouble("height"), output.height, 0.0)
    }
  }
}