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
  ItemIcon,
  LoadingBlock,
  Panel,
  Tag,
} from '@/components/kit'
import { callErrorText } from '../../lib/errorText'
import { useItemNames } from '../../lib/i18n'
import type { MarketTransaction } from '../../lib/types'
import { useMockAction } from '../../mock'

/**
 * 跳蚤市场 · 成交历史。
 *
 * 单一数据源 market.history (真契约)。本页此前是"真实 action 恒回空 + 一张 planned 示例表"的双轨结构,
 * W2 给 MarketDao 补上按玩家查流水的方法之后, 示例表整段作废 —— 一张标着"示例"的表和一张真表并排放,
 * 玩家分不清哪个是自己的钱。
 *
 * 分页吃回执的 total。market.list 至今没有 total (前端只能做"还有下一页"的启发式), 本条刻意补上了,
 * 故这里给的是真页码而不是猜的。
 */

const PAGE_SIZE = 10

const ROLE_LABEL: Record<MarketTransaction['role'], string> = {
  buy: '买入',
  sell: '卖出',
}

function formatTimestamp(at: number): string {
  return new Date(at).toLocaleString('zh-CN', { hour12: false })
}

/**
 * 对手方离线时服务端只有 UUID (transactions 表没有名字快照列, 只能解析在线玩家)。
 * 取前 8 位是为了让它在表格里放得下, 同时仍能与其它流水对上号; **不编"未知玩家"** ——
 * 那会把"这人现在不在线"说成"这条记录坏了"。
 */
function counterpartyFallback(uuid: string): string {
  return `${uuid.slice(0, 8)} (离线)`
}

export function HistoryPage(): ReactElement {
  const [page, setPage] = useState(0)
  const historyQuery = useMockAction('market.history', { page, pageSize: PAGE_SIZE })

  const transactions = historyQuery.status === 'ready' ? historyQuery.data.transactions : []
  const descriptionIds = Array.from(new Set(transactions.map((txn) => txn.descriptionId)))
  const names = useItemNames(descriptionIds)

  const totalPages =
    historyQuery.status === 'ready'
      ? Math.max(1, Math.ceil(historyQuery.data.total / PAGE_SIZE))
      : 1

  const columns: readonly DataTableColumn<MarketTransaction>[] = [
    {
      key: 'role',
      header: '方向',
      render: (row) => (
        <Tag tone={row.role === 'buy' ? 'info' : 'success'}>{ROLE_LABEL[row.role]}</Tag>
      ),
      sortValue: (row) => ROLE_LABEL[row.role],
    },
    {
      key: 'item',
      header: '物品',
      /*
       * 流水行没有 customModelData / nameParts: transactions 表只存 item_id, 不存成交物的 NBT。
       * 于是 195 种枪匠零件在这张表里是同名同图标 —— 这是数据层缺口, 不在这里用别处的 NBT 猜一个。
       */
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
      /*
       * 列名必须限定成"成交"手续费: 这一列恒为 0 (费在挂单那一刻由 market.place 收掉, 成交时不再收第二次),
       * 而叫"手续费"的一列全是 0 会被卖家读成"这单没收过费"。表格头放不下解释, 故下方配一行脚注。
       */
      header: '成交手续费',
      numeric: true,
      render: (row) => <Currency amount={row.fee} currency="credit" size="sm" />,
      sortValue: (row) => row.fee,
    },
    {
      key: 'counterparty',
      header: '对手方',
      render: (row) => (
        <span
          className={
            row.counterpartyName === null
              ? 'text-muted-foreground text-sm'
              : 'text-foreground text-sm'
          }
        >
          {row.counterpartyName ?? counterpartyFallback(row.counterpartyUuid)}
        </span>
      ),
      sortValue: (row) => row.counterpartyName ?? row.counterpartyUuid,
    },
    {
      key: 'createdAt',
      header: '成交时间',
      render: (row) => (
        <span className="text-muted-foreground text-sm">{formatTimestamp(row.createdAt)}</span>
      ),
      sortValue: (row) => row.createdAt,
    },
  ]

  return (
    <div className="flex flex-col gap-4">
      <Panel title="我的成交记录">
        {historyQuery.status === 'loading' ? <LoadingBlock label="正在查询" /> : null}
        {historyQuery.status === 'error' ? (
          <ErrorBlock
            message={`查询失败: ${callErrorText(historyQuery.error)}`}
            onRetry={historyQuery.reload}
          />
        ) : null}

        {historyQuery.status === 'ready' && transactions.length === 0 ? (
          <EmptyBlock
            hint={page === 0 ? '买入或卖出成交后会记在这里' : '这一页没有记录, 请返回上一页'}
            icon={<ClockIcon aria-hidden="true" />}
            title="暂无成交记录"
          />
        ) : null}

        {historyQuery.status === 'ready' && transactions.length > 0 ? (
          <div className="flex flex-col gap-3">
            <DataTable columns={columns} rowKey={(row) => String(row.txnId)} rows={transactions} />
            <p className="text-muted-foreground text-xs">
              成交手续费恒为 0: 卖出的手续费在
              <strong className="text-foreground font-medium">挂单那一刻</strong>
              就已从余额扣除 (撤单不退), 成交时不再收第二次。
            </p>
            <div className="flex items-center justify-center gap-3">
              <Button
                disabled={page <= 0}
                onClick={() => {
                  setPage((current) => Math.max(0, current - 1))
                }}
                size="sm"
                variant="outline"
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-sm">
                第 {page + 1} / {totalPages} 页 (共 {historyQuery.data.total} 条)
              </span>
              <Button
                disabled={page >= totalPages - 1}
                onClick={() => {
                  setPage((current) => Math.min(totalPages - 1, current + 1))
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
