/**
 * 假数据后端: 让整套 UI 脱离 Minecraft 就能在普通浏览器里跑起来改设计。
 *
 * 只在 dev server 且宿主未注入时被 bridge.ts 动态加载 (生产构建整块摇掉)。
 *
 * 它是什么: 契约形状的忠实复刻 + 一点点内存状态, 好让"挂单 -> 我的挂单 -> 撤单"这类闭环在浏览器里走得通。
 * 它不是什么: 服务端业务规则的第二实现。手续费率、开箱概率、等级门、OP 门控一律不复刻 ——
 * 复刻一份必然与 Java 侧漂移, 而漂移的假规则比没有规则更误导设计判断。
 *
 * 这条原则有五处**已知且刻意**的近似, 读数时不要当真值:
 *   1. market.place / market.feePreview 的 listFee 用同一个固定比例占位。真费率由服务端 MarketFee 按挂价
 *      对 V0 的偏离度算, 量级可以差好几倍; 前端任何时候都以回执里的 listFee 为准, 不得照这个比例自己算。
 *   2. case.open 的中奖皮肤按 openingId 哈希在皮肤表里均匀取, 不按 weights 抽。于是回执里的
 *      weights 是线上真值, 而 reel 与中奖结果的稀有度分布不是 —— 拿这一页评估"金色出现得多不多"必然错。
 *   3. market.tradable / market.place 的可交易判定是服务端 MarketTradeWhitelist 的等价复刻 (塔罗牌只有
 *      最低品质 R 可挂)。它比前两条更接近真值 (规则本身只有三条分支), 但仍是第二份实现: 服务端改白名单时
 *      本文件必须跟着改, 否则设计评审会照着一套过期规则做界面。
 *   4. economy.today / economy.priceTable 的 creditFaucetNextFactor 是**固定占位系数**, 不是衰减主闸那条
 *      按档递减的几何主项。它只保证落在 (0.01, 1.0) 内 (即"已衰减未触底"), 拿这一页反推主闸曲线必然错。
 *   5. economy.status 的 afkFrozen 只按挖掘侧一条判据算, 而真服要求"无挖掘 && 无显著位移"两条同时成立
 *      (mock 手里没有位移侧数据)。于是 mock 会比真服更早显示冻结 —— 面板文案仍要按真服的两条判据写。
 * 五处都不改成"更真"的实现: 真实现会与 Java 侧无声漂移, 而漂移的假规则比明写的近似危险得多。
 *
 * 与上面五条不同的另一类东西 (不是近似, 照抄即真值): 各 config / 枚举的**常量表** (塔罗定价与 CD、
 * 干员分级表、纳米板档表、军火台数量、婚姻五项定价、精英星表与词条表)。它们是定长常量而不是曲线,
 * 逐字照抄不会算错; 但服务端改 toml / 改枚举时本文件同样要跟着改。
 *
 * 假数据刻意铺满边界值, 因为这些正是设计稿最容易漏掉的形态:
 *   - 空列表      market.list 翻到第 2 页即空
 *   - 超长中文名  枪匠枪管的 27 字名, 撞挂单行/物品格/表头三处截断
 *   - 极大数值    一条 unitPrice = 2^53-1 的挂单, 撞金额格式化与 Java long 的精度边界
 *   - 零余额      青辉石余额为 0 (负余额不可能, 服务端不允许)
 *   - 名字缺席    未改名物品没有 displayName 键、未翻译的键 i18n 原样返回
 *   - 注册表缺失  一件已卸载 mod 的遗留物品: market 回退成 itemId, admin 回退成空串 (两处口径本就不同)
 */

import { getWorld, mutateWorld } from '../mock/store'
import type { WebUiActionName } from './actions'
import { SERVER_ACTIONS } from './actions'
import type { PayloadOf, ResultOf } from './bridge'
import { SERVER_FAILURE_CODE, WebUiCallError } from './bridge'
import type {
  AdminEconomyBalancePayload,
  AdminEconomyBalanceResult,
  AdminEconomySetPayload,
  AdminEconomySetResult,
  AdminItemEntry,
  AdminJobSetLevelPayload,
  AdminJobSetLevelResult,
  AdminListItemsPayload,
  AdminListItemsResult,
  AdminMiningResetPayload,
  AdminMiningResetResult,
  AdminSetBaseValuePayload,
  AdminSetBaseValueResult,
  AgentAffixEntry,
  AgentScanResult,
  AgentScanTarget,
  AgentSealCategory,
  AgentSealOutcomeCode,
  AgentSealPayload,
  AgentSealResult,
  AgentStateResult,
  ArmorEffectRow,
  BaseValueSource,
  Blueprint,
  BlueprintsResult,
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
  ChampionAffixPool,
  ChampionAffixQuality,
  ChampionAffixRow,
  ChampionCodexResult,
  ChampionDistributionRow,
  ChampionInspectAffix,
  ChampionInspectPayload,
  ChampionInspectResult,
  ChampionQualityRow,
  ChampionStarRow,
  ChefEffectRow,
  ChefEffectUnit,
  ChefQualityRow,
  ChefStateResult,
  ClientI18nPayload,
  ClientI18nResult,
  ClientPlayCaseSoundPayload,
  ClientPlayCaseSoundResult,
  EconomyPriceAnchor,
  EconomyPriceTableResult,
  EconomyStatusResult,
  EconomyTodayResult,
  EngineerStatLine,
  EngineerStateResult,
  FarmerSellPayload,
  FarmerSellResult,
  FarmerStateResult,
  FarmerTierRow,
  HighValueOreId,
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
  MarriageBuyRingResult,
  MarriageDivorceResult,
  MarriageIncomingProposal,
  MarriageOutgoingProposal,
  MarriageProposePayload,
  MarriageProposeResult,
  MarriageRespondPayload,
  MarriageRespondResult,
  MarriageSharedInvResult,
  MarriageStateResult,
  MarriageStatus,
  MarriageWedOutcomeCode,
  MarriageWedPayload,
  MarriageWedResult,
  MinerScanResult,
  MinerStateResult,
  MinerToggleState,
  MiningDifficulty,
  MiningEnterPayload,
  MiningEnterResult,
  MiningGenState,
  MiningInstanceRow,
  MiningLeaveResult,
  MiningMyStatusResult,
  MiningOverviewResult,
  MunitionsStateResult,
  MunitionsStation,
  NanoRepairUnit,
  NanoTierRow,
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
  PlayerRosterResult,
  PlayerWalletResult,
  QuestBoardResult,
  QuestChainRow,
  QuestClaimPayload,
  QuestClaimResult,
  QuestItemRow,
  QuestRefreshPayload,
  QuestRefreshResult,
  QuestRow,
  QuestTurnInPayload,
  QuestTurnInResult,
  SystemEchoPayload,
  SystemEchoResult,
  SystemServerStatusResult,
  TarotBuyPackPayload,
  TarotBuyPackResult,
  TarotCooldownCategory,
  TarotDeckEntry,
  TarotPackKind,
  TarotPackRow,
  TarotQualityId,
  TarotQualityRow,
  TarotStateResult,
  WebUiBlockPos,
  WebUiCurrency,
  WebUiJobId,
  WebUiWallet,
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
  /*
   * 三种塔罗卡包与两种戒指: 登记在这里不是为了给市场多几行商品, 而是因为 job.tarot.buyPack 与
   * marriage.buyRing 会把**实物**发进背包 (depositToInventory -> requireItem 要查得到它们)。
   * 戒指分两个注册项而不是一个带 NBT 的物品 (ModItems.ENGAGEMENT_RING / WEDDING_RING), 故典礼那一步
   * 是"订婚戒指换成结婚戒指"而不是改 NBT —— 这正是 marriage.state 的 engagementRingOwned 能靠扫背包算出来的原因。
   */
  {
    itemId: 'miningdim:tarot_pack_common',
    registered: true,
    descriptionId: 'item.miningdim.tarot_pack_common',
    top: 'other',
    sub: null,
  },
  {
    itemId: 'miningdim:tarot_pack_advanced',
    registered: true,
    descriptionId: 'item.miningdim.tarot_pack_advanced',
    top: 'other',
    sub: null,
  },
  {
    itemId: 'miningdim:tarot_pack_shiny',
    registered: true,
    descriptionId: 'item.miningdim.tarot_pack_shiny',
    top: 'other',
    sub: null,
  },
  {
    itemId: 'miningdim:engagement_ring',
    registered: true,
    descriptionId: 'item.miningdim.engagement_ring',
    top: 'gear',
    sub: null,
  },
  {
    itemId: 'miningdim:wedding_ring',
    registered: true,
    descriptionId: 'item.miningdim.wedding_ring',
    top: 'gear',
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
  /*
   * 本轮新接的 28 条 action 用到的键, 全部逐字抄自 src/main/resources/assets/miningdim/lang/zh_cn.json。
   *
   * 三批**刻意不收**的键 (它们在真服客户端同样解不出, mock 补上等于造一个真服没有的好形态):
   *   tacz.gun.<templateId>.name      属 TACZ 自己的 lang, 未装 TACZ 就是解不出 (Blueprint.gunNameKey 注释已写明)
   *   effect.miningdim.nano.*         lang 条目尚未落地 (EngineerStateResult 注释)
   *   stat.miningdim.engineer.*       同上
   */
  'item.miningdim.tarot_pack_common': '普通卡包',
  'item.miningdim.tarot_pack_advanced': '高级卡包',
  'item.miningdim.tarot_pack_shiny': '闪耀卡包',
  'tooltip.miningdim.tarot.quality.r': '低级 R',
  'tooltip.miningdim.tarot.quality.sr': '中级 SR',
  'tooltip.miningdim.tarot.quality.ssr': '高级 SSR',
  'tooltip.miningdim.tarot.quality.ur': '超凡 UR',
  'tooltip.miningdim.tarot.quality.shiny': '闪耀',
  'tooltip.miningdim.tarot.arcana.fool': '0 愚者',
  'tooltip.miningdim.tarot.arcana.magician': 'I 魔术师',
  'tooltip.miningdim.tarot.arcana.high_priestess': 'II 女祭司',
  'tooltip.miningdim.tarot.arcana.empress': 'III 女皇',
  'tooltip.miningdim.tarot.arcana.emperor': 'IV 皇帝',
  'tooltip.miningdim.tarot.arcana.hierophant': 'V 教皇',
  'tooltip.miningdim.tarot.arcana.lovers': 'VI 恋人',
  'tooltip.miningdim.tarot.arcana.chariot': 'VII 战车',
  'tooltip.miningdim.tarot.arcana.strength': 'VIII 力量',
  'tooltip.miningdim.tarot.arcana.hermit': 'IX 隐士',
  'tooltip.miningdim.tarot.arcana.wheel_of_fortune': 'X 命运之轮',
  'tooltip.miningdim.tarot.arcana.justice': 'XI 正义',
  'tooltip.miningdim.tarot.arcana.hanged_man': 'XII 倒吊人',
  'tooltip.miningdim.tarot.arcana.death': 'XIII 死神',
  'tooltip.miningdim.tarot.arcana.temperance': 'XIV 节制',
  'tooltip.miningdim.tarot.arcana.devil': 'XV 恶魔',
  'tooltip.miningdim.tarot.arcana.tower': 'XVI 高塔',
  'tooltip.miningdim.tarot.arcana.star': 'XVII 星星',
  'tooltip.miningdim.tarot.arcana.moon': 'XVIII 月亮',
  'tooltip.miningdim.tarot.arcana.sun': 'XIX 太阳',
  'tooltip.miningdim.tarot.arcana.judgement': 'XX 审判',
  'tooltip.miningdim.tarot.arcana.world': 'XXI 世界',
  'item.miningdim.engagement_ring': '订婚戒指',
  'item.miningdim.wedding_ring': '结婚戒指',
  'difficulty.miningdim.easy': '简单',
  'difficulty.miningdim.medium': '普通',
  'difficulty.miningdim.hard': '困难',
  'block.miningdim.munitions_bench_high': '高级军火台',
  'block.miningdim.gunsmith_press': '机械冲压机',
  'block.miningdim.gunsmith_assembly_bench': '枪械组装台',
  'item.miningdim.gunsmith_blueprint.name': '%s 图纸',
  'gunsmith.platform.ak': 'AK',
  'gunsmith.platform.pistol': '手枪',
  'gunsmith.part.barrel': '基础枪管',
  'gunsmith.part.bolt': '基础枪机',
  'gunsmith.part.handguard': '基础护木',
  'gunsmith.part.grip': '基础握把',
  'gunsmith.part.stock': '基础枪托',
  'gunsmith.part.slide': '基础套筒',
  'gunsmith.part.trigger': '基础扳机',
  'gunsmith.part.hammer': '基础击锤',
  'tier.miningdim.nano.low': '低级',
  'tier.miningdim.nano.medium': '中级',
  'tier.miningdim.nano.high': '高级',
  'tier.miningdim.nano.superior': '极品',
  'tier.miningdim.nano.transcendent': '超凡',
  'tier.miningdim.nano.radiant': '闪耀',
  'item.miningdim.nano_plate_low': '低级纳米维修套件',
  'item.miningdim.nano_plate_medium': '中级纳米维修套件',
  'item.miningdim.nano_plate_high': '高级纳米维修套件',
  'item.miningdim.nano_plate_superior': '极品纳米维修套件',
  'item.miningdim.nano_plate_transcendent': '超凡纳米维修套件',
  'item.miningdim.nano_plate_radiant': '闪耀纳米维修套件',
  'affix.champions.composite_armor': '复合装甲',
  'affix.champions.heavy_armor': '重型护甲',
  'affix.champions.flammable_regen': '易燃再生',
  'affix.champions.fortitude_shield': '刚毅护盾',
  'affix.champions.gigantism': '巨大化',
  'affix.champions.burning': '燃烧',
  'affix.champions.heavy_cannon': '重炮',
  'affix.champions.frost': '寒霜',
  'affix.champions.overdrive': '超速移动',
  'affix.champions.thunder': '天雷',
  'affix.champions.death_mark': '命定之死',
  'affix.champions.summon_support': '支援',
  // 原版实体名 (champion.inspect / job.agent.* 的目标行)。
  'entity.minecraft.zombie': '僵尸',
  'entity.minecraft.skeleton': '骷髅',
  'entity.minecraft.wither_skeleton': '凋灵骷髅',
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
 * world.jobs.progress (本文件的 mockAdminJobSetLevel 就是往那里写)。本文件若另存一份, 设计评审时会出现
 * "开关拨了但首页没变"这种只有 mock 才有的假故障。
 *
 * 钱包的叠加层 world.walletOverlay 已到达它自己写明的销毁条件: 曾经记在那里的 planned 域收支 (卖菜/
 * 买卡包/买戒指) 本轮全部核销成真契约, 扣款改由本文件那份 wallet 直接承担, 于是它的两个分量恒为 0。
 * 这里仍把它加进来只是为了不越界改 mock/store.ts —— 那个字段该由删它的那次改动一并清掉。
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
    /*
     * 两栏与 economy.today 读同一对常量, 不各存一份。
     *
     * 真服这两栏与 economy.today 的同名字段取自账本同一张 daily_counters 表 (只是 counter_key 不同),
     * 恒等; mock 若在两处各写一个数字, 首页与经济页会给出两个互相矛盾的"今日"。
     * 旧实现是按 decayFactor 从 mock 世界的 earnedToday 反推毛额, 那份世界状态已随本轮核销删除。
     */
    todayCreditFaucetGross: MOCK_TODAY_CREDIT_FAUCET_GROSS,
    // 青辉石走硬截断, 账本落的就是实发额, 与上一栏刻意不对称。
    todayAzureIn: MOCK_TODAY_AZURE_IN,
  }
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
 * 面板域与顺序 = 服务端 HubWebUiActions 的 11 项硬编码表 (任务叫 quests, 精英怪图鉴叫 codex)。
 * 这里只发 panelId/enabled/lockCode, 展示层三项 (route/label/iconItemId) 归前端 lib/panels.ts。
 */
const HUB_PANEL_IDS: readonly HubPanelId[] = [
  'home',
  'market',
  'shop',
  'jobs',
  'mining',
  'quests',
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
// 本轮新接 28 条的共用小工具
// ============================================================

/**
 * 定长常量表取值。noUncheckedIndexedAccess 下 `table[i]` 的类型是 `T | undefined`, 而下面按等级/档位取值的
 * 表全是编译期定长的照抄表 —— 取不到就是 mock 数据缺陷本身, 当场抛比带着一个 undefined 一路走下去好查得多。
 */
function requireAt<T>(table: readonly T[], index: number, what: string): T {
  const value = table[index]
  if (value === undefined) {
    throw new Error(`mock 数据缺陷: ${what} 没有下标 ${String(index)}`)
  }
  return value
}

/** 被拒入参回显的截断规则 (服务端 WebUiPayloads: 超 64 字符截断并追加省略号)。 */
function truncateValue(value: string): string {
  return value.length <= 64 ? value : `${value.slice(0, 64)}...`
}

/**
 * 服务端 gameTime (tick)。
 *
 * marriage.* 与 mining.* 的时刻一律是 gameTime 而非 epoch millis (服务端没有可信墙钟), 每个回执都附带一个
 * 当前值作换算基准。这里按真实秒数往前走: 钉死一个常数会让"还有 3 天"永远是 3 天, 倒计时组件在假数据
 * 模式下就一次都没被验证过。
 */
const GAME_TICK_BASE = 5_184_000

function gameTick(): number {
  return GAME_TICK_BASE + Math.floor((Date.now() - NOW) / MS_PER_TICK)
}

/** 1 现实天 = 1728000 tick (marriage 契约头的换算口径; 婚龄与再婚冷却都按它折算)。 */
const TICKS_PER_DAY = 1_728_000

// ============================================================
// 在线玩家名册 (admin.economy.* / marriage.propose 那条"只认在线玩家"的门)
// ============================================================

type MockPlayerRow = {
  name: string
  uuid: string
  /** 离线者一律被 INVALID_REQUEST 拒 (admin.economy.* 与 marriage.propose 同一条纪律)。 */
  online: boolean
}

/**
 * 假名册。真服的在线玩家来自 PlayerList, 本文件只能自己列一份; UUID 复用成交流水那张表, 免得同一个人
 * 在市场页与管理台是两个不同的 UUID。
 *
 * 必须留一位离线的: admin.economy.balance 对离线玩家的拒绝在面板上最容易被画成"余额 0", 而契约明写了
 * 那条拒绝要显示成"该玩家不在线/名字不对"。全员在线的名册会让这条分支在假数据模式下永远走不到。
 */
const MOCK_PLAYERS: readonly MockPlayerRow[] = [
  { name: MOCK_PLAYER_NAME, uuid: requireCounterpartyUuid(MOCK_PLAYER_NAME), online: true },
  { name: '矿工阿建', uuid: requireCounterpartyUuid('矿工阿建'), online: true },
  { name: '拍卖狂魔', uuid: requireCounterpartyUuid('拍卖狂魔'), online: true },
  { name: '鲸鱼玩家', uuid: requireCounterpartyUuid('鲸鱼玩家'), online: false },
  { name: '甜品师小柚', uuid: mockUuid(9_001), online: true },
]

/**
 * 其他玩家的账本 (admin.economy.* 用)。本人那份权威仍是文件顶部的 wallet, 不在这里再存一遍 ——
 * OP 把自己的余额调完之后, 首页与市场页必须立刻看见同一个数。
 *
 * 两个零余额是真形态: 服务端不允许负余额, 但 0 是随时可能出现的正常值, 且它正是"买不起"文案的触发点。
 */
const otherPlayerWallets = new Map<string, WebUiWallet>([
  [requireCounterpartyUuid('矿工阿建'), { credit: 86_400, azure: 12 }],
  [requireCounterpartyUuid('拍卖狂魔'), { credit: 2_450_000, azure: 0 }],
  [mockUuid(9_001), { credit: 0, azure: 3 }],
])

/** 按名字取在线玩家 (大小写不敏感, 与服务端一致); 找不到即 INVALID_REQUEST + params{field,value}。 */
function requireOnlinePlayer(action: WebUiActionName, field: string, name: unknown): MockPlayerRow {
  if (typeof name !== 'string' || name === '') {
    throw businessFailure(action, 'INVALID_REQUEST', `字段 ${field} 必须是非空字符串`, false, { field })
  }
  const hit = MOCK_PLAYERS.find(
    (player) => player.online && player.name.toLowerCase() === name.toLowerCase(),
  )
  if (hit === undefined) {
    throw businessFailure(action, 'INVALID_REQUEST', `找不到在线玩家 ${name}`, false, {
      field,
      value: truncateValue(name),
    })
  }
  return hit
}

/**
 * 在线玩家名册。与上面那道"只认在线玩家"的门读同一张表, 这一点本身就是要保住的性质:
 * 名册列出来的名字必须恰好是 marriage.propose / admin.economy.* 会接受的那些, 否则点选器里会出现
 * 一点就被 INVALID_REQUEST 拒的名字。
 *
 * 服务端不做任何过滤 (调用者自己也在名册里), 故这里也不排除本人 —— 要排除自己是前端按 player.profile
 * 的名字做的事。truncated 恒 false: 硬上限是 200 条, mock 的名册离它太远, 那条截断提示只能靠真服看见。
 */
function mockPlayerRoster(): PlayerRosterResult {
  const players = MOCK_PLAYERS.filter((player) => player.online).map((player) => ({
    name: player.name,
    uuid: player.uuid,
  }))
  return { players, total: players.length, truncated: false }
}

/** 该玩家的账本引用 (本人走顶部那份, 其余走上面的表)。返回引用而非副本: admin.economy.set 要就地改。 */
function walletOf(player: MockPlayerRow): WebUiWallet {
  if (player.name === MOCK_PLAYER_NAME) {
    return wallet
  }
  const other = otherPlayerWallets.get(player.uuid)
  if (other === undefined) {
    throw new Error(`mock 数据缺陷: 玩家 ${player.name} 没有登记账本`)
  }
  return other
}

// ============================================================
// job.tarot.* (TarotConfig 默认值照抄: 卡包定价/日限/保底/CD 全是常量, 不是等级曲线)
// ============================================================

/** TarotArcana 声明序, 下标即 cardId (0-21)。 */
const TAROT_ARCANA_IDS: readonly string[] = [
  'fool',
  'magician',
  'high_priestess',
  'empress',
  'emperor',
  'hierophant',
  'lovers',
  'chariot',
  'strength',
  'hermit',
  'wheel_of_fortune',
  'justice',
  'hanged_man',
  'death',
  'temperance',
  'devil',
  'tower',
  'star',
  'moon',
  'sun',
  'judgement',
  'world',
]

/** [qualityId, tierIndex, requiredLevel, rawXp] —— TarotQuality 五档 + TarotConfig 的 rawXp 默认值。 */
const TAROT_QUALITY_ROWS: readonly (readonly [TarotQualityId, number, number, number])[] = [
  ['r', 0, 1, 8],
  ['sr', 1, 3, 16],
  ['ssr', 2, 5, 32],
  ['ur', 3, 8, 60],
  // shiny 不走四档缩放 (走签名大招), tierIndex 是哨兵 -1 而不是 4。
  ['shiny', -1, 10, 120],
]

/** TarotConfig 的四个满 CD 默认值 (tick); 不是剩余量 —— 上游没有只读剩余量的入口。 */
const TAROT_COOLDOWN_TICKS = { gcd: 30, utility: 200, buff: 500, combat: 900 }

/** [packKind, 币种, 单价] —— TarotConfig 的三个定价默认值 (闪耀包收青辉石, 另两种收信用点)。 */
const TAROT_PACK_ROWS: readonly (readonly [TarotPackKind, WebUiCurrency, number])[] = [
  ['common', 'CREDIT', 200],
  ['advanced', 'CREDIT', 1_200],
  ['shiny', 'AZURE', 64],
]

const TAROT_DAILY_PACK_LIMIT = 20
const TAROT_SHARD_EXCHANGE_COST = 40
const TAROT_DUPLICATE_SHARD_REFUND = 1
const TAROT_ADVANCED_PITY_THRESHOLD = 10

/**
 * 起始已购停在日限前 2 个: 买 1 个还买得动、买 3 个就撞 RATE_LIMITED —— 两条分支在假数据模式下都走得到。
 * 与农夫的 farmerSoldToday 停在软上限前两株是同一条铺法。
 */
let tarotPacksBoughtToday = 18

/** 碎片数刻意低于兑换线 40: 面板要显示的是"还差 13 张", 给够反而看不见未达成态。 */
const TAROT_SHARDS_HELD = 27

/** 高级包保底进度 (未满 10 即"下一包不保底"那一态)。 */
const TAROT_ADVANCED_PITY_STREAK = 7

/** 三档冷却分类轮转 (真服由 datapack 逐牌指定; mock 只需保证三档在牌组里都出现)。 */
const TAROT_COOLDOWN_CATEGORIES: readonly TarotCooldownCategory[] = ['utility', 'buff', 'combat']

/** 闪耀牌 CD (tick), 按分类给分钟级的量 (真服同样按牌表逐牌配, 这里只保证量级对)。 */
const TAROT_SHINY_COOLDOWN_TICKS: Readonly<Record<TarotCooldownCategory, number>> = {
  utility: 1_200,
  buff: 2_400,
  combat: 3_600,
}

/**
 * 22 张大阿卡纳的持有状况。
 *
 * 三种形态各占约 1/3, 且必须同时存在:
 *   owned=0        未持有 (打不出这张牌) —— 面板要能画出灰掉的牌位
 *   单档持有       最常见形态
 *   跨两档持有     同一张牌同时有 R 与 SR, 正是"品质是牌的属性、不是牌与品质一对一"那条契约
 * inInventory 另在 1/4 的牌上比 owned 多一张 (背包里有别人绑定的同名牌): owned 与 inInventory 讲的是
 * "能不能打"与"背包里实际有几张"两件事, 两栏恒等的话面板上永远分不出这个区别。
 *
 * collected (复核 finding 3/5) 特意在 cardId 是 6 的倍数、owned=0 且 inInventory=0 的那几张 (0/6/12/18)
 * 上仍置 true, 用来在 dev 通路里练出"账本记着但背包/持有栏都是 0"这一态 (牌被放进箱子, 或曾经收集过、
 * 品质净额还没被打出/合成消耗掉) —— 只看 owned/inInventory 推 collected 会让这条状态在 mock 里永远造不出来。
 */
function tarotDeck(): TarotDeckEntry[] {
  return TAROT_ARCANA_IDS.map((arcanaId, cardId) => {
    const category = requireAt(TAROT_COOLDOWN_CATEGORIES, cardId % 3, '塔罗冷却分类表')
    const ownedByQuality =
      cardId === 21
        ? // 世界牌: 唯一一张持有闪耀档的牌, 撑起 shiny 那一列 (它不走四档缩放)。
          [0, 0, 1, 0, 1]
        : cardId % 3 === 0
          ? [0, 0, 0, 0, 0]
          : cardId % 3 === 1
            ? [1, 0, 0, 0, 0]
            : [2, 1, 0, 0, 0]
    const owned = ownedByQuality.reduce((sum, count) => sum + count, 0)
    const inInventory = cardId % 4 === 0 ? owned + 1 : owned
    return {
      cardId,
      arcanaId,
      nameKey: `tooltip.miningdim.tarot.arcana.${arcanaId}`,
      ownedByQuality,
      owned,
      inInventory,
      collected: owned > 0 || inInventory > 0 || cardId % 6 === 0,
      /*
       * cardDataLoaded 为真, 故这两栏一律有值。它们为 null 的那一态只在 datapack 重载中/失败时出现,
       * 而那是**整份牌组同时**为 null (不是逐牌的), 与本回执的 cardDataLoaded=true 互斥, 造不出来。
       */
      cooldownCategory: category,
      shinyCooldownTicks: TAROT_SHINY_COOLDOWN_TICKS[category],
    }
  })
}

function tarotPackRows(): TarotPackRow[] {
  return TAROT_PACK_ROWS.map(([packKind, currency, unitPrice]) => ({
    packKind,
    itemId: `miningdim:tarot_pack_${packKind}`,
    nameKey: `item.miningdim.tarot_pack_${packKind}`,
    currency,
    unitPrice,
  }))
}

function tarotPacksRemainingToday(): number {
  return Math.max(0, TAROT_DAILY_PACK_LIMIT - tarotPacksBoughtToday)
}

function mockTarotState(): TarotStateResult {
  const level = mockJobLevel('tarot')
  const qualities: TarotQualityRow[] = TAROT_QUALITY_ROWS.map(
    ([qualityId, tierIndex, requiredLevel, rawXp]) => ({
      qualityId,
      nameKey: `tooltip.miningdim.tarot.quality.${qualityId}`,
      tierIndex,
      requiredLevel,
      // 纯等级比较 (testMode 关着时它才等于"打得出"), 不是别的门。
      usable: level >= requiredLevel,
      rawXp,
    }),
  )
  return {
    level,
    // 测试模式关着: 开着的话买包免费且不计日限, 那是运营开关不是常态, mock 不默认打开。
    testMode: false,
    shards: TAROT_SHARDS_HELD,
    shardExchangeCost: TAROT_SHARD_EXCHANGE_COST,
    duplicateShardRefund: TAROT_DUPLICATE_SHARD_REFUND,
    qualities,
    cooldownTicks: { ...TAROT_COOLDOWN_TICKS },
    cardDataLoaded: true,
    deck: tarotDeck(),
    packs: tarotPackRows(),
    packsBoughtToday: tarotPacksBoughtToday,
    packDailyLimit: TAROT_DAILY_PACK_LIMIT,
    packsRemainingToday: tarotPacksRemainingToday(),
    advancedPityStreak: TAROT_ADVANCED_PITY_STREAK,
    advancedPityThreshold: TAROT_ADVANCED_PITY_THRESHOLD,
  }
}

/**
 * 买卡包。买到的是**卡包物品**而不是卡牌 (服务端没有"买即开"的入口), 故这里也只往背包里发实物,
 * 不掷牌、不发碎片 —— 回执里根本没有 drawn/fragmentsGained 这两个字段。
 */
function mockTarotBuyPack(payload: TarotBuyPackPayload): TarotBuyPackResult {
  const row = TAROT_PACK_ROWS.find(([packKind]) => packKind === payload.kind)
  if (row === undefined) {
    throw businessFailure('job.tarot.buyPack', 'INVALID_REQUEST', '卡包种类非法', false, {
      field: 'kind',
      value: truncateValue(String(payload.kind)),
    })
  }
  if (!Number.isInteger(payload.count) || payload.count < 1 || payload.count > 64) {
    throw businessFailure('job.tarot.buyPack', 'INVALID_REQUEST', '购买个数必须是 [1,64] 内的整数', false, {
      field: 'count',
      value: String(payload.count),
    })
  }
  const [packKind, currency, unitPrice] = row
  const remaining = tarotPacksRemainingToday()
  if (payload.count > remaining) {
    // 暂借 RATE_LIMITED (服务端补 DAILY_LIMIT_REACHED 之前就是这条码), params 逐字对齐契约注释。
    throw businessFailure('job.tarot.buyPack', 'RATE_LIMITED', '今日卡包购买额度不足', false, {
      scope: 'tarot_pack_daily',
      requested: String(payload.count),
      remainingToday: String(remaining),
      dailyLimit: String(TAROT_DAILY_PACK_LIMIT),
    })
  }
  const totalPrice = unitPrice * payload.count
  const balance = currency === 'CREDIT' ? wallet.credit : wallet.azure
  if (balance < totalPrice) {
    throw businessFailure('job.tarot.buyPack', 'INSUFFICIENT_FUNDS', '余额不足', false, {
      currency,
      totalPrice: String(totalPrice),
      packKind,
    })
  }
  if (currency === 'CREDIT') {
    wallet.credit -= totalPrice
  } else {
    wallet.azure -= totalPrice
  }
  tarotPacksBoughtToday += payload.count
  const itemId = `miningdim:tarot_pack_${packKind}`
  depositToInventory('job.tarot.buyPack', itemId, payload.count)
  return {
    packKind,
    itemId,
    nameKey: `item.miningdim.tarot_pack_${packKind}`,
    count: payload.count,
    currency,
    unitPrice,
    // 实扣额。testMode 恒 false, 故它等于 unitPrice*count; 真服测试模式下会是 0, 前端仍不得自己乘。
    totalPrice,
    testMode: false,
    packsBoughtToday: tarotPacksBoughtToday,
    packsRemainingToday: tarotPacksRemainingToday(),
    packDailyLimit: TAROT_DAILY_PACK_LIMIT,
  }
}

// ============================================================
// job.agent.* (AgentSkillTable 的分级常量表照抄; 它是一张定长表, 不是等级曲线)
// ============================================================

/** 职业等级钳制 (AgentSkillTable.clampLevel / MunitionsLevels.clampLevel 同一形态)。 */
function clampJobLevel(level: number): number {
  return Math.min(10, Math.max(1, level))
}

/**
 * 扫描范围 (格)。L10 的表值在 Java 里是哨兵 -1 (跨区块), 服务端已解析成 max(448, 视距区块 x 16);
 * 默认视距下那个 max 就取 448, 故这里直接写 448 —— 前端拿到的永远是正数, 不该看见 -1。
 */
const AGENT_SCAN_RANGE_BLOCKS: readonly number[] = [64, 96, 128, 160, 200, 256, 320, 384, 448, 448]

const AGENT_PASSIVE_WINDOW_SECONDS: readonly number[] = [0, 0, 8, 9, 9, 10, 11, 11, 11, 12]
const AGENT_PASSIVE_CD_SECONDS: readonly number[] = [0, 0, 30, 30, 26, 24, 22, 20, 20, 18]
const AGENT_MECHANIC_WINDOW_SECONDS: readonly number[] = [0, 0, 0, 0, 0, 0, 0, 3, 4, 5]
const AGENT_MECHANIC_CD_SECONDS: readonly number[] = [0, 0, 0, 0, 0, 0, 0, 20, 20, 45]
const AGENT_ENHANCED_REWARD: readonly number[] = [1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.7, 2.85, 3]
const AGENT_DAILY_BOUNTY_SLOTS: readonly number[] = [1, 1, 2, 2, 3, 3, 3, 4, 4, 5]
const AGENT_WEEKLY_BOUNTY_SLOTS: readonly number[] = [0, 0, 0, 1, 1, 1, 2, 2, 2, 3]
const AGENT_DAMAGE_BONUS_PERCENT: readonly number[] = [5, 6, 7, 8, 9, 10, 11, 12, 13, 15]

const AGENT_SEAL_UNLOCK_LEVEL = 3
const AGENT_MECHANIC_SEAL_UNLOCK_LEVEL = 8
const AGENT_WEEKLY_BOUNTY_UNLOCK_LEVEL = 4
const AGENT_WORLD_BOSS_UNLOCK_LEVEL = 8
const AGENT_SECOND_SEAL_SLOT_UNLOCK_LEVEL = 9
/** AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP, 跨 ISO 周清零。 */
const AGENT_WEEKLY_AZURE_CAP = 50

/** 脉冲 CD (秒): L1=60 线性缩到 L10=30, 整数除法与 Java 逐字一致 (取整位置不同会差 1 秒)。 */
function agentPulseCooldownTicks(level: number): number {
  return (60 - Math.floor(((clampJobLevel(level) - 1) * 30) / 9)) * 20
}

function agentSealWindowSeconds(level: number, category: AgentSealCategory): number {
  const index = clampJobLevel(level) - 1
  return category === 'PASSIVE'
    ? requireAt(AGENT_PASSIVE_WINDOW_SECONDS, index, '被动封印窗口表')
    : requireAt(AGENT_MECHANIC_WINDOW_SECONDS, index, '机制封印窗口表')
}

function agentSealCooldownSeconds(level: number, category: AgentSealCategory): number {
  const index = clampJobLevel(level) - 1
  return category === 'PASSIVE'
    ? requireAt(AGENT_PASSIVE_CD_SECONDS, index, '被动封印 CD 表')
    : requireAt(AGENT_MECHANIC_CD_SECONDS, index, '机制封印 CD 表')
}

type MockAgentEntrySeed = {
  affixId: string
  category: AgentSealCategory
  decrypted: boolean
  /** 服务端集成层已滤掉外来/纯防御词条, 但"解密了却封不动"仍是真形态 (对应 AFFIX_NOT_SEALABLE)。 */
  sealable: boolean
}

type MockAgentTargetSeed = {
  targetNetworkId: number
  star: number
  distanceBlocks: number
  entityTypeId: string
  /** 脉冲当刻的坐标; 只有 L8+ 发出的脉冲才会把它带给前端 (见 agentTargets)。 */
  pos: WebUiBlockPos
  entries: readonly MockAgentEntrySeed[]
}

/**
 * 三个扫描目标。星级刻意跨开 3/7/9:
 *   3 星  低等级干员也封得动, 是唯一能走通 OK 的那一只
 *   7 星  L7 以下封不动 (STAR_TOO_HIGH), 且带一条机制类词条 (L8 前 CATEGORY_LOCKED)
 *   9 星  基本只能看 —— 可封星级 = 干员等级, 满级前它恒被拒
 * 每个目标都留了未解密行: 那三格 (affixId/displayKey/category) 同时为 null 是服务端刻意的脱敏,
 * 面板必须把它渲染成不可点的占位; 全解密的假数据会让人做出一张"每行都能点"的表。
 */
const AGENT_TARGET_SEEDS: readonly MockAgentTargetSeed[] = [
  {
    targetNetworkId: 40_219,
    star: 3,
    distanceBlocks: 18.42,
    entityTypeId: 'minecraft:zombie',
    pos: { x: 141, y: 38, z: -52 },
    entries: [
      { affixId: 'COMPOSITE_ARMOR', category: 'PASSIVE', decrypted: true, sealable: true },
      { affixId: 'BURNING', category: 'PASSIVE', decrypted: true, sealable: true },
      // 解密了但封不动的一条 (AFFIX_NOT_SEALABLE 那一态)。
      { affixId: 'FORTITUDE_SHIELD', category: 'PASSIVE', decrypted: true, sealable: false },
      { affixId: 'HEAVY_CANNON', category: 'PASSIVE', decrypted: false, sealable: false },
    ],
  },
  {
    targetNetworkId: 40_877,
    star: 7,
    distanceBlocks: 61.25,
    entityTypeId: 'minecraft:skeleton',
    pos: { x: 96, y: 44, z: -118 },
    entries: [
      { affixId: 'HEAVY_ARMOR', category: 'PASSIVE', decrypted: true, sealable: true },
      { affixId: 'DEATH_MARK', category: 'MECHANIC', decrypted: true, sealable: true },
      { affixId: 'FROST', category: 'PASSIVE', decrypted: false, sealable: false },
      { affixId: 'OVERDRIVE', category: 'PASSIVE', decrypted: false, sealable: false },
    ],
  },
  {
    targetNetworkId: 41_003,
    star: 9,
    distanceBlocks: 143.9,
    entityTypeId: 'minecraft:wither_skeleton',
    pos: { x: -12, y: 31, z: -207 },
    entries: [
      { affixId: 'THUNDER', category: 'MECHANIC', decrypted: true, sealable: true },
      { affixId: 'GIGANTISM', category: 'PASSIVE', decrypted: false, sealable: false },
      { affixId: 'SUMMON_SUPPORT', category: 'MECHANIC', decrypted: false, sealable: false },
    ],
  },
]

/** 已被封印中的词条 (键 `<targetNetworkId>:<affixId>`)。种一条进去, AFFIX_ALREADY_SEALED 才走得到。 */
const agentSealedAffixes = new Set<string>(['40219:BURNING'])

/** 两类封印各一本 CD 账本 (封被动不会锁住机制, 这是契约明写的)。值是到期时刻 epoch ms; 0 = 就绪。 */
const agentSealReadyAt: Record<AgentSealCategory, number> = { PASSIVE: 0, MECHANIC: 0 }

/** 脉冲 CD 到期时刻 (epoch ms); 0 = 就绪。初始就绪且无快照, 与矿工探矿同一范式。 */
let agentScanReadyAt = 0

/**
 * 写快照那一刻的干员等级。
 * 契约里 pos 的判据取的是**发出脉冲时**的等级 —— L7 扫完立刻升到 L8, 旧快照里的 pos 仍是 null。
 * 存这一个数就是为了让 mock 也照这个判据答, 而不是每次拿当前等级重算。
 */
let agentSnapshotLevel = 1

function agentTargets(snapshotLevel: number): AgentScanTarget[] {
  return AGENT_TARGET_SEEDS.map((seed) => {
    const entries: AgentAffixEntry[] = seed.entries.map((entry) =>
      entry.decrypted
        ? {
            affixId: entry.affixId,
            displayKey: `affix.champions.${entry.affixId.toLowerCase()}`,
            category: entry.category,
            decrypted: true,
            sealable: entry.sealable,
            sealed: agentSealedAffixes.has(`${String(seed.targetNetworkId)}:${entry.affixId}`),
          }
        : {
            // 未解密行的三格同时为 null, 后两个布尔一律 false —— 前端本就不该让这一行可点。
            affixId: null,
            displayKey: null,
            category: null,
            decrypted: false,
            sealable: false,
            sealed: false,
          },
    )
    return {
      targetNetworkId: seed.targetNetworkId,
      star: seed.star,
      distanceBlocks: seed.distanceBlocks,
      entityTypeId: seed.entityTypeId,
      entityNameKey: `entity.minecraft.${seed.entityTypeId.slice('minecraft:'.length)}`,
      // 精确坐标绑在 L8 那一格; 未到就只能显示 distanceBlocks。
      pos: snapshotLevel >= 8 ? { ...seed.pos } : null,
      entries,
    }
  })
}

function mockAgentState(): AgentStateResult {
  const level = mockJobLevel('agent')
  // 两栏按契约恒等 (同一 pulseTick 派生), 故读同一个剩余量; 归零即快照作废, targets 一并变空。
  const remaining = remainingTicks(agentScanReadyAt)
  return {
    level,
    // 接缝恒绑定: scanOnline=false 是"Champions 未加载"那一态, 复刻它等于让整个面板在 mock 下没内容可看。
    scanOnline: true,
    scanRadiusBlocks: requireAt(AGENT_SCAN_RANGE_BLOCKS, clampJobLevel(level) - 1, '扫描范围表'),
    scanCrossChunk: level >= 10,
    scanPulseCooldownTicks: agentPulseCooldownTicks(level),
    scanCooldownRemainingTicks: remaining,
    snapshotRemainingTicks: remaining,
    // 语义是"球内还有未检视的活体", 不是"还有更多精英"; 目标只有 3 个, 没被 8 个上限截断。
    truncated: false,
    targets: remaining > 0 ? agentTargets(agentSnapshotLevel) : [],
    seal: {
      passiveUnlockLevel: AGENT_SEAL_UNLOCK_LEVEL,
      mechanicUnlockLevel: AGENT_MECHANIC_SEAL_UNLOCK_LEVEL,
      passiveUnlocked: level >= AGENT_SEAL_UNLOCK_LEVEL,
      mechanicUnlocked: level >= AGENT_MECHANIC_SEAL_UNLOCK_LEVEL,
      // 未解锁是真值 0 (不是"无限制"), 可封星级 = 干员等级。
      maxSealableStar: level >= AGENT_SEAL_UNLOCK_LEVEL ? clampJobLevel(level) : 0,
      passiveWindowSeconds: agentSealWindowSeconds(level, 'PASSIVE'),
      passiveCooldownSeconds: agentSealCooldownSeconds(level, 'PASSIVE'),
      mechanicWindowSeconds: agentSealWindowSeconds(level, 'MECHANIC'),
      mechanicCooldownSeconds: agentSealCooldownSeconds(level, 'MECHANIC'),
      passiveCooldownRemainingTicks: remainingTicks(agentSealReadyAt.PASSIVE),
      mechanicCooldownRemainingTicks: remainingTicks(agentSealReadyAt.MECHANIC),
      slotsDefault: level >= AGENT_SEAL_UNLOCK_LEVEL ? 1 : 0,
      // 双槽只在 (目标 8 星+ 且干员 L9+) 时成立, 故这一栏本身也随等级翻。
      slotsVsStar8Plus: level >= AGENT_SECOND_SEAL_SLOT_UNLOCK_LEVEL ? 2 : level >= AGENT_SEAL_UNLOCK_LEVEL ? 1 : 0,
      secondSlotUnlockLevel: AGENT_SECOND_SEAL_SLOT_UNLOCK_LEVEL,
    },
    bounty: {
      dailySlots: requireAt(AGENT_DAILY_BOUNTY_SLOTS, clampJobLevel(level) - 1, '日常悬赏槽表'),
      weeklySlots: requireAt(AGENT_WEEKLY_BOUNTY_SLOTS, clampJobLevel(level) - 1, '周常悬赏槽表'),
      weeklyUnlocked: level >= AGENT_WEEKLY_BOUNTY_UNLOCK_LEVEL,
      weeklyUnlockLevel: AGENT_WEEKLY_BOUNTY_UNLOCK_LEVEL,
      maxBountyStar: clampJobLevel(level),
      worldBossUnlocked: level >= AGENT_WORLD_BOSS_UNLOCK_LEVEL,
      worldBossUnlockLevel: AGENT_WORLD_BOSS_UNLOCK_LEVEL,
      // 悬赏接取/进度/发奖尚未上线 (F017/F078), 真实后端此计数器永远无人写入, mock 必须如实恒为 0——
      // 之前这里写死 32 只是为了让进度条"看起来不是空的", 属于伪造玩家从未达成过的既成进度, 已按复核意见改正。
      weeklyAzureGranted: 0,
      weeklyAzureCap: AGENT_WEEKLY_AZURE_CAP,
      available: false,
    },
    enhancedRewardMultiplier: requireAt(AGENT_ENHANCED_REWARD, clampJobLevel(level) - 1, '加强奖励表'),
    damageBonusPercent: requireAt(AGENT_DAMAGE_BONUS_PERCENT, clampJobLevel(level) - 1, '伤害加成表'),
    // 入职标志。false 那一态 (从未做过特勤活计) 会把整页数值变成"一分不吃", 不作默认。
    activeAgent: true,
  }
}

function mockAgentScan(): AgentScanResult {
  const level = mockJobLevel('agent')
  const remaining = remainingTicks(agentScanReadyAt)
  if (remaining > 0) {
    // 与矿工探矿复用同一条码, 靠 params.skill 区分是哪个技能在冷却。
    throw businessFailure('job.agent.scan', 'SKILL_ON_COOLDOWN', '战术扫描仍在冷却中', false, {
      skill: 'tactical_scan',
      remainingTicks: String(remaining),
    })
  }
  const pulseCooldownTicks = agentPulseCooldownTicks(level)
  agentScanReadyAt = Date.now() + pulseCooldownTicks * MS_PER_TICK
  agentSnapshotLevel = level
  return {
    agentLevel: level,
    radiusBlocks: requireAt(AGENT_SCAN_RANGE_BLOCKS, clampJobLevel(level) - 1, '扫描范围表'),
    crossChunk: level >= 10,
    pulseCooldownTicks,
    scanOnline: true,
    truncated: false,
    // 成功回执里这两栏都等于刚烧起来的整轮 CD。
    scanCooldownRemainingTicks: pulseCooldownTicks,
    snapshotRemainingTicks: pulseCooldownTicks,
    targets: agentTargets(level),
  }
}

/**
 * 封印一条词条。
 *
 * 九态里有三条在 mock 里走不到, 且都不是漏做:
 *   NOT_BOUND          未加载 Champions 时写不出快照, 快照门会先拒 (Java 侧同样标了本路径不可达)
 *   NO_TARGET          mock 的目标不会离场, 网络 id 也不会失效
 *   ALL_SLOTS_OCCUPIED 槽是精英自身的容量, mock 没有对应状态可占
 * 其余六态 (OK / AFFIX_NOT_SEALABLE / CATEGORY_LOCKED / STAR_TOO_HIGH / AFFIX_ALREADY_SEALED /
 * ON_COOLDOWN) 都铺到了 —— 前端那本九态文案字典仍要写全。
 */
function mockAgentSeal(payload: AgentSealPayload): AgentSealResult {
  const level = mockJobLevel('agent')
  const snapshotValid = remainingTicks(agentScanReadyAt) > 0
  const target = snapshotValid
    ? AGENT_TARGET_SEEDS.find((seed) => seed.targetNetworkId === payload.targetNetworkId)
    : undefined
  if (target === undefined) {
    // 前置门之一: 没扫过 / 快照已过期 / 该目标不在快照里, 前端要显示成"请先扫描"。
    throw businessFailure('job.agent.seal', 'INVALID_REQUEST', '该目标不在当前扫描快照里', false, {
      field: 'targetNetworkId',
      value: truncateValue(String(payload.targetNetworkId)),
    })
  }
  const entry = target.entries.find((row) => row.decrypted && row.affixId === payload.affixId)
  if (entry === undefined) {
    // 前置门之二: 词条不在快照里 / 未解密 / 空白, 前端要显示成"该词条尚未解密, 点不了"。
    throw businessFailure('job.agent.seal', 'INVALID_REQUEST', '该词条尚未解密', false, {
      field: 'affixId',
      value: truncateValue(String(payload.affixId)),
    })
  }
  const category = entry.category
  const windowSeconds = agentSealWindowSeconds(level, category)
  const cooldownSeconds = agentSealCooldownSeconds(level, category)
  const sealedKey = `${String(target.targetNetworkId)}:${entry.affixId}`
  const categoryUnlockLevel =
    category === 'PASSIVE' ? AGENT_SEAL_UNLOCK_LEVEL : AGENT_MECHANIC_SEAL_UNLOCK_LEVEL

  let outcomeCode: AgentSealOutcomeCode = 'OK'
  if (!entry.sealable) {
    outcomeCode = 'AFFIX_NOT_SEALABLE'
  } else if (level < categoryUnlockLevel) {
    outcomeCode = 'CATEGORY_LOCKED'
  } else if (target.star > clampJobLevel(level)) {
    outcomeCode = 'STAR_TOO_HIGH'
  } else if (agentSealedAffixes.has(sealedKey)) {
    outcomeCode = 'AFFIX_ALREADY_SEALED'
  } else if (remainingTicks(agentSealReadyAt[category]) > 0) {
    outcomeCode = 'ON_COOLDOWN'
  }
  if (outcomeCode === 'OK') {
    agentSealedAffixes.add(sealedKey)
    agentSealReadyAt[category] = Date.now() + cooldownSeconds * 1000
  }
  return {
    ok: outcomeCode === 'OK',
    outcomeCode,
    targetNetworkId: target.targetNetworkId,
    affixId: entry.affixId,
    category,
    // 失败时同样发表值 (与占槽用的是同一张表同一对入参)。
    windowSeconds,
    cooldownSeconds,
    categoryCooldownRemainingTicks: remainingTicks(agentSealReadyAt[category]),
  }
}

// ============================================================
// job.munitions.state / job.blueprints
// ============================================================

/** MunitionsConfig 的 tableCountL1..L10 默认值 (等级 -> 军火台总数上限)。 */
const MUNITIONS_BENCH_CAP: readonly number[] = [1, 1, 2, 2, 3, 3, 4, 4, 5, 6]

/** MunitionsWebUiActions.SEARCH_RADIUS_BLOCKS = 4 区块 x 16。 */
const MUNITIONS_SEARCH_RADIUS_BLOCKS = 64

/** GunsmithAssemblyBenchBlockEntity.ASSEMBLY_DURATION_TICKS (真值常量)。 */
const MUNITIONS_ASSEMBLY_DURATION_TICKS = 160

/**
 * 三台机器。三行刻意各走一条不同的形态, 因为它们在面板上是三种完全不同的画法:
 *   军火台   扫到了且在跑 —— progressTicks 是 20 的倍数 (ContainerData 按秒过线, 还原不到逐 tick),
 *            outputItemId 为 null 而缓冲照涨 (TACZ 未装时的真形态, 产出权威看 bufferedRounds)
 *   冲压机   **没扫到** —— pos=null 时其余遥测全 null/0 且 detail 是空对象, 文案必须写"附近未找到"
 *   装配台   扫到了且在跑, 但 progressTicks 是 null (它没有 ContainerData): 必须画不定态进度条,
 *            当 0% 画就是假数据
 */
function munitionsStations(): MunitionsStation[] {
  return [
    {
      stationId: 'munitions_bench',
      // 六档军火台是六个注册名, 扫到哪一档就换成哪一档的键。
      nameKey: 'block.miningdim.munitions_bench_high',
      pos: { x: 152, y: 66, z: -88 },
      running: true,
      progressTicks: 40,
      requiredTicks: 100,
      outputItemId: null,
      outputCount: 0,
      detail: {
        caliberId: 'pistol',
        bufferedRounds: 640,
        bufferCap: 2_048,
        locked: false,
        refineUnlocked: true,
        // 台档会把台主等级压到该档上限, 故它可能低于职业等级。
        effectiveLevel: 6,
        continuousCrafting: true,
      },
    },
    {
      stationId: 'gunsmith_press',
      nameKey: 'block.miningdim.gunsmith_press',
      pos: null,
      running: false,
      progressTicks: null,
      requiredTicks: null,
      outputItemId: null,
      outputCount: 0,
      detail: {},
    },
    {
      stationId: 'gunsmith_assembly_bench',
      nameKey: 'block.miningdim.gunsmith_assembly_bench',
      pos: { x: 149, y: 66, z: -92 },
      running: true,
      progressTicks: null,
      requiredTicks: MUNITIONS_ASSEMBLY_DURATION_TICKS,
      outputItemId: null,
      outputCount: 0,
      // 键在、值为 null: "有图纸槽这个概念, 但槽里没放图纸" —— 与 detail 里整键缺席不是一回事。
      detail: { blueprintId: null },
    },
  ]
}

function mockMunitionsState(): MunitionsStateResult {
  const level = mockJobLevel('munitions')
  return {
    level,
    benchCap: requireAt(MUNITIONS_BENCH_CAP, clampJobLevel(level) - 1, '军火台数量表'),
    // 全局已放置数 (与 stations 里扫到几台无关): 取 benchCap 之下的一个数, 好让"还能再造几台"有意义。
    benchesPlaced: 3,
    searchRadiusBlocks: MUNITIONS_SEARCH_RADIUS_BLOCKS,
    // MunitionsConfig.GUNSMITH_ENABLED 的默认值就是 false, 不改成 true —— 面板必须先把这件事讲清楚。
    gunsmithEnabled: false,
    stations: munitionsStations(),
  }
}

/** GunsmithPressPart 声明序 (requiredParts 按它排, 不按平台自己的 List 顺序)。 */
const GUNSMITH_PART_ORDER: readonly string[] = [
  'core',
  'barrel',
  'bolt',
  'handguard',
  'grip',
  'stock',
  'slide',
  'trigger',
  'hammer',
  'receiver',
  'bipod',
]

/** GunsmithPlatform 的部位集 (今 9 张图纸只覆盖 ar/ak/pistol 三个平台)。 */
const GUNSMITH_PLATFORM_PARTS: Readonly<Record<string, readonly string[]>> = {
  ar: ['core', 'barrel', 'bolt', 'handguard', 'grip', 'stock'],
  ak: ['core', 'barrel', 'bolt', 'handguard', 'grip', 'stock'],
  pistol: ['barrel', 'grip', 'slide', 'trigger', 'hammer'],
}

/** [templateId, platformId] —— GunsmithBlueprint 声明序, 今 9 款。 */
const BLUEPRINT_ROWS: readonly (readonly [string, string])[] = [
  ['m4a1', 'ar'],
  ['m16a1', 'ar'],
  ['m16a4', 'ar'],
  ['hk416d', 'ar'],
  ['spr15hb', 'ar'],
  ['ak47', 'ak'],
  ['rpk', 'ak'],
  ['type_81', 'ak'],
  ['m1911', 'pistol'],
]

function mockBlueprints(): BlueprintsResult {
  const blueprints: Blueprint[] = BLUEPRINT_ROWS.map(([blueprintId, platformId]) => {
    const parts = GUNSMITH_PLATFORM_PARTS[platformId]
    if (parts === undefined) {
      throw new Error(`mock 数据缺陷: 平台部位表里没有 ${platformId}`)
    }
    return {
      blueprintId,
      gunId: `tacz:${blueprintId}`,
      nameKey: 'item.miningdim.gunsmith_blueprint.name',
      // 属 TACZ 的 lang: I18N_NAMES 刻意不收这批键, 未装 TACZ 的客户端本来就解不出, 前端要退回显示 gunId。
      gunNameKey: `tacz.gun.${blueprintId}.name`,
      platformId,
      platformLabelKey: `gunsmith.platform.${platformId}`,
      requiredParts: GUNSMITH_PART_ORDER.filter((partId) => parts.includes(partId)).map((partId) => ({
        partId,
        labelKey: `gunsmith.part.${partId}`,
        // 恒 1: 装配台每个部位槽 getSlotLimit=1。
        count: 1,
      })),
    }
  })
  return {
    blueprints,
    blueprintCount: blueprints.length,
    // 195 种零件共用这一个 itemId 与一个翻译键, 故只在顶层发一份。
    partItemId: 'miningdim:gunsmith_part',
    partDescriptionId: 'item.miningdim.gunsmith_part',
    // 与 job.munitions.state 的同名字段读同一个 config 值, 两处不许分叉。
    gunsmithEnabled: false,
  }
}

// ============================================================
// job.engineer.state (EngineerConfig 默认值照抄)
// ============================================================

/** [tierId, index, unlockLevel, oreCost, outputCount, produceTicks, rawXp, repairUnit, repairValue]。 */
const NANO_TIER_ROWS: readonly (readonly [
  string,
  number,
  number,
  number,
  number,
  number,
  number,
  NanoRepairUnit,
  number,
])[] = [
  ['low', 0, 1, 4, 1, 100, 15, 'durability', 100],
  ['medium', 1, 3, 5, 1, 120, 30, 'durability', 250],
  ['high', 2, 5, 3, 1, 160, 60, 'durability', 600],
  // 极品档是唯一一次产 2 板的 (1 下界合金锭 -> 2 板)。
  ['superior', 3, 7, 1, 2, 200, 110, 'permille', 300],
  ['transcendent', 4, 9, 1, 1, 240, 200, 'permille', 650],
  ['radiant', 5, 10, 2, 1, 300, 200, 'permille', 1_000],
]

/** 护甲特效解锁等级 (= 最低可掷特效档 high 的解锁等级)。四个特效同时解锁, 没有各自的门。 */
const ENGINEER_EFFECT_UNLOCK_LEVEL = 5

/** TOTEM_SHARED_CD_TICKS 默认值 = 30 分钟。 */
const ENGINEER_REACTOR_SHARED_CD_TICKS = 36_000

/** 反应堆 CD 到期时刻 (epoch ms)。种一个进行中的值: 恒就绪的话那条进度条在 mock 下永远是满的。 */
const engineerReactorReadyAt = NOW + 11 * 60_000

/** [effectId, 数值行] —— NanoEffect 声明序; 数值逐条取自 EngineerConfig 默认值。 */
const ENGINEER_EFFECT_ROWS: readonly (readonly [string, readonly EngineerStatLine[]])[] = [
  [
    'reshape',
    [
      { key: 'durabilityPerTick', labelKey: 'stat.miningdim.engineer.durabilityPerTick', value: 2, unit: 'flat' },
      { key: 'failDamagePct', labelKey: 'stat.miningdim.engineer.failDamagePct', value: 0.4, unit: 'percent' },
      { key: 'intervalTicks', labelKey: 'stat.miningdim.engineer.intervalTicks', value: 20, unit: 'ticks' },
    ],
  ],
  [
    'vitality',
    [
      { key: 'healPctPerTick', labelKey: 'stat.miningdim.engineer.healPctPerTick', value: 0.02, unit: 'percent' },
      {
        key: 'failDurabilityPct',
        labelKey: 'stat.miningdim.engineer.failDurabilityPct',
        value: 0.5,
        unit: 'percent',
      },
      { key: 'intervalTicks', labelKey: 'stat.miningdim.engineer.intervalTicks', value: 20, unit: 'ticks' },
    ],
  ],
  [
    'shield',
    [
      { key: 'immunityTicks', labelKey: 'stat.miningdim.engineer.immunityTicks', value: 40, unit: 'ticks' },
      {
        key: 'regenIntervalTicks',
        labelKey: 'stat.miningdim.engineer.regenIntervalTicks',
        value: 1_200,
        unit: 'ticks',
      },
      // 实现把 IntValue 拓宽成 double, 故 count 档在 JSON 里也是 5.0 那种形态 —— 前端不得断言整数。
      { key: 'maxCharges', labelKey: 'stat.miningdim.engineer.maxCharges', value: 5, unit: 'count' },
    ],
  ],
  [
    'totem',
    [
      {
        key: 'sharedCdTicks',
        labelKey: 'stat.miningdim.engineer.sharedCdTicks',
        value: ENGINEER_REACTOR_SHARED_CD_TICKS,
        unit: 'ticks',
      },
      {
        key: 'reviveHealthPct',
        labelKey: 'stat.miningdim.engineer.reviveHealthPct',
        value: 0.5,
        unit: 'percent',
      },
      { key: 'invulnTicks', labelKey: 'stat.miningdim.engineer.invulnTicks', value: 40, unit: 'ticks' },
      {
        key: 'durabilityCostPct',
        labelKey: 'stat.miningdim.engineer.durabilityCostPct',
        value: 0.25,
        unit: 'percent',
      },
    ],
  ],
]

function mockEngineerState(): EngineerStateResult {
  const level = mockJobLevel('engineer')
  const tiers: NanoTierRow[] = NANO_TIER_ROWS.map(
    ([tierId, index, unlockLevel, oreCost, outputCount, produceTicks, rawXp, repairUnit, repairValue]) => ({
      tierId,
      labelKey: `tier.miningdim.nano.${tierId}`,
      index,
      unlockLevel,
      unlocked: level >= unlockLevel,
      plateItemId: `miningdim:nano_plate_${tierId}`,
      plateDescriptionId: `item.miningdim.nano_plate_${tierId}`,
      oreCost,
      outputCount,
      produceTicks,
      rawXp,
      // 高级板起才可能掷出特效。
      canRollEffect: index >= 2,
      repairUnit,
      repairValue,
      guaranteedEffect: tierId === 'radiant',
      // 两个字段**仅闪耀档存在**: 其余档整键缺席 (默认 Gson 不写 null), 前端只能用 !== undefined 判。
      ...(tierId === 'radiant' ? { successChance: 0.5, failRefundScrap: 1 } : {}),
    }),
  )
  const highestUnlocked = NANO_TIER_ROWS.filter(([, , unlockLevel]) => level >= unlockLevel).at(-1)
  if (highestUnlocked === undefined) {
    throw new Error('mock 数据缺陷: 纳米板档表里没有任何一档在 L1 解锁')
  }
  const armorEffects: ArmorEffectRow[] = ENGINEER_EFFECT_ROWS.map(([effectId, stats]) => ({
    effectId,
    // 这两批键的 lang 条目尚未落地, 解出来就是键本身 —— 真服同样如此, mock 不许替它补上。
    labelKey: `effect.miningdim.nano.${effectId}`,
    descriptionKey: `effect.miningdim.nano.${effectId}.desc`,
    unlockLevel: ENGINEER_EFFECT_UNLOCK_LEVEL,
    unlocked: level >= ENGINEER_EFFECT_UNLOCK_LEVEL,
    stats: stats.map((line) => ({ ...line })),
  }))
  return {
    level,
    jobNameKey: 'job.miningdim.engineer',
    unlockedTierId: highestUnlocked[0],
    effectUnlockLevel: ENGINEER_EFFECT_UNLOCK_LEVEL,
    reactorCooldownRemainingTicks: remainingTicks(engineerReactorReadyAt),
    reactorSharedCdTicks: ENGINEER_REACTOR_SHARED_CD_TICKS,
    tiers,
    armorEffects,
    qualityBonusThreshold: 4,
    qualityBonusPlateChance: 0.5,
    ownPlateRepairXpBonus: 0.5,
  }
}

// ============================================================
// economy.* (三条只读表; 常量逐条取自契约注释里标死的真值)
// ============================================================

/** 无挖掘判据阈值 (tick, 2 分钟)。 */
const ECONOMY_AFK_NO_MINE_TICKS = 2_400
/** 无位移判据阈值 (方块)。它是与上一条**并列**的另一条判据, 不是倒计时。 */
const ECONOMY_AFK_NO_MOVE_BLOCKS = 4
/** 衰减主闸单档大小 (毛收入/档) —— 不是每日上限。 */
const ECONOMY_CREDIT_FAUCET_TIER = 60_000
/** 青辉石每人每日硬上限 (撞顶即不发, 不是衰减)。 */
const ECONOMY_AZURE_DAILY_CAP = 30

/**
 * 今日信用点 faucet 毛额 (衰减前) 与青辉石实发额。
 *
 * 两个数字都是常量, 卖菜/买卡包都不回写 —— 真服这两栏由账本 daily_counters 聚合, mock 复刻那套聚合等于
 * 把服务端口径抄第二遍 (与 market.p2pCap 的两段已用量同一条纪律)。
 * 取值刻意跨过一整档 (78400 > 60000): 恰好落在"已经开始衰减"的区间, 面板那句"再赚 1 点只到手 X"才有内容;
 * 青辉石取 12 则停在硬上限 30 之下, 撞顶态与未撞顶态里更常见的是后者。
 */
const MOCK_TODAY_CREDIT_FAUCET_GROSS = 78_400
const MOCK_TODAY_AZURE_IN = 12

/**
 * 当前档位下"再赚 1 点毛收入"的实发系数。
 *
 * **固定占位值, 不是主闸函数的复刻** (同文件头第 1 条近似的纪律): 真闸是按档递减的几何主项, 抄一份必然
 * 与 Java 侧漂移。它只需满足两件事: 落在 (0.01, 1.0) 开区间内 (即"已衰减但没到地板"), 且与
 * MOCK_TODAY_CREDIT_FAUCET_GROSS 讲同一个故事。
 */
const MOCK_CREDIT_FAUCET_NEXT_FACTOR = 0.72

/**
 * 距上次在矿区有效挖掘的 game tick; null = 从未挖过 (**不是 0** —— 0 的意思是"刚刚挖过")。
 *
 * 初值 null 就是那条最容易被画成 0 的分支。mining.enter 会把它落成一个真值 —— 那是 mock 的近似:
 * 真服的判据是矿区内的有效挖掘, "进过矿洞"并不等于"挖过", 但 mock 没有挖掘这个动作可挂。
 */
let economyLastMineGameTick: number | null = null

function mockEconomyStatus(): EconomyStatusResult {
  const ticksSinceLastMine =
    economyLastMineGameTick === null ? null : Math.max(0, gameTick() - economyLastMineGameTick)
  return {
    /*
     * 真服的冻结判据是"无挖掘 && 无显著位移"两条同时成立; mock 手里只有挖掘侧, 故这里只按第一条判 ——
     * 也就是说 mock 会比真服**更早**显示冻结。面板文案必须照真服写"还要位移条件同时成立", 不能照本行写。
     */
    afkFrozen: ticksSinceLastMine !== null && ticksSinceLastMine > ECONOMY_AFK_NO_MINE_TICKS,
    ticksSinceLastMine,
    afkNoMineTicks: ECONOMY_AFK_NO_MINE_TICKS,
    afkNoMoveBlocks: ECONOMY_AFK_NO_MOVE_BLOCKS,
    ticksPerSecond: 20,
  }
}

/** 当日 UTC 日戳 (epochDay), 与 faucet 计数器的翻日判据同一时钟。 */
function economyDayStamp(): number {
  return Math.floor(NOW / 86_400_000)
}

function mockEconomyToday(): EconomyTodayResult {
  const dayStamp = economyDayStamp()
  return {
    dayStamp,
    // 本组唯一一个墙钟字段: 翻日的自变量本来就是 UTC 挂钟。
    resetsAtUtcMillis: (dayStamp + 1) * 86_400_000,
    creditFaucetKey: 'credit_faucet',
    todayCreditFaucetGross: MOCK_TODAY_CREDIT_FAUCET_GROSS,
    creditFaucetTier: ECONOMY_CREDIT_FAUCET_TIER,
    creditFaucetNextFactor: MOCK_CREDIT_FAUCET_NEXT_FACTOR,
    todayAzureIn: MOCK_TODAY_AZURE_IN,
    azureDailyCap: ECONOMY_AZURE_DAILY_CAP,
  }
}

/** [oreId, itemId, 锚价, 每日软上限, 本人今日已产出个数] —— ShopPriceTable 的三条真值 + 一份产量现场。 */
const ECONOMY_PRICE_ROWS: readonly (readonly [HighValueOreId, string, number, number, number])[] = [
  // 未超软上限: 逐矿 steering 还没开始打折, 只吃主闸。
  ['DIAMOND', 'minecraft:diamond', 500, 64, 41],
  // 超出 57 颗: 0.97^57 已把单价压到两成上下, 正是"为什么降了"那句文案要解释的形态。
  ['GOLD', 'minecraft:gold_ingot', 120, 256, 312],
  // 刚好卡在软上限上: 下一颗就是第一颗打折的, 相邻两颗差价最大的那一格。
  ['NETHERITE_SCRAP', 'minecraft:netherite_scrap', 4_500, 8, 8],
]

function mockEconomyPriceTable(): EconomyPriceTableResult {
  const anchors: EconomyPriceAnchor[] = ECONOMY_PRICE_ROWS.map(
    ([oreId, itemId, anchorPrice, dailySoftCap, minedToday]) => {
      // 逐矿 steering: 超限数按"下一颗"算, 系数 0.97^超限数 有 1% 地板, 结果向下取整 (契约里写死的展示式)。
      const over = Math.max(0, minedToday + 1 - dailySoftCap)
      const nextUnitGrossCredit = Math.floor(anchorPrice * Math.max(0.01, Math.pow(0.97, over)))
      return {
        oreId,
        itemId,
        descriptionId: requireItem(itemId).descriptionId,
        anchorPrice,
        minedToday,
        dailySoftCap,
        nextUnitGrossCredit,
        // 串联顺序与服务端一致: 逐矿 steering -> 衰减主闸。刻意不取整 (真服也不取), 展示前由前端 round。
        nextUnitNetCredit: nextUnitGrossCredit * MOCK_CREDIT_FAUCET_NEXT_FACTOR,
      }
    },
  )
  return {
    dayStamp: economyDayStamp(),
    todayCreditFaucetGross: MOCK_TODAY_CREDIT_FAUCET_GROSS,
    anchors,
  }
}

// ============================================================
// marriage.* (MiningServerConfig 的婚姻五个默认值照抄; 时间一律 gameTime tick)
// ============================================================

const MARRIAGE_RING_PRICE_CREDIT = 5_000
/** 典礼总价, 双方各付一半 (总价为奇数时发起方多付 1)。 */
const MARRIAGE_WEDDING_COST_CREDIT = 20_000
const MARRIAGE_DIVORCE_COST_CREDIT = 10_000
/** 冷却 = 本值 x (1 + 离婚次数), 随每次离婚递增 (故首次离婚是 14 天)。 */
const MARRIAGE_REMARRY_COOLDOWN_DAYS = 7
/**
 * 离婚公示期时长 (MiningServerConfig divorceEscrowHours 默认 24 小时, 24 x 3600 x 20 tick/s = TICKS_PER_DAY,
 * 与"1 天"那个换算常数刚好同值, 不是巧合编的数字)。marriage.divorce 现在提交进公示期而不是立即解除
 * (spec 第六章闸 2), mock 必须能复现这一态, 否则新增的"待生效离婚"面板永远没有假数据能验证它。
 */
const MARRIAGE_DIVORCE_ESCROW_TICKS = TICKS_PER_DAY
/** 共享背包解锁婚龄 (天) 与各级暴露格数; 两张表下标一一对应。 */
const MARRIAGE_BACKPACK_UNLOCK_DAYS: readonly number[] = [0, 3, 7, 14, 30]
const MARRIAGE_BACKPACK_SLOTS: readonly number[] = [9, 18, 27, 45, 54]
/** marriage.state 的 incomingProposals 硬上限 (防撞回执体积)。 */
const MARRIAGE_INCOMING_CAP = 32
const RING_ENGAGEMENT_ITEM_ID = 'miningdim:engagement_ring'
const RING_WEDDING_ITEM_ID = 'miningdim:wedding_ring'

type MockProposalSeed = {
  uuid: string
  /** **仅求婚方在线时有值** (全库零 GameProfileCache 用法, 离线拿不到名字)。 */
  name: string | null
  online: boolean
  accepted: boolean
}

/**
 * 收到的婚约。
 *
 * 刻意造满 33 条 (> 32 硬上限): incomingProposalsTruncated 那条提示只有超上限才会亮, 造 3 条的话它在
 * 假数据模式下恒为 false, 面板上那句"还有 N 人没显示"就从没被人看见过。
 * 前三条取自名册 (名字/UUID 与市场页、管理台一致), 其中包含一条**离线**的 —— 那条的 proposerName 是 null。
 * 已接受的刻意有两条: marriage.wed 不带 partnerName 时会因此撞上"有多份已接受的婚约"那道 INVALID_REQUEST,
 * 只有一条的话面板永远不知道自己还要做一个"选一位"的界面。
 */
function buildIncomingProposals(): MockProposalSeed[] {
  const rows: MockProposalSeed[] = [
    { uuid: requireCounterpartyUuid('矿工阿建'), name: '矿工阿建', online: true, accepted: true },
    { uuid: mockUuid(9_001), name: '甜品师小柚', online: true, accepted: false },
    { uuid: requireCounterpartyUuid('鲸鱼玩家'), name: null, online: false, accepted: false },
  ]
  for (let index = 0; index < 30; index += 1) {
    const online = index % 4 !== 3
    rows.push({
      uuid: mockUuid(9_100 + index),
      name: online ? `求婚者${String(index).padStart(2, '0')}` : null,
      online,
      accepted: index === 5,
    })
  }
  return rows
}

let marriageIncoming: MockProposalSeed[] = buildIncomingProposals()
let marriageOutgoing: MarriageOutgoingProposal | null = null
let marriageSpouse: MockPlayerRow | null = null
let marriageIdValue: number | null = null
let marriageWeddedAtTick: number | null = null
let marriageDivorceCount = 0
/** 再婚冷却到期的 gameTime tick; 0 = 无冷却。 */
let marriageRemarryCooldownEndTick = 0
/** 本段关系内是否已领过 first_marriage 里程碑。 */
let marriageMilestoneClaimed = false
/**
 * 待生效离婚 (spec 第六章闸 2); null = 当前无 pending。initiatorUuid 恒是本地玩家 —— mock 只有一个可操作视角,
 * 没有"配偶主动提交"这回事。撤回 (/marriage divorce cancel) / 提前确认 (/marriage divorce confirm) 是命令层
 * 专属能力, 本假桥只做 WebUiAction 分发, 没有聊天指令入口可模拟, 故这里只提供"到期自动结算"这一条出路,
 * 与真实 MarriageDivorce.finalizeMatured 的低频扫描同源。
 */
let marriagePendingDivorce: { initiatorUuid: string; filedAtTick: number; effectiveAtTick: number } | null = null

/**
 * 共享背包内容。离婚时整份退回**发起方**背包, 故它必须是可变的 ——
 * 常量的话"离婚后东西去哪了"这条在面板上就成了一句无法验证的说明文字。
 */
let marriageSharedItems: PlayerInventoryItem[] = [
  { slot: 0, itemId: 'minecraft:diamond', descriptionId: 'item.minecraft.diamond', count: 12 },
  {
    slot: 3,
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
  { slot: 8, itemId: 'minecraft:arrow', descriptionId: 'item.minecraft.arrow', count: 64 },
]

/** 扫主背包 36 格得到的真值 (与契约同一取法), 不另存一个布尔 —— 两份状态必然分叉。 */
function engagementRingOwned(): boolean {
  return inventory.some((item) => item.itemId === RING_ENGAGEMENT_ITEM_ID)
}

function marriageRemarryCooldownTicks(): number {
  return Math.max(0, marriageRemarryCooldownEndTick - gameTick())
}

function marriedDays(): number {
  return marriageWeddedAtTick === null
    ? 0
    : Math.floor((gameTick() - marriageWeddedAtTick) / TICKS_PER_DAY)
}

/** 按婚龄现算的共享背包等级 (1-based) 与格数; 未婚均为 0。 */
function sharedInvLevelAndSlots(): { level: number; slots: number } {
  if (marriageSpouse === null) {
    return { level: 0, slots: 0 }
  }
  const days = marriedDays()
  const level = MARRIAGE_BACKPACK_UNLOCK_DAYS.filter((unlockDay) => days >= unlockDay).length
  return { level, slots: requireAt(MARRIAGE_BACKPACK_SLOTS, level - 1, '共享背包格数表') }
}

/** 四态**并非互斥**, 按 married > engaged > cooldown > single 取优先级 (与服务端同序)。 */
function marriageStatus(): MarriageStatus {
  if (marriageSpouse !== null) {
    return 'married'
  }
  if (
    marriageIncoming.some((proposal) => proposal.accepted) ||
    (marriageOutgoing !== null && marriageOutgoing.accepted)
  ) {
    return 'engaged'
  }
  if (marriageRemarryCooldownTicks() > 0) {
    return 'cooldown'
  }
  return 'single'
}

/**
 * 公示期到期自动结算 (仿真实 MarriageDivorce.finalizeMatured 的低频扫描); 在任何读取婚姻状态的入口前置调用,
 * 让"到期"这件事不需要一个真的后台定时器。共享背包按槽归属清算在真实现里是"谁放入谁取回", mock 世界不追踪
 * 逐槽归属 (marriageSharedItems 只是一张平铺列表), 简化为全部退回本地玩家背包 —— 与旧的"即时离婚"分支
 * 退货逻辑同源, 不是新引入的简化。
 */
function settleMarriageDivorceIfMatured(): void {
  if (marriagePendingDivorce === null || gameTick() < marriagePendingDivorce.effectiveAtTick) {
    return
  }
  for (const item of [...marriageSharedItems]) {
    depositToInventory('marriage.divorce', item.itemId, item.count)
  }
  marriageSharedItems = []
  marriageSpouse = null
  marriageIdValue = null
  marriageWeddedAtTick = null
  marriageMilestoneClaimed = false
  marriageDivorceCount += 1
  // 冷却随离婚次数递增: 首次离婚 = 7 x (1+1) = 14 天。
  marriageRemarryCooldownEndTick =
    gameTick() + MARRIAGE_REMARRY_COOLDOWN_DAYS * (1 + marriageDivorceCount) * TICKS_PER_DAY
  marriagePendingDivorce = null
}

function mockMarriageState(): MarriageStateResult {
  settleMarriageDivorceIfMatured()
  const shared = sharedInvLevelAndSlots()
  const visible: MarriageIncomingProposal[] = marriageIncoming
    .slice(0, MARRIAGE_INCOMING_CAP)
    .map((proposal) => ({
      // proposalId 恒等于求婚方 UUID (一人同时只持一条 outgoing 意向, 该 UUID 已是完整主键)。
      proposalId: proposal.uuid,
      proposerUuid: proposal.uuid,
      proposerName: proposal.name,
      proposerOnline: proposal.online,
      accepted: proposal.accepted,
    }))
  return {
    nowTick: gameTick(),
    status: marriageStatus(),
    divorceCount: marriageDivorceCount,
    remarryCooldownTicks: marriageRemarryCooldownTicks(),
    marriageId: marriageIdValue,
    spouseUuid: marriageSpouse === null ? null : marriageSpouse.uuid,
    // 已婚但配偶离线时 spouseUuid 有值而本栏为 null; mock 的配偶恒在线, 那一态要靠名册里的离线位去想象。
    spouseName: marriageSpouse === null ? null : marriageSpouse.name,
    spouseOnline: marriageSpouse !== null && marriageSpouse.online,
    weddedAtTick: marriageWeddedAtTick,
    marriedDays: marriedDays(),
    sharedInvLevel: shared.level,
    sharedInvSlots: shared.slots,
    engagementRingOwned: engagementRingOwned(),
    ringPriceCredit: MARRIAGE_RING_PRICE_CREDIT,
    weddingCostCredit: MARRIAGE_WEDDING_COST_CREDIT,
    divorceCostCredit: MARRIAGE_DIVORCE_COST_CREDIT,
    milestones: [
      {
        milestoneId: 'first_marriage',
        claimedInCurrentMarriage: marriageMilestoneClaimed,
        // 只在 status='married' 时有意义: 单身 (含离婚后) 时它与"没有关系记录"绑定, 恒 false。
        claimedByPair: marriageSpouse !== null && marriageMilestoneClaimed,
      },
    ],
    incomingProposals: visible,
    incomingProposalTotal: marriageIncoming.length,
    incomingProposalsTruncated: marriageIncoming.length > MARRIAGE_INCOMING_CAP,
    outgoingProposal: marriageOutgoing === null ? null : { ...marriageOutgoing },
    pendingDivorce:
      marriagePendingDivorce === null
        ? null
        : {
            initiatorUuid: marriagePendingDivorce.initiatorUuid,
            filedAtTick: marriagePendingDivorce.filedAtTick,
            effectiveAtTick: marriagePendingDivorce.effectiveAtTick,
          },
  }
}

function mockMarriageBuyRing(): MarriageBuyRingResult {
  if (wallet.credit < MARRIAGE_RING_PRICE_CREDIT) {
    throw businessFailure('marriage.buyRing', 'INSUFFICIENT_FUNDS', '信用点不足', false, {
      cost: String(MARRIAGE_RING_PRICE_CREDIT),
      currency: 'CREDIT',
      balance: String(wallet.credit),
    })
  }
  wallet.credit -= MARRIAGE_RING_PRICE_CREDIT
  depositToInventory('marriage.buyRing', RING_ENGAGEMENT_ITEM_ID, 1)
  return {
    costCredit: MARRIAGE_RING_PRICE_CREDIT,
    wallet: { ...wallet },
    /*
     * 这里恒为 true, 但它在真服**不是恒 true**: 背包满时引擎把戒指掉在玩家脚下 (钱照扣), 那一次是 false。
     * mock 的 depositToInventory 在背包满时是抛错而不是掉落, 故造不出那一态 —— 前端仍必须备"检查脚下"的文案。
     */
    engagementRingOwned: engagementRingOwned(),
  }
}

function mockMarriagePropose(payload: MarriageProposePayload): MarriageProposeResult {
  const target = requireOnlinePlayer('marriage.propose', 'targetName', payload.targetName)
  if (target.name === MOCK_PLAYER_NAME) {
    throw businessFailure('marriage.propose', 'INVALID_REQUEST', '不能向自己求婚', false, {
      field: 'targetName',
      value: truncateValue(target.name),
    })
  }
  const me = requireCounterpartyUuid(MOCK_PLAYER_NAME)
  // 覆盖自己旧的那条 outgoing 意向 (一人同时只持一条), 与命令层同一副作用。
  marriageOutgoing = {
    proposalId: me,
    targetUuid: target.uuid,
    targetName: target.name,
    targetOnline: target.online,
    accepted: false,
  }
  return {
    proposalId: me,
    proposerUuid: me,
    targetUuid: target.uuid,
    targetName: target.name,
    accepted: false,
  }
}

function mockMarriageRespond(payload: MarriageRespondPayload): MarriageRespondResult {
  const proposal = marriageIncoming.find((row) => row.uuid === payload.proposalId)
  if (proposal === undefined) {
    throw businessFailure('marriage.respond', 'INVALID_REQUEST', '没有这条指向你的婚约', false, {
      field: 'proposalId',
      value: truncateValue(String(payload.proposalId)),
    })
  }
  if (payload.accept) {
    proposal.accepted = true
  } else {
    // 没有"已拒绝"的第三态: 直接清掉, 求婚方只能靠下次刷新发现意向没了。
    marriageIncoming = marriageIncoming.filter((row) => row.uuid !== proposal.uuid)
  }
  return {
    proposalId: proposal.uuid,
    proposerUuid: proposal.uuid,
    proposerName: proposal.name,
    accepted: payload.accept,
    // 接受后通常是 engaged 而不是 married —— 典礼是 marriage.wed 那一步。
    status: marriageStatus(),
    spouseName: marriageSpouse === null ? null : marriageSpouse.name,
  }
}

/** 典礼回执的唯一构造点 (八个字段无论成败都写满, 缺的写 null), 与服务端 wedResponse 同形。 */
function wedResult(
  ok: boolean,
  outcomeCode: MarriageWedOutcomeCode,
  messageKey: string | null,
  messageArgs: string[],
  partnerUuid: string | null,
  partnerName: string | null,
  marriageId: number | null,
  weddedAtTick: number | null,
): MarriageWedResult {
  return { ok, outcomeCode, messageKey, messageArgs, partnerUuid, partnerName, marriageId, weddedAtTick }
}

function mockMarriageWed(payload: MarriageWedPayload): MarriageWedResult {
  const accepted = marriageIncoming.filter((row) => row.accepted)
  let partner: MockProposalSeed
  if (payload.partnerName === undefined) {
    if (accepted.length === 0) {
      /*
       * 连伴侣是谁都不知道, 而 no_accepted_proposal 那条 lang 键要一个玩家名占位符 —— 填不出实参就不发 key,
       * 让前端用自己那句话 (发一条渲染出来缺半截的文案比不发更糟)。这是服务端的选择, 照抄。
       */
      return wedResult(false, 'NO_ACCEPTED_PROPOSAL', null, [], null, null, null, null)
    }
    if (accepted.length > 1) {
      throw businessFailure(
        'marriage.wed',
        'INVALID_REQUEST',
        '有多份已接受的婚约, 必须指名 partnerName',
        false,
        { field: 'partnerName', candidateCount: String(accepted.length) },
      )
    }
    partner = requireAt(accepted, 0, '已接受婚约表')
    if (!partner.online) {
      // 典礼要求双方在场, 且此时拿不到离线者的名字, 故 partnerName 发 null。
      return wedResult(false, 'PARTNER_OFFLINE', null, [], partner.uuid, null, null, null)
    }
  } else {
    // 先落成局部常量: 回调内的属性收窄会被 TS 丢掉, 而这里需要的正是"已确定是 string"这一步。
    const wanted = payload.partnerName
    const lowered = wanted.toLowerCase()
    if (lowered === MOCK_PLAYER_NAME.toLowerCase()) {
      throw businessFailure('marriage.wed', 'INVALID_REQUEST', '不能和自己办典礼', false, {
        field: 'partnerName',
        value: truncateValue(wanted),
      })
    }
    const namedAccepted = accepted.find(
      (row) => row.name !== null && row.name.toLowerCase() === lowered,
    )
    if (namedAccepted === undefined) {
      /*
       * 名字解析得出人、却没有已接受的婚约 —— 服务端把它当**业务结果** (ok:false + NO_ACCEPTED_PROPOSAL,
       * 且这一条填得出玩家名故会发 messageKey), 不是入参错误。这里按同一口径分两档: 名册里有这个在线玩家
       * 就回业务结果, 名册里也没有 (离线/拼错) 才是 INVALID_REQUEST。
       */
      const online = MOCK_PLAYERS.find((row) => row.online && row.name.toLowerCase() === lowered)
      if (online === undefined) {
        throw businessFailure('marriage.wed', 'INVALID_REQUEST', '找不到该在线玩家', false, {
          field: 'partnerName',
          value: truncateValue(wanted),
        })
      }
      return wedResult(
        false,
        'NO_ACCEPTED_PROPOSAL',
        'message.miningdim.marriage.wed.no_accepted_proposal',
        [online.name],
        online.uuid,
        online.name,
        null,
        null,
      )
    }
    partner = namedAccepted
  }
  if (partner.name === null) {
    // 走到这里的伴侣必然在线, 而离线才会没名字 —— 两者对不上就是种子自相矛盾, 不拿空串糊过去。
    throw new Error('mock 数据缺陷: 在线的求婚方没有名字')
  }
  const partnerName = partner.name
  if (marriageSpouse !== null) {
    return wedResult(
      false,
      'ALREADY_MARRIED',
      'message.miningdim.marriage.wed.already_married',
      [],
      partner.uuid,
      partnerName,
      null,
      null,
    )
  }
  if (!engagementRingOwned()) {
    return wedResult(
      false,
      'NO_ENGAGEMENT_RING',
      'message.miningdim.marriage.wed.no_ring',
      [],
      partner.uuid,
      partnerName,
      null,
      null,
    )
  }
  if (marriageRemarryCooldownTicks() > 0) {
    return wedResult(
      false,
      'REMARRY_COOLDOWN',
      'message.miningdim.marriage.wed.remarry_cooldown',
      [],
      partner.uuid,
      partnerName,
      null,
      null,
    )
  }
  // 双方各付一半; 总价为奇数时发起方多付 1, 故这里向上取整。
  const myShare = Math.ceil(MARRIAGE_WEDDING_COST_CREDIT / 2)
  if (wallet.credit < myShare) {
    return wedResult(
      false,
      'INSUFFICIENT_FUNDS',
      'message.miningdim.marriage.wed.insufficient',
      [],
      partner.uuid,
      partnerName,
      null,
      null,
    )
  }
  wallet.credit -= myShare
  /*
   * 订婚戒指换成结婚戒指 (它们是两个注册项, 典礼时同槽 setItem), 于是 engagementRingOwned 自然翻成 false ——
   * 这正是"已婚玩家再点典礼会被 NO_ENGAGEMENT_RING 拦下"之前先撞 ALREADY_MARRIED 的原因。
   */
  const ringSlot = inventory.find((item) => item.itemId === RING_ENGAGEMENT_ITEM_ID)
  if (ringSlot === undefined) {
    throw new Error('mock 数据缺陷: 订婚戒指判定通过却找不到那一格')
  }
  ringSlot.itemId = RING_WEDDING_ITEM_ID
  ringSlot.descriptionId = 'item.miningdim.wedding_ring'
  marriageSpouse = { name: partnerName, uuid: partner.uuid, online: true }
  marriageIdValue = 7_001
  marriageWeddedAtTick = gameTick()
  marriageMilestoneClaimed = true
  // 典礼成功要清双方残留意向, 少了这一步面板上那条婚约会一直挂着。
  marriageIncoming = []
  marriageOutgoing = null
  return wedResult(
    true,
    'OK',
    'message.miningdim.marriage.wed.broadcast',
    [MOCK_PLAYER_NAME, partnerName],
    partner.uuid,
    partnerName,
    marriageIdValue,
    marriageWeddedAtTick,
  )
}

/**
 * marriage.divorce (MarriageWebUiActions.DIVORCE)。**不是立即解除** —— 只做"提交进公示期"这一步 (spec 第六章
 * 闸 2), 与 MarriageDivorce.file 的真实语义同源: 首次提交扣费并开公示期, 关系照旧存续; 到期自动结算由
 * settleMarriageDivorceIfMatured 处理, 撤回/确认是命令层专属, 本假桥不提供。
 */
function mockMarriageDivorce(): MarriageDivorceResult {
  settleMarriageDivorceIfMatured()
  if (marriageSpouse === null) {
    return {
      ok: false,
      outcomeCode: 'NOT_MARRIED',
      messageKey: 'message.miningdim.marriage.not_married',
      messageArgs: [],
      // 三种失败一分未扣, 故本栏是**定价**而不是已扣额。
      costCredit: MARRIAGE_DIVORCE_COST_CREDIT,
      divorceCount: marriageDivorceCount,
      remarryCooldownTicks: marriageRemarryCooldownTicks(),
      formerSpouseUuid: null,
      pending: false,
      alreadyPending: false,
      effectiveAtTick: null,
      escrowTicks: MARRIAGE_DIVORCE_ESCROW_TICKS,
    }
  }
  if (marriagePendingDivorce === null && wallet.credit < MARRIAGE_DIVORCE_COST_CREDIT) {
    return {
      ok: false,
      outcomeCode: 'INSUFFICIENT_FUNDS',
      messageKey: 'message.miningdim.marriage.divorce.insufficient',
      // 只有这一种失败带实参 (那条 lang 键有一个 %s 要填价钱)。
      messageArgs: [String(MARRIAGE_DIVORCE_COST_CREDIT)],
      costCredit: MARRIAGE_DIVORCE_COST_CREDIT,
      divorceCount: marriageDivorceCount,
      remarryCooldownTicks: marriageRemarryCooldownTicks(),
      formerSpouseUuid: null,
      pending: false,
      alreadyPending: false,
      effectiveAtTick: null,
      escrowTicks: MARRIAGE_DIVORCE_ESCROW_TICKS,
    }
  }
  // formerSpouseUuid 取的是**调用这一刻**的配偶 (真实实现同样在 file() 之前取快照), 提交进公示期时关系
  // 尚未解除, 此时它描述的其实是"当前仍然是"的那位配偶, 不是"刚刚变成前任"的那位。
  const formerSpouseUuid = marriageSpouse.uuid
  const now = gameTick()

  if (marriagePendingDivorce !== null) {
    // 幂等重复提交: 不二次扣费, 不推迟到期时刻。真实实现用 Long 整除 (/20L), 这里用 Math.floor 对齐截断语义。
    const remainingSeconds = Math.floor(Math.max(0, marriagePendingDivorce.effectiveAtTick - now) / 20)
    return {
      ok: true,
      outcomeCode: 'OK',
      messageKey: 'message.miningdim.marriage.divorce.filed',
      messageArgs: [String(remainingSeconds)],
      costCredit: MARRIAGE_DIVORCE_COST_CREDIT,
      divorceCount: marriageDivorceCount,
      remarryCooldownTicks: marriageRemarryCooldownTicks(),
      formerSpouseUuid,
      pending: true,
      alreadyPending: true,
      effectiveAtTick: marriagePendingDivorce.effectiveAtTick,
      escrowTicks: MARRIAGE_DIVORCE_ESCROW_TICKS,
    }
  }

  wallet.credit -= MARRIAGE_DIVORCE_COST_CREDIT
  const effectiveAtTick = now + MARRIAGE_DIVORCE_ESCROW_TICKS
  marriagePendingDivorce = {
    initiatorUuid: requireCounterpartyUuid(MOCK_PLAYER_NAME),
    filedAtTick: now,
    effectiveAtTick,
  }
  const remainingSeconds = MARRIAGE_DIVORCE_ESCROW_TICKS / 20
  return {
    ok: true,
    outcomeCode: 'OK',
    messageKey: 'message.miningdim.marriage.divorce.filed',
    messageArgs: [String(remainingSeconds)],
    costCredit: MARRIAGE_DIVORCE_COST_CREDIT,
    // 提交阶段重读的旧值: 只有公示期到期真正 settle 时才会变, 与真实契约同源, 不能拿来说"已离婚"。
    divorceCount: marriageDivorceCount,
    remarryCooldownTicks: marriageRemarryCooldownTicks(),
    formerSpouseUuid,
    pending: true,
    alreadyPending: false,
    effectiveAtTick,
    escrowTicks: MARRIAGE_DIVORCE_ESCROW_TICKS,
  }
}

function mockMarriageSharedInv(): MarriageSharedInvResult {
  const shared = sharedInvLevelAndSlots()
  return {
    // 恒 54 (容器固定大小), 与 slots 是两件事: 等级只控暴露的子集。
    capacity: 54,
    married: marriageSpouse !== null,
    marriageId: marriageIdValue,
    level: shared.level,
    slots: shared.slots,
    // 只回当前等级暴露的前 slots 格; 超出可见面的格子即使有货也一格不发。
    items:
      marriageSpouse === null
        ? []
        : marriageSharedItems.filter((item) => item.slot < shared.slots).map((item) => ({ ...item })),
  }
}

// ============================================================
// mining.* (R1 模型: 全服只有 3 块常驻共享区域, 没有"我的副本"; 时间一律矿山维度 gameTime)
// ============================================================

const MINING_DIFFICULTIES: readonly MiningDifficulty[] = ['easy', 'medium', 'hard']

/** 预警秒数 (AUTO_RESET_WARN_SECONDS 默认值); 真实换图 = nextResetGameTime + 本值 x 20 tick。 */
const MINING_AUTO_RESET_WARN_SECONDS = 60

type MockMiningRow = {
  difficulty: MiningDifficulty
  /** MinerLevelGate 的真值 1/4/8 (GateResult 头注释里的 10/25 是过期文档口径)。 */
  requiredMinerLevel: number
  /** 入场费默认全为 0; 等产出埋点积累真机样本后再标定。 */
  entryFee: number
  /** AUTO_RESET_HOURS_<难度> 默认值 (6/4/2 小时)。 */
  autoResetHours: number
  /** 距今多少 tick 之前做的上一次**定时**刷新 (手动重置不写它)。 */
  lastResetAgoTicks: number
  /** false = 该难度的常驻区域此刻不存在, 6 个字段随之为 null。 */
  available: boolean
  instanceId: number
  genState: MiningGenState
  playersInside: number
  regionOriginX: number
  regionOriginZ: number
}

/**
 * 三块常驻区域。三行各占一种形态, 缺一种面板就有一条分支没验过:
 *   easy    READY 且空场      —— 唯一能真进得去、也是唯一能被 admin.mining.reset 受理的一块
 *   medium  READY_FALLBACK 有人在场 —— 重置会被 OCCUPIED 拒 (回执照样如实回报会踢几个人)
 *   hard    available=false   —— 6 个字段全 null 的那一态 (开服重建未完成的极早期)
 */
const MINING_ROWS: readonly MockMiningRow[] = [
  {
    difficulty: 'easy',
    requiredMinerLevel: 1,
    entryFee: 0,
    autoResetHours: 6,
    lastResetAgoTicks: 190_000,
    available: true,
    instanceId: 101,
    genState: 'READY',
    playersInside: 0,
    regionOriginX: 0,
    regionOriginZ: 0,
  },
  {
    difficulty: 'medium',
    requiredMinerLevel: 4,
    entryFee: 0,
    autoResetHours: 4,
    // 距下次定时刷新只剩约 15 分钟: 预警倒计时那一屏在 mock 下也看得见。
    lastResetAgoTicks: 270_000,
    available: true,
    instanceId: 102,
    // 回退生成态: 它同样 enterable, 面板不能把它画成故障。
    genState: 'READY_FALLBACK',
    playersInside: 3,
    regionOriginX: 4_096,
    regionOriginZ: 0,
  },
  {
    difficulty: 'hard',
    requiredMinerLevel: 8,
    entryFee: 0,
    autoResetHours: 2,
    lastResetAgoTicks: 100_000,
    available: false,
    instanceId: 103,
    genState: 'PENDING',
    playersInside: 0,
    regionOriginX: 8_192,
    regionOriginZ: 0,
  },
]

/** 重置受理后短暂停在 RESETTING 的到期时刻 (epoch ms)。真服重置完会回 READY, mock 用一个短窗口模拟这一步。 */
const miningResettingUntil = new Map<MiningDifficulty, number>()

/** 我此刻在哪一块区域; null = 不在矿洞。 */
let miningCurrentDifficulty: MiningDifficulty | null = null
/** capability 的实例指针。不在任何实例时是哨兵 **-1** (不是 0, 也不是 null)。 */
let miningCurrentInstanceId = -1
/** 出生保护截止 gameTime tick; 从未进过矿洞为 0。 */
let miningSpawnFreezeUntilTick = 0

function requireMiningRow(difficulty: MiningDifficulty): MockMiningRow {
  const row = MINING_ROWS.find((candidate) => candidate.difficulty === difficulty)
  if (row === undefined) {
    throw new Error(`mock 数据缺陷: 矿洞难度表里没有 ${difficulty}`)
  }
  return row
}

/** 入参大小写不敏感, 回执一律回显规范小写名 (与服务端同一个解析器)。 */
function requireDifficulty(action: WebUiActionName, value: unknown): MiningDifficulty {
  const text = typeof value === 'string' ? value.toLowerCase() : ''
  const hit = MINING_DIFFICULTIES.find((difficulty) => difficulty === text)
  if (hit === undefined) {
    throw businessFailure(action, 'INVALID_REQUEST', '难度非法', false, {
      field: 'difficulty',
      value: truncateValue(String(value)),
    })
  }
  return hit
}

function miningGenState(row: MockMiningRow): MiningGenState {
  const until = miningResettingUntil.get(row.difficulty)
  return until !== undefined && until > Date.now() ? 'RESETTING' : row.genState
}

/** 只有 READY / READY_FALLBACK 可进可重置 (GenState.isEnterable)。 */
function miningEnterableState(genState: MiningGenState): boolean {
  return genState === 'READY' || genState === 'READY_FALLBACK'
}

function mockMiningOverview(): MiningOverviewResult {
  const minerLevel = mockJobLevel('miner')
  const instances: MiningInstanceRow[] = MINING_ROWS.map((row) => {
    const genState = miningGenState(row)
    const lastResetGameTime = GAME_TICK_BASE - row.lastResetAgoTicks
    return {
      difficulty: row.difficulty,
      dropsOnDeath: row.difficulty === 'hard',
      entryFee: row.entryFee,
      nameKey: `difficulty.miningdim.${row.difficulty}`,
      requiredMinerLevel: row.requiredMinerLevel,
      unlocked: minerLevel >= row.requiredMinerLevel,
      available: row.available,
      instanceId: row.available ? row.instanceId : null,
      genState: row.available ? genState : null,
      enterable:
        row.available && minerLevel >= row.requiredMinerLevel && miningEnterableState(genState),
      playersInside: row.available ? row.playersInside : null,
      // 全服共享 (R1 模型下恒 true), 与"我的副本"是两个世界观。
      shared: row.available ? true : null,
      regionOriginX: row.available ? row.regionOriginX : null,
      regionOriginZ: row.available ? row.regionOriginZ : null,
      autoResetHours: row.autoResetHours,
      lastResetGameTime,
      // = lastReset + 小时 x 72000; 它是**预警起点**不是换图时刻 (真换图还在其后 warnSeconds 秒)。
      nextResetGameTime:
        row.autoResetHours <= 0 ? null : lastResetGameTime + row.autoResetHours * 72_000,
    }
  })
  return {
    instances,
    minerLevel,
    gameTime: gameTick(),
    autoResetWarnSeconds: MINING_AUTO_RESET_WARN_SECONDS,
    myDifficulty: miningCurrentDifficulty,
  }
}

function mockMiningMyStatus(): MiningMyStatusResult {
  const inside = miningCurrentDifficulty !== null
  const row = miningCurrentDifficulty === null ? null : requireMiningRow(miningCurrentDifficulty)
  const now = gameTick()
  return {
    inside,
    inMiningDimension: inside,
    difficulty: miningCurrentDifficulty,
    instanceId: row === null ? null : row.instanceId,
    genState: row === null ? null : miningGenState(row),
    // 不在矿洞时这几栏一律 null 而不是 0 —— 发 0 会被画成"你在原点那块区域"。
    regionOriginX: row === null ? null : row.regionOriginX,
    regionOriginZ: row === null ? null : row.regionOriginZ,
    currentInstanceId: miningCurrentInstanceId,
    gameTime: now,
    spawnFreezeUntilGameTime: miningSpawnFreezeUntilTick,
    spawnFreezeRemainingTicks: Math.max(0, miningSpawnFreezeUntilTick - now),
    minerLevel: mockJobLevel('miner'),
  }
}

/** 进场后的出生保护时长 (tick)。真服由 EntryGateway 写, 这里取 5 秒好让倒计时看得见走完。 */
const MINING_SPAWN_FREEZE_TICKS = 100

function mockMiningEnter(payload: MiningEnterPayload): MiningEnterResult {
  const difficulty = requireDifficulty('mining.enter', payload.difficulty)
  const row = requireMiningRow(difficulty)
  const minerLevel = mockJobLevel('miner')
  if (minerLevel < row.requiredMinerLevel) {
    return {
      difficulty,
      requiredMinerLevel: row.requiredMinerLevel,
      minerLevel,
      instanceId: row.instanceId,
      accepted: false,
      reasonCode: 'LEVEL_TOO_LOW',
      reasonKey: 'message.miningdim.gate.level_too_low',
    }
  }
  // 0 是合法免费配置; 与真服一致, 免费时连 wallet.credit 的读取也不发生。
  if (row.entryFee > 0 && wallet.credit < row.entryFee) {
    return {
      difficulty,
      requiredMinerLevel: row.requiredMinerLevel,
      minerLevel,
      instanceId: row.instanceId,
      accepted: false,
      reasonCode: 'INSUFFICIENT_FUNDS',
      reasonKey: 'message.miningdim.gate.insufficient_funds',
    }
  }
  if (miningCurrentDifficulty !== null) {
    return {
      difficulty,
      requiredMinerLevel: row.requiredMinerLevel,
      minerLevel,
      instanceId: row.instanceId,
      accepted: false,
      reasonCode: 'ALREADY_INSIDE',
      reasonKey: 'message.miningdim.enter.already_inside',
    }
  }
  if (!row.available) {
    // 与 admin.mining.reset 同一形态: 常驻区域不存在时服务端抛的是裸异常, 回执里没有 errorCode。
    throw plainFailure('mining.enter', `${difficulty} 难度的常驻区域此刻不存在`)
  }
  if (row.entryFee > 0) {
    wallet.credit -= row.entryFee
  }
  miningCurrentDifficulty = difficulty
  miningCurrentInstanceId = row.instanceId
  miningSpawnFreezeUntilTick = gameTick() + MINING_SPAWN_FREEZE_TICKS
  // 见 economyLastMineGameTick 的注释: 这是 mock 的近似 (进洞 != 挖矿), 但没有别的动作可挂。
  economyLastMineGameTick = gameTick()
  return {
    difficulty,
    requiredMinerLevel: row.requiredMinerLevel,
    minerLevel,
    instanceId: row.instanceId,
    /*
     * accepted 只表示"已交给权威入场链路"。mock 在同一次调用里就把人放进去了, 真服要等若干 tick 后
     * 才真传送 (且成败不走 webui 通道), 故面板确认是否真进去了仍必须轮询 mining.myStatus。
     */
    accepted: true,
    reasonCode: null,
    reasonKey: null,
  }
}

function mockMiningLeave(): MiningLeaveResult {
  if (miningCurrentDifficulty === null) {
    // 本就不在实例内不是错误, 回 left=false 而不是抛。
    return { left: false, reasonCode: 'NOT_INSIDE', reasonKey: 'message.miningdim.leave.not_inside' }
  }
  miningCurrentDifficulty = null
  miningCurrentInstanceId = -1
  miningSpawnFreezeUntilTick = 0
  return { left: true, reasonCode: null, reasonKey: null }
}

// ============================================================
// quest.* (只复刻任务板交互闭环, 任务判据与奖励池仍以服务端为准)
// ============================================================

const QUEST_DAILY_REFRESH_COST = 500
const QUEST_WEEKLY_REFRESH_COST = 2_500

const questDaily: QuestRow[] = [
  {
    questId: 'daily.mine.coal',
    title: '今日矿务',
    objective: '挖掘 32 个煤矿石',
    difficulty: 1,
    count: 14,
    requiredCount: 32,
    complete: false,
    claimed: false,
    turnIn: false,
    creditReward: 1_200,
    itemReward: { tier: 'IRON', materialStacks: 1, bookChance: 0.04 },
  },
  {
    questId: 'daily.turn_in.wheat',
    title: '粮食储备',
    objective: '上交 16 株农夫小麦',
    difficulty: 2,
    count: 0,
    requiredCount: 16,
    complete: false,
    claimed: false,
    turnIn: true,
    creditReward: 1_800,
    itemReward: { tier: 'IRON', materialStacks: 1, bookChance: 0.04 },
  },
  {
    questId: 'daily.kill.zombie',
    title: '清理亡灵',
    objective: '击败 24 只僵尸',
    difficulty: 2,
    count: 24,
    requiredCount: 24,
    complete: true,
    claimed: true,
    turnIn: false,
    creditReward: 2_200,
    itemReward: { tier: 'IRON', materialStacks: 1, bookChance: 0.04 },
  },
]

const questWeekly: QuestRow[] = [
  {
    questId: 'weekly.travel.overworld',
    title: '远行者',
    objective: '在主世界移动 12000 格',
    difficulty: 3,
    count: 12_000,
    requiredCount: 12_000,
    complete: true,
    claimed: false,
    turnIn: false,
    creditReward: 8_000,
    itemReward: { tier: 'DIAMOND', materialStacks: 1, bookChance: 0.3 },
  },
]

const questSpecial: QuestRow[] = [
  {
    questId: 'special.village.defense',
    title: '村庄告急',
    objective: '击败 5 名灾厄村民',
    difficulty: 3,
    count: 2,
    requiredCount: 5,
    complete: false,
    claimed: false,
    turnIn: false,
    creditReward: 5_000,
    itemReward: { tier: 'IRON', materialStacks: 1, bookChance: 0.04 },
  },
]

const questChains: QuestChainRow[] = [
  {
    chainId: 'deep_delver',
    title: '深层勘探',
    finished: false,
    stageIndex: 1,
    stageCount: 4,
    current: {
      questId: 'hidden.deep_delver.2',
      title: '深层勘探 II',
      objective: '在困难矿洞挖掘 10 个钻石矿石',
      difficulty: 3,
      count: 3,
      requiredCount: 10,
      complete: false,
      claimed: false,
      turnIn: false,
      creditReward: 3_500,
      itemReward: { tier: 'DIAMOND', materialStacks: 1, bookChance: 0.3 },
    },
  },
]

const QUEST_TURN_IN_ITEMS: ReadonlyMap<string, string> = new Map([
  ['daily.turn_in.wheat', FARMER_CROP_ITEM_ID],
])

let questRefreshSerial = 0

function cloneQuestRow(row: QuestRow): QuestRow {
  // itemReward 是嵌套对象, 浅拷会让所有克隆共享同一份 —— 与 cloneQuestItem 同一条纪律。
  return { ...row, itemReward: { ...row.itemReward } }
}

function cloneQuestItem(row: QuestItemRow): QuestItemRow {
  return {
    ...row,
    ...(row.enchantments === undefined
      ? {}
      : { enchantments: row.enchantments.map((enchantment) => ({ ...enchantment })) }),
  }
}

function questRows(): QuestRow[] {
  const rows = [...questDaily, ...questWeekly, ...questSpecial]
  for (const chain of questChains) {
    if (chain.current !== null) {
      rows.push(chain.current)
    }
  }
  return rows
}

function questById(questId: string): QuestRow | undefined {
  return questRows().find((row) => row.questId === questId)
}

function mockQuestBoard(): QuestBoardResult {
  return {
    dailyRefreshCost: QUEST_DAILY_REFRESH_COST,
    weeklyRefreshCost: QUEST_WEEKLY_REFRESH_COST,
    creditBalance: wallet.credit,
    daily: questDaily.map(cloneQuestRow),
    weekly: questWeekly.map(cloneQuestRow),
    special: questSpecial.map(cloneQuestRow),
    chains: questChains.map((chain) => ({
      ...chain,
      current: chain.current === null ? null : cloneQuestRow(chain.current),
    })),
  }
}

function questRewardItems(questId: string): QuestItemRow[] {
  if (questId === 'weekly.travel.overworld') {
    return [
      {
        itemId: 'minecraft:diamond_chestplate',
        descriptionId: 'item.minecraft.diamond_chestplate',
        count: 1,
        enchantments: [{ id: 'minecraft:mending', level: 1 }],
      },
    ]
  }
  return [
    {
      itemId: 'minecraft:diamond',
      descriptionId: 'item.minecraft.diamond',
      count: 2,
    },
  ]
}

function mockQuestClaim(payload: QuestClaimPayload): QuestClaimResult {
  const row = questById(payload.questId)
  if (row === undefined) {
    return { outcome: 'NOT_FOUND', questId: payload.questId, title: null, credit: 0, items: [] }
  }
  if (row.claimed) {
    return { outcome: 'ALREADY_CLAIMED', questId: row.questId, title: row.title, credit: 0, items: [] }
  }
  if (!row.complete) {
    return { outcome: 'NOT_COMPLETE', questId: row.questId, title: row.title, credit: 0, items: [] }
  }
  const items = questRewardItems(row.questId)
  wallet.credit += row.creditReward
  row.claimed = true
  for (const item of items) {
    depositToInventory('quest.claim', item.itemId, item.count)
  }
  return {
    outcome: 'CLAIMED',
    questId: row.questId,
    title: row.title,
    credit: row.creditReward,
    items: items.map(cloneQuestItem),
  }
}

function takeQuestTurnInItem(itemId: string, wanted: number): number {
  let remaining = wanted
  for (const stack of [...inventory]) {
    if (remaining <= 0) {
      break
    }
    if (stack.itemId !== itemId) {
      continue
    }
    const taken = Math.min(stack.count, remaining)
    stack.count -= taken
    remaining -= taken
    if (stack.count === 0) {
      inventory.splice(inventory.indexOf(stack), 1)
    }
  }
  return wanted - remaining
}

function mockQuestTurnIn(payload: QuestTurnInPayload): QuestTurnInResult {
  const row = questById(payload.questId)
  if (row === undefined) {
    return { outcome: 'NOT_FOUND', questId: payload.questId, title: null, count: 0 }
  }
  if (!row.turnIn) {
    return { outcome: 'NOT_A_TURN_IN', questId: row.questId, title: row.title, count: 0 }
  }
  if (row.complete) {
    return { outcome: 'ALREADY_COMPLETE', questId: row.questId, title: row.title, count: 0 }
  }
  const itemId = QUEST_TURN_IN_ITEMS.get(row.questId)
  if (itemId === undefined) {
    throw new Error(`mock 数据缺陷: 上交任务 ${row.questId} 没有登记物品`)
  }
  const taken = takeQuestTurnInItem(itemId, row.requiredCount - row.count)
  if (taken <= 0) {
    return { outcome: 'NOTHING_TO_TURN_IN', questId: row.questId, title: row.title, count: 0 }
  }
  row.count += taken
  row.complete = row.count >= row.requiredCount
  return { outcome: 'TURNED_IN', questId: row.questId, title: row.title, count: taken }
}

function refreshedQuest(source: QuestRefreshPayload['source']): QuestRow {
  questRefreshSerial += 1
  return {
    questId: `${source}.mock.refresh.${String(questRefreshSerial)}`,
    title: source === 'daily' ? '临时巡查' : '周度清剿',
    objective: source === 'daily' ? '击败 12 只骷髅' : '击败 80 只怪物',
    difficulty: source === 'daily' ? 1 : 3,
    count: 0,
    requiredCount: source === 'daily' ? 12 : 80,
    complete: false,
    claimed: false,
    turnIn: false,
    creditReward: source === 'daily' ? 1_400 : 7_500,
    itemReward:
      source === 'daily'
        ? { tier: 'IRON', materialStacks: 1, bookChance: 0.04 }
        : { tier: 'DIAMOND', materialStacks: 1, bookChance: 0.3 },
  }
}

function mockQuestRefresh(payload: QuestRefreshPayload): QuestRefreshResult {
  const source = payload.source.toLowerCase()
  if (source !== 'daily' && source !== 'weekly') {
    throw businessFailure('quest.refresh', 'INVALID_REQUEST', '任务来源只接受 daily 或 weekly', false, {
      field: 'source',
      value: truncateValue(String(payload.source)),
    })
  }
  const rows = source === 'daily' ? questDaily : questWeekly
  if (!Number.isInteger(payload.slot) || payload.slot < 0 || payload.slot >= rows.length) {
    throw businessFailure('quest.refresh', 'SLOT_OUT_OF_RANGE', '任务槽位超出范围', false, {
      slot: String(payload.slot),
      size: String(rows.length),
    })
  }
  const cost = source === 'daily' ? QUEST_DAILY_REFRESH_COST : QUEST_WEEKLY_REFRESH_COST
  if (wallet.credit < cost) {
    return { outcome: 'NOT_ENOUGH_CREDIT', cost, replacement: null }
  }
  wallet.credit -= cost
  const replacement = refreshedQuest(source)
  rows[payload.slot] = replacement
  return { outcome: 'REFRESHED', cost, replacement: cloneQuestRow(replacement) }
}

// ============================================================
// champion.* (StarRank / AffixQuality / AffixDef / ChampionSpawnPolicy 的真值)
// ============================================================

/** [qualityId, tier, costMultiplier, displayColorRgb] —— AffixQuality 五档真值。 */
const CHAMPION_QUALITY_ROWS: readonly (readonly [ChampionAffixQuality, number, number, number])[] = [
  ['COMMON', 0, 1, 0xc8c8c8],
  ['UNCOMMON', 1, 1.6, 0x55c040],
  ['RARE', 2, 2.5, 0x3070e0],
  ['EPIC', 3, 4, 0x9b30e0],
  ['LEGENDARY', 4, 6.5, 0xe0b020],
]

/** [star, 生存, 战斗, 机动, 技能, 词条上限, 技能上限, 最高品质, 基础有效HP, 单击基线] —— StarRank 十行真值。 */
const CHAMPION_STAR_ROWS: readonly (readonly [
  number,
  number,
  number,
  number,
  number,
  number,
  number,
  ChampionAffixQuality,
  number,
  number,
])[] = [
  [1, 10, 8, 0, 0, 1, 0, 'COMMON', 135, 0.04],
  [2, 20, 14, 4, 0, 2, 0, 'COMMON', 225, 0.05],
  [3, 35, 24, 8, 15, 3, 1, 'UNCOMMON', 360, 0.06],
  [4, 55, 36, 12, 25, 4, 1, 'UNCOMMON', 540, 0.08],
  [5, 80, 55, 20, 45, 5, 1, 'RARE', 765, 0.1],
  [6, 120, 80, 30, 70, 6, 2, 'RARE', 2_700, 0.12],
  [7, 165, 110, 45, 110, 7, 2, 'EPIC', 6_000, 0.14],
  [8, 240, 160, 75, 180, 9, 3, 'EPIC', 27_000, 0.16],
  [9, 330, 230, 115, 260, 11, 3, 'LEGENDARY', 45_000, 0.18],
  [10, 440, 310, 155, 360, 13, 4, 'LEGENDARY', 73_000, 0.2],
]

/** StarRank.barColorRgb 的十档 signature 色 (低星暖粉 -> 黄 -> 橙 -> 青 -> 紫, 高星红系收尾于金/紫)。 */
const CHAMPION_BAR_COLORS: readonly number[] = [
  0xffc0cb, 0xffff00, 0xff9900, 0x66ffff, 0xcc33ff, 0xff5555, 0xff0000, 0xaa0000, 0xffd700, 0xb030ff,
]

type MockAffixSeed = {
  affixId: string
  pool: ChampionAffixPool
  baseCost: number
  minStar: number
  isSkill: boolean
  /** 无互斥是字符串 'NONE' 而不是 null —— 判空要写 === 'NONE'。 */
  mutexFlag: string
  primaryUnit: string
  /** 长度恒 5; 0 一律是"该档不存在"的占位 (没有任何词条的合法数值是 0)。 */
  primaryValues: readonly number[]
  secondaryUnit: string | null
  secondaryValues: readonly number[] | null
}

/**
 * 词条表。**真服恒 35 条, 这里只取 13 条**, 因为图鉴页要验的是"每种形态画对没有"而不是条数:
 *   HEAVY_ARMOR / FORTITUDE_SHIELD 前两档是 0 -> availableTiers 必须把那两格灰掉 (照直画会多出"减伤 0%")
 *   DEATH_MARK 前三档是 0, SELF_REPAIR **中段**一档是 0 (spec 那个 "—") -> 0 不总在开头
 *   OVERDRIVE 2.50 / GIGANTISM 1.80 / HEAVY_CANNON 1.00 -> 按 0..1 钳制会把超速压成 100%
 *   FORTITUDE_SHIELD 是"数值越小越强" -> 不能按大即强排序
 *   GIGANTISM / FROST / THUNDER / SUMMON_SUPPORT 带副数值, 其余整键缺席
 * 顺序 = AffixDef 声明序 (champion.inspect 的 affixes 与本表可直接按 affixId join)。
 */
const CHAMPION_AFFIX_SEEDS: readonly MockAffixSeed[] = [
  {
    affixId: 'COMPOSITE_ARMOR',
    pool: 'SURVIVAL',
    baseCost: 8,
    minStar: 1,
    isSkill: false,
    mutexFlag: 'NONE',
    primaryUnit: 'fraction_damage_reduction',
    primaryValues: [0.35, 0.45, 0.55, 0.65, 0.75],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'HEAVY_ARMOR',
    pool: 'SURVIVAL',
    baseCost: 26,
    minStar: 7,
    isSkill: false,
    mutexFlag: 'HEAVY_ARMOR',
    primaryUnit: 'fraction_damage_reduction',
    primaryValues: [0, 0, 0.35, 0.42, 0.49],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'FLAMMABLE_REGEN',
    pool: 'SURVIVAL',
    baseCost: 10,
    minStar: 3,
    isSkill: false,
    mutexFlag: 'NONE',
    primaryUnit: 'flat_hp_per_second',
    primaryValues: [8, 15, 30, 60, 90],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'FORTITUDE_SHIELD',
    pool: 'SURVIVAL',
    baseCost: 22,
    minStar: 6,
    isSkill: false,
    mutexFlag: 'FORTITUDE',
    primaryUnit: 'flat_hp_damage_cap',
    primaryValues: [0, 0, 120, 80, 50],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'GIGANTISM',
    pool: 'SURVIVAL',
    baseCost: 12,
    minStar: 3,
    isSkill: false,
    mutexFlag: 'SIZE',
    primaryUnit: 'fraction_max_health_bonus',
    primaryValues: [0.3, 0.5, 0.8, 1.2, 1.8],
    secondaryUnit: 'fraction_size_bonus',
    secondaryValues: [0.25, 0.4, 0.6, 0.85, 1.2],
  },
  {
    affixId: 'BURNING',
    pool: 'COMBAT',
    baseCost: 8,
    minStar: 1,
    isSkill: false,
    mutexFlag: 'NONE',
    primaryUnit: 'fraction_maxhp_per_second_per_stack',
    primaryValues: [0.01, 0.015, 0.02, 0.03, 0.04],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'HEAVY_CANNON',
    pool: 'COMBAT',
    baseCost: 10,
    minStar: 2,
    isSkill: false,
    mutexFlag: 'NONE',
    primaryUnit: 'fraction_damage_bonus',
    primaryValues: [0.3, 0.475, 0.65, 0.825, 1],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'FROST',
    pool: 'COMBAT',
    baseCost: 10,
    minStar: 2,
    isSkill: false,
    mutexFlag: 'NONE',
    primaryUnit: 'fraction_maxhp_per_second_per_stack',
    primaryValues: [0.008, 0.012, 0.018, 0.025, 0.035],
    secondaryUnit: 'fraction_slow_per_stack',
    secondaryValues: [0.04, 0.06, 0.08, 0.1, 0.12],
  },
  {
    affixId: 'OVERDRIVE',
    pool: 'MOBILITY',
    baseCost: 10,
    minStar: 3,
    isSkill: false,
    mutexFlag: 'MOVE_SPEED',
    primaryUnit: 'fraction_move_speed_bonus',
    primaryValues: [1, 1.3, 1.6, 2, 2.5],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'THUNDER',
    pool: 'SKILL',
    baseCost: 18,
    minStar: 5,
    isSkill: true,
    mutexFlag: 'NONE',
    primaryUnit: 'fraction_maxhp',
    primaryValues: [0.12, 0.17, 0.22, 0.27, 0.32],
    secondaryUnit: 'strike_count',
    secondaryValues: [2, 3, 4, 5, 6],
  },
  {
    affixId: 'DEATH_MARK',
    pool: 'SKILL',
    baseCost: 30,
    minStar: 8,
    isSkill: true,
    mutexFlag: 'DEATH_MARK',
    primaryUnit: 'multiplier',
    primaryValues: [0, 0, 0, 1.6, 1.6],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'SELF_REPAIR',
    pool: 'SKILL',
    baseCost: 14,
    minStar: 4,
    isSkill: true,
    mutexFlag: 'NONE',
    primaryUnit: 'flat_hp_per_second',
    // 中级档那个 0 是 spec "40/—/80/150/300" 里的 "—": 0 不总在开头, 前端不得按"前导 0"简化判据。
    primaryValues: [40, 0, 80, 150, 300],
    secondaryUnit: null,
    secondaryValues: null,
  },
  {
    affixId: 'SUMMON_SUPPORT',
    pool: 'SKILL',
    baseCost: 16,
    minStar: 4,
    isSkill: true,
    mutexFlag: 'NONE',
    primaryUnit: 'count',
    primaryValues: [1, 2, 2, 3, 3],
    secondaryUnit: 'concurrent_count',
    secondaryValues: [2, 3, 4, 5, 6],
  },
]

/** 首个主数值非 0 的品质 = 该词条的最低可用档 (AffixDef.minUsableQuality 的同一判据)。 */
function affixMinQuality(seed: MockAffixSeed): ChampionAffixQuality {
  const tier = seed.primaryValues.findIndex((value) => value !== 0)
  if (tier < 0) {
    throw new Error(`mock 数据缺陷: 词条 ${seed.affixId} 五档全 0`)
  }
  return requireAt(CHAMPION_QUALITY_ROWS, tier, '词条品质表')[0]
}

/** = ceil(baseCost x costMultiplier)。ceil 是防小数成本破整数点池预算的业务规则, 前端不得自己乘。 */
function affixCosts(baseCost: number): number[] {
  return CHAMPION_QUALITY_ROWS.map(([, , costMultiplier]) => Math.ceil(baseCost * costMultiplier))
}

function championAffixRow(seed: MockAffixSeed): ChampionAffixRow {
  return {
    affixId: seed.affixId,
    nameKey: `affix.champions.${seed.affixId.toLowerCase()}`,
    pool: seed.pool,
    baseCost: seed.baseCost,
    minStar: seed.minStar,
    isSkill: seed.isSkill,
    mutexFlag: seed.mutexFlag,
    minQuality: affixMinQuality(seed),
    primaryUnit: seed.primaryUnit,
    primaryValues: [...seed.primaryValues],
    availableTiers: seed.primaryValues.map((value) => value !== 0),
    costs: affixCosts(seed.baseCost),
    // 副数值两键同进同出; 无副数值时**整键不出现** (默认 Gson 不写 null)。
    ...(seed.secondaryUnit === null || seed.secondaryValues === null
      ? {}
      : { secondaryUnit: seed.secondaryUnit, secondaryValues: [...seed.secondaryValues] }),
  }
}

function requireStarRow(star: number): (typeof CHAMPION_STAR_ROWS)[number] {
  const row = CHAMPION_STAR_ROWS.find(([value]) => value === star)
  if (row === undefined) {
    throw new Error(`mock 数据缺陷: 星表里没有 ${String(star)} 星`)
  }
  return row
}

function mockChampionCodex(): ChampionCodexResult {
  const qualities: ChampionQualityRow[] = CHAMPION_QUALITY_ROWS.map(
    ([qualityId, tier, costMultiplier, displayColorRgb]) => ({
      qualityId,
      tier,
      costMultiplier,
      displayColorRgb,
    }),
  )
  const stars: ChampionStarRow[] = CHAMPION_STAR_ROWS.map(
    ([
      star,
      survivalBudget,
      combatBudget,
      mobilityBudget,
      skillBudget,
      maxAffixes,
      maxSkills,
      maxQuality,
      baseEffectiveHp,
      baseSingleHitPct,
    ]) => ({
      star,
      survivalBudget,
      combatBudget,
      mobilityBudget,
      skillBudget,
      maxAffixes,
      maxSkills,
      maxQuality,
      baseEffectiveHp,
      baseSingleHitPct,
      // 红线 3 的三档硬上限; 与设计基线是两回事, 故两列都发。
      normalHitCapPct: star <= 5 ? 0.4 : star <= 7 ? 0.5 : 0.6,
      usesCustomBloodPool: star >= 6,
      barColorRgb: requireAt(CHAMPION_BAR_COLORS, star - 1, 'BOSS 血条配色表'),
    }),
  )
  const distribution: ChampionDistributionRow[] = [
    { difficulty: 'EASY', configName: 'easy', promoteChance: 0.06, minStar: 1, maxStar: 3 },
    { difficulty: 'MEDIUM', configName: 'medium', promoteChance: 0.1, minStar: 3, maxStar: 6 },
    { difficulty: 'HARD', configName: 'hard', promoteChance: 0.15, minStar: 5, maxStar: 10 },
  ]
  return {
    customBloodPoolMinStar: 6,
    // 无权重表: 掷星就是区间内均匀取整, 要画分布图只能按均匀分布铺。
    starRollMode: 'UNIFORM_INCLUSIVE',
    qualities,
    affixes: CHAMPION_AFFIX_SEEDS.map((seed) => championAffixRow(seed)),
    stars,
    distribution,
  }
}

type MockChampionSeed = {
  entityId: number
  entityTypeId: string
  star: number
  summonedByAffix: boolean
  /** 权威血量比例 (血池档与 vanilla 档都按它算, 保证 health/maxHealth 自洽)。 */
  healthFraction: number
  /** 盖章时算出的有效血 (星表基础血 x 生存点剩余曲线 x 体型乘数), 与星表基础值不等。 */
  effectiveHp: number
  affixes: readonly (readonly [string, ChampionAffixQuality])[]
}

/**
 * 两只可查的精英, 且 entityId 与 job.agent.* 的扫描目标是同一套号 —— 扫到之后点进图鉴看详情, 这条路径
 * 在假数据模式下才走得通。
 *
 * 7 星那只是**血池档**: maxHealth 5312.8 而 vanillaMaxHealth 被 generic.max_health 的 1024 硬上限钳住
 * (契约里那组实测值)。画血条只能用 health/maxHealth, 拿 vanilla 那一对算比例必错 —— 这一对数字造出来
 * 就是为了让照着 vanilla 算的实现在 mock 阶段就露馅。
 * 3 星那只走 vanilla: 两对数字相等, 于是"用错了也看不出来"的那一半也在。
 */
const CHAMPION_SEEDS: readonly MockChampionSeed[] = [
  {
    entityId: 40_219,
    entityTypeId: 'minecraft:zombie',
    star: 3,
    summonedByAffix: false,
    healthFraction: 0.82,
    effectiveHp: 424.8,
    affixes: [
      ['COMPOSITE_ARMOR', 'UNCOMMON'],
      ['BURNING', 'COMMON'],
      ['HEAVY_CANNON', 'COMMON'],
    ],
  },
  {
    entityId: 40_877,
    entityTypeId: 'minecraft:skeleton',
    star: 7,
    summonedByAffix: false,
    healthFraction: 0.63,
    effectiveHp: 5_312.8,
    affixes: [
      ['HEAVY_ARMOR', 'RARE'],
      ['FLAMMABLE_REGEN', 'RARE'],
      ['GIGANTISM', 'EPIC'],
      ['FROST', 'RARE'],
      ['THUNDER', 'EPIC'],
      ['SELF_REPAIR', 'EPIC'],
    ],
  },
]

/** 在场但不是精英的实体 (NOT_A_CHAMPION 那条拒绝); 表外的 id 一律 ENTITY_NOT_FOUND。 */
const CHAMPION_PLAIN_ENTITY_IDS: readonly number[] = [50_001]

function mockChampionInspect(payload: ChampionInspectPayload): ChampionInspectResult {
  const seed = CHAMPION_SEEDS.find((candidate) => candidate.entityId === payload.entityId)
  if (seed === undefined) {
    /*
     * 两种拒绝共用 INVALID_REQUEST, 靠 params.reason 区分 —— 服务端绝不会返回一份 star=0 的成功回执
     * 冒充"什么都没有", 故这里也必须抛而不是回一个空壳。
     */
    const reason = CHAMPION_PLAIN_ENTITY_IDS.includes(payload.entityId)
      ? 'NOT_A_CHAMPION'
      : 'ENTITY_NOT_FOUND'
    throw businessFailure('champion.inspect', 'INVALID_REQUEST', '该实体不是本工程的精英', false, {
      field: 'entityId',
      value: truncateValue(String(payload.entityId)),
      reason,
    })
  }
  const starRow = requireStarRow(seed.star)
  const customBloodPool = seed.star >= 6
  const maxHealth = seed.effectiveHp
  const vanillaMaxHealth = customBloodPool ? 1_024 : seed.effectiveHp
  const affixes: ChampionInspectAffix[] = CHAMPION_AFFIX_SEEDS.filter((affix) =>
    seed.affixes.some(([affixId]) => affixId === affix.affixId),
  ).map((affix) => {
    const pick = seed.affixes.find(([affixId]) => affixId === affix.affixId)
    if (pick === undefined) {
      throw new Error(`mock 数据缺陷: 词条 ${affix.affixId} 没有掷出的品质`)
    }
    const qualityRow = CHAMPION_QUALITY_ROWS.find(([qualityId]) => qualityId === pick[1])
    if (qualityRow === undefined) {
      throw new Error(`mock 数据缺陷: 品质表里没有 ${pick[1]}`)
    }
    const tier = qualityRow[1]
    return {
      affixId: affix.affixId,
      nameKey: `affix.champions.${affix.affixId.toLowerCase()}`,
      pool: affix.pool,
      isSkill: affix.isSkill,
      quality: pick[1],
      tier,
      // 实际点数成本与主数值都从图鉴那张表按 tier 取, 不另写一份 —— 两处各写一份必然对不上。
      cost: requireAt(affixCosts(affix.baseCost), tier, `${affix.affixId} 成本表`),
      primaryUnit: affix.primaryUnit,
      primaryValue: requireAt(affix.primaryValues, tier, `${affix.affixId} 主数值表`),
      ...(affix.secondaryUnit === null || affix.secondaryValues === null
        ? {}
        : {
            secondaryUnit: affix.secondaryUnit,
            secondaryValue: requireAt(affix.secondaryValues, tier, `${affix.affixId} 副数值表`),
          }),
    }
  })
  return {
    entityId: seed.entityId,
    entityTypeId: seed.entityTypeId,
    entityDescriptionId: `entity.minecraft.${seed.entityTypeId.slice('minecraft:'.length)}`,
    star: seed.star,
    maxQuality: starRow[7],
    summonedByAffix: seed.summonedByAffix,
    effectiveHp: seed.effectiveHp,
    customBloodPool,
    healthSource: customBloodPool ? 'BLOOD_POOL' : 'VANILLA_MAX_HEALTH',
    health: maxHealth * seed.healthFraction,
    maxHealth,
    healthFraction: seed.healthFraction,
    vanillaHealth: vanillaMaxHealth * seed.healthFraction,
    vanillaMaxHealth,
    affixes,
  }
}

// ============================================================
// admin.economy.* / admin.job.setLevel / admin.mining.reset
// (OP 门控一律不复刻: mock 里没有权限体系, 与 admin.setBaseValue 同一条纪律)
// ============================================================

function mockAdminEconomyBalance(payload: AdminEconomyBalancePayload): AdminEconomyBalanceResult {
  const player = requireOnlinePlayer('admin.economy.balance', 'playerName', payload.playerName)
  return {
    // 回的是解析到的**真名**而不是回显入参 (入参大小写可能不同)。
    playerName: player.name,
    playerUuid: player.uuid,
    wallet: { ...walletOf(player) },
  }
}

function mockAdminEconomySet(payload: AdminEconomySetPayload): AdminEconomySetResult {
  const player = requireOnlinePlayer('admin.economy.set', 'playerName', payload.playerName)
  if (payload.currency !== 'CREDIT' && payload.currency !== 'AZURE') {
    throw businessFailure('admin.economy.set', 'INVALID_REQUEST', '币种非法', false, {
      field: 'currency',
      value: truncateValue(String(payload.currency)),
    })
  }
  if (
    !Number.isInteger(payload.amount) ||
    payload.amount < 0 ||
    payload.amount > Number.MAX_SAFE_INTEGER
  ) {
    // 上界是 JS Number 的无损整数上限而非账本上限 —— 超过它的"整数"在到达服务端前就已经变形了。
    throw businessFailure('admin.economy.set', 'INVALID_REQUEST', '金额必须是 0 到 2^53-1 的整数', false, {
      field: 'amount',
      value: String(payload.amount),
    })
  }
  const target = walletOf(player)
  // 改前快照: 真服没有调账流水表, 这是唯一一次看见改前值的机会。
  const before: WebUiWallet = { ...target }
  if (payload.currency === 'CREDIT') {
    target.credit = payload.amount
  } else {
    target.azure = payload.amount
  }
  return {
    playerName: player.name,
    playerUuid: player.uuid,
    currency: payload.currency,
    before,
    wallet: { ...target },
  }
}

/**
 * 占位整级线: 每级 10000 累计经验。
 *
 * **不是 JobXpCurve 的复刻** —— 那条曲线抄一份必然与 Java 侧漂移 (同文件头第 1 条纪律)。它只需满足
 * setLevel 的那条副作用: 服务端 JobProgress.setLevel 会把累计经验一并对齐到该级整级线, 只改 level
 * 不改 xp 的话下一次入账会被曲线按旧 xp 派生回原等级。
 */
const MOCK_XP_PER_LEVEL = 10_000

const MOCK_JOB_IDS: readonly WebUiJobId[] = [
  'miner',
  'farmer',
  'engineer',
  'tarot',
  'chef',
  'agent',
  'munitions',
  'brewer',
]

function mockAdminJobSetLevel(payload: AdminJobSetLevelPayload): AdminJobSetLevelResult {
  const player = requireOnlinePlayer('admin.job.setLevel', 'playerName', payload.playerName)
  const jobId = MOCK_JOB_IDS.find((candidate) => candidate === payload.jobId)
  if (jobId === undefined) {
    throw businessFailure('admin.job.setLevel', 'INVALID_REQUEST', '未知职业 id', false, {
      field: 'jobId',
      value: truncateValue(String(payload.jobId)),
    })
  }
  if (!Number.isInteger(payload.level) || payload.level < 1 || payload.level > 10) {
    throw businessFailure('admin.job.setLevel', 'INVALID_REQUEST', '等级必须是 1-10 的整数', false, {
      field: 'level',
      value: String(payload.level),
    })
  }
  const level = payload.level
  const totalXp = (level - 1) * MOCK_XP_PER_LEVEL
  /*
   * 只有改本人时才写 mock 世界: 世界里那份职业进度就是本人的 (player.profile / job.progress / 各职业面板
   * 都读它), 别人的进度 mock 根本没有存储。对别人只回一份形状正确的回执 —— 这是 mock 的缺口, 不是契约的。
   */
  if (player.name === MOCK_PLAYER_NAME) {
    mutateWorld((draft) => {
      const entry = draft.jobs.progress.find((candidate) => candidate.jobId === jobId)
      if (entry === undefined) {
        throw new Error(`mock 数据缺陷: 职业进度表里没有 ${jobId}`)
      }
      entry.level = level
      entry.totalXp = totalXp
      entry.levelXp = 0
      // 满级态: nextLevelXp 与 levelXp 同时发 0, 前端据前者判满级而不是硬编码 level === 10。
      entry.nextLevelXp = level >= 10 ? 0 : MOCK_XP_PER_LEVEL
    })
  }
  return { playerName: player.name, playerUuid: player.uuid, jobId, level, totalXp }
}

/** 受理后停在 RESETTING 的时长 (ms)。真服的重置是异步的, 终局只进服务端日志。 */
const MINING_RESET_WINDOW_MS = 10_000

function mockAdminMiningReset(payload: AdminMiningResetPayload): AdminMiningResetResult {
  const difficulty = requireDifficulty('admin.mining.reset', payload.difficulty)
  if (payload.reseed !== undefined && typeof payload.reseed !== 'boolean') {
    throw businessFailure('admin.mining.reset', 'INVALID_REQUEST', 'reseed 必须是布尔', false, {
      field: 'reseed',
      value: truncateValue(String(payload.reseed)),
    })
  }
  const row = requireMiningRow(difficulty)
  if (!row.available) {
    // 常驻区域实例不存在时服务端抛的是裸 IllegalStateException: 回执没有 errorCode, 只处理 reasonCode 两态会漏掉它。
    throw plainFailure('admin.mining.reset', `${difficulty} 难度的常驻区域实例不存在`)
  }
  const genState = miningGenState(row)
  const occupants = row.playersInside
  // 裁决与 ResetSystem.reset 的前置校验对齐; requireEmpty 取真服默认的"有人就拒", kickOnForce 不复刻。
  const reasonCode = !miningEnterableState(genState)
    ? 'NOT_RESETTABLE'
    : occupants > 0
      ? 'OCCUPIED'
      : null
  if (reasonCode === null) {
    miningResettingUntil.set(difficulty, Date.now() + MINING_RESET_WINDOW_MS)
  }
  return {
    difficulty,
    // 缺省 true = NEW_SEED 换图。
    mode: payload.reseed === false ? 'SAME_SEED' : 'NEW_SEED',
    // 受理那一刻的矿山维度 gameTime (不是重置完成时刻, 更不是 epoch millis)。
    requestedAtGameTime: gameTick(),
    instanceId: row.instanceId,
    genState: miningGenState(row),
    // 被拒时也如实回报会踢几个人, 而不是 0。
    evictedPlayers: occupants,
    accepted: reasonCode === null,
    reasonCode,
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
    /*
     * 批量合并在没有宿主时是关着的 (lib/bridge 的 call 判据是 bridgeInjected() && isBatchableAction),
     * 所以这条分支<b>不可达</b>: 假数据模式下每条 action 各自落到上面那些 mock 函数, 没有往返成本可省。
     *
     * 于是这里明确抛错而不是补一个能跑的假实现 —— 它真被走到只有一种可能: 那个判据被改坏了, 而那时
     * 假数据模式会开始批量, 一整屏数据的形状全变。宁可在 dev 里当场炸掉, 也不要让它默默"看起来能用"。
     */
    case 'system.batch':
      throw new Error(
        'bridge.mock 不实现 system.batch: 无宿主时批量合并本应是关闭的 (见 lib/bridge.ts 的 call 与 lib/batch.ts)',
      )
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
    case 'player.roster':
      return mockPlayerRoster()
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
    case 'job.tarot.state':
      return mockTarotState()
    case 'job.tarot.buyPack':
      return mockTarotBuyPack(payload as TarotBuyPackPayload)
    case 'job.agent.state':
      return mockAgentState()
    case 'job.agent.scan':
      return mockAgentScan()
    case 'job.agent.seal':
      return mockAgentSeal(payload as AgentSealPayload)
    case 'job.munitions.state':
      return mockMunitionsState()
    case 'job.blueprints':
      return mockBlueprints()
    case 'job.engineer.state':
      return mockEngineerState()
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
    case 'admin.economy.balance':
      return mockAdminEconomyBalance(payload as AdminEconomyBalancePayload)
    case 'admin.economy.set':
      return mockAdminEconomySet(payload as AdminEconomySetPayload)
    case 'admin.job.setLevel':
      return mockAdminJobSetLevel(payload as AdminJobSetLevelPayload)
    case 'admin.mining.reset':
      return mockAdminMiningReset(payload as AdminMiningResetPayload)
    case 'economy.status':
      return mockEconomyStatus()
    case 'economy.today':
      return mockEconomyToday()
    case 'economy.priceTable':
      return mockEconomyPriceTable()
    case 'marriage.state':
      return mockMarriageState()
    case 'marriage.buyRing':
      return mockMarriageBuyRing()
    case 'marriage.propose':
      return mockMarriagePropose(payload as MarriageProposePayload)
    case 'marriage.respond':
      return mockMarriageRespond(payload as MarriageRespondPayload)
    case 'marriage.wed':
      return mockMarriageWed(payload as MarriageWedPayload)
    case 'marriage.divorce':
      return mockMarriageDivorce()
    case 'marriage.sharedInv':
      return mockMarriageSharedInv()
    case 'mining.overview':
      return mockMiningOverview()
    case 'mining.myStatus':
      return mockMiningMyStatus()
    case 'mining.enter':
      return mockMiningEnter(payload as MiningEnterPayload)
    case 'mining.leave':
      return mockMiningLeave()
    case 'quest.board':
      return mockQuestBoard()
    case 'quest.claim':
      return mockQuestClaim(payload as QuestClaimPayload)
    case 'quest.turnIn':
      return mockQuestTurnIn(payload as QuestTurnInPayload)
    case 'quest.refresh':
      return mockQuestRefresh(payload as QuestRefreshPayload)
    case 'champion.codex':
      return mockChampionCodex()
    case 'champion.inspect':
      return mockChampionInspect(payload as ChampionInspectPayload)
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
    // 两条宿主动作在浏览器里没有对应物 (没有 Screen 可关, 也没有 Java 侧的焦点标记),
    // 回一个成功回执即可 —— 目的只是让 dev 下点关闭按钮不报错。
    case 'client.closePanel':
      return { closed: true }
    case 'client.textFocus':
      return { ok: true }
    // 浏览器里没有宿主可调, 回默认值让设置页能画出滑块。
    case 'client.display.get':
    case 'client.display.set':
      return { zoomPercent: 125, coveragePercent: 70 }
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
