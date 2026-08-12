import type { ReactElement } from 'react'
import { useEffect, useRef } from 'react'
import type { PixelFrameTone } from './PixelFrame'
import { PixelIcon } from './PixelIcon'
import { PixelFrame } from './PixelFrame'

/**
 * 操作回执单条展示件。真源: conventions.md 十-L2 表 ——
 * "纯展示件。队列与挂载点本批不定": 本组件不管自己出现在屏幕哪里、不管同时有几条,
 * 那是要动全局布局的事, 属于路由/App.tsx 由 hub 统一维护的范围。"支持多条堆叠"落到这里的意思是
 * "多个实例并排渲染时互不干扰"(不假设自己是唯一实例, 不用 position: fixed 抢占视口),
 * 具体的挂载容器与堆叠方向由消费页面用 flex 列表包起来决定。
 *
 * 自动消失用"最新回调 ref"存 onDismiss: 若直接把 onDismiss 放进 effect 依赖数组,
 * 调用方每次渲染传入新的内联箭头函数就会重置计时器, 一条本该 4 秒消失的提示可能永远因为
 * 父组件的无关重渲染被反复续期。定时器只在挂载时起一次, 到点读 ref 里最新的回调。
 */

const AUTO_DISMISS_MS = 4000
const BASE_CLASS = 'w-80'

export interface PixelToastProps {
  tone: PixelFrameTone
  message: string
  onDismiss: () => void
  className?: string
}

export function PixelToast({ tone, message, onDismiss, className }: PixelToastProps): ReactElement {
  const onDismissRef = useRef(onDismiss)

  useEffect(() => {
    onDismissRef.current = onDismiss
  })

  useEffect(() => {
    const timer = setTimeout(() => {
      onDismissRef.current()
    }, AUTO_DISMISS_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [])

  return (
    <PixelFrame
      variant="panel"
      tone={tone}
      className={className === undefined ? BASE_CLASS : `${BASE_CLASS} ${className}`}
    >
      <div role="status" aria-live="polite" className="flex items-center gap-2 p-3">
        <p className="flex-1 text-1x text-fg">{message}</p>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="关闭提示"
          className="text-muted hover:text-fg focus-visible:text-accent focus-visible:outline-none"
        >
          <PixelIcon name="close" scale={1} />
        </button>
      </div>
    </PixelFrame>
  )
}

/** 供组件预览页复用; 三档语义色各取一例, 覆盖成功/危险/信息三类回执文案。 */
export const PIXEL_TOAST_DEMO: readonly Pick<PixelToastProps, 'tone' | 'message'>[] = [
  { tone: 'success', message: '上架成功' },
  { tone: 'danger', message: '余额不足, 交易已取消' },
  { tone: 'info', message: '价格已更新为 120 CREDIT' },
]
