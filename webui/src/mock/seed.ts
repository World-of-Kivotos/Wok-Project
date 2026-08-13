/**
 * mock 世界的初始数据。
 *
 * 数值口径声明 (最重要的一条, 请勿越过):
 * **本文件的数值全部是演示用, 不是平衡真源。** 平衡真源在 docs/ 下各职业 spec 与 Java 常量类里。
 * 任何人要评估经济平衡、掉率、等级曲线, 都必须去读那些文件, 不能拿这里的数字当依据 —— 这里的数字
 * 只需满足两个条件: 量级读起来合理 (不会让面板排版失真), 以及能把边界形态铺满。
 *
 * 唯一的例外是精英怪图鉴 (champion.codex): 35 词条与 10 星级表是逐字抄自 com.miningdim.champion.AffixDef
 * 与 StarRank 的真值 —— 那两张表本来就是纯静态枚举 dump (接线清单 G1 判定为 WRAP), 抄真值比编一份
 * 假的更省事也更有用; 抄的时候连"高级+才有意义的档位填 0 占位"这类细节也一并保留了。
 *
 * 刻意铺满的边界形态 (设计稿最容易漏掉的那些):
 *   - 空列表      特勤扫描候选 seals 为空 (未扫描态)、待收货款 items 为空、部分商店无比价对象
 *   - 超长中文名  防弹背心的 45 字名, 撞流水行/物品格/表头三处截断 (与 bridge.mock 用的是同一件物品)
 *   - NBT 变体件  同 itemId 同翻译键、只靠 customModelData + nameParts 区分的枪匠零件 (见 ITEM_GAS_CORE)
 *   - 极大数值    一条 total = 2^53-1 的成交流水 + 一个余额 2^53-1 的鲸鱼玩家, 撞金额格式化与 long 精度边界
 *   - 零值        一个双币余额均为 0 的玩家、一个 0 层数的酒、一条 0 进度的悬赏
 *   - 单页刚好装满  系统商店 20 条 (按 pageSize=20 恰好一页, 第 2 页为空)
 *   - 差一个装满   成交流水 39 条 (按 pageSize=20 分两页, 第 2 页 19 条, 差一条满页)
 *   - 名字缺席    一条对手方为 null 的流水 (系统回收)、未改名物品无 displayName 键
 */

import type { ItemNamePart } from '../lib/i18n'
import type { MarketListing, PlayerInventoryItem } from '../lib/types'
import type {
  PlannedAffixPool,
  PlannedChampionAffix,
  PlannedChampionStar,
  PlannedHubPanel,
  PlannedJobId,
  PlannedJobProgressEntry,
  PlannedMarketTransaction,
  PlannedShopEntry,
  PlannedStatLine,
  PlannedTarotCard,
  PlannedTxnRole,
} from './planned'
import type { MockOtherPlayer, MockWorld } from './store'

/** 与 bridge.mock 的 MOCK_PLAYER_NAME 逐字一致; 不一致会让"我的挂单"两边认不出同一个人。 */
const PLAYER_NAME = '测试员_Mock'
const PLAYER_UUID = '11111111-1111-4111-8111-111111111111'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 假定的默认分页大小; 上面"刚好装满/差一条"两个边界都是按它凑的。 */
const ASSUMED_PAGE_SIZE = 20

/**
 * 物品三元组 (id + 翻译键 + 中文名) 集中一处, 免得同一件物品在各面板叫不同名字。
 *
 * **全部 itemId 必须是仓库里真实存在的注册名**, 中文名必须与 lang/zh_cn.json 逐字一致。
 * 编一个"看着像那么回事"的 id 的代价: 贴图取不到 -> 界面上是一格棋盘格占位块, 而这个症状看起来
 * 与"第三方 mod 贴图缺口 (决策 J1)"一模一样, 会把一个纯 mock 数据错误伪装成一条真实的架构缺口。
 * (2026-08-13 就踩了这个: 一条编造的 gunsmith_barrel_heavy_match_grade 在设计评审里被当成了真物品。)
 */
interface SeedItem {
  itemId: string
  descriptionId: string
  displayName: string
  /** NBT 变体件专用, 见 ITEM_GAS_CORE。 */
  customModelData?: number
  /** NBT 变体件专用, 见 ITEM_GAS_CORE。 */
  nameParts?: ItemNamePart[]
}

function seedItem(itemId: string, descriptionId: string, displayName: string): SeedItem {
  return { itemId, descriptionId, displayName }
}

/**
 * NBT 变体件。itemId 与 descriptionId 都是 Item 级的 (195 种变体共用), 真正区分它们的是后两个字段。
 * displayName 只作 mock 内部对账用 (真桥上它由 nameParts 经 client.i18n 拼出来)。
 *
 * 返回类型把那两个字段收成**必填**, 而不是原样返回 SeedItem。
 * 理由是 exactOptionalPropertyTypes: 契约类型里它们是 `?: number` (要么键不存在, 要么是 number,
 * 不接受显式 undefined)。若这里返回的仍是可选版, 把 ITEM_GAS_CORE.customModelData 铺进挂单字面量时
 * 类型是 `number | undefined`, 直接被拒。收成必填是语义上更准的写法 —— 变体件按定义就一定有这两个值。
 */
type SeedVariantItem = SeedItem & Required<Pick<SeedItem, 'customModelData' | 'nameParts'>>

function seedVariant(
  itemId: string,
  descriptionId: string,
  displayName: string,
  customModelData: number,
  nameParts: ItemNamePart[],
): SeedVariantItem {
  return { itemId, descriptionId, displayName, customModelData, nameParts }
}

const ITEM_DIAMOND = seedItem('minecraft:diamond', 'item.minecraft.diamond', '钻石')
/**
 * 已核实为**编造 id**: 仓库里没有 miningdim:azurite 这件物品 —— 青辉石是纯账本货币
 * (Currency.AZURE), 无注册项无翻译键无贴图。留着是因为改它要先由后端决定"青辉石到底要不要有实体物品",
 * 前端不自行裁决。用到它的地方一律会落占位块, 见 nonTradable 处的说明。
 */
const ITEM_AZURITE = seedItem('miningdim:azurite', 'item.miningdim.azurite', '青辉石')
const ITEM_GOLD = seedItem('minecraft:gold_ingot', 'item.minecraft.gold_ingot', '金锭')
const ITEM_SCRAP = seedItem(
  'minecraft:netherite_scrap',
  'item.minecraft.netherite_scrap',
  '下界合金碎片',
)
const ITEM_IRON_ORE = seedItem('minecraft:iron_ore', 'block.minecraft.iron_ore', '铁矿石')
/**
 * 超长中文名边界: 45 字, 取自 lang/zh_cn.json 里全库最长的那条真实物品名 (防弹背心系列)。
 * 贴图 textures/item/plate_armor_banshee_atacs_au.png 真实存在, 故它在界面上是一张真图标而不是占位块。
 */
const ITEM_LONG_NAME = seedItem(
  'miningdim:plate_armor_banshee_atacs_au',
  'item.miningdim.plate_armor_banshee_atacs_au',
  'Shellback Tactical Banshee 防弹背心（A-Tacs AU 迷彩）',
)

/**
 * NBT 变体件样本: AR 平台的「格赫娜高速导气」传奇档。
 *
 * 它存在的意义不是多一件商品, 而是让**整条变体链路在假数据模式下也真的跑一遍** ——
 * 195 种枪匠零件共用 miningdim:gunsmith_part 这一个 itemId 与一个翻译键, 只有 customModelData
 * 与 nameParts 能把它们区分开。少了这个样本, 换皮预览里永远看不出变体件画错没有。
 *
 * customModelData 1000005 不是编的: 它等于 variant.index()*1_000_000 + platform*100 + part*10 + quality + 1,
 * 与构建期从模型 overrides 生成的 /mc/variants.json 里那条 gehenna_high_speed_gas_legendary 逐位相等。
 * 改它之前先看那张表。
 */
const ITEM_GAS_CORE = seedVariant(
  'miningdim:gunsmith_part',
  'item.miningdim.gunsmith_part',
  '格赫娜高速导气 传奇',
  1_000_005,
  [
    { k: 'gunsmith.variant.gehenna_high_speed_gas' },
    { t: ' ' },
    { k: 'gunsmith.quality.legendary' },
  ],
)
const ITEM_TACZ_GUN = seedItem('tacz:modern_kinetic_gun', 'item.tacz.modern_kinetic_gun', '现代动能枪械')
const ITEM_WHEAT = seedItem('minecraft:wheat', 'item.minecraft.wheat', '小麦')
const ITEM_CARROT = seedItem('minecraft:carrot', 'item.minecraft.carrot', '胡萝卜')
const ITEM_POTATO = seedItem('minecraft:potato', 'item.minecraft.potato', '马铃薯')
const ITEM_BEETROOT = seedItem('minecraft:beetroot', 'item.minecraft.beetroot', '甜菜根')
const ITEM_CHESTPLATE = seedItem(
  'minecraft:diamond_chestplate',
  'item.minecraft.diamond_chestplate',
  '钻石胸甲',
)
const ITEM_ARROW = seedItem('minecraft:arrow', 'item.minecraft.arrow', '箭')
const ITEM_BREAD = seedItem('minecraft:bread', 'item.minecraft.bread', '面包')

function statLine(
  key: string,
  label: string,
  value: number,
  unit: PlannedStatLine['unit'],
): PlannedStatLine {
  return { key, label, value, unit }
}

// ============================================================
// 职业进度 (8 条并列; 顺序与 JobId.values() 一致)
// ============================================================

/** [jobId, 中文名, 等级, 累计经验, 本级已获, 升级所需, 今日已获, 今日剩余额度]。 */
type JobSeedRow = readonly [PlannedJobId, string, number, number, number, number, number, number]

const JOB_ROWS: readonly JobSeedRow[] = [
  ['miner', '矿工', 6, 48_200, 3_200, 9_000, 1_450, 2_550],
  ['farmer', '农夫', 4, 12_400, 900, 4_800, 4_000, 0],
  // 玩家可见名是"铸甲师"; engineer 只是旧存档与旧命令的兼容 id (JobId.byId 里那条特判)。
  ['engineer', '铸甲师', 2, 2_150, 150, 1_600, 0, 4_000],
  ['tarot', '塔罗师', 5, 26_800, 5_400, 7_200, 620, 3_380],
  ['chef', '厨师', 3, 6_900, 400, 2_400, 240, 3_760],
  ['agent', '特勤干员', 1, 0, 0, 800, 0, 4_000],
  ['munitions', '军火商', 7, 91_500, 11_500, 14_000, 3_900, 100],
  // 满级形态: nextLevelXp 为 0, 前端据此判满级而不是硬编码 level === 10。
  ['brewer', '酿酒师', 10, 412_000, 0, 0, 0, 4_000],
]

function seedJobProgress(): PlannedJobProgressEntry[] {
  return JOB_ROWS.map((row) => {
    const [jobId, displayName, level, totalXp, levelXp, nextLevelXp, dailyXp, dailyRemaining] = row
    return { jobId, displayName, level, totalXp, levelXp, nextLevelXp, dailyXp, dailyRemaining }
  })
}

function jobLevels(
  miner: number,
  farmer: number,
  engineer: number,
  tarot: number,
  chef: number,
  agent: number,
  munitions: number,
  brewer: number,
): Record<PlannedJobId, number> {
  return { miner, farmer, engineer, tarot, chef, agent, munitions, brewer }
}

// ============================================================
// 精英怪图鉴 (唯一一块抄自 Java 真值的数据)
// ============================================================

/** [枚举名, 中文名, 池, 成本 c, 最低星, 是否技能, 互斥族, 5 档品质数值]。 */
type AffixSeedRow = readonly [
  string,
  string,
  PlannedAffixPool,
  number,
  number,
  boolean,
  string | null,
  readonly number[],
]

const AFFIX_ROWS: readonly AffixSeedRow[] = [
  ['COMPOSITE_ARMOR', '复合装甲', 'SURVIVAL', 8, 1, false, null, [0.35, 0.45, 0.55, 0.65, 0.75]],
  ['UHMWPE_ARMOR', '超高分子聚乙烯护甲层', 'SURVIVAL', 7, 1, false, null, [0.1, 0.15, 0.22, 0.3, 0.4]],
  // 高级+ 才有意义的词条, 前几档填 0 占位 —— 这是 Java 表里的原样, 前端渲染时要把 0 显示成"—"。
  ['HEAVY_ARMOR', '重型护甲', 'SURVIVAL', 26, 7, false, 'HEAVY_ARMOR', [0, 0, 0.35, 0.42, 0.49]],
  ['REGEN_TISSUE', '再生组织', 'SURVIVAL', 6, 1, false, null, [0.03, 0.04, 0.05, 0.06, 0.08]],
  ['FLAMMABLE_REGEN', '易燃再生', 'SURVIVAL', 10, 3, false, null, [8, 15, 30, 60, 90]],
  ['DEFLECTOR_SHIELD', '偏斜护盾', 'SURVIVAL', 10, 2, false, 'DEFLECTOR', [0.08, 0.12, 0.18, 0.25, 0.35]],
  ['FORTITUDE_SHIELD', '刚毅护盾', 'SURVIVAL', 22, 6, false, 'FORTITUDE', [0, 0, 120, 80, 50]],
  ['THORNS', '反震', 'SURVIVAL', 9, 2, false, null, [0.02, 0.035, 0.05, 0.07, 0.1]],
  ['GIGANTISM', '巨大化', 'SURVIVAL', 12, 3, false, 'SIZE', [0.3, 0.5, 0.8, 1.2, 1.8]],
  ['MINIATURIZATION', '缩小化', 'SURVIVAL', 10, 3, false, 'SIZE', [0.25, 0.32, 0.4, 0.48, 0.58]],
  ['BURNING', '燃烧', 'COMBAT', 8, 1, false, null, [0.01, 0.015, 0.02, 0.03, 0.04]],
  ['ARMOR_PIERCING', '穿甲', 'COMBAT', 10, 2, false, null, [0.04, 0.06, 0.09, 0.13, 0.18]],
  ['REND', '撕裂', 'COMBAT', 12, 3, false, null, [0.05, 0.08, 0.12, 0.16, 0.2]],
  ['HEAVY_CANNON', '重炮', 'COMBAT', 10, 2, false, null, [0.3, 0.475, 0.65, 0.825, 1]],
  ['CORROSIVE', '强酸', 'COMBAT', 8, 3, false, null, [2, 4, 6, 10, 15]],
  ['DOUBLE_STRIKE', '双倍打击', 'COMBAT', 9, 3, false, 'MULTI_STRIKE', [2, 2, 2, 2, 2]],
  ['QUADRUPLE_STRIKE', '四倍痛处', 'COMBAT', 16, 5, false, 'MULTI_STRIKE', [4, 4, 4, 4, 4]],
  ['BLOODLUST', '嗜血', 'COMBAT', 10, 2, false, null, [0.15, 0.25, 0.35, 0.5, 0.6]],
  ['CHAOS_STRIKE', '混沌重击', 'COMBAT', 11, 4, false, null, [1, 1, 1, 1, 1]],
  ['FROST', '寒霜', 'COMBAT', 10, 2, false, null, [0.008, 0.012, 0.018, 0.025, 0.035]],
  ['SPRINT', '高速移动', 'MOBILITY', 6, 1, false, 'MOVE_SPEED', [0.1, 0.15, 0.22, 0.3, 0.4]],
  ['OVERDRIVE', '超速移动', 'MOBILITY', 10, 3, false, 'MOVE_SPEED', [1, 1.3, 1.6, 2, 2.5]],
  ['BLINK', '闪光', 'MOBILITY', 8, 2, false, 'TELEPORT_FAMILY', [9, 8, 7, 5.5, 4]],
  ['TACTICAL_BLINK', '战术传送', 'MOBILITY', 8, 2, false, 'TELEPORT_FAMILY', [8, 7, 6, 5, 4]],
  ['PHASE_WALK', '灵体移动', 'MOBILITY', 12, 4, false, 'TELEPORT_FAMILY', [2, 2.5, 3, 3.5, 4]],
  ['ELECTRO_CHARGE', '电磁蓄力', 'SKILL', 14, 4, true, null, [0.18, 0.26, 0.36, 0.46, 0.55]],
  ['THUNDER', '天雷', 'SKILL', 18, 5, true, null, [0.12, 0.17, 0.22, 0.27, 0.32]],
  ['LITTLE_BOY', '小男孩', 'SKILL', 28, 7, true, null, [0, 0, 0, 0.7, 0.85]],
  ['DEATH_MARK', '命定之死', 'SKILL', 30, 8, true, 'DEATH_MARK', [0, 0, 0, 1.6, 1.6]],
  ['VISUAL_DISRUPTION', '视觉干扰', 'SKILL', 12, 4, true, null, [3, 3, 3, 3, 3]],
  ['SELF_REPAIR', '自我修复单元', 'SKILL', 14, 4, true, null, [40, 0, 80, 150, 300]],
  ['COUNTER_UNIT', '反击单元', 'SKILL', 12, 3, true, 'DEATH_MARK', [0.4, 0.55, 0.7, 0.85, 1]],
  ['CAESAR_SWAP', '凯撒实验型转换器', 'SKILL', 14, 5, true, 'TELEPORT_FAMILY', [20, 17, 14, 12, 10]],
  ['BLADE_WALTZ', '利刃华尔兹', 'SKILL', 16, 5, true, 'TELEPORT_FAMILY', [3, 4, 5, 6, 7]],
  ['SUMMON_SUPPORT', '支援', 'SKILL', 16, 4, true, null, [1, 2, 2, 3, 3]],
]

function seedAffixes(): PlannedChampionAffix[] {
  return AFFIX_ROWS.map((row) => {
    const [affixId, displayName, pool, cost, minStar, isSkill, mutexFamily, tiers] = row
    return { affixId, displayName, pool, cost, minStar, isSkill, mutexFamily, tiers: [...tiers] }
  })
}

/** [星, 生存池, 战斗池, 机动池, 技能池, 总词条上限, 技能数上限, 最高品质, 基础有效HP, 基础单击%]。 */
type StarSeedRow = readonly [number, number, number, number, number, number, number, string, number, number]

const STAR_ROWS: readonly StarSeedRow[] = [
  [1, 10, 8, 0, 0, 1, 0, 'COMMON', 135, 0.04],
  [2, 20, 14, 4, 0, 2, 0, 'COMMON', 225, 0.05],
  [3, 35, 24, 8, 15, 3, 1, 'UNCOMMON', 360, 0.06],
  [4, 55, 36, 12, 25, 4, 1, 'UNCOMMON', 540, 0.08],
  [5, 80, 55, 20, 45, 5, 1, 'RARE', 765, 0.1],
  // 6 星起基础有效 HP 破原版 generic.max_health 1024 上限, 故走自定义血池 (spec 6.2)。
  [6, 120, 80, 30, 70, 6, 2, 'RARE', 2_700, 0.12],
  [7, 165, 110, 45, 110, 7, 2, 'EPIC', 6_000, 0.14],
  [8, 240, 160, 75, 180, 9, 3, 'EPIC', 27_000, 0.16],
  [9, 330, 230, 115, 260, 11, 3, 'LEGENDARY', 45_000, 0.18],
  [10, 440, 310, 155, 360, 13, 4, 'LEGENDARY', 73_000, 0.2],
]

function seedStars(): PlannedChampionStar[] {
  return STAR_ROWS.map((row) => {
    const [
      star,
      survivalBudget,
      combatBudget,
      mobilityBudget,
      skillBudget,
      affixCap,
      skillCap,
      maxQuality,
      baseEffectiveHp,
      baseHitPct,
    ] = row
    return {
      star,
      survivalBudget,
      combatBudget,
      mobilityBudget,
      skillBudget,
      affixCap,
      skillCap,
      maxQuality,
      baseEffectiveHp,
      baseHitPct,
    }
  })
}

// ============================================================
// 成交流水 (39 条 = 按 pageSize 20 分两页, 第 2 页差一条满)
// ============================================================

const TXN_ITEMS: readonly SeedItem[] = [
  ITEM_DIAMOND,
  ITEM_GOLD,
  ITEM_IRON_ORE,
  ITEM_WHEAT,
  ITEM_TACZ_GUN,
  ITEM_LONG_NAME,
]

const TXN_COUNTERPARTIES: readonly string[] = ['矿工阿建', '拍卖狂魔', '鲸鱼玩家', '甜品师小狐']

function seedTransactions(epoch: number): PlannedMarketTransaction[] {
  const total = ASSUMED_PAGE_SIZE * 2 - 1
  const transactions: PlannedMarketTransaction[] = []
  for (let index = 0; index < total; index += 1) {
    const item = TXN_ITEMS[index % TXN_ITEMS.length]
    if (item === undefined) {
      throw new Error('mock 种子缺陷: 流水物品表为空')
    }
    const role: PlannedTxnRole = index % 3 === 0 ? 'seller' : 'buyer'
    const count = 1 + (index % 7)
    const unitPrice = 40 + index * 37
    const counterparty = TXN_COUNTERPARTIES[index % TXN_COUNTERPARTIES.length]
    transactions.push({
      txnId: 9_000 + index,
      role,
      itemId: item.itemId,
      descriptionId: item.descriptionId,
      count,
      unitPrice,
      total: unitPrice * count,
      // 卖方侧才承担挂单手续费; 买方侧当前恒 0 (与真契约 market.buy 回执的 fee 恒 0 同口径)。
      fee: role === 'seller' ? Math.round(unitPrice * count * 0.04) : 0,
      counterpartyName: counterparty === undefined ? null : counterparty,
      at: epoch - index * 37 * MINUTE,
    })
  }
  // 极大数值边界: 2^53-1, 撞 Java long -> JSON number 的精度上界与金额列的宽度上界。
  transactions.push({
    txnId: 9_999,
    role: 'seller',
    itemId: ITEM_SCRAP.itemId,
    descriptionId: ITEM_SCRAP.descriptionId,
    count: 1,
    unitPrice: Number.MAX_SAFE_INTEGER,
    total: Number.MAX_SAFE_INTEGER,
    fee: 0,
    // 对手方缺席: 系统回收/挂单过期退回这类没有对家的流水, 前端不得当成"名字没加载出来"。
    counterpartyName: null,
    at: epoch - 3 * DAY,
  })
  transactions.sort((left, right) => right.at - left.at)
  return transactions.slice(0, total)
}

// ============================================================
// 系统商店 (20 条 = 按 pageSize 20 恰好装满一页, 第 2 页为空)
// ============================================================

const SHOP_ITEMS: readonly SeedItem[] = [
  ITEM_BREAD,
  ITEM_ARROW,
  ITEM_WHEAT,
  ITEM_CARROT,
  ITEM_POTATO,
  ITEM_DIAMOND,
  ITEM_GOLD,
  ITEM_IRON_ORE,
  ITEM_CHESTPLATE,
  ITEM_BEETROOT,
]

function seedShops(): PlannedShopEntry[] {
  const shops: PlannedShopEntry[] = []
  for (let index = 0; index < ASSUMED_PAGE_SIZE; index += 1) {
    const item = SHOP_ITEMS[index % SHOP_ITEMS.length]
    if (item === undefined) {
      throw new Error('mock 种子缺陷: 商店物品表为空')
    }
    const buyPrice = 12 + index * 9
    shops.push({
      shopId: `shop_${String(index).padStart(2, '0')}`,
      // 逐维度隔离是真服 AdminShopRegistry 的既有形态, 故种子里必须有跨维度的店。
      dimension: index % 5 === 0 ? 'miningdim:mining' : 'minecraft:overworld',
      pos: { x: 120 + index * 3, y: 64, z: -40 - index },
      itemId: item.itemId,
      descriptionId: item.descriptionId,
      buyPrice,
      // 每 4 家里有 1 家只收不卖: 前端两列都要能显示"—"。
      sellPrice: index % 4 === 3 ? null : Math.round(buyPrice * 0.6),
      // 系统店无限库存记为 null, 与"库存为 0"是两回事。
      stock: index % 3 === 0 ? null : 64 * (index % 5),
    })
  }
  return shops
}

// ============================================================
// 塔罗牌组 (22 张大阿卡纳; 名字是通用译名, 品质分档为演示值)
// ============================================================

const TAROT_NAMES: readonly string[] = [
  '愚者',
  '魔术师',
  '女祭司',
  '女皇',
  '皇帝',
  '教皇',
  '恋人',
  '战车',
  '力量',
  '隐者',
  '命运之轮',
  '正义',
  '倒吊人',
  '死神',
  '节制',
  '恶魔',
  '高塔',
  '星星',
  '月亮',
  '太阳',
  '审判',
  '世界',
]

function seedTarotDeck(epoch: number): PlannedTarotCard[] {
  return TAROT_NAMES.map((displayName, index) => {
    const quality =
      index >= 20 ? 'legendary' : index >= 16 ? 'epic' : index >= 10 ? 'rare' : index >= 4 ? 'uncommon' : 'common'
    return {
      cardId: `tarot_${String(index).padStart(2, '0')}`,
      displayName,
      // 后 6 张未持有: 卡池面板需要同时存在"已持有"与"未持有"两种格位形态。
      owned: index >= 16 ? 0 : 1 + (index % 3),
      quality,
      // 只有一张在冷却中; 冷却是只读 peek, 面板绝不能调 tryUse (那是校验并占用的写方法)。
      cooldownUntil: index === 5 ? epoch + 42_000 : 0,
      equipped: index < 3,
    }
  })
}

// ============================================================
// 其他玩家
// ============================================================

function seedOtherPlayers(): MockOtherPlayer[] {
  return [
    {
      name: '矿工阿建',
      uuid: '22222222-2222-4222-8222-222222222222',
      online: true,
      wallet: { credit: 24_800, azure: 12 },
      jobLevels: jobLevels(8, 2, 1, 1, 3, 1, 2, 1),
    },
    {
      // 零余额边界: 双币均为 0。负余额服务端不可能产生, 故不造。
      name: '拍卖狂魔',
      uuid: '33333333-3333-4333-8333-333333333333',
      online: false,
      wallet: { credit: 0, azure: 0 },
      jobLevels: jobLevels(1, 1, 1, 1, 1, 1, 1, 1),
    },
    {
      // 极大数值边界: 余额 2^53-1, 撞 OP 调账面板的输入框与金额列。
      name: '鲸鱼玩家',
      uuid: '44444444-4444-4444-8444-444444444444',
      online: true,
      wallet: { credit: Number.MAX_SAFE_INTEGER, azure: 30 },
      jobLevels: jobLevels(10, 10, 10, 10, 10, 10, 10, 10),
    },
    {
      name: '甜品师小狐',
      uuid: '55555555-5555-4555-8555-555555555555',
      online: true,
      wallet: { credit: 6_400, azure: 3 },
      jobLevels: jobLevels(2, 5, 1, 3, 9, 1, 1, 4),
    },
  ]
}

// ============================================================
// 挂单镜像的种子 (与 bridge.mock 的初始挂单同 id, 便于对照; 真实数据仍以 call 回执为准)
// ============================================================

function seedMyListings(epoch: number): MarketListing[] {
  return [
    {
      id: 1002,
      sellerName: PLAYER_NAME,
      itemId: ITEM_LONG_NAME.itemId,
      descriptionId: ITEM_LONG_NAME.descriptionId,
      count: 1,
      unitPrice: 88_000,
      total: 88_000,
      createdAt: epoch - 140 * MINUTE,
    },
    {
      id: 1005,
      sellerName: PLAYER_NAME,
      itemId: ITEM_TACZ_GUN.itemId,
      descriptionId: ITEM_TACZ_GUN.descriptionId,
      count: 2,
      unitPrice: 12_500,
      total: 25_000,
      createdAt: epoch - 900 * MINUTE,
    },
    {
      // NBT 变体件: itemId 与 descriptionId 都是 Item 级的, 名字与图标全靠后两个字段区分。
      // 少了这一条, 换皮预览里看不出变体件画对没有 (它是本页唯一走这条链路的数据)。
      id: 1007,
      sellerName: PLAYER_NAME,
      itemId: ITEM_GAS_CORE.itemId,
      descriptionId: ITEM_GAS_CORE.descriptionId,
      customModelData: ITEM_GAS_CORE.customModelData,
      nameParts: ITEM_GAS_CORE.nameParts,
      count: 1,
      unitPrice: 34_800,
      total: 34_800,
      createdAt: epoch - 20 * MINUTE,
    },
  ]
}

function seedSharedInvItems(): PlayerInventoryItem[] {
  return [
    { slot: 0, itemId: ITEM_DIAMOND.itemId, descriptionId: ITEM_DIAMOND.descriptionId, count: 9 },
    { slot: 1, itemId: ITEM_BREAD.itemId, descriptionId: ITEM_BREAD.descriptionId, count: 32 },
    {
      // 唯一带 displayName 的格位 (铁砧改名); 其余格位该键整体缺席, 不是空串。
      slot: 5,
      itemId: ITEM_CHESTPLATE.itemId,
      descriptionId: ITEM_CHESTPLATE.descriptionId,
      count: 1,
      displayName: '「同心」纪念胸甲',
    },
  ]
}

function seedHubPanels(): PlannedHubPanel[] {
  /*
   * route 必须与 src/router.ts 的路由表一致, 且以 router.ts 为准 (它由 hub 负责维护)。
   * 这里的字符串只是"面板注册表在服务端会长什么样"的假定形状, 不是路由真源 —— 真接线后这份
   * 注册表由服务端下发, 前端拿到的 route 仍要能在 router.ts 里找到对应页面, 对不上就是接线出错。
   */
  return [
    { panelId: 'home', label: '个人档案', route: '/', iconItemId: 'minecraft:book', enabled: true, lockReason: null },
    {
      panelId: 'market',
      label: '跳蚤市场',
      route: '/market',
      iconItemId: 'minecraft:emerald',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'shop',
      label: '系统商店',
      route: '/shop',
      iconItemId: 'minecraft:chest',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'jobs',
      label: '职业',
      route: '/jobs',
      iconItemId: 'minecraft:iron_pickaxe',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'mining',
      label: '矿洞',
      route: '/mining',
      iconItemId: 'minecraft:deepslate',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'champion',
      label: '精英怪图鉴',
      route: '/champion',
      iconItemId: 'minecraft:wither_skeleton_skull',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'marriage',
      label: '婚姻',
      route: '/marriage',
      iconItemId: 'minecraft:golden_apple',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'settings',
      label: '设置',
      route: '/settings',
      iconItemId: 'minecraft:comparator',
      enabled: true,
      lockReason: null,
    },
    {
      // 锁态样本: 面板注册表要能表达"看得见但进不去", 否则前端做不出灰态。
      panelId: 'admin',
      label: '管理后台',
      route: '/admin',
      iconItemId: 'minecraft:command_block',
      enabled: true,
      lockReason: null,
    },
    {
      panelId: 'quests',
      label: '任务',
      route: '/quests',
      iconItemId: 'minecraft:written_book',
      enabled: false,
      lockReason: '任务系统尚未实现 (经济文档 faucet 之首, 全库零实现)',
    },
  ]
}

/**
 * 造一份全新的世界。每次调用都返回互不共享引用的新对象 —— resetWorld 依赖这一点,
 * 若这里返回了模块级常量的引用, 重置之后玩家上一轮的改动会跟着一起回来。
 */
export function createInitialWorld(): MockWorld {
  const epoch = Date.now()
  return {
    revision: 0,
    epoch,
    player: { name: PLAYER_NAME, uuid: PLAYER_UUID, isOp: true },
    mirror: {
      // 全 null: 本会话还没拉过真域数据。面板据此显示骨架, 而不是显示一个假的 0。
      wallet: null,
      inventory: null,
      myListings: seedMyListings(epoch),
      caseOwnedTotal: null,
      refreshedAt: 0,
    },
    walletOverlay: { credit: 0, azure: 0 },
    prefs: { uiScale: 2, muteToasts: false, layout: 'comfortable' },
    server: {
      online: 17,
      maxPlayers: 60,
      tps: 19.8,
      mspt: 32.4,
      uptimeSeconds: 3 * 24 * 3600 + 4 * 3600 + 12 * 60,
      announcement: '本周末 20:00 精英怪讨伐活动, 详情见群公告。',
    },
    hubPanels: seedHubPanels(),
    market: {
      transactions: seedTransactions(epoch),
      pendingPayout: {
        credit: 4_820,
        // 空列表边界: 有货款待收但没有待退物品, 两段各自可空。
        items: [],
      },
      p2pUsedToday: 380,
      p2pCapPerDay: 512,
      /*
       * 不可交易清单。两条各带一个**已核实的真实缺口**, 别当成随手写的示例:
       *
       * 1. 青辉石 (ITEM_AZURITE) 在仓库里**根本不是物品** —— 它是纯账本货币
       *    (com.miningdim.economy.Currency.AZURE, "点券式高级货币, 仅 >=6 星精英怪掉落入账"),
       *    没有注册项、没有翻译键、没有贴图。于是 BOUND_CURRENCY 这条规则永远不可能命中一件真物品。
       *    接线时要么后端确实给青辉石加一个实体物品, 要么这条 reasonCode 整个作废 —— 现在这样
       *    既画不出图标 (落占位块), 也在语义上站不住。**待后端裁决, 前端不自行改口径。**
       *
       * 2. 塔罗牌的真实注册名是 miningdim:tarot_card 一个 id (220 种牌面靠 NBT 区分),
       *    此前这里写的 tarot_card_fool 是编造的。已改成真 id。它现在仍会落占位块, 但那是
       *    **如实反映**: 塔罗牌走的是自定义 ItemProperties 谓词而不是 CustomModelData,
       *    本次的变体贴图映射表覆盖不到它 (理由见 vite.config.ts 的 buildVariantMap)。
       */
      nonTradable: [
        { itemId: ITEM_AZURITE.itemId, reasonCode: 'BOUND_CURRENCY', reason: '青辉石绑定账号, 不可交易' },
        { itemId: 'miningdim:tarot_card', reasonCode: 'TAROT_BANNED', reason: '塔罗牌禁止交易' },
      ],
    },
    jobs: {
      progress: seedJobProgress(),
      miner: {
        level: 6,
        charge: 7,
        chargeMax: 12,
        chainEnabled: true,
        // 初始就绪 (0 = 无冷却): 冷却态由玩家第一次探测后自然产生, 不必在种子里先堵住入口 ——
        // 一进面板按钮就是灰的, 会让人以为功能没做。
        scanReadyAt: 0,
        scanRadius: 24,
        scanUnlockLevel: 3,
        passives: [
          statLine('mining_speed', '挖掘速度', 0.18, 'percent'),
          statLine('ore_yield', '矿物额外产出', 0.12, 'percent'),
          statLine('vein_resist', '矿脉抗性', 0.09, 'percent'),
          statLine('chain_range', '连锁半径', 5, 'blocks'),
        ],
        dailyOres: [
          {
            itemId: ITEM_IRON_ORE.itemId,
            descriptionId: ITEM_IRON_ORE.descriptionId,
            minedToday: 412,
            softCap: 600,
            decayFactor: 1,
          },
          {
            // 已撞软上限: 单价按 0.6 打折, 正是 D3 里"玩家最想看的那个数"。
            itemId: ITEM_DIAMOND.itemId,
            descriptionId: ITEM_DIAMOND.descriptionId,
            minedToday: 64,
            softCap: 48,
            decayFactor: 0.6,
          },
          {
            itemId: ITEM_GOLD.itemId,
            descriptionId: ITEM_GOLD.descriptionId,
            minedToday: 0,
            softCap: 200,
            decayFactor: 1,
          },
        ],
      },
      farmer: {
        level: 4,
        sellUnlockLevel: 2,
        soldToday: 128,
        dailySoldCap: 400,
        crops: [
          {
            itemId: ITEM_WHEAT.itemId,
            descriptionId: ITEM_WHEAT.descriptionId,
            unitPrice: 6,
            basePrice: 6,
            soldToday: 64,
          },
          {
            itemId: ITEM_CARROT.itemId,
            descriptionId: ITEM_CARROT.descriptionId,
            unitPrice: 5,
            basePrice: 7,
            soldToday: 48,
          },
          {
            itemId: ITEM_POTATO.itemId,
            descriptionId: ITEM_POTATO.descriptionId,
            unitPrice: 7,
            basePrice: 7,
            soldToday: 16,
          },
          {
            itemId: ITEM_BEETROOT.itemId,
            descriptionId: ITEM_BEETROOT.descriptionId,
            unitPrice: 9,
            basePrice: 9,
            soldToday: 0,
          },
        ],
        farmlandTiers: [
          statLine('tier1', '一档 生土', 0, 'percent'),
          statLine('tier2', '二档 熟土', 0.08, 'percent'),
          statLine('tier3', '三档 沃土', 0.16, 'percent'),
          statLine('tier4', '四档 膏壤', 0.26, 'percent'),
          statLine('tier5', '五档 灵田', 0.4, 'percent'),
        ],
      },
      chef: {
        level: 3,
        qualityCap: 3,
        effects: [
          statLine('quality1', '一品 饱食恢复', 4, 'flat'),
          statLine('quality2', '二品 额外心数', 2, 'flat'),
          statLine('quality3', '三品 减伤', 0.05, 'percent'),
          statLine('quality4', '四品 减伤', 0.09, 'percent'),
          statLine('quality5', '五品 减伤', 0.14, 'percent'),
        ],
        seasoningCostCredit: 120,
      },
      brewer: {
        level: 10,
        brews: [
          { brewId: 'vodka', displayName: '伏特加', permanentStacks: 5, maxStacks: 9, moonshineAffixes: ['烈酒钝感'] },
          { brewId: 'gin', displayName: '金酒', permanentStacks: 3, maxStacks: 9, moonshineAffixes: [] },
          // 零值边界: 一瓶都没喝过的酒。
          { brewId: 'rum', displayName: '朗姆', permanentStacks: 0, maxStacks: 9, moonshineAffixes: [] },
          {
            brewId: 'whisky',
            displayName: '威士忌',
            permanentStacks: 9,
            maxStacks: 9,
            moonshineAffixes: ['橡木回甘', '陈年余韵'],
          },
        ],
        recipes: [
          {
            recipeId: 'vodka_base',
            displayName: '伏特加 基酒',
            inputs: [
              { itemId: ITEM_POTATO.itemId, descriptionId: ITEM_POTATO.descriptionId, count: 12 },
              { itemId: ITEM_WHEAT.itemId, descriptionId: ITEM_WHEAT.descriptionId, count: 6 },
            ],
            agingDays: 7,
          },
          {
            recipeId: 'whisky_base',
            displayName: '威士忌 基酒',
            inputs: [{ itemId: ITEM_WHEAT.itemId, descriptionId: ITEM_WHEAT.descriptionId, count: 24 }],
            agingDays: 21,
          },
        ],
      },
      tarot: {
        level: 5,
        fragments: 240,
        deck: seedTarotDeck(epoch),
        packPriceCredit: 1_800,
        packsBoughtToday: 1,
        packDailyLimit: 5,
      },
      agent: {
        level: 1,
        scanReadyAt: 0,
        scanRadius: 32,
        // 空列表边界: 还没扫描过, 面板必须有"未扫描"这一态而不是空表格。
        seals: [],
        bounties: [
          {
            bountyId: 'bounty_ore',
            title: '清点深层矿脉',
            targetType: 'ORE_SURVEY',
            progress: 3,
            goal: 10,
            rewardCredit: 2_400,
            expiresAt: epoch + 2 * DAY,
            claimable: false,
          },
          {
            bountyId: 'bounty_champion',
            title: '讨伐 5 星以上精英怪',
            targetType: 'CHAMPION_KILL',
            // 零进度边界。
            progress: 0,
            goal: 3,
            rewardCredit: 6_000,
            expiresAt: epoch + 5 * DAY,
            claimable: false,
          },
          {
            bountyId: 'bounty_deliver',
            title: '向前哨交付面包',
            targetType: 'DELIVER',
            progress: 64,
            goal: 64,
            rewardCredit: 900,
            expiresAt: epoch + 8 * HOUR,
            // 已达成待领取: 领取按钮的可用态需要有样本。
            claimable: true,
          },
        ],
      },
      munitions: {
        level: 7,
        stations: [
          {
            stationId: 'workbench',
            displayName: '军械台',
            pos: { x: 214, y: 71, z: -88 },
            progress: 42,
            maxProgress: 100,
            running: true,
            outputItemId: ITEM_LONG_NAME.itemId,
          },
          {
            stationId: 'press',
            displayName: '冲压机',
            pos: { x: 218, y: 71, z: -88 },
            progress: 0,
            maxProgress: 100,
            running: false,
            outputItemId: null,
          },
          {
            // 未放置形态: pos 为 null, 面板要能显示"尚未建造"。
            stationId: 'assembler',
            displayName: '装配台',
            pos: null,
            progress: 0,
            maxProgress: 100,
            running: false,
            outputItemId: null,
          },
        ],
      },
      engineer: {
        level: 2,
        tiers: [
          statLine('nano_t1', '纳米板 一档', 2, 'flat'),
          statLine('nano_t2', '纳米板 二档', 4, 'flat'),
          statLine('nano_t3', '纳米板 三档', 7, 'flat'),
          statLine('nano_t4', '纳米板 四档', 11, 'flat'),
          statLine('nano_t5', '纳米板 五档', 16, 'flat'),
          statLine('nano_t6', '纳米板 六档', 22, 'flat'),
        ],
        armorEffects: [
          {
            effectId: 'kinetic_absorb',
            displayName: '动能吸收',
            description: '受子弹伤害时按层数削减一部分冲量, 与其它减伤乘法叠加。',
            unlocked: true,
          },
          {
            effectId: 'thermal_vent',
            displayName: '热管疏导',
            description: '脱战后每秒回复少量护甲耐久。',
            unlocked: false,
          },
        ],
      },
      blueprints: {
        blueprints: [
          {
            blueprintId: 'bp_m4a1',
            displayName: 'M4A1 图纸',
            gunId: 'tacz:m4a1',
            requiredParts: [
              { itemId: ITEM_LONG_NAME.itemId, descriptionId: ITEM_LONG_NAME.descriptionId, count: 1 },
              { itemId: ITEM_GOLD.itemId, descriptionId: ITEM_GOLD.descriptionId, count: 4 },
            ],
          },
          {
            blueprintId: 'bp_ak47',
            displayName: 'AK47 图纸',
            gunId: 'tacz:ak47',
            requiredParts: [
              { itemId: ITEM_SCRAP.itemId, descriptionId: ITEM_SCRAP.descriptionId, count: 2 },
              { itemId: ITEM_IRON_ORE.itemId, descriptionId: ITEM_IRON_ORE.descriptionId, count: 12 },
            ],
          },
        ],
      },
    },
    economy: {
      status: { afkFrozen: false, idleSeconds: 42, freezeThresholdSeconds: 300 },
      today: {
        faucets: [
          { faucetKey: 'mining', label: '挖矿收购', earnedToday: 8_240, softCap: 12_000, decayFactor: 1 },
          // 已过软上限: 衰减到 0.6, 这一档正是玩家最关心的展示点 (D3)。
          { faucetKey: 'farming', label: '农作物收购', earnedToday: 14_800, softCap: 12_000, decayFactor: 0.6 },
          { faucetKey: 'champion', label: '精英怪分赃', earnedToday: 0, softCap: 20_000, decayFactor: 1 },
          { faucetKey: 'bounty', label: '悬赏奖励', earnedToday: 900, softCap: 6_000, decayFactor: 1 },
        ],
        sinks: [
          { sinkKey: 'market_fee', label: '市场手续费', spentToday: 3_520 },
          { sinkKey: 'tarot_pack', label: '塔罗卡包', spentToday: 1_800 },
          { sinkKey: 'case_open', label: '开箱', spentToday: 5_000 },
          { sinkKey: 'seasoning', label: '调味台', spentToday: 240 },
        ],
        totalCreditIn: 23_940,
        totalCreditOut: 10_560,
        azureIn: 18,
        azureDailyCap: 30,
        resetsAt: epoch + 6 * HOUR,
      },
      priceTable: {
        anchors: [
          {
            itemId: ITEM_DIAMOND.itemId,
            descriptionId: ITEM_DIAMOND.descriptionId,
            anchorPrice: 500,
            todayPrice: 300,
            minedToday: 1_840,
          },
          {
            itemId: ITEM_GOLD.itemId,
            descriptionId: ITEM_GOLD.descriptionId,
            anchorPrice: 120,
            todayPrice: 120,
            minedToday: 640,
          },
          {
            itemId: ITEM_SCRAP.itemId,
            descriptionId: ITEM_SCRAP.descriptionId,
            anchorPrice: 3_000,
            todayPrice: 2_400,
            minedToday: 22,
          },
          {
            itemId: ITEM_WHEAT.itemId,
            descriptionId: ITEM_WHEAT.descriptionId,
            anchorPrice: 6,
            todayPrice: 4,
            minedToday: 9_600,
          },
        ],
      },
    },
    marriage: {
      status: 'single',
      spouseName: null,
      spouseUuid: null,
      spouseOnline: false,
      weddedAt: null,
      marriageDays: 0,
      divorceCount: 1,
      remarryCooldownUntil: 0,
      sharedInvLevel: 1,
      sharedInvSlots: 9,
      ringOwned: false,
      ringPriceCredit: 12_000,
      milestones: [
        { milestoneId: 'first_week', label: '相伴七日', achievedAt: null },
        { milestoneId: 'first_month', label: '相伴一月', achievedAt: null },
        { milestoneId: 'shared_inv_2', label: '共享背包二级', achievedAt: null },
      ],
      // 两条待处理求婚: 收件方向的列表在真服是 E3 缺的那个反查索引, 前端必须能展示与应答。
      incomingProposals: [
        {
          proposalId: 'prop_1',
          playerName: '甜品师小狐',
          playerUuid: '55555555-5555-4555-8555-555555555555',
          createdAt: epoch - 20 * MINUTE,
          expiresAt: epoch + 40 * MINUTE,
        },
        {
          proposalId: 'prop_2',
          playerName: '矿工阿建',
          playerUuid: '22222222-2222-4222-8222-222222222222',
          createdAt: epoch - 3 * HOUR,
          expiresAt: epoch + 5 * MINUTE,
        },
      ],
      outgoingProposal: null,
    },
    sharedInv: { slots: 9, items: seedSharedInvItems() },
    mining: {
      instances: [
        {
          difficulty: 'easy',
          displayName: '浅层矿区',
          requiredMinerLevel: 1,
          playersInside: 6,
          danger: 0.12,
          lastResetAt: epoch - 5 * HOUR,
          nextResetAt: epoch + 7 * HOUR,
        },
        {
          difficulty: 'medium',
          displayName: '中层矿区',
          // 代码权威是 L4 (GateResult 头注释里的 MEDIUM=10 是过期文档口径)。
          requiredMinerLevel: 4,
          playersInside: 3,
          danger: 0.48,
          lastResetAt: epoch - 11 * HOUR,
          nextResetAt: epoch + 1 * HOUR,
        },
        {
          difficulty: 'hard',
          displayName: '深层矿区',
          requiredMinerLevel: 8,
          playersInside: 0,
          danger: 0.86,
          lastResetAt: epoch - 2 * HOUR,
          nextResetAt: epoch + 10 * HOUR,
        },
      ],
      myStatus: {
        inside: false,
        difficulty: null,
        regionX: 0,
        regionZ: 0,
        danger: 0,
        spawnFreezeUntil: 0,
        minerLevel: 6,
      },
    },
    champion: {
      codex: {
        affixes: seedAffixes(),
        stars: seedStars(),
        distribution: [
          {
            difficulty: 'easy',
            starWeights: [
              { star: 1, weight: 70 },
              { star: 2, weight: 25 },
              { star: 3, weight: 5 },
            ],
          },
          {
            difficulty: 'medium',
            starWeights: [
              { star: 3, weight: 45 },
              { star: 4, weight: 35 },
              { star: 5, weight: 15 },
              { star: 6, weight: 5 },
            ],
          },
          {
            difficulty: 'hard',
            starWeights: [
              { star: 6, weight: 40 },
              { star: 7, weight: 30 },
              { star: 8, weight: 20 },
              { star: 9, weight: 8 },
              { star: 10, weight: 2 },
            ],
          },
        ],
      },
      samples: [
        {
          entityId: 4_201,
          entityType: 'minecraft:zombie',
          displayName: '复合装甲 僵尸',
          star: 4,
          affixIds: ['COMPOSITE_ARMOR', 'BURNING', 'SPRINT'],
          health: 380,
          maxHealth: 540,
          customBloodPool: false,
        },
        {
          entityId: 4_202,
          entityType: 'minecraft:wither_skeleton',
          displayName: '命定 凋灵骷髅',
          star: 9,
          affixIds: ['HEAVY_ARMOR', 'DEATH_MARK', 'THUNDER', 'PHASE_WALK', 'REND'],
          health: 41_500,
          maxHealth: 45_000,
          // 6 星及以上走自定义血池, 血量不再受原版 generic.max_health 1024 上限约束。
          customBloodPool: true,
        },
      ],
    },
    shops: seedShops(),
    otherPlayers: seedOtherPlayers(),
  }
}
