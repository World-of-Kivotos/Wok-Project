import type { ReactElement } from 'react'
import { useState } from 'react'
import type { DataTableColumn } from '@/components/kit'
import {
  Button,
  ConfirmDangerDialog,
  Currency,
  DataTable,
  EmptyBlock,
  ErrorBlock,
  ItemIcon,
  LoadingBlock,
  Panel,
} from '@/components/kit'
import { useItemNames } from '../../lib/i18n'
import type { MarketListing } from '../../lib/types'
import { callMock, useMockAction } from '../../mock'

/**
 * 跳蚤市场 · 我的挂单。
 *
 * 全部数据来自已真实接线的 action, 不依赖 planned.ts 的任何假定契约:
 *   market.mine (读我的 ACTIVE 挂单) + market.cancel (撤单)。
 * 撤单不退手续费 (MarketEngine.CancelResult 无 fee 字段, 见 lib/types.ts MarketCancelResult 注释),
 * 二次确认弹窗的文案必须把这一点讲清楚, 不能让玩家以为撤单是无成本的。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 相对时间只用于这张表的展示, 不参与任何业务判定, 故取本地 Date.now() 而非 mock 的世界纪元。 */
function formatAge(createdAt: number): string {
  const elapsedMs = Date.now() - createdAt
  const minutes = Math.floor(elapsedMs / 60_000)
  if (minutes < 60) {
    return `${String(Math.max(0, minutes))} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${String(hours)} 小时前`
  }
  return `${String(Math.floor(hours / 24))} 天前`
}

export function MyListingsPage(): ReactElement {
  const listingsQuery = useMockAction('market.mine', EMPTY_PAYLOAD)
  const reload = listingsQuery.reload

  const [cancelTarget, setCancelTarget] = useState<MarketListing | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<Error | null>(null)

  const listings = listingsQuery.status === 'ready' ? listingsQuery.data.listings : []
  const descriptionIds = Array.from(new Set(listings.map((listing) => listing.descriptionId)))
  const names = useItemNames(descriptionIds)

  function handleConfirmCancel(): void {
    if (cancelTarget === null) {
      return
    }
    setCancelling(true)
    callMock('market.cancel', { listingId: cancelTarget.id })
      .then(() => {
        setCancelling(false)
        setCancelTarget(null)
        setCancelError(null)
        reload()
      })
      .catch((error: unknown) => {
        setCancelling(false)
        // 失败时**保住** cancelTarget: 清掉它, 下面那个"重试"按钮就没有目标可撤, 只能退化成"把错误关掉",
        // 而按钮上写着重试 —— 玩家点完以为重发了一次, 实际什么都没发生。目标留着, 重试才名副其实。
        setCancelError(toError(error))
      })
  }

  const columns: readonly DataTableColumn<MarketListing>[] = [
    {
      key: 'item',
      header: '物品',
      render: (row) => {
        const label = names[row.descriptionId] ?? row.descriptionId
        return (
          <div className="flex items-center gap-2">
            <ItemIcon itemId={row.itemId} label={label} scale={1} />
            <span className="text-foreground text-sm">{label}</span>
          </div>
        )
      },
      sortValue: (row) => names[row.descriptionId] ?? row.descriptionId,
    },
    {
      key: 'count',
      header: '数量',
      numeric: true,
      render: (row) => String(row.count),
      sortValue: (row) => row.count,
    },
    {
      key: 'unitPrice',
      header: '单价',
      numeric: true,
      render: (row) => <Currency amount={row.unitPrice} currency="credit" size="sm" />,
      sortValue: (row) => row.unitPrice,
    },
    {
      key: 'total',
      header: '总价',
      numeric: true,
      render: (row) => <Currency amount={row.total} currency="credit" size="sm" />,
      sortValue: (row) => row.total,
    },
    {
      key: 'createdAt',
      header: '挂出时间',
      render: (row) => <span className="text-muted-foreground text-sm">{formatAge(row.createdAt)}</span>,
      sortValue: (row) => row.createdAt,
    },
    {
      key: 'action',
      header: '操作',
      render: (row) => (
        <Button
          onClick={() => {
            setCancelTarget(row)
          }}
          size="sm"
          variant="destructive"
        >
          撤单
        </Button>
      ),
    },
  ]

  return (
    <div className="flex flex-col gap-4">
      {cancelError === null ? null : (
        <ErrorBlock
          message={`撤单失败: ${cancelError.message}`}
          {...(cancelTarget === null ? {} : { onRetry: handleConfirmCancel })}
        />
      )}

      {listingsQuery.status === 'loading' ? <LoadingBlock label="正在读取我的挂单" /> : null}

      {listingsQuery.status === 'error' ? (
        <ErrorBlock message={`挂单读取失败: ${listingsQuery.error.message}`} onRetry={reload} />
      ) : null}

      {listingsQuery.status === 'ready' && listings.length === 0 ? (
        <EmptyBlock hint="去仓库页把要出售的物品挂上跳蚤市场" title="暂无在售挂单" />
      ) : null}

      {listingsQuery.status === 'ready' && listings.length > 0 ? (
        <Panel padded={false} title="在售挂单 (ACTIVE)">
          <DataTable
            columns={columns}
            emptyHint="暂无在售挂单"
            rowKey={(row) => String(row.id)}
            rows={listings}
          />
        </Panel>
      ) : null}

      <ConfirmDangerDialog
        confirmLabel="确认撤单"
        loading={cancelling}
        message={
          cancelTarget === null
            ? ''
            : `撤下后 ${names[cancelTarget.descriptionId] ?? cancelTarget.descriptionId} x${cancelTarget.count} 将退回背包, 已收取的挂单手续费不予退还。`
        }
        onConfirm={handleConfirmCancel}
        onOpenChange={(next) => {
          // 对话框只在"被关掉"时清目标; 打开由 setCancelTarget 单向驱动, 这里不反向写回。
          if (!next) {
            setCancelTarget(null)
          }
        }}
        open={cancelTarget !== null}
        title="撤下该挂单?"
      />
    </div>
  )
}
