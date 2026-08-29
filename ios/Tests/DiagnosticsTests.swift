import XCTest
@testable import ExpoSugiyama

/// Diagnostics API tests (spec §5C.4).
final class DiagnosticsTests: XCTestCase {
  override func setUp() {
    Diagnostics.shared.reset()
    super.setUp()
  }

  func testLastLayoutDurationIsNilBeforeFirstLayout() {
    XCTAssertNil(Diagnostics.shared.lastLayoutDurationMs)
  }

  func testRecordLayoutStoresDurationAndSkippedCount() {
    Diagnostics.shared.recordLayout(durationMs: 12.5, skippedCount: 3)
    XCTAssertEqual(Diagnostics.shared.lastLayoutDurationMs ?? -1, 12.5, accuracy: 0.0)
    XCTAssertEqual(Diagnostics.shared.lastSkippedCount, 3)
  }

  func testRecordLayoutOverwritesPreviousValues() {
    Diagnostics.shared.recordLayout(durationMs: 1.0, skippedCount: 0)
    Diagnostics.shared.recordLayout(durationMs: 99.25, skippedCount: 7)
    XCTAssertEqual(Diagnostics.shared.lastLayoutDurationMs ?? -1, 99.25, accuracy: 0.0)
    XCTAssertEqual(Diagnostics.shared.lastSkippedCount, 7)
  }

  func testLoggingDisabledByDefault() {
    XCTAssertFalse(Diagnostics.shared.isLoggingEnabled)
  }

  func testSetLoggingEnabledToggles() {
    Diagnostics.shared.setLoggingEnabled(true)
    XCTAssertTrue(Diagnostics.shared.isLoggingEnabled)
    Diagnostics.shared.setLoggingEnabled(false)
    XCTAssertFalse(Diagnostics.shared.isLoggingEnabled)
  }
}
