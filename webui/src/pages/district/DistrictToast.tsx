import type { ReactElement } from 'react'
import { useCallback, useRef, useState } from 'react'
import { FeedbackAlert, type FeedbackTone } from '@/components/kit'
import type { DistrictToast } from './format'

/**
 * 自管区页各分区自己的回执条。每个有写操作的分区各持一个, 回执就出现在点按钮的那块旁边 ——
 * 住户表很长, 回执若统一画在页顶, 操作者在表格底部点完"移出"是看不见结果的。
 */
export function useDistrictToast(): {
  toast: DistrictToast | null
  show: (tone: FeedbackTone, message: string, title?: string) => void
  clear: () => void
} {
  const [toast, setToast] = useState<DistrictToast | null>(null)
  const seqRef = useRef(0)
  const show = useCallback((tone: FeedbackTone, message: string, title?: string) => {
    seqRef.current += 1
    setToast({ seq: seqRef.current, tone, message, title })
  }, [])
  const clear = useCallback(() => {
    setToast(null)
  }, [])
  return { toast, show, clear }
}

export function DistrictToastSlot({
  toast,
  onClear,
}: {
  toast: DistrictToast | null
  onClear: () => void
}): ReactElement | null {
  if (toast === null) {
    return null
  }
  return (
    <FeedbackAlert
      // 待生效这类"成功了但有保留"的回执要读两句话, 4 秒不够; 给 8 秒, 仍可手动关。
      autoDismissMs={toast.tone === 'warning' ? 8000 : undefined}
      key={toast.seq}
      message={toast.message}
      onDismiss={onClear}
      title={toast.title}
      tone={toast.tone}
    />
  )
}
