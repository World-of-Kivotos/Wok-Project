import type { PointerEvent as ReactPointerEvent, ReactElement } from 'react'
import { useCallback, useEffect, useRef } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelControlSize } from './controlSize'
import { PixelFrame } from './PixelFrame'
import type { PixelIconName, PixelIconScale } from './PixelIcon'
import { PixelIcon } from './PixelIcon'

/**
 * 数量/价格步进器。真源: conventions.md 十 · L1 · PixelStepper(冻结 props: value/onChange/min/max/
 * step/disabled/size, "市场的数量/价格输入靠它绕开中文输入")。
 *
 * 正因为存在的理由就是"绕开中文输入", 数值展示格是纯只读文本, 不叠加任何可键入的输入框 ——
 * 加一个能打字的框等于把刚绕开的坑又挖回来。
 *
 * 长按连增 (按下 400ms 后转为每 100ms 一次自增) 不复用 `PixelButton`: 它需要
 * `onPointerDown/Up/Leave/Cancel` 四个钩子, 而这四个不在 PixelButton 的冻结 props 表里,
 * conventions.md 一-1.2 又明令禁止组件透传任意 HTML 属性 —— 为这一个特例给 PixelButton 开口子
 * 会让它的公开面为少数派用途膨胀。这里改用同目录私有的 `StepButton`, 视觉语言(同一份
 * shadow-hard + active:translate-y-1 按压手感 + PixelFrame variant="panel")与 PixelButton 保持一致,
 * 只是各自持有一份极小的按钮外壳, 不进 barrel。
 *
 * setInterval 里的回调是长期存活的闭包, 若直接捕获 value/min/max/onChange 会在长按期间一直读取
 * "按下那一刻"的旧值。用 ref 转存最新一份供闭包读取, 而不是在组件内部另建一份 useState 影子值——
 * 后者正是 conventions.md 四-4.2 明令禁止的"控件内藏一份副本"; ref 只是给闭包一个读"当前 props"的
 * 通道, 单一数据源仍是外部受控的 value。
 */

const STEP_REPEAT_DELAY_MS = 400
const STEP_REPEAT_INTERVAL_MS = 100

type StepDirection = 1 | -1

function clampStep(current: number, direction: StepDirection, step: number, min: number, max: number): number {
  const next = current + direction * step
  if (next < min) {
    return min
  }
  if (next > max) {
    return max
  }
  return next
}

const STEP_BUTTON_ICON_SCALE: Record<PixelControlSize, PixelIconScale> = {
  sm: 1,
  md: 1,
  lg: 2,
}

interface StepButtonProps {
  icon: PixelIconName
  label: string
  size: PixelControlSize
  disabled: boolean
  onFire: () => void
}

function StepButton({ icon, label, size, disabled, onFire }: StepButtonProps): ReactElement {
  const timeoutRef = useRef<number | null>(null)
  const intervalRef = useRef<number | null>(null)

  const stopRepeat = useCallback(() => {
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current)
      timeoutRef.current = null
    }
    if (intervalRef.current !== null) {
      window.clearInterval(intervalRef.current)
      intervalRef.current = null
    }
  }, [])

  const startRepeat = useCallback(
    (event: ReactPointerEvent<HTMLButtonElement>) => {
      // 只认主指针按键(左键/触控/笔尖), 右键菜单一类的按下不该触发连发。
      if (disabled || event.button !== 0) {
        return
      }
      stopRepeat()
      // 首次一格由原生 click 负责, 这里只安排"按住不放之后开始连发"的后续节奏。
      timeoutRef.current = window.setTimeout(() => {
        intervalRef.current = window.setInterval(onFire, STEP_REPEAT_INTERVAL_MS)
      }, STEP_REPEAT_DELAY_MS)
    },
    [disabled, onFire, stopRepeat],
  )

  useEffect(() => stopRepeat, [stopRepeat])

  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onFire}
      onPointerDown={startRepeat}
      onPointerUp={stopRepeat}
      onPointerLeave={stopRepeat}
      onPointerCancel={stopRepeat}
      className={`border-2 border-transparent shadow-hard outline-none focus-visible:border-border-strong active:translate-y-1 active:shadow-none disabled:shadow-none ${
        disabled ? 'text-muted' : 'text-fg'
      }`}
    >
      <PixelFrame variant="panel" className={PIXEL_CONTROL_PADDING_CLASS[size]}>
        <PixelIcon name={icon} scale={STEP_BUTTON_ICON_SCALE[size]} />
      </PixelFrame>
    </button>
  )
}

export interface PixelStepperProps {
  value: number
  onChange: (next: number) => void
  min: number
  max: number
  step?: number
  disabled?: boolean
  size?: PixelControlSize
  className?: string
}

export function PixelStepper({
  value,
  onChange,
  min,
  max,
  step = 1,
  disabled = false,
  size = 'md',
  className,
}: PixelStepperProps): ReactElement {
  const latestRef = useRef({ value, min, max, step, onChange })
  useEffect(() => {
    latestRef.current = { value, min, max, step, onChange }
  })

  const fire = useCallback((direction: StepDirection) => {
    const { value: current, min: lo, max: hi, step: s, onChange: change } = latestRef.current
    const next = clampStep(current, direction, s, lo, hi)
    if (next !== current) {
      change(next)
    }
  }, [])

  const fireDecrement = useCallback(() => {
    fire(-1)
  }, [fire])
  const fireIncrement = useCallback(() => {
    fire(1)
  }, [fire])

  const atMin = value <= min
  const atMax = value >= max

  return (
    <div className={`inline-flex items-center gap-2 ${className === undefined ? '' : className}`}>
      <StepButton icon="minus" label="减少" size={size} disabled={disabled || atMin} onFire={fireDecrement} />
      <PixelFrame
        variant="inset"
        className={`${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]} ${
          disabled ? 'text-muted' : 'text-fg'
        }`}
      >
        <span aria-live="polite">{value}</span>
      </PixelFrame>
      <StepButton icon="plus" label="增加" size={size} disabled={disabled || atMax} onFire={fireIncrement} />
    </div>
  )
}

export interface PixelStepperDemoCase {
  readonly value: number
  readonly min: number
  readonly max: number
  readonly step: number
}

/** 物品堆叠数量的典型区间(单堆上限 64), 供预览页与后续面板直接复用而不必各自现造样例取值。 */
export const PIXEL_STEPPER_DEMO_CASE: PixelStepperDemoCase = { value: 1, min: 1, max: 64, step: 1 }
