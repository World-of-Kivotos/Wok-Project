import type { ReactElement } from 'react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelLoading,
  PixelProgress,
  PixelSelect,
  PixelStepper,
  PixelTable,
} from '../../../components/pixel'
import { useItemNames } from '../../../lib/i18n'
import type { PlayerInventoryItem } from '../../../lib/types'
import { callMock, refreshWalletAndInventory, useMockAction, useMockWorld } from '../../../mock'
import type { PlannedCropPrice, PlannedFarmerSellResult } from '../../../mock'
import { formatStatValue, toError } from './shared'

/**
 * 农夫面板 (接线清单 C8 job.farmer.sell + job.farmer.state, PLANNED, 备注"服务端先扣后发 + 收购曲线
 * + faucet 主闸全就绪; 今日已售/耕地五档均已持久化可查")。
 *
 * 依赖的假定契约:
 *   - job.farmer.state -> PlannedFarmerStateResult (卖菜等级门/今日已售/收购曲线/耕地五档)
 *   - job.farmer.sell   -> PlannedFarmerSellResult (卖出结果: 实发信用点 + 卖后新单价)
 *
 * mock 阶段的已知偏差 (mock/handlers.ts 文件头第 1 条): 背包权威在 bridge.mock 内部, 外部没有写入口,
 * 因此 job.farmer.sell 只校验背包里确实有这件作物、**不真的扣物**, 演示时同一槽位可以反复卖出 ——
 * 真服接线后这里会自然变成"卖完这一槽就从选项里消失", 不需要改本文件任何一行。
 *
 * 背包数据走 mock 的真域镜像 (world.mirror.inventory), 不发明一条"农夫背包"专用查询: TabletShell 已在
 * 挂载期拉过一次 player.inventory, 但页面自身可能先于外壳的 effect 挂载, 不能假设镜像已就绪, 故本文件
 * 独立调用一次 refreshWalletAndInventory 兜底。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}
// 模块级稳定引用, 理由同 MinerPanel 的 EMPTY_DAILY_ORES: 避免 loading 期间每帧新建 [] 字面量,
// 拖着下游 useMemo/useEffect 一起判定"依赖变了"而重跑。
const EMPTY_CROPS: readonly PlannedCropPrice[] = []

export function FarmerPanel(): ReactElement {
  const query = useMockAction('job.farmer.state', EMPTY_PAYLOAD)
  const world = useMockWorld()

  const [inventoryError, setInventoryError] = useState<Error | null>(null)
  const [selectedSlot, setSelectedSlot] = useState<number | null>(null)
  const [count, setCount] = useState(1)
  const [selling, setSelling] = useState(false)
  const [sellError, setSellError] = useState<Error | null>(null)
  const [sellResult, setSellResult] = useState<PlannedFarmerSellResult | null>(null)

  const loadInventory = useCallback(() => {
    refreshWalletAndInventory()
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

  const crops = query.status === 'ready' ? query.data.crops : EMPTY_CROPS
  const cropItemIds = useMemo(() => new Set(crops.map((crop) => crop.itemId)), [crops])
  const inventory: readonly PlayerInventoryItem[] = world.mirror.inventory ?? []
  const sellableStacks = inventory.filter((item) => cropItemIds.has(item.itemId))
  const names = useItemNames([
    ...crops.map((crop) => crop.descriptionId),
    ...sellableStacks.map((stack) => stack.descriptionId),
  ])

  // 首次拿到可卖作物后默认选中第一格; selectedSlot 一旦非空就不再被这条效果覆盖。
  useEffect(() => {
    if (selectedSlot === null && sellableStacks.length > 0) {
      const first = sellableStacks[0]
      if (first !== undefined) {
        setSelectedSlot(first.slot)
        setCount(1)
      }
    }
  }, [sellableStacks, selectedSlot])

  const selectedStack = sellableStacks.find((stack) => stack.slot === selectedSlot)

  if (query.status === 'loading') {
    return <PixelLoading label="正在读取农夫档案" />
  }
  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data
  const levelGated = data.level < data.sellUnlockLevel

  async function handleSell(): Promise<void> {
    if (selectedStack === undefined) {
      return
    }
    setSelling(true)
    setSellError(null)
    try {
      const result = await callMock('job.farmer.sell', { slot: selectedStack.slot, count })
      setSellResult(result)
      // 卖出结果会改动 soldToday/单价曲线, 必须等提交真正完成再重查 job.farmer.state, 否则拿到的是旧值。
      query.reload()
    } catch (error) {
      setSellError(toError(error))
    } finally {
      setSelling(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="p-4">
        <span className="text-2x text-fg">农夫 Lv.{data.level}</span>
      </PixelFrame>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">卖菜</h2>
        <PixelFrame variant="panel" className="flex flex-col gap-4 p-4">
          {inventoryError === null ? null : (
            <div className="flex items-center gap-3">
              <p role="alert" className="text-1x text-danger">
                背包读取失败: {inventoryError.message}
              </p>
              <PixelButton size="sm" onClick={loadInventory}>
                重试
              </PixelButton>
            </div>
          )}
          {levelGated ? (
            <PixelBadge tone="warning">
              卖菜需要农夫 {data.sellUnlockLevel} 级 (当前 {data.level} 级)
            </PixelBadge>
          ) : /*
               读取失败也满足 inventory === null, 早先这一支只判 null, 于是失败时上面那条错误横幅与
               这里的"正在读取背包"同时挂着, 而加载态永远不会结束 —— 界面同时声称"出错了"和"还在读",
               玩家不知道该等还是该点重试。失败时把加载态让出去, 只留错误与重试。
             */
          world.mirror.inventory === null ? (
            inventoryError === null ? <PixelLoading label="正在读取背包" size="sm" /> : null
          ) : sellableStacks.length === 0 ? (
            <PixelEmpty title="背包里没有可出售的作物" hint="收购站只收本职业当前挂牌的作物" />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <PixelSelect
                  value={selectedSlot === null ? '' : String(selectedSlot)}
                  options={sellableStacks.map((stack) => ({
                    value: String(stack.slot),
                    label: `${names[stack.descriptionId] ?? stack.descriptionId} x${String(stack.count)} (槽位 ${String(stack.slot)})`,
                  }))}
                  onChange={(next) => {
                    setSelectedSlot(Number(next))
                    setCount(1)
                  }}
                />
                <PixelStepper
                  value={count}
                  onChange={setCount}
                  min={1}
                  max={selectedStack === undefined ? 1 : selectedStack.count}
                  disabled={selectedStack === undefined}
                />
                <PixelButton
                  tone="accent"
                  loading={selling}
                  disabled={selectedStack === undefined}
                  onClick={() => {
                    void handleSell()
                  }}
                >
                  出售
                </PixelButton>
              </div>
              {sellError === null ? null : (
                <p role="alert" className="text-1x text-danger">
                  {sellError.message}
                </p>
              )}
              {sellResult === null ? null : (
                <p className="flex flex-wrap items-center gap-1 text-1x text-success">
                  已售出 {sellResult.count} 件, 获得
                  <PixelCurrency amount={sellResult.credited} currency="credit" size="sm" />, 新单价
                  <PixelCurrency amount={sellResult.unitPriceAfter} currency="credit" size="sm" />
                </p>
              )}
            </>
          )}
        </PixelFrame>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">今日已售</h2>
        <PixelProgress
          value={data.soldToday}
          max={data.dailySoldCap}
          tone={data.soldToday >= data.dailySoldCap ? 'warning' : 'accent'}
          label={`${String(data.soldToday)}/${String(data.dailySoldCap)}`}
        />
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">收购曲线预览</h2>
        {crops.length === 0 ? (
          <PixelEmpty title="暂无作物收购数据" />
        ) : (
          <PixelTable
            columns={[
              {
                key: 'item',
                header: '作物',
                render: (row) => (
                  <span className="flex items-center gap-2">
                    <ItemIcon itemId={row.itemId} label={names[row.descriptionId] ?? row.descriptionId} />
                    <span>{names[row.descriptionId] ?? row.descriptionId}</span>
                  </span>
                ),
              },
              {
                key: 'unitPrice',
                header: '当前单价',
                render: (row) => <PixelCurrency amount={row.unitPrice} currency="credit" size="sm" />,
                sortValue: (row) => row.unitPrice,
              },
              {
                key: 'basePrice',
                header: '基准价',
                render: (row) => <PixelCurrency amount={row.basePrice} currency="credit" size="sm" />,
                sortValue: (row) => row.basePrice,
              },
              {
                key: 'soldToday',
                header: '今日已售',
                render: (row) => String(row.soldToday),
                sortValue: (row) => row.soldToday,
              },
            ]}
            rows={crops}
            rowKey={(row) => row.itemId}
          />
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">耕地五档</h2>
        <PixelTable
          columns={[
            { key: 'label', header: '档位', render: (row) => row.label },
            {
              key: 'value',
              header: '收购加成',
              render: (row) => formatStatValue(row.value, row.unit),
              sortValue: (row) => row.value,
            },
          ]}
          rows={data.farmlandTiers}
          rowKey={(row) => row.key}
        />
      </section>
    </div>
  )
}
