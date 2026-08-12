import type { KeyboardEvent, ReactElement } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelControlSize } from './controlSize'
import type { PixelFrameTone } from './PixelFrame'
import { PixelFrame } from './PixelFrame'

/**
 * 单行文本输入。真源: conventions.md 十 · L1 · PixelInput(冻结 props: value/onChange/placeholder/
 * disabled/invalid/size/maxLength)、九-10(中文输入下限)、架构文档第七章(中文输入方案)。
 *
 * `onRequestEdit` 是冻结表之外新增的接口位(十一节允许"表里没列到的 props 自行加"): 架构文档第七章
 * 定的中文输入路线是 MC 原生 `EditBox` 浮层接收键盘、提交后经桥回填 `value`, 而非在浏览器里直接打字
 * (MCEF 单 char API 没有 CEF IME 桥)。宿主叠加层当前未实现(接线清单 A14 BLOCKED), 本组件因此只能
 * 交付"喊话"的一半: 传了 `onRequestEdit` 时控件转只读展示, 点击/Enter/Space 把当前值报给宿主, 真正的
 * 回填由宿主日后调 `onChange` 完成; 不传时保持原生可键入, 直接满足九-10"数字/英文输入必须不依赖宿主
 * 能力可用"——市场数量/价格这类纯数字场景不必等 A14 解决。两种模式二选一, 不是两者都要支持的开关。
 *
 * 容器固定 `PixelFrame variant="inset"`(二-2.4 的"内凹凹槽", 输入井正是这个用途)。焦点态不改动
 * PixelFrame 的 tone(9-slice 没有逐态子色, 见 PixelButton 头注释同一条理由), 改用真实 `:focus-visible`
 * 伪类在 `<input>` 自身上换一圈常驻透明边框的颜色(七-2/九-5), 不需要额外的 hover/focus 状态。
 */

export interface PixelInputProps {
  value: string
  onChange: (next: string) => void
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
  size?: PixelControlSize
  maxLength?: number
  onRequestEdit?: (current: string) => void
  className?: string
}

export function PixelInput({
  value,
  onChange,
  placeholder,
  disabled = false,
  invalid = false,
  size = 'md',
  maxLength,
  onRequestEdit,
  className,
}: PixelInputProps): ReactElement {
  const editViaHost = onRequestEdit !== undefined

  const handleActivateEdit = (): void => {
    if (disabled || onRequestEdit === undefined) {
      return
    }
    onRequestEdit(value)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (!editViaHost) {
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      // 阻止空格触发浏览器默认的"滚动一页"(九-2), readOnly 输入框本身不消费空格字符。
      event.preventDefault()
      handleActivateEdit()
    }
  }

  const tone: PixelFrameTone = disabled ? 'neutral' : invalid ? 'danger' : 'neutral'

  return (
    <PixelFrame
      variant="inset"
      tone={tone}
      className={className === undefined ? 'block' : `block ${className}`}
    >
      <input
        type="text"
        value={value}
        readOnly={editViaHost}
        disabled={disabled}
        placeholder={placeholder}
        {...(maxLength === undefined ? {} : { maxLength })}
        aria-invalid={invalid}
        onChange={editViaHost ? undefined : (event) => { onChange(event.target.value) }}
        onClick={editViaHost ? handleActivateEdit : undefined}
        onKeyDown={editViaHost ? handleKeyDown : undefined}
        className={`w-full appearance-none border-2 border-transparent bg-transparent outline-none focus-visible:border-border-strong ${
          PIXEL_CONTROL_PADDING_CLASS[size]
        } ${PIXEL_CONTROL_TEXT_CLASS[size]} ${disabled ? 'text-muted' : 'text-fg'} ${
          editViaHost ? 'cursor-pointer' : ''
        } placeholder:text-muted`}
      />
    </PixelFrame>
  )
}

export interface PixelInputDemoCase {
  readonly value: string
  readonly placeholder: string
}

/** 一个空值搜索框、一个已有数字取值的字段, 供预览页与后续面板直接复用而不必各自现造样例文案。 */
export const PIXEL_INPUT_DEMO_CASES: readonly PixelInputDemoCase[] = [
  { value: '', placeholder: '搜索物品...' },
  { value: '128', placeholder: '数量' },
]
