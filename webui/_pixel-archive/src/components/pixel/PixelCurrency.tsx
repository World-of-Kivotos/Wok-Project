import type { ReactElement } from 'react'
import type { PixelControlSize } from './controlSize'
import { PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import { PixelIcon } from './PixelIcon'

/**
 * 双货币行内展示: 信用点 (可交易) 与青辉石 (不可转移) 共用同一套格式化, 差别只在图标由调用方经
 * currency 挑选。真源: conventions.md 十 · L1 · PixelCurrency (冻结 props: amount/currency/size/
 * showIcon) 与八-3 (kind 类型只在本文件声明, 不 import 契约层; market.place 的大写 'CREDIT' 与此处
 * 小写 'credit' 是两套不相关的枚举, 不许互相套用)。
 *
 * 不套 PixelFrame: conventions.md 二-2.2 明确把它与"紧凑档的 PixelBadge"并列为只取字号、不吃控件
 * 内边距的行内文本件——它就是一行数字, 出现在钱包摘要/挂单价格这类内联语境里, 套一层 9-slice 边框
 * 反而会在这些场景里制造出一堆不需要的小方框。
 */

export type PixelCurrencyKind = 'credit' | 'azure'

export interface PixelCurrencyProps {
  amount: number
  currency: PixelCurrencyKind
  size?: PixelControlSize
  /** 默认 true。 */
  showIcon?: boolean
  className?: string
}

const CURRENCY_ICON: Record<PixelCurrencyKind, 'coin-credit' | 'coin-azure'> = {
  credit: 'coin-credit',
  azure: 'coin-azure',
}

const GROUP_DIGITS = /\B(?=(\d{3})+(?!\d))/g

/**
 * 千分位分组只处理整数部分, 小数部分原样保留不分组。当前经济数值恒为整数 (Java long 序列化而来),
 * 但格式化函数本身不该替上游"悄悄抹平"一个理论上不该出现的小数——那等于制造一个新的静默失真来源。
 */
function formatAmount(amount: number): string {
  const sign = amount < 0 ? '-' : ''
  const [integerPart, fractionPart] = Math.abs(amount).toString().split('.')
  const grouped = (integerPart ?? '0').replace(GROUP_DIGITS, ',')
  return fractionPart === undefined ? `${sign}${grouped}` : `${sign}${grouped}.${fractionPart}`
}

export function PixelCurrency({
  amount,
  currency,
  size = 'md',
  showIcon = true,
  className,
}: PixelCurrencyProps): ReactElement {
  const baseClass = `inline-flex items-center gap-1 ${PIXEL_CONTROL_TEXT_CLASS[size]} text-fg`
  return (
    <span className={className === undefined ? baseClass : `${baseClass} ${className}`}>
      {showIcon ? <PixelIcon name={CURRENCY_ICON[currency]} scale={1} /> : null}
      <span>{formatAmount(amount)}</span>
    </span>
  )
}

/** 信用点/青辉石各两条 (含一条大额验千分位分组), 供预览页与面板 agent 直接复用。 */
export interface PixelCurrencyDemoItem {
  readonly id: string
  readonly amount: number
  readonly currency: PixelCurrencyKind
}

export const PIXEL_CURRENCY_DEMO_ITEMS: readonly PixelCurrencyDemoItem[] = [
  { id: 'credit-small', amount: 128, currency: 'credit' },
  { id: 'credit-large', amount: 1284560, currency: 'credit' },
  { id: 'azure-small', amount: 6, currency: 'azure' },
  { id: 'azure-large', amount: 23890, currency: 'azure' },
]
