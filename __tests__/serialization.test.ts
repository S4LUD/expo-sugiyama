import { serializeLayoutInput } from '../src/serialization';
import { DEFAULT_LAYOUT_OPTIONS, type LayoutOptions } from '../src/types';

describe('serializeLayoutInput', () => {
  it('applies all option defaults and preserves valid input', () => {
    const payload = serializeLayoutInput(
      [
        { id: 'a', width: 100, height: 50 },
        { id: 'b', width: 150, height: 64 },
      ],
      [{ id: 'e0', source: 'a', target: 'b' }],
    );
    expect(payload.nodes).toEqual([
      { id: 'a', width: 100, height: 50 },
      { id: 'b', width: 150, height: 64 },
    ]);
    expect(payload.edges).toEqual([{ id: 'e0', source: 'a', target: 'b' }]);
    // Spacing is left absent so the native engine derives it from node size.
    expect(payload.options).toEqual({ ...DEFAULT_LAYOUT_OPTIONS, nodesep: undefined, ranksep: undefined });
  });

  it('drops nodes with missing ids or non-finite dimensions', () => {
    const payload = serializeLayoutInput(
      [
        { id: '', width: 10, height: 10 },
        { id: 'nan', width: Number.NaN, height: 10 },
        { id: 'inf', width: 10, height: Number.POSITIVE_INFINITY },
        { id: 'ok', width: 10, height: 10 },
      ],
      [],
    );
    expect(payload.nodes).toEqual([{ id: 'ok', width: 10, height: 10 }]);
  });

  it('drops invalid edges (self-loops, dangling endpoints, missing ids)', () => {
    const payload = serializeLayoutInput(
      [
        { id: 'a', width: 10, height: 10 },
        { id: 'b', width: 10, height: 10 },
      ],
      [
        { id: 'self', source: 'a', target: 'a' },
        { id: 'dangling', source: 'a', target: 'z' },
        { id: '', source: 'a', target: 'b' },
        { id: 'ok', source: 'a', target: 'b' },
      ],
    );
    expect(payload.edges).toEqual([{ id: 'ok', source: 'a', target: 'b' }]);
  });

  it('clamps negative spacing to 0 and non-integer passes to 4', () => {
    const payload = serializeLayoutInput([], [], {
      nodesep: -5,
      ranksep: -1.5,
      orderingPasses: 2.5,
    });
    expect(payload.options.nodesep).toBe(0);
    expect(payload.options.ranksep).toBe(0);
    expect(payload.options.orderingPasses).toBe(4);
  });

  it('normalizes invalid enums to defaults and keeps valid ones', () => {
    const weird = {
      rankdir: 'diagonal',
      cyclePolicy: 'throw',
      alignBranches: 'yes',
    } as unknown as LayoutOptions;
    const payload = serializeLayoutInput([], [], weird);
    expect(payload.options.rankdir).toBe('TB');
    expect(payload.options.cyclePolicy).toBe('throw');
    expect(payload.options.alignBranches).toBe(false);

    const valid = serializeLayoutInput([], [], { rankdir: 'LR' });
    expect(valid.options.rankdir).toBe('LR');
  });

  it('handles empty input lists', () => {
    const payload = serializeLayoutInput([], []);
    expect(payload.nodes).toEqual([]);
    expect(payload.edges).toEqual([]);
  });
});
