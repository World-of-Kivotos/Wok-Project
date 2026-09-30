import type { PointerEvent as ReactPointerEvent, ReactElement } from 'react'
import { useId, useRef, useState } from 'react'
import type { DistrictBounds, PlotArea, PlotSummary } from '@/lib/types'
import { PLOT_STATUS_LABEL, freezeLeftText, freezeMapText } from './format'

/**
 * 本区俯视平面图: 按真实坐标等比画出自管区边界与每一块地块, 点地块选中 (与下面的地块列表联动);
 * 划 / 调地块时在图上按住拖出一个矩形 (整数格对齐)。
 *
 * 坐标系就是游戏坐标: SVG 的 x = 方块 X, y = 方块 Z (Z 越大越靠南, 与屏幕向下同向), 于是"上北下南"天然成立,
 * 不需要任何翻转。一格方块 = 1 个 SVG 单位; 地块范围含两端, 故宽 = maxX - minX + 1。
 *
 * 颜色一律取主题令牌 (Tailwind 的 fill-* / stroke-* 类), 亮暗两套主题自动跟着换:
 *   我的 = brand, 已有户主 = 中性灰, 空置 = success, 冻结中 = warning + 斜纹。选中 = 前景色粗框。
 * 形状也在区分 (空置虚线框、冻结斜纹), 不全靠颜色: 强调色是用户可调的, 调到哪个色相都不能和别的状态撞成一样。
 * 线宽一律 non-scaling-stroke (按屏幕像素算): 自管区有大有小, 按方块算的线宽在大区里会细到看不见。
 *
 * 尺寸: 宽度跟随容器、高度按比例, 最高 20rem; 超出时整张图按比例缩小居中 (preserveAspectRatio meet),
 * 平板 765 与 867 两档内容区都不会横向溢出。
 */

type PlotKind = 'mine' | 'owned' | 'vacant' | 'frozen'

/** 冻结地块第二行字 ("剩 N 天") 相对编号字号的比例 (地块窄时还会再缩, 见下面 freezeFont)。 */
const FREEZE_LINE_SCALE = 0.8

/**
 * 地块里的字最小缩到整图基准字号的几成。基准字号按整张图的跨度定 (span / 22), 小地块 (如 8 x 8) 放不下
 * 基准字号时按地块自己的宽高缩小, 缩到这一档以下才不写 —— 不然刚划出来的最小地块连编号都没有。
 */
const MIN_LABEL_SCALE = 0.5

const KIND_CLASS: Record<PlotKind, string> = {
  mine: 'fill-brand/45 stroke-brand',
  owned: 'fill-muted-foreground/25 stroke-muted-foreground',
  vacant: 'fill-success/20 stroke-success',
  frozen: 'fill-warning/20 stroke-warning',
}

/** 图例色块与图上一致: 空置虚线框, 冻结中带斜纹 (图上是 SVG 斜纹, 色块用一样走向的重复渐变画)。 */
export const PLOT_LEGEND: readonly { kind: PlotKind; label: string; swatch: string }[] = [
  { kind: 'mine', label: '我的', swatch: 'border-brand bg-brand/45' },
  { kind: 'owned', label: PLOT_STATUS_LABEL.owned, swatch: 'border-muted-foreground bg-muted-foreground/25' },
  { kind: 'vacant', label: PLOT_STATUS_LABEL.vacant, swatch: 'border-dashed border-success bg-success/20' },
  {
    kind: 'frozen',
    label: PLOT_STATUS_LABEL.frozen,
    swatch:
      'border-warning bg-warning/20 bg-[image:repeating-linear-gradient(45deg,var(--color-warning)_0_1.5px,transparent_1.5px_4px)]',
  },
]

/** 从这个码位起 (CJK 部首补充) 往后的字按全角算。 */
const FULL_WIDTH_FROM = 0x2e80

/** 一行字大约占几个字号宽: 汉字按 1, 数字、空格、字母按 0.6。只用来判断放不放得下, 不求精确。 */
function textEm(text: string): number {
  let em = 0
  for (const char of text) {
    em += (char.codePointAt(0) ?? 0) >= FULL_WIDTH_FROM ? 1 : 0.6
  }
  return em
}

function kindOf(plot: PlotSummary, myPlotId: string | null): PlotKind {
  if (plot.plotId === myPlotId) {
    return 'mine'
  }
  return plot.status
}

/** 编号里给人看的那一段 ("千年-04" -> "04"), 画在地块中间。 */
function shortCode(code: string): string {
  const index = code.lastIndexOf('-')
  return index === -1 ? code : code.slice(index + 1)
}

function describePlot(plot: PlotSummary, myPlotId: string | null, now: number): string {
  if (plot.plotId === myPlotId) {
    return `${plot.code}，我的地块`
  }
  if (plot.status === 'frozen' && plot.frozen !== null) {
    return `${plot.code}，${freezeLeftText(plot.frozen.reclaimAt, now)}`
  }
  if (plot.status === 'owned' && plot.ownerName !== null) {
    return `${plot.code}，户主 ${plot.ownerName}`
  }
  return `${plot.code}，${PLOT_STATUS_LABEL[plot.status]}`
}

interface Cell {
  x: number
  z: number
}

function areaBetween(start: Cell, end: Cell): PlotArea {
  return {
    minX: Math.min(start.x, end.x),
    minZ: Math.min(start.z, end.z),
    maxX: Math.max(start.x, end.x),
    maxZ: Math.max(start.z, end.z),
  }
}

export interface PlotMapDrawing {
  /** 当前的新范围 (输入框或拖出来的); null = 还没有。 */
  draft: PlotArea | null
  /** 新范围是否合规 (决定画成中性虚线框还是红框)。 */
  valid: boolean
  /** 正在调整的那块地 (原范围画淡); 新划地块为 null。 */
  editingPlotId: string | null
  onDraw: (area: PlotArea) => void
}

export function PlotMap({
  label,
  bounds,
  plots,
  myPlotId,
  selectedId,
  onSelect,
  edgeGap,
  now,
  drawing,
}: {
  /** 读屏念的整张图的名字, 如"千年自管区俯视图"。 */
  label: string
  bounds: DistrictBounds
  plots: readonly PlotSummary[]
  myPlotId: string | null
  selectedId: string | null
  onSelect: (plotId: string) => void
  edgeGap: number
  now: number
  /** 非 null = 画框模式: 在图上按住拖出矩形, 点地块不再选中。 */
  drawing: PlotMapDrawing | null
}): ReactElement {
  const svgRef = useRef<SVGSVGElement>(null)
  const dragStart = useRef<Cell | null>(null)
  const [focusedId, setFocusedId] = useState<string | null>(null)
  // useId 在 React 19 里带特殊字符, 不能直接放进 url(#…)。
  const idBase = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const hatchId = `plot-hatch-${idBase}`
  const clipId = `plot-clip-${idBase}`

  const width = bounds.maxX - bounds.minX + 1
  const depth = bounds.maxZ - bounds.minZ + 1
  const span = Math.max(width, depth)
  const pad = span * 0.02
  const fontSize = span / 22
  const hatchSize = span / 36

  function cellAt(event: ReactPointerEvent<SVGSVGElement>): Cell | null {
    const svg = svgRef.current
    const matrix = svg?.getScreenCTM() ?? null
    if (matrix === null) {
      return null
    }
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse())
    // 拖到区外时贴住边界: 区外的格子本来就划不了, 贴边之后校验会如实报"离边界太近"。
    return {
      x: Math.min(bounds.maxX, Math.max(bounds.minX, Math.floor(point.x))),
      z: Math.min(bounds.maxZ, Math.max(bounds.minZ, Math.floor(point.y))),
    }
  }

  function handlePointerDown(event: ReactPointerEvent<SVGSVGElement>): void {
    if (drawing === null || event.button !== 0) {
      return
    }
    const cell = cellAt(event)
    if (cell === null) {
      return
    }
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    dragStart.current = cell
    drawing.onDraw(areaBetween(cell, cell))
  }

  function endDrag(): void {
    dragStart.current = null
  }

  function handlePointerMove(event: ReactPointerEvent<SVGSVGElement>): void {
    const start = dragStart.current
    if (drawing === null || start === null) {
      return
    }
    // 松开那一下没送到 (拖出窗口外松手、游戏切走焦点…): 键已经不按着了, 不能再跟着指针改范围。
    if (event.buttons === 0) {
      endDrag()
      return
    }
    const cell = cellAt(event)
    if (cell !== null) {
      drawing.onDraw(areaBetween(start, cell))
    }
  }

  const draft = drawing?.draft ?? null

  return (
    <svg
      aria-label={label}
      className={`block h-auto max-h-80 w-full select-none ${drawing === null ? '' : 'cursor-crosshair touch-none'}`}
      // 捕获丢了 (pointerup 没送到这里、元素被重渲染替换…) 也算拖完, 免得之后光移动指针就在改范围。
      onLostPointerCapture={endDrag}
      onPointerCancel={endDrag}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endDrag}
      preserveAspectRatio="xMidYMid meet"
      ref={svgRef}
      role="group"
      viewBox={`${String(bounds.minX - pad)} ${String(bounds.minZ - pad)} ${String(width + pad * 2)} ${String(depth + pad * 2)}`}
    >
      <defs>
        <pattern
          height={hatchSize}
          id={hatchId}
          patternTransform="rotate(45)"
          patternUnits="userSpaceOnUse"
          width={hatchSize}
        >
          <line className="stroke-warning" strokeWidth={hatchSize * 0.35} x1={0} x2={0} y1={0} y2={hatchSize} />
        </pattern>
        {/* 输入框里填了区外的坐标时, 新范围框只画到图的边上, 不画进旁边的留白里。 */}
        <clipPath id={clipId}>
          <rect height={depth + pad * 2} width={width + pad * 2} x={bounds.minX - pad} y={bounds.minZ - pad} />
        </clipPath>
      </defs>

      {/* 自管区本身 (公共区域) */}
      <rect
        className="fill-muted stroke-border"
        height={depth}
        strokeWidth={1.5}
        vectorEffect="non-scaling-stroke"
        width={width}
        x={bounds.minX}
        y={bounds.minZ}
      />
      {/* 离边界 edgeGap 格的虚线: 线内才能划地块 */}
      {edgeGap > 0 && width > edgeGap * 2 && depth > edgeGap * 2 ? (
        <rect
          className="fill-none stroke-muted-foreground/60"
          height={depth - edgeGap * 2}
          strokeDasharray="3 3"
          strokeWidth={1}
          vectorEffect="non-scaling-stroke"
          width={width - edgeGap * 2}
          x={bounds.minX + edgeGap}
          y={bounds.minZ + edgeGap}
        />
      ) : null}

      {plots.map((plot) => {
        const kind = kindOf(plot, myPlotId)
        const x = plot.bounds.minX
        const y = plot.bounds.minZ
        const w = plot.bounds.maxX - plot.bounds.minX + 1
        const h = plot.bounds.maxZ - plot.bounds.minZ + 1
        const editing = drawing !== null && drawing.editingPlotId === plot.plotId
        const selected = plot.plotId === selectedId
        const focused = plot.plotId === focusedId
        // 编号字号按地块自己的宽高缩 (两位编号约 1.2 个字号宽, 留出边距取 1.8)。
        const codeFont = Math.min(fontSize, w / 1.8, h / 1.3)
        const labelFits = codeFont >= fontSize * MIN_LABEL_SCALE
        // 冻结中的在编号下面再写一行"剩 N 天": 地块窄就再缩一点, 缩过头或高度不够两行就不写
        // (悬停提示、右边的卡片和列表里都有)。
        const freezeText = plot.frozen === null ? null : freezeMapText(plot.frozen.reclaimAt, now)
        const freezeFont =
          freezeText === null ? 0 : Math.min(codeFont * FREEZE_LINE_SCALE, (w * 0.9) / textEm(freezeText))
        const lineGap = codeFont * 0.15
        const freezeLine =
          freezeText !== null &&
          labelFits &&
          freezeFont >= fontSize * MIN_LABEL_SCALE &&
          h >= (codeFont + lineGap + freezeFont) * 1.25
            ? freezeText
            : null
        const description = describePlot(plot, myPlotId, now)
        return (
          <g
            aria-label={description}
            aria-pressed={selected}
            className={`outline-none ${drawing === null ? 'cursor-pointer' : ''} ${editing ? 'opacity-35' : ''}`}
            key={plot.plotId}
            onBlur={() => {
              setFocusedId(null)
            }}
            onClick={() => {
              if (drawing === null) {
                onSelect(plot.plotId)
              }
            }}
            onFocus={() => {
              setFocusedId(plot.plotId)
            }}
            onKeyDown={(event) => {
              if (drawing === null && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault()
                onSelect(plot.plotId)
              }
            }}
            role="button"
            tabIndex={drawing === null ? 0 : -1}
          >
            <title>{description}</title>
            <rect
              className={KIND_CLASS[kind]}
              height={h}
              strokeDasharray={kind === 'vacant' ? '4 2' : undefined}
              strokeWidth={1.5}
              vectorEffect="non-scaling-stroke"
              width={w}
              x={x}
              y={y}
            />
            {kind === 'frozen' ? (
              <rect className="pointer-events-none" fill={`url(#${hatchId})`} height={h} opacity={0.5} width={w} x={x} y={y} />
            ) : null}
            {labelFits ? (
              <text
                className="pointer-events-none fill-foreground font-medium"
                dominantBaseline="central"
                fontSize={codeFont}
                textAnchor="middle"
                x={x + w / 2}
                // 两行时整块 (编号 + 间距 + 第二行) 在地块里垂直居中。
                y={freezeLine === null ? y + h / 2 : y + h / 2 - (lineGap + freezeFont) / 2}
              >
                {shortCode(plot.code)}
              </text>
            ) : null}
            {freezeLine === null ? null : (
              <text
                className="pointer-events-none fill-foreground"
                dominantBaseline="central"
                fontSize={freezeFont}
                textAnchor="middle"
                x={x + w / 2}
                y={y + h / 2 + (codeFont + lineGap) / 2}
              >
                {freezeLine}
              </text>
            )}
            {selected || focused ? (
              <rect
                className="pointer-events-none fill-none stroke-foreground"
                height={h}
                strokeDasharray={selected ? undefined : '3 2'}
                strokeWidth={selected ? 3 : 2}
                vectorEffect="non-scaling-stroke"
                width={w}
                x={x}
                y={y}
              />
            ) : null}
          </g>
        )
      })}

      {draft === null ? null : (
        <rect
          className={`pointer-events-none ${drawing?.valid === true ? 'fill-foreground/10 stroke-foreground' : 'fill-destructive/20 stroke-destructive'}`}
          clipPath={`url(#${clipId})`}
          height={draft.maxZ - draft.minZ + 1}
          strokeDasharray="5 3"
          strokeWidth={2}
          vectorEffect="non-scaling-stroke"
          width={draft.maxX - draft.minX + 1}
          x={draft.minX}
          y={draft.minZ}
        />
      )}
    </svg>
  )
}
