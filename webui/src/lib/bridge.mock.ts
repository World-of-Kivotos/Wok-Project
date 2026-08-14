/**
 * 假数据后端: 让整套 UI 脱离 Minecraft 就能在普通浏览器里跑起来改设计。
 *
 * 只在 dev server 且宿主未注入时被 bridge.ts 动态加载 (生产构建整块摇掉)。
 *
 * 它是什么: 契约形状的忠实复刻 + 一点点内存状态, 好让"挂单 -> 我的挂单 -> 撤单"这类闭环在浏览器里走得通。
 * 它不是什么: 服务端业务规则的第二实现。手续费率、开箱概率、等级门、OP 门控一律不复刻 ——
 * 复刻一份必然与 Java 侧漂移, 而漂移的假规则比没有规则更误导设计判断。
 *
 * 这条原则有三处**已知且刻意**的近似, 读数时不要当真值:
 *   1. market.place / market.feePreview 的 listFee 用同一个固定比例占位。真费率由服务端 MarketFee 按挂价
 *      对 V0 的偏离度算, 量级可以差好几倍; 前端任何时候都以回执里的 listFee 为准, 不得照这个比例自己算。
 *   2. case.open 的中奖皮肤按 openingId 哈希在皮肤表里均匀取, 不按 weights 抽。于是回执里的
 *      weights 是线上真值, 而 reel 与中奖结果的稀有度分布不是 —— 拿这一页评估"金色出现得多不多"必然错。
 *   3. market.tradable / market.place 的可交易判定是服务端 MarketTradeWhitelist 的等价复刻 (塔罗牌只有
 *      最低品质 R 可挂)。它比前两条更接近真值 (规则本身只有三条分支), 但仍是第二份实现: 服务端改白名单时
 *      本文件必须跟着改, 否则设计评审会照着一套过期规则做界面。
 * 三处都不改成"更真"的实现: 真实现会与 Java 侧无声漂移, 而漂移的假规则比明写的近似危险得多。
 *
 * 假数据刻意铺满边界值, 因为这些正是设计稿最容易漏掉的形态:
 *   - 空列表      market.list 翻到第 2 页即空
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
  BrewerBrewEntry,
  BrewerRecipeRow,
  BrewerStateResult,
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
  ChefEffectRow,
  ChefEffectUnit,
  ChefQualityRow,
  ChefStateResult,
  ClientI18nPayload,
  ClientI18nResult,
  ClientPlayCaseSoundPayload,
  ClientPlayCaseSoundResult,
  FarmerSellPayload,
  FarmerSellResult,
  FarmerStateResult,
  FarmerTierRow,
  HubPanelId,
  HubPanelsResult,
  ItemDetailKind,
  ItemDetailStat,
  JobProgressResult,
  JobStatLine,
  MarketBaseValuePayload,
  MarketBaseValueResult,
  MarketBuyPayload,
  MarketBuyResult,
  MarketCancelPayload,
  MarketCancelResult,
  MarketCategoriesResult,
  MarketFeePreviewPayload,
  MarketFeePreviewResult,
  MarketHistoryPayload,
  MarketHistoryResult,
  MarketListPayload,
  MarketListResult,
  MarketListing,
  MarketMineResult,
  MarketP2pCapResult,
  MarketPendingPayoutResult,
  MarketPlacePayload,
  MarketPlaceResult,
  MarketTradablePayload,
  MarketTradableResult,
  MarketTransaction,
  MinerScanResult,
  MinerStateResult,
  MinerToggleState,
  PlayerInventoryItem,
  PlayerInventoryResult,
  PlayerIsOpResult,
  PlayerItemDetailPayload,
  PlayerItemDetailResult,
  PlayerPrefs,
  PlayerPrefsGetResult,
  PlayerPrefsSetPayload,
  PlayerPrefsSetResult,
  PlayerJobProgressEntry,
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
    // 农夫收购站唯一认的作物 (FarmerWheatSellService 只认它); 与原版小麦是两件物品, 不可混用。
    itemId: 'miningdim:farmer_wheat',
    registered: true,
    descriptionId: 'item.miningdim.farmer_wheat',
    top: 'food',
    sub: null,
  },
  {
    /*
     * 塔罗牌。220 张牌面 x 5 档品质共用这**一个** itemId (TarotRegistry 只注册了 tarot_card),
     * 区分它们的全部信息都在 NBT 里 —— 这正是 market.tradable 的入参必须是 slot 而不是 itemId 的原因。
     */
    itemId: 'miningdim:tarot_card',
    registered: true,
    descriptionId: 'item.miningdim.tarot_card',
    top: 'other',
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
  // 塔罗牌: Item 级键 (220 张牌面共用), 牌面与品质在真服由 NBT 决定, 不进翻译键。
  'item.miningdim.tarot_card': '塔罗牌',
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
  /*
   * 以下几批键随 W3 职业一接线补齐。凡是 lang/zh_cn.json 已有条目的 (矿工三个开关、耕地五档、农夫小麦、
   * 厨师品质与 18 效果、9 种酒) 一律逐字照抄真值; 只有 stat.miningdim.miner.* 与 brewer.moonshine.*
   * 两批是本批新增的键, 中文取自各自 Java 源码注释里的措辞。
   */
  'skill.miningdim.miner.chain': '连锁挖矿',
  'skill.miningdim.miner.auto_collect': '自动入包',
  'skill.miningdim.miner.auto_smelt': '自动熔炼',
  'stat.miningdim.miner.dig_speed': '挖掘提速',
  'stat.miningdim.miner.durability_save': '不耗耐久概率',
  'stat.miningdim.miner.fortune_extra': '时运额外掉落',
  'stat.miningdim.miner.danger_time_factor': '压力累积系数',
  'stat.miningdim.miner.trap_damage_reduction': '矿脉抗性 (陷阱减伤)',
  'stat.miningdim.miner.chain_refill_full': '连锁充能回满',
  'item.miningdim.farmer_wheat': '农夫小麦',
  'block.miningdim.farmer_farmland_low': '低级耕地',
  'block.miningdim.farmer_farmland_medium': '中级耕地',
  'block.miningdim.farmer_farmland_high': '高级耕地',
  'block.miningdim.farmer_farmland_premium': '极品耕地',
  'block.miningdim.farmer_farmland_supreme': '超凡耕地',
  'chef.quality.prefix.low': '低级',
  'chef.quality.prefix.medium': '中级',
  'chef.quality.prefix.high': '高级',
  'chef.quality.prefix.extraordinary': '超凡',
  'chef.quality.prefix.radiant': '闪耀',
  'chef.effect.amplify': '增香(buff 时长)',
  'chef.effect.nourish_food': '增量(额外饱食)',
  'chef.effect.aftertaste_sat': '回味(饱和)',
  'chef.effect.sated_jump': '饱食(跳跃提升)',
  'chef.effect.nourish_heal': '膳香(按最大血量回血)',
  'chef.effect.purify': '回甘(净化负面)',
  'chef.effect.oversalt': '多盐(饱和减半)',
  'chef.effect.spoiled': '失败品(菜肴报废)',
  'chef.effect.endurance': '耐饥(饥饿衰减变慢)',
  'chef.effect.refresh': '提神(急速)',
  'chef.effect.night_sight': '夜照(夜视)',
  'chef.effect.shield': '披甲(黄心护盾)',
  'chef.effect.grease': '凝脂(爆炸减伤)',
  'chef.effect.aftertaste_regen': '余韵(延迟再生)',
  'chef.effect.stable_aim': '稳膛(抗击退)',
  'chef.effect.underdone': '夹生(随机负面)',
  'chef.effect.scorched': '烧焦(自伤)',
  'chef.effect.nausea': '倒胃(中毒)',
  'item.miningdim.wine_brandy': '白兰地',
  'item.miningdim.wine_vodka': '伏特加',
  'item.miningdim.wine_gin': '金酒',
  'item.miningdim.wine_rum': '朗姆酒',
  'item.miningdim.wine_tequila': '龙舌兰',
  'item.miningdim.wine_maotai': '茅台',
  'item.miningdim.wine_whiskey': '威士忌',
  'item.miningdim.wine_champagne': '香槟',
  'item.miningdim.wine_moonshine': '月光酒',
  'brewer.moonshine.knockback_res': '击退抗性',
  'brewer.moonshine.plated': '护甲',
  'brewer.moonshine.tough': '护甲韧性',
  'brewer.moonshine.lucky': '幸运',
  'brewer.moonshine.swift': '移速',
  'brewer.moonshine.brute': '攻击击退',
  'brewer.moonshine.vigor': '近战攻击',
  'brewer.moonshine.night_vision': '永久夜视',
  // 酿酒配方原料 (BrewRecipes 用到的原版物品)。
  'item.minecraft.apple': '苹果',
  'item.minecraft.sugar': '糖',
  'item.minecraft.sugar_cane': '甘蔗',
  'item.minecraft.carrot': '胡萝卜',
  'item.minecraft.wheat_seeds': '小麦种子',
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
  /*
   * 两张塔罗牌: 同 itemId 同翻译键, 只差 NBT 品质 (见 MOCK_TAROT_QUALITY_BY_SLOT)。
   * 必须是两张而不是一张 —— market.tradable 的"可挂"与"不可挂"是它唯一有分歧的两条分支,
   * 只放一张的话另一条分支在假数据模式下永远走不到, 照着 mock 做界面的人就会假定按钮从不变灰。
   */
  { slot: 2, itemId: 'miningdim:tarot_card', descriptionId: 'item.miningdim.tarot_card', count: 1 },
  { slot: 3, itemId: 'miningdim:tarot_card', descriptionId: 'item.miningdim.tarot_card', count: 1 },
  // 第三张: 品质表里查不到, 复刻"创造模式直给的裸牌"(牌身份不可读) 那条拒绝分支。
  { slot: 5, itemId: 'miningdim:tarot_card', descriptionId: 'item.miningdim.tarot_card', count: 1 },
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
   * 农夫小麦散在两个未满栈里, 正是 job.farmer.sell 不按槽位结算的那个形态: 服务端按物品种类扫全背包扣,
   * 面板要显示的是"背包里共有 40 株"而不是某一格的数量。只放一格的话这条区别在假数据模式下永远看不出来。
   */
  {
    slot: 18,
    itemId: 'miningdim:farmer_wheat',
    descriptionId: 'item.miningdim.farmer_wheat',
    count: 24,
  },
  {
    slot: 19,
    itemId: 'miningdim:farmer_wheat',
    descriptionId: 'item.miningdim.farmer_wheat',
    count: 16,
  },
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
  /*
   * 白名单判定与 market.tradable 共用同一个 judgeTradable —— 服务端那侧 (MarketTradeWhitelist) 也是
   * 一份判定两条路径。mock 若只在 tradable 里判、place 放行, 就会做出"前端灰了但提交得上去"的假象,
   * 而那正是这条规则最要防的事。
   */
  const verdict = judgeTradable(payload.slot, stack.itemId)
  if (!verdict.tradable) {
    throw businessFailure('market.place', ITEM_NOT_TRADABLE, verdict.reason, false, {
      itemId: stack.itemId,
      rule: verdict.rule,
    })
  }
  const listFee = mockListFee(payload.unitPrice, payload.count)
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
  /*
   * 记一条流水。服务端 MarketEngine.buy 在同一次调用里就 insertTxn, 成交历史是买入的直接后果;
   * mock 不记的话 market.history 会变成一张与任何操作都无关的静态表, "买了之后去历史里找得到"这条
   * 最基本的验收路径就走不通。
   */
  nextTxnId += 1
  transactions.unshift({
    txnId: nextTxnId,
    listingId: listing.id,
    role: 'buy',
    itemId: listing.itemId,
    descriptionId: listing.descriptionId,
    count,
    unitPrice: listing.unitPrice,
    total,
    fee: 0,
    counterpartyUuid: requireCounterpartyUuid(listing.sellerName),
    counterpartyName: listing.sellerName,
    createdAt: Date.now(),
  })
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

// ============================================================
// 市场 W2 五条 (手续费预览 / 每日额度 / 成交流水 / 待结货款 / 可交易判定)
// ============================================================

/**
 * mock 的挂单手续费占位比例。
 *
 * 真费率由 MarketFee 按挂价对 V0 的偏离度算, 量级能差好几倍 (见文件头第 1 条近似)。
 * 关键是 market.feePreview 与 market.place 必须**吃同一个函数**: 服务端那侧两条路径共用 MarketFee.listingFee,
 * mock 若各算各的, 就会做出"预览 40 实扣 200"这种服务端根本不可能出现的故障。
 */
const MOCK_LIST_FEE_RATIO = 0.04

function mockListFee(unitPrice: number, count: number): number {
  return Math.max(1, Math.round(unitPrice * count * MOCK_LIST_FEE_RATIO))
}

/** market.place 与 market.tradable 共用的拒绝码 (真源 WebUiErrorCodes.ITEM_NOT_TRADABLE)。 */
const ITEM_NOT_TRADABLE = 'ITEM_NOT_TRADABLE'

/**
 * 塔罗牌品质 (按槽位声明)。
 *
 * mock 背包只有 itemId/count 这几个字段, 没有 NBT 通道, 而真服的塔罗品质只活在 NBT 里 (220 张牌面
 * x 5 档品质共用一个 miningdim:tarot_card)。故这里不是"发明了一套品质规则", 只是把"哪一格里是哪档牌"
 * 这条数据写下来; 判定规则本身在 judgeTradable。表里查不到的塔罗牌 = 牌身份不可读 (创造模式直给的裸牌)。
 */
const MOCK_TAROT_QUALITY_BY_SLOT: ReadonlyMap<number, string> = new Map([
  [2, 'R'],
  [3, 'SSR'],
])

const TAROT_ITEM_ID = 'miningdim:tarot_card'

type TradableVerdict =
  | { tradable: true }
  | { tradable: false; rule: string; reason: string }

/**
 * 可交易判定 (MarketTradeWhitelist.judge 的等价复刻, 见文件头第 3 条)。
 *
 * 青辉石不写分支: 它是纯账本货币 (Currency.AZURE), 全库没有对应的注册物品, 按 itemId 写的规则永远
 * 匹配不到真实 ItemStack —— 这一条已由后端确认, 不是前端漏了。
 */
function judgeTradable(slot: number, itemId: string): TradableVerdict {
  if (itemId !== TAROT_ITEM_ID) {
    return { tradable: true }
  }
  const quality = MOCK_TAROT_QUALITY_BY_SLOT.get(slot)
  if (quality === undefined) {
    return {
      tradable: false,
      rule: 'TAROT_IDENTITY_UNREADABLE',
      reason: '这张塔罗牌的数据不完整, 无法上架',
    }
  }
  if (quality === 'R') {
    return { tradable: true }
  }
  return {
    tradable: false,
    rule: 'TAROT_QUALITY_ABOVE_R',
    reason: '只有最低品质(R)的塔罗牌可以在市场挂单, 更高品质请自行合成',
  }
}

function mockMarketTradable(payload: MarketTradablePayload): MarketTradableResult {
  if (payload.slot < 0 || payload.slot >= INVENTORY_SIZE) {
    throw businessFailure(
      'market.tradable',
      'SLOT_OUT_OF_RANGE',
      `槽位 ${String(payload.slot)} 超出背包范围`,
      false,
      { slot: String(payload.slot), size: String(INVENTORY_SIZE) },
    )
  }
  const stack = inventory.find((item) => item.slot === payload.slot)
  if (stack === undefined) {
    throw businessFailure('market.tradable', 'SLOT_EMPTY', `槽位 ${String(payload.slot)} 是空的`, false, {
      slot: String(payload.slot),
    })
  }
  const verdict = judgeTradable(stack.slot, stack.itemId)
  if (verdict.tradable) {
    return { slot: stack.slot, itemId: stack.itemId, tradable: true, reasonCode: null, reason: null }
  }
  return {
    slot: stack.slot,
    itemId: stack.itemId,
    tradable: false,
    // 与 place 拒绝时抛的 errorCode 是同一个字符串, 故前端一条文案同时服务灰按钮与硬提交被拒。
    reasonCode: ITEM_NOT_TRADABLE,
    reason: verdict.reason,
  }
}

function mockMarketFeePreview(payload: MarketFeePreviewPayload): MarketFeePreviewResult {
  // 非法入参抛 INVALID_REQUEST 而不是回一个 listFee=0: 给非法入参编一个金额就是拿掩盖当兜底。
  if (payload.unitPrice <= 0) {
    throw businessFailure('market.feePreview', 'INVALID_REQUEST', '单价必须为正整数', false, {
      field: 'unitPrice',
      value: String(payload.unitPrice),
    })
  }
  if (payload.count <= 0) {
    throw businessFailure('market.feePreview', 'INVALID_REQUEST', '数量必须为正整数', false, {
      field: 'count',
      value: String(payload.count),
    })
  }
  const resolved = resolveBaseValue(payload.itemId)
  const listFee = mockListFee(payload.unitPrice, payload.count)
  return {
    itemId: payload.itemId,
    listFee,
    // 分母是玩家自己挂的总价 (与契约一致), 故它可以 > 1 —— 前端不得按 0..1 钳死。
    ratio: listFee / (payload.unitPrice * payload.count),
    v0: resolved.v0,
    source: resolved.source,
  }
}

/** MarketConstants.COPPER_IRON_ITEM_IDS 逐字抄本 (6 项, 字典序); 服务端回执也是排好序的。 */
const COPPER_IRON_ITEM_IDS: readonly string[] = [
  'minecraft:copper_ingot',
  'minecraft:copper_ore',
  'minecraft:iron_ingot',
  'minecraft:iron_ore',
  'minecraft:raw_copper',
  'minecraft:raw_iron',
]

/** MarketConstants.COPPER_IRON_DAILY_P2P_CAP。 */
const COPPER_IRON_DAILY_P2P_CAP = 512

/**
 * 已用量的两段。**固定值, 挂单/买入都不写回** —— 真服这两个数由 DAO 按各自口径聚合算出,
 * mock 复刻那套聚合等于把服务端口径抄第二遍。面板上已如实标注"假数据: 买入不会写回额度"。
 *
 * 刻意两段都取非零: 只有两段同时有值, 面板那句"在挂中的 N 件始终占额度, 今日已成交的 M 件在 X 归零"
 * 才在 mock 下也是可读的; 任一段为 0 会让文案的半边在开发期从没被人看见过。
 */
const MOCK_P2P_ACTIVE_HELD = 128
const MOCK_P2P_SOLD_TODAY = 252

/** soldToday 的归零时刻: 服务器本地时区的次日零点 (与服务端 ZoneId.systemDefault() 同口径, 不是 UTC 翻日)。 */
function startOfTomorrow(): number {
  const tomorrow = new Date(NOW)
  // setHours(24,...) 走的是本地日历加一天, 夏令时切换日不会像 +86400000 那样偏一小时。
  tomorrow.setHours(24, 0, 0, 0)
  return tomorrow.getTime()
}

function mockMarketP2pCap(): MarketP2pCapResult {
  // usedToday 由两段相加得出而不是另写一个常量: 三个数各自独立会让 mock 自己先违反契约里的恒等式。
  const usedToday = MOCK_P2P_ACTIVE_HELD + MOCK_P2P_SOLD_TODAY
  return {
    usedToday,
    activeHeld: MOCK_P2P_ACTIVE_HELD,
    soldToday: MOCK_P2P_SOLD_TODAY,
    capPerDay: COPPER_IRON_DAILY_P2P_CAP,
    remaining: Math.max(0, COPPER_IRON_DAILY_P2P_CAP - usedToday),
    resetsAt: startOfTomorrow(),
    scopeItemIds: [...COPPER_IRON_ITEM_IDS],
  }
}

/**
 * 待结货款。只读 peek: 真服的发放只发生在登录时 (settlePendingOnLogin 走 drainPendingPayout),
 * 面板查多少次都不会少一分钱, 故这里是个常量而不是可被"领取"消费的状态。
 */
const MOCK_PENDING_PAYOUT: MarketPendingPayoutResult = { credit: 4_820, entryCount: 3 }

function mockMarketPendingPayout(): MarketPendingPayoutResult {
  return { ...MOCK_PENDING_PAYOUT }
}

/**
 * 成交流水的对手方。
 *
 * 末条 name 为 null 是**离线对手方**这条真实形态: transactions 表没有名字快照列, 服务端只能解析在线玩家,
 * 离线时回 null 让前端拿 uuid 自行降级 —— 它不是"名字没加载出来", 前端不得编一个"未知玩家"顶上。
 */
const TXN_COUNTERPARTIES: readonly { uuid: string; name: string | null }[] = [
  { uuid: '3f2b1c40-0a11-4c3e-9f01-1a2b3c4d5e6f', name: '矿工阿建' },
  { uuid: '5c8d2e91-7b33-4a2d-8c44-9e0f1a2b3c4d', name: '拍卖狂魔' },
  { uuid: '7a1e3d52-4c55-4b6a-9d77-2b3c4d5e6f70', name: '鲸鱼玩家' },
  { uuid: '9b4f5a63-2d77-4e8b-8a99-3c4d5e6f7081', name: null },
]

/** 挂单卖家名 -> UUID。查不到即抛: mock 数据缺陷该当场炸, 不该拿一个占位 uuid 糊过去。 */
const TXN_UUID_BY_NAME: ReadonlyMap<string, string> = new Map([
  [MOCK_PLAYER_NAME, '1d0c9b8a-7654-4321-9fed-cba987654321'],
  ...TXN_COUNTERPARTIES.filter(
    (party): party is { uuid: string; name: string } => party.name !== null,
  ).map((party): [string, string] => [party.name, party.uuid]),
])

function requireCounterpartyUuid(name: string): string {
  const uuid = TXN_UUID_BY_NAME.get(name)
  if (uuid === undefined) {
    throw new Error(`mock 数据缺陷: 没有登记玩家 ${name} 的 UUID`)
  }
  return uuid
}

const TXN_ITEM_IDS: readonly string[] = [
  'minecraft:diamond',
  'minecraft:gold_ingot',
  'minecraft:iron_ore',
  'minecraft:wheat',
  'tacz:modern_kinetic_gun',
  'miningdim:plate_armor_banshee_atacs_au',
]

/** 39 条 = 按 pageSize 20 分两页, 第 2 页 19 条 (差一条满页, 撞分页控件的末页形态)。 */
const TXN_SEED_COUNT = 39

function buildTransactions(): MarketTransaction[] {
  const rows: MarketTransaction[] = []
  for (let index = 0; index < TXN_SEED_COUNT - 1; index += 1) {
    const itemId = TXN_ITEM_IDS[index % TXN_ITEM_IDS.length]
    const party = TXN_COUNTERPARTIES[index % TXN_COUNTERPARTIES.length]
    if (itemId === undefined || party === undefined) {
      throw new Error('mock 数据缺陷: 流水物品表或对手方表为空')
    }
    const count = 1 + (index % 7)
    const unitPrice = 40 + index * 37
    rows.push({
      txnId: 9_000 + index,
      listingId: 5_000 + index,
      role: index % 3 === 0 ? 'sell' : 'buy',
      itemId,
      descriptionId: marketDescriptionId(requireItem(itemId)),
      count,
      unitPrice,
      total: unitPrice * count,
      // 恒 0: 手续费在挂单时就收掉了, 写流水时固定传 0 (与服务端 insertTxn 的调用点一致)。
      fee: 0,
      counterpartyUuid: party.uuid,
      counterpartyName: party.name,
      createdAt: NOW - index * 37 * 60_000,
    })
  }
  // 极大数值边界: 2^53-1, 撞 Java long -> JSON number 的精度上界与金额列的宽度上界。
  const whale = TXN_COUNTERPARTIES[2]
  if (whale === undefined) {
    throw new Error('mock 数据缺陷: 对手方表缺少鲸鱼玩家')
  }
  rows.push({
    txnId: 9_999,
    listingId: 5_999,
    role: 'sell',
    itemId: 'minecraft:netherite_scrap',
    descriptionId: marketDescriptionId(requireItem('minecraft:netherite_scrap')),
    count: 1,
    unitPrice: Number.MAX_SAFE_INTEGER,
    total: Number.MAX_SAFE_INTEGER,
    fee: 0,
    counterpartyUuid: whale.uuid,
    counterpartyName: whale.name,
    createdAt: NOW - 3 * 24 * 3_600_000,
  })
  return rows
}

/** 按 created_at 降序维护 (与 DAO 的 ORDER BY 一致): 新成交一律 unshift 到队首。 */
const transactions: MarketTransaction[] = buildTransactions()

let nextTxnId = 10_000

function mockMarketHistory(payload: MarketHistoryPayload): MarketHistoryResult {
  const page = payload.page === undefined ? 0 : payload.page
  const pageSize = payload.pageSize === undefined ? 20 : payload.pageSize
  const offset = page * pageSize
  return {
    transactions: transactions.slice(offset, offset + pageSize).map((row) => ({ ...row })),
    page,
    pageSize,
    total: transactions.length,
  }
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
    // 与 job.progress 同一份数据同一个取法 (服务端那侧也是共用 JobProgressJson.of), 两处各抄一遍必漂移。
    jobs: mockJobProgress().jobs,
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
// job.* (W3 职业一: 进度 / 矿工 / 农夫 / 厨师 / 酿酒师)
// ============================================================

/** 一 game tick 的毫秒数。服务端只发剩余 tick, 由前端在收到那一刻折成本地时刻 (见 MinerStateResult 注释)。 */
const MS_PER_TICK = 50

/**
 * 职业等级的唯一来源是 store 的 jobs.progress —— 与 player.profile / job.progress 同一份数据。
 * 各职业面板若各存一个 level, OP 面板改完级只有一半界面跟着变。
 */
function mockJobLevel(jobId: PlayerJobProgressEntry['jobId']): number {
  const entry = getWorld().jobs.progress.find((candidate) => candidate.jobId === jobId)
  if (entry === undefined) {
    throw new Error(`mock 数据缺陷: 职业进度表里没有 ${jobId}`)
  }
  return entry.level
}

function mockJobProgress(): JobProgressResult {
  return { jobs: getWorld().jobs.progress.map((entry) => ({ ...entry })) }
}

/** readyAt 已过去时返回 0 (就绪), 否则返回剩余 tick —— 与服务端 cooldownReady 判定后再相减同口径。 */
function remainingTicks(readyAt: number): number {
  const remainMs = readyAt - Date.now()
  return remainMs <= 0 ? 0 : Math.ceil(remainMs / MS_PER_TICK)
}

/**
 * 矿工被动的一份 **L6 快照**, 逐个按 MinerSkills 的真实曲线在 level=6 处算好后钉死。
 *
 * 为什么是快照而不是把曲线抄进来: 与本文件头第 3 段同一条纪律 —— 复刻一份等级曲线必然与 Java 侧漂移。
 * 于是 OP 面板把矿工改到别的等级时, 这些数字**不会跟着变** (只有下面几个解锁位会变, 那些是常量不是曲线)。
 * 拿这一页评估"升级能提升多少挖速"必然错, 真值永远以服务端回执为准。
 */
const MINER_PASSIVES_AT_L6: readonly JobStatLine[] = [
  { key: 'dig_speed', labelKey: 'stat.miningdim.miner.dig_speed', value: 1.678, unit: 'multiplier' },
  {
    key: 'durability_save',
    labelKey: 'stat.miningdim.miner.durability_save',
    value: 0.189,
    unit: 'percent',
  },
  { key: 'fortune_extra', labelKey: 'stat.miningdim.miner.fortune_extra', value: 0.22, unit: 'flat' },
  {
    key: 'danger_time_factor',
    labelKey: 'stat.miningdim.miner.danger_time_factor',
    value: 0.767,
    unit: 'multiplier',
  },
  {
    key: 'trap_damage_reduction',
    labelKey: 'stat.miningdim.miner.trap_damage_reduction',
    value: 0.15,
    unit: 'percent',
  },
  {
    key: 'chain_refill_full',
    labelKey: 'stat.miningdim.miner.chain_refill_full',
    value: 5_100,
    unit: 'ticks',
  },
]

/** 三个开关各自的解锁等级 (MinerConstants 常量, 非曲线, 故照抄真值)。 */
const MINER_TOGGLE_UNLOCK_LEVEL: Readonly<Record<MinerToggleState['skillId'], number>> = {
  chain: 2,
  auto_collect: 2,
  auto_smelt: 6,
}

/**
 * 三个开关的当前开合 (瞬态运行态, 服务端也不持久化)。auto_smelt 刻意留在关闭态:
 * "已解锁但玩家自己关着"与"还没解锁"在面板上必须是两种不同的样子, 只给一种就分不出来。
 */
const minerToggleEnabled: Record<MinerToggleState['skillId'], boolean> = {
  chain: true,
  auto_collect: true,
  auto_smelt: false,
}

const MINER_SCAN_UNLOCK_LEVEL = 3
/** L6 快照, 同 MINER_PASSIVES_AT_L6 的纪律 (MinerSkills.oreScanRadius / oreScanCooldownTicks 在 L6 处的值)。 */
const MINER_SCAN_RADIUS_AT_L6 = 10
const MINER_SCAN_CD_TICKS_AT_L6 = 4_971
/** MinerConstants.SCAN_PULSE_TICKS = 8s, 是常量不是曲线。 */
const MINER_SCAN_PULSE_TICKS = 160

/** 探矿冷却到期时刻 (epoch ms); 0 = 就绪。初始就绪, 冷却态由第一次探测产生。 */
let minerScanReadyAt = 0

/** 矿工当前的连锁充能 (真服由 MinerSystem 每 tick 回充; mock 不跑时钟, 钉在半池附近)。 */
const MINER_CHARGE_AT_L6 = 19
const MINER_CHARGE_MAX_AT_L6 = 32

function mockMinerState(): MinerStateResult {
  const level = mockJobLevel('miner')
  const scanUnlocked = level >= MINER_SCAN_UNLOCK_LEVEL
  const chainUnlocked = level >= MINER_TOGGLE_UNLOCK_LEVEL.chain
  return {
    level,
    charge: chainUnlocked ? MINER_CHARGE_AT_L6 : 0,
    chargeMax: chainUnlocked ? MINER_CHARGE_MAX_AT_L6 : 0,
    miningFatigueImmune: level >= 4,
    toggles: (['chain', 'auto_collect', 'auto_smelt'] as const).map((skillId) => ({
      skillId,
      unlocked: level >= MINER_TOGGLE_UNLOCK_LEVEL[skillId],
      enabled: minerToggleEnabled[skillId],
    })),
    scanUnlockLevel: MINER_SCAN_UNLOCK_LEVEL,
    scanUnlocked,
    // 未解锁时半径是真值 0, 不是缺省填充 (MinerSkills.oreScanRadius 未解锁即返 0)。
    scanRadius: scanUnlocked ? MINER_SCAN_RADIUS_AT_L6 : 0,
    scanCooldownRemainingTicks: remainingTicks(minerScanReadyAt),
    passives: MINER_PASSIVES_AT_L6.map((line) => ({ ...line })),
  }
}

/** mock 玩家脚下坐标。真服由 sender 自带; 探测命中点绕它铺一圈, 同样的入参永远画出同一组坐标。 */
const MOCK_PLAYER_POS = { x: 128, y: 40, z: -64 } as const

function mockMinerScan(): MinerScanResult {
  const level = mockJobLevel('miner')
  if (level < MINER_SCAN_UNLOCK_LEVEL) {
    throw businessFailure(
      'job.miner.scan',
      'SKILL_LOCKED',
      `矿物探测需要矿工 ${String(MINER_SCAN_UNLOCK_LEVEL)} 级`,
      false,
      {
        skill: 'ore_scan',
        requiredLevel: String(MINER_SCAN_UNLOCK_LEVEL),
        currentLevel: String(level),
      },
    )
  }
  const remaining = remainingTicks(minerScanReadyAt)
  if (remaining > 0) {
    throw businessFailure('job.miner.scan', 'SKILL_ON_COOLDOWN', '矿物探测仍在冷却中', false, {
      skill: 'ore_scan',
      remainingTicks: String(remaining),
    })
  }
  minerScanReadyAt = Date.now() + MINER_SCAN_CD_TICKS_AT_L6 * MS_PER_TICK
  /*
   * 命中点数与半径都是服务端裁决的 (单矿种一次 + 有限半径 + 64 条硬顶), 前端不得放大。
   * 矿种同样由服务端按固定优先序自选 —— 没有入参能影响它, 故这里也只回铁矿这一种。
   */
  const hits = Array.from({ length: 6 }, (_unused, index) => {
    const angle = (index / 6) * Math.PI * 2
    const distance = MINER_SCAN_RADIUS_AT_L6 * (0.35 + (index % 4) * 0.15)
    return {
      x: MOCK_PLAYER_POS.x + Math.round(Math.cos(angle) * distance),
      y: MOCK_PLAYER_POS.y - (index % 5),
      z: MOCK_PLAYER_POS.z + Math.round(Math.sin(angle) * distance),
    }
  })
  return {
    oreItemId: 'minecraft:iron_ore',
    oreDescriptionId: 'block.minecraft.iron_ore',
    hits,
    radius: MINER_SCAN_RADIUS_AT_L6,
    pulseTicks: MINER_SCAN_PULSE_TICKS,
    scanCooldownRemainingTicks: MINER_SCAN_CD_TICKS_AT_L6,
  }
}

const FARMER_CROP_ITEM_ID = 'miningdim:farmer_wheat'
const FARMER_CROP_DESCRIPTION_ID = 'item.miningdim.farmer_wheat'
/** FarmerConstants 的三个真值 (纯常量, 非曲线)。 */
const FARMER_DAILY_SOFTCAP = 2_160
const FARMER_BASE_PRICE = 1
const FARMER_PRICE_FLOOR_RATIO = 0.25

/**
 * 起始已售株数刻意停在软上限前两株: 面板一打开是"还没降价"的样子, 卖一次就能亲眼看见跨过软上限之后
 * 单价掉到 0 —— 那正是 FarmerSellResult.credited 可能为 0 而物品照扣的那一态, 不铺出来就没人会去处理它。
 */
let farmerSoldToday = FARMER_DAILY_SOFTCAP - 2

/**
 * 下一株的收购单价。
 *
 * 这不是"把收购曲线复刻一遍": 真曲线是 floor(basePrice * max(0.25, 0.97^超出量)), 而 basePrice 恒为 1 时
 * 它只有两个取值 —— 软上限内 1, 超出一株即被下取整抹成 0。这里直接写出这两个真值; 一旦服务端调高
 * basePrice, 本函数就不再成立, 面板显示的永远以回执为准。
 */
function farmerNextUnitPrice(soldToday: number): number {
  return soldToday < FARMER_DAILY_SOFTCAP ? FARMER_BASE_PRICE : 0
}

/** [tierId, 解锁等级, 成长分钟, 每次产量] —— FarmerTier 的四个字段, 纯枚举常量, 照抄真值。 */
const FARMER_TIER_ROWS: readonly (readonly [string, number, number, number])[] = [
  ['low', 1, 10, 2],
  ['medium', 3, 8, 3],
  ['high', 5, 6, 4],
  ['premium', 7, 5, 5],
  ['supreme', 9, 4, 6],
]

function mockFarmerTiers(level: number): FarmerTierRow[] {
  return FARMER_TIER_ROWS.map(([tierId, unlockLevel, growthMinutes, yieldPerHarvest]) => ({
    tierId,
    nameKey: `block.miningdim.farmer_farmland_${tierId}`,
    unlockLevel,
    unlocked: level >= unlockLevel,
    growthMinutes,
    yieldPerHarvest,
    // 与 FarmerCropTable.row 同一派生式 (纯除法, 不是平衡规则)。
    wheatPerHour: (60 / growthMinutes) * yieldPerHarvest,
  }))
}

function mockFarmerState(): FarmerStateResult {
  const level = mockJobLevel('farmer')
  return {
    level,
    crop: { itemId: FARMER_CROP_ITEM_ID, descriptionId: FARMER_CROP_DESCRIPTION_ID },
    soldToday: farmerSoldToday,
    dailySoftCap: FARMER_DAILY_SOFTCAP,
    basePrice: FARMER_BASE_PRICE,
    priceFloorRatio: FARMER_PRICE_FLOOR_RATIO,
    nextUnitPrice: farmerNextUnitPrice(farmerSoldToday),
    farmlandTiers: mockFarmerTiers(level),
  }
}

/** 从背包扣掉 amount 株农夫小麦 (跨槽位按种类扣, 与 Inventory.clearOrCountMatchingItems 同语义), 返回实扣数。 */
function chargeFarmerWheat(amount: number): number {
  let left = amount
  for (const stack of [...inventory]) {
    if (left <= 0) {
      break
    }
    if (stack.itemId !== FARMER_CROP_ITEM_ID) {
      continue
    }
    const taken = Math.min(stack.count, left)
    stack.count -= taken
    left -= taken
    if (stack.count === 0) {
      inventory.splice(inventory.indexOf(stack), 1)
    }
  }
  return amount - left
}

function mockFarmerSell(payload: FarmerSellPayload): FarmerSellResult {
  if (!Number.isInteger(payload.count) || payload.count < 1) {
    throw businessFailure('job.farmer.sell', 'INVALID_REQUEST', '出售株数必须是 >= 1 的整数', false, {
      field: 'count',
      value: String(payload.count),
    })
  }
  const owned = inventory
    .filter((item) => item.itemId === FARMER_CROP_ITEM_ID)
    .reduce((sum, item) => sum + item.count, 0)
  if (owned <= 0) {
    throw businessFailure('job.farmer.sell', 'NOTHING_TO_SELL', '背包里没有可出售的农夫小麦', false, {
      itemId: FARMER_CROP_ITEM_ID,
    })
  }
  // 先扣物后发钱, 与 FarmerWheatSellService.sell 同序: 发币量严格锚定已离手的小麦数。
  const removed = chargeFarmerWheat(Math.min(owned, payload.count))
  let credited = 0
  for (let index = 0; index < removed; index += 1) {
    credited += farmerNextUnitPrice(farmerSoldToday + index)
  }
  farmerSoldToday += removed
  wallet.credit += credited
  return {
    soldCount: removed,
    credited,
    soldToday: farmerSoldToday,
    nextUnitPrice: farmerNextUnitPrice(farmerSoldToday),
  }
}

/** [qualityId, tier, maxEffects, noFailure, combatUnlocked, rawXp] —— ChefQuality 五档 + ChefConfig 默认经验。 */
const CHEF_QUALITY_ROWS: readonly (readonly [string, number, number, boolean, boolean, number])[] = [
  ['low', 0, 1, false, false, 50],
  ['medium', 1, 1, false, false, 80],
  ['high', 2, 2, false, true, 130],
  ['extraordinary', 3, 2, true, true, 220],
  ['radiant', 4, 3, true, true, 400],
]

/** [effectId, combat, negative, windowed, unit, 5 档 magnitude, 5 档时长秒] —— ChefEffectType 顺序 + ChefConfig 默认值。 */
type ChefEffectSeed = readonly [
  string,
  boolean,
  boolean,
  boolean,
  ChefEffectUnit,
  readonly [number, number, number, number, number],
  readonly [number, number, number, number, number],
]

const CHEF_EFFECT_ROWS: readonly ChefEffectSeed[] = [
  ['amplify', false, false, false, 'mul_x100', [120, 150, 200, 300, 500], [0, 0, 0, 0, 0]],
  ['nourish_food', false, false, false, 'mul_x100', [150, 200, 300, 400, 800], [0, 0, 0, 0, 0]],
  ['aftertaste_sat', false, false, false, 'mul_x100', [150, 200, 300, 400, 500], [0, 0, 0, 0, 0]],
  ['sated_jump', false, false, false, 'level', [1, 2, 3, 4, 5], [0, 0, 0, 0, 0]],
  // 战斗向只在高/超凡/闪耀解锁, 低/中两档是真值 0 而不是缺数据。
  ['nourish_heal', true, false, false, 'permille', [0, 0, 75, 100, 1000], [0, 0, 0, 0, 0]],
  ['purify', true, false, false, 'count', [0, 0, 3, 4, 99], [0, 0, 0, 0, 0]],
  ['oversalt', false, true, false, 'none', [0, 0, 0, 0, 0], [0, 0, 0, 0, 0]],
  ['spoiled', false, true, false, 'none', [0, 0, 0, 0, 0], [0, 0, 0, 0, 0]],
  [
    'endurance',
    false,
    false,
    true,
    'permille',
    [150, 300, 500, 700, 900],
    [120, 180, 300, 480, 900],
  ],
  ['refresh', false, false, false, 'level', [1, 2, 3, 4, 5], [90, 150, 240, 360, 600]],
  [
    'night_sight',
    false,
    false,
    false,
    'seconds',
    [60, 120, 240, 480, 900],
    [60, 120, 240, 480, 900],
  ],
  ['shield', true, false, true, 'permille', [0, 0, 40, 60, 80], [120, 120, 120, 120, 120]],
  ['grease', true, false, true, 'permille', [0, 0, 300, 450, 600], [120, 120, 120, 120, 120]],
  ['aftertaste_regen', true, false, true, 'permille', [0, 0, 50, 60, 100], [30, 30, 30, 30, 30]],
  ['stable_aim', true, false, true, 'permille', [0, 500, 700, 850, 1000], [60, 60, 60, 60, 60]],
  // 翻车负面只在低/中/高掷出, 超凡/闪耀 noFailure 恒 0。
  ['underdone', false, true, false, 'permille', [800, 500, 250, 0, 0], [12, 8, 6, 0, 0]],
  ['scorched', false, true, false, 'permille', [80, 50, 30, 0, 0], [0, 0, 0, 0, 0]],
  ['nausea', false, true, false, 'level', [2, 1, 1, 0, 0], [8, 6, 4, 0, 0]],
]

/** ChefQualityResolver 的等级 -> 品质上限门 (纯阶梯常量)。 */
function chefQualityCapTier(level: number): number {
  if (level >= 9) {
    return 4
  }
  if (level >= 7) {
    return 3
  }
  if (level >= 4) {
    return 2
  }
  if (level >= 2) {
    return 1
  }
  return 0
}

function mockChefState(): ChefStateResult {
  const level = mockJobLevel('chef')
  const qualities: ChefQualityRow[] = CHEF_QUALITY_ROWS.map(
    ([qualityId, tier, maxEffects, noFailure, combatUnlocked, rawXp]) => ({
      qualityId,
      tier,
      nameKey: `chef.quality.prefix.${qualityId}`,
      maxEffects,
      noFailure,
      combatUnlocked,
      rawXp,
    }),
  )
  const effects: ChefEffectRow[] = CHEF_EFFECT_ROWS.map(
    ([effectId, combat, negative, windowed, unit, magnitudes, durationSeconds]) => ({
      effectId,
      labelKey: `chef.effect.${effectId}`,
      combat,
      negative,
      windowed,
      unit,
      magnitudes: [...magnitudes],
      durationSeconds: [...durationSeconds],
    }),
  )
  return {
    level,
    qualityCapTier: chefQualityCapTier(level),
    qualities,
    effects,
    // ChefConfig.TABLE_USE_COST_CREDIT 的默认值; 运营改 toml 即变, 面板不得抄这个数。
    seasoningCostCredit: 5,
  }
}

/** [wineId, 该玩家永久层数] —— WineType 九种, 顺序即枚举声明序。层数铺了 0 / 中间 / 满层三种形态。 */
const BREWER_BREW_ROWS: readonly (readonly [string, number])[] = [
  ['brandy', 0],
  ['vodka', 5],
  ['gin', 3],
  ['rum', 0],
  ['tequila', 1],
  ['maotai', 0],
  ['whiskey', 4],
  ['champagne', 0],
  ['moonshine', 5],
]

/** BrewRecipes 九条配方的原料表 (物品 id + 计数), 逐字照抄 Java 侧的精确匹配表。 */
const BREWER_RECIPE_INPUTS: Readonly<Record<string, readonly (readonly [string, number])[]>> = {
  brandy: [
    ['minecraft:wheat', 16],
    ['minecraft:apple', 4],
  ],
  vodka: [['minecraft:wheat', 32]],
  gin: [
    ['minecraft:wheat', 16],
    ['minecraft:sugar', 4],
  ],
  rum: [
    ['minecraft:sugar_cane', 8],
    ['minecraft:wheat', 16],
  ],
  tequila: [
    ['minecraft:carrot', 8],
    ['minecraft:wheat', 16],
  ],
  maotai: [
    ['minecraft:wheat', 16],
    ['minecraft:wheat_seeds', 8],
  ],
  whiskey: [['minecraft:wheat', 24]],
  champagne: [
    ['minecraft:wheat', 16],
    ['minecraft:sugar', 4],
    ['minecraft:apple', 2],
  ],
  moonshine: [
    ['minecraft:wheat', 24],
    ['minecraft:sugar', 8],
  ],
}

/** 月光满层固化的 5 条 (8 选 5, MoonshinePerk 的前五个 id)。上面月光正好是满层, 故这里非空。 */
const BREWER_MOONSHINE_PERK_IDS: readonly string[] = [
  'knockback_res',
  'plated',
  'lucky',
  'swift',
  'night_vision',
]

/** 原版物品 id -> 翻译键 (配方原料用; 原版命名规则 item.minecraft.<path>, 这几味都不是方块)。 */
function vanillaItemDescriptionId(itemId: string): string {
  return `item.minecraft.${itemId.slice('minecraft:'.length)}`
}

function mockBrewerState(): BrewerStateResult {
  const brews: BrewerBrewEntry[] = BREWER_BREW_ROWS.map(([wineId, permanentStacks]) => ({
    wineId,
    itemId: `miningdim:wine_${wineId}`,
    descriptionId: `item.miningdim.wine_${wineId}`,
    permanentStacks,
  }))
  const recipes: BrewerRecipeRow[] = BREWER_BREW_ROWS.map(([wineId]) => {
    const inputs = BREWER_RECIPE_INPUTS[wineId]
    if (inputs === undefined) {
      throw new Error(`mock 数据缺陷: 酿酒配方表里没有 ${wineId}`)
    }
    return {
      wineId,
      inputs: inputs.map(([itemId, count]) => ({
        itemId,
        descriptionId: vanillaItemDescriptionId(itemId),
        count,
      })),
    }
  })
  return {
    level: mockJobLevel('brewer'),
    // BrewerConstants.MAX_LAYERS_PER_TYPE。
    maxLayersPerType: 5,
    brews,
    moonshinePerks: BREWER_MOONSHINE_PERK_IDS.map((perkId) => ({
      perkId,
      labelKey: `brewer.moonshine.${perkId}`,
    })),
    recipes,
    // BrewerConstants.MILLIS_PER_VINTAGE_YEAR = 现实一天一个年份。
    millisPerVintageYear: 86_400_000,
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
    case 'job.progress':
      return mockJobProgress()
    case 'job.miner.state':
      return mockMinerState()
    case 'job.miner.scan':
      return mockMinerScan()
    case 'job.farmer.state':
      return mockFarmerState()
    case 'job.farmer.sell':
      return mockFarmerSell(payload as FarmerSellPayload)
    case 'job.chef.state':
      return mockChefState()
    case 'job.brewer.state':
      return mockBrewerState()
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
    case 'market.feePreview':
      return mockMarketFeePreview(payload as MarketFeePreviewPayload)
    case 'market.p2pCap':
      return mockMarketP2pCap()
    case 'market.pendingPayout':
      return mockMarketPendingPayout()
    case 'market.tradable':
      return mockMarketTradable(payload as MarketTradablePayload)
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
