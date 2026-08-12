import type { ReactElement } from 'react'
import type { PixelControlSize } from './controlSize'
import { PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelFrameTone } from './PixelFrame'
import { PixelFrame } from './PixelFrame'

/**
 * 数值进度条: 经验条/耐久/产能缓冲共用同一副身体, 差别只在 tone 与是否传 segments/thresholds。
 * 真源: conventions.md 十 · L1 · PixelProgress (冻结 props: value/max/tone/size/label)。
 *
 * 轨道固定用 PixelFrame variant="inset"——conventions.md 二-2.4 明确把内凹凹槽点名给了"进度槽"这个
 * 用途, 不是本文件自选的形状。填充与阈值标记是轨道内的纯色矩形, 不套 9-slice: 色块本身没有斜面,
 * 用图像边框资产画一条纯色矩形反而要多绕一层。
 *
 * segments 与 thresholds 是冻结表之外新增的两个维度 (十一节允许"表里没列到的 props 自行加"):
 * 前者把单条填充拆成首尾相接的多色分段 (如产能缓冲的三级构成), 后者在轨道上叠一条细线标记关键位置
 * (如耐久的警戒线)。两者都只影响视觉, aria 数值语义仍然只认 value/max。
 */

export interface PixelProgressSegment {
  /** 本段自身的量 (非累计值), 从上一段末尾续接渲染; 分段之和允许小于 max (末端留白), 超出部分按轨道宽度裁剪。 */
  amount: number
  tone: PixelFrameTone
}

export interface PixelProgressThreshold {
  /** 阈值在 [0, max] 区间的绝对位置, 不是百分比。 */
  at: number
  /** 缺省 danger——阈值标记最典型的用途是警戒线。 */
  tone?: PixelFrameTone
}

export interface PixelProgressProps {
  value: number
  /** 必须为正数; 轨道百分比按 value/max 计算, 本组件不为 0 或负值兜底 (那会把上游传错的值悄悄描成看似正常的进度条)。 */
  max: number
  tone?: PixelFrameTone
  size?: PixelControlSize
  label?: string
  segments?: readonly PixelProgressSegment[]
  thresholds?: readonly PixelProgressThreshold[]
  className?: string
}

const TRACK_HEIGHT_CLASS: Record<PixelControlSize, string> = {
  sm: 'h-4',
  md: 'h-6',
  lg: 'h-8',
}

const TONE_FILL_CLASS: Record<PixelFrameTone, string> = {
  neutral: 'bg-fg',
  accent: 'bg-accent',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
  info: 'bg-info',
}

/** 越界值按端点画 (冻结表备注): 传入非正 max 时结果是 NaN, 在轨道上表现为不渲染而非被静默纠正成假进度。 */
function clampPercent(value: number, max: number): number {
  return Math.min(100, Math.max(0, (value / max) * 100))
}

export function PixelProgress({
  value,
  max,
  tone = 'neutral',
  size = 'md',
  label,
  segments,
  thresholds,
  className,
}: PixelProgressProps): ReactElement {
  let cursor = 0
  const segmentBars =
    segments === undefined
      ? null
      : segments.map((segment, index) => {
          const left = clampPercent(cursor, max)
          const rawWidth = (segment.amount / max) * 100
          const width = Math.max(0, Math.min(rawWidth, 100 - left))
          cursor += segment.amount
          return (
            <div
              key={`${String(index)}-${String(segment.amount)}`}
              className={`absolute inset-y-0 ${TONE_FILL_CLASS[segment.tone]}`}
              style={{ left: `${String(left)}%`, width: `${String(width)}%` }}
            />
          )
        })

  return (
    <div
      className={className === undefined ? 'flex flex-col gap-1' : `flex flex-col gap-1 ${className}`}
      role="progressbar"
      /*
       * 播报值必须与画出来的那一条一致。视觉侧走 clampPercent 钳到 [0, max], 而这里早先直传原始 value,
       * 于是越界数据下"看到的是满格、听到的是 130/100" —— 两条通路各说各话, 读屏用户拿到的是错的那份。
       * 钳制在这里做一次, 与 clampPercent 同一口径。
       */
      aria-valuenow={Math.min(max, Math.max(0, value))}
      aria-valuemin={0}
      aria-valuemax={max}
      {...(label === undefined ? {} : { 'aria-label': label })}
    >
      {label === undefined ? null : (
        <span className={`${PIXEL_CONTROL_TEXT_CLASS[size]} text-muted`} aria-hidden>
          {label}
        </span>
      )}
      <PixelFrame variant="inset" className={`block w-full ${TRACK_HEIGHT_CLASS[size]}`}>
        {/*
          裁切与定位基准挂在这层内层 div 上, 不能挂回 PixelFrame 自身。
          PixelFrame 的染色层是一层 inset 为负 (外扩到 border 区) 的伪元素 (index.css 的
          [data-pixel-tone]::after), 而 overflow: hidden 按 padding box 裁剪后代 —— 直接给 PixelFrame
          加 overflow-hidden 会把伪元素外扩的那一圈剪掉, 结果是中心块被上色、9-slice 边框仍是原始灰度,
          且不报错。轨道要 overflow 只是为了压住末端阈值标记与分段条的溢出, 这件事内层做即可。
        */}
        <div className="relative h-full w-full overflow-hidden">
          {segmentBars ?? (
            <div
              className={`h-full ${TONE_FILL_CLASS[tone]}`}
              style={{ width: `${String(clampPercent(value, max))}%` }}
            />
          )}
          {thresholds?.map((threshold, index) => (
            <div
              key={`${String(index)}-${String(threshold.at)}`}
              className={`absolute inset-y-0 w-1 ${TONE_FILL_CLASS[threshold.tone ?? 'danger']}`}
              style={{ left: `${String(clampPercent(threshold.at, max))}%` }}
            />
          ))}
        </div>
      </PixelFrame>
    </div>
  )
}

/** 经验条 (纯色单值) / 耐久 (阈值标记) / 产能缓冲 (多段合成) 三类典型用法, 供预览页与面板 agent 复用。 */
export interface PixelProgressDemoItem {
  readonly id: string
  readonly label: string
  readonly value: number
  readonly max: number
  readonly tone: PixelFrameTone
  readonly segments?: readonly PixelProgressSegment[]
  readonly thresholds?: readonly PixelProgressThreshold[]
}

export const PIXEL_PROGRESS_DEMO_ITEMS: readonly PixelProgressDemoItem[] = [
  { id: 'exp', label: '经验值 1180/2000', value: 1180, max: 2000, tone: 'accent' },
  {
    id: 'durability',
    label: '耐久 42/100 (警戒阈值 20)',
    value: 42,
    max: 100,
    tone: 'warning',
    thresholds: [{ at: 20, tone: 'danger' }],
  },
  {
    id: 'buffer',
    label: '产能缓冲 (三级分段构成)',
    value: 80,
    max: 120,
    tone: 'neutral',
    segments: [
      { amount: 40, tone: 'success' },
      { amount: 30, tone: 'warning' },
      { amount: 10, tone: 'danger' },
    ],
  },
]
