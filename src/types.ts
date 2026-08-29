/** Layout direction (default: 'TB'). */
export type LayoutRankDir = 'TB' | 'BT' | 'LR' | 'RL';

export interface LayoutNodeInput {
  id: string;
  width: number;
  height: number;
}

export interface LayoutEdgeInput {
  id: string;
  source: string;
  target: string;
  /**
   * Declared side of the source port. In LR/RL a TOP or BOTTOM side, and in
   * TB/BT a LEFT or RIGHT side, marks the edge as a stacking edge: the layout
   * places the target beside the source in the same column instead of one
   * rank downstream.
   */
  sourcePortSide?: 'TOP' | 'BOTTOM' | 'LEFT' | 'RIGHT';
}

export interface LayoutOptions {
  rankdir?: LayoutRankDir;
  nodesep?: number;
  ranksep?: number;
  marginx?: number;
  marginy?: number;
  nodeWidth?: number;
  nodeHeight?: number;
  orderingPasses?: number;
  alignBranches?: boolean;
  cyclePolicy?: 'tolerant' | 'throw';
}

export interface LayoutPosition {
  x: number;
  y: number;
}

export interface LayoutSkippedItem {
  id: string;
  reason: string;
}

export interface LayoutResult {
  positions: Record<string, LayoutPosition>;
  width: number;
  height: number;
  skipped: LayoutSkippedItem[];
}

export type SugiyamaError =
  | { code: 'CYCLE_DETECTED'; message: string; path: string[] }
  | { code: 'INTERNAL'; message: string };

export const DEFAULT_LAYOUT_OPTIONS: Required<LayoutOptions> = {
  rankdir: 'TB',
  nodesep: 60,
  ranksep: 80,
  marginx: 40,
  marginy: 40,
  nodeWidth: 150,
  nodeHeight: 64,
  orderingPasses: 4,
  alignBranches: false,
  cyclePolicy: 'tolerant',
};