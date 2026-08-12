import type { FocusEvent, KeyboardEvent, ReactElement } from 'react'
import { useEffect, useId, useRef, useState } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelControlSize } from './controlSize'
import { PixelFrame } from './PixelFrame'
import { PixelIcon } from './PixelIcon'

/**
 * 自绘下拉选择。真源: conventions.md 十 · L1 · PixelSelect(冻结 props: value/options/onChange/
 * disabled/size, "原生 select 的弹层是系统渲染, 不许用")。
 *
 * 触发器固定 `PixelFrame variant="inset"`(输入井), 展开的选项浮层固定 `variant="window"`
 * (二-2.4 的"外凸窗口/浮层", 与 PixelTooltip/PixelModal 同一档而非新开)。
 *
 * 键盘走真实 DOM 焦点在选项间移动的 roving 模式(而不是虚拟的 aria-activedescendant): 九-6 要求
 * "浮层打开时焦点移入浮层", 每个 `<li>` 都是可编程聚焦的真实焦点目标(`tabIndex=-1` + 方向键调用
 * `.focus()`), 浏览器原生 `:focus`/`:focus-visible` 因此天然给出高亮, 不需要再用一份 React state
 * 去影子同一件事(四-4.2 同一条理由)。打开动作与 PixelModal/PixelConfirmDanger 同一手法: 用
 * `useEffect` 挂在 `[open]` 上, 渲染提交后再把焦点送进浮层, 不需要 requestAnimationFrame。
 * Tab 键不做焦点陷阱: `<ul>` 上的 `onBlur` 检测 `relatedTarget` 是否仍落在容器内, 一旦焦点离开
 * 整个组件复合体就直接收起浮层, 比强行拦住 Tab 更贴近下拉菜单而非模态对话框的交互预期。
 *
 * 找不到 `value` 匹配项时触发器直接显示原始 `value` 而不是拼一句"未选择": 四-4.3 禁止 `?? 0` /
 * `|| '未知'` 这类兜底, 缺值就该让人看得出缺, 不能靠占位文案盖过去。
 */

export interface PixelSelectOption {
  readonly value: string
  readonly label: string
}

export interface PixelSelectProps {
  value: string
  options: readonly PixelSelectOption[]
  onChange: (next: string) => void
  disabled?: boolean
  size?: PixelControlSize
  className?: string
}

export function PixelSelect({
  value,
  options,
  onChange,
  disabled = false,
  size = 'md',
  className,
}: PixelSelectProps): ReactElement {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const optionRefs = useRef<(HTMLLIElement | null)[]>([])
  const pendingFocusIndexRef = useRef(0)
  const listboxId = useId()

  const selected = options.find((option) => option.value === value)
  const triggerText = selected === undefined ? value : selected.label
  const currentIndex = options.findIndex((option) => option.value === value)

  const focusOptionAt = (index: number): void => {
    optionRefs.current[index]?.focus()
  }

  const close = (returnFocus: boolean): void => {
    setOpen(false)
    if (returnFocus) {
      triggerRef.current?.focus()
    }
  }

  const openAt = (index: number): void => {
    pendingFocusIndexRef.current = index
    setOpen(true)
  }

  useEffect(() => {
    if (!open) {
      return
    }
    // 本次渲染已把浮层提交进真实 DOM, ref 回调也已跑过, 此刻直接送焦点即可, 不需要等下一帧。
    focusOptionAt(pendingFocusIndexRef.current)

    const handlePointerDown = (event: PointerEvent): void => {
      if (containerRef.current !== null && !containerRef.current.contains(event.target as Node)) {
        close(false)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [open])

  const selectOption = (index: number): void => {
    const option = options[index]
    // disabled 只挂在触发器上, 浮层展开着的时候外部把 disabled 翻成 true, 选项仍然点得动、键盘也选得中。
    // 受控值的写入口只有这一处, 在这里补判最省。
    if (option === undefined || disabled) {
      return
    }
    onChange(option.value)
    close(true)
  }

  const handleTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>): void => {
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && !open) {
      event.preventDefault()
      openAt(currentIndex >= 0 ? currentIndex : 0)
    } else if (event.key === 'Escape' && open) {
      // 正常路径下浮层一开焦点就已经在选项上, Escape 落到这里只覆盖 options 为空导致焦点没能移走的边界态。
      event.preventDefault()
      close(true)
    }
  }

  const moveFocus = (fromIndex: number, delta: number): void => {
    if (options.length === 0) {
      return
    }
    const next = Math.min(Math.max(fromIndex + delta, 0), options.length - 1)
    focusOptionAt(next)
  }

  const handleOptionKeyDown = (event: KeyboardEvent<HTMLLIElement>, index: number): void => {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        moveFocus(index, 1)
        break
      case 'ArrowUp':
        event.preventDefault()
        moveFocus(index, -1)
        break
      case 'Home':
        event.preventDefault()
        focusOptionAt(0)
        break
      case 'End':
        event.preventDefault()
        focusOptionAt(options.length - 1)
        break
      case 'Enter':
      case ' ':
        event.preventDefault()
        selectOption(index)
        break
      case 'Escape':
        event.preventDefault()
        close(true)
        break
      default:
        break
    }
  }

  /**
   * 挂在**根容器**上而不是 <ul> 上。
   *
   * 挂在 ul 上时有一条逃逸路径: 从首个选项 Shift+Tab 回到触发器 —— 触发器仍在容器内, 于是不关;
   * 此后焦点在触发器上再 Shift+Tab 离开整个组件, 而触发器身上没有任何离焦处理, 浮层就永远开着了
   * (焦点已经在页面别处, 用户再也关不掉它)。根容器的 onBlur 走 focusout 冒泡, 覆盖触发器与选项两处,
   * 只要 relatedTarget 落在容器之外就收起, 不存在漏网的中间态。
   */
  const handleContainerBlur = (event: FocusEvent<HTMLDivElement>): void => {
    const next = event.relatedTarget
    if (next === null || !(next instanceof Node) || containerRef.current === null || !containerRef.current.contains(next)) {
      close(false)
    }
  }

  optionRefs.current = []

  return (
    <div
      ref={containerRef}
      onBlur={handleContainerBlur}
      className={`relative inline-block ${className === undefined ? '' : className}`}
    >
      <button
        ref={triggerRef}
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        disabled={disabled}
        onClick={() => {
          if (open) {
            close(false)
          } else {
            openAt(currentIndex >= 0 ? currentIndex : 0)
          }
        }}
        onKeyDown={handleTriggerKeyDown}
        className={`block w-full border-2 border-transparent outline-none focus-visible:border-border-strong ${
          disabled ? 'text-muted' : 'text-fg'
        }`}
      >
        <PixelFrame
          variant="inset"
          className={`flex w-full items-center justify-between gap-2 ${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]}`}
        >
          <span>{triggerText}</span>
          <PixelIcon name="arrow-down" scale={1} />
        </PixelFrame>
      </button>

      {open ? (
        <PixelFrame
          variant="window"
          // position 走 style 而非 absolute 工具类: PixelFrame 自身携带 [data-pixel-tone] 的
          // position:relative 规则, 两者同为 (0,1,0) 特异度时样式表源序会赢, 只有行内样式稳赢(见 README 六)。
          className="left-0 top-full z-10 mt-1 w-full"
          style={{ position: 'absolute' }}
        >
          <ul id={listboxId} role="listbox" aria-label="选项列表">
            {options.map((option, index) => (
              <li
                key={option.value}
                ref={(node) => {
                  optionRefs.current[index] = node
                }}
                role="option"
                aria-selected={option.value === value}
                tabIndex={-1}
                onKeyDown={(event) => {
                  handleOptionKeyDown(event, index)
                }}
                onClick={() => {
                  selectOption(index)
                }}
                className={`cursor-pointer outline-none ${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]} ${
                  option.value === value ? 'text-accent' : 'text-fg'
                } hover:bg-raised focus:bg-raised`}
              >
                {option.label}
              </li>
            ))}
          </ul>
        </PixelFrame>
      ) : null}
    </div>
  )
}

/** 市场排序场景的三个典型档位, 供预览页与后续面板直接复用而不必各自现造样例选项。 */
export const PIXEL_SELECT_DEMO_OPTIONS: readonly PixelSelectOption[] = [
  { value: 'newest', label: '最新上架' },
  { value: 'price-asc', label: '价格从低到高' },
  { value: 'price-desc', label: '价格从高到低' },
]
