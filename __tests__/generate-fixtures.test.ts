import * as fs from 'fs';
import * as path from 'path';

import { computeLayout, type LayoutOptions } from '@/features/layout-engine/dagre';

interface FixtureNodeDef {
  id: string;
  width: number;
  height: number;
}

interface FixtureEdgeDef {
  id: string;
  source: string;
  target: string;
}

interface FixtureDef {
  name: string;
  nodes: FixtureNodeDef[];
  edges: FixtureEdgeDef[];
  options: LayoutOptions;
}

const FIXTURES_DIR = path.resolve(__dirname, '../fixtures');

const DEFAULT_OPTIONS: Required<LayoutOptions> = {
  rankdir: 'TB',
  nodesep: 60,
  ranksep: 80,
  marginx: 40,
  marginy: 40,
  nodeWidth: 150,
  nodeHeight: 64,
  orderingPasses: 4,
};

type FixtureOverrides = Partial<LayoutOptions>;

function node(id: string): FixtureNodeDef {
  return { id, width: 150, height: 64 };
}

function nodeWithDims(id: string, width: number, height: number): FixtureNodeDef {
  return { id, width, height };
}

function edge(id: string, source: string, target: string): FixtureEdgeDef {
  return { id, source, target };
}

function chainNodes(length: number): { nodes: FixtureNodeDef[]; edges: FixtureEdgeDef[] } {
  const nodes: FixtureNodeDef[] = [];
  const edges: FixtureEdgeDef[] = [];
  for (let i = 0; i < length; i++) {
    nodes.push(node(`n${i}`));
    if (i > 0) edges.push(edge(`e${i - 1}`, `n${i - 1}`, `n${i}`));
  }
  return { nodes, edges };
}

function layeredGrid(cols: number, layers: number): FixtureDef {
  const nodes: FixtureNodeDef[] = [];
  const edges: FixtureEdgeDef[] = [];
  const id = (c: number, layer: number) => `n${layer}l${c}`;

  for (let layer = 0; layer < layers; layer++) {
    for (let c = 0; c < cols; c++) {
      nodes.push(node(id(c, layer)));
    }
  }

  let edgeCount = 0;
  for (let layer = 0; layer < layers - 1; layer++) {
    for (let c = 0; c < cols; c++) {
      edges.push(edge(`e${edgeCount++}`, id(c, layer), id(c, layer + 1)));
      edges.push(edge(`e${edgeCount++}`, id(c, layer), id((c + 1) % cols, layer + 1)));
    }
  }

  return { name: `grid-${nodes.length}`, nodes, edges, options: { ...DEFAULT_OPTIONS } };
}

function def(
  name: string,
  structure: { nodes: FixtureNodeDef[]; edges: FixtureEdgeDef[] },
  overrides: FixtureOverrides = {},
): FixtureDef {
  return {
    name,
    nodes: structure.nodes,
    edges: structure.edges,
    options: { ...DEFAULT_OPTIONS, ...overrides },
  };
}

const dense = {
  nodes: [node('a'), node('b'), node('c'), node('d'), node('e'), node('f'), node('g')],
  edges: [
    edge('e0', 'a', 'd'),
    edge('e1', 'a', 'e'),
    edge('e2', 'b', 'e'),
    edge('e3', 'b', 'f'),
    edge('e4', 'c', 'e'),
    edge('e5', 'c', 'f'),
    edge('e6', 'd', 'g'),
    edge('e7', 'e', 'g'),
    edge('e8', 'f', 'g'),
  ],
};

const DEFINITIONS: FixtureDef[] = [
  def('chain', chainNodes(4)),
  def('deep-chain', chainNodes(20)),
  def('fork', {
    nodes: [node('r'), node('a'), node('b'), node('c')],
    edges: [edge('e0', 'r', 'a'), edge('e1', 'r', 'b'), edge('e2', 'r', 'c')],
  }),
  def('join', {
    nodes: [node('a'), node('b'), node('c'), node('s')],
    edges: [edge('e0', 'a', 's'), edge('e1', 'b', 's'), edge('e2', 'c', 's')],
  }),
  def('diamond', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }),
  def('tree-3-level', {
    nodes: [node('r'), node('a'), node('b'), node('a1'), node('a2'), node('b1'), node('b2')],
    edges: [
      edge('e0', 'r', 'a'),
      edge('e1', 'r', 'b'),
      edge('e2', 'a', 'a1'),
      edge('e3', 'a', 'a2'),
      edge('e4', 'b', 'b1'),
      edge('e5', 'b', 'b2'),
    ],
  }),
  def('dense', dense),
  def('disconnected', {
    nodes: [node('n0'), node('n1'), node('n2'), node('n3'), node('n4'), node('n5')],
    edges: [edge('e0', 'n0', 'n1'), edge('e1', 'n2', 'n3'), edge('e2', 'n3', 'n4')],
  }),
  def('multi-root', {
    nodes: [node('ra'), node('rb'), node('s')],
    edges: [edge('e0', 'ra', 's'), edge('e1', 'rb', 's')],
  }),
  def('single-node', { nodes: [node('s')], edges: [] }),
  def('empty', { nodes: [], edges: [] }),
  def('orphans', { nodes: [node('a'), node('b'), node('c')], edges: [] }),
  def('chain-nodesep-20', chainNodes(4), { nodesep: 20 }),
  def('chain-ranksep-120', chainNodes(4), { ranksep: 120 }),
  def('chain-margins-8x16', chainNodes(4), { marginx: 8, marginy: 16 }),
  def('chain-node-size-96x40', chainNodes(4), { nodeWidth: 96, nodeHeight: 40 }),
  def('dense-passes-1', dense, { orderingPasses: 1 }),
  def('dense-passes-8', dense, { orderingPasses: 8 }),
  def('dense-custom', dense, { nodesep: 24, ranksep: 96, marginx: 12, marginy: 28, orderingPasses: 6 }),
  def('dense-lr', dense, { rankdir: 'LR' }),
  def('chain-bt', chainNodes(4), { rankdir: 'BT' }),
  def('fork-bt', {
    nodes: [node('r'), node('a'), node('b'), node('c')],
    edges: [edge('e0', 'r', 'a'), edge('e1', 'r', 'b'), edge('e2', 'r', 'c')],
  }, { rankdir: 'BT' }),
  def('dense-bt', dense, { rankdir: 'BT' }),
  def('diamond-bt', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }, { rankdir: 'BT' }),
  def('chain-lr', chainNodes(4), { rankdir: 'LR' }),
  def('fork-lr', {
    nodes: [node('r'), node('a'), node('b'), node('c')],
    edges: [edge('e0', 'r', 'a'), edge('e1', 'r', 'b'), edge('e2', 'r', 'c')],
  }, { rankdir: 'LR' }),
  def('diamond-lr', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }, { rankdir: 'LR' }),
  def('tree-3-level-lr', {
    nodes: [node('r'), node('a'), node('b'), node('a1'), node('a2'), node('b1'), node('b2')],
    edges: [
      edge('e0', 'r', 'a'),
      edge('e1', 'r', 'b'),
      edge('e2', 'a', 'a1'),
      edge('e3', 'a', 'a2'),
      edge('e4', 'b', 'b1'),
      edge('e5', 'b', 'b2'),
    ],
  }, { rankdir: 'LR' }),
  def('dense-lr-nodesep-120', dense, { rankdir: 'LR', nodesep: 120 }),
  def('chain-rl', chainNodes(4), { rankdir: 'RL' }),
  def('dense-rl', dense, { rankdir: 'RL' }),
  def('diamond-rl', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }, { rankdir: 'RL' }),
  def('multi-root-rl', {
    nodes: [node('ra'), node('rb'), node('s')],
    edges: [edge('e0', 'ra', 's'), edge('e1', 'rb', 's')],
  }, { rankdir: 'RL' }),
  def('mixed-size', {
    nodes: [nodeWithDims('a', 120, 80), nodeWithDims('b', 200, 48), nodeWithDims('c', 150, 64), nodeWithDims('d', 96, 120), nodeWithDims('e', 180, 56)],
    edges: [edge('e0', 'a', 'c'), edge('e1', 'b', 'c'), edge('e2', 'c', 'd'), edge('e3', 'c', 'e')],
  }),
  def('mixed-size-lr', {
    nodes: [nodeWithDims('a', 120, 80), nodeWithDims('b', 200, 48), nodeWithDims('c', 150, 64), nodeWithDims('d', 96, 120), nodeWithDims('e', 180, 56)],
    edges: [edge('e0', 'a', 'c'), edge('e1', 'b', 'c'), edge('e2', 'c', 'd'), edge('e3', 'c', 'e')],
  }, { rankdir: 'LR' }),
  def('fork-nodesep-100', {
    nodes: [node('r'), node('a'), node('b'), node('c')],
    edges: [edge('e0', 'r', 'a'), edge('e1', 'r', 'b'), edge('e2', 'r', 'c')],
  }, { nodesep: 100 }),
  def('fork-ranksep-120', {
    nodes: [node('r'), node('a'), node('b'), node('c')],
    edges: [edge('e0', 'r', 'a'), edge('e1', 'r', 'b'), edge('e2', 'r', 'c')],
  }, { ranksep: 120 }),
  def('join-nodesep-100', {
    nodes: [node('a'), node('b'), node('c'), node('s')],
    edges: [edge('e0', 'a', 's'), edge('e1', 'b', 's'), edge('e2', 'c', 's')],
  }, { nodesep: 100 }),
  def('diamond-nodesep-100', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }, { nodesep: 100 }),
  def('diamond-passes-1', {
    nodes: [node('a'), node('b'), node('c'), node('d')],
    edges: [edge('e0', 'a', 'b'), edge('e1', 'a', 'c'), edge('e2', 'b', 'd'), edge('e3', 'c', 'd')],
  }, { orderingPasses: 1 }),
  def('tree-3-level-passes-8', {
    nodes: [node('r'), node('a'), node('b'), node('a1'), node('a2'), node('b1'), node('b2')],
    edges: [
      edge('e0', 'r', 'a'),
      edge('e1', 'r', 'b'),
      edge('e2', 'a', 'a1'),
      edge('e3', 'a', 'a2'),
      edge('e4', 'b', 'b1'),
      edge('e5', 'b', 'b2'),
    ],
  }, { orderingPasses: 8 }),
  def('multi-root-custom', {
    nodes: [node('ra'), node('rb'), node('s')],
    edges: [edge('e0', 'ra', 's'), edge('e1', 'rb', 's')],
  }, { nodesep: 20, ranksep: 120 }),
  def('disconnected-custom', {
    nodes: [node('n0'), node('n1'), node('n2'), node('n3'), node('n4'), node('n5')],
    edges: [edge('e0', 'n0', 'n1'), edge('e1', 'n2', 'n3'), edge('e2', 'n3', 'n4')],
  }, { nodesep: 100, ranksep: 120, marginx: 8, marginy: 8 }),
  def('orphans-custom', { nodes: [node('a'), node('b'), node('c')], edges: [] }, { nodesep: 20 }),
  def('chain-10', chainNodes(10)),
  def('chain-10-nodesep-20', chainNodes(10), { nodesep: 20 }),
  def('deep-chain-50', chainNodes(50)),
  layeredGrid(5, 2), // 10 nodes
  layeredGrid(10, 10), // 100 nodes
  layeredGrid(25, 20), // 500 nodes
  layeredGrid(25, 40), // 1000 nodes
];

function toReferenceEdges(edges: FixtureEdgeDef[]) {
  return edges.map((e) => ({ id: e.id, source: { nodeId: e.source }, target: { nodeId: e.target } }));
}

function readJson(filePath: string) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

describe('layout fixture generation', () => {
  it('keeps fixtures in sync with the JS reference', () => {
    for (const definition of DEFINITIONS) {
      const result = computeLayout(
        definition.nodes,
        toReferenceEdges(definition.edges),
        definition.options,
      );

      const payload = {
        name: definition.name,
        options: definition.options,
        nodes: definition.nodes,
        edges: definition.edges,
        output: {
          positions: Object.fromEntries(
            [...result.nodes.entries()].map(([id, p]) => [id, { x: p.x, y: p.y }]),
          ),
          width: result.width,
          height: result.height,
        },
      };

      const filePath = path.join(FIXTURES_DIR, `${definition.name}.json`);

      if (process.env.REGENERATE_FIXTURES === '1') {
        fs.mkdirSync(FIXTURES_DIR, { recursive: true });
        fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`);
        continue;
      }

      expect(fs.existsSync(filePath)).toBe(true);
      expect(payload).toEqual(readJson(filePath));
    }
  });

  it('produces deterministic output', () => {
    const denseDef = DEFINITIONS.find((d) => d.name === 'dense');
    if (!denseDef) throw new Error('missing dense definition');

    const first = computeLayout(denseDef.nodes, toReferenceEdges(denseDef.edges), denseDef.options);
    const second = computeLayout(denseDef.nodes, toReferenceEdges(denseDef.edges), denseDef.options);

    expect(first).toEqual(second);
  });

  it('regenerates every fixture on demand', () => {
    for (const definition of DEFINITIONS) {
      const filePath = path.join(FIXTURES_DIR, `${definition.name}.json`);
      expect(fs.existsSync(filePath)).toBe(true);
    }
  });
});