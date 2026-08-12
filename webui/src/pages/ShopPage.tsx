import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelInput,
  PixelLoading,
  PixelModal,
  PixelSelect,
  PixelStepper,
  PixelTable,
} from '../components/pixel'
import type { PixelSelectOption, PixelTableColumn } from '../components/pixel'
import { useItemNames } from '../lib/i18n'
import { callMock } from '../mock/handlers'
import type { PlannedShopDetailResult, PlannedShopEntry } from '../mock/planned'
import { useMockAction } from '../mock/useMockWorld'

/**
 * 系统商店目录与比价 (接线清单 H 组 · WOK-ChestShop 跨仓)。
 *
 * 依赖的假定契约 (均未接线, 走 mock/planned.ts):
 *   - shop.catalog  H1  全服告示牌商店目录。清单标注 BACKEND: AdminShopRegistry 是 private Map,
 *                   按 BlockPos 存且逐维度隔离, 需新增 public entries() 遍历全部 ServerLevel 才拿得到,
 *                   真服目前**没有任何聚合读取接口**, 本页数据纯 mock。
 *   - shop.detail   H2 + H4  单店详情 + 跨店比价。H2 (WRAP 跨 jar): recordAt(BlockPos) 已就绪但
 *                   handler 须写在主仓跨 jar 调用 ChestShop; H4 (BACKEND): 真服只有正向索引
 *                   BlockPos -> ShopRecord, 反向"同物品有哪些店在卖"的索引要新建。
 *   - shop.buy      H3  远程下单。清单原文: ShopTransaction.buy 逻辑完整且已挂主仓 IEconomyService,
 *                   但**只被玩家物理点击真实告示牌的路径调用**(内嵌 reach/tamper/冷却校验), 隔空下单
 *                   需要新规格 —— 这条 action 在真服是否会存在本身还没定, 本页的购买按钮建立在
 *                   "服务端未来会开一条隔空下单通道"这个假定之上, 是全页面假定成分最高的一处。
 *
 * 明确不做的部分: H5 (商店流水) 状态 NONE —— 每笔买卖只发一条聊天提示, 不落任何流水,
 * 本页不展示"历史成交"之类不存在数据源的列表。
 *
 * 中文输入: 商店目录的搜索框只按 itemId (英文命名空间字符串, 如 minecraft:diamond) 做子串匹配,
 * 与 bridge.mock 里 market.list 的过滤口径一致, 不涉及中文名搜索, 故用普通受控 PixelInput 即可,
 * 不需要 onRequestEdit 接口位 —— 本页没有任何字段要求输入中文自由文本。
 */

const DIMENSION_LABEL: Record<string, string> = {
  'minecraft:overworld': '主世界',
  'miningdim:mining': '矿洞维度',
}

function dimensionLabel(dimension: string): string {
  return DIMENSION_LABEL[dimension] ?? dimension
}

const CATALOG_BASE_COLUMNS: readonly PixelTableColumn<PlannedShopEntry>[] = [
  {
    key: 'dimension',
    header: '维度',
    render: (row) => dimensionLabel(row.dimension),
    sortValue: (row) => row.dimension,
  },
  {
    key: 'buy',
    header: '买入价',
    render: (row) => (row.buyPrice === null ? '—' : <PixelCurrency amount={row.buyPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.buyPrice ?? -1,
  },
  {
    key: 'sell',
    header: '卖出价',
    render: (row) => (row.sellPrice === null ? '—' : <PixelCurrency amount={row.sellPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.sellPrice ?? -1,
  },
  {
    key: 'stock',
    header: '库存',
    render: (row) => (row.stock === null ? '无限' : String(row.stock)),
    sortValue: (row) => row.stock ?? -1,
  },
]

const COMPARABLE_COLUMNS: readonly PixelTableColumn<PlannedShopEntry>[] = [
  {
    key: 'dimension',
    header: '维度',
    render: (row) => dimensionLabel(row.dimension),
    sortValue: (row) => row.dimension,
  },
  {
    key: 'pos',
    header: '坐标',
    render: (row) => `${String(row.pos.x)}, ${String(row.pos.y)}, ${String(row.pos.z)}`,
  },
  {
    key: 'buy',
    header: '买入价',
    render: (row) => (row.buyPrice === null ? '—' : <PixelCurrency amount={row.buyPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.buyPrice ?? -1,
  },
  {
    key: 'sell',
    header: '卖出价',
    render: (row) => (row.sellPrice === null ? '—' : <PixelCurrency amount={row.sellPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.sellPrice ?? -1,
  },
  {
    key: 'stock',
    header: '库存',
    render: (row) => (row.stock === null ? '无限' : String(row.stock)),
    sortValue: (row) => row.stock ?? -1,
  },
]

type DetailState =
  | { status: 'idle' }
  | { status: 'loading'; shopId: string }
  | { status: 'ready'; shopId: string; data: PlannedShopDetailResult }
  | { status: 'error'; shopId: string; message: string }

export function ShopPage(): ReactElement {
  const catalog = useMockAction('shop.catalog', {})
  const shops = catalog.status === 'ready' ? catalog.data.shops : []
  const displayNames = useItemNames(shops.map((entry) => entry.descriptionId))

  const [search, setSearch] = useState('')
  const [dimensionFilter, setDimensionFilter] = useState('all')
  const [detailState, setDetailState] = useState<DetailState>({ status: 'idle' })
  const [purchaseCount, setPurchaseCount] = useState(1)
  const [purchasing, setPurchasing] = useState(false)
  const [purchaseFeedback, setPurchaseFeedback] = useState<{ tone: 'success' | 'danger'; message: string } | null>(
    null,
  )

  async function refreshDetail(shopId: string): Promise<void> {
    try {
      const data = await callMock('shop.detail', { shopId })
      setDetailState({ status: 'ready', shopId, data })
      setPurchaseCount((current) => (data.shop.stock === null ? current : Math.min(current, Math.max(1, data.shop.stock))))
    } catch (error) {
      setDetailState({
        status: 'error',
        shopId,
        message: error instanceof Error ? error.message : String(error),
      })
    }
  }

  async function handleOpenDetail(shopId: string): Promise<void> {
    setDetailState({ status: 'loading', shopId })
    setPurchaseFeedback(null)
    setPurchaseCount(1)
    await refreshDetail(shopId)
  }

  function handleCloseDetail(): void {
    setDetailState({ status: 'idle' })
    setPurchaseFeedback(null)
  }

  async function handleBuy(shopId: string): Promise<void> {
    setPurchasing(true)
    try {
      const result = await callMock('shop.buy', { shopId, count: purchaseCount })
      await refreshDetail(shopId)
      catalog.reload()
      setPurchaseFeedback({
        tone: 'success',
        message: `购买成功: ${String(result.count)} 件, 花费 ${String(result.paidCredit)} 信用点`,
      })
    } catch (error) {
      setPurchaseFeedback({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setPurchasing(false)
    }
  }

  const dimensions = Array.from(new Set(shops.map((entry) => entry.dimension)))
  const dimensionOptions: readonly PixelSelectOption[] = [
    { value: 'all', label: '全部维度' },
    ...dimensions.map((dimension) => ({ value: dimension, label: dimensionLabel(dimension) })),
  ]

  const filteredShops = shops.filter((entry) => {
    const term = search.trim().toLowerCase()
    const matchesSearch = term === '' || entry.itemId.toLowerCase().includes(term)
    const matchesDimension = dimensionFilter === 'all' || entry.dimension === dimensionFilter
    return matchesSearch && matchesDimension
  })

  const catalogColumns: readonly PixelTableColumn<PlannedShopEntry>[] = [
    {
      key: 'item',
      header: '物品',
      render: (row) => (
        <span className="flex items-center gap-2">
          <ItemIcon itemId={row.itemId} label={displayNames[row.descriptionId] ?? row.descriptionId} scale={1} />
          <span>{displayNames[row.descriptionId] ?? row.descriptionId}</span>
        </span>
      ),
      sortValue: (row) => displayNames[row.descriptionId] ?? row.descriptionId,
    },
    ...CATALOG_BASE_COLUMNS,
  ]

  function renderDetailBody(): ReactElement {
    if (detailState.status === 'idle') {
      return <PixelEmpty title="尚未选择商店" hint="点击目录中的一行查看详情与比价" icon="info" />
    }
    if (detailState.status === 'loading') {
      return (
        <PixelFrame variant="panel" className="p-8">
          <PixelLoading label="正在读取商店详情" size="lg" />
        </PixelFrame>
      )
    }
    if (detailState.status === 'error') {
      const shopId = detailState.shopId
      return (
        <PixelError
          message={detailState.message}
          onRetry={() => {
            void refreshDetail(shopId)
          }}
        />
      )
    }

    const { shop, comparable } = detailState.data
    const name = displayNames[shop.descriptionId] ?? shop.descriptionId

    return (
      <div className="flex flex-col gap-4">
        <div className="flex items-center gap-3">
          <ItemIcon itemId={shop.itemId} label={name} scale={2} />
          <div className="flex flex-col">
            <span className="text-2x text-fg">{name}</span>
            <span className="text-1x text-muted">
              {dimensionLabel(shop.dimension)} · {shop.pos.x}, {shop.pos.y}, {shop.pos.z}
            </span>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          <PixelBadge tone={shop.buyPrice === null ? 'neutral' : 'success'}>
            买入{' '}
            {shop.buyPrice === null ? '不收' : <PixelCurrency amount={shop.buyPrice} currency="credit" size="sm" />}
          </PixelBadge>
          <PixelBadge tone={shop.sellPrice === null ? 'neutral' : 'info'}>
            卖出{' '}
            {shop.sellPrice === null ? '不收' : <PixelCurrency amount={shop.sellPrice} currency="credit" size="sm" />}
          </PixelBadge>
          <PixelBadge tone={shop.stock === null ? 'accent' : shop.stock === 0 ? 'danger' : 'neutral'}>
            库存 {shop.stock === null ? '无限' : shop.stock}
          </PixelBadge>
        </div>

        {purchaseFeedback === null ? null : (
          <PixelFrame variant="panel" tone={purchaseFeedback.tone} className="p-3">
            <p className="text-1x text-fg">{purchaseFeedback.message}</p>
          </PixelFrame>
        )}

        {shop.buyPrice === null ? (
          <p className="text-1x text-muted">本店不出售该物品, 无法购买。</p>
        ) : shop.stock === 0 ? (
          <p className="text-1x text-danger">库存已售罄。</p>
        ) : (
          <div className="flex flex-col gap-2">
            <PixelStepper value={purchaseCount} onChange={setPurchaseCount} min={1} max={shop.stock === null ? 999 : shop.stock} />
            <div className="flex items-center justify-between">
              <span className="text-1x text-muted">合计</span>
              <PixelCurrency amount={shop.buyPrice * purchaseCount} currency="credit" />
            </div>
            <PixelButton
              tone="accent"
              loading={purchasing}
              onClick={() => {
                void handleBuy(shop.shopId)
              }}
            >
              购买
            </PixelButton>
          </div>
        )}

        <div className="flex flex-col gap-2">
          <h3 className="text-2x text-fg">同物品其它商店比价</h3>
          {comparable.length === 0 ? (
            <PixelEmpty title="没有其它商店出售同一物品" icon="info" />
          ) : (
            <PixelTable columns={COMPARABLE_COLUMNS} rows={comparable} rowKey={(row) => row.shopId} className="h-64" />
          )}
        </div>
      </div>
    )
  }

  return (
    <section className="flex flex-col gap-6">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <header className="flex flex-col gap-2">
        <p className="text-1x text-muted">
          目录聚合自 WOK-ChestShop 跨仓告示牌商店, 真服当前无聚合读取接口 (H1 状态 BACKEND), 本页数据
          纯 mock; 点击一行可查看比价, 购买通道 (shop.buy, H3) 在真服是否会开放尚未确定。
        </p>
      </header>

      {catalog.status === 'loading' ? (
        <PixelFrame variant="panel" className="p-8">
          <PixelLoading label="正在读取系统商店目录" size="lg" />
        </PixelFrame>
      ) : catalog.status === 'error' ? (
        <PixelError message={catalog.error.message} onRetry={catalog.reload} />
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-1">
              <span className="text-1x text-muted">搜索物品 ID (如 minecraft:diamond)</span>
              <PixelInput value={search} onChange={setSearch} placeholder="minecraft:diamond" />
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-1x text-muted">维度</span>
              <PixelSelect value={dimensionFilter} options={dimensionOptions} onChange={setDimensionFilter} />
            </div>
          </div>

          {filteredShops.length === 0 ? (
            <PixelEmpty title="未找到匹配的商店" hint="尝试放宽物品 ID 或维度筛选" icon="search" />
          ) : (
            <PixelTable
              columns={catalogColumns}
              rows={filteredShops}
              rowKey={(row) => row.shopId}
              onRowClick={(row) => {
                void handleOpenDetail(row.shopId)
              }}
              className="h-96"
            />
          )}
        </>
      )}

      <PixelModal open={detailState.status !== 'idle'} title="商店详情" onClose={handleCloseDetail}>
        {renderDetailBody()}
      </PixelModal>
    </section>
  )
}
