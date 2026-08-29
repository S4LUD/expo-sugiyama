package expo.modules.sugiyama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics API tests (spec §5C.4).
 */
class DiagnosticsTest {
  @Test
  fun lastLayoutDurationIsNullBeforeFirstLayout() {
    Diagnostics.reset()
    assertNull(Diagnostics.getLastLayoutDurationMs())
  }

  @Test
  fun recordLayoutStoresDurationAndSkippedCount() {
    Diagnostics.recordLayout(12.5, 3)
    assertEquals(12.5, Diagnostics.getLastLayoutDurationMs() ?: -1.0, 0.0)
    assertEquals(3, Diagnostics.getLastSkippedCount())
  }

  @Test
  fun recordLayoutOverwritesPreviousValues() {
    Diagnostics.recordLayout(1.0, 0)
    Diagnostics.recordLayout(99.25, 7)
    assertEquals(99.25, Diagnostics.getLastLayoutDurationMs() ?: -1.0, 0.0)
    assertEquals(7, Diagnostics.getLastSkippedCount())
  }

  @Test
  fun loggingDisabledByDefault() {
    Diagnostics.setLoggingEnabled(false)
    assertFalse(Diagnostics.loggingEnabled)
  }

  @Test
  fun setLoggingEnabledToggles() {
    Diagnostics.setLoggingEnabled(true)
    assertTrue(Diagnostics.loggingEnabled)
    Diagnostics.setLoggingEnabled(false)
    assertFalse(Diagnostics.loggingEnabled)
  }
}
