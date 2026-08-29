package expo.modules.sugiyama

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Native module definition for the expo-sugiyama layout engine. The bridge
 * layer only coerces arguments and serializes results - it contains no
 * algorithm logic (spec A.5). Execution runs on the Expo AsyncFunction
 * background executor, never on the JS thread.
 */
class SugiyamaModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoSugiyama")

    AsyncFunction("computeLayoutAsync") { payload: Map<String, Any?> ->
      val start = System.nanoTime()
      val result = Bridge.compute(payload)
      val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
      Diagnostics.recordLayout(
        durationMs = elapsedMs,
        skippedCount = (result["skipped"] as? List<*>)?.size ?: 0,
      )
      result
    }

    // Diagnostics API (spec §5C.4)
    Function("getLastLayoutDuration") {
      Diagnostics.getLastLayoutDurationMs()
    }

    Function("getLastSkippedCount") {
      Diagnostics.getLastSkippedCount()
    }

    Function("setLoggingEnabled") { enabled: Boolean ->
      Diagnostics.setLoggingEnabled(enabled)
    }
  }
}