import type { ReactElement } from 'react'
import { Currency, EmptyBlock, ErrorBlock, LoadingBlock, Panel, Surface } from '@/components/kit'
import { callErrorText } from '../../lib/errorText'
import type { MarketPendingPayoutPayload } from '../../lib/types'
import { useMockAction } from '../../mock'

/**
 * 跳蚤市场 · 收件箱 (离线成交的待结货款)。
 *
 * 数据源 market.pendingPayout (真契约), **只读 peek**: 服务端读同一批 pending_payout 行但不删。
 * 真实发放只发生在玩家登录时 (MarketEngine.settlePendingOnLogin 走取即删的 drainPendingPayout),
 * 故本页没有"领取"按钮 —— 这不是少做了一个功能: 本轮没有 claim action, 摆一个点了没反应的按钮
 * 比没有按钮更糟, 而让 peek 顺手清空则等于玩家查一次货款就把钱冲掉。
 *
 * 也没有"待领取物品": pending_payout 表只有金额没有物品 (被卖掉的是什么从未被持久化过),
 * 买家那侧的实物是成交当场进背包的。这里不摆一个永远空的物品格区。
 */

const EMPTY_PAYLOAD: MarketPendingPayoutPayload = {}

export function InboxPage(): ReactElement {
  const payoutQuery = useMockAction('market.pendingPayout', EMPTY_PAYLOAD)

  return (
    <div className="flex flex-col gap-4">
      {payoutQuery.status === 'loading' ? <LoadingBlock label="正在查询待结货款" /> : null}

      {payoutQuery.status === 'error' ? (
        <ErrorBlock
          message={`查询失败: ${callErrorText(payoutQuery.error)}`}
          onRetry={payoutQuery.reload}
        />
      ) : null}

      {payoutQuery.status === 'ready' && payoutQuery.data.entryCount === 0 ? (
        <EmptyBlock hint="你离线时成交的货款会先记在这里, 下次登录自动结清" title="没有待结货款" />
      ) : null}

      {payoutQuery.status === 'ready' && payoutQuery.data.entryCount > 0 ? (
        <Panel description="离线成交的货款, 下次登录时自动结清到账户" title="待结货款">
          <div className="flex flex-col gap-3">
            <Currency amount={payoutQuery.data.credit} currency="credit" size="lg" />
            <span className="text-muted-foreground text-sm">
              共 {payoutQuery.data.entryCount} 笔离线成交
            </span>
            <Surface>
              <p className="text-muted-foreground text-sm">
                不需要手动领取: 下次登录时服务端会一次性把这笔钱打进你的账户。
              </p>
            </Surface>
          </div>
        </Panel>
      ) : null}
    </div>
  )
}
