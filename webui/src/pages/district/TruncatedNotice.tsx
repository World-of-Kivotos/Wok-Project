import type { ReactElement } from 'react'
import { Surface } from '@/components/kit'

/**
 * 列表被服务端截断时, 画在那张列表上方的一行提示。
 *
 * 回执有体积预算 (District_Backend_Design 14.3): 装不下时服务端直接不下发列表的尾部, 只把对应的截断标记 (plotsTruncated 这一类) 置真。
 * 界面不提示的话列表看起来是完整的, 缺的那几项谁都发现不了 —— 地块列表被截掉的恰好是最新划出的在售地块。
 * 平板上还不能翻页 (契约分页是后续工作), 所以这里只如实说两件事: 没显示全, 以及全的去哪看。
 * 文案由调用方写: 各张列表"其余的去哪看"并不一样 (地块、住户有 /district 命令可查, 记录和留档没有)。
 */
export function TruncatedNotice({
  children,
  className,
}: {
  children: string
  className?: string | undefined
}): ReactElement {
  return (
    <Surface className={className} tone="warning">
      <p className="text-foreground text-xs">{children}</p>
    </Surface>
  )
}
