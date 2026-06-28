import { useMemo, useState, useCallback } from 'react'
import {
  ReactFlow,
  Background,
  useNodesState,
  useEdgesState,
  ReactFlowProvider,
  type Node,
  type Edge,
  type NodeProps,
  type EdgeProps,
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from 'dagre'
import { cn } from '../lib/utils'
import type { GraphTrace, TopologyNode, TopologyEdge } from '../types'
import { NodeDetailPanel } from './NodeDetailPanel'
import { Check, Loader2, XCircle } from 'lucide-react'

// ─── 布局配置 ───

const NODE_WIDTH = 140
const NODE_HEIGHT = 60
const LAYOUT_RANK_SEP = 100
const LAYOUT_NODE_SEP = 60

// ─── dagre 自动布局 ───

function layoutGraph(
  topoNodes: TopologyNode[],
  topoEdges: TopologyEdge[],
): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', ranksep: LAYOUT_RANK_SEP, nodesep: LAYOUT_NODE_SEP })

  topoNodes.forEach((n) => {
    g.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT })
  })
  topoEdges.forEach((e) => {
    if (e.from !== '__start__' && e.to !== '__end__') {
      g.setEdge(e.from, e.to)
    }
  })

  dagre.layout(g)

  const nodes: Node[] = topoNodes.map((n) => {
    const pos = g.node(n.id)
    return {
      id: n.id,
      type: 'dagNode',
      position: {
        x: pos ? pos.x - NODE_WIDTH / 2 : 0,
        y: pos ? pos.y - NODE_HEIGHT / 2 : 0,
      },
      data: { ...n },
      style: { width: NODE_WIDTH, height: NODE_HEIGHT },
    }
  })

  const edges: Edge[] = topoEdges
    .filter((e) => e.from !== '__start__' && e.to !== '__end__')
    .map((e, idx) => ({
      id: `e-${e.from}-${e.to}-${idx}`,
      source: e.from,
      target: e.to,
      type: 'dagEdge',
      label: e.label || undefined,
      markerEnd: { type: MarkerType.ArrowClosed, width: 20, height: 20 },
      style: { strokeWidth: 2 },
      data: { condition: e.conditions?.[0] || '' },
    }))

  return { nodes, edges }
}

// ─── 节点状态 → 视觉映射 ───

interface NodeStatusStyle {
  bg: string
  border: string
  icon: React.ReactNode
  labelClass: string
}

function getNodeStyle(
  nodeId: string,
  _nodeType?: string,
  currentNode?: string | null,
  completedNodes?: string[],
  nodeStatusMap?: Record<string, string>,
): NodeStatusStyle {
  // 优先使用 nodeStatusMap 中的状态
  const explicitStatus = nodeStatusMap?.[nodeId]
  if (explicitStatus === 'done' || (completedNodes?.includes(nodeId) && nodeId !== currentNode)) {
    return {
      bg: 'bg-sage-50 dark:bg-sage-900/30',
      border: 'border-sage-500 dark:border-sage-400',
      icon: <Check className="w-4 h-4 text-sage-600 dark:text-sage-400" />,
      labelClass: 'text-sage-700 dark:text-sage-300',
    }
  }
  if (nodeId === currentNode || explicitStatus === 'running') {
    return {
      bg: 'bg-terracotta-50 dark:bg-terracotta-900/30',
      border: 'border-terracotta-500 dark:border-terracotta-400',
      icon: <Loader2 className="w-4 h-4 text-terracotta-500 animate-spin" />,
      labelClass: 'text-terracotta-700 dark:text-terracotta-300',
    }
  }
  if (explicitStatus === 'error') {
    return {
      bg: 'bg-red-50 dark:bg-red-900/30',
      border: 'border-red-500 dark:border-red-400',
      icon: <XCircle className="w-4 h-4 text-red-500" />,
      labelClass: 'text-red-700 dark:text-red-300',
    }
  }
  // idle
  return {
    bg: 'bg-white dark:bg-charcoal-800',
    border: 'border-charcoal-200 dark:border-charcoal-600',
    icon: null as React.ReactNode,
    labelClass: 'text-charcoal-500 dark:text-charcoal-400',
  }
}

// ─── 自定义节点 ───

function DagNode({ data, selected }: NodeProps) {
  const d = data as unknown as TopologyNode & {
    graphTrace?: GraphTrace
  }

  // Try to get graphTrace from parent context (passed via data)
  const style = getNodeStyle(
    d.id,
    d.type,
    d.graphTrace?.currentNode,
    d.graphTrace?.completedNodes,
    d.graphTrace?.nodeStatusMap,
  )

  const typeIcon = {
    supervisor: '🔷',
    worker: '⚙️',
    validator: '🔍',
    report: '📋',
  }[d.type] || '⬜'

  return (
    <div
      className={cn(
        'flex items-center gap-2 px-3 py-2 rounded-xl border-2 transition-all duration-300 cursor-pointer shadow-sm hover:shadow-md',
        style.bg,
        style.border,
        selected && 'ring-2 ring-terracotta-400 shadow-lg',
      )}
      style={{ width: NODE_WIDTH - 4, height: NODE_HEIGHT - 4 }}
    >
      {/* type icon */}
      <span className="text-base flex-shrink-0">{typeIcon}</span>

      {/* label + status icon */}
      <div className="flex-1 min-w-0">
        <div className={cn('text-xs font-semibold truncate', style.labelClass)}>
          {d.label}
        </div>
      </div>

      {/* status icon */}
      <div className="flex-shrink-0">{style.icon}</div>
    </div>
  )
}

// ─── 自定义边 ───

function DagEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
}: EdgeProps) {
  const edgeData = data as { condition?: string; active?: boolean } | undefined
  const isActive = edgeData?.active
  const condition = edgeData?.condition

  const [edgePath] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
  })

  return (
    <>
      {/* 边线 */}
      <path
        d={edgePath}
        className={cn(
          'fill-none transition-all duration-500',
          isActive
            ? 'stroke-terracotta-500 dark:stroke-terracotta-400 stroke-[3px]'
            : 'stroke-charcoal-200 dark:stroke-charcoal-600 stroke-[2px]',
        )}
      />
      {/* 条件标签 */}
      {condition && (
        <div
          className="absolute px-1.5 py-0.5 rounded text-[9px] font-medium bg-white dark:bg-charcoal-800 border border-charcoal-200 dark:border-charcoal-600 text-charcoal-500 dark:text-charcoal-400 whitespace-nowrap"
          style={{
            left: (sourceX + targetX) / 2 - 20,
            top: (sourceY + targetY) / 2 - 10,
          }}
        >
          {condition}
        </div>
      )}
    </>
  )
}

// 辅助：smooth step path（内联简化版）
function getSmoothStepPath({
  sourceX, sourceY, targetX, targetY,
}: {
  sourceX: number; sourceY: number; _sourcePosition?: string
  targetX: number; targetY: number; _targetPosition?: string
}): [string] {
  const mx = (sourceX + targetX) / 2
  return [`M ${sourceX},${sourceY} C ${mx},${sourceY} ${mx},${targetY} ${targetX},${targetY}`]
}

// ─── 节点定义（给 React Flow 注册）───

const nodeTypes = { dagNode: DagNode }
const edgeTypes = { dagEdge: DagEdge }

// ─── 主组件 ───

interface GraphFlowProps {
  graphTrace: GraphTrace | null
  visible?: boolean
}

export function GraphFlow({ graphTrace, visible = true }: GraphFlowProps) {
  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])

  // 拓扑 → React Flow 节点/边
  const layout = useMemo(() => {
    if (!graphTrace?.topology) return null
    return layoutGraph(graphTrace.topology.nodes, graphTrace.topology.edges)
  }, [graphTrace?.topology])

  // 同步布局到 React Flow state
  useMemo(() => {
    if (!layout) return
    // 注入 graphTrace 到每个节点的 data，让自定义节点能访问状态
    const enrichedNodes = layout.nodes.map((n) => ({
      ...n,
      data: { ...n.data, graphTrace },
    }))
    setNodes(enrichedNodes)

    // 标记活跃边
    const activeBranches = new Set(
      graphTrace?.branches?.map((b) => `${b.from}→${b.to}`) || [],
    )
    const enrichedEdges = layout.edges.map((e) => ({
      ...e,
      data: {
        ...(e.data || {}),
        active: activeBranches.has(`${e.source}→${e.target}`),
      },
    }))
    setEdges(enrichedEdges)
  }, [layout, graphTrace, setNodes, setEdges])

  const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    setSelectedNode(node.id)
  }, [])

  const onPaneClick = useCallback(() => {
    setSelectedNode(null)
  }, [])

  if (!visible || !graphTrace) return null

  const nodeDataMap = graphTrace.nodeDataMap || {}
  const iterationInfo =
    graphTrace.iterationCount && graphTrace.iterationCount > 1
      ? `第 ${graphTrace.iterationCount}/${graphTrace.maxIterations} 轮回退`
      : null

  return (
    <div className="relative border-b border-warm-white-200 dark:border-charcoal-700 bg-white/80 dark:bg-charcoal-900/80 backdrop-blur-sm">
      {/* 迭代标记 */}
      {iterationInfo && (
        <div className="absolute top-2 right-4 z-10 px-2 py-0.5 rounded-full text-[10px] font-medium bg-mustard-100 dark:bg-mustard-900/40 text-mustard-700 dark:text-mustard-300 border border-mustard-200 dark:border-mustard-700">
          {iterationInfo}
        </div>
      )}

      <ReactFlowProvider>
        <div className="h-[220px]">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onNodeClick={onNodeClick}
            onPaneClick={onPaneClick}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={true}
            panOnDrag={false}
            zoomOnScroll={false}
            preventScrolling={false}
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={20} size={1} color="#e5e0d8" />
          </ReactFlow>
        </div>

        {/* 节点详情面板 */}
        {selectedNode && nodeDataMap[selectedNode] && (
          <NodeDetailPanel
            nodeId={selectedNode}
            nodeData={nodeDataMap[selectedNode]}
            onClose={() => setSelectedNode(null)}
          />
        )}
      </ReactFlowProvider>
    </div>
  )
}
