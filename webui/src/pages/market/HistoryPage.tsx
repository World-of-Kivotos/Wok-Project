import type { ReactElement } from 'react'
import { useState } from 'react'
import type { PixelTableColumn } from '../../components/pixel'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelLoading,
  PixelTable,
} from '../../components/pixel'
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

  const columns: readonly PixelTableColumn<PlannedMarketTransaction>[] = [
    {
      key: 'role',
      header: '方向',
      render: (row) => (
        <PixelBadge tone={row.role === 'buyer' ? 'info' : 'success'}>{ROLE_LABEL[row.role]}</PixelBadge>
      ),
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
            <span className="text-1x text-fg">{label}</span>
          </div>
        )
      },
      sortValue: (row) => names[row.descriptionId] ?? row.descriptionId,
    },
    { key: 'count', header: '数量', render: (row) => String(row.count), sortValue: (row) => row.count },
    {
      key: 'unitPrice',
      header: '单价',
      render: (row) => <PixelCurrency amount={row.unitPrice} currency="credit" size="sm" />,
      sortValue: (row) => row.unitPrice,
    },
    {
      key: 'total',
      header: '总价',
      render: (row) => <PixelCurrency amount={row.total} currency="credit" size="sm" />,
      sortValue: (row) => row.total,
    },
    {
      key: 'fee',
      header: '手续费',
      render: (row) => <PixelCurrency amount={row.fee} currency="credit" size="sm" />,
      sortValue: (row) => row.fee,
    },
    {
      key: 'counterparty',
      header: '对手方',
      // null 是"系统回收/无对手方"的合法值, 不是名字没加载出来 —— 两者不能用同一句"—"含糊带过。
      render: (row) => (
        <span className={row.counterpartyName === null ? 'text-1x text-muted' : 'text-1x text-fg'}>
          {row.counterpartyName ?? '系统 (无对手方)'}
        </span>
      ),
      sortValue: (row) => row.counterpartyName ?? '',
    },
    {
      key: 'at',
      header: '成交时间',
      render: (row) => <span className="text-1x text-muted">{formatTimestamp(row.at)}</span>,
      sortValue: (row) => row.at,
    },
  ]

  return (
    <div className="flex flex-col gap-6">
      <PixelBadge tone="warning" className="w-full justify-start">
        后端 market.history 当前恒返回空数组 (MarketDao 无按玩家查流水的方法), 下方"预览数据"表使用前端假定契约
        market.transactions (接线清单 B6) 演示日后形态, 与真实成交记录无关。
      </PixelBadge>

      <section className="flex flex-col gap-2">
        <h2 className="text-1x text-fg">真实数据 (market.history)</h2>
        {realQuery.status === 'loading' ? <PixelLoading size="sm" label="正在查询" /> : null}
        {realQuery.status === 'error' ? (
          <PixelError message={`查询失败: ${realQuery.error.message}`} onRetry={realQuery.reload} />
        ) : null}
        {realQuery.status === 'ready' ? (
          <PixelEmpty title="暂无成交记录" hint="服务端流水查询尚未实现, 这不是你的账号没有交易" icon="clock" />
        ) : null}
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-1x text-fg">预览数据 (market.transactions, 假定契约)</h2>

        {plannedQuery.status === 'loading' ? <PixelLoading label="正在读取演示数据" /> : null}
        {plannedQuery.status === 'error' ? (
          <PixelError message={`读取失败: ${plannedQuery.error.message}`} onRetry={plannedQuery.reload} />
        ) : null}

        {plannedQuery.status === 'ready' && transactions.length === 0 ? (
          <PixelEmpty title="暂无演示流水" icon="clock" />
        ) : null}

        {plannedQuery.status === 'ready' && transactions.length > 0 ? (
          <>
            <PixelTable columns={columns} rows={transactions} rowKey={(row) => String(row.txnId)} className="h-96" />
            <div className="flex items-center justify-center gap-4">
              <PixelButton
                size="sm"
                disabled={plannedPage <= 0}
                onClick={() => {
                  setPlannedPage((page) => Math.max(0, page - 1))
                }}
              >
                上一页
              </PixelButton>
              <span className="text-1x text-muted">
                第 {plannedPage + 1} / {totalPages} 页
              </span>
              <PixelButton
                size="sm"
                disabled={plannedPage >= totalPages - 1}
                onClick={() => {
                  setPlannedPage((page) => Math.min(totalPages - 1, page + 1))
                }}
              >
                下一页
              </PixelButton>
            </div>
          </>
        ) : null}
      </section>
    </div>
  )
}
