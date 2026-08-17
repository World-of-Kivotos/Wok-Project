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
