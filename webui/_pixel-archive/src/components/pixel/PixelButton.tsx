import type { ReactElement, ReactNode } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelControlSize } from './controlSize'
import type { PixelFrameTone } from './PixelFrame'
import { PixelFrame } from './PixelFrame'
import type { PixelIconName } from './PixelIcon'
import { PixelIcon } from './PixelIcon'
import { PixelLoading } from './PixelLoading'

/**
 * 全库唯一的按钮控件。真源: conventions.md 十 · L1 · PixelButton(冻结 props: tone/size/disabled/
 * loading/icon/label/onClick/children)。
 *
 * 语义维度只有 `tone` 一个 —— conventions.md 二-2.4 明确禁止组件自造 `variant: primary/ghost/danger`
 * 这套 Web 默认词表, 上游任务描述里的"primary/ghost/danger"因此落到 tone 的 accent/neutral/danger
 * 三档, 不另开维度。
 *
 * 容器固定 `PixelFrame variant="panel"`(平面板, 二-2.4), tone 是按钮的语义身份, 在 normal/hover/
 * pressed/disabled 四态间保持不变 —— 9-slice 的中心块颜色由 `--pixel-tone` 这一个 CSS 变量决定
 * (PixelFrame.tsx), 而 tone 只有六个语义档而没有逐态子档 (`accent-hover` 不是合法 tone 值), 状态差
 * 因此只能靠"面板之外"的通道表达: 文字颜色 (仅 accent 有 hover/active 三态 token, 见 index.css) 与
 * 七-2 唯一被批准的位移 (`shadow-hard active:translate-y-1 active:shadow-none`) 挂在承载 PixelFrame
 * 的原生 `<button>` 本身。这不是偷懒的折衷, 而是当前色彩 token 表能诚实支撑的全部状态表达 ——
 * 硬造一套 `tone-hover` token 会违反十一节"不许动 index.css"。
 *
 * loading 复用 `PixelLoading`(七-3 一律不得自绘动效), 与 icon 共享同一个槽位而不是并排新增:
 * 忙碌时替换的是"位置指示", 文字 (children) 原样保留, 不触发三-3.2 禁止的"整段换掉导致宽度跳变"。
 * `disabled` 与 `loading` 语义/无障碍表达刻意不合并 (三-3.2): 前者原生 `disabled` 属性直接拦回调,
 * 后者保持可聚焦、只在 `handleClick` 内部拦, 外部用 `aria-busy` 而非 `aria-disabled` 声明。
 */

export interface PixelButtonBaseProps {
  tone?: PixelFrameTone
  size?: PixelControlSize
  disabled?: boolean
  loading?: boolean
  onClick: () => void
  className?: string
}

/**
 * 纯图标按钮 (无 children) 强制要求 `label`: 此时图标是唯一语义载体 (九-7), 少一个字段就是一个
 * 键盘/读屏用户摸不到名字的死按钮。用判别联合在编译期堵住, 而不是运行期报警 —— 运行期报警本身
 * 也需要一条"打印到哪"的路径, 编译期直接不让这份代码写出来更省事也更硬。
 */
export type PixelButtonContent =
  | { children: ReactNode; icon?: PixelIconName; label?: string }
  | { children?: undefined; icon: PixelIconName; label: string }

export type PixelButtonProps = PixelButtonBaseProps & PixelButtonContent

const TONE_TEXT_CLASS: Record<PixelFrameTone, string> = {
  neutral: 'text-fg',
  accent: 'text-accent',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  info: 'text-info',
}

export function PixelButton(props: PixelButtonProps): ReactElement {
  const {
    tone = 'neutral',
    size = 'md',
    disabled = false,
    loading = false,
    onClick,
    className,
    icon,
    label,
    children,
  } = props

  /*
   * ReactNode 里 null / false / undefined 都渲染成空, 但只有 undefined 会被判别联合逼着给 label。
   * 调用方传一个运行期为 null 的变量 (`{maybeText}`) 时, 编译期命中的是"有 children"那一支,
   * 运行期却什么都不画 —— 早先只比 undefined, 结果是一个既无可见文字也无 aria-label 的哑按钮,
   * 键盘与读屏用户完全拿不到它的名字。三种空值一并算作纯图标态。
   */
  const iconOnly = children === undefined || children === null || typeof children === 'boolean'

  const handleClick = (): void => {
    // disabled 已由原生 disabled 属性挡在浏览器层, 这里补的是 loading 分支 —— 它刻意不设原生 disabled
    // (见文件头注释), 回调必须由控件自己拦。
    if (disabled || loading) {
      return
    }
    onClick()
  }

  const effectiveTone: PixelFrameTone = disabled ? 'neutral' : tone
  const textClass = disabled
    ? 'text-muted'
    : `${TONE_TEXT_CLASS[tone]}${tone === 'accent' ? ' hover:text-accent-hover active:text-accent-active' : ''}`

  return (
    <button
      type="button"
      disabled={disabled}
      aria-busy={loading}
      {...(iconOnly ? { 'aria-label': label } : {})}
      onClick={handleClick}
      className={`inline-block border-2 border-transparent shadow-hard outline-none focus-visible:border-border-strong active:translate-y-1 active:shadow-none disabled:shadow-none ${
        className === undefined ? '' : className
      }`}
    >
      <PixelFrame
        variant="panel"
        tone={effectiveTone}
        className={`inline-flex items-center gap-2 ${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]} ${textClass}`}
      >
        {loading ? (
          <PixelLoading size={size} />
        ) : icon === undefined ? null : (
          <PixelIcon name={icon} scale={1} />
        )}
        {children}
      </PixelFrame>
    </button>
  )
}

export interface PixelButtonDemoCase {
  readonly tone: PixelFrameTone
  readonly label: string
}

/** 三档语义各配一个贴合真实业务的动作文案, 供预览页与后续面板直接映射渲染, 不必各自现造样例文案。 */
export const PIXEL_BUTTON_DEMO_CASES: readonly PixelButtonDemoCase[] = [
  { tone: 'accent', label: '确认' },
  { tone: 'neutral', label: '取消' },
  { tone: 'danger', label: '删除' },
]
