import { CheckCircle2Icon, CircleAlertIcon, InfoIcon, TriangleAlertIcon, XIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
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

/*
 * 进退动效的四段类名。拆成常量而不是拼字符串, 是因为 Tailwind 靠扫描源码里的**完整字面量**决定
 * 生成哪些工具类 —— 任何拼接出来的类名都不会被生成, 表现为样式凭空不存在且不报错。
 *
 * 三条必须钉死的选择, 换写法前先读:
 *
 * 1. 过渡的属性是 translate 而不是 transform。Tailwind v4 的 translate-y-* 生成的是独立的
 *    translate 属性 (`translate: var(--tw-translate-x) var(--tw-translate-y)`), 不是 transform
 *    的一部分; 写成 transition-[opacity,transform] 会让位移完全不过渡, 而且没有任何报错。
 *    同目录上游组件 sheet.tsx 写的也是 transition-[opacity,translate], 同一个理由。
 *
 * 2. 只动 translate 与 opacity。两者都不触发布局重排 —— 这个界面与 Minecraft 的渲染循环共享
 *    GPU/CPU, 回执条改一次 top/height 就可能带着整个游戏画面掉帧。
 *
 * 3. 进与退走同一条边 (下方)。回执条在 BrowsePage 固定在右下角, 从底边进就该从底边出;
 *    从哪来回哪去, 用户才能把"它走了"和"它来过的位置"对上。
 */
const ENTER_TRANSITION = 'transition-[opacity,translate] duration-(--duration-enter) ease-out-soft'
const EXIT_TRANSITION = 'transition-[opacity,translate] duration-(--duration-exit) ease-in-quick'

/*
 * 未就位态 (入场前 / 退场后)。8px 是"能看出它是滑进来的"与"不至于晃眼"之间的量。
 *
 * motion-reduce 档把位移收到 2px 而不是 0: 令牌层只管得到时长, 幅度得消费方自己收。
 * 收到 0 会让入场退化成纯淡入, 而"从下方来"这条空间线索恰恰是这条回执唯一的方位信息,
 * 留 2px 既不构成前庭刺激, 又保住了方向感。
 */
const OFFSCREEN = 'translate-y-2 opacity-0 motion-reduce:translate-y-0.5'
const ONSCREEN = 'translate-y-0 opacity-100'

/**
 * 退场要等多久, 直接问元素自己算出来的 transition-duration, 不在 JS 里另抄一份毫秒数。
 *
 * 理由: --duration-exit 在 prefers-reduced-motion 下会被令牌层改写 (140ms -> 90ms), JS 里抄一份
 * 必然漂移, 而漂移的表现极隐蔽 —— 要么退场没播完就被父组件卸载 (末尾跳变), 要么播完了还杵着不走。
 *
 * 读不出数值时按 0 处理。这不是拿默认值掩盖异常: 读不出只可能是过渡样式压根没生效 (Chromium 116
 * 上超基线语法会被静默丢弃), 此时唯一正确的降级是立刻通知父组件关闭 —— 退回改动前的"瞬间消失",
 * 而不是让一条永远播不完退场的回执卡死在屏幕上。
 */
function readExitDurationMs(node: HTMLElement): number {
  // 计算值形如 "0.14s, 0.14s" (opacity 与 translate 各一份), parseFloat 取到第一个数就停。
  const seconds = Number.parseFloat(window.getComputedStyle(node).transitionDuration)
  return Number.isFinite(seconds) ? seconds * 1000 : 0
}

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

  /* 退场时长要从元素自己的计算样式上读, 故需要拿到真实 DOM 节点。 */
  const nodeRef = useRef<HTMLDivElement>(null)

  /*
   * 入场前状态靠"挂载后翻转一次的 state"制造, 不用 CSS 的 @starting-style ——
   * 渲染目标是 MCEF 内嵌的 Chromium 116, 那条至规则与 transition-behavior: allow-discrete 都要
   * 117 才落地; 在 116 上它们不是降级而是整段规则被丢弃且不报错, 只会表现为"动效莫名其妙没有"。
   */
  const [entered, setEntered] = useState(false)

  /*
   * 退场态。它存在的唯一理由: 调用方的 dismiss 是把这条从数组里过滤掉 —— 元素当场卸载, 没有
   * 播退场的机会。本组件因此先自己进退场态, 播完再通知父组件, 调用方一行都不用改。
   */
  const [leaving, setLeaving] = useState(false)

  useEffect(() => {
    /*
     * 必须是两层 rAF。rAF 的回调跑在当帧的样式计算/绘制之前, 只套一层的话"未就位态"与"稳态"
     * 会落进同一帧, 浏览器从头到尾只看见一个值, 过渡整个不发生 (元素直接就位, 静默无动效)。
     * 两层保证未就位态真的被绘制过一帧, 翻转才有起点。
     */
    let innerHandle = 0
    const outerHandle = window.requestAnimationFrame(() => {
      innerHandle = window.requestAnimationFrame(() => {
        setEntered(true)
      })
    })
    return () => {
      window.cancelAnimationFrame(outerHandle)
      window.cancelAnimationFrame(innerHandle)
    }
  }, [])

  /*
   * "要关掉了"的唯一入口 —— 手动点关闭与自动消失都走这里, 两条路径的退场表现因此不可能不一致。
   * 依赖数组为空是成立的: 函数体只碰 ref 与 setState, 两者身份恒定, 不存在闭包读到旧值的问题。
   */
  const startExit = useCallback(() => {
    // 没给 onDismiss = 这是常驻横幅, 它的去留由页面自己决定, 本组件不该有退场路径。
    if (onDismissRef.current === undefined) {
      return
    }
    setLeaving(true)
  }, [])

  useEffect(() => {
    if (onDismiss === undefined || autoDismissMs <= 0) {
      return
    }
    const timer = window.setTimeout(() => {
      startExit()
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
  }, [onDismiss === undefined, autoDismissMs, message, startExit])

  useEffect(() => {
    if (!leaving) {
      return
    }
    const node = nodeRef.current
    const timer = window.setTimeout(
      () => {
        onDismissRef.current?.()
      },
      node === null ? 0 : readExitDurationMs(node),
    )
    /*
     * 这个清理同时兜住两种半路中断:
     *   1. 退场播到一半整页被卸载 (用户切页面) —— 定时器必须撤掉, 否则会对已卸载的组件调回调;
     *   2. 退场播到一半这条被新内容顶替 —— 下面那个 effect 把 leaving 归位, 本次退场随之作废。
     */
    return () => {
      window.clearTimeout(timer)
    }
  }, [leaving])

  /*
   * 同一处回执被新内容顶替时 (message 变了, 元素没卸载), 退场态必须跟着倒计时一起归位。
   * 否则新回执直接继承上一条的退场态: 一挂出来就是半透明下沉的, 而且永远不会自己走。
   * 归位后元素从退场当时的实际位置平滑折返回稳态 —— 这正是必须用过渡而非关键帧的原因,
   * 关键帧遇到这种中途改向只会从头重播。
   */
  useEffect(() => {
    setLeaving(false)
  }, [message])

  /*
   * 指针事件由本组件显式两分, 不留"默认值"这条缝。
   *
   * opacity:0 的元素仍然参与命中测试 (与 visibility:hidden 不同), 故退场那 140ms 必须让出;
   * 而非退场态要显式写 auto 而不是靠默认 —— 承载它的容器 (如 BrowsePage 右下角那个 fixed 回执栈)
   * 本身已经被设成 pointer-events-none, 好让回执之间的空隙不吞点击; 那种容器下, 子元素不显式要回来
   * 就连关闭按钮都点不动。
   *
   * 两个分支互斥地各出一个值, 而不是在同一个 class 串里同时出现 none 与 auto —— 后者谁赢取决于
   * 两条工具类在产出 CSS 里的先后, 是个看不出来也测不到的隐患。
   */
  const motionClassName = leaving
    ? `${EXIT_TRANSITION} ${OFFSCREEN} pointer-events-none`
    : `${ENTER_TRANSITION} ${entered ? ONSCREEN : OFFSCREEN} pointer-events-auto`

  // 调用方的 className 放在最后: 它与动效类撞车时应当由调用方说了算。
  const alertClassName = `${motionClassName}${className === undefined ? '' : ` ${className}`}`

  return (
    <Alert className={alertClassName} ref={nodeRef} variant={TONE_VARIANT[tone]}>
      {TONE_ICON[tone]}
      {title === undefined ? null : <AlertTitle>{title}</AlertTitle>}
      <AlertDescription>{message}</AlertDescription>
      {action === undefined && onDismiss === undefined ? null : (
        <AlertAction>
          <div className="flex items-center gap-1">
            {action}
            {onDismiss === undefined ? null : (
              <Button aria-label="关闭提示" onClick={startExit} size="icon-xs" variant="ghost">
                <XIcon />
              </Button>
            )}
          </div>
        </AlertAction>
      )}
    </Alert>
  )
}
