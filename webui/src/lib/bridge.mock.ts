/**
 * 假数据后端: 让整套 UI 脱离 Minecraft 就能在普通浏览器里跑起来改设计。
 *
 * 只在 dev server 且宿主未注入时被 bridge.ts 动态加载 (生产构建整块摇掉)。
 *
 * 它是什么: 契约形状的忠实复刻 + 一点点内存状态, 好让"挂单 -> 我的挂单 -> 撤单"这类闭环在浏览器里走得通。
 * 它不是什么: 服务端业务规则的第二实现。手续费率、开箱概率、等级门、OP 门控一律不复刻 ——
 * 复刻一份必然与 Java 侧漂移, 而漂移的假规则比没有规则更误导设计判断。
 *
 * 这条原则有两处**已知且刻意**的近似, 读数时不要当真值:
 *   1. market.place 的 listFee 用一个固定比例占位。真费率由服务端 MarketFee 按挂价对 V0 的偏离度算,
 *      量级可以差好几倍; 前端任何时候都以回执里的 listFee 为准, 不得照这个比例自己算给玩家看。
 *   2. case.open 的中奖皮肤按 openingId 哈希在皮肤表里均匀取, 不按 weights 抽。于是回执里的
 *      weights 是线上真值, 而 reel 与中奖结果的稀有度分布不是 —— 拿这一页评估"金色出现得多不多"必然错。
 * 两处都不改成真实现: 真实现会与 Java 侧无声漂移, 而漂移的假规则比明写的近似危险得多。
 *
 * 假数据刻意铺满边界值, 因为这些正是设计稿最容易漏掉的形态:
 *   - 空列表      market.history 恒空、market.list 翻到第 2 页即空
 *   - 超长中文名  枪匠枪管的 27 字名, 撞挂单行/物品格/表头三处截断
 *   - 极大数值    一条 unitPrice = 2^53-1 的挂单, 撞金额格式化与 Java long 的精度边界
 *   - 零余额      青辉石余额为 0 (负余额不可能, 服务端不允许)
 *   - 名字缺席    未改名物品没有 displayName 键、未翻译的键 i18n 原样返回
 *   - 注册表缺失  一件已卸载 mod 的遗留物品: market 回退成 itemId, admin 回退成空串 (两处口径本就不同)
 */

import type { WebUiActionName } from './actions'
import { SERVER_ACTIONS } from './actions'
import type { PayloadOf, ResultOf } from './bridge'
import { SERVER_FAILURE_CODE, WebUiCallError } from './bridge'
import type {
  AdminItemEntry,
  AdminListItemsPayload,
  AdminListItemsResult,
  AdminSetBaseValuePayload,
  AdminSetBaseValueResult,
  BaseValueSource,
  CaseApplyPayload,
  CaseApplyResult,
  CaseOpenPayload,
  CaseOpenResult,
  CaseOwnedAsset,
  CaseRarityWeight,
  CaseSkinSummary,
  CaseStateResult,
  CategoryLeafNode,
  CategoryNode,
  ClientI18nPayload,
  ClientI18nResult,
  ClientPlayCaseSoundPayload,
  ClientPlayCaseSoundResult,
  MarketBaseValuePayload,
  MarketBaseValueResult,
  MarketBuyPayload,
  MarketBuyResult,
  MarketCancelPayload,
  MarketCancelResult,
  MarketCategoriesResult,
  MarketHistoryPayload,
  MarketHistoryResult,
  MarketListPayload,
  MarketListResult,
  MarketListing,
  MarketMineResult,
  MarketPlacePayload,
  MarketPlaceResult,
  PlayerInventoryItem,
  PlayerInventoryResult,
  PlayerWalletResult,
  SystemEchoPayload,
  SystemEchoResult,
} from './types'

/** 假往返延迟: 太快会让 loading 态在设计稿里根本看不见, 太慢又难用。 */
const MOCK_LATENCY_MS = 90

const MOCK_PLAYER_NAME = '测试员_Mock'

/** 全部相对时间的基准, 模块加载时定一次 —— 页面存活期内时间戳不漂移, 截图可复现。 */
const NOW = Date.now()

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })
}

/** 服务端通用异常路径: 只有一句 message, 没有稳定 errorCode (缺口 A10 尚未做错误码体系)。 */
function plainFailure(action: WebUiActionName, message: string): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, null)
}

/** 服务端业务拒绝路径 (WebUiBusinessException), 带稳定机器码。 */
function businessFailure(
  action: WebUiActionName,
  errorCode: string,
  message: string,
  retrySameOpeningId: boolean,
): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, { errorCode, retrySameOpeningId })
}

/** Java 侧按 UTF-16 码元比较, 这里同口径 (localeCompare 会按语言习惯重排, 与服务端顺序对不上)。 */
function compareIds(left: string, right: string): number {
  if (left < right) {
    return -1
  }
  if (left > right) {
    return 1
  }
  return 0
}

// ============================================================
// 静态物品表
// ============================================================

type MockItemDef = {
  itemId: string
  /**
   * 该物品还在不在注册表里。false 复刻"卸载 mod 后遗留的历史挂单":
   * 服务端此时 market 侧回退成 itemId、admin 侧回退成空串, 两处口径本就不同, 前端两边都要能显示。
   */
  registered: boolean
  descriptionId: string
  top: 'ores' | 'weapons' | 'ammo' | 'gear' | 'food' | 'other'
  sub: 'ore' | 'ingot' | 'gem' | null
}

const MOCK_ITEMS: readonly MockItemDef[] = [
  {
    itemId: 'minecraft:diamond',
    registered: true,
    descriptionId: 'item.minecraft.diamond',
    top: 'ores',
    sub: 'gem',
  },
  {
    itemId: 'miningdim:azurite',
    registered: true,
    descriptionId: 'item.miningdim.azurite',
    top: 'ores',
    sub: 'gem',
  },
  {
    itemId: 'minecraft:gold_ingot',
    registered: true,
    descriptionId: 'item.minecraft.gold_ingot',
    top: 'ores',
    sub: 'ingot',
  },
  {
    itemId: 'minecraft:netherite_scrap',
    registered: true,
    descriptionId: 'item.minecraft.netherite_scrap',
    top: 'ores',
    sub: 'ingot',
  },
  {
    // 方块类物品: 翻译键前缀是 block. 而非 item., 正是前端推不出翻译键、必须收服务端 descriptionId 的原因。
    itemId: 'minecraft:iron_ore',
    registered: true,
    descriptionId: 'block.minecraft.iron_ore',
    top: 'ores',
    sub: 'ore',
  },
  {
    // 超长中文名边界。
    itemId: 'miningdim:plate_armor_banshee_atacs_au',
    registered: true,
    descriptionId: 'item.miningdim.plate_armor_banshee_atacs_au',
    top: 'weapons',
    sub: null,
  },
  {
    // 第三方 mod 物品: 贴图既不在本仓库也不在原版镜像站, 是 J1 未决的那一类, 前端应落像素占位块。
    itemId: 'tacz:modern_kinetic_gun',
    registered: true,
    descriptionId: 'item.tacz.modern_kinetic_gun',
    top: 'weapons',
    sub: null,
  },
  {
    itemId: 'minecraft:arrow',
    registered: true,
    descriptionId: 'item.minecraft.arrow',
    top: 'ammo',
    sub: null,
  },
  {
    itemId: 'minecraft:diamond_chestplate',
    registered: true,
    descriptionId: 'item.minecraft.diamond_chestplate',
    top: 'gear',
    sub: null,
  },
  {
    itemId: 'minecraft:wheat',
    registered: true,
    descriptionId: 'item.minecraft.wheat',
    top: 'food',
    sub: null,
  },
  {
    itemId: 'removedmod:ghost_item',
    registered: false,
    descriptionId: '',
    top: 'other',
    sub: null,
  },
]

function requireItem(itemId: string): MockItemDef {
  const item = MOCK_ITEMS.find((candidate) => candidate.itemId === itemId)
  if (item === undefined) {
    throw new Error(`mock 数据缺陷: 未登记的 itemId ${itemId}`)
  }
  return item
}

/** market 侧的 descriptionId 回退: 注册表取不到时回 itemId 本身 (MarketActions.listingJson)。 */
function marketDescriptionId(item: MockItemDef): string {
  return item.registered ? item.descriptionId : item.itemId
}

/** admin 侧的 descriptionId 回退: 注册表取不到时回空字符串 (MarketAdminActions.LIST_ITEMS)。 */
function adminDescriptionId(item: MockItemDef): string {
  return item.registered ? item.descriptionId : ''
}

/** 翻译键 -> 中文名。故意不覆盖全部键: 缺的那些走"原样返回键"的原版回退路径。 */
const I18N_NAMES: Readonly<Record<string, string>> = {
  'item.minecraft.diamond': '钻石',
  'item.miningdim.azurite': '青辉石',
  'item.minecraft.gold_ingot': '金锭',
  'item.minecraft.netherite_scrap': '下界合金碎片',
  'block.minecraft.iron_ore': '铁矿石',
  // 超长中文名边界 (45 字), 取自 lang/zh_cn.json 的真实条目。
  'item.miningdim.plate_armor_banshee_atacs_au': 'Shellback Tactical Banshee 防弹背心（A-Tacs AU 迷彩）',
  // NBT 变体件: Item 级键解出来是"枪匠零件"(195 种共用), 真正区分它们的是下面 nameParts 用的两个键。
  'item.miningdim.gunsmith_part': '枪匠零件',
  'gunsmith.variant.gehenna_high_speed_gas': '格赫娜高速导气',
  'gunsmith.quality.legendary': '传奇',
  'item.tacz.modern_kinetic_gun': '现代动能枪械',
  'item.minecraft.arrow': '箭',
  'item.minecraft.diamond_chestplate': '钻石胸甲',
  'item.minecraft.wheat': '小麦',
}

// ============================================================
// 可变内存状态 (只为让 UI 闭环走得通, 不承载任何业务规则)
// ============================================================

/**
 * 青辉石取 3 箱的量 (CASE_AZURE_COST x 3)。这不是随手填的:
 *
 * 开箱在服务端要同时扣 CREDIT 与 AZURE, 给 0 会让 case.open 在 mock 里第一次就被 INSUFFICIENT_FUNDS 挡住,
 * 整个开箱界面无从预览; 给一个大数则永远走不到余额不足那一态。取恰好 3 箱, 于是同一份 mock 里
 * "开得动 -> 开到第 4 箱不够了 -> 青辉石余额为 0 的展示形态" 三种情况依次都能走到, 一个都不缺。
 * 负余额服务端不可能产生, 故不造。
 */
const wallet: PlayerWalletResult = { credit: 1_234_567, azure: 90 }

const inventory: PlayerInventoryItem[] = [
  { slot: 0, itemId: 'minecraft:diamond', descriptionId: 'item.minecraft.diamond', count: 64 },
  { slot: 1, itemId: 'miningdim:azurite', descriptionId: 'item.miningdim.azurite', count: 3 },
  {
    slot: 4,
    itemId: 'miningdim:plate_armor_banshee_atacs_au',
    descriptionId: 'item.miningdim.plate_armor_banshee_atacs_au',
    count: 1,
  },
  {
    // 唯一带 displayName 的格位 (铁砧改名); 其余格位该键整体缺席, 不是空串。
    slot: 8,
    itemId: 'minecraft:diamond_chestplate',
    descriptionId: 'item.minecraft.diamond_chestplate',
    count: 1,
    displayName: '「初火」试作型胸甲',
  },
  { slot: 17, itemId: 'minecraft:wheat', descriptionId: 'item.minecraft.wheat', count: 1 },
  // 末槽位: 36 槽的最后一格, 撞背包网格的边界渲染。
  { slot: 35, itemId: 'minecraft:arrow', descriptionId: 'item.minecraft.arrow', count: 64 },
]

let nextListingId = 1006

function makeListing(
  id: number,
  sellerName: string,
  itemId: string,
  count: number,
  unitPrice: number,
  ageMinutes: number,
): MarketListing {
  const item = requireItem(itemId)
  return {
    id,
    sellerName,
    itemId,
    descriptionId: marketDescriptionId(item),
    count,
    unitPrice,
    total: unitPrice * count,
    createdAt: NOW - ageMinutes * 60_000,
  }
}

const listings: MarketListing[] = [
  makeListing(1001, '矿工阿建', 'minecraft:diamond', 12, 480, 35),
  makeListing(1002, MOCK_PLAYER_NAME, 'miningdim:plate_armor_banshee_atacs_au', 1, 88_000, 140),
  // 单价 1 的整叠白菜价, 与下面的天价挂单一起压住金额列的两端。
  makeListing(1003, '拍卖狂魔', 'removedmod:ghost_item', 64, 1, 720),
  // 极大数值边界: Java long 到 2^53-1 之后 JSON.parse 就开始丢精度, 前端在这条上不得静默截断。
  makeListing(1004, '鲸鱼玩家', 'minecraft:netherite_scrap', 1, Number.MAX_SAFE_INTEGER, 5),
  makeListing(1005, MOCK_PLAYER_NAME, 'tacz:modern_kinetic_gun', 2, 12_500, 900),
]

const BASE_VALUE_PRESETS: ReadonlyMap<string, number> = new Map([
  ['minecraft:diamond', 500],
  ['minecraft:gold_ingot', 120],
  ['minecraft:netherite_scrap', 3000],
  ['minecraft:wheat', 6],
])

const baseValueOverrides = new Map<string, number>([['miningdim:azurite', 4200]])

function resolveBaseValue(itemId: string): { v0: number | null; source: BaseValueSource } {
  const override = baseValueOverrides.get(itemId)
  if (override !== undefined) {
    return { v0: override, source: 'override' }
  }
  const preset = BASE_VALUE_PRESETS.get(itemId)
  if (preset !== undefined) {
    return { v0: preset, source: 'preset' }
  }
  return { v0: null, source: 'none' }
}

let serverTick = 1_284_390

// ============================================================
// 分类树 (复刻 MarketCategoryTree: 固定顶层序、叶子按 itemId 字典序、空分支不输出)
// ============================================================

type MockCategoryDef = {
  id: MockItemDef['top']
  label: string
  subs: readonly { id: NonNullable<MockItemDef['sub']>; label: string }[]
}

const CATEGORY_TREE: readonly MockCategoryDef[] = [
  {
    id: 'ores',
    label: '矿物与材料',
    subs: [
      { id: 'ore', label: '原矿与矿石' },
      { id: 'ingot', label: '锭与材料' },
      { id: 'gem', label: '宝石' },
    ],
  },
  { id: 'weapons', label: '武器', subs: [] },
  { id: 'ammo', label: '弹药', subs: [] },
  { id: 'gear', label: '装备', subs: [] },
  { id: 'food', label: '食物', subs: [] },
  { id: 'other', label: '其他', subs: [] },
]

function categoryLeaves(top: MockItemDef['top'], sub: MockItemDef['sub']): CategoryLeafNode[] {
  return MOCK_ITEMS.filter((item) => item.top === top && item.sub === sub)
    .sort((left, right) => compareIds(left.itemId, right.itemId))
    .map((item) => ({
      id: `i_${item.itemId.replace(':', '_')}`,
      label: marketDescriptionId(item),
      itemId: item.itemId,
    }))
}

function buildCategories(): MarketCategoriesResult {
  const roots: CategoryNode[] = []
  for (const top of CATEGORY_TREE) {
    const children: CategoryNode[] = []
    for (const sub of top.subs) {
      const leaves = categoryLeaves(top.id, sub.id)
      if (leaves.length > 0) {
        children.push({ id: sub.id, label: sub.label, children: leaves })
      }
    }
    children.push(...categoryLeaves(top.id, null))
    if (children.length > 0) {
      roots.push({ id: top.id, label: top.label, children })
    }
  }
  return roots
}

// ============================================================
// 开箱 (取自真实 CaseCatalog 的子集, 权重是线上真值)
// ============================================================

const CASE_ID = 'founders'
const CASE_DISPLAY_NAME = '创始武器箱'
const CASE_CREDIT_COST = 5000
const CASE_AZURE_COST = 30
const REEL_LENGTH = 40
/** 服务端权威落点: 前端动画必须停在这一格, 不许自己抽。 */
const REEL_STOP_INDEX = 35
/** 服务端 OWNED_RESPONSE_LIMIT: owned 超过它就截断, 真实总数只看 ownedTotal。 */
const OWNED_RESPONSE_LIMIT = 60

function makeSkin(skinId: string, displayName: string, rarity: CaseSkinSummary['rarity'], gunPath: string): CaseSkinSummary {
  return {
    skinId,
    displayName,
    rarity,
    gunId: `tacz:${gunPath}`,
    displayId: `miningdim:case_${skinId}_display`,
  }
}

const MOCK_SKINS: readonly CaseSkinSummary[] = [
  makeSkin('arctic_grid', '极地网格', 'blue', 'm4a1'),
  makeSkin('copper_wasp', '赤铜胡蜂', 'blue', 'ak47'),
  makeSkin('violet_reactor', '紫晶反应堆', 'purple', 'aug'),
  makeSkin('aurora_protocol', '极光协议', 'pink', 'hk416d'),
  makeSkin('vermilion_sovereign', '朱雀君临', 'red', 'ai_awp'),
  makeSkin('gilded_omen', '鎏金神谕', 'gold', 'timeless50'),
]

/** CaseWeights.DEFAULT 的真值, 总和恒 100000。 */
const CASE_WEIGHTS: readonly CaseRarityWeight[] = [
  { rarity: 'blue', weight: 79_110 },
  { rarity: 'purple', weight: 15_500 },
  { rarity: 'pink', weight: 4_000 },
  { rarity: 'red', weight: 990 },
  { rarity: 'gold', weight: 400 },
]

function pickSkin(seed: number): CaseSkinSummary {
  const skin = MOCK_SKINS[Math.abs(seed) % MOCK_SKINS.length]
  if (skin === undefined) {
    throw new Error('mock 数据缺陷: 皮肤表为空')
  }
  return skin
}

/** 定长伪 UUID: 形状对得上服务端的 UUID 字符串, 且同一序号永远同一个值, 截图可复现。 */
function mockUuid(index: number): string {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

function makeAsset(skin: CaseSkinSummary, index: number): CaseOwnedAsset {
  return {
    ...skin,
    assetId: mockUuid(index),
    acquiredAt: NOW - index * 3_600_000,
    // 服务端当前恒 0 (7 天交易锁未启用), 不造非零值免得前端照着写死一套倒计时。
    tradeLockedUntil: 0,
  }
}

/** 63 件 > 截断上限 60: 让 owned/ownedTotal 的差值在 UI 上真实存在。 */
const ownedAssets: CaseOwnedAsset[] = Array.from({ length: 63 }, (_, index) =>
  makeAsset(pickSkin(index), index),
)

const openings = new Map<string, CaseOpenResult>()

function hashOf(text: string): number {
  let hash = 0
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) | 0
  }
  return Math.abs(hash)
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// ============================================================
// 各 action 的假实现
// ============================================================

function mockEcho(payload: SystemEchoPayload): SystemEchoResult {
  if (typeof payload.msg !== 'string') {
    throw plainFailure('system.echo', 'missing msg in payload')
  }
  serverTick += 37
  return { player: MOCK_PLAYER_NAME, echo: payload.msg, serverTick }
}

function mockInventory(): PlayerInventoryResult {
  return { items: inventory.map((item) => ({ ...item })) }
}

function mockMarketList(payload: MarketListPayload): MarketListResult {
  const page = payload.page === undefined ? 0 : payload.page
  const pageSize = payload.pageSize === undefined ? 20 : payload.pageSize
  const query = payload.query === undefined || payload.query === null ? '' : payload.query.toLowerCase()
  // 只按 itemId 匹配: MarketDaoSqlite 的过滤条件只有 `item_id LIKE ?`, 不碰翻译键。
  // 顺手多匹配 descriptionId 会让设计侧以为"搜中文名/翻译键能搜到东西", 而真服搜出来是空的。
  const matched = listings.filter(
    (listing) => query === '' || listing.itemId.toLowerCase().includes(query),
  )
  // 服务端默认 sort="created_at" 不在 DAO 白名单, 会静默落回 newest —— 这里同样落 newest, 不"纠正"它。
  const sorted = [...matched].sort((left, right) => {
    const sort: string | undefined = payload.sort
    if (sort === 'price_asc') {
      return left.unitPrice - right.unitPrice
    }
    if (sort === 'price_desc') {
      return right.unitPrice - left.unitPrice
    }
    return right.createdAt - left.createdAt
  })
  const offset = page * pageSize
  return {
    listings: sorted.slice(offset, offset + pageSize).map((listing) => ({ ...listing })),
    page,
    pageSize,
  }
}

function mockMarketPlace(payload: MarketPlacePayload): MarketPlaceResult {
  const stack = inventory.find((item) => item.slot === payload.slot)
  if (stack === undefined) {
    throw plainFailure('market.place', `槽位 ${payload.slot} 是空的, 无法挂单`)
  }
  if (payload.count <= 0 || payload.count > stack.count) {
    throw plainFailure('market.place', `挂单数量 ${payload.count} 超出该槽位持有量 ${stack.count}`)
  }
  if (payload.unitPrice <= 0) {
    throw plainFailure('market.place', '单价必须为正整数')
  }
  // 4% 只是个能看见 sink 存在的占位比例; 真实费率由服务端 MarketFee 按偏离度算, 前端永远以回执为准。
  const listFee = Math.max(1, Math.round(payload.unitPrice * payload.count * 0.04))
  if (listFee > wallet.credit) {
    throw plainFailure('market.place', '信用点不足以支付挂单手续费')
  }
  wallet.credit -= listFee
  stack.count -= payload.count
  if (stack.count === 0) {
    inventory.splice(inventory.indexOf(stack), 1)
  }
  nextListingId += 1
  listings.push(
    makeListing(nextListingId, MOCK_PLAYER_NAME, stack.itemId, payload.count, payload.unitPrice, 0),
  )
  return { listingId: nextListingId, listFee }
}

function mockMarketBuy(payload: MarketBuyPayload): MarketBuyResult {
  const listing = listings.find((candidate) => candidate.id === payload.listingId)
  if (listing === undefined) {
    throw plainFailure('market.buy', `挂单 ${payload.listingId} 不存在或已成交`)
  }
  const requested = payload.count === undefined ? 0 : payload.count
  // 与 MarketEngine.buy 同口径: <=0 一律买整单剩余。曾只判 ===0, 于是 count=-1 会一路算出负总价,
  // 反过来给钱包加钱、给挂单加量、往背包塞负数 —— mock 造出服务端不可能出现的状态比没有 mock 更误导。
  const count = requested <= 0 ? listing.count : requested
  if (count > listing.count) {
    throw plainFailure('market.buy', `挂单仅剩 ${listing.count} 件, 买不到 ${count} 件`)
  }
  const total = listing.unitPrice * count
  if (total > wallet.credit) {
    throw plainFailure('market.buy', '信用点不足')
  }
  wallet.credit -= total
  listing.count -= count
  listing.total = listing.unitPrice * listing.count
  if (listing.count === 0) {
    listings.splice(listings.indexOf(listing), 1)
  }
  depositToInventory('market.buy', listing.itemId, count)
  // fee 恒 0: 买家侧手续费当前不收 (卖家已在挂单时付过), 与服务端回执一致。
  return { ok: true, itemId: listing.itemId, count, total, fee: 0 }
}

function mockMarketCancel(payload: MarketCancelPayload): MarketCancelResult {
  const listing = listings.find((candidate) => candidate.id === payload.listingId)
  if (listing === undefined || listing.sellerName !== MOCK_PLAYER_NAME) {
    throw plainFailure('market.cancel', `挂单 ${payload.listingId} 不是你的挂单`)
  }
  listings.splice(listings.indexOf(listing), 1)
  depositToInventory('market.cancel', listing.itemId, listing.count)
  // 无 fee 字段: 挂单手续费不退。
  return { ok: true, itemId: listing.itemId, count: listing.count }
}

/**
 * 把物品放回背包 (买入与撤单共用)。背包满时抛错而非静默丢弃 ——
 * 服务端在这条路径上同样是拒绝执行, 前端要能看见这个拒绝。
 */
function depositToInventory(action: WebUiActionName, itemId: string, count: number): void {
  const stack = inventory.find((item) => item.itemId === itemId)
  if (stack !== undefined) {
    stack.count += count
    return
  }
  for (let slot = 0; slot < 36; slot += 1) {
    if (!inventory.some((item) => item.slot === slot)) {
      inventory.push({
        slot,
        itemId,
        descriptionId: marketDescriptionId(requireItem(itemId)),
        count,
      })
      return
    }
  }
  throw plainFailure(action, '背包已满, 无法取回物品')
}

function mockMarketMine(): MarketMineResult {
  return {
    listings: listings
      .filter((listing) => listing.sellerName === MOCK_PLAYER_NAME)
      .map((listing) => ({ ...listing })),
  }
}

function mockMarketHistory(payload: MarketHistoryPayload): MarketHistoryResult {
  // 恒空不是偷懒: MarketDao 至今没有按玩家查 transactions 的方法, 服务端本身就只能回空数组。
  return { transactions: [], page: payload.page === undefined ? 0 : payload.page }
}

function mockBaseValue(payload: MarketBaseValuePayload): MarketBaseValueResult {
  const resolved = resolveBaseValue(payload.itemId)
  return { itemId: payload.itemId, v0: resolved.v0, source: resolved.source }
}

function mockAdminSetBaseValue(payload: AdminSetBaseValuePayload): AdminSetBaseValueResult {
  // 不复刻 OP 门控: mock 里没有权限体系, 一律放行, 好让管理页能被设计与预览。
  baseValueOverrides.set(payload.itemId, payload.v0)
  return { ok: true, itemId: payload.itemId, v0: payload.v0 }
}

function mockAdminListItems(payload: AdminListItemsPayload): AdminListItemsResult {
  const query = payload.query === undefined ? '' : payload.query.toLowerCase()
  const page = payload.page === undefined ? 0 : Math.max(0, payload.page)
  const requestedSize = payload.pageSize === undefined ? 50 : payload.pageSize
  const pageSize = Math.min(200, Math.max(1, requestedSize))
  const matched = MOCK_ITEMS.filter((item) => item.itemId.toLowerCase().includes(query))
  const offset = page * pageSize
  const items: AdminItemEntry[] = matched.slice(offset, offset + pageSize).map((item) => {
    const resolved = resolveBaseValue(item.itemId)
    return {
      itemId: item.itemId,
      descriptionId: adminDescriptionId(item),
      /*
       * 无锚时**不带 v0 键**, 而不是给 null: MarketAdminActions 用的是默认 Gson (serializeNulls=false),
       * 它写进去的 JsonNull 在写出阶段连键一起被丢掉。这与 market.baseValue (GSON_NULLS, 保留显式 null)
       * 恰好相反, 是前端最容易两处套同一种判存在写法的地方, 故 mock 必须把这个差别原样造出来。
       */
      ...(resolved.v0 === null ? {} : { v0: resolved.v0 }),
      source: resolved.source,
    }
  })
  return { items, page, pageSize, total: matched.length }
}

function mockCaseState(): CaseStateResult {
  const ownedCounts = new Map<string, number>()
  for (const asset of ownedAssets) {
    const current = ownedCounts.get(asset.skinId)
    ownedCounts.set(asset.skinId, current === undefined ? 1 : current + 1)
  }
  return {
    enabled: true,
    caseId: CASE_ID,
    displayName: CASE_DISPLAY_NAME,
    creditCost: CASE_CREDIT_COST,
    azureCost: CASE_AZURE_COST,
    wallet: { ...wallet },
    weights: CASE_WEIGHTS.map((entry) => ({ ...entry })),
    skins: MOCK_SKINS.map((skin) => {
      const owned = ownedCounts.get(skin.skinId)
      return { ...skin, ownedCount: owned === undefined ? 0 : owned }
    }),
    owned: ownedAssets.slice(0, OWNED_RESPONSE_LIMIT).map((asset) => ({ ...asset })),
    ownedTotal: ownedAssets.length,
  }
}

function mockCaseOpen(payload: CaseOpenPayload): CaseOpenResult {
  if (typeof payload.openingId !== 'string' || !UUID_PATTERN.test(payload.openingId)) {
    throw businessFailure('case.open', 'INVALID_REQUEST', '字段 openingId 不是有效 UUID', false)
  }
  if (payload.caseId !== undefined && payload.caseId !== CASE_ID) {
    throw businessFailure('case.open', 'INVALID_REQUEST', 'caseId 无效', false)
  }
  const replay = openings.get(payload.openingId)
  if (replay !== undefined) {
    // 断线重连复播: 同一 openingId 拿回同一结果且不再扣费, 钱包取当下的实时值。
    return { ...replay, replayed: true, wallet: { ...wallet } }
  }
  /*
   * 双货币一起校验一起扣: CaseOpeningService.open 在 reserve 之前同时要求 credit 与 azure 足额
   * (`available.credit() < requiredCredit || available.azure() < requiredAzure`)。mock 早先只看 credit,
   * 于是假钱包 azure=0 时开箱在 mock 里成功、在真服必被 INSUFFICIENT_FUNDS 拒绝 —— 这正是设计侧
   * 最容易照着画出一个不存在的成功流程的地方。
   * retrySameOpeningId 取 false 也是照抄服务端: 该异常构造处传的就是 false, 扣费尚未发生, 重试必须换新 id。
   */
  if (wallet.credit < CASE_CREDIT_COST || wallet.azure < CASE_AZURE_COST) {
    throw businessFailure(
      'case.open',
      'INSUFFICIENT_FUNDS',
      `余额不足：需要 ${String(CASE_CREDIT_COST)} CREDIT 和 ${String(CASE_AZURE_COST)} AZURE`,
      false,
    )
  }
  wallet.credit -= CASE_CREDIT_COST
  wallet.azure -= CASE_AZURE_COST
  const seed = hashOf(payload.openingId)
  const winner = pickSkin(seed)
  const reel = Array.from({ length: REEL_LENGTH }, (_, index) => pickSkin(seed + index * 7))
  // stopIndex 那一格必须就是中奖皮肤, 否则动画停下时显示的和 result 对不上。
  reel[REEL_STOP_INDEX] = winner
  const asset = makeAsset(winner, ownedAssets.length)
  ownedAssets.unshift(asset)
  const result: CaseOpenResult = {
    openingId: payload.openingId,
    replayed: false,
    stopIndex: REEL_STOP_INDEX,
    wallet: { ...wallet },
    result: asset,
    reel,
  }
  openings.set(payload.openingId, result)
  return result
}

function mockCaseApply(payload: CaseApplyPayload): CaseApplyResult {
  if (typeof payload.assetId !== 'string' || !UUID_PATTERN.test(payload.assetId)) {
    throw businessFailure('case.apply', 'INVALID_REQUEST', '字段 assetId 不是有效 UUID', false)
  }
  const asset = ownedAssets.find((candidate) => candidate.assetId === payload.assetId)
  if (asset === undefined) {
    throw businessFailure('case.apply', 'ASSET_NOT_OWNED', '该皮肤资产不属于你', false)
  }
  // 回执刻意比 case.state 的资产对象窄, 别顺手把 rarity/displayName 加回来。
  return {
    applied: true,
    assetId: asset.assetId,
    skinId: asset.skinId,
    gunId: asset.gunId,
    displayId: asset.displayId,
  }
}

function mockI18n(payload: ClientI18nPayload): ClientI18nResult {
  const keys = Array.isArray(payload.keys) ? payload.keys : []
  const names: Record<string, string> = {}
  for (const key of keys) {
    const translated = I18N_NAMES[key]
    // 原版 I18n.get 缺翻译时回退为键本身, 不报错 —— 前端据此判"未翻译"。
    names[key] = translated === undefined ? key : translated
  }
  return { names }
}

const CASE_SOUND_CUES: readonly string[] = [
  'unlock',
  'open',
  'tick',
  'reveal_blue',
  'reveal_purple',
  'reveal_pink',
  'reveal_red',
  'reveal_gold',
]

function mockPlayCaseSound(payload: ClientPlayCaseSoundPayload): ClientPlayCaseSoundResult {
  if (!CASE_SOUND_CUES.includes(payload.cue)) {
    // 宿主对白名单外的 cue 是 callback.failure(-1), 不是服务端失败信封, 故 code 用 -1。
    throw new WebUiCallError('client.playCaseSound', -1, `unknown case sound cue: ${payload.cue}`, null)
  }
  return { played: true }
}

// ============================================================
// 派发
// ============================================================

/**
 * payload 在此处按 action 收窄。这层的 as 是必要的: 泛型 A 在函数体内无法把 PayloadOf<A> 分解成具体分支,
 * 而每个 case 的实参类型由 mockCall 的签名在调用点保证, 转换本身不引入运行期风险。
 */
function resolveMock(action: WebUiActionName, payload: unknown): unknown {
  switch (action) {
    case 'system.echo':
      return mockEcho(payload as SystemEchoPayload)
    case 'system.handshake':
      // 与前端声明清单逐字一致 —— mock 下不该出现契约漂移告警, 否则每次本地开发都在喊狼来了。
      return { modVersion: '0.0.0-mock', actions: [...SERVER_ACTIONS] }
    case 'player.inventory':
      return mockInventory()
    case 'player.wallet':
      return { ...wallet }
    case 'market.list':
      return mockMarketList(payload as MarketListPayload)
    case 'market.place':
      return mockMarketPlace(payload as MarketPlacePayload)
    case 'market.buy':
      return mockMarketBuy(payload as MarketBuyPayload)
    case 'market.cancel':
      return mockMarketCancel(payload as MarketCancelPayload)
    case 'market.mine':
      return mockMarketMine()
    case 'market.history':
      return mockMarketHistory(payload as MarketHistoryPayload)
    case 'market.baseValue':
      return mockBaseValue(payload as MarketBaseValuePayload)
    case 'market.categories':
      return buildCategories()
    case 'admin.setBaseValue':
      return mockAdminSetBaseValue(payload as AdminSetBaseValuePayload)
    case 'admin.listItems':
      return mockAdminListItems(payload as AdminListItemsPayload)
    case 'case.state':
      return mockCaseState()
    case 'case.open':
      return mockCaseOpen(payload as CaseOpenPayload)
    case 'case.apply':
      return mockCaseApply(payload as CaseApplyPayload)
    case 'client.i18n':
      return mockI18n(payload as ClientI18nPayload)
    case 'client.playCaseSound':
      return mockPlayCaseSound(payload as ClientPlayCaseSoundPayload)
    default: {
      // 契约表新增 action 而这里忘了实现时, 本行编译失败 (action 被收窄成 never)。
      const unhandled: never = action
      throw new Error(`mock 未实现的 action: ${String(unhandled)}`)
    }
  }
}

/** bridge.ts 在桥缺失时转发到这里; 调用方感知不到差别, 只是数据是假的。 */
export async function mockCall<A extends WebUiActionName>(
  action: A,
  payload: PayloadOf<A>,
): Promise<ResultOf<A>> {
  await sleep(MOCK_LATENCY_MS)
  return resolveMock(action, payload) as ResultOf<A>
}
