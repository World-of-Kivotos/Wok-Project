import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogPopup,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { TextInput } from './Controls'

/**
 * 破坏性操作的二次确认。
 *
 * 用 AlertDialog 而不是普通 Dialog: 前者不允许点遮罩或按 Esc 关闭 (Base UI 的 alert-dialog 语义),
 * 必须显式点"取消"。破坏性操作的误关闭代价与误确认同级 —— 一个手滑关掉的确认框, 用户往往会以为
 * 操作已经生效。
 *
 * confirmWord 是可选的二道锁: 给了就必须逐字敲对才解锁确认按钮。留给"改玩家余额""重置职业等级"
 * 这类改动经济数据、无法撤销的操作 —— 单纯一个确认按钮挡不住手快。
 */

export interface ConfirmDangerDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  /** 说清楚"会发生什么"与"能不能撤销"。别只写"确定吗"。 */
  message: string
  confirmLabel: string
  onConfirm: () => void
  /** 提交中: 确认按钮转圈, 两个按钮都锁住, 且对话框不可关闭。 */
  loading?: boolean | undefined
  /** 给了就要求用户逐字输入这段文本才能确认。 */
  confirmWord?: string | undefined
}

export function ConfirmDangerDialog({
  open,
  onOpenChange,
  title,
  message,
  confirmLabel,
  onConfirm,
  loading = false,
  confirmWord,
}: ConfirmDangerDialogProps): ReactElement {
  const [typed, setTyped] = useState('')

  // 每次重新打开都清空输入: 留着上一次敲的字, 等于第二次确认时那道锁形同虚设。
  useEffect(() => {
    if (open) {
      setTyped('')
    }
  }, [open])

  const locked = confirmWord !== undefined && typed !== confirmWord

  return (
    <AlertDialog
      onOpenChange={(next) => {
        // 提交中不许关: 关掉之后请求仍在飞, 用户会以为自己取消了。
        if (!loading) {
          onOpenChange(next)
        }
      }}
      open={open}
    >
      <AlertDialogPopup>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{message}</AlertDialogDescription>
        </AlertDialogHeader>

        {confirmWord === undefined ? null : (
          <div className="flex flex-col gap-2 px-6">
            <label className="text-muted-foreground text-xs" htmlFor="confirm-word">
              输入 <span className="font-mono text-foreground">{confirmWord}</span> 以确认
            </label>
            <TextInput
              disabled={loading}
              onChange={setTyped}
              placeholder={confirmWord}
              size="sm"
              value={typed}
            />
          </div>
        )}

        <AlertDialogFooter>
          <AlertDialogClose render={<Button disabled={loading} variant="outline" />}>
            取消
          </AlertDialogClose>
          <Button disabled={locked} loading={loading} onClick={onConfirm} variant="destructive">
            {confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogPopup>
    </AlertDialog>
  )
}
