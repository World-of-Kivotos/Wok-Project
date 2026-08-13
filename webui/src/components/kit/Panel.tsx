import type { ReactElement, ReactNode } from 'react'
import { Card } from '@/components/ui/card'
import type { Tone } from './tokens'

/**
 * 页面分区容器。业务页里最高频的结构 —— 一个带标题的卡片, 右上角可能挂几个操作按钮。
 *
 * 为什么不让页面直接用 Coss 的 Card + CardHeader + CardTitle + CardPanel 四件套:
 * 那套组合在 15 个页面里会被抄写上百次, 而每次抄写都是一次"标题字号/内边距/间距"漂移的机会 ——
 * 上一版界面的 h2 字号跨批次接缝 (市场五页一档、其余页面另一档) 就是这么来的。收成一个组件后,
 * 分区标题的排版只有一处定义。
 *
 * 内边距刻意不用 Coss 的 CardHeader/CardPanel (它们是 p-6): 本界面是游戏内平板, 一屏要塞下
 * 挂单列表/职业进度这类密集数据, 24px 的四边留白会让可视行数少掉三分之一。这里统一收到 16px。
 */

export interface PanelProps {
  /** 分区标题。不给则不渲染表头行, 整个卡片只有内容区。 */
  title?: string | undefined
  /** 标题下方的一行说明。仅在 title 存在时渲染。 */
  description?: string | undefined
  /** 表头行右侧的操作区 (按钮 / 徽标 / 筛选器)。 */
  actions?: ReactNode | undefined
  /** 置假时内容区不加内边距 —— 表格铺满卡片时用。默认真。 */
  padded?: boolean | undefined
  children: ReactNode
  className?: string | undefined
}

export function Panel({
  title,
  description,
  actions,
  padded = true,
  children,
  className,
}: PanelProps): ReactElement {
  const hasHead = title !== undefined || actions !== undefined
  return (
    <Card className={className}>
      {hasHead ? (
        <div className="flex items-start justify-between gap-3 border-b px-4 py-3">
          <div className="flex min-w-0 flex-col gap-0.5">
            {title === undefined ? null : (
              <h2 className="truncate font-medium text-base text-foreground">{title}</h2>
            )}
            {description === undefined ? null : (
              <p className="text-muted-foreground text-xs">{description}</p>
            )}
          </div>
          {actions === undefined ? null : (
            <div className="flex shrink-0 items-center gap-2">{actions}</div>
          )}
        </div>
      ) : null}
      <div className={padded ? 'flex flex-1 flex-col p-4' : 'flex flex-1 flex-col'}>{children}</div>
    </Card>
  )
}

/**
 * 轻量着色块。用于"这一段内容属于某个语义状态"的场合 (等级门未达成的说明、危险操作的前置警告),
 * 比 Panel 轻, 比 FeedbackAlert 更适合包住一段结构化内容而不只是一行字。
 *
 * tone="neutral" 是一块纯中性的次级底, 不带任何语义色 —— 默认档, 也是最常用的一档。
 */
const SURFACE_TONE_CLASS: Record<Tone, string> = {
  neutral: 'border-border bg-muted/40',
  brand: 'border-brand/32 bg-brand/8',
  success: 'border-success/32 bg-success/8',
  warning: 'border-warning/32 bg-warning/8',
  danger: 'border-destructive/32 bg-destructive/8',
  info: 'border-info/32 bg-info/8',
}

export interface SurfaceProps {
  tone?: Tone | undefined
  children: ReactNode
  className?: string | undefined
}

export function Surface({ tone = 'neutral', children, className }: SurfaceProps): ReactElement {
  return (
    <div
      className={`rounded-lg border p-3 ${SURFACE_TONE_CLASS[tone]}${
        className === undefined ? '' : ` ${className}`
      }`}
    >
      {children}
    </div>
  )
}
