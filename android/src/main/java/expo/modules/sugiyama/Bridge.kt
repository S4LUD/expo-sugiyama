package expo.modules.sugiyama

import expo.modules.kotlin.exception.CodedException
import expo.modules.sugiyama.layout.CyclePolicy
import expo.modules.sugiyama.layout.DagreLayout
import expo.modules.sugiyama.layout.LayoutCycleException
import expo.modules.sugiyama.layout.LayoutEdge
import expo.modules.sugiyama.layout.LayoutNode
import expo.modules.sugiyama.layout.LayoutOptions
import expo.modules.sugiyama.layout.LayoutRankDir
import expo.modules.sugiyama.layout.PortSide
import kotlin.math.floor

/**
 * Bridge layer (spec §10.5, §14, A.5). Coerces and validates the JS payload,
 * clamps options, runs [DagreLayout], and serializes the result. It contains
 * no algorithm logic.
 *
 * Error contract (§15):
 *   - Invalid nodes/edges -> skipped (never thrown); a partial layout is returned
 *   - Cycle + cyclePolicy=throw -> reject with CYCLE_DETECTED
 *   - Unexpected failure -> reject with INTERNAL
 */
object Bridge {
  private class BridgeException(
    code: String,
    message: String,
    cause: Throwable? = null,
  ) : CodedException(code, message, cause)

  data class ParsedInput(
    val nodes: List<LayoutNode>,
    val edges: List<LayoutEdge>,
    val options: LayoutOptions,
    val skipped: List<Map<String, String>>,
  )

  /** Full compute for the AsyncFunction (A.5). */
  fun compute(payload: Map<String, Any?>): Map<String, Any?> {
    val input = parsePayload(payload)
    val output = try {
      DagreLayout.computeLayout(input.nodes, input.edges, input.options)
    } catch (error: LayoutCycleException) {
      throw BridgeException("CYCLE_DETECTED", error.message ?: "Cycle detected in graph", error)
    } catch (error: Throwable) {
      throw BridgeException("INTERNAL", error.message ?: "Unexpected internal failure", error)
    }

    val positions = LinkedHashMap<String, Map<String, Double>>(input.nodes.size)
    for (node in input.nodes) {
      val position = output.positions.getValue(node.id)
      positions[node.id] = mapOf("x" to position.x, "y" to position.y)
    }

    return mapOf(
      "positions" to positions,
      "width" to output.width,
      "height" to output.height,
      "skipped" to input.skipped,
    )
  }

  fun parsePayload(payload: Map<String, Any?>): ParsedInput {
    val options = parseOptions(payload["options"])
    val skipped = mutableListOf<Map<String, String>>()
    val nodes = parseNodes(payload["nodes"], skipped)
    val validIds = nodes.mapTo(HashSet()) { it.id }
    val edges = parseEdges(payload["edges"], validIds, skipped)
    return ParsedInput(nodes, edges, options, skipped)
  }

  private fun parseNodes(raw: Any?, skipped: MutableList<Map<String, String>>): List<LayoutNode> {
    val list = raw as? List<*> ?: return emptyList()
    val result = mutableListOf<LayoutNode>()
    for (item in list) {
      val map = item as? Map<*, *>
      val id = map?.get("id") as? String
      if (id == null || id.isEmpty()) {
        skipped += mapOf("id" to (id ?: "<missing>"), "reason" to "missing or invalid id")
        continue
      }
      val width = finiteNumber(map?.get("width"))
      val height = finiteNumber(map?.get("height"))
      if (width == null || height == null) {
        skipped += mapOf("id" to id, "reason" to "missing or non-finite width/height")
        continue
      }
      result += LayoutNode(id, width, height)
    }
    return result
  }

  private fun parseEdges(raw: Any?, validIds: Set<String>, skipped: MutableList<Map<String, String>>): List<LayoutEdge> {
    val list = raw as? List<*> ?: return emptyList()
    val result = mutableListOf<LayoutEdge>()
    for (item in list) {
      val map = item as? Map<*, *>
      val id = map?.get("id") as? String
      if (id == null || id.isEmpty()) {
        skipped += mapOf("id" to (id ?: "<missing>"), "reason" to "missing or invalid id")
        continue
      }
      val source = map?.get("source") as? String
      val target = map?.get("target") as? String
      if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
        skipped += mapOf("id" to id, "reason" to "missing source or target")
        continue
      }
      if (source == target) {
        skipped += mapOf("id" to id, "reason" to "self-loop")
        continue
      }
      if (!validIds.contains(source) || !validIds.contains(target)) {
        skipped += mapOf("id" to id, "reason" to "source or target node not present")
        continue
      }
      result += LayoutEdge(
        id = id,
        source = source,
        target = target,
        sourcePortSide = when (map?.get("sourcePortSide")) {
          "TOP" -> PortSide.TOP
          "BOTTOM" -> PortSide.BOTTOM
          "LEFT" -> PortSide.LEFT
          "RIGHT" -> PortSide.RIGHT
          else -> null
        },
      )
    }
    return result
  }

  private fun parseOptions(raw: Any?): LayoutOptions {
    val map = raw as? Map<*, *> ?: return LayoutOptions()
    return LayoutOptions(
      rankdir = when (map["rankdir"]) {
        "BT" -> LayoutRankDir.BT
        "LR" -> LayoutRankDir.LR
        "RL" -> LayoutRankDir.RL
        else -> LayoutRankDir.TB
      },
      nodesep = optionalNonNegative(map["nodesep"]),
      ranksep = optionalNonNegative(map["ranksep"]),
      marginx = clampNonNegative(map["marginx"], 40.0),
      marginy = clampNonNegative(map["marginy"], 40.0),
      nodeWidth = clampNonNegative(map["nodeWidth"], 150.0),
      nodeHeight = clampNonNegative(map["nodeHeight"], 64.0),
      orderingPasses = integerPasses(map["orderingPasses"]),
      alignBranches = map["alignBranches"] as? Boolean ?: false,
      cyclePolicy = if (map["cyclePolicy"] == "throw") CyclePolicy.THROW else CyclePolicy.TOLERANT,
    )
  }

  /** Finite numbers only; non-finite -> null. */
  private fun finiteNumber(value: Any?): Double? {
    val number = value as? Number ?: return null
    val converted = number.toDouble()
    return if (converted.isFinite()) converted else null
  }

  /**
   * Spacing is optional: absent or non-finite -> null so the engine derives
   * defaults from the graph's node dimensions; negative -> 0.
   */
  private fun optionalNonNegative(value: Any?): Double? {
    val number = finiteNumber(value) ?: return null
    return if (number >= 0.0) number else 0.0
  }

  /** §14.4: negative spacing/dimensions -> 0; non-finite -> default. */
  private fun clampNonNegative(value: Any?, default: Double): Double {
    val converted = finiteNumber(value) ?: return default
    return if (converted < 0.0) 0.0 else converted
  }

  /** §14.4: non-integer ordering passes -> 4 (default). */
  private fun integerPasses(value: Any?): Int {
    val converted = finiteNumber(value) ?: return 4
    return if (converted == floor(converted)) converted.toInt() else 4
  }
}