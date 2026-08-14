import { ClockIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import type { DataTableColumn } from '@/components/kit'
import {
  Button,
  Currency,
  DataTable,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  ItemIcon,
  LoadingBlock,
  Panel,
  Tag,
} from '@/components/kit'
import { isMockActive } from '../../lib/bridge'
import { useItemNames } from '../../lib/i18n'
import { useMockAction } from '../../mock'
import type { PlannedMarketTransaction } from '../../mock'

/**
 * 跳蚤市场 · 成交历史。
 *
 * 本页同时展示两条互不替代的数据源, 界面上必须分开标注, 不能揉成一张看似真实的表:
 *   1. **真实数据** market.history (真契约, 已接线) —— MarketDao 至今没有按玩家查 transactions 的
 *      方法, 服务端固定回 `{ transactions: [], page }`, lib/types.ts 的 MarketHistoryResult 甚至把
 *      transactions 的类型钉死成空元组 `[]`。本页仍然真的调用它 (走 loading/ready/error 三态),
 *      而不是假装这条 action 不存在 —— 它就是当前的真实后果, 空态本身就是要传达的信息。
 *   2. **预览数据** market.transactions (接线清单 B6, planned.ts 假定契约) —— 用来演示"成交流水做出来
 *      之后长什么样", 数据整段来自 mock/seed.ts 的种子, 与任何真实玩家操作无关, 不随挂单/撤单变化。
 */

const REAL_HISTORY_PAYLOAD: { page: number } = { page: 0 }
const PLANNED_PAGE_SIZE = 10

const ROLE_LABEL: Record<PlannedMarketTransaction['role'], string> = {
  buyer: '买入',
  seller: '卖出',
}

function formatTimestamp(at: number): string {
  const date = new Date(at)
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function HistoryPage(): ReactElement {
  const realQuery = useMockAction('market.history', REAL_HISTORY_PAYLOAD)

  const [plannedPage, setPlannedPage] = useState(0)
  const plannedQuery = useMockAction('market.transactions', {
    page: plannedPage,
    pageSize: PLANNED_PAGE_SIZE,
  })

  const transactions = plannedQuery.status === 'ready' ? plannedQuery.data.transactions : []
  const descriptionIds = Array.from(new Set(transactions.map((txn) => txn.descriptionId)))
  const names = useItemNames(descriptionIds)

  const totalPages =
    plannedQuery.status === 'ready' ? Math.max(1, Math.ceil(plannedQuery.data.total / PLANNED_PAGE_SIZE)) : 1

  const columns: readonly DataTableColumn<PlannedMarketTransaction>[] = [
    {
      key: 'role',
      header: '方向',
      render: (row) => <Tag tone={row.role === 'buyer' ? 'info' : 'success'}>{ROLE_LABEL[row.role]}</Tag>,
      sortValue: (row) => ROLE_LABEL[row.role],
    },
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
      key: 'fee',
      header: '手续费',
      numeric: true,
      render: (row) => <Currency amount={row.fee} currency="credit" size="sm" />,
      sortValue: (row) => row.fee,
    },
    {
      key: 'counterparty',
      header: '对手方',
      // null 是"系统回收/无对手方"的合法值, 不是名字没加载出来 —— 两者不能用同一句"—"含糊带过。
      render: (row) => (
        <span
          className={
            row.counterpartyName === null
              ? 'text-muted-foreground text-sm'
              : 'text-foreground text-sm'
          }
        >
          {row.counterpartyName ?? '系统'}
        </span>
      ),
      sortValue: (row) => row.counterpartyName ?? '',
    },
    {
      key: 'at',
      header: '成交时间',
      render: (row) => <span className="text-muted-foreground text-sm">{formatTimestamp(row.at)}</span>,
      sortValue: (row) => row.at,
    },
  ]

  return (
    <div className="flex flex-col gap-4">
      <FeedbackAlert
        message={
          isMockActive()
            ? '成交历史暂未开放, 下方"示例记录"是演示数据 (market.transactions, 接线清单 B6), 不是你的真实成交。'
            : '成交历史暂未开放, 下方"示例记录"只是演示用的数据, 不是你的真实成交。'
        }
        tone="warning"
      />

      <Panel title="我的成交记录">
        {realQuery.status === 'loading' ? <LoadingBlock label="正在查询" size="sm" /> : null}
        {realQuery.status === 'error' ? (
          <ErrorBlock message={`查询失败: ${realQuery.error.message}`} onRetry={realQuery.reload} />
        ) : null}
        {realQuery.status === 'ready' ? (
          <EmptyBlock
            hint="成交流水功能还没开放, 不是你的记录丢了"
            icon={<ClockIcon aria-hidden="true" />}
            title="暂无成交记录"
          />
        ) : null}
      </Panel>

      <Panel description="功能开放后, 你的成交记录会长这样" title="示例记录">
        {plannedQuery.status === 'loading' ? <LoadingBlock label="正在读取示例数据" /> : null}
        {plannedQuery.status === 'error' ? (
          <ErrorBlock message={`读取失败: ${plannedQuery.error.message}`} onRetry={plannedQuery.reload} />
        ) : null}

        {plannedQuery.status === 'ready' && transactions.length === 0 ? (
          <EmptyBlock icon={<ClockIcon aria-hidden="true" />} title="暂无示例记录" />
        ) : null}

        {plannedQuery.status === 'ready' && transactions.length > 0 ? (
          <div className="flex flex-col gap-3">
            <DataTable columns={columns} rowKey={(row) => String(row.txnId)} rows={transactions} />
            <div className="flex items-center justify-center gap-3">
              <Button
                disabled={plannedPage <= 0}
                onClick={() => {
                  setPlannedPage((page) => Math.max(0, page - 1))
                }}
                size="sm"
                variant="outline"
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-sm">
                第 {plannedPage + 1} / {totalPages} 页
              </span>
              <Button
                disabled={plannedPage >= totalPages - 1}
                onClick={() => {
                  setPlannedPage((page) => Math.min(totalPages - 1, page + 1))
                }}
                size="sm"
                variant="outline"
              >
                下一页
              </Button>
            </div>
          </div>
        ) : null}
      </Panel>
    </div>
  )
}
