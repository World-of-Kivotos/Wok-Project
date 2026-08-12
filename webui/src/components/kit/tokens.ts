/**
 * kit 层共用的档位词汇。
 *
 * 存在的理由是把"语义"与"某个组件库的 variant 名"解耦: 页面写 tone="danger", kit 内部才把它翻成
 * Coss UI 的 variant="error" 或 "destructive" —— 两者在上游是按控件分别命名的 (Badge 用 error,
 * Button 用 destructive, Alert 用 error), 让 15 个业务页各自记住这份差异是纯粹的负担,
 * 且上游改名时要全库搜字符串。
 *
 * 更要紧的是换皮成本: 像素风将来重启时 (docs/PixelUI_DesignSystem_DesignSpec.md 标 DEFERRED),
 * 要改的是 kit 内部的翻译表, 业务页的 tone="danger" 一个字都不用动。
 */

/** 语义档。neutral 是无语义的中性档, brand 是用户可调的强调色。 */
export type Tone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger' | 'info'

/** 全部语义档, 声明序即组件预览页的穷举序。加档只改这一处。 */
export const TONES: readonly Tone[] = ['neutral', 'brand', 'success', 'warning', 'danger', 'info']

/** 控件尺寸档。与 Coss UI 的 sm/default/lg 三档一一对应, 只是把 default 改叫 md。 */
export type ControlSize = 'sm' | 'md' | 'lg'

export const CONTROL_SIZES: readonly ControlSize[] = ['sm', 'md', 'lg']

/** kit -> Coss 的尺寸翻译。上游把中间档叫 default, 本项目一律叫 md。 */
export const COSS_SIZE: Record<ControlSize, 'sm' | 'default' | 'lg'> = {
  sm: 'sm',
  md: 'default',
  lg: 'lg',
}

/** 文本类元素的字号档。用于 Currency / Stat 这类不套控件外壳、只取字号的行内件。 */
export const TEXT_SIZE_CLASS: Record<ControlSize, string> = {
  sm: 'text-xs',
  md: 'text-sm',
  lg: 'text-base',
}

/** 语义档 -> 前景色工具类。用于图标与强调文字。 */
export const TONE_TEXT_CLASS: Record<Tone, string> = {
  neutral: 'text-muted-foreground',
  brand: 'text-brand',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-destructive',
  info: 'text-info',
}

/** 语义档 -> 实心填充色。用于进度条填充、状态点这类需要色块的位置。 */
export const TONE_FILL_CLASS: Record<Tone, string> = {
  neutral: 'bg-muted-foreground',
  brand: 'bg-brand',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-destructive',
  info: 'bg-info',
}
