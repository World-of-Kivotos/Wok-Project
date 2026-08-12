import type { ReactElement } from 'react'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import { PixelIcon } from './PixelIcon'
import { PixelFrame } from './PixelFrame'

/**
 * 错误态占位, 带可选重试。真源: conventions.md 十-L2 表("不内置错误码字典, 接线清单 A10 未做")。
 *
 * 容器固定 `tone="danger"`(错误是唯一必须一眼可辨的空态变体, 不开放 tone prop 给调用方乱调)。
 * 重试按钮不复用 `PixelButton`: L1 批次与本批并行交付, 此刻同目录下还没有那个文件;
 * 直接手写原生 `<button>` 并复用 controlSize 的 md 档表, 与未来的 PixelButton md 档视觉对齐,
 * 待 L1 落地后这里具体要不要换成 `<PixelButton>` 是一次纯替换, 不影响对外 props。
 * `code` 是服务端错误码原文, 不做中文化映射 —— 映射表还没做, 硬翻会是猜的。
 */

const BASE_CLASS = 'w-full'

export interface PixelErrorProps {
  message: string
  code?: string
  onRetry?: () => void
  className?: string
}

export function PixelError({ message, code, onRetry, className }: PixelErrorProps): ReactElement {
  return (
    <PixelFrame
      variant="panel"
      tone="danger"
      className={className === undefined ? BASE_CLASS : `${BASE_CLASS} ${className}`}
    >
      {/* role="alert" 让错误在读屏下抢占播报(assertive), 空态/加载态不需要这种打断优先级。 */}
      <div role="alert" className="flex flex-col items-center gap-4 p-8 text-center">
        <PixelIcon name="warning" scale={2} className="text-danger" />
        <p className="text-1x text-fg">{message}</p>
        {code === undefined ? null : <p className="text-1x text-muted">{code}</p>}
        {onRetry === undefined ? null : (
          <button
            type="button"
            onClick={onRetry}
            className={`${PIXEL_CONTROL_PADDING_CLASS.md} ${PIXEL_CONTROL_TEXT_CLASS.md} inline-flex items-center gap-2 border border-border-strong bg-accent text-on-accent shadow-hard hover:bg-accent-hover active:translate-y-1 active:bg-accent-active active:shadow-none focus-visible:outline-none focus-visible:border-fg`}
          >
            <PixelIcon name="refresh" scale={1} />
            重试
          </button>
        )}
      </div>
    </PixelFrame>
  )
}

/** 供组件预览页与请求失败面板复用; 不含 onRetry —— demo 数据不该带一个具体的函数引用。 */
export const PIXEL_ERROR_DEMO: Pick<PixelErrorProps, 'message' | 'code'> = {
  message: '市场数据加载失败, 请检查网络连接后重试',
  code: 'MARKET_FETCH_TIMEOUT',
}
