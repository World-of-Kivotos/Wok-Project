import { InfoIcon, SearchIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  Currency,
  DataTable,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  ItemIcon,
  LoadingBlock,
  Panel,
  Stat,
  Tag,
  TextInput,
} from '@/components/kit'
import type { DataTableColumn, DropdownOption } from '@/components/kit'
import {
  Dialog,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogPopup,
  DialogTitle,
} from '@/components/ui/dialog'
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
 *
 * shop.buy 已拍板**不做**: 系统商店只做浏览与跨店比价, 不做隔空下单 —— ShopTransaction.buy 只接受玩家
 * 物理点击真实告示牌的那条路径 (内嵌 reach/tamper/冷却校验), 它不会有服务端实现。故本页没有购买入口,
 * 也刻意**不留一个禁用状态的购买按钮**: 灰着的按钮会让玩家以为将来会开放, 那比没有更糟。
 *
 * 明确不做的部分: H5 (商店流水) 状态 NONE —— 每笔买卖只发一条聊天提示, 不落任何流水,
 * 本页不展示"历史成交"之类不存在数据源的列表。
 *
 * 中文输入: 商店目录的搜索框只按 itemId (英文命名空间字符串, 如 minecraft:diamond) 做子串匹配,
 * 与 bridge.mock 里 market.list 的过滤口径一致, 不涉及中文名搜索, 故用普通受控 TextInput 即可,
 * 不需要 onRequestEdit 接口位 —— 本页没有任何字段要求输入中文自由文本。
 */

const DIMENSION_LABEL: Record<string, string> = {
  'minecraft:overworld': '主世界',
  'miningdim:mining': '矿洞维度',
}

function dimensionLabel(dimension: string): string {
  return DIMENSION_LABEL[dimension] ?? dimension
}

const CATALOG_BASE_COLUMNS: readonly DataTableColumn<PlannedShopEntry>[] = [
  {
    key: 'dimension',
    header: '维度',
    render: (row) => dimensionLabel(row.dimension),
    sortValue: (row) => row.dimension,
  },
  {
    key: 'buy',
    header: '买入价',
    numeric: true,
    render: (row) => (row.buyPrice === null ? '—' : <Currency amount={row.buyPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.buyPrice ?? -1,
  },
  {
    key: 'sell',
    header: '卖出价',
    numeric: true,
    render: (row) => (row.sellPrice === null ? '—' : <Currency amount={row.sellPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.sellPrice ?? -1,
  },
  {
    key: 'stock',
    header: '库存',
    numeric: true,
    render: (row) => (row.stock === null ? '无限' : String(row.stock)),
    sortValue: (row) => row.stock ?? -1,
  },
]

const COMPARABLE_COLUMNS: readonly DataTableColumn<PlannedShopEntry>[] = [
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
    numeric: true,
    render: (row) => (row.buyPrice === null ? '—' : <Currency amount={row.buyPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.buyPrice ?? -1,
  },
  {
    key: 'sell',
    header: '卖出价',
    numeric: true,
    render: (row) => (row.sellPrice === null ? '—' : <Currency amount={row.sellPrice} currency="credit" size="sm" />),
    sortValue: (row) => row.sellPrice ?? -1,
  },
  {
    key: 'stock',
    header: '库存',
    numeric: true,
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

  async function refreshDetail(shopId: string): Promise<void> {
    try {
      const data = await callMock('shop.detail', { shopId })
      setDetailState({ status: 'ready', shopId, data })
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
    await refreshDetail(shopId)
  }

  function handleCloseDetail(): void {
    setDetailState({ status: 'idle' })
  }

  const dimensions = Array.from(new Set(shops.map((entry) => entry.dimension)))
  const dimensionOptions: readonly DropdownOption<string>[] = [
    { value: 'all', label: '全部维度' },
    ...dimensions.map((dimension) => ({ value: dimension, label: dimensionLabel(dimension) })),
  ]

  const filteredShops = shops.filter((entry) => {
    const term = search.trim().toLowerCase()
    const matchesSearch = term === '' || entry.itemId.toLowerCase().includes(term)
    const matchesDimension = dimensionFilter === 'all' || entry.dimension === dimensionFilter
    return matchesSearch && matchesDimension
  })

  const catalogColumns: readonly DataTableColumn<PlannedShopEntry>[] = [
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
      return (
        <EmptyBlock
          hint="点击目录中的一行查看详情与比价"
          icon={<InfoIcon aria-hidden="true" />}
          title="尚未选择商店"
        />
      )
    }
    if (detailState.status === 'loading') {
      return <LoadingBlock label="正在读取商店详情" size="lg" />
    }
    if (detailState.status === 'error') {
      const shopId = detailState.shopId
      return (
        <ErrorBlock
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
            <span className="font-medium text-base text-foreground">{name}</span>
            <span className="text-muted-foreground text-xs">
              {dimensionLabel(shop.dimension)} · {shop.pos.x}, {shop.pos.y}, {shop.pos.z}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-3">
          <Stat
            label="买入价"
            value={shop.buyPrice === null ? '不售' : <Currency amount={shop.buyPrice} currency="credit" size="sm" />}
          />
          <Stat
            label="卖出价"
            value={shop.sellPrice === null ? '不收' : <Currency amount={shop.sellPrice} currency="credit" size="sm" />}
          />
          <Stat
            label="库存"
            value={
              shop.stock === null ? (
                <Tag tone="brand">无限</Tag>
              ) : shop.stock === 0 ? (
                <Tag tone="danger">售罄</Tag>
              ) : (
                String(shop.stock)
              )
            }
          />
        </div>

        {/*
          没有购买入口是设计结论而不是未完成: 系统商店只做浏览比价, 下单必须走玩家在游戏里点告示牌
          那条路 (那条路才有 reach/tamper/冷却校验)。这句话必须写在玩家看得到的地方, 否则"为什么不能买"
          会变成一条反复出现的提问。
        */}
        <p className="text-muted-foreground text-sm">
          {shop.buyPrice === null
            ? '这家店不卖这件物品。'
            : shop.stock === 0
              ? '库存已售罄。'
              : '要买请到这块告示牌前右键 —— 系统商店只能在游戏里当面交易, 平板只负责让你先比好价。'}
        </p>

        <div className="flex flex-col gap-2">
          <h3 className="font-medium text-foreground text-sm">同款在别的商店卖多少</h3>
          {comparable.length === 0 ? (
            <EmptyBlock icon={<InfoIcon aria-hidden="true" />} title="没有别的商店卖这件物品" />
          ) : (
            <div className="max-h-64 overflow-y-auto">
              <DataTable columns={COMPARABLE_COLUMNS} rowKey={(row) => row.shopId} rows={comparable} />
            </div>
          )}
        </div>
      </div>
    )
  }

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <p className="text-muted-foreground text-sm">
        全服告示牌商店的价目一览。点某一行可以看这件物品在别的商店卖多少钱。买卖仍要到告示牌前当面进行,
        这里只负责比价; 商店数据本身尚未接通真服。
      </p>

      {catalog.status === 'loading' ? (
        <Panel>
          <LoadingBlock label="正在读取系统商店目录" size="lg" />
        </Panel>
      ) : catalog.status === 'error' ? (
        <ErrorBlock message={catalog.error.message} onRetry={catalog.reload} />
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">搜索物品 (按英文 ID)</span>
              <TextInput onChange={setSearch} placeholder="minecraft:diamond" value={search} />
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">维度</span>
              <Dropdown onChange={setDimensionFilter} options={dimensionOptions} value={dimensionFilter} />
            </div>
          </div>

          {filteredShops.length === 0 ? (
            <EmptyBlock
              hint="换个搜索词, 或把维度切回全部"
              icon={<SearchIcon aria-hidden="true" />}
              title="未找到匹配的商店"
            />
          ) : (
            <Panel className="overflow-hidden" padded={false}>
              <div className="max-h-96 overflow-y-auto">
                <DataTable
                  columns={catalogColumns}
                  onRowClick={(row) => {
                    void handleOpenDetail(row.shopId)
                  }}
                  rowKey={(row) => row.shopId}
                  rows={filteredShops}
                />
              </div>
            </Panel>
          )}
        </>
      )}

      <Dialog
        onOpenChange={(next) => {
          if (!next) {
            handleCloseDetail()
          }
        }}
        open={detailState.status !== 'idle'}
      >
        <DialogPopup>
          <DialogHeader>
            <DialogTitle>商店详情</DialogTitle>
            <DialogDescription>这家店的价格, 以及别的商店同款卖多少</DialogDescription>
          </DialogHeader>
          <div className="flex min-h-0 flex-col overflow-y-auto px-6 pb-4">{renderDetailBody()}</div>
          <DialogFooter>
            <Button onClick={handleCloseDetail} variant="outline">
              关闭
            </Button>
          </DialogFooter>
        </DialogPopup>
      </Dialog>
    </section>
  )
}
