import type { ReactElement, ReactNode } from 'react'
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
 *
 * children + confirmDisabled 是给"确认时必须顺带交代一句"的操作用的 (如移出住户必须选原因):
 * 表单画在说明文字下方, 表单没填完就由调用方用 confirmDisabled 锁住确认键。表单状态归调用方持有 ——
 * 本组件只负责摆位置, 不知道也不该知道表单里有什么。
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
  /** 说明文字下方的附加表单区 (如移出原因)。 */
  children?: ReactNode | undefined
  /** 置真时锁住确认键 (附加表单未填完)。与 confirmWord 的锁叠加生效。 */
  confirmDisabled?: boolean | undefined
  /**
   * 确认键的样式, 默认 destructive (红色)。只给"不可随手撤回、但本身不是破坏"的操作 (买东西、把东西还回去)
   * 改成 default: 红色确认键读起来像删除, 用户会以为自己在毁掉什么。不能撤回的交代照样写在 message 里。
   */
  confirmVariant?: 'destructive' | 'default' | undefined
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
  children,
  confirmDisabled = false,
  confirmVariant = 'destructive',
}: ConfirmDangerDialogProps): ReactElement {
  const [typed, setTyped] = useState('')

  // 每次重新打开都清空输入: 留着上一次敲的字, 等于第二次确认时那道锁形同虚设。
  useEffect(() => {
    if (open) {
      setTyped('')
    }
  }, [open])

  const locked = confirmDisabled || (confirmWord !== undefined && typed !== confirmWord)

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

        {children === undefined ? null : <div className="flex flex-col gap-3 px-6 pb-4">{children}</div>}

        {confirmWord === undefined ? null : (
          <div className="flex flex-col gap-2 px-6 pb-4">
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
          <Button disabled={locked} loading={loading} onClick={onConfirm} variant={confirmVariant}>
            {confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogPopup>
    </AlertDialog>
  )
}
