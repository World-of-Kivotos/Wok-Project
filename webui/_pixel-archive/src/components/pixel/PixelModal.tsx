import type { ReactElement, ReactNode } from 'react'
import { useEffect, useId, useRef } from 'react'
import type { PixelControlSize } from './controlSize'
import { PixelIcon } from './PixelIcon'
import { PixelFrame } from './PixelFrame'

/**
 * 模态浮层。真源: conventions.md 十-L2 表、九-3/6/9(Esc 关闭 / 焦点陷阱 / 禁 title 属性)。
 *
 * 结构上 `PixelFrame` 只承担边框(`variant="window"`, 唯一带"浮起"高光关系的层级, 适合悬浮内容),
 * 键盘语义(role/aria/tabIndex/Esc/Tab 陷阱)全部挂在外面再包一层 div ——
 * `PixelFrame` 按第一节约定不透传任意 HTML 属性, 这些非视觉属性没有位置可挂。
 *
 * 焦点陷阱用"最新回调 ref"而不是把 onClose 放进 effect 依赖数组: 若依赖它, 调用方每次渲染传入的
 * 内联箭头函数标识都会变, 导致 keydown 监听器整个重新挂载并重新执行"聚焦容器"这一步 ——
 * 表现为浮层开着的时候焦点无故跳回容器本身, 用户正在浮层内部 Tab 到的位置会被打断。
 * 只让 `open` 进依赖数组, 就把"何时挂载监听器"与"回调是否变化"彻底解耦。
 */

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

const WIDTH_CLASS: Record<PixelControlSize, string> = {
  sm: 'w-64',
  md: 'w-96',
  lg: 'w-128',
}

export interface PixelModalProps {
  open: boolean
  title: string
  onClose: () => void
  children?: ReactNode
  size?: PixelControlSize
  className?: string
}

export function PixelModal({
  open,
  title,
  onClose,
  children,
  size = 'md',
  className,
}: PixelModalProps): ReactElement | null {
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)

  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    if (!open) {
      return
    }
    const previouslyFocused = document.activeElement
    dialogRef.current?.focus()

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || dialogRef.current === null) {
        return
      }
      const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (first === undefined || last === undefined) {
        return
      }
      /*
       * 容器自身 (tabIndex={-1}) 必须与 first 一起算作"往回走的边界"。
       * 打开浮层时焦点先落在容器上, 而容器不在 FOCUSABLE_SELECTOR 的查询结果里 ——
       * 早先只比对 first/last, 于是"刚打开就按 Shift+Tab"两个分支都不成立, 陷阱整个不生效,
       * 浏览器把焦点交给 DOM 里排在浮层之前的背景内容, 焦点直接逃出模态。
       */
      const atBackwardEdge = document.activeElement === first || document.activeElement === dialogRef.current
      if (event.shiftKey && atBackwardEdge) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      if (previouslyFocused instanceof HTMLElement) {
        previouslyFocused.focus()
      }
    }
  }, [open])

  if (!open) {
    return null
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* 遮罩点击关闭是鼠标态的额外便利(键盘态已有 Esc 与关闭按钮), 用纯色块 + opacity 压暗, 不碰 blur。 */}
      <div className="fixed inset-0 bg-shadow opacity-70" aria-hidden="true" onClick={onClose} />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={
          className === undefined
            ? `relative ${WIDTH_CLASS[size]}`
            : `relative ${WIDTH_CLASS[size]} ${className}`
        }
      >
        <PixelFrame variant="window" className="shadow-hard-3">
          <div className="flex items-center justify-between border-b border-border px-4 py-2">
            <h2 id={titleId} className="text-1x text-fg">
              {title}
            </h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="关闭"
              className="text-muted hover:text-fg focus-visible:text-accent focus-visible:outline-none"
            >
              <PixelIcon name="close" scale={1} />
            </button>
          </div>
          <div className="p-4">{children}</div>
        </PixelFrame>
      </div>
    </div>
  )
}

/** 供组件预览页复用; children 是具体面板内容, demo 只给标题与档位这两个数据态。 */
export const PIXEL_MODAL_DEMO: Pick<PixelModalProps, 'title' | 'size'> = {
  title: '出售物品',
  size: 'md',
}
