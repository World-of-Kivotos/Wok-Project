/**
 * 与宿主 (MC 客户端) 打交道的两件小事: 请求关闭平板、上报输入焦点。
 *
 * 两件事都只能由页面发起, 因为只有 DOM 知道答案:
 *  - 关闭按钮在页面右上角, 而关的是 MC 的 Screen 栈, 页面自己关不掉;
 *  - 打开键 (默认 G) 兼作关闭键, 但玩家也要把 G 打进搜索框。CEF 与 MCEF 都不暴露"当前焦点是不是可编辑
 *    节点" (javap 实测 CefRenderHandler 与 MCEFBrowser 都没有这个接口), 只有 DOM 自己清楚。
 */

import { useEffect } from 'react'

import { call } from './bridge'

/**
 * 请求宿主关闭平板。
 *
 * 失败只吞进控制台不向上抛: 这是一个"点了没反应"就已经把结果告诉玩家的动作, 再弹一个错误浮层没有信息量;
 * 而在普通浏览器里 (无宿主) 调用必然失败, 那是开发时的正常状态, 不该每次都炸一个红框。
 */
export function closePanel(): void {
  void call('client.closePanel', {}).catch((error: unknown) => {
    console.warn('[host-panel] 关闭平板失败', error)
  })
}

/** 这个元素上打字算不算"正在输入"。 */
function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (target.isContentEditable) {
    return true
  }
  const tag = target.tagName
  if (tag === 'TEXTAREA' || tag === 'SELECT') {
    return true
  }
  if (tag !== 'INPUT') {
    return false
  }
  // 按钮型 input (checkbox/radio/button/submit/range 等) 不吃字符, 按它们算"正在输入"会让关闭键在
  // 一堆开关和滑块上失效 —— 而设置页正好全是这类控件。
  const type = (target as HTMLInputElement).type
  return !['button', 'checkbox', 'color', 'file', 'image', 'radio', 'range', 'reset', 'submit'].includes(type)
}

/**
 * 全局跟踪可编辑焦点并上报宿主。挂在外壳上, 整个应用只装一份。
 *
 * 用 focusin/focusout 而不是给每个输入框挂 onFocus: 前者会冒泡, 一个监听器覆盖所有现在和将来的控件,
 * 包括各页面自己渲染的、以及 Base UI 弹层里那些。逐个挂必然漏。
 */
export function useTextFocusReporting(): void {
  useEffect(() => {
    let lastReported: boolean | null = null

    const report = (focused: boolean): void => {
      // 只在变化时发: focusin/focusout 在页面里点来点去会高频触发, 而宿主那边是个布尔量,
      // 重复发同一个值纯属浪费每玩家令牌桶。
      if (lastReported === focused) {
        return
      }
      lastReported = focused
      void call('client.textFocus', { focused }).catch((error: unknown) => {
        console.warn('[host-panel] 上报输入焦点失败', error)
      })
    }

    const onFocusIn = (event: FocusEvent): void => {
      report(isEditable(event.target))
    }
    // focusout 的 relatedTarget 是即将拿到焦点的那个元素; 从一个输入框切到另一个输入框时它非空,
    // 只看 focusout 会先误报一次"没有输入焦点"。
    const onFocusOut = (event: FocusEvent): void => {
      report(isEditable(event.relatedTarget))
    }

    document.addEventListener('focusin', onFocusIn)
    document.addEventListener('focusout', onFocusOut)
    report(isEditable(document.activeElement))

    return () => {
      document.removeEventListener('focusin', onFocusIn)
      document.removeEventListener('focusout', onFocusOut)
    }
  }, [])
}
