/**
 * 滚轮的步进归一化 + 平滑滚动。
 *
 * 症状: 在游戏里滚一格直接飞出去一大截。成因是链路上叠了三层放大, 而没有任何一层知道另外两层的存在:
 *   1. 原版 {@code MouseHandler.onScroll} 把 GLFW 的 yOffset 乘上 mouseWheelSensitivity 交给 Screen;
 *   2. 本 mod 的 {@code WebUiScreen.mouseScrolled} 再乘 40 (注释写的是"放大到像素级");
 *   3. MCEF 的 {@code MCEFBrowser.sendMouseWheel} 在非 macOS 上还要 {@code Math.ceil} 后<b>再乘 3</b>
 *      (javap 反汇编确认: {@code dload_3; ldc2_w 3.0d; dmul}), 然后才交给 CEF 换算成 Blink 的 wheel 事件。
 *
 * 三层里没有一层能单独定出"一格该滚多远"—— 而且第 1 层还随玩家的鼠标型号变 (高分辨率滚轮/自由滚轮一次
 * 甩出的 yOffset 可以是几十)。所以这里不去猜那个倍率, 而是<b>把量级钳掉</b>: 一次滚轮事件最多推进
 * {@link STEP_CAP_PX} 个 CSS 像素, 小于该值的按原样透传 (自由滚轮的连续小步长因此仍然连续)。
 * 于是无论上游怎么放大, 手感都由这一个常量决定。
 *
 * 顺带把滚动做成平滑的: 原生滚动是一步跳到位, 而这里每次 rAF 逼近一点。平滑滚动在 MCEF 里不是免费的
 * (每帧都要重绘 + 上传贴图), 但它是<b>有限</b>动画 —— 一次滚动约 250ms 后彻底静止, 与无限循环动画不是
 * 一个量级的代价 (见 styles/index.css 顶部的动效纪律)。
 */

/**
 * 单次滚轮事件的位移上限 (CSS px)。72 约等于四行正文, 比 Chromium 默认的一格 (约 100-120px) 明显克制 ——
 * 平板的内容区在默认 70% 覆盖 + 125% 缩放下只有 500 多 CSS px 高, 一格 120px 就是五分之一屏。
 */
const STEP_CAP_PX = 72

/** 每帧向目标逼近的比例。0.24 在 60fps 下约 250ms 收敛, 与 --duration-page 同档, 手感统一。 */
const APPROACH_PER_FRAME = 0.24

/** 逼近到 0.5px 以内即收尾并对齐整数, 免得留一个永不收敛的亚像素尾巴一直在重绘。 */
const SETTLE_EPSILON_PX = 0.5

interface Animation {
  target: number
  frame: number
}

const animations = new WeakMap<Element, Animation>()

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 该元素在纵向上是不是一个真的滚动容器 (可滚且样式上允许滚)。 */
function scrollableVertically(element: Element): boolean {
  if (!(element instanceof HTMLElement)) {
    return false
  }
  if (element.scrollHeight - element.clientHeight <= 1) {
    return false
  }
  const overflowY = window.getComputedStyle(element).overflowY
  return overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay'
}

/**
 * 从事件目标往上找第一个"能往这个方向继续滚"的容器。
 *
 * 判"能往这个方向滚"而不是"是滚动容器": 已经滚到底的内层容器不该把滚轮吃掉 —— 那正是原生滚动链
 * (scroll chaining) 的行为, 接管之后必须自己实现, 否则表现是"列表到底之后整页就卡住不动了"。
 */
function findScrollTarget(start: EventTarget | null, direction: number): HTMLElement | null {
  let node: Element | null = start instanceof Element ? start : null
  while (node !== null) {
    if (scrollableVertically(node) && node instanceof HTMLElement) {
      const max = node.scrollHeight - node.clientHeight
      const current = animations.get(node)?.target ?? node.scrollTop
      if ((direction < 0 && current > 0.5) || (direction > 0 && current < max - 0.5)) {
        return node
      }
    }
    node = node.parentElement
  }
  return null
}

function step(element: HTMLElement): void {
  const animation = animations.get(element)
  if (animation === undefined) {
    return
  }
  const delta = animation.target - element.scrollTop
  if (Math.abs(delta) <= SETTLE_EPSILON_PX) {
    element.scrollTop = animation.target
    animations.delete(element)
    return
  }
  element.scrollTop += delta * APPROACH_PER_FRAME
  animation.frame = window.requestAnimationFrame(() => {
    step(element)
  })
}

function scrollBy(element: HTMLElement, amount: number): void {
  const max = element.scrollHeight - element.clientHeight
  const existing = animations.get(element)
  // 从<b>目标</b>而不是当前位置累加: 连甩三格时后两格若都从"当前实际位置"起算, 三格加起来会少于三格的距离。
  const base = existing?.target ?? element.scrollTop
  const target = Math.min(max, Math.max(0, base + amount))

  if (prefersReducedMotion()) {
    if (existing !== undefined) {
      window.cancelAnimationFrame(existing.frame)
      animations.delete(element)
    }
    element.scrollTop = target
    return
  }

  if (existing !== undefined) {
    existing.target = target
    return
  }
  const animation: Animation = { target, frame: 0 }
  animations.set(element, animation)
  animation.frame = window.requestAnimationFrame(() => {
    step(element)
  })
}

function onWheel(event: WheelEvent): void {
  /*
   * 已经有人处理过就不抢。本监听器挂在<b>冒泡</b>阶段正是为了这一条: 页面里确实有控件把滚轮当自己的输入
   * (Base UI 的 NumberField 聚焦后滚轮加减数值), 它们在自己的 handler 里 preventDefault; 若改挂捕获阶段,
   * 本模块会先把事件吃掉并去滚最近的滚动容器 —— 表现是"在数字输入框上滚轮不改数值, 反而整页滚了"。
   */
  if (event.defaultPrevented) {
    return
  }
  // 横向为主的滚动 (shift+滚轮、可横滚的表格) 交回原生: 本模块只归一化纵向, 接管一半会让另一半失灵。
  if (Math.abs(event.deltaX) > Math.abs(event.deltaY) || event.deltaY === 0) {
    return
  }
  // ctrl+滚轮是缩放手势, 不是滚动。
  if (event.ctrlKey) {
    return
  }
  const direction = event.deltaY > 0 ? 1 : -1
  const element = findScrollTarget(event.target, direction)
  if (element === null) {
    return
  }
  // 到这里才 preventDefault: 上面每一条提前 return 的分支都必须把事件原样留给原生处理。
  event.preventDefault()
  scrollBy(element, direction * Math.min(Math.abs(event.deltaY), STEP_CAP_PX))
}

/**
 * 装上全局滚轮接管, 返回卸载函数。
 *
 * 必须 {@code passive: false} —— 被动监听器里 preventDefault 是空操作 (且 Chromium 会在控制台报警),
 * 而 preventDefault 恰恰是本模块能定住步长的唯一手段。
 */
export function installWheelNormalizer(): () => void {
  window.addEventListener('wheel', onWheel, { passive: false })
  return () => {
    window.removeEventListener('wheel', onWheel)
  }
}
