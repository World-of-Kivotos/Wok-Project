import type { ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Currency,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  ItemSlot,
  LoadingBlock,
  Panel,
  Surface,
} from '@/components/kit'
import { useItemDisplayNames } from '../../lib/i18n'
import { getWorld, mutateWorld, useMockAction } from '../../mock'

/**
 * 跳蚤市场 · 收件箱 (离线成交的货款与退回物品领取)。
 *
 * 依赖的假定契约 (planned.ts, 后端尚无对应 action):
 *   - market.pendingPayout (接线清单 B11): 只读 peek。真服对应的方法是 drainPendingPayout ——
 *     一个"取即删"的破坏性方法, 没有只读版本; 前端不得直接转调它当查询用 (打开收件箱就清零), 这条
 *     assumed action 本身就是在建模"服务端将来需要拆出一个不清零的只读接口"这件事。
 *
 * "领取"没有对应的 planned action (B11 只定义了 peek, 没定义 claim/领取的写操作), 且本批次不得往
 * mock/planned.ts 加新契约 (mock 目录由 mock 批次统一维护)。因此本页的领取按钮直接用已导出的
 * mutateWorld 就地改世界状态模拟"领取后消费掉待领队列"这件事 (与 TabletShell 的 OP 视图开关同一手法:
 * 页面层直接写 mock 世界, 不经 callMock), 不是绕过约定, 而是约定本就没有覆盖这一步。
 *
 * 已知偏差 (必须报备, 不得假装做到了): 货款领取会真实计入 walletOverlay (顶栏余额可见变化);
 * 退回物品领取只会清空收件箱列表, **不会**真的把物品塞回背包 —— bridge.mock 的背包数组是模块私有状态,
 * 没有对外写入口 (见 mock/handlers.ts 文件头"已知偏差 1"), 这条限制早于本页存在, 本页不重新发明一个。
 * 真接线后, 服务端 drainPendingPayout 会在同一次调用里把货款与物品一起发货, 这个割裂到时自然消失。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}
/** 模拟一次往返手感, 与 mock/handlers.ts 的 planned 延迟量级对齐, 好让忙碌态真的能被看见。 */
const CLAIM_LATENCY_MS = 220

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })
}

type ClaimState = { status: 'idle' } | { status: 'claiming' } | { status: 'claimed'; creditClaimed: number }

export function InboxPage(): ReactElement {
  const payoutQuery = useMockAction('market.pendingPayout', EMPTY_PAYLOAD)
  const reload = payoutQuery.reload

  const [claimState, setClaimState] = useState<ClaimState>({ status: 'idle' })

  /*
   * 领取途中离开本页 (平板切面板即卸载) 时, sleep 的定时器仍会到点并跑完回调 —— 那里面既有 setState
   * (卸载后写状态) 也有 mutateWorld + reload (对全局世界的写入)。写全局的那部分尤其不能放任:
   * 它会在玩家已经看不到收件箱的时候悄悄清空待领取项。这面旗一竖, 回调整体不执行。
   */
  const aliveRef = useRef(true)
  useEffect(
    () => () => {
      aliveRef.current = false
    },
    [],
  )

  const items = payoutQuery.status === 'ready' ? payoutQuery.data.items : []
  const nameOf = useItemDisplayNames(items)

  function handleClaim(): void {
    if (payoutQuery.status !== 'ready') {
      return
    }
    const { credit } = payoutQuery.data
    setClaimState({ status: 'claiming' })
    sleep(CLAIM_LATENCY_MS)
      .then(() => {
        if (!aliveRef.current) {
          return
        }
        mutateWorld((draft) => {
          draft.walletOverlay.credit += getWorld().market.pendingPayout.credit
          draft.market.pendingPayout = { credit: 0, items: [] }
        })
        setClaimState({ status: 'claimed', creditClaimed: credit })
        reload()
      })
      .catch(() => {
        // 本地纯内存写入不会真的失败; 保留分支是让状态机穷举完整, 而不是假装这里不可能出错。
        setClaimState({ status: 'idle' })
      })
  }

  const hasAnything = payoutQuery.status === 'ready' && (payoutQuery.data.credit > 0 || items.length > 0)

  return (
    <div className="flex flex-col gap-4">
      <FeedbackAlert
        message='收件箱背后的 market.pendingPayout 是前端假定契约 (接线清单 B11), 服务端尚未实现对应查询; "领取"按钮当前只在浏览器内存里模拟发放, 装进游戏后由服务端统一改写。'
        tone="warning"
      />

      {payoutQuery.status === 'loading' ? <LoadingBlock label="正在查询待领取内容" /> : null}

      {payoutQuery.status === 'error' ? (
        <ErrorBlock message={`查询失败: ${payoutQuery.error.message}`} onRetry={reload} />
      ) : null}

      {payoutQuery.status === 'ready' && !hasAnything ? (
        <EmptyBlock hint="离线成交的货款与退回物品会出现在这里" title="收件箱是空的" />
      ) : null}

      {payoutQuery.status === 'ready' && hasAnything ? (
        <div className="flex flex-col gap-4">
          <Panel title="待领取货款">
            {payoutQuery.data.credit > 0 ? (
              <Currency amount={payoutQuery.data.credit} currency="credit" size="lg" />
            ) : (
              <span className="text-muted-foreground text-sm">暂无待领取货款</span>
            )}
          </Panel>

          <Panel title="待领取物品">
            {items.length === 0 ? (
              <span className="text-muted-foreground text-sm">暂无待退回物品</span>
            ) : (
              <div className="flex flex-wrap gap-2">
                {items.map((item, index) => (
                  <ItemSlot
                    count={item.count}
                    // 待领货款里装的是别人买走的挂单实物, 必然出现枪匠零件这类变体件;
                    // 不带这个键的话它们在格子里是同一张图。
                    customModelData={item.customModelData}
                    itemId={item.itemId}
                    key={`${item.itemId}-${String(index)}`}
                    label={nameOf(item)}
                  />
                ))}
              </div>
            )}
          </Panel>

          <div>
            <Button
              disabled={claimState.status === 'claiming'}
              loading={claimState.status === 'claiming'}
              onClick={handleClaim}
              variant="brand"
            >
              全部领取
            </Button>
          </div>
        </div>
      ) : null}

      {claimState.status === 'claimed' ? (
        <Surface tone="success">
          <p className="flex flex-wrap items-center gap-1 text-foreground text-sm">
            已领取 <Currency amount={claimState.creditClaimed} currency="credit" size="sm" /> 货款
            (退回物品当前 mock 架构无法写回背包, 详见文件头"已知偏差")
          </p>
        </Surface>
      ) : null}
    </div>
  )
}
