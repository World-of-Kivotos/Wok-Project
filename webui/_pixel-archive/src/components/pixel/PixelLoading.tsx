import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import type { PixelControlSize } from './controlSize'
import { PIXEL_CONTROL_TEXT_CLASS } from './controlSize'

/**
 * 忙碌指示器。真源: conventions.md 十-L2 表(唯一允许实现动效的组件)、九章无障碍下限。
 *
 * 为什么不是旋转圆环: 硬红线第 3/4 条禁 stroke 矢量图标与非整数倍缩放/旋转, CSS 的 rotate 动画
 * 必然在中间帧产生非整数角度采样(半像素抗锯齿), 与像素风观感直接冲突。这里改用离散色块推进 ——
 * 用 setInterval 每帧只切换"哪个块是亮的", 没有任何 transition/animate 工具类参与, 状态差是瞬时切色,
 * 与规格第七章"状态靠换色, 不做渐变过渡"的压缩原则同源, 只是把它用在时间轴上而不是交互态上。
 *
 * 为什么不包一层 PixelFrame: 本组件是被别的容器承载的忙碌态(按钮内部 / 面板中央 / 表格空态行),
 * 调用方所在的容器通常已经有框, 再起一层边框是双重视觉噪声。需要独立成框的忙碌页由调用方外面套
 * PixelFrame, 不把这个决定内置进控件。
 */

const BLOCK_COUNT = 4
const FRAME_INTERVAL_MS = 400
/** 无 label 时读屏仍需要一个可读状态名; 这不是业务数据兜底, 是控件本身固定的界面文案。 */
const DEFAULT_LOADING_LABEL = '加载中'

const BLOCK_SIZE_CLASS: Record<PixelControlSize, string> = {
  sm: 'h-2 w-2',
  md: 'h-3 w-3',
  lg: 'h-4 w-4',
}

const GAP_CLASS: Record<PixelControlSize, string> = {
  sm: 'gap-1',
  md: 'gap-1',
  lg: 'gap-2',
}

const BASE_CLASS = 'inline-flex items-center gap-2'

export interface PixelLoadingProps {
  label?: string
  size?: PixelControlSize
  className?: string
}

export function PixelLoading({ label, size = 'md', className }: PixelLoadingProps): ReactElement {
  const [activeFrame, setActiveFrame] = useState(0)

  useEffect(() => {
    const timer = setInterval(() => {
      setActiveFrame((frame) => (frame + 1) % BLOCK_COUNT)
    }, FRAME_INTERVAL_MS)
    return () => {
      clearInterval(timer)
    }
  }, [])

  const blocks = Array.from({ length: BLOCK_COUNT }, (_unused, index) => index)

  return (
    <div
      role="status"
      aria-live="polite"
      {...(label === undefined ? { 'aria-label': DEFAULT_LOADING_LABEL } : {})}
      className={className === undefined ? BASE_CLASS : `${BASE_CLASS} ${className}`}
    >
      <span className={`inline-flex ${GAP_CLASS[size]}`}>
        {blocks.map((index) => (
          <span
            key={index}
            className={`${BLOCK_SIZE_CLASS[size]} ${index === activeFrame ? 'bg-accent' : 'bg-border'}`}
          />
        ))}
      </span>
      {label === undefined ? null : <span className={PIXEL_CONTROL_TEXT_CLASS[size]}>{label}</span>}
    </div>
  )
}

export interface PixelLoadingDemoCase {
  readonly size: PixelControlSize
  readonly label?: string
}

/** 三档尺寸各取一例, lg 档故意不给 label —— 演示"纯指示器嵌入按钮"这个最常见的用法。 */
export const PIXEL_LOADING_DEMO: readonly PixelLoadingDemoCase[] = [
  { size: 'sm', label: '加载中' },
  { size: 'md', label: '正在同步市场数据' },
  { size: 'lg' },
]
