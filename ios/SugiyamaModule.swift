import ExpoModulesCore

/// Native module definition for the expo-sugiyama layout engine. The bridge layer
/// only coerces arguments and serializes results - it contains no algorithm logic
/// (spec A.4). The native module is registered as "ExpoSugiyama". Execution runs
/// on the Expo AsyncFunction background executor, never on the JS thread.
public class SugiyamaModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoSugiyama")

    AsyncFunction("computeLayoutAsync") { (payload: [String: Any]) throws -> [String: Any] in
      let start = DispatchTime.now()
      let result = try Bridge.compute(payload)
      let elapsedMs = Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000
      Diagnostics.shared.recordLayout(
        durationMs: elapsedMs,
        skippedCount: (result["skipped"] as? [[String: String]])?.count ?? 0
      )
      return result
    }

    // Diagnostics API (spec §5C.4)
    Function("getLastLayoutDuration") {
      return Diagnostics.shared.lastLayoutDurationMs
    }

    Function("getLastSkippedCount") {
      return Diagnostics.shared.lastSkippedCount
    }

    Function("setLoggingEnabled") { (enabled: Bool) in
      Diagnostics.shared.setLoggingEnabled(enabled)
    }
  }
}
