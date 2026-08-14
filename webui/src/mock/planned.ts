/**
 * 前端假定契约 (PLANNED) —— 本文件所有类型都是前端现在发明的形状, 不是服务端契约。
 * 接线时必须以 Java 实现为准重写, 不允许反过来让 Java 迁就本文件。
 *
 * 为什么要单独一个文件 (而不是往 ../lib/types.ts 里塞):
 * lib/types.ts 里那 24 个 action 是逐字读 Java 源码抄下来的真契约, 每个字段都标了落点; 本文件里的东西
 * 一行 Java 都不对应。两者一旦混在一处, 半年后没人分得清哪个字段是"服务端真的会返回"、哪个是"当初编的"。
 * 故物理隔离三件套: 独立文件 + 全部类型名带 Planned 前缀 + 每个 action 标注接线清单行号。
 *
 * 接线核销流程 (逐条):
 *   1. 每个 action 上方注明它对应 docs/WebUI_Frontend_Wiring_Checklist.md 第三章接线总表的哪一行;
 *   2. 后端把该行落地后, 按 Java 实现重写这里的 payload/result;
 *   3. 重写完成的 action 从 PLANNED_ACTIONS 与 PlannedContractMap 移除, 类型搬进 lib/types.ts,
 *      handlers.ts 里对应的实现改为转调 lib/bridge 的 call()。三步做完才算这一行核销掉。
 *
 * 三条全局约定 (免得每个类型重复解释):
 *   - displayName: 真服大概率只回翻译键 (descriptionId) 交给 client.i18n 解, 但 mock 阶段那些键没有 lang
 *     条目, 面板会显示成一串 job.miningdim.miner。故 planned 结构一律直给中文 displayName; 接线时凡是能
 *     改走翻译键的都应改掉, 这个字段本身就是"尚未接线"的标记。
 *   - 时间: 一律 epoch millis, 与真契约的 createdAt / acquiredAt 同口径; 不用相对秒数, 也不用 Date 对象
 *     (JSON 里不存在 Date, 用了就会在接线时炸)。
 *   - 可选性: planned 结构一律不用 `?:`, 缺值用 `| null`。真契约里 `?:` 与 `| null` 的分野来自 Gson 的
 *     serializeNulls 配置 (见 lib/types.ts 文件头), 那是读码读出来的事实; 这里没有对应事实可依, 与其瞎猜
 *     一个键是缺席还是 null, 不如全用 null 并在接线时按 Java 逐个改正。
 */

import type { ItemNamePart } from '../lib/i18n'
import type { BaseValueSource, PlayerInventoryItem, WebUiWallet } from '../lib/types'

/** 不吃任何字段的 planned action 用它占位 (等价 `{}`, 但不触发 no-empty-object-type)。 */
export type PlannedEmptyPayload = Record<string, never>

/** 三维坐标。矿洞落点/商店告示牌/军械台方块位共用。 */
export interface PlannedBlockPos {
  x: number
  y: number
  z: number
}

/**
 * 一行可读的数值展示。职业被动、档位表、护甲特效等"标签 + 数字 + 单位"的表格全用它 ——
 * 这些表在真服由各自的纯函数/Config 算出, 形状必然各不相同; 前端在接线前只需要能把它们排成表,
 * 故先统一成一种行结构, 接线时按各 action 的真实回执拆开。
 */
export interface PlannedStatLine {
  key: string
  label: string
  value: number
  unit: PlannedStatUnit
}

/** value 的量纲。percent 表示 0.35 要显示成 35%, 不是 0.35%。 */
export type PlannedStatUnit = 'flat' | 'percent' | 'seconds' | 'blocks' | 'credit'

/** 职业稳定 id。**不是发明的**: 逐字取自 com.miningdim.job.JobId.id(), 顺序也按 values() 声明序。 */
export type PlannedJobId =
  | 'miner'
  | 'farmer'
  | 'engineer'
  | 'tarot'
  | 'chef'
  | 'agent'
  | 'munitions'
  | 'brewer'

/** 矿洞三难度。**不是发明的**: 取自 com.miningdim.core.Difficulty.configName()。 */
export type PlannedDifficulty = 'easy' | 'medium' | 'hard'

// ============================================================
// A 组 · 地基
// ============================================================

/**
 * C1 job.progress 的单条职业进度。
 *
 * 原先它同时服务 A5 player.profile —— 那条已在 W1 核销为真契约 (lib/types.ts 的 PlayerJobProgressEntry),
 * 真契约里没有 displayName (服务端不直给中文, 前端按 `job.miningdim.<jobId>` 自解翻译键)。本结构留着
 * displayName 是 planned 域的既有约定 (见文件头第一条), job.progress 接线时按同一手法一并去掉。
 */
export interface PlannedJobProgressEntry {
  jobId: PlannedJobId
  displayName: string
  /** 1..10。 */
  level: number
  /** 累计有效经验 (IJobService.totalXp)。 */
  totalXp: number
  /** 当前等级内已获经验, 与 nextLevelXp 一起画进度条。 */
  levelXp: number
  /** 升到下一级所需经验; 满级时为 0 (前端据此判满级, 不要靠 level === 10 硬编码)。 */
  nextLevelXp: number
  /** 今日已入账有效经验 (受每日软上限衰减后)。 */
  dailyXp: number
  /** 今日还能满额入账多少 (撞 0 之后仍能获经验, 但按衰减系数打折)。 */
  dailyRemaining: number
}

// ============================================================
// B 组 · 跳蚤市场 (补真契约缺的那几条)
// ============================================================

/** B9 market.feePreview (WRAP, MarketFee:35-46 纯函数已就绪只差单独暴露) 入参。 */
export interface PlannedFeePreviewPayload {
  itemId: string
  unitPrice: number
  count: number
}

/**
 * B9 market.feePreview 回执。
 * 没有这条时玩家提交后才知道扣了多少费。ratio 是服务端算出来的实际费率, 前端**不得**自己按比例反算 ——
 * 真费率按挂价对 V0 的偏离度浮动, 量级能差好几倍 (与 bridge.mock 头部记的那条近似同源)。
 */
export interface PlannedFeePreviewResult {
  listFee: number
  /** 0..1 的实际费率, 仅供展示 "本单费率 X%"。 */
  ratio: number
  /** 该物品的基准价; 无锚时 null。 */
  v0: number | null
  /** 复用真契约的分层标注 (lib/types.ts BaseValueSource)。 */
  source: BaseValueSource
}

/** B10 market.p2pCap (WRAP, MarketConstants:51 cap=512/日 + DAO 聚合已就绪) 回执。 */
export interface PlannedP2pCapResult {
  usedToday: number
  capPerDay: number
  remaining: number
  /** 额度重置时刻 (UTC 翻日)。 */
  resetsAt: number
}

/**
 * B6 成交流水的单条记录。
 * 命名说明: 真 action 名很可能仍叫 market.history (它已存在, 只是 transactions 恒空);
 * 这里用 market.transactions 是为了不与真契约的 MarketHistoryResult 撞名 —— 那个类型把 transactions
 * 钉死成空元组 `[]` 是刻意的编译期路障, 不该被本文件覆盖掉。接线时按后端最终命名合并。
 */
export interface PlannedMarketTransaction {
  txnId: number
  /** 本条流水里"我"是买方还是卖方。 */
  role: PlannedTxnRole
  itemId: string
  descriptionId: string
  count: number
  unitPrice: number
  total: number
  /** 该笔实收/实付的手续费。 */
  fee: number
  /** 对手方玩家名; 系统回收等无对手场景为 null (A16 记的改名过期问题同样适用于这个快照名)。 */
  counterpartyName: string | null
  at: number
}

export type PlannedTxnRole = 'buyer' | 'seller'

/** B6 market.transactions 入参。 */
export interface PlannedTransactionsPayload {
  page: number
  pageSize: number
}

/** B6 market.transactions 回执。带 total —— market.list 缺 total 是已知缺陷 (B1), 新接的这条别再犯。 */
export interface PlannedTransactionsResult {
  transactions: PlannedMarketTransaction[]
  page: number
  pageSize: number
  total: number
}

/**
 * B11 market.pendingPayout (BACKEND, drainPendingPayout 是登录时取即删的破坏性方法, 无只读 peek) 回执。
 * 这条是只读 peek, 不得触发发放 —— 真接线时若复用了 drain, 玩家一开面板货款就没了。
 */
export interface PlannedPendingPayoutResult {
  credit: number
  items: PlannedPendingItem[]
}

export interface PlannedPendingItem {
  itemId: string
  descriptionId: string
  count: number
  /**
   * NBT 变体件的两个字段, 与 MarketListing / PlayerInventoryItem 同形状 (见 lib/types.ts 的 ItemVariantFields)。
   *
   * 这里预先补上而不是等接线时再加: 待领货款里装的就是别人买走的挂单实物, 一定会出现枪匠零件这类
   * 变体件。服务端真接线时只要让 drainPendingPayout 走一遍 WebUiItemJson, 这两个字段自然就在,
   * 前端不用再改一次 —— 而缺了它们, 收件箱里的 195 种零件是同名同图标的。
   */
  customModelData?: number
  nameParts?: ItemNamePart[]
}

/** B12 market.tradable (BACKEND, 挂单路径无标的过滤) 入参。 */
export interface PlannedTradablePayload {
  itemId: string
}

/**
 * B12 market.tradable 回执。
 * 不做这条, 前端无法灰掉不可挂的物品, 玩家会先托管再报错。reasonCode 留成字符串位而不是枚举:
 * 白名单规则 (J8) 尚未拍板, 现在定枚举等于替产品做决定。
 */
export interface PlannedTradableResult {
  itemId: string
  tradable: boolean
  reasonCode: string | null
  reason: string | null
}

// ============================================================
// C 组 · 职业
// ============================================================

/** C1 job.progress (WRAP, IJobService.progress 已给全字段只经 /job list 聊天文本暴露) 回执。 */
export interface PlannedJobProgressResult {
  /** 8 条并列, 按 JobId.values() 顺序。C3 已定: 全职业被动恒生效, 前端不做单选器。 */
  jobs: PlannedJobProgressEntry[]
}

/** C5 + C7 job.miner.state 回执。 */
export interface PlannedMinerStateResult {
  level: number
  /** 连锁充能当前值 / 上限 (MinerChargeState)。 */
  charge: number
  chargeMax: number
  /** 玩家自己的连锁开关。 */
  chainEnabled: boolean
  /** 探测脉冲可再次使用的时刻; 已就绪时 <= 现在。 */
  scanReadyAt: number
  /** 探测半径 (格)。C6 强调必须保留同等防 X 光限制, 前端不得放大这个数。 */
  scanRadius: number
  /** 探测解锁等级。 */
  scanUnlockLevel: number
  passives: PlannedStatLine[]
  /** C7 当日矿物软上限进度: 撞上限后单价按 decayFactor 打折。 */
  dailyOres: PlannedDailyOreLine[]
}

export interface PlannedDailyOreLine {
  itemId: string
  descriptionId: string
  minedToday: number
  softCap: number
  /** 当前生效的价格系数 (1 = 未衰减)。 */
  decayFactor: number
}

/** C6 job.miner.scan 入参: 一次只探一种矿 (防 X 光的核心限制之一)。 */
export interface PlannedMinerScanPayload {
  oreItemId: string
}

/** C6 job.miner.scan 回执。hits 是脉冲期内的一次性快照, 过期后必须由前端自行熄灭。 */
export interface PlannedMinerScanResult {
  oreItemId: string
  hits: PlannedBlockPos[]
  radius: number
  /** 脉冲熄灭时刻。 */
  expiresAt: number
  /** 下次可探测时刻。 */
  scanReadyAt: number
}

/** C8 job.farmer.state 回执。 */
export interface PlannedFarmerStateResult {
  level: number
  /** 卖菜的等级门 (地板对齐引用单一真源, 前端不得另抄一份常量)。 */
  sellUnlockLevel: number
  soldToday: number
  /** 今日收购额度; 撞顶后走衰减而非拒收。 */
  dailySoldCap: number
  crops: PlannedCropPrice[]
  /** 耕地五档。 */
  farmlandTiers: PlannedStatLine[]
}

export interface PlannedCropPrice {
  itemId: string
  descriptionId: string
  /** 当前单价 (已含收购曲线衰减)。 */
  unitPrice: number
  /** 未衰减时的锚价, 用来画"已跌到多少"。 */
  basePrice: number
  soldToday: number
}

/** C8 job.farmer.sell 入参: 按背包槽位卖, 与 market.place 同一索引空间。 */
export interface PlannedFarmerSellPayload {
  slot: number
  count: number
}

/** C8 job.farmer.sell 回执。服务端先扣物后发钱, 回执里的 credited 是实发金额 (已过收购曲线)。 */
export interface PlannedFarmerSellResult {
  itemId: string
  count: number
  credited: number
  soldToday: number
  /** 卖完之后该作物的新单价 (曲线已下移), 用来给玩家看"再卖会更便宜"。 */
  unitPriceAfter: number
}

/**
 * C9 job.chef.state 回执。
 * 数值走 ForgeConfigSpec 运营可调, 前端必须实时读这条 action, **严禁**抄一份静态副本进代码。
 */
export interface PlannedChefStateResult {
  level: number
  /** 当前等级能做出的最高菜品品质 (1..5)。 */
  qualityCap: number
  /** 各品质对应的增益数值表。 */
  effects: PlannedStatLine[]
  /** 调味台单次花费。 */
  seasoningCostCredit: number
}

/** C11 job.brewer.state 回执。 */
export interface PlannedBrewerStateResult {
  level: number
  brews: PlannedBrewEntry[]
  recipes: PlannedBrewRecipe[]
}

export interface PlannedBrewEntry {
  brewId: string
  displayName: string
  /** 永久层数 (BrewBuffStore), 喝到即永久。 */
  permanentStacks: number
  maxStacks: number
  /** 月光词条 (8 选 5) 中该酒当前挂上的那些。 */
  moonshineAffixes: string[]
}

export interface PlannedBrewRecipe {
  recipeId: string
  displayName: string
  inputs: PlannedPendingItem[]
  /** 陈酿天数 (至少七天周期的制造职业)。 */
  agingDays: number
}

/** C14 job.tarot.state 回执。 */
export interface PlannedTarotStateResult {
  level: number
  /** 碎片余额, 可兑换指定卡。 */
  fragments: number
  /** 22 张大阿卡纳的持有情况。 */
  deck: PlannedTarotCard[]
  /** C15 卡包: 信用点主力 sink。 */
  packPriceCredit: number
  packsBoughtToday: number
  /** 每日限购; spentToday 在真服是私有计数无 getter, 这条 action 存在的意义就是把它露出来。 */
  packDailyLimit: number
}

export interface PlannedTarotCard {
  cardId: string
  displayName: string
  /** 0 表示未持有。 */
  owned: number
  /** 品质门: 低于职业等级门时不可上阵。 */
  quality: PlannedTarotQuality
  /** 冷却结束时刻; 不在冷却时等于 0 (只读 peek, 不占用 —— tryUse 是写方法, 面板绝不能调它)。 */
  cooldownUntil: number
  /** 是否已编入当前卡组。 */
  equipped: boolean
}

export type PlannedTarotQuality = 'common' | 'uncommon' | 'rare' | 'epic' | 'legendary'

/** C15 job.tarot.buyPack 入参。 */
export interface PlannedBuyPackPayload {
  count: number
}

/** C15 job.tarot.buyPack 回执: 开出的卡 + 花掉的钱 + 剩余限购。 */
export interface PlannedBuyPackResult {
  drawn: PlannedTarotDraw[]
  spentCredit: number
  fragmentsGained: number
  packsBoughtToday: number
  packsRemainingToday: number
}

export interface PlannedTarotDraw {
  cardId: string
  displayName: string
  quality: PlannedTarotQuality
  /** true = 已持有, 本次转成碎片。 */
  duplicate: boolean
}

/** C16 + C18 job.agent.state 回执。 */
export interface PlannedAgentStateResult {
  level: number
  /** 战术扫描可再次使用的时刻。C16 记录了扫描触发入口零调用点 (最大缺口), 这里假定入口已存在。 */
  scanReadyAt: number
  scanRadius: number
  /** 上一次扫描到的封印候选。 */
  seals: PlannedSealTarget[]
  /** C18 悬赏板; 真服模板库与持久化未实现, 这是纯假定形状。 */
  bounties: PlannedBounty[]
}

export interface PlannedSealTarget {
  /** 扫描快照内的目标编号 (AgentSealSeam 的 targetNetworkId)。 */
  targetNetworkId: number
  entityLabel: string
  star: number
  /** 该目标身上可被封印的词条 id (取自 AffixDef 枚举名)。 */
  affixIds: string[]
  pos: PlannedBlockPos
}

export interface PlannedBounty {
  bountyId: string
  title: string
  targetType: string
  progress: number
  goal: number
  rewardCredit: number
  expiresAt: number
  claimable: boolean
}

/**
 * C16 job.agent.scan 回执。
 * C16 是本切片最大的缺口: menu/S2C/C2S 三件套齐全, 但 AgentSealSeam.buildScanSnapshot 与
 * AgentScanMenu.Provider 全工程零调用点 —— 探测脉冲根本没有触发入口 (触发入口本身是待决策项 J9)。
 * 这条 action 因此建立在"入口最终会存在"这个假定上, 是本文件里假定层数最深的几条之一。
 */
export interface PlannedAgentScanResult {
  seals: PlannedSealTarget[]
  radius: number
  /** 下次可扫描时刻。 */
  scanReadyAt: number
  /** 本次快照失效时刻; 过期后 seals 里的 targetNetworkId 不再可用于封印。 */
  expiresAt: number
}

/** C17 job.agent.seal 入参: 按目标 + 词条封印 (SealOutcome 九态服务端裁决齐全, 直转调即可)。 */
export interface PlannedAgentSealPayload {
  targetNetworkId: number
  affixId: string
}

/**
 * C17 job.agent.seal 回执。
 * outcomeCode 刻意留成字符串而不是联合字面量: 九态的枚举名以 Java SealOutcome 为准, 现在把名字编出来
 * 会让前端照着写 switch, 接线时九个分支全错。前端只判 ok, 文案直接显示 message。
 */
export interface PlannedAgentSealResult {
  ok: boolean
  outcomeCode: string
  message: string
}

/** C19 job.munitions.state 回执: 军火台 / 冲压机 / 装配台三台 ContainerData 的远程只读镜像。 */
export interface PlannedMunitionsStateResult {
  level: number
  stations: PlannedMunitionsStation[]
}

export interface PlannedMunitionsStation {
  stationId: string
  displayName: string
  /** 未放置该台时为 null。 */
  pos: PlannedBlockPos | null
  progress: number
  maxProgress: number
  running: boolean
  /** 当前产出物; 空闲时 null。 */
  outputItemId: string | null
}

/** C20 job.blueprints 回执: GunsmithBlueprint 枚举 dump, 玩家最常查的静态表。 */
export interface PlannedBlueprintsResult {
  blueprints: PlannedBlueprint[]
}

export interface PlannedBlueprint {
  blueprintId: string
  displayName: string
  gunId: string
  requiredParts: PlannedPendingItem[]
}

/** C21 job.engineer.state 回执 (玩家可见职业名是"铸甲师", engineer 只是旧存档兼容 id)。 */
export interface PlannedEngineerStateResult {
  level: number
  /** 纳米板档位表。 */
  tiers: PlannedStatLine[]
  armorEffects: PlannedArmorEffect[]
}

export interface PlannedArmorEffect {
  effectId: string
  displayName: string
  description: string
  /** 玩家当前是否已解锁。 */
  unlocked: boolean
}

// ============================================================
// D 组 · 经济
// ============================================================

/** D2 economy.status (WRAP, isAfkFrozen 接口方法已就绪) 回执。 */
export interface PlannedEconomyStatusResult {
  /** 挂机冻结: 冻结期间 faucet 不入账。 */
  afkFrozen: boolean
  /** 已连续静止秒数。 */
  idleSeconds: number
  /** 触发冻结的阈值秒数。 */
  freezeThresholdSeconds: number
}

/** D3 + D4 + D6 economy.today 回执: 今日全口径收支。 */
export interface PlannedEconomyTodayResult {
  faucets: PlannedFaucetLine[]
  sinks: PlannedSinkLine[]
  totalCreditIn: number
  totalCreditOut: number
  azureIn: number
  /** D4 青辉石每日硬上限 (撞顶即硬截断, 不是衰减)。 */
  azureDailyCap: number
  /** UTC 翻日时刻。 */
  resetsAt: number
}

export interface PlannedFaucetLine {
  faucetKey: string
  label: string
  earnedToday: number
  /** 软上限; 超过后按 decayFactor 打折继续发。 */
  softCap: number
  /** D3 玩家最想看的那个数: 当前处在哪一档衰减。 */
  decayFactor: number
}

export interface PlannedSinkLine {
  sinkKey: string
  label: string
  spentToday: number
}

/** D5 economy.priceTable 回执: 挖矿是最大 faucet 且价格随当日产量递减, 玩家却无处查。 */
export interface PlannedPriceTableResult {
  anchors: PlannedPriceAnchor[]
}

export interface PlannedPriceAnchor {
  itemId: string
  descriptionId: string
  /** ShopPriceTable 的静态锚价。 */
  anchorPrice: number
  /** 今日实际收购价 (锚价 x 当日衰减)。 */
  todayPrice: number
  /** 全服今日已产出量, 衰减的自变量。 */
  minedToday: number
}

// ============================================================
// E 组 · 婚姻
// ============================================================

/** E1 marriage.state 回执: 把散在 MarriageEngine 各查询方法里的状态聚合成一条。 */
export interface PlannedMarriageStateResult {
  status: PlannedMarriageStatus
  spouseName: string | null
  spouseUuid: string | null
  spouseOnline: boolean
  /** 结婚时刻; 未婚为 null。 */
  weddedAt: number | null
  marriageDays: number
  divorceCount: number
  /** 再婚冷却结束时刻; 无冷却为 0。 */
  remarryCooldownUntil: number
  /** 共享背包等级与对应格数 (E5 只读展示, 取放仍走 vanilla menu)。 */
  sharedInvLevel: number
  sharedInvSlots: number
  ringOwned: boolean
  ringPriceCredit: number
  milestones: PlannedMilestone[]
  /** E3 谁向我求婚: 真服 MarriageProposals 只有 byProposer 单向表, 无反查索引, 这是要新写的那部分。 */
  incomingProposals: PlannedProposal[]
  /** 我发出的那一份; 没有则 null。 */
  outgoingProposal: PlannedProposal | null
}

export type PlannedMarriageStatus = 'single' | 'engaged' | 'married' | 'cooldown'

export interface PlannedProposal {
  proposalId: string
  /** 求婚方玩家名 (A16: 快照名会随改名过期)。 */
  playerName: string
  playerUuid: string
  createdAt: number
  expiresAt: number
}

export interface PlannedMilestone {
  milestoneId: string
  label: string
  /** 未达成为 null。 */
  achievedAt: number | null
}

/** E2 marriage.buyRing 回执。 */
export interface PlannedBuyRingResult {
  ok: boolean
  costCredit: number
  wallet: WebUiWallet
}

/** E2 marriage.propose 入参 (按玩家名找人, 撞 A14 中文输入与 A16 名字解析两个缺口)。 */
export interface PlannedProposePayload {
  targetName: string
}

/** E2 marriage.propose 回执。 */
export interface PlannedProposeResult {
  proposalId: string
  targetName: string
  expiresAt: number
}

/** E2 + E3 marriage.respond 入参: 应答收到的求婚。 */
export interface PlannedRespondPayload {
  proposalId: string
  accept: boolean
}

/** E2 marriage.respond 回执。 */
export interface PlannedRespondResult {
  status: PlannedMarriageStatus
  spouseName: string | null
}

/** E2 marriage.wed 回执 (wed 六态失败枚举需映射前端文案; 同 seal, 枚举名以 Java 为准故只留字符串位)。 */
export interface PlannedWedResult {
  ok: boolean
  outcomeCode: string
  message: string
  weddedAt: number | null
}

/** E2 marriage.divorce 回执 (divorce 四态同上)。 */
export interface PlannedDivorceResult {
  ok: boolean
  outcomeCode: string
  message: string
  /** 再婚冷却结束时刻。 */
  cooldownUntil: number
}

/**
 * E5 marriage.sharedInv 回执。
 * items 复用真契约的 PlayerInventoryItem —— 清单原文就是"仿 PlayerWebUiActions.INVENTORY 逐槽转 JSON",
 * 发明第二种槽位形状只会让两边的渲染组件分叉。白名单已在容器层强制, 前端只读展示不重复校验。
 */
export interface PlannedSharedInvResult {
  slots: number
  items: PlayerInventoryItem[]
}

// ============================================================
// F 组 · 矿洞
// ============================================================

/**
 * F1 mining.overview 回执。
 * 认知前提 (最容易设计错的一点): R1 模型下全服**只有 3 个常驻共享固定实例**, 每难度一个, 不是私有副本。
 * 面板上不能出现"创建实例 / 我的副本"这类概念。
 */
export interface PlannedMiningOverviewResult {
  instances: PlannedMiningInstance[]
  /** 我当前所在难度; 不在矿洞维度时 null。 */
  myDifficulty: PlannedDifficulty | null
}

export interface PlannedMiningInstance {
  difficulty: PlannedDifficulty
  displayName: string
  /** F5 等级门。代码权威是 L4/L8, GateResult 头注释里的 MEDIUM=10/HARD=25 是过期文档口径, 别照抄。 */
  requiredMinerLevel: number
  playersInside: number
  /** F6 danger 实时值 0..1。真服已按周期推送原生 S2C, 但走的不是 webui 通道。 */
  danger: number
  lastResetAt: number
  /** F7 真服倒计时只活在 AutoResetScheduler 私有字段里; 退而求其次可用 lastReset + autoResetHours 推算。 */
  nextResetAt: number
}

/** F2 + F8 mining.myStatus 回执。 */
export interface PlannedMiningMyStatusResult {
  inside: boolean
  difficulty: PlannedDifficulty | null
  /** regionAt(x,z) 的结果。不在矿洞维度时两者均为 0。 */
  regionX: number
  regionZ: number
  danger: number
  /** F8 新手保护结束时刻; 无保护时 0。 */
  spawnFreezeUntil: number
  minerLevel: number
}

/** F3 mining.enter 入参。 */
export interface PlannedMiningEnterPayload {
  difficulty: PlannedDifficulty
}

/**
 * F3 mining.enter 回执。
 * 硬约束 (写死在任务书里的那条): 服务端实现必须复用 EntryGateway.requestEnter 这条权威路径 ——
 * /mining enter 命令与 SelectZoneC2S 包都跳过 gateCheck 且从不实际传送玩家, 照它们抄就是复制 bug。
 */
export interface PlannedMiningEnterResult {
  entered: boolean
  difficulty: PlannedDifficulty
  /** 被拒时的机器码 (等级门/维度不可用/已在内); 成功为 null。 */
  reasonCode: string | null
  message: string
}

/** F4 mining.leave 回执 (委派 EntrySystem.leaveToFallback)。 */
export interface PlannedMiningLeaveResult {
  left: boolean
  message: string
}

// ============================================================
// G 组 · 精英怪图鉴
// ============================================================

/**
 * G1 champion.codex 回执 (纯静态 dump: 35 词条 + 10 星级 + 难度分布)。
 * 本条的 mock 数据是全库唯一"直接抄自 Java 枚举真值"的一块 (AffixDef / StarRank), 见 seed.ts 注记。
 */
export interface PlannedChampionCodexResult {
  affixes: PlannedChampionAffix[]
  stars: PlannedChampionStar[]
  distribution: PlannedChampionDistribution[]
}

export interface PlannedChampionAffix {
  /** AffixDef 枚举名。 */
  affixId: string
  displayName: string
  pool: PlannedAffixPool
  /** 基础成本 c。 */
  cost: number
  minStar: number
  /** 是否占技能数上限。 */
  isSkill: boolean
  /** 互斥族; 无互斥为 null。 */
  mutexFamily: string | null
  /** 5 档品质数值 (普通/中级/高级/超凡/闪耀)。语义随词条不同 (减伤率/FLAT HP/%maxHP/s...), 不可跨条比较。 */
  tiers: number[]
}

/** AffixPool 枚举名 (SURVIVAL/COMBAT/MOBILITY/SKILL)。 */
export type PlannedAffixPool = 'SURVIVAL' | 'COMBAT' | 'MOBILITY' | 'SKILL'

export interface PlannedChampionStar {
  star: number
  survivalBudget: number
  combatBudget: number
  mobilityBudget: number
  skillBudget: number
  affixCap: number
  skillCap: number
  maxQuality: string
  /** 基础有效 HP; 6 星起破原版 1024 上限走自定义血池。 */
  baseEffectiveHp: number
  /** 该星普通单击的名义基线 (%maxHP)。 */
  baseHitPct: number
}

export interface PlannedChampionDistribution {
  difficulty: PlannedDifficulty
  /** 各星级在该难度的出现权重。 */
  starWeights: PlannedStarWeight[]
}

export interface PlannedStarWeight {
  star: number
  weight: number
}

/** G2 champion.inspect 入参。 */
export interface PlannedChampionInspectPayload {
  entityId: number
}

/** G2 champion.inspect 回执: 按实体查星级/词条/血量 (6 星及以上走自定义血池)。 */
export interface PlannedChampionInspectResult {
  entityId: number
  entityType: string
  displayName: string
  star: number
  affixIds: string[]
  health: number
  maxHealth: number
  /** true = 血量来自自定义血池而非 generic.max_health。 */
  customBloodPool: boolean
}

// ============================================================
// H 组 · 系统商店 (WOK-ChestShop 跨仓)
// ============================================================

/** H1 shop.catalog / H2 shop.detail 共用的告示牌商店条目。 */
export interface PlannedShopEntry {
  shopId: string
  /** 告示牌所在维度 id。真服 AdminShopRegistry 按 BlockPos 存且逐维度隔离, 故维度必须带上。 */
  dimension: string
  pos: PlannedBlockPos
  itemId: string
  descriptionId: string
  /** 玩家买入价; 该店不收买单时 null。 */
  buyPrice: number | null
  /** 玩家卖出价; 该店不收卖单时 null。 */
  sellPrice: number | null
  /** 系统店无限库存时 null。 */
  stock: number | null
}

/** H1 shop.catalog 回执。 */
export interface PlannedShopCatalogResult {
  shops: PlannedShopEntry[]
}

/** H2 shop.detail 入参。 */
export interface PlannedShopDetailPayload {
  shopId: string
}

/** H2 + H4 shop.detail 回执: 单店详情 + 同物品跨店比价 (真服只有正向索引, 比价是要新建的那部分)。 */
export interface PlannedShopDetailResult {
  shop: PlannedShopEntry
  comparable: PlannedShopEntry[]
}

/** H3 shop.buy 入参。 */
export interface PlannedShopBuyPayload {
  shopId: string
  count: number
}

/**
 * H3 shop.buy 回执。
 * 注意 H3 在清单里是 NONE: ShopTransaction.buy 只被"玩家物理点击真实告示牌"的路径调用, 内嵌 reach/tamper/
 * 冷却校验。隔空下单需要新规格 —— 这条 action 在真服存在与否本身还没定, 是本文件里假定成分最高的一条。
 */
export interface PlannedShopBuyResult {
  itemId: string
  count: number
  paidCredit: number
  wallet: WebUiWallet
}

// ============================================================
// I 组 · 管理后台 (OP)
// ============================================================

/** I2 admin.economy.balance 入参。 */
export interface PlannedAdminBalancePayload {
  playerName: string
}

/** I2 admin.economy.balance 回执。 */
export interface PlannedAdminBalanceResult {
  playerName: string
  playerUuid: string
  wallet: WebUiWallet
}

/** I2 admin.economy.set 入参 (抄 /economy set 的 ledgerOf + balance 范式)。 */
export interface PlannedAdminSetBalancePayload {
  playerName: string
  currency: PlannedCurrency
  amount: number
}

/** 货币种类。CREDIT 可转移, AZURE 不可转移 (契约第 1 节), 市场只认 CREDIT。 */
export type PlannedCurrency = 'CREDIT' | 'AZURE'

/**
 * I2 admin.economy.set 回执。
 * 带 before 是刻意的: 真服无流水表 (D7), 面板做出来也查不到历史调账, 至少让操作者当场看见改前改后。
 */
export interface PlannedAdminSetBalanceResult {
  playerName: string
  before: WebUiWallet
  wallet: WebUiWallet
}

/** I3 admin.job.setLevel 入参。 */
export interface PlannedAdminSetLevelPayload {
  playerName: string
  jobId: PlannedJobId
  level: number
}

/** I3 admin.job.setLevel 回执 (真服改级后会 syncTo, 前端无需另外触发同步)。 */
export interface PlannedAdminSetLevelResult {
  playerName: string
  jobId: PlannedJobId
  level: number
}

/** I4 admin.mining.reset 入参。 */
export interface PlannedAdminResetPayload {
  difficulty: PlannedDifficulty
}

/**
 * I4 admin.mining.reset 回执。
 * 活跃版 /mining reset 无二次确认 (有二次确认的那套在 com.miningdim.command 死代码里),
 * 故面板按钮必须自己加确认弹窗 —— 这条约束属于前端, 不要指望服务端拦。
 */
export interface PlannedAdminResetResult {
  difficulty: PlannedDifficulty
  resetAt: number
  /** 被踢出该实例的玩家数。 */
  evictedPlayers: number
}

// ============================================================
// 契约表 (与 lib/bridge.ts 的 WebUiContractMap 同结构, 但两张表永不合并)
// ============================================================

/**
 * planned action 名 -> {payload, result}。
 *
 * 命名与真契约的关系: 除 market.transactions 外, 这里的名字都是"后端将来大概率会叫的名字"(接线清单里
 * 写的那个)。market.transactions 是唯一一个刻意避名的 —— 真 action market.history 已存在且回执恒空,
 * 占着那个名字。
 */
export type PlannedContractMap = {
  'market.feePreview': { payload: PlannedFeePreviewPayload; result: PlannedFeePreviewResult }
  'market.p2pCap': { payload: PlannedEmptyPayload; result: PlannedP2pCapResult }
  'market.transactions': { payload: PlannedTransactionsPayload; result: PlannedTransactionsResult }
  'market.pendingPayout': { payload: PlannedEmptyPayload; result: PlannedPendingPayoutResult }
  'market.tradable': { payload: PlannedTradablePayload; result: PlannedTradableResult }
  'job.progress': { payload: PlannedEmptyPayload; result: PlannedJobProgressResult }
  'job.miner.state': { payload: PlannedEmptyPayload; result: PlannedMinerStateResult }
  'job.miner.scan': { payload: PlannedMinerScanPayload; result: PlannedMinerScanResult }
  'job.farmer.state': { payload: PlannedEmptyPayload; result: PlannedFarmerStateResult }
  'job.farmer.sell': { payload: PlannedFarmerSellPayload; result: PlannedFarmerSellResult }
  'job.chef.state': { payload: PlannedEmptyPayload; result: PlannedChefStateResult }
  'job.brewer.state': { payload: PlannedEmptyPayload; result: PlannedBrewerStateResult }
  'job.tarot.state': { payload: PlannedEmptyPayload; result: PlannedTarotStateResult }
  'job.tarot.buyPack': { payload: PlannedBuyPackPayload; result: PlannedBuyPackResult }
  'job.agent.state': { payload: PlannedEmptyPayload; result: PlannedAgentStateResult }
  'job.agent.scan': { payload: PlannedEmptyPayload; result: PlannedAgentScanResult }
  'job.agent.seal': { payload: PlannedAgentSealPayload; result: PlannedAgentSealResult }
  'job.munitions.state': { payload: PlannedEmptyPayload; result: PlannedMunitionsStateResult }
  'job.blueprints': { payload: PlannedEmptyPayload; result: PlannedBlueprintsResult }
  'job.engineer.state': { payload: PlannedEmptyPayload; result: PlannedEngineerStateResult }
  'economy.status': { payload: PlannedEmptyPayload; result: PlannedEconomyStatusResult }
  'economy.today': { payload: PlannedEmptyPayload; result: PlannedEconomyTodayResult }
  'economy.priceTable': { payload: PlannedEmptyPayload; result: PlannedPriceTableResult }
  'marriage.state': { payload: PlannedEmptyPayload; result: PlannedMarriageStateResult }
  'marriage.buyRing': { payload: PlannedEmptyPayload; result: PlannedBuyRingResult }
  'marriage.propose': { payload: PlannedProposePayload; result: PlannedProposeResult }
  'marriage.respond': { payload: PlannedRespondPayload; result: PlannedRespondResult }
  'marriage.wed': { payload: PlannedEmptyPayload; result: PlannedWedResult }
  'marriage.divorce': { payload: PlannedEmptyPayload; result: PlannedDivorceResult }
  'marriage.sharedInv': { payload: PlannedEmptyPayload; result: PlannedSharedInvResult }
  'mining.overview': { payload: PlannedEmptyPayload; result: PlannedMiningOverviewResult }
  'mining.myStatus': { payload: PlannedEmptyPayload; result: PlannedMiningMyStatusResult }
  'mining.enter': { payload: PlannedMiningEnterPayload; result: PlannedMiningEnterResult }
  'mining.leave': { payload: PlannedEmptyPayload; result: PlannedMiningLeaveResult }
  'champion.codex': { payload: PlannedEmptyPayload; result: PlannedChampionCodexResult }
  'champion.inspect': {
    payload: PlannedChampionInspectPayload
    result: PlannedChampionInspectResult
  }
  'shop.catalog': { payload: PlannedEmptyPayload; result: PlannedShopCatalogResult }
  'shop.detail': { payload: PlannedShopDetailPayload; result: PlannedShopDetailResult }
  'shop.buy': { payload: PlannedShopBuyPayload; result: PlannedShopBuyResult }
  'admin.economy.balance': {
    payload: PlannedAdminBalancePayload
    result: PlannedAdminBalanceResult
  }
  'admin.economy.set': {
    payload: PlannedAdminSetBalancePayload
    result: PlannedAdminSetBalanceResult
  }
  'admin.job.setLevel': { payload: PlannedAdminSetLevelPayload; result: PlannedAdminSetLevelResult }
  'admin.mining.reset': { payload: PlannedAdminResetPayload; result: PlannedAdminResetResult }
}

export type PlannedActionName = keyof PlannedContractMap
export type PlannedPayloadOf<A extends PlannedActionName> = PlannedContractMap[A]['payload']
export type PlannedResultOf<A extends PlannedActionName> = PlannedContractMap[A]['result']

/**
 * 全部 planned action 名的运行期清单。
 *
 * 用途有二: handlers 据此判"这个 action 走内存世界还是转调真桥"; 接线时它就是核销进度表 ——
 * 数组变短一条, 代表后端真落地了一行。按接线清单的分组顺序排列, 便于与文档对照。
 */
export const PLANNED_ACTIONS = [
  'market.feePreview',
  'market.p2pCap',
  'market.transactions',
  'market.pendingPayout',
  'market.tradable',
  'job.progress',
  'job.miner.state',
  'job.miner.scan',
  'job.farmer.state',
  'job.farmer.sell',
  'job.chef.state',
  'job.brewer.state',
  'job.tarot.state',
  'job.tarot.buyPack',
  'job.agent.state',
  'job.agent.scan',
  'job.agent.seal',
  'job.munitions.state',
  'job.blueprints',
  'job.engineer.state',
  'economy.status',
  'economy.today',
  'economy.priceTable',
  'marriage.state',
  'marriage.buyRing',
  'marriage.propose',
  'marriage.respond',
  'marriage.wed',
  'marriage.divorce',
  'marriage.sharedInv',
  'mining.overview',
  'mining.myStatus',
  'mining.enter',
  'mining.leave',
  'champion.codex',
  'champion.inspect',
  'shop.catalog',
  'shop.detail',
  'shop.buy',
  'admin.economy.balance',
  'admin.economy.set',
  'admin.job.setLevel',
  'admin.mining.reset',
] as const

/**
 * 编译期双向核对 (同 lib/bridge.ts 的 AssertContractCoverage): 清单数组与契约表任一方少一条或多一条,
 * 本类型即坍成 never, 下面那行赋值随之报错。两张手工表最容易悄悄脱节, 这道锁把脱节提到编译期。
 */
type AssertPlannedCoverage = [
  Exclude<PlannedActionName, (typeof PLANNED_ACTIONS)[number]>,
] extends [never]
  ? [Exclude<(typeof PLANNED_ACTIONS)[number], PlannedActionName>] extends [never]
    ? true
    : never
  : never

export const PLANNED_ACTIONS_COVER_CONTRACT: AssertPlannedCoverage = true
