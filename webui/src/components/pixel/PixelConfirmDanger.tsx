import type { ReactElement } from 'react'
import { useEffect, useId, useRef } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import { PixelLoading } from './PixelLoading'
import { PixelIcon } from './PixelIcon'
import { PixelFrame } from './PixelFrame'

/**
 * 破坏性操作二次确认。真源: conventions.md 十-L2 表("打开时默认焦点落取消")。
 *
 * `role="alertdialog"`(而不是 PixelModal 用的 "dialog"): 这是 WAI-ARIA 对"需要用户立即响应的
 * 确认框"给的专用语义, 与普通信息浮层区分开。遮罩不响应点击 —— 破坏性操作必须走显式按钮,
 * 点遮罩误关等于给"取消"开了个鼠标误触后门, 这一条与 PixelModal 刻意不同。
 *
 * `loading` 期间两个按钮用 `aria-disabled` 而不是原生 `disabled`: 原生 disabled 会让当前持有焦点的
 * "取消"按钮被浏览器自动 blur 到 document.body, 焦点直接跑出浮层, 与九-6 的焦点陷阱要求冲突。
 * `aria-disabled` 保留可聚焦性, 真正拦回调靠 onClick 内部的 loading 判断, 两件事(可聚焦 / 可触发)
 * 分开处理, 陷阱 effect 也就不必因 loading 变化而重新挂载监听器(同 PixelModal 的"最新值 ref"手法)。
 */

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export interface PixelConfirmDangerProps {
  open: boolean
  title: string
  message: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
  loading: boolean
  className?: string
}

export function PixelConfirmDanger({
  open,
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
  loading,
  className,
}: PixelConfirmDangerProps): ReactElement | null {
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const cancelButtonRef = useRef<HTMLButtonElement>(null)
  const stateRef = useRef({ loading, onCancel })

  useEffect(() => {
    stateRef.current = { loading, onCancel }
  })

  useEffect(() => {
    if (!open) {
      return
    }
    const previouslyFocused = document.activeElement
    cancelButtonRef.current?.focus()

    const handleKeyDown = (event: KeyboardEvent) => {
      const current = stateRef.current
      // 处理中不许用 Esc 抄近路取消, 与两个按钮的 aria-disabled 态是同一条规则, 避免操作与视觉互相矛盾。
      if (current.loading) {
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        current.onCancel()
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
      if (event.shiftKey && document.activeElement === first) {
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
      <div className="fixed inset-0 bg-shadow opacity-70" aria-hidden="true" />
      <div
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={className === undefined ? 'relative w-96' : `relative w-96 ${className}`}
      >
        <PixelFrame variant="window" tone="danger" className="shadow-hard-3">
          <div className="flex items-center gap-2 border-b border-border px-4 py-2">
            <PixelIcon name="warning" scale={1} className="text-danger" />
            <h2 id={titleId} className="text-1x text-fg">
              {title}
            </h2>
          </div>
          <div className="p-4">
            <p className="text-1x text-fg">{message}</p>
          </div>
          <div className="flex justify-end gap-2 border-t border-border p-4">
            <button
              ref={cancelButtonRef}
              type="button"
              aria-disabled={loading}
              onClick={() => {
                if (!loading) {
                  onCancel()
                }
              }}
              className={`${PIXEL_CONTROL_PADDING_CLASS.md} ${PIXEL_CONTROL_TEXT_CLASS.md} border border-border-strong shadow-hard active:translate-y-1 active:shadow-none focus-visible:border-accent focus-visible:outline-none ${
                loading ? 'bg-surface text-muted' : 'bg-surface text-fg hover:bg-raised'
              }`}
            >
              取消
            </button>
            <button
              type="button"
              aria-disabled={loading}
              onClick={() => {
                if (!loading) {
                  onConfirm()
                }
              }}
              className={`${PIXEL_CONTROL_PADDING_CLASS.md} ${PIXEL_CONTROL_TEXT_CLASS.md} inline-flex items-center gap-2 border border-border-strong shadow-hard active:translate-y-1 active:shadow-none focus-visible:border-fg focus-visible:outline-none ${
                loading ? 'bg-surface text-muted' : 'bg-danger text-on-accent'
              }`}
            >
              {loading ? <PixelLoading size="sm" /> : null}
              {confirmLabel}
            </button>
          </div>
        </PixelFrame>
      </div>
    </div>
  )
}

/** 供组件预览页复用; loading/open 是运行态开关, demo 只给静态文案三项。 */
export const PIXEL_CONFIRM_DANGER_DEMO: Pick<PixelConfirmDangerProps, 'title' | 'message' | 'confirmLabel'> = {
  title: '确认下架商品',
  message: '下架后买家将无法继续购买该商品, 已支付的订单不受影响。',
  confirmLabel: '确认下架',
}
