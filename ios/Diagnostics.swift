import Foundation

/// Diagnostics + logging (spec §5C). Records the last layout duration and the
/// number of skipped items, and toggles native logging. Foundation-only so it
/// can be exercised by the SwiftPM harness without ExpoModulesCore.
public final class Diagnostics {
  public static let shared = Diagnostics()

  private let lock = NSLock()

  private var _lastLayoutDurationMs: Double?
  private var _lastSkippedCount = 0
  private var _loggingEnabled = false

  /// Last layout duration in milliseconds, or nil before the first layout.
  public var lastLayoutDurationMs: Double? {
    lock.lock(); defer { lock.unlock() }
    return _lastLayoutDurationMs
  }

  /// Number of nodes/edges skipped during the last layout.
  public var lastSkippedCount: Int {
    lock.lock(); defer { lock.unlock() }
    return _lastSkippedCount
  }

  public var isLoggingEnabled: Bool {
    lock.lock(); defer { lock.unlock() }
    return _loggingEnabled
  }

  public func setLoggingEnabled(_ enabled: Bool) {
    lock.lock(); defer { lock.unlock() }
    _loggingEnabled = enabled
  }

  /// Clears all recorded state (testing helper).
  public func reset() {
    lock.lock(); defer { lock.unlock() }
    _lastLayoutDurationMs = nil
    _lastSkippedCount = 0
    _loggingEnabled = false
  }

  /// Called by the module after every layout (spec §5C.3).
  public func recordLayout(durationMs: Double, skippedCount: Int) {
    lock.lock(); defer { lock.unlock() }
    _lastLayoutDurationMs = durationMs
    _lastSkippedCount = skippedCount
    if _loggingEnabled {
      NSLog("[INFO][LAYOUT] Layout completed {duration: %.3fms, skipped: %d}", durationMs, skippedCount)
    }
  }
}