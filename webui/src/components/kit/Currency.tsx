import { CoinsIcon, GemIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useCountUp } from '@/lib/motion'
import type { ControlSize } from './tokens'
import { TEXT_SIZE_CLASS } from './tokens'

/**
 * 双货币行内展示: 信用点 (可交易) 与青辉石 (不可转移)。
 *
 * 两支货币刻意只靠图标形状区分, 不靠颜色: 整套界面是中性灰阶, 给货币各配一支彩色会让"钱"成为
 * 屏幕上最抢眼的东西, 而它在多数页面里只是一行辅助信息。硬币与宝石两个轮廓在 14px 下已足够可辨。
 *
 * 不套任何容器: 它出现在钱包摘要、挂单价格、手续费预览这类内联语境里, 套一层卡片会在这些位置
 * 制造出一堆不需要的小方框。
 *
 * 类型上刻意不 import 契约层的货币枚举: market.place 的大写 'CREDIT' 与此处小写 'credit' 是两套
 * 不相关的枚举 (一套是服务端协议, 一套是本组件的展示档), 互相套用会在协议改名时静默错位。
 */

export type CurrencyKind = 'credit' | 'azure'

export interface CurrencyProps {
  amount: number
  currency: CurrencyKind
  size?: ControlSize | undefined
  /** 默认 true。 */
  showIcon?: boolean | undefined
  /** 语义着色: 收入为正、支出为负时自动着色。默认 false, 即一律用常规前景色。 */
  signed?: boolean | undefined
  /**
   * 金额变化时滚动到新值。默认 false, 且刻意做成 opt-in ——
   * 本组件也用在挂单价、手续费预览这类<b>成排出现</b>的位置, 那里每行都滚一遍只是噪音 (还要为此重绘半屏)。
   * 只有"同一个数字持续代表同一件事"的场合 (顶栏钱包) 才值得滚: 那时滚动传达的是"你刚赚了多少"。
   */
  animate?: boolean | undefined
  className?: string | undefined
}

const CURRENCY_LABEL: Record<CurrencyKind, string> = {
  credit: '信用点',
  azure: '青辉石',
}

const ICON_SIZE_CLASS: Record<ControlSize, string> = {
  sm: 'size-3',
  md: 'size-3.5',
  lg: 'size-4',
}

const GROUP_DIGITS = /\B(?=(\d{3})+(?!\d))/g

/**
 * 千分位分组只处理整数部分, 小数部分原样保留不分组。当前经济数值恒为整数 (Java long 序列化而来),
 * 但格式化函数本身不该替上游"悄悄抹平"一个理论上不该出现的小数 —— 那等于制造一个新的静默失真来源。
 */
export function formatAmount(amount: number): string {
  const sign = amount < 0 ? '-' : ''
  const [integerPart, fractionPart] = Math.abs(amount).toString().split('.')
  const grouped = (integerPart ?? '0').replace(GROUP_DIGITS, ',')
  return fractionPart === undefined ? `${sign}${grouped}` : `${sign}${grouped}.${fractionPart}`
}

export function Currency({
  amount,
  currency,
  size = 'md',
  showIcon = true,
  signed = false,
  animate = false,
  className,
}: CurrencyProps): ReactElement {
  const Icon = currency === 'credit' ? CoinsIcon : GemIcon
  // hook 必须无条件调用 (React 规则), 故由 animate 决定用哪个结果而不是决定要不要调。
  const rolled = useCountUp(amount)
  const shown = animate ? rolled : amount
  // 着色仍看**目标值**而不是滚动中的中间值: 一笔正收入在滚动途中不该有任何一帧显示成红色。
  const signClass = signed && amount > 0 ? 'text-success' : signed && amount < 0 ? 'text-destructive' : ''
  const text = signed && amount > 0 ? `+${formatAmount(shown)}` : formatAmount(shown)

  return (
    <span
      className={`inline-flex items-center gap-1 tabular-nums ${TEXT_SIZE_CLASS[size]} ${
        signClass === '' ? 'text-foreground' : signClass
      }${className === undefined ? '' : ` ${className}`}`}
      title={CURRENCY_LABEL[currency]}
    >
      {showIcon ? (
        <Icon aria-hidden="true" className={`${ICON_SIZE_CLASS[size]} shrink-0 text-muted-foreground`} />
      ) : null}
      <span>{text}</span>
    </span>
  )
}
