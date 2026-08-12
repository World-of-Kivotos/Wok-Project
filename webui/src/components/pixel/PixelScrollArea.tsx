import type { CSSProperties, KeyboardEvent, PointerEvent, ReactElement, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { PixelFrame } from './PixelFrame'

/**
 * 自绘滚动条容器: 内容区 overflow:hidden + 手动接管 wheel/键盘/拖拽, 全程不出现原生滚动条。
 * 真源: conventions.md 十, PixelScrollArea 行 + 任务书"原生滚动条是圆角矢量, 必须换掉"。
 *
 * 选"hidden + 手动 scrollTop/scrollLeft"而不是"auto + 用 ::-webkit-scrollbar 隐藏原生外观":
 * 后者需要一条新的全局 CSS 伪元素规则才能在 Chromium 上真正抹掉滚动条, 而本仓库唯一的全局样式表
 * (index.css) 已被锁定不可改, 另开一个 CSS 文件只为一条伪元素规则并不划算; 前者只用标准 DOM API
 * (scrollTop/scrollLeft 对 overflow:hidden 元素依旧可写、依旧会裁切可见区域), 零 CSS、零伪元素。
 *
 * 代价: 放弃了原生触控惯性, 换成本组件自己实现的 wheel 步进 + 方向键/翻页键/Home/End + 拖拽拇指
 * 三条输入通道。wheel 的 preventDefault 必须挂原生 (非 passive) 监听器——
 * React 17+ 的合成 wheel 事件默认走 passive, 调用 preventDefault 会被浏览器忽略并在控制台告警。
 */

export type PixelScrollAreaOrientation = 'vertical' | 'horizontal' | 'both'

export interface PixelScrollAreaProps {
  children: ReactNode
  className?: string
  orientation?: PixelScrollAreaOrientation
  /** 可滚动区域的无障碍名; 长列表 (订单簿/背包) 建议给出, 否则读屏只报得出"可滚动区域"这一层。 */
  label?: string
}

interface ScrollMetrics {
  top: number
  left: number
  scrollHeight: number
  scrollWidth: number
  clientHeight: number
  clientWidth: number
}

const EMPTY_METRICS: ScrollMetrics = {
  top: 0,
  left: 0,
  scrollHeight: 0,
  scrollWidth: 0,
  clientHeight: 0,
  clientWidth: 0,
}

/** 拇指最小长度 (CSS px)。低于这个尺寸的拇指在 MCEF 内嵌浏览器里 (鼠标精度受 DPI/GUI Scale 叠加影响) 点不中。 */
const MIN_THUMB_PX = 24

/** 方向键/翻页键单次滚动的近似步长 (CSS px)。纯运行期交互量, 不是设计尺寸, 不必落在 --px 网格上。 */
const ARROW_STEP_PX = 40

/**
 * 这些角色的元素自己就要用方向键/Home/End, 滚动区必须让位 (见 handleKeyDown 的判定)。
 * 列的是"角色"而不是具体组件, 于是新增控件只要按 ARIA 正确标注就自动被覆盖, 不必回来改这张表。
 */
const KEY_CONSUMING_SELECTOR =
  'input, textarea, select, [contenteditable="true"], [role="combobox"], [role="listbox"], [role="tablist"], [role="tab"], [role="grid"], [role="spinbutton"], [role="slider"], [role="menu"], [role="menubar"]'

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

interface ThumbGeometry {
  lengthPx: number
  offsetPx: number
}

/**
 * 拇指长度/偏移的纯函数, 垂直/水平两条轴共用同一份数学: 调用方只需决定"用 scrollHeight 还是
 * scrollWidth 这组取值来源", 不必把公式重复写两遍。
 */
function computeThumb(
  scrollPos: number,
  scrollSize: number,
  clientSize: number,
  trackLengthPx: number,
): ThumbGeometry {
  if (scrollSize <= clientSize || trackLengthPx <= 0) {
    return { lengthPx: trackLengthPx, offsetPx: 0 }
  }
  const rawLength = (clientSize / scrollSize) * trackLengthPx
  const lengthPx = clamp(rawLength, Math.min(MIN_THUMB_PX, trackLengthPx), trackLengthPx)
  const maxScroll = scrollSize - clientSize
  const maxOffsetPx = trackLengthPx - lengthPx
  const offsetPx = maxScroll <= 0 ? 0 : (scrollPos / maxScroll) * maxOffsetPx
  return { lengthPx, offsetPx }
}

interface DragState {
  axis: 'vertical' | 'horizontal'
  startClientPos: number
  startScrollPos: number
  maxScroll: number
  maxOffsetPx: number
}

export function PixelScrollArea({
  children,
  className,
  orientation = 'vertical',
  label,
}: PixelScrollAreaProps): ReactElement {
  const viewportRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<DragState | null>(null)
  const [metrics, setMetrics] = useState<ScrollMetrics>(EMPTY_METRICS)

  const allowVertical = orientation === 'vertical' || orientation === 'both'
  const allowHorizontal = orientation === 'horizontal' || orientation === 'both'

  useEffect(() => {
    const viewport = viewportRef.current
    const content = contentRef.current
    if (viewport === null || content === null) {
      return undefined
    }

    // 读数/wheel 两个回调都会被当作事件监听器晚于本次 effect 执行调用, 逃出了当前作用域的控制流——
    // TS 不会把上面这次 null 检查的窄化带进它们, 各自重新从 ref 读一次、重新判空, 而不是复用外层的 viewport。
    function readMetrics(): void {
      const el = viewportRef.current
      if (el === null) {
        return
      }
      setMetrics({
        top: el.scrollTop,
        left: el.scrollLeft,
        scrollHeight: el.scrollHeight,
        scrollWidth: el.scrollWidth,
        clientHeight: el.clientHeight,
        clientWidth: el.clientWidth,
      })
    }

    readMetrics()

    // content 尺寸变化 (增删行/列) 不改变 viewport 自身的 box, ResizeObserver 挂在 viewport 上收不到通知;
    // 必须单独观察 content, 这是 overflow:hidden + 手动 scrollTop 方案下感知"内容变了多少"的唯一来源。
    const resizeObserver = new ResizeObserver(readMetrics)
    resizeObserver.observe(viewport)
    resizeObserver.observe(content)

    function handleWheel(event: WheelEvent): void {
      const el = viewportRef.current
      if (el === null) {
        return
      }
      event.preventDefault()
      if (allowVertical) {
        el.scrollTop += event.deltaY
      }
      if (allowHorizontal) {
        el.scrollLeft += allowVertical ? event.deltaX : event.deltaY
      }
    }
    viewport.addEventListener('wheel', handleWheel, { passive: false })
    viewport.addEventListener('scroll', readMetrics, { passive: true })

    return () => {
      resizeObserver.disconnect()
      viewport.removeEventListener('wheel', handleWheel)
      viewport.removeEventListener('scroll', readMetrics)
    }
  }, [allowVertical, allowHorizontal])

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const viewport = viewportRef.current
    if (viewport === null) {
      return
    }
    /*
     * 让位给自己认领这些按键的内容控件。
     *
     * children 是任意内容, 里面常有输入框、下拉、页签、槽位网格: 它们的方向键/Home/End 各有语义
     * (移光标、切选项、在网格里移焦点), 而这些事件会一路冒泡到视口, 下面每个分支又都调 preventDefault ——
     * 不让位的话, 焦点一进内容那些控件的键盘操作就被整片吞掉, 只剩容器在滚。
     *
     * 判据取"该角色是否本就消费方向键", 而不是简单的 target !== currentTarget: 后者会把普通可聚焦元素
     * (如 PixelTable 只处理 Enter/Space 的行) 也一并排除, 那时方向键谁都不接, 表格里就彻底滚不动了。
     */
    // 后代已经认领并消费掉了这个键 (PixelSlotGrid 的网格移动 / PixelTabs 的左右切换 / PixelSelect 的选项移动
    // 都会 preventDefault)。React 事件冒泡时 defaultPrevented 对祖先可见, 这一条比下面的角色表更普适:
    // 新控件只要按常规写法 preventDefault 就自动被让位, 不必回来登记。
    if (event.defaultPrevented) {
      return
    }
    const target = event.target
    if (
      target !== event.currentTarget &&
      target instanceof Element &&
      target.closest(KEY_CONSUMING_SELECTOR) !== null
    ) {
      return
    }
    switch (event.key) {
      case 'ArrowDown': {
        if (!allowVertical) {
          return
        }
        event.preventDefault()
        viewport.scrollTop += ARROW_STEP_PX
        return
      }
      case 'ArrowUp': {
        if (!allowVertical) {
          return
        }
        event.preventDefault()
        viewport.scrollTop -= ARROW_STEP_PX
        return
      }
      case 'ArrowRight': {
        if (!allowHorizontal) {
          return
        }
        event.preventDefault()
        viewport.scrollLeft += ARROW_STEP_PX
        return
      }
      case 'ArrowLeft': {
        if (!allowHorizontal) {
          return
        }
        event.preventDefault()
        viewport.scrollLeft -= ARROW_STEP_PX
        return
      }
      case 'PageDown': {
        event.preventDefault()
        viewport.scrollTop += viewport.clientHeight
        return
      }
      case 'PageUp': {
        event.preventDefault()
        viewport.scrollTop -= viewport.clientHeight
        return
      }
      case 'Home': {
        event.preventDefault()
        viewport.scrollTop = 0
        viewport.scrollLeft = 0
        return
      }
      case 'End': {
        event.preventDefault()
        viewport.scrollTop = viewport.scrollHeight
        viewport.scrollLeft = viewport.scrollWidth
        return
      }
      default:
        return
    }
  }

  function handleTrackPointerDown(event: PointerEvent<HTMLDivElement>, axis: 'vertical' | 'horizontal'): void {
    const viewport = viewportRef.current
    if (viewport === null) {
      return
    }
    const rect = event.currentTarget.getBoundingClientRect()
    const clickPos = axis === 'vertical' ? event.clientY - rect.top : event.clientX - rect.left
    const trackLengthPx = axis === 'vertical' ? rect.height : rect.width
    const scrollSize = axis === 'vertical' ? viewport.scrollHeight : viewport.scrollWidth
    const clientSize = axis === 'vertical' ? viewport.clientHeight : viewport.clientWidth
    const maxScroll = scrollSize - clientSize
    if (maxScroll <= 0 || trackLengthPx <= 0) {
      return
    }
    // 点轨道直接跳到点击处对应的比例位置 (而非"翻一页"), 与大多数系统原生滚动条的轨道点击行为一致。
    const fraction = clamp(clickPos / trackLengthPx, 0, 1)
    const nextScroll = fraction * maxScroll
    if (axis === 'vertical') {
      viewport.scrollTop = nextScroll
    } else {
      viewport.scrollLeft = nextScroll
    }
  }

  function handleThumbPointerDown(event: PointerEvent<HTMLDivElement>, axis: 'vertical' | 'horizontal'): void {
    // 拇指是轨道的子节点, 不拦住冒泡的话轨道的"跳转到点击处"会跟拖拽同时触发。
    event.stopPropagation()
    const viewport = viewportRef.current
    if (viewport === null) {
      return
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    const trackLengthPx = axis === 'vertical' ? viewport.clientHeight : viewport.clientWidth
    const scrollSize = axis === 'vertical' ? viewport.scrollHeight : viewport.scrollWidth
    const clientSize = axis === 'vertical' ? viewport.clientHeight : viewport.clientWidth
    const scrollPos = axis === 'vertical' ? viewport.scrollTop : viewport.scrollLeft
    const { lengthPx } = computeThumb(scrollPos, scrollSize, clientSize, trackLengthPx)
    dragRef.current = {
      axis,
      startClientPos: axis === 'vertical' ? event.clientY : event.clientX,
      startScrollPos: scrollPos,
      maxScroll: scrollSize - clientSize,
      maxOffsetPx: trackLengthPx - lengthPx,
    }
  }

  function handleThumbPointerMove(event: PointerEvent<HTMLDivElement>): void {
    const drag = dragRef.current
    const viewport = viewportRef.current
    if (drag === null || viewport === null) {
      return
    }
    const currentClientPos = drag.axis === 'vertical' ? event.clientY : event.clientX
    const deltaPx = currentClientPos - drag.startClientPos
    const deltaScroll = drag.maxOffsetPx <= 0 ? 0 : (deltaPx / drag.maxOffsetPx) * drag.maxScroll
    const nextScroll = clamp(drag.startScrollPos + deltaScroll, 0, drag.maxScroll)
    if (drag.axis === 'vertical') {
      viewport.scrollTop = nextScroll
    } else {
      viewport.scrollLeft = nextScroll
    }
  }

  function handleThumbPointerUp(event: PointerEvent<HTMLDivElement>): void {
    event.currentTarget.releasePointerCapture(event.pointerId)
    dragRef.current = null
  }

  // 轨道/拇指只在真的溢出时才画: 内容比视口短的场景 (含还没测量完的首帧) 不该留一条空轨道占位。
  const verticalOverflow = allowVertical && metrics.scrollHeight > metrics.clientHeight
  const horizontalOverflow = allowHorizontal && metrics.scrollWidth > metrics.clientWidth

  const gridStyle: CSSProperties = {
    gridTemplateColumns: verticalOverflow ? 'minmax(0, 1fr) auto' : 'minmax(0, 1fr)',
    gridTemplateRows: horizontalOverflow ? 'minmax(0, 1fr) auto' : 'minmax(0, 1fr)',
  }

  const verticalThumb = computeThumb(metrics.top, metrics.scrollHeight, metrics.clientHeight, metrics.clientHeight)
  const horizontalThumb = computeThumb(metrics.left, metrics.scrollWidth, metrics.clientWidth, metrics.clientWidth)

  return (
    <PixelFrame variant="inset" className={className === undefined ? 'grid' : `grid ${className}`} style={gridStyle}>
      <div
        ref={viewportRef}
        role="region"
        aria-label={label}
        tabIndex={0}
        className="col-start-1 row-start-1 min-h-0 min-w-0 overflow-hidden outline-none focus-visible:bg-raised"
        onKeyDown={handleKeyDown}
      >
        <div ref={contentRef}>{children}</div>
      </div>

      {verticalOverflow ? (
        <div
          className="relative col-start-2 row-start-1 w-3 bg-surface"
          onPointerDown={(event) => {
            handleTrackPointerDown(event, 'vertical')
          }}
        >
          <div
            className="absolute left-0 w-3 bg-border-strong hover:bg-accent-hover"
            style={{
              top: `${String(Math.round(verticalThumb.offsetPx))}px`,
              height: `${String(Math.round(verticalThumb.lengthPx))}px`,
            }}
            onPointerDown={(event) => {
              handleThumbPointerDown(event, 'vertical')
            }}
            onPointerMove={handleThumbPointerMove}
            onPointerUp={handleThumbPointerUp}
          />
        </div>
      ) : null}

      {horizontalOverflow ? (
        <div
          className="relative col-start-1 row-start-2 h-3 bg-surface"
          onPointerDown={(event) => {
            handleTrackPointerDown(event, 'horizontal')
          }}
        >
          <div
            className="absolute top-0 h-3 bg-border-strong hover:bg-accent-hover"
            style={{
              left: `${String(Math.round(horizontalThumb.offsetPx))}px`,
              width: `${String(Math.round(horizontalThumb.lengthPx))}px`,
            }}
            onPointerDown={(event) => {
              handleThumbPointerDown(event, 'horizontal')
            }}
            onPointerMove={handleThumbPointerMove}
            onPointerUp={handleThumbPointerUp}
          />
        </div>
      ) : null}

      {verticalOverflow && horizontalOverflow ? <div className="col-start-2 row-start-2 bg-surface" /> : null}
    </PixelFrame>
  )
}

/** 组件预览页复用的示例内容: 模拟一份可滚动长列表, 用于验证 wheel/键盘/拖拽三条输入通道。 */
export const PIXEL_SCROLL_AREA_DEMO_LINES: readonly string[] = Array.from(
  { length: 40 },
  (_placeholder, index) => `示例行 ${String(index + 1)}`,
)
