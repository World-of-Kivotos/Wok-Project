import { useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'

export type ContentTransitionDirection = 'backward' | 'forward'

let activeTransition: ViewTransition | null = null
let keyboardFrame: number | null = null

function clearActiveTransition(transition: ViewTransition): void {
  if (activeTransition === transition) {
    activeTransition = null
  }
}

/**
 * 用 Chromium 的页面快照衔接一次同步 React 更新。快照动画由合成线程执行, 不用 JS 计时器锁帧。
 */
export function transitionContent(
  direction: ContentTransitionDirection,
  update: () => void,
): void {
  activeTransition?.skipTransition()
  document.documentElement.dataset.contentDirection = direction

  if (typeof document.startViewTransition !== 'function') {
    update()
    return
  }

  const transition = document.startViewTransition(() => {
    flushSync(update)
  })
  activeTransition = transition
  void transition.finished.then(
    () => {
      clearActiveTransition(transition)
    },
    () => {
      clearActiveTransition(transition)
    },
  )
}

/** 数字滚动时长。与 --duration-page 同档略长 —— 它要让人看清"变了多少", 不只是"变了"。 */
const COUNT_UP_MS = 420

/** 快起慢落, 与 --ease-out-soft 同族 (那是 CSS 侧的贝塞尔, 这里是等价的解析式)。 */
function easeOutCubic(t: number): number {
  return 1 - (1 - t) ** 3
}

/**
 * 把一个数字的变化播成滚动。返回当前该显示的数 (整数)。
 *
 * 只在<b>变化时</b>播: 首次拿到的值直接显示, 不从 0 滚上去 —— 开面板时钱包从 0 爬到六位数, 那不是反馈,
 * 那是每次开面板都要看一遍的动画片。真正值得播的是"我刚卖了菜, 余额涨了多少"。
 *
 * 每帧只改一个文本节点, 脏矩形是那几个字的大小; 且 420ms 后彻底停下 —— 符合 styles/index.css 顶部
 * 那条"动效必须自己停下来"的纪律。
 */
export function useCountUp(value: number): number {
  const [displayed, setDisplayed] = useState(value)
  const fromRef = useRef(value)
  const seenRef = useRef(false)

  useEffect(() => {
    if (!seenRef.current) {
      // 首个值 (含挂载后第一次拿到真实数据) 直接落地。
      seenRef.current = true
      fromRef.current = value
      setDisplayed(value)
      return
    }
    const from = fromRef.current
    if (from === value) {
      return
    }
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      fromRef.current = value
      setDisplayed(value)
      return
    }
    let frame = 0
    const startedAt = performance.now()
    const tick = (at: number): void => {
      const progress = Math.min(1, (at - startedAt) / COUNT_UP_MS)
      const current = from + (value - from) * easeOutCubic(progress)
      setDisplayed(progress >= 1 ? value : Math.round(current))
      if (progress < 1) {
        frame = window.requestAnimationFrame(tick)
      } else {
        fromRef.current = value
      }
    }
    frame = window.requestAnimationFrame(tick)
    return () => {
      window.cancelAnimationFrame(frame)
      // 中途被打断 (又变了一次) 时, 下一轮必须从"当前画到哪儿"接着滚, 而不是从旧的起点重来。
      fromRef.current = displayed
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- displayed 只在清理时读一次, 进依赖表会让每帧重启动画
  }, [value])

  return displayed
}

/** 键盘连续切换必须即时响应; 保留状态反馈, 但不播放位移动画。 */
export function updateContentFromKeyboard(update: () => void): void {
  const root = document.documentElement
  activeTransition?.skipTransition()
  activeTransition = null
  root.dataset.motionInput = 'keyboard'
  if (keyboardFrame !== null) {
    window.cancelAnimationFrame(keyboardFrame)
  }

  flushSync(update)
  keyboardFrame = window.requestAnimationFrame(() => {
    keyboardFrame = window.requestAnimationFrame(() => {
      delete root.dataset.motionInput
      keyboardFrame = null
    })
  })
}
