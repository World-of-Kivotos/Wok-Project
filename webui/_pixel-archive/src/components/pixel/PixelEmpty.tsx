import type { ReactElement } from 'react'
import type { PixelIconName } from './PixelIcon'
import { PixelIcon } from './PixelIcon'
import { PixelFrame } from './PixelFrame'

/**
 * 空态占位。真源: conventions.md 十-L2 表。
 *
 * 容器一律走 `PixelFrame variant="panel"` —— 空态出现的位置(列表/表格/背包页取代内容区)
 * 与常规面板同一层级, 不需要 window 那种"浮起"的强调, 也不需要 inset 的凹陷感。
 * 图标故意压成 `text-muted`: 空态是"暂无内容"而不是需要提醒的异常, 亮色图标会让它比正常内容更抢眼。
 */

const BASE_CLASS = 'w-full'

export interface PixelEmptyProps {
  title: string
  hint?: string
  icon?: PixelIconName
  className?: string
}

export function PixelEmpty({ title, hint, icon, className }: PixelEmptyProps): ReactElement {
  return (
    <PixelFrame
      variant="panel"
      className={className === undefined ? BASE_CLASS : `${BASE_CLASS} ${className}`}
    >
      <div className="flex flex-col items-center gap-4 p-8 text-center">
        {icon === undefined ? null : <PixelIcon name={icon} scale={2} className="text-muted" />}
        <p className="text-2x text-fg">{title}</p>
        {hint === undefined ? null : <p className="text-1x text-muted">{hint}</p>}
      </div>
    </PixelFrame>
  )
}

/** 供组件预览页与消费空态的面板(市场我的上架 / 背包过滤后无结果等)复用的示例数据。 */
export const PIXEL_EMPTY_DEMO: PixelEmptyProps = {
  title: '暂无上架物品',
  hint: '去仓库页添加要出售的物品',
  icon: 'bag',
}
