import { CheckCircle2Icon, CircleAlertIcon, InfoIcon, TriangleAlertIcon, XIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useEffect, useRef } from 'react'
import { Alert, AlertAction, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import type { Tone } from './tokens'

/**
 * 操作回执条。业务页在每次写操作后都要报一句"成功/失败 + 服务端原话", 这是那句话的统一外观。
 *
 * 刻意保留服务端原文而不做任何改写: 回执文案 (余额不足 / 等级不够 / 该挂单已被买走) 是服务端
 * 权威判定的结果, 前端改写等于制造第二套业务口径。
 */

/** brand 档不参与回执: 回执必须表达"成功还是失败", 用户可调的强调色不携带这个语义。 */
export type FeedbackTone = Exclude<Tone, 'brand'>

const TONE_VARIANT: Record<FeedbackTone, 'default' | 'success' | 'warning' | 'error' | 'info'> = {
  neutral: 'default',
  success: 'success',
  warning: 'warning',
  danger: 'error',
  info: 'info',
}

const TONE_ICON: Record<FeedbackTone, ReactElement> = {
  neutral: <InfoIcon aria-hidden="true" />,
  success: <CheckCircle2Icon aria-hidden="true" />,
  warning: <TriangleAlertIcon aria-hidden="true" />,
  danger: <CircleAlertIcon aria-hidden="true" />,
  info: <InfoIcon aria-hidden="true" />,
}

/** 默认自动消失时长。太短来不及读完一条服务端回执, 太长又会一直占着版面。 */
const DEFAULT_AUTO_DISMISS_MS = 4000

export interface FeedbackAlertProps {
  tone: FeedbackTone
  /** 正文。通常直接放服务端回执原文。 */
  message: string
  /** 可选的粗体首行。只有一句话时不必给。 */
  title?: string | undefined
  /**
   * 右侧行动区 (如"查看详情"/"撤销")。直接传按钮即可, 本组件负责套 AlertAction ——
   * Coss 的 Alert 靠 `data-slot="alert-action"` 决定网格列, 裸节点会掉到第二行去。
   */
  action?: ReactNode | undefined
  /**
   * 给了就渲染关闭按钮, 并默认在 4 秒后自动调用一次。
   *
   * 不给 = 这是一条常驻横幅 (如"以下偏好只存在本机"), 由页面自己决定何时不再渲染它。
   * 给了 = 这是一条操作回执, 它必须会自己消失 —— 首页那种"每进出一次矿洞就追加一条、
   * 只有手动点关闭才走"的行为, 连点几次就会把整屏内容顶下去。
   */
  onDismiss?: (() => void) | undefined
  /** 覆盖自动消失时长; 传 0 表示不自动消失, 只留手动关闭。仅在给了 onDismiss 时有意义。 */
  autoDismissMs?: number | undefined
  className?: string | undefined
}

export function FeedbackAlert({
  tone,
  message,
  title,
  action,
  onDismiss,
  autoDismissMs = DEFAULT_AUTO_DISMISS_MS,
  className,
}: FeedbackAlertProps): ReactElement {
  /*
   * 自动消失用"最新回调 ref"存 onDismiss, 而不是把它放进 effect 依赖数组。
   *
   * 调用方几乎一定传的是内联箭头函数 (onDismiss={() => setToast(null)}), 放进依赖数组的话,
   * 父组件每次无关重渲染都会重建这个函数、重置计时器 —— 一条本该 4 秒消失的回执可能永远续期。
   * 定时器只在挂载时起一次, 到点读 ref 里最新的那个回调。
   */
  const onDismissRef = useRef(onDismiss)
  useEffect(() => {
    onDismissRef.current = onDismiss
  })

  useEffect(() => {
    if (onDismiss === undefined || autoDismissMs <= 0) {
      return
    }
    const timer = window.setTimeout(() => {
      onDismissRef.current?.()
    }, autoDismissMs)
    return () => {
      window.clearTimeout(timer)
    }
    /*
     * message 进依赖数组是刻意的: 同一处回执被新内容顶替时 (元素没有卸载, 只是文案变了),
     * 倒计时必须重新起 —— 否则新回执会继承上一条已经走了一半的计时, 刚出现就消失。
     * onDismiss 的身份变化由上面的 ref 承接, 故这里只看"有没有给"而不看它本身。
     */
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onDismiss === undefined, autoDismissMs, message])

  return (
    <Alert className={className} variant={TONE_VARIANT[tone]}>
      {TONE_ICON[tone]}
      {title === undefined ? null : <AlertTitle>{title}</AlertTitle>}
      <AlertDescription>{message}</AlertDescription>
      {action === undefined && onDismiss === undefined ? null : (
        <AlertAction>
          <div className="flex items-center gap-1">
            {action}
            {onDismiss === undefined ? null : (
              <Button aria-label="关闭提示" onClick={onDismiss} size="icon-xs" variant="ghost">
                <XIcon />
              </Button>
            )}
          </div>
        </AlertAction>
      )}
    </Alert>
  )
}
