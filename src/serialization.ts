import { DEFAULT_LAYOUT_OPTIONS, type LayoutEdgeInput, type LayoutNodeInput, type LayoutOptions } from './types';

/**
 * Coerce consumer input into the native payload (spec A.3, §14.1, §14.4).
 *
 * Non-finite numbers are filtered with a warning; option defaults are applied
 * and clamped exactly like the native bridge. The native module re-validates
 * defensively and reports remaining problems in `result.skipped`.
 */
export interface LayoutPayload {
  nodes: LayoutNodeInput[];
  edges: LayoutEdgeInput[];
  options: LayoutOptions;
}

const VALID_RANK_DIRS: ReadonlySet<string> = new Set(['TB', 'BT', 'LR', 'RL']);
const VALID_CYCLE_POLICIES: ReadonlySet<string> = new Set(['tolerant', 'throw']);
const VALID_PORT_SIDES: ReadonlySet<string> = new Set(['TOP', 'BOTTOM', 'LEFT', 'RIGHT']);

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function warn(message: string): void {
  console.warn(`[expo-sugiyama] ${message}`);
}

function clampNonNegative(value: unknown, fallback: number): number {
  if (!isFiniteNumber(value)) return fallback;
  return Math.max(0, value);
}

function integerPasses(value: unknown): number {
  if (!isFiniteNumber(value) || !Number.isInteger(value)) {
    return DEFAULT_LAYOUT_OPTIONS.orderingPasses;
  }
  return value;
}

export function serializeLayoutInput(
  nodes: readonly LayoutNodeInput[],
  edges: readonly LayoutEdgeInput[],
  options?: LayoutOptions,
): LayoutPayload {
  const cleanNodes: LayoutNodeInput[] = [];
  for (const node of nodes) {
    if (typeof node?.id !== 'string' || node.id.length === 0) {
      warn('Skipping node with missing or empty id');
      continue;
    }
    if (!isFiniteNumber(node.width) || !isFiniteNumber(node.height)) {
      warn(`Skipping node "${node.id}": non-finite dimensions`);
      continue;
    }
    cleanNodes.push({ id: node.id, width: node.width, height: node.height });
  }

  const nodeIds = new Set(cleanNodes.map((node) => node.id));
  const cleanEdges: LayoutEdgeInput[] = [];
  for (const edge of edges) {
    if (typeof edge?.id !== 'string' || edge.id.length === 0) {
      warn('Skipping edge with missing or empty id');
      continue;
    }
    if (typeof edge.source !== 'string' || typeof edge.target !== 'string') {
      warn(`Skipping edge "${edge.id}": missing source or target`);
      continue;
    }
    if (edge.source === edge.target) {
      warn(`Skipping self-loop edge "${edge.id}"`);
      continue;
    }
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      warn(`Skipping edge "${edge.id}": source or target node not present`);
      continue;
    }
    cleanEdges.push({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      ...(edge.sourcePortSide !== undefined &&
      VALID_PORT_SIDES.has(edge.sourcePortSide)
        ? { sourcePortSide: edge.sourcePortSide }
        : {}),
    });
  }

  const rankdir = options?.rankdir;
  const cyclePolicy = options?.cyclePolicy;
  const nodesep = options?.nodesep;
  const ranksep = options?.ranksep;
  const merged: LayoutOptions = {
    rankdir:
      rankdir !== undefined && VALID_RANK_DIRS.has(rankdir) ? rankdir : DEFAULT_LAYOUT_OPTIONS.rankdir,
    marginx: clampNonNegative(options?.marginx, DEFAULT_LAYOUT_OPTIONS.marginx),
    marginy: clampNonNegative(options?.marginy, DEFAULT_LAYOUT_OPTIONS.marginy),
    nodeWidth: clampNonNegative(options?.nodeWidth, DEFAULT_LAYOUT_OPTIONS.nodeWidth),
    nodeHeight: clampNonNegative(options?.nodeHeight, DEFAULT_LAYOUT_OPTIONS.nodeHeight),
    orderingPasses: integerPasses(options?.orderingPasses),
    alignBranches:
      typeof options?.alignBranches === 'boolean' ? options.alignBranches : DEFAULT_LAYOUT_OPTIONS.alignBranches,
    cyclePolicy:
      cyclePolicy !== undefined && VALID_CYCLE_POLICIES.has(cyclePolicy)
        ? cyclePolicy
        : DEFAULT_LAYOUT_OPTIONS.cyclePolicy,
  };
  // Spacing is intentionally left absent when the caller didn't specify it,
  // so the native engine derives defaults from the graph's node dimensions.
  if (nodesep !== undefined) {
    merged.nodesep = clampNonNegative(nodesep, DEFAULT_LAYOUT_OPTIONS.nodesep);
  }
  if (ranksep !== undefined) {
    merged.ranksep = clampNonNegative(ranksep, DEFAULT_LAYOUT_OPTIONS.ranksep);
  }
  return { nodes: cleanNodes, edges: cleanEdges, options: merged };
}
