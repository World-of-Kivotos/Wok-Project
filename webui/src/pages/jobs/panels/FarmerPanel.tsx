import type { ReactElement } from 'react'
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Currency,
  DataTable,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  ItemIcon,
  LoadingBlock,
  Meter,
  NumberInput,
  Panel,
  Stat,
  Surface,
} from '@/components/kit'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type { FarmerSellResult, FarmerTierRow, PlayerInventoryItem } from '../../../lib/types'
import { callMock, refreshInventoryMirror, useMockAction, useMockWorld } from '../../../mock'
import { toError } from './shared'

/**
 * 农夫面板 (`job.farmer.state` / `job.farmer.sell`, Java 落点 com.miningdim.job.farmer.FarmerWebUiActions)。
 * 回执形状见 lib/types.ts 的 FarmerStateResult / FarmerSellResult。
 *
 * 两条容易写错的口径, 文案必须照此写:
 *   1. dailySoftCap 不是拒收线, 是**降价线** —— 超过之后单价按曲线衰减 (basePrice=1 时下取整直接到 0),
 *      但收购站照收。写成"今日额度"会让玩家以为卖不动了。
 *   2. soldCount > 0 而 credited === 0 是合法结果 (曲线跌到地板), 物品照扣、发币为 0, 必须如实显示,
 *      不许当成失败。
 *
 * 出售没有等级门: /farmer sell 全路径零等级判定 (FarmerSystem.sellCommand 与 FarmerWheatSellService.sell
 * 都不校验), 故本面板不画等级门横幅。唯一与等级相关的是耕地档位解锁, 那是产出门不是卖出门。
 *
 * 出售不按槽位: 服务端按物品种类扫全背包扣, 玩家的小麦本来就可能散在多个未满栈里。可卖上限由本页拿
 * 背包镜像按 crop.itemId 求和自算 —— 服务端不另开一条查库存的 action。
 *
 * 背包数据走 mock 的真域镜像 (world.mirror.inventory): TabletShell 已在挂载期拉过一次 player.inventory,
 * 但页面自身可能先于外壳的 effect 挂载, 不能假设镜像已就绪, 故本文件独立调用一次 refreshInventoryMirror
 * 兜底。卖出成功后不必再手动重拉背包 —— job.farmer.sell 已登记进 handlers.ts 的 MIRROR_AFTER_INVENTORY,
 * delegateReal 会在写操作成功后自动刷一次镜像。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

export function FarmerPanel(): ReactElement {
  const query = useMockAction('job.farmer.state', EMPTY_PAYLOAD)
  const world = useMockWorld()

  const [inventoryError, setInventoryError] = useState<Error | null>(null)
  const [count, setCount] = useState(1)
  const [selling, setSelling] = useState(false)
  const [sellError, setSellError] = useState<Error | null>(null)
  const [sellResult, setSellResult] = useState<FarmerSellResult | null>(null)

  const loadInventory = useCallback(() => {
    refreshInventoryMirror()
      .then(() => {
        setInventoryError(null)
      })
      .catch((error: unknown) => {
        setInventoryError(toError(error))
      })
  }, [])

  useEffect(() => {
    loadInventory()
  }, [loadInventory])

  const data = query.status === 'ready' ? query.data : null
  const cropDescriptionId = data === null ? null : data.crop.descriptionId
  const names = useItemNames([
    ...(cropDescriptionId === null ? [] : [cropDescriptionId]),
    ...(data === null ? [] : data.farmlandTiers.map((tier) => tier.nameKey)),
  ])

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取农夫档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.farmer.state 回执为空" onRetry={query.reload} />
  }

  const inventory: readonly PlayerInventoryItem[] = world.mirror.inventory ?? []
  const owned = inventory
    .filter((item) => item.itemId === data.crop.itemId)
    .reduce((sum, item) => sum + item.count, 0)
  const cropName = names[data.crop.descriptionId] ?? data.crop.descriptionId
  const overSoftCap = data.soldToday >= data.dailySoftCap
  const sellable = Math.min(count, owned)

  async function handleSell(): Promise<void> {
    setSelling(true)
    setSellError(null)
    try {
      const result = await callMock('job.farmer.sell', { count })
      setSellResult(result)
      // 背包由镜像层在写操作成功后自动刷 (handlers.ts 的 MIRROR_AFTER_INVENTORY); 本处只重查职业档案,
      // 拿到 soldToday/单价曲线的新值。
      query.reload()
    } catch (error) {
      setSellError(toError(error))
    } finally {
      setSelling(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Panel title="农夫">
        <div className="grid grid-cols-3 gap-4">
          <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
          <Stat label="下一株收购价" value={<Currency amount={data.nextUnitPrice} currency="credit" />} />
        </div>
      </Panel>

      <Panel title="卖菜">
        <div className="flex flex-col gap-3">
          {inventoryError === null ? null : (
            <FeedbackAlert
              action={
                <Button onClick={loadInventory} size="sm" variant="outline">
                  重试
                </Button>
              }
              message={`背包读取失败: ${inventoryError.message}`}
              tone="danger"
            />
          )}
          {/*
            读取失败也满足 inventory === null, 早先这一支只判 null, 于是失败时上面那条错误横幅与
            这里的"正在读取背包"同时挂着, 而加载态永远不会结束 —— 界面同时声称"出错了"和"还在读",
            玩家不知道该等还是该点重试。失败时把加载态让出去, 只留错误与重试。
          */}
          {world.mirror.inventory === null ? (
            inventoryError === null ? (
              <LoadingBlock label="正在读取背包" size="sm" />
            ) : null
          ) : owned === 0 ? (
            <EmptyBlock hint={`收购站只收${cropName}`} title="背包里没有可出售的作物" />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-3">
                <span className="flex items-center gap-2 text-sm">
                  <ItemIcon itemId={data.crop.itemId} label={cropName} />
                  <span className="text-foreground">
                    背包里共有 {owned} 株{cropName}
                  </span>
                </span>
                <NumberInput max={owned} min={1} onChange={setCount} value={count} />
                <Button
                  loading={selling}
                  onClick={() => {
                    void handleSell()
                  }}
                  variant="brand"
                >
                  出售
                </Button>
              </div>
              <p className="text-muted-foreground text-xs">
                按物品种类扫全背包扣, 与槽位无关; 请求数超过持有量时按持有量卖 (本次将卖出 {sellable} 株)
              </p>
              {/*
                事前警示。收购价跌到 0 不是渐变而是断崖: 锚价 1 下取整后, 超过软上限的第一株单价就已经是 0,
                并不存在"逐株衰减到地板比例"那个过程。原先只有卖完之后的事后提示, 玩家拉满数量点一次就是
                整批作物凭空消失, 且那时东西已经扣了。
              */}
              {data.nextUnitPrice === 0 ? (
                <FeedbackAlert
                  message={`当前收购价已是 0: 卖出会照常扣掉${cropName}, 但一个信用点都不产生。`}
                  tone="warning"
                />
              ) : null}
              {sellError === null ? null : (
                <FeedbackAlert message={callErrorText(sellError)} tone="danger" />
              )}
              {sellResult === null ? null : (
                <Surface tone={sellResult.credited === 0 ? 'warning' : 'success'}>
                  <div className="flex flex-col gap-1">
                    <p className="flex flex-wrap items-center gap-1 text-foreground text-sm">
                      已售出 {sellResult.soldCount} 株, 实发
                      <Currency amount={sellResult.credited} currency="credit" size="sm" />, 下一株单价
                      <Currency amount={sellResult.nextUnitPrice} currency="credit" size="sm" />
                    </p>
                    {sellResult.soldCount > 0 && sellResult.credited === 0 ? (
                      <p className="text-muted-foreground text-xs">
                        收购曲线已跌到地板, 本次单价被下取整到 0: 小麦照常扣除, 但这一批不产生信用点
                      </p>
                    ) : null}
                  </div>
                </Surface>
              )}
            </>
          )}
        </div>
      </Panel>

      <Panel title="今日收购曲线">
        <div className="flex flex-col gap-3">
          <Meter
            label="今日已售 (超过后降价, 不是拒收)"
            max={data.dailySoftCap}
            tone={overSoftCap ? 'warning' : 'brand'}
            value={Math.min(data.soldToday, data.dailySoftCap)}
            valueText={`${String(data.soldToday)} / ${String(data.dailySoftCap)}`}
          />
          <div className="grid grid-cols-3 gap-4">
            <Stat label="锚价 (未衰减)" value={<Currency amount={data.basePrice} currency="credit" />} />
            <Stat
              label="下一株单价"
              value={<Currency amount={data.nextUnitPrice} currency="credit" />}
            />
            <Stat
              hint="衰减最多跌到锚价的这个比例"
              label="价格地板"
              value={`${(data.priceFloorRatio * 100).toFixed(0)}%`}
            />
          </div>
          <p className="text-muted-foreground text-xs">
            超出软上限的部分按 0.97 的幂逐株衰减, 最低不低于锚价的{' '}
            {(data.priceFloorRatio * 100).toFixed(0)}%; 整数下取整后可能为 0, 那是边际收益归零而非缺数据
          </p>
        </div>
      </Panel>

      <Panel title="耕地五档">
        <DataTable<FarmerTierRow>
          columns={[
            {
              header: '档位',
              key: 'name',
              render: (row) => (
                <span className={row.unlocked ? 'text-foreground' : 'text-muted-foreground'}>
                  {names[row.nameKey] ?? row.nameKey}
                  {row.unlocked ? '' : ' (未解锁)'}
                </span>
              ),
            },
            {
              header: '解锁等级',
              key: 'unlockLevel',
              numeric: true,
              render: (row) => String(row.unlockLevel),
              sortValue: (row) => row.unlockLevel,
            },
            {
              header: '成长分钟',
              key: 'growthMinutes',
              numeric: true,
              render: (row) => `${String(row.growthMinutes)} 分`,
              sortValue: (row) => row.growthMinutes,
            },
            {
              header: '每次产量',
              key: 'yieldPerHarvest',
              numeric: true,
              render: (row) => `${String(row.yieldPerHarvest)} 株`,
              sortValue: (row) => row.yieldPerHarvest,
            },
            {
              header: '每小时产量',
              key: 'wheatPerHour',
              numeric: true,
              render: (row) => `${row.wheatPerHour.toFixed(1)} 株`,
              sortValue: (row) => row.wheatPerHour,
            },
          ]}
          rowKey={(row) => row.tierId}
          rows={data.farmlandTiers}
        />
      </Panel>
    </div>
  )
}
