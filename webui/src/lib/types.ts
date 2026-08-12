/**
 * Web UI 服务端/客户端 action 的 payload / result 类型 (契约层, 不含传输信封本身 —— 信封类型见 ../bridge/types.ts)。
 *
 * 字段名与 Java 侧逐字一致 (含大小写), 严禁按 TS 习惯改写成驼峰或加下划线 —— 这里的字段直接来自 Gson
 * `addProperty`/`add` 序列化, 改名即读不到值。每个接口都在其上方标了对应的 Java 类/方法, 契约漂移时按此反查。
 *
 * 精度纪律: Java `long` 字段 (id/金额/时间戳类) 序列化成 JSON number, 前端 `JSON.parse` 后收窄成 number ——
 * 超 Number.MAX_SAFE_INTEGER (2^53) 会静默丢精度。本文件在每个 long 字段上标注来源, 不代表已处理该风险
 * (当前经济数值远低于该阈值), 只是如实反映契约, 避免日后有人想当然把它们当 int 处理。
 *
 * 可选字段纪律: `?:` 表示"服务端可能整体不返回该键"(前端须用 `in` / `!== undefined` 判存在);
 * `| null` 表示"键恒返回但值可能是 JSON null"。两者语义不同, 严禁混用。
 *
 * 两个"无锚 V0"的 action 恰好落在这条分界的两侧, 且差别只来自各自用的 Gson 实例, 极易看走眼:
 *   - market.baseValue 用 `new GsonBuilder().serializeNulls()` (MarketActions.GSON_NULLS), 故 v0 恒有键、
 *     无锚时值为 JSON null       -> `number | null`
 *   - admin.listItems 用默认 `new Gson()` (MarketAdminActions.GSON)。它虽然显式 `o.add("v0", JsonNull.INSTANCE)`,
 *     但默认 Gson 的 serializeNulls=false 会在写出阶段连键一起丢掉, 前端收到的是**没有 v0 键**的对象
 *                                -> `v0?: number`
 * 即"服务端代码里写了 null"不等于"JSON 里有 null"; 判定依据必须是那一处用的哪个 Gson 实例。
 */

// ============================================================
// 共享子结构 (跨多个 action 复用同一 JSON 形状; 复用而非逐 action 重复定义)
// ============================================================

/** 不读 payload 任何字段的 action 用此类型占位: 语义等价于 `{}`, 但不触发
 *  `@typescript-eslint/no-empty-object-type` (recommendedTypeChecked 规则集内)。 */
export type EmptyPayload = Record<string, never>

/**
 * 双货币余额形状。player.wallet 的 result 与 case.state / case.open 内嵌的 `wallet` 字段逐字同形
 * (PlayerWebUiActions.WALLET 与 CaseWebUiActions.wallet(...) 各自独立 addProperty, 但字段集恒等), 故合一复用。
 */
export interface WebUiWallet {
  /** Java long -> number, 超 2^53 精度丢失。 */
  credit: number
  /** Java long -> number。键名是 azure (非 heartstone); Java 侧对应方法名为 heartstoneBalance/azureBalance。 */
  azure: number
}

/** market.baseValue / admin.listItems 共用的 V0 命中层标注 (MarketActions.BASE_VALUE / MarketAdminActions.LIST_ITEMS)。 */
export type BaseValueSource = 'override' | 'preset' | 'none'

/**
 * market.list 的合法排序键 (MarketDaoSqlite 白名单)。传其它任意字符串 (包括服务端自身的缺省值 "created_at",
 * 它不在白名单内) 都会被静默映射回 newest —— 这个类型只挡住前端手滑拼错字符串, 拦不住"服务端缺省值本身就
 * 不合法"这个陷阱, 前端就该直接不传 sort 让服务端走它自己的缺省路径。
 */
export type MarketSort = 'newest' | 'price_asc' | 'price_desc'

/** 开箱五档稀有度 (CaseRarity.id(), 枚举名小写)。声明序 blue -> purple -> pink -> red -> gold。 */
export type CaseRarity = 'blue' | 'purple' | 'pink' | 'red' | 'gold'

/**
 * WebUiBusinessException 的失败信封形状 (WebUiServerDispatcher.businessErrorJson)。
 * 仅 case.open / case.apply 当前会抛出该异常类型; 其余 action 的业务失败 (如 admin 非 OP) 走通用
 * Exception 分支, 只回 `{error: string}`, 没有 errorCode / retrySameOpeningId —— 不要对那些 action
 * 套用本类型。
 */
export interface WebUiBusinessErrorEnvelope<TCode extends string> {
  error: string
  errorCode: TCode
  retrySameOpeningId: boolean
}

// ============================================================
// system.* — WebUiServerSubsystem.java:50-78
// ============================================================

/** system.echo 入参 (WebUiServerSubsystem.handleEcho, :50-57)。 */
export interface SystemEchoPayload {
  /** 必填; 缺失时 payload.get("msg") 为 null, 对其 getAsString 自然抛, 经 Gateway 转 success=false。 */
  msg: string
}

/** system.echo 回执 (WebUiServerSubsystem.handleEcho, :50-57)。 */
export interface SystemEchoResult {
  /** = sender.getName().getString()。 */
  player: string
  echo: string
  /** Java int (服务器全局 tick 计数), 无精度风险。 */
  serverTick: number
}

/** system.handshake 入参 (WebUiServerSubsystem.handleHandshake, :67-78) —— 不吃任何字段。 */
export type SystemHandshakePayload = EmptyPayload

/**
 * system.handshake 回执 (WebUiServerSubsystem.handleHandshake, :67-78 + WebUiServerDispatcher.registeredActions, :139-143)。
 * 页面启动时拿 actions 与自身构建期记录的 action 集比对做契约自检 (架构文档 10.6)。
 */
export interface SystemHandshakeResult {
  /** 取自 ModList mod 容器版本; 取不到回退字面量 "unknown" (非缺席键)。 */
  modVersion: string
  /** 全部已注册服务端 action 名, 字典序, 不含 client.* 本地 action。 */
  actions: string[]
}

// ============================================================
// player.* — PlayerWebUiActions.java
// ============================================================

/** player.inventory 入参 (PlayerWebUiActions.INVENTORY, :47-71) —— 不读 payload。 */
export type PlayerInventoryPayload = EmptyPayload

/** 主背包非空格位条目 (仅 36 主背包槽, 不含护甲/副手)。 */
export interface PlayerInventoryItem {
  /** 与 market.place 的 slot 同一索引空间 (Inventory.items 下标)。 */
  slot: number
  itemId: string
  /** 翻译键; 过 client.i18n 解出显示名 (专用服务端不加载 lang)。 */
  descriptionId: string
  count: number
  /** 仅当 stack.hasCustomHoverName() 为真 (铁砧改名) 时才存在; 普通物品该键整体缺席。 */
  displayName?: string
}

/** player.inventory 回执 (PlayerWebUiActions.INVENTORY, :47-71)。 */
export interface PlayerInventoryResult {
  items: PlayerInventoryItem[]
}

/** player.wallet 入参 (PlayerWebUiActions.WALLET, :81-87) —— 不读 payload。 */
export type PlayerWalletPayload = EmptyPayload

/** player.wallet 回执 (PlayerWebUiActions.WALLET, :81-87)。 */
export type PlayerWalletResult = WebUiWallet

// ============================================================
// market.* — MarketActions.java
// ============================================================

/** market.list / market.mine 共用的挂单形状 (MarketActions.listingJson, :163-176)。 */
export interface MarketListing {
  /** Java long -> number。 */
  id: number
  sellerName: string
  itemId: string
  /** 翻译键; 物品已从注册表移除时 (卸载 mod 后的历史挂单) 回退为 itemId 本身。 */
  descriptionId: string
  count: number
  /** Java long -> number。 */
  unitPrice: number
  /** = unitPrice * count, 服务端算好的总价 (买入将付的金额)。Java long -> number。 */
  total: number
  /** epoch millis, Java long -> number。 */
  createdAt: number
}

/** market.list 入参 (MarketActions.LIST, :63-81)。 */
export interface MarketListPayload {
  /** 缺省 undefined 或显式 null 均视为"无过滤"(server 的 optString 对二者一视同仁)。 */
  query?: string | null
  /** 缺省时服务端用 "created_at" (不在白名单, 静默落 newest); 前端应直接省略本字段而非显式传它。 */
  sort?: MarketSort
  /** 缺省 0。 */
  page?: number
  /** 缺省 20; 服务端无上限钳制。 */
  pageSize?: number
}

/**
 * market.list 回执 (MarketActions.LIST, :63-81)。
 * 陷阱: 无 total 字段, 前端无法算总页数 (admin.listItems 才有 total —— 那是全库唯一一个)。
 */
export interface MarketListResult {
  listings: MarketListing[]
  page: number
  pageSize: number
}

/** market.place 入参 (MarketActions.PLACE, :87-102)。 */
export interface MarketPlacePayload {
  /** 必填; 缺失自然抛。 */
  slot: number
  /** 必填; 缺失自然抛。 */
  count: number
  /** 必填; 缺失自然抛。 */
  unitPrice: number
  /** 缺省 "CREDIT"; 市场只认信用点计价 (AZURE 不可转移, 契约第 1 节), 传其它字符串由引擎拒绝。 */
  currency?: 'CREDIT'
}

/** market.place 回执 (MarketActions.PLACE, :87-102)。 */
export interface MarketPlaceResult {
  /** Java long -> number。 */
  listingId: number
  /** 上单即收的手续费 (sink); 撤单不退。Java long -> number。 */
  listFee: number
}

/** market.buy 入参 (MarketActions.BUY, :108-121)。 */
export interface MarketBuyPayload {
  /** 必填; 缺失自然抛。 */
  listingId: number
  /**
   * 缺省或 <= 0 都表示"买下整单剩余"(MarketEngine.buy: `requestedCount <= 0 ? row.count() : requestedCount`),
   * 负数不是错误输入而是与 0 同义 —— 前端若把负数当非法值拦下, 拦的是自己而不是服务端。>0 则校验落在 1..剩余。
   */
  count?: number
}

/** market.buy 回执 (MarketActions.BUY, :108-121 + MarketEngine.BuyResult, :426)。 */
export interface MarketBuyResult {
  /** 恒为字面量 true; 失败走 success=false 信封, 不会带 ok:false。 */
  ok: true
  itemId: string
  /** Java int。 */
  count: number
  /** Java long -> number。 */
  total: number
  /** Java long -> number。 */
  fee: number
}

/** market.cancel 入参 (MarketActions.CANCEL, :127-136)。 */
export interface MarketCancelPayload {
  /** 必填; 缺失自然抛。 */
  listingId: number
}

/** market.cancel 回执 (MarketActions.CANCEL, :127-136 + MarketEngine.CancelResult, :430)。无 fee 字段 (手续费不退)。 */
export interface MarketCancelResult {
  ok: true
  itemId: string
  count: number
}

/** market.mine 入参 (MarketActions.MINE, :142-154) —— 不读 payload, 服务端权威取 sender 自己的 ACTIVE 挂单。 */
export type MarketMinePayload = EmptyPayload

/** market.mine 回执 (MarketActions.MINE, :142-154)。与 market.list 的 listings 同形, 但无 page / pageSize 字段。 */
export interface MarketMineResult {
  listings: MarketListing[]
}

/** market.history 入参 (MarketActions.HISTORY, :189-196)。 */
export interface MarketHistoryPayload {
  /** 缺省 0; 当前仅解析不消费 (无查询能力消费它)。 */
  page?: number
}

/**
 * market.history 回执 (MarketActions.HISTORY, :189-196)。
 * 陷阱: transactions 当前恒为空数组 —— MarketDao 至今无 transactionsByPlayer 查询 (已复核 store 包接口,
 * 仍只有 insertTxn 写入端)。类型故意钉死成空元组 `[]` 而非 `unknown[]`: 元素类型因此是 never,
 * 任何试图读取条目字段的代码都会立刻编译失败, 逼写这段代码的人先把真实条目形状补进契约。
 *
 * 但要清楚它拦不住什么: TypeScript 不参与 Java 那侧的序列化, 后端单方面把数组填上数据并不会让前端构建失败,
 * 只是"没人写消费代码"时数据被静默丢弃。契约漂移的真正兜底是 system.handshake 自检与人工同步, 不是这个类型。
 */
export interface MarketHistoryResult {
  transactions: []
  page: number
}

/** market.baseValue 入参 (MarketActions.BASE_VALUE, :207-229)。 */
export interface MarketBaseValuePayload {
  /** 必填; 缺失自然抛。 */
  itemId: string
}

/**
 * market.baseValue 回执 (MarketActions.BASE_VALUE, :207-229)。
 * 全库唯一使用 GsonBuilder().serializeNulls() 的 action: source==="none" 时 v0 是显式 JSON null 而非缺席键,
 * 故类型是 `number | null` 而非 `number | undefined` —— 前端可安全用 `'v0' in result` 判存在 (恒为 true),
 * 用 `result.v0 === null` 判"无锚"。注意 admin.listItems 的同名字段是**缺席键**, 两者不可套用同一套判存在写法。
 */
export interface MarketBaseValueResult {
  itemId: string
  /** Java long -> number, 无锚时为 null。 */
  v0: number | null
  source: BaseValueSource
}

/** market.categories 入参 (MarketActions.CATEGORIES, :239) —— 不读 payload。 */
export type MarketCategoriesPayload = EmptyPayload

/** 分支节点 (分类, 无 itemId 键): 分支 label 是固定中文字面量, 恒有 children 且非空 (空分支不输出)。 */
export interface CategoryBranchNode {
  id: string
  label: string
  children: CategoryNode[]
}

/** 叶子节点 (物品, 无 children 键): id = "i_" + itemId 把冒号换成下划线; label 是翻译键需过 client.i18n。 */
export interface CategoryLeafNode {
  id: string
  label: string
  itemId: string
}

export type CategoryNode = CategoryBranchNode | CategoryLeafNode

/**
 * market.categories 回执 (MarketActions.CATEGORIES, :239 + MarketCategoryTree.java:95-160)。
 * 陷阱: 顶层直接是数组, 不包外层对象 —— `JSON.parse(resultJson)` 直接得到 CategoryNode[], 不要按
 * `{categories: [...]}` 解。
 */
export type MarketCategoriesResult = CategoryNode[]

// ============================================================
// admin.* — MarketAdminActions.java (均为 OP 门控; 非 OP 抛 IllegalStateException 走失败信封)
// ============================================================

/** admin.setBaseValue 入参 (MarketAdminActions.SET_BASE_VALUE, :53-66)。 */
export interface AdminSetBaseValuePayload {
  /** 必填; 缺失自然抛。 */
  itemId: string
  /** 必填; 缺失自然抛。下界/合法性由引擎 setBaseValueOverride 校验, 越界自然抛。 */
  v0: number
}

/** admin.setBaseValue 回执 (MarketAdminActions.SET_BASE_VALUE, :53-66)。 */
export interface AdminSetBaseValueResult {
  ok: true
  itemId: string
  /** Java long -> number。 */
  v0: number
}

/** admin.listItems 入参 (MarketAdminActions.LIST_ITEMS, :72-125)。 */
export interface AdminListItemsPayload {
  /** 缺省 ""; 按小写子串匹配完整 itemId。 */
  query?: string
  /** 缺省 0; 服务端下钳到 >= 0。 */
  page?: number
  /** 缺省 50; 服务端钳制区间 [1,200]。 */
  pageSize?: number
}

/** admin.listItems 单条物品条目。 */
export interface AdminItemEntry {
  itemId: string
  /** 物品从注册表取不到时回退为空字符串 "" (与 MarketListing.descriptionId 回退成 itemId 本身不同, 勿混淆)。 */
  descriptionId: string
  /**
   * Java long -> number。**无锚 (source==="none") 时本键整体缺席**, 不是 JSON null ——
   * MarketAdminActions 虽写了 `o.add("v0", JsonNull.INSTANCE)`, 但它用的是默认 Gson (serializeNulls=false),
   * 键在写出阶段被一并丢掉。故判存在只能用 `'v0' in entry` / `entry.v0 !== undefined`, 严禁 `=== null`。
   */
  v0?: number
  source: BaseValueSource
}

/**
 * admin.listItems 回执 (MarketAdminActions.LIST_ITEMS, :72-125)。
 * 本 action 带 total (全库唯一一个); market.list 没有, 不要把两者的 result 类型混用。
 */
export interface AdminListItemsResult {
  items: AdminItemEntry[]
  page: number
  pageSize: number
  /** 匹配 query 后的全量条数 (分页前), 用于算总页数。 */
  total: number
}

// ============================================================
// case.* — CaseWebUiActions.java
// ============================================================

/** 皮肤目录条目的公共 5 字段 (CaseWebUiActions.skin(...), :118-126)。case.state.skins / case.open.reel 直接用它。 */
export interface CaseSkinSummary {
  skinId: string
  displayName: string
  rarity: CaseRarity
  /** ResourceLocation 字符串, 如 "tacz:m4a1"。 */
  gunId: string
  /** ResourceLocation 字符串, 如 "miningdim:case_<skinId>_display"。 */
  displayId: string
}

/** case.state.skins 条目: 目录 5 字段 + 当前玩家持有数量。 */
export interface CaseCatalogSkin extends CaseSkinSummary {
  ownedCount: number
}

/** 已持有的皮肤资产 (CaseWebUiActions.asset(...), :128-135): 目录 5 字段 + 资产 3 字段。 */
export interface CaseOwnedAsset extends CaseSkinSummary {
  /** UUID 字符串。 */
  assetId: string
  /** epoch millis, Java long -> number。 */
  acquiredAt: number
  /** Java long -> number; 当前恒为 0 (交易锁尚未实现)。 */
  tradeLockedUntil: number
}

/** 单个稀有度的权重条目 (CaseRarity.values() 枚举序: blue/purple/pink/red/gold)。 */
export interface CaseRarityWeight {
  rarity: CaseRarity
  /** Java int; 五档权重总和恒 100000 (整数权重, 非小数概率)。 */
  weight: number
}

/** case.state 入参 (CaseWebUiActions.STATE, :34-76) —— 不读 payload。 */
export type CaseStatePayload = EmptyPayload

/** case.state 回执 (CaseWebUiActions.STATE, :34-76)。 */
export interface CaseStateResult {
  /** = 配置开关 AND tacz 已加载 AND 资源包已注册, 三者任一为假即 false。 */
  enabled: boolean
  /** 恒 "founders"。 */
  caseId: string
  /** 恒中文字面量 "创始武器箱"。 */
  displayName: string
  /** Java long -> number。 */
  creditCost: number
  /** Java long -> number。 */
  azureCost: number
  wallet: WebUiWallet
  weights: CaseRarityWeight[]
  skins: CaseCatalogSkin[]
  /** 被 OWNED_RESPONSE_LIMIT=60 截断的持有资产切片; 真实总数看 ownedTotal。 */
  owned: CaseOwnedAsset[]
  /** 持有资产的真实总数 (owned 数组可能被截断到 60 条)。 */
  ownedTotal: number
}

/** case.open 入参 (CaseWebUiActions.OPEN, :78-96)。 */
export interface CaseOpenPayload {
  /** 必填, 必须是合法 UUID 字符串, 由前端生成; 同一 id 重放安全 (断线重连复播)。 */
  openingId: string
  /** 缺省 "founders"。显式传 null 或非法值在服务端抛 INVALID_REQUEST —— 前端应直接省略而非显式传 null。 */
  caseId?: string
}

/** case.open 回执 (CaseWebUiActions.OPEN, :78-96)。 */
export interface CaseOpenResult {
  openingId: string
  /** true 表示该 openingId 此前已存在 (断线重连复播, 不重复扣费)。 */
  replayed: boolean
  /** 指向 reel 数组中最终停下的下标; 前端动画必须以此为权威落点。Java int。 */
  stopIndex: number
  wallet: WebUiWallet
  result: CaseOwnedAsset
  /** 只有 skin 五字段, 无 assetId (滚动动画用, 非持有资产)。 */
  reel: CaseSkinSummary[]
}

/** case.open 业务失败信封 (CaseOpeningService.java:101-147 抛出的 WebUiBusinessException errorCode 全集)。 */
export type CaseOpenErrorCode =
  | 'CASE_DISABLED'
  | 'INSUFFICIENT_FUNDS'
  | 'RATE_LIMITED'
  | 'OPENING_REFUNDED'
  | 'OPENING_ID_CONFLICT'
  | 'INVALID_REQUEST'

export type CaseOpenErrorEnvelope = WebUiBusinessErrorEnvelope<CaseOpenErrorCode>

/** case.apply 入参 (CaseWebUiActions.APPLY, :98-109)。 */
export interface CaseApplyPayload {
  /** 必填, 必须是合法 UUID 字符串; 非法抛 INVALID_REQUEST。 */
  assetId: string
}

/**
 * case.apply 回执 (CaseWebUiActions.APPLY, :98-109)。
 * 陷阱: 本 action 回执刻意比 case.state 的资产对象窄 —— 无 displayName / rarity / acquiredAt /
 * tradeLockedUntil, 故不派生自 CaseOwnedAsset, 独立声明五个字段。
 */
export interface CaseApplyResult {
  applied: true
  assetId: string
  skinId: string
  gunId: string
  displayId: string
}

/** case.apply 业务失败信封 (CaseOpeningService.java:186-203 抛出的 WebUiBusinessException errorCode 全集)。 */
export type CaseApplyErrorCode = 'TACZ_UNAVAILABLE' | 'ASSET_NOT_OWNED' | 'INVALID_REQUEST'

export type CaseApplyErrorEnvelope = WebUiBusinessErrorEnvelope<CaseApplyErrorCode>

// ============================================================
// client.* — WebUiBridge.java (客户端本地 action, 不走服务端往返; 不出现在 system.handshake 的 actions 里)
// ============================================================

/** client.i18n 入参 (WebUiBridge.handleClientLocal, :160-186)。 */
export interface ClientI18nPayload {
  /** 非数组或缺席时按空数组处理 (不抛)。 */
  keys: string[]
}

/** client.i18n 回执 (WebUiBridge.handleClientLocal, :160-186)。 */
export interface ClientI18nResult {
  /** 键为传入的翻译键原文; 缺翻译时原版 I18n.get 回退为键本身而非报错, 故 `names[key] === key` 要按"未翻译"处理。 */
  names: Record<string, string>
}

/** client.playCaseSound 的合法 cue 白名单 (WebUiBridge.CASE_SOUND_CUES, :51-53)。 */
export type CaseSoundCue =
  | 'unlock'
  | 'open'
  | 'tick'
  | 'reveal_blue'
  | 'reveal_purple'
  | 'reveal_pink'
  | 'reveal_red'
  | 'reveal_gold'

/** client.playCaseSound 入参 (WebUiBridge.handleCaseSound, :188-201)。 */
export interface ClientPlayCaseSoundPayload {
  /** 必须命中 CaseSoundCue 白名单, 否则 callback.failure(-1)。 */
  cue: CaseSoundCue
}

/** client.playCaseSound 回执 (WebUiBridge.handleCaseSound, :188-201) —— 硬编码字符串字面量, 无其它字段。 */
export interface ClientPlayCaseSoundResult {
  played: true
}
