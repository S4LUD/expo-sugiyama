import Foundation
import ExpoModulesCore

/// Bridge layer (spec §10.5, §14, A.4). Coerces and validates the JS payload,
/// clamps options, runs `DagreLayout`, and serializes the result. It contains
/// no algorithm logic. Mirror of the Android `Bridge.kt`.
///
/// Error contract (§15):
///   - Invalid nodes/edges -> skipped (never thrown); a partial layout is returned
///   - Cycle + `cyclePolicy == "throw"` -> reject with CYCLE_DETECTED
///   - Unexpected failure -> reject with INTERNAL
enum Bridge {
  struct ParsedInput {
    let nodes: [LayoutNode]
    let edges: [LayoutEdge]
    let options: LayoutOptions
    let skipped: [[String: String]]
  }

  /// Full compute for the AsyncFunction (A.4).
  static func compute(_ payload: [String: Any]) throws -> [String: Any] {
    let input = parsePayload(payload)
    let output: LayoutOutput
    do {
      output = try DagreLayout.computeLayout(
        nodes: input.nodes,
        edges: input.edges,
        options: input.options
      )
    } catch let error as LayoutCycleException {
      throw Exception(
        name: "CycleDetected",
        description: error.message,
        code: "CYCLE_DETECTED"
      )
    } catch {
      throw Exception(
        name: "InternalFailure",
        description: error.localizedDescription,
        code: "INTERNAL"
      )
    }

    var positions: [String: [String: Double]] = [:]
    for node in input.nodes {
      guard let position = output.positions[node.id] else { continue }
      positions[node.id] = ["x": position.x, "y": position.y]
    }

    return [
      "positions": positions,
      "width": output.width,
      "height": output.height,
      "skipped": input.skipped,
    ]
  }

  static func parsePayload(_ payload: [String: Any]) -> ParsedInput {
    let options = parseOptions(payload["options"])
    var skipped: [[String: String]] = []
    let nodes = parseNodes(payload["nodes"], skipped: &skipped)
    let validIds = Set(nodes.map { $0.id })
    let edges = parseEdges(payload["edges"], validIds: validIds, skipped: &skipped)
    return ParsedInput(nodes: nodes, edges: edges, options: options, skipped: skipped)
  }

  // MARK: - Node/edge validation

  private static func parseNodes(_ raw: Any?, skipped: inout [[String: String]]) -> [LayoutNode] {
    guard let list = raw as? [Any] else { return [] }
    var result: [LayoutNode] = []
    for item in list {
      guard let map = item as? [String: Any] else {
        skipped.append(["id": "<missing>", "reason": "missing or invalid id"])
        continue
      }
      let id = map["id"] as? String
      guard let id = id, !id.isEmpty else {
        skipped.append(["id": id ?? "<missing>", "reason": "missing or invalid id"])
        continue
      }
      guard
        let width = finiteNumber(map["width"]),
        let height = finiteNumber(map["height"])
      else {
        skipped.append(["id": id, "reason": "missing or non-finite width/height"])
        continue
      }
      result.append(LayoutNode(id: id, width: width, height: height))
    }
    return result
  }

  private static func parseEdges(
    _ raw: Any?,
    validIds: Set<String>,
    skipped: inout [[String: String]]
  ) -> [LayoutEdge] {
    guard let list = raw as? [Any] else { return [] }
    var result: [LayoutEdge] = []
    for item in list {
      guard let map = item as? [String: Any] else {
        skipped.append(["id": "<missing>", "reason": "missing or invalid id"])
        continue
      }
      let id = map["id"] as? String
      guard let id = id, !id.isEmpty else {
        skipped.append(["id": id ?? "<missing>", "reason": "missing or invalid id"])
        continue
      }
      let source = map["source"] as? String
      let target = map["target"] as? String
      guard
        let source = source, !source.isEmpty,
        let target = target, !target.isEmpty
      else {
        skipped.append(["id": id, "reason": "missing source or target"])
        continue
      }
      if source == target {
        skipped.append(["id": id, "reason": "self-loop"])
        continue
      }
      if !validIds.contains(source) || !validIds.contains(target) {
        skipped.append(["id": id, "reason": "source or target node not present"])
        continue
      }
      result.append(
        LayoutEdge(
          id: id,
          source: source,
          target: target,
          sourcePortSide: (map["sourcePortSide"] as? String).flatMap(PortSide.init(rawValue:))
        )
      )
    }
    return result
  }

  // MARK: - Options

  private static func parseOptions(_ raw: Any?) -> LayoutOptions {
    guard let map = raw as? [String: Any] else { return LayoutOptions() }
    var options = LayoutOptions()
    if let rankdir = map["rankdir"] as? String {
      options.rankdir = LayoutRankDir(rawValue: rankdir) ?? .tb
    }
    options.nodesep = optionalNonNegative(map["nodesep"])
    options.ranksep = optionalNonNegative(map["ranksep"])
    options.marginx = clampNonNegative(map["marginx"], fallback: 40.0)
    options.marginy = clampNonNegative(map["marginy"], fallback: 40.0)
    options.nodeWidth = clampNonNegative(map["nodeWidth"], fallback: 150.0)
    options.nodeHeight = clampNonNegative(map["nodeHeight"], fallback: 64.0)
    options.orderingPasses = integerPasses(map["orderingPasses"])
    if let alignBranches = map["alignBranches"] as? Bool {
      options.alignBranches = alignBranches
    }
    options.cyclePolicy = (map["cyclePolicy"] as? String) == "throw" ? .throwing : .tolerant
    return options
  }

  /// Finite numbers only; non-finite/non-numeric -> nil. Booleans are rejected
  /// (they bridge as NSNumber, so check explicitly).
  private static func finiteNumber(_ value: Any?) -> Double? {
    if value is Bool { return nil }
    guard let number = value as? NSNumber else { return nil }
    let converted = number.doubleValue
    return converted.isFinite ? converted : nil
  }

  /// §14.4: negative spacing/dimensions -> 0; non-finite -> default.
  private static func clampNonNegative(_ value: Any?, fallback: Double) -> Double {
    guard let converted = finiteNumber(value) else { return fallback }
    return converted < 0 ? 0 : converted
  }

  /// Spacing is optional: absent or non-finite -> nil so the engine derives
  /// defaults from the graph's node dimensions; negative -> 0.
  private static func optionalNonNegative(_ value: Any?) -> Double? {
    guard let converted = finiteNumber(value) else { return nil }
    return converted < 0 ? 0 : converted
  }

  /// §14.4: non-integer ordering passes -> 4 (default).
  private static func integerPasses(_ value: Any?) -> Int {
    guard let converted = finiteNumber(value) else { return 4 }
    let integral = converted.rounded(.down)
    return converted == integral ? Int(converted) : 4
  }
}