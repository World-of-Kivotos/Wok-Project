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

import { getWorld } from '../mock/store'
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
  HubPanelId,
  HubPanelsResult,
  ItemDetailKind,
  ItemDetailStat,
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
  PlayerIsOpResult,
  PlayerItemDetailPayload,
  PlayerItemDetailResult,
  PlayerPrefs,
  PlayerPrefsGetResult,
  PlayerPrefsSetPayload,
  PlayerPrefsSetResult,
  PlayerProfileResult,
  PlayerWalletResult,
  SystemEchoPayload,
  SystemEchoResult,
  SystemServerStatusResult,
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

/**
 * 服务端业务拒绝路径 (WebUiBusinessException), 带稳定机器码。
 *
 * params 缺省即"不带占位符实参": 服务端 businessErrorJson 在 params 为空 Map 时整键不写, 这里同样
 * 不传就不写 —— 补一个空对象会让前端文案层把"服务端没给"当成"给了但是空的"。
 */
function businessFailure(
  action: WebUiActionName,
  errorCode: string,
  message: string,
  retrySameOpeningId: boolean,
  params?: Record<string, string>,
): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, {
    errorCode,
    retrySameOpeningId,
    ...(params === undefined ? {} : { params }),
  })
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
    /*
     * NBT 变体件。195 种零件共用这一个 itemId 与这一个翻译键, 故它必须登记 —— 背包里那两件零件挂上市场时
     * makeListing 要按 itemId 查这张表, 查不到就是一句 "mock 数据缺陷" 的硬抛。
     */
    itemId: 'miningdim:gunsmith_part',
    registered: true,
    descriptionId: 'item.miningdim.gunsmith_part',
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
  // BASIC 变体的名字不带变体键: GunsmithPartItem.getName 对它拼的是 平台键 + 部位键 + 空格 + 品质键。
  'gunsmith.platform.ar': 'AR',
  'gunsmith.part.core': '基础导气',
  'gunsmith.variant.gehenna_high_speed_gas': '格赫娜高速导气',
  'gunsmith.quality.legendary': '传奇',
  'item.tacz.modern_kinetic_gun': '现代动能枪械',
  'item.minecraft.arrow': '箭',
  'item.minecraft.diamond_chestplate': '钻石胸甲',
  'item.minecraft.wheat': '小麦',
  /*
   * 八个职业名。player.profile 的 jobs 不带 displayName (服务端不直给中文), 前端按
   * `job.miningdim.<jobId>` 走 client.i18n 自解, 故这张表必须覆盖它们, 否则假数据模式下首页八个格子
   * 全显示成原始键。取值逐字抄自 src/main/resources/assets/miningdim/lang/zh_cn.json:704-711。
   */
  'job.miningdim.miner': '矿工',
  'job.miningdim.farmer': '农夫',
  'job.miningdim.engineer': '铸甲师',
  'job.miningdim.tarot': '塔罗师',
  'job.miningdim.chef': '厨师',
  'job.miningdim.agent': '特勤干员',
  'job.miningdim.munitions': '军火商',
  'job.miningdim.brewer': '酿酒师',
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

/** 主背包格数 (Inventory.items 的长度, 不含护甲/副手)。player.itemDetail 的越界判定与补货找空位共用。 */
const INVENTORY_SIZE = 36

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
  /*
   * 两件枪匠零件: 同平台 (AR) 同部位 (core) 同品质 (传奇), 只差变体。
   *
   * 必须是两件而不是一件 —— 真服对 BASIC 变体只发 coefficient 一行, 非 BASIC 才另加三行
   * (WebUiItemDetailJson.appendGunsmithPart:159-167)。只放一件的话另一种行集在假数据模式下永远走不到,
   * 照着 mock 写详情面板的人就会假定那三行恒存在, 接真服后基础零件上出现三个 undefined 行。
   *
   * customModelData 按 GunsmithPartItem.customModelData:249-253 逐位算:
   * variant.index()*1_000_000 + platform.index()*100 + part.index()*10 + quality.index() + 1。
   * AR=0 / core=0 / legendary=4, 故 BASIC (变体序号 0) 得 5, 格赫娜高速导气 (变体序号 1) 得 1_000_005
   * —— 后者与 mock/seed.ts 的 ITEM_GAS_CORE 是同一件, 那条已核对过 /mc/variants.json。
   *
   * nameParts 的两种形状也不同 (GunsmithPartItem.getName:122-137): BASIC 拼 平台键 + 部位键,
   * 非 BASIC 拼变体键, 之后才是空格与品质键。
   */
  {
    slot: 20,
    itemId: 'miningdim:gunsmith_part',
    descriptionId: 'item.miningdim.gunsmith_part',
    count: 1,
    customModelData: 5,
    nameParts: [
      { k: 'gunsmith.platform.ar' },
      { k: 'gunsmith.part.core' },
      { t: ' ' },
      { k: 'gunsmith.quality.legendary' },
    ],
  },
  {
    slot: 21,
    itemId: 'miningdim:gunsmith_part',
    descriptionId: 'item.miningdim.gunsmith_part',
    count: 1,
    customModelData: 1_000_005,
    nameParts: [
      { k: 'gunsmith.variant.gehenna_high_speed_gas' },
      { t: ' ' },
      { k: 'gunsmith.quality.legendary' },
    ],
  },
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
  for (let slot = 0; slot < INVENTORY_SIZE; slot += 1) {
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
// 账号 / 服务器状态 (W1 核销的七条)
// ============================================================

/**
 * 这七条的假实现为什么要读 mock 世界 (import getWorld):
 *
 * 它们权威的是"我是谁、我练到哪儿了、我能不能进管理后台", 而这三样在假数据模式下的开关都已经在
 * mock 世界里了 —— 外壳顶栏的"OP 视图"Toggle 写的是 world.player.isOp, admin.job.setLevel 改的是
 * world.jobs.progress。本文件若另存一份, 设计评审时会出现"开关拨了但首页没变"这种只有 mock 才有的假故障。
 * 钱包同理: 基线归本文件的 wallet, planned 域的收支 (卖菜/买卡包) 记在 world.walletOverlay, 两者相加
 * 才是玩家该看到的余额 —— 这条合成规则整个前端只有这一处。
 *
 * 装进游戏后本文件整体不参与 (生产构建摇掉), 故这层耦合不会渗进真服路径。
 */

/**
 * 平均每刻毫秒。取 52.6 而不是一个健康值: 原版设计满速是 50ms/刻, 只有 mspt > 50 才可能掉刻,
 * 而 TPS 徽标的三档配色 (TabletShell.tpsTone) 若在假数据下恒为绿, 掉刻那两档就从没被人看见过。
 */
const SERVER_MSPT = 52.6
const SERVER_ONLINE = 17
const SERVER_MAX_PLAYERS = 60
/** 已运行基线 (3 天 4 小时 12 分); 页面存活期内按真实秒数往上走, 好让"已运行"不是个死数字。 */
const SERVER_UPTIME_BASE_SECONDS = 3 * 24 * 3600 + 4 * 3600 + 12 * 60

function mockServerStatus(): SystemServerStatusResult {
  /*
   * tps 由 mspt 现算, 公式 (含 20 的上钳) 与服务端逐字一致。
   * 不各填一个常数: 那样 mspt 与 tps 会讲两个互相矛盾的故事 (旧种子就是 mspt 32.4 配 tps 19.8,
   * 而 1000/32.4 = 30.9), 而真服里这两个数恒等地绑在一起。
   */
  const tps = SERVER_MSPT <= 0 ? 20 : Math.min(20, 1000 / SERVER_MSPT)
  return {
    online: SERVER_ONLINE,
    maxPlayers: SERVER_MAX_PLAYERS,
    tps,
    mspt: SERVER_MSPT,
    uptimeSeconds: SERVER_UPTIME_BASE_SECONDS + Math.floor((Date.now() - NOW) / 1000),
  }
}

function mockIsOp(): PlayerIsOpResult {
  return { isOp: getWorld().player.isOp }
}

function mockProfile(): PlayerProfileResult {
  const world = getWorld()
  return {
    playerName: MOCK_PLAYER_NAME,
    isOp: world.player.isOp,
    wallet: { credit: wallet.credit + world.walletOverlay.credit, azure: wallet.azure + world.walletOverlay.azure },
    // 真契约无 displayName (服务端不直给中文), 故这里显式挑字段而不是整条 spread —— 多带一个字段
    // 就等于让面板可以读到真服根本不会发的东西。
    jobs: world.jobs.progress.map((entry) => ({
      jobId: entry.jobId,
      level: entry.level,
      totalXp: entry.totalXp,
      levelXp: entry.levelXp,
      nextLevelXp: entry.nextLevelXp,
      dailyXp: entry.dailyXp,
      dailyRemaining: entry.dailyRemaining,
    })),
    todayCreditFaucetGross: mockCreditFaucetGross(),
    // 青辉石走硬截断, 账本落的就是实发额, 与上一栏刻意不对称。
    todayAzureIn: world.economy.today.azureIn,
  }
}

/**
 * 今日信用点 faucet 毛额 (衰减前)。
 *
 * 真服直接从账本读 rawAmount 的累加值; mock 世界只存了打折**之后**的 earnedToday, 故这里按 decayFactor
 * 反推回去 —— 目的只有一个: 让首页那两栏 (毛额 vs economy.today 的实发合计) 大小关系与真服一致,
 * 否则面板上会出现"毛额比实发还小"这种真服不可能的形态, 反而把 D3 那条口径讲反。
 */
function mockCreditFaucetGross(): number {
  let gross = 0
  for (const faucet of getWorld().economy.today.faucets) {
    if (faucet.decayFactor <= 0) {
      throw new Error(`mock 数据缺陷: faucet ${faucet.faucetKey} 的 decayFactor 非正, 无法反推毛额`)
    }
    gross += Math.round(faucet.earnedToday / faucet.decayFactor)
  }
  return gross
}

/**
 * 物品大类。mock 里没有 NBT, 只能按 itemId 判 —— 真服判的是 NBT 根标签 (GunsmithGunStats.ROOT_KEY 等),
 * 同一个 itemId 完全可能既有 NBT 又没有。这条差异写在这里, 免得有人照着 mock 推断真服的判定依据。
 */
function mockItemKind(itemId: string): ItemDetailKind {
  if (itemId.startsWith('tacz:')) {
    return 'gun'
  }
  if (itemId === 'miningdim:gunsmith_part') {
    return 'gunsmith_part'
  }
  return 'plain'
}

/**
 * 这件零件是不是 BASIC 变体。
 *
 * 真服判的是 NBT 里的 variant (PartData.variant), mock 手里只有 customModelData —— 而按
 * GunsmithPartItem.customModelData:249-253 的算式, 它的百万位就是 variant.index(), 0 即 BASIC
 * (GunsmithPartVariant 的首个常量)。缺这一位的零件在真服不存在 (算式带 +1, 恒非 0), 故直接抛,
 * 不给一个"当作 BASIC"的默认值把 mock 数据缺陷盖过去。
 */
function mockPartIsBasic(item: PlayerInventoryItem): boolean {
  if (item.customModelData === undefined) {
    throw new Error(`mock 数据缺陷: 槽位 ${String(item.slot)} 的枪匠零件缺 customModelData, 变体无从判定`)
  }
  return Math.floor(item.customModelData / 1_000_000) === 0
}

/** 各 kind 的数值行。key 与 unit 逐字对齐真契约的行表, 数值本身是占位 (mock 无 NBT 可解)。 */
function mockItemAttributes(kind: ItemDetailKind, item: PlayerInventoryItem): ItemDetailStat[] {
  if (kind === 'gun') {
    return [
      { key: 'damage', value: 0.18, unit: 'percent' },
      { key: 'headshot', value: 0.05, unit: 'percent' },
      { key: 'range', value: 0.12, unit: 'percent' },
      { key: 'handling', value: -0.04, unit: 'percent' },
      { key: 'average', value: 0.08, unit: 'percent' },
      { key: 'fireRate', value: 0.1, unit: 'percent' },
      // 后坐与散布是"越低越好"的量, 真服同样发负数表示改善, 前端不得取绝对值。
      { key: 'verticalRecoil', value: -0.15, unit: 'percent' },
      { key: 'horizontalRecoil', value: -0.09, unit: 'percent' },
      { key: 'inaccuracy', value: -0.11, unit: 'percent' },
      { key: 'partCount', value: 5, unit: 'flat' },
    ]
  }
  if (kind === 'gunsmith_part') {
    /*
     * 行集随变体走, 与 WebUiItemDetailJson.appendGunsmithPart:159-167 逐条对齐:
     * coefficient 恒发一行, 后三行**只有非 BASIC 变体才有** (BASIC 的三个乘数恒为 1.0, 发三行 +0% 是噪音)。
     * 无条件发四行的写法会让照 mock 写的渲染层假定后三行恒存在, 接真服后基础零件上出现三个 undefined 行。
     */
    // 1.42 落在 GunsmithPartQuality.LEGENDARY 的 [1.36, 1.50] 内 —— 必须与下面 tags 里的 part.quality 自洽,
    // 否则这份假数据在真服的 requireCoefficient 那里会被当场拒, 而 mock 存在的意义就是与真服同口径。
    const stats: ItemDetailStat[] = [{ key: 'coefficient', value: 1.42, unit: 'flat' }]
    if (!mockPartIsBasic(item)) {
      stats.push(
        { key: 'fireRate', value: 0.06, unit: 'percent' },
        { key: 'verticalRecoil', value: -0.12, unit: 'percent' },
        { key: 'inaccuracy', value: -0.08, unit: 'percent' },
      )
    }
    return stats
  }
  return []
}

/** 标签码。形态 'ns.name' 或 'ns.name:<稳定id>', 与真契约同一套; 文案由前端自解, 服务端不发中文。 */
function mockItemTags(kind: ItemDetailKind, item: PlayerInventoryItem): string[] {
  if (kind === 'gun') {
    return ['gun.platform:ar', 'gun.template:m4a1']
  }
  if (kind === 'gunsmith_part') {
    return [
      'part.platform:ar',
      // 部位 id 是 core: 真服发的是 part().id() (GunsmithPressPart.CORE), "GAS" 只是它的短标签。
      'part.slot:core',
      `part.variant:${mockPartIsBasic(item) ? 'basic' : 'gehenna_high_speed_gas'}`,
      'part.quality:legendary',
    ]
  }
  return []
}

function mockItemDetail(payload: PlayerItemDetailPayload): PlayerItemDetailResult {
  if (typeof payload.slot !== 'number' || !Number.isInteger(payload.slot)) {
    // 与服务端同形: 类型不符只报 field, 不报值 (见 prefsTypeRejected 的说明)。
    throw businessFailure('player.itemDetail', 'INVALID_REQUEST', '字段 slot 必须是整数', false, {
      field: 'slot',
    })
  }
  if (payload.slot < 0 || payload.slot >= INVENTORY_SIZE) {
    throw businessFailure(
      'player.itemDetail',
      'SLOT_OUT_OF_RANGE',
      `槽位 ${String(payload.slot)} 超出背包范围`,
      false,
      { slot: String(payload.slot), size: String(INVENTORY_SIZE) },
    )
  }
  const stack = inventory.find((item) => item.slot === payload.slot)
  if (stack === undefined) {
    throw businessFailure('player.itemDetail', 'SLOT_EMPTY', `槽位 ${String(payload.slot)} 是空的`, false, {
      slot: String(payload.slot),
    })
  }
  const kind = mockItemKind(stack.itemId)
  return {
    slot: stack.slot,
    itemId: stack.itemId,
    descriptionId: stack.descriptionId,
    count: stack.count,
    // 三个可选字段一律"没有就整键不写", 与 player.inventory 同一形态 (默认 Gson 不写 null)。
    ...(stack.displayName === undefined ? {} : { displayName: stack.displayName }),
    ...(stack.customModelData === undefined ? {} : { customModelData: stack.customModelData }),
    ...(stack.nameParts === undefined ? {} : { nameParts: stack.nameParts }),
    kind,
    attributes: mockItemAttributes(kind, stack),
    tags: mockItemTags(kind, stack),
  }
}

/** 四项默认值逐字对齐服务端 UiPrefs.DEFAULT 与前端 theme.ts / brand.ts 的默认档, 否则首帧会闪一次。 */
const DEFAULT_PREFS: PlayerPrefs = {
  muteToasts: false,
  language: 'zh_cn',
  theme: 'dark',
  brandHue: 250,
}

/**
 * 偏好在真服落 capability (跟 player.dat 走)。mock 只存进模块变量, 刷新页面即回默认值 ——
 * 刻意不落 localStorage: 那会与 theme.ts / brand.ts 自己的 localStorage 键长成两份互相打架的偏好,
 * 而这条 action 存在的意义恰恰是"账号级偏好压过本机偏好"。
 */
let prefs: PlayerPrefs = { ...DEFAULT_PREFS }

const LANGUAGE_PATTERN = /^[a-z0-9_]{1,16}$/

function mockPrefsGet(): PlayerPrefsGetResult {
  return { ...prefs }
}

/**
 * 两种拒绝的 params 刻意不同, 与服务端逐字对齐 (PlayerWebUiActions 的 requireXxx / rejectValue):
 * 字段缺失或类型不符只给 field (根本没有一个"值"可报), 取值域外才给 field + value。
 * 文案层据此选带参还是不带参的那句话, mock 若一律塞 value, 会把"缺字段"渲染成"字段 x 不接受 undefined"。
 */
function prefsTypeRejected(field: string): WebUiCallError {
  return businessFailure('player.prefs.set', 'INVALID_REQUEST', `字段 ${field} 类型不符`, false, {
    field,
  })
}

function prefsValueRejected(field: string, value: string): WebUiCallError {
  return businessFailure(
    'player.prefs.set',
    'INVALID_REQUEST',
    `字段 ${field} 取值非法: ${value}`,
    false,
    { field, value },
  )
}

/**
 * 整份覆盖 + 逐字段校验。写入侧一律拒绝而不是静默钳制 —— 掩盖非法值等于让契约声明的取值域失效,
 * 而"钳制"这条路只属于读取侧 (服务端 UiPrefs.sanitized 反序列化 NBT 时才回退)。
 */
function mockPrefsSet(payload: PlayerPrefsSetPayload): PlayerPrefsSetResult {
  if (typeof payload.muteToasts !== 'boolean') {
    throw prefsTypeRejected('muteToasts')
  }
  if (typeof payload.language !== 'string') {
    throw prefsTypeRejected('language')
  }
  if (!LANGUAGE_PATTERN.test(payload.language)) {
    throw prefsValueRejected('language', payload.language)
  }
  if (typeof payload.theme !== 'string') {
    throw prefsTypeRejected('theme')
  }
  if (payload.theme !== 'dark' && payload.theme !== 'light') {
    throw prefsValueRejected('theme', payload.theme)
  }
  if (typeof payload.brandHue !== 'number' || !Number.isInteger(payload.brandHue)) {
    throw prefsTypeRejected('brandHue')
  }
  if (payload.brandHue < 0 || payload.brandHue > 360) {
    throw prefsValueRejected('brandHue', String(payload.brandHue))
  }
  prefs = {
    muteToasts: payload.muteToasts,
    language: payload.language,
    theme: payload.theme,
    brandHue: payload.brandHue,
  }
  // 回发落盘值而不是 {ok:true}: 前端据此对齐本地状态, 服务端日后收窄取值域时也能立刻看出被改成了什么。
  return { ...prefs }
}

/**
 * 面板域与顺序 = 服务端 HubWebUiActions 的硬编码表 (quests 不存在, 精英怪图鉴叫 codex)。
 * 这里只发 panelId/enabled/lockCode, 展示层三项 (route/label/iconItemId) 归前端 lib/panels.ts。
 */
const HUB_PANEL_IDS: readonly HubPanelId[] = [
  'home',
  'market',
  'shop',
  'jobs',
  'mining',
  'codex',
  'marriage',
  'case',
  'settings',
  'admin',
]

function mockHubPanels(): HubPanelsResult {
  const isOp = getWorld().player.isOp
  return {
    panels: HUB_PANEL_IDS.map((panelId) => {
      // 当前唯一的门: admin 随 OP 翻转。婚姻恒开 (它本身就是未婚玩家的求婚入口), 职业等级门本批不做。
      if (panelId === 'admin' && !isOp) {
        return { panelId, enabled: false, lockCode: 'NOT_OP' }
      }
      return { panelId, enabled: true }
    }),
  }
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
    case 'system.serverStatus':
      return mockServerStatus()
    case 'player.inventory':
      return mockInventory()
    case 'player.isOp':
      return mockIsOp()
    case 'player.itemDetail':
      return mockItemDetail(payload as PlayerItemDetailPayload)
    case 'player.prefs.get':
      return mockPrefsGet()
    case 'player.prefs.set':
      return mockPrefsSet(payload as PlayerPrefsSetPayload)
    case 'player.profile':
      return mockProfile()
    case 'player.wallet':
      return { ...wallet }
    case 'hub.panels':
      return mockHubPanels()
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
