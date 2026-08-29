jest.mock('../src/SugiyamaModule', () => ({
  __esModule: true,
  default: { computeLayoutAsync: jest.fn() },
}));

import { computeLayoutAsync } from '../src';
import SugiyamaNativeModule from '../src/SugiyamaModule';
import { DEFAULT_LAYOUT_OPTIONS, type LayoutResult } from '../src/types';

const mockCompute = SugiyamaNativeModule.computeLayoutAsync as jest.Mock;

const NATIVE_RESULT: LayoutResult = {
  positions: { a: { x: 145, y: 40 } },
  width: 940,
  height: 472,
  skipped: [{ id: 'junk', reason: 'missing or invalid id' }],
};

beforeEach(() => {
  mockCompute.mockReset();
});

describe('computeLayoutAsync', () => {
  it('forwards nodes, edges, and default options to the native module', async () => {
    mockCompute.mockResolvedValue(NATIVE_RESULT);
    const result = await computeLayoutAsync(
      [{ id: 'a', width: 150, height: 64 }],
      [{ id: 'e0', source: 'a', target: 'b' }],
    );

    expect(mockCompute).toHaveBeenCalledTimes(1);
    const payload = mockCompute.mock.calls[0][0];
    expect(payload.nodes).toEqual([{ id: 'a', width: 150, height: 64 }]);
    // Spacing is left absent so the native engine derives it from node size.
    expect(payload.options).toEqual({ ...DEFAULT_LAYOUT_OPTIONS, nodesep: undefined, ranksep: undefined });
    expect(result).toEqual(NATIVE_RESULT);
  });

  it('propagates user options and strips invalid input', async () => {
    mockCompute.mockResolvedValue(NATIVE_RESULT);
    await computeLayoutAsync(
      [
        { id: 'a', width: 200, height: 80 },
        { id: '', width: 1, height: 1 },
      ],
      [{ id: 'loop', source: 'a', target: 'a' }],
      { nodesep: 24, ranksep: 96, orderingPasses: 6 },
    );

    const payload = mockCompute.mock.calls[0][0];
    expect(payload.nodes).toEqual([{ id: 'a', width: 200, height: 80 }]);
    expect(payload.edges).toEqual([]);
    expect(payload.options.nodesep).toBe(24);
    expect(payload.options.ranksep).toBe(96);
    expect(payload.options.orderingPasses).toBe(6);
  });

  it('rejects when the native module rejects with a typed error', async () => {
    const cycleError = Object.assign(new Error('Cycle detected in graph'), {
      code: 'CYCLE_DETECTED',
    });
    mockCompute.mockRejectedValue(cycleError);
    await expect(
      computeLayoutAsync(
        [{ id: 'a', width: 10, height: 10 }],
        [{ id: 'e0', source: 'a', target: 'b' }],
        { cyclePolicy: 'throw' },
      ),
    ).rejects.toMatchObject({ code: 'CYCLE_DETECTED' });
  });
});
