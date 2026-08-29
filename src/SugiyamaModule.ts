import { NativeModule, requireNativeModule } from 'expo';

import type { LayoutEdgeInput, LayoutNodeInput, LayoutOptions, LayoutResult } from './types';

declare class SugiyamaNativeModule extends NativeModule {
  computeLayoutAsync(payload: SugiyamaLayoutPayload): Promise<LayoutResult>;
  getLastLayoutDuration(): number | null;
  getLastSkippedCount(): number;
  setLoggingEnabled(enabled: boolean): void;
}

interface SugiyamaLayoutPayload {
  nodes: LayoutNodeInput[];
  edges: LayoutEdgeInput[];
  options?: LayoutOptions;
}

export default requireNativeModule<SugiyamaNativeModule>('ExpoSugiyama');