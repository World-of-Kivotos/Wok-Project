import { InboxIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty'
import { Spinner } from '@/components/ui/spinner'
import type { ControlSize } from './tokens'
import { TEXT_SIZE_CLASS } from './tokens'

/**
 * 异步三态的标准呈现: 加载中 / 出错 / 空。
 *
 * 三者收在一个文件里是刻意的 —— 它们必须一起被看到。上一版界面里"错误态没有重试按钮"和
 * "空态与错误态长得一样"这两类问题, 都源于三个状态件分居三处、各自演化。
 */

const SPINNER_SIZE_CLASS: Record<ControlSize, string> = {
  sm: 'size-4',
  md: 'size-5',
  lg: 'size-6',
}

export interface LoadingBlockProps {
  label?: string | undefined
  size?: ControlSize | undefined
  className?: string | undefined
}

export function LoadingBlock({ label, size = 'md', className }: LoadingBlockProps): ReactElement {
  return (
    <div
      className={`flex items-center justify-center gap-2 py-6 text-muted-foreground${
        className === undefined ? '' : ` ${className}`
      }`}
      role="status"
    >
      <Spinner className={SPINNER_SIZE_CLASS[size]} />
      {label === undefined ? null : <span className={TEXT_SIZE_CLASS[size]}>{label}</span>}
    </div>
  )
}

export interface ErrorBlockProps {
  message: string
  /** 服务端错误码等可选补充标识, 单独一行小字展示。 */
  code?: string | undefined
  onRetry?: (() => void) | undefined
  className?: string | undefined
}

/**
 * role="alert" 让错误在读屏下抢占播报 (assertive)。加载态与空态不需要这种打断优先级, 故只有这里有。
 */
export function ErrorBlock({ message, code, onRetry, className }: ErrorBlockProps): ReactElement {
  return (
    <div
      className={`flex flex-col items-center gap-3 rounded-lg border border-destructive/32 bg-destructive/4 px-4 py-6 text-center${
        className === undefined ? '' : ` ${className}`
      }`}
      role="alert"
    >
      <TriangleAlertIcon aria-hidden="true" className="size-5 text-destructive" />
      <p className="text-foreground text-sm">{message}</p>
      {code === undefined ? null : <p className="font-mono text-muted-foreground text-xs">{code}</p>}
      {onRetry === undefined ? null : (
        <Button onClick={onRetry} size="sm" variant="outline">
          重试
        </Button>
      )}
    </div>
  )
}

export interface EmptyBlockProps {
  title: string
  hint?: string | undefined
  /** 缺省用收件箱图标。传 lucide 元素以贴合具体语境 (如筛选无结果用放大镜)。 */
  icon?: ReactNode | undefined
  /** 空态下的行动入口 (如"去挂单"/"清除筛选")。 */
  action?: ReactNode | undefined
  className?: string | undefined
}

export function EmptyBlock({ title, hint, icon, action, className }: EmptyBlockProps): ReactElement {
  return (
    <Empty className={className}>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          {icon ?? <InboxIcon aria-hidden="true" />}
        </EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        {hint === undefined ? null : <EmptyDescription>{hint}</EmptyDescription>}
      </EmptyHeader>
      {action}
    </Empty>
  )
}
