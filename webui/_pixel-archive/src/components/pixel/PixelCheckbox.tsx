import type { ReactElement } from 'react'
import { PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelControlSize } from './controlSize'
import { PixelFrame } from './PixelFrame'
import type { PixelIconScale } from './PixelIcon'
import { PixelIcon } from './PixelIcon'

/**
 * 勾选框。真源: conventions.md 十 · L1 · PixelCheckbox(冻结 props: checked/onChange/label/disabled/
 * size, "label 必填且点击区包含文字")。
 *
 * 原生 `<input type="checkbox">` 藏在 `sr-only`(仅对可访问性树可见)里承担全部键盘/读屏语义
 * (勾选态播报、Space 切换、原生 disabled), 视觉呈现完全交给 `PixelFrame variant="inset"` + `PixelIcon`
 * ——两者用 `peer`/`peer-focus-visible:` 关联而不是自己另写一套 tabIndex/onKeyDown, 省掉重新发明
 * 原生 checkbox 已经免费提供的行为(九-1 也正是要求"可交互元素一律用原生可聚焦标签")。
 * input 直接嵌在 `<label>` 内, 靠 HTML 原生的"嵌套关联"生效, 不需要额外的 `useId` + htmlFor/id。
 *
 * 方框边长锁定成 `PixelIcon` 的量化档位(16/32 格, 见 PixelIcon.tsx 二-2.1)而不是自选的任意格数:
 * `PixelIcon` 的 scale 只开放 16 的整数倍, 硬塞一个 5 格方框会让对勾图标要么溢出要么打不满整个格,
 * 边缘必然出现非整数倍缩放(硬红线第 4 条)。方框尺寸因此就是"打算放多大的对勾"反推出来的, 不是独立变量。
 * 未勾选时方框仍保留同样的 h-16/h-32 尺寸(只是不渲染子节点), 勾选切换不会引起盒子尺寸跳动。
 */

export interface PixelCheckboxProps {
  checked: boolean
  onChange: (next: boolean) => void
  label: string
  disabled?: boolean
  size?: PixelControlSize
  className?: string
}

const BOX_CLASS: Record<PixelControlSize, string> = {
  sm: 'h-16 w-16',
  md: 'h-16 w-16',
  lg: 'h-32 w-32',
}

const BOX_ICON_SCALE: Record<PixelControlSize, PixelIconScale> = {
  sm: 1,
  md: 1,
  lg: 2,
}

export function PixelCheckbox({
  checked,
  onChange,
  label,
  disabled = false,
  size = 'md',
  className,
}: PixelCheckboxProps): ReactElement {
  const tone = disabled ? 'neutral' : checked ? 'accent' : 'neutral'

  return (
    <label
      className={`inline-flex items-center gap-2 ${disabled ? 'cursor-not-allowed' : 'cursor-pointer'} ${
        className === undefined ? '' : className
      }`}
    >
      <input
        type="checkbox"
        className="peer sr-only"
        checked={checked}
        disabled={disabled}
        onChange={(event) => {
          onChange(event.target.checked)
        }}
      />
      {/*
        焦点环必须挂在 PixelFrame **外面**这一层, 不能挂在 PixelFrame 的 className 上。
        PixelFrame 用行内样式写死 borderStyle/borderColor/borderWidth (9-slice 的地基),
        而行内样式压得过任何工具类 —— 早先 border-2/border-transparent/peer-focus-visible:border-border-strong
        三个类全部挂在 PixelFrame 上, 结果是整组静默失效: 键盘聚焦时方框毫无变化, 复选框成了键盘不可见项。
        这里的写法与 PixelButton 一致 (环挂在承载 PixelFrame 的外层元素上), 常驻透明边框占住位置,
        聚焦时只换色, 不引起尺寸跳动。
      */}
      <span className="inline-flex border-2 border-transparent peer-focus-visible:border-border-strong">
        <PixelFrame
          variant="inset"
          tone={tone}
          className={`flex items-center justify-center ${BOX_CLASS[size]} ${
            checked && !disabled ? 'text-on-accent' : ''
          }`}
        >
          {checked ? <PixelIcon name="check" scale={BOX_ICON_SCALE[size]} /> : null}
        </PixelFrame>
      </span>
      <span className={`${PIXEL_CONTROL_TEXT_CLASS[size]} ${disabled ? 'text-muted' : 'text-fg'}`}>{label}</span>
    </label>
  )
}

export interface PixelCheckboxDemoCase {
  readonly label: string
  readonly checked: boolean
}

/** 一个未勾选、一个已勾选, 供预览页与后续面板直接复用两种视觉态而不必各自现造样例文案。 */
export const PIXEL_CHECKBOX_DEMO_CASES: readonly PixelCheckboxDemoCase[] = [
  { label: '仅显示我的挂单', checked: false },
  { label: '仅显示有货物品', checked: true },
]
