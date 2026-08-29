import { serializeLayoutInput } from './serialization';
import type { LayoutEdgeInput, LayoutNodeInput, LayoutOptions, LayoutResult } from './types';

import SugiyamaNativeModule from './SugiyamaModule';

/**
 * Computes a hierarchical (Sugiyama) graph layout entirely on a native
 * background thread. Never executes on the JS thread.
 *
 * Invalid nodes/edges are dropped with a console warning; the native module
 * re-validates defensively and reports anything it skipped in `result.skipped`.
 */
export function computeLayoutAsync(
  nodes: LayoutNodeInput[],
  edges: LayoutEdgeInput[],
  options?: LayoutOptions,
): Promise<LayoutResult> {
  const payload = serializeLayoutInput(nodes, edges, options);
  return SugiyamaNativeModule.computeLayoutAsync(payload);
}

/**
 * Diagnostics API (spec §5C.4).
 * Last layout duration in milliseconds, or null if no layout has run yet.
 */
export function getLastLayoutDuration(): number | null {
  return SugiyamaNativeModule.getLastLayoutDuration();
}

/** Number of nodes/edges skipped during the last layout. */
export function getLastSkippedCount(): number {
  return SugiyamaNativeModule.getLastSkippedCount();
}

/** Enable/disable native logging. */
export function setLoggingEnabled(enabled: boolean): void {
  SugiyamaNativeModule.setLoggingEnabled(enabled);
}

export * from './types';
