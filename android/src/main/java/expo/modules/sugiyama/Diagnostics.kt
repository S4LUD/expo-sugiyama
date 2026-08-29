package expo.modules.sugiyama

import java.util.Locale

/**
 * Diagnostics + logging (spec §5C). Records the last layout duration and the
 * number of skipped items, and toggles native logging. Pure JVM state so the
 * JUnit suite can exercise it without Robolectric.
 */
object Diagnostics {
  @Volatile
  private var lastLayoutDurationMsValue: Double? = null

  @Volatile
  private var lastSkippedCountValue: Int = 0

  @Volatile
  private var loggingEnabledValue: Boolean = false

  /** Last layout duration in milliseconds, or null before the first layout. */
  fun getLastLayoutDurationMs(): Double? = lastLayoutDurationMsValue

  /** Number of nodes/edges skipped during the last layout. */
  fun getLastSkippedCount(): Int = lastSkippedCountValue

  /** Whether native logging is currently enabled. */
  val loggingEnabled: Boolean
    get() = loggingEnabledValue

  fun setLoggingEnabled(enabled: Boolean) {
    loggingEnabledValue = enabled
  }

  /** Clears all recorded state (testing helper). */
  fun reset() {
    lastLayoutDurationMsValue = null
    lastSkippedCountValue = 0
    loggingEnabledValue = false
  }

  /** Called by the module after every layout (spec §5C.3). */
  fun recordLayout(durationMs: Double, skippedCount: Int) {
    lastLayoutDurationMsValue = durationMs
    lastSkippedCountValue = skippedCount
    if (loggingEnabledValue) {
      val duration = String.format(Locale.US, "%.3f", durationMs)
      android.util.Log.i(
        "ExpoSugiyama",
        "[INFO][LAYOUT] Layout completed {duration: ${duration}ms, skipped: $skippedCount}",
      )
    }
  }
}