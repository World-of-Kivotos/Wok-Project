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

import type { ItemNamePart } from './i18n'

export type { ItemNamePart }

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

/**
 * 货币种类 (Java 侧 com.miningdim.economy.Currency 枚举名逐字大写)。
 *
 * 只在"服务端会回显币种"或"入参要指定币种"的 action 上出现 (job.tarot.state/buyPack 的卡包定价、
 * admin.economy.set 的目标币种)。余额本身走 WebUiWallet 的 credit/azure 两个具名字段, 不用本类型索引 ——
 * 两套写法混用会让"哪一栏是青辉石"变成运行期才知道的事。
 */
export type WebUiCurrency = 'CREDIT' | 'AZURE'

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
  /**
   * 文案占位符实参 (WebUiBusinessException 第四参)。服务端只在非空时写这一键, 故整键可缺席;
   * 值一律是字符串 (数字也字符串化)。case.* 现有拒绝均不带 params。
   */
  params?: Record<string, string>
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

/** system.serverStatus 入参 —— 不读 payload 任何字段。 */
export type SystemServerStatusPayload = EmptyPayload

/**
 * system.serverStatus 回执 (Java 落点: WebUiServerSubsystem, 与 system.echo/handshake 同类同 GSON 实例
 * `private static final Gson GSON = new Gson()` —— 默认 Gson, 无 serializeNulls, 故本回执全部字段恒存在,
 * 无 `?:` 也无 `| null`)。
 *
 * announcement 已按 D5 砍掉: 全库 src/main/java 下 grep 公告/announcement/bulletin 零命中, 唯一贴近的
 * MinecraftServer.getMotd() 是 server.properties 的服务器描述, 不是运营公告。恒回空串等于立一个永远为空的
 * 死约定, 故直接不发这个字段。
 */
export interface SystemServerStatusResult {
  /** = sender.server.getPlayerCount() (Java int)。 */
  online: number
  /** = sender.server.getMaxPlayers() (Java int)。 */
  maxPlayers: number
  /**
   * 派生值, 服务端算: mspt <= 0 ? 20.0 : Math.min(20.0, 1000.0 / mspt)。
   * mspt <= 0 只出现在服务器首 tick 之前 (averageTickTime 字段初值 0), 是正常初值不是故障, 故给设计满速 20
   * 而不是让它除出 Infinity。20 是原版 MS_PER_TICK=50 对应的设计上限, 硬编码。
   */
  tps: number
  /**
   * = sender.server.getAverageTickTime() (Java float -> JSON number)。单位已是毫秒 (原版每 tick 做
   * averageTickTime = averageTickTime*0.8f + (nanos/1_000_000f)*0.2f 的 EMA 平滑), 服务端不再二次平均。
   */
  mspt: number
  /**
   * = sender.server.getTickCount() / 20 (Java int 整数除)。
   * 口径必须写死: 这是"已运行的游戏刻数折算秒", 服务器掉刻时会慢于真实挂钟时间。选它是因为原版没有"开机挂钟
   * 时刻"的公开 getter, 而为一个状态栏数字新建一份 ServerStartedEvent 时间戳状态不划算 (YAGNI)。
   * 前端 AdminPage 的 formatUptime 文案按此口径写"已运行", 不要宣称是挂钟时长。
   */
  uptimeSeconds: number
}

// ============================================================
// player.* — PlayerWebUiActions.java
// ============================================================

/** player.inventory 入参 (PlayerWebUiActions.INVENTORY) —— 不读 payload。 */
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
  /** NBT 变体件的贴图选择码。见 ItemVariantFields。 */
  customModelData?: number
  /** NBT 变体件的显示名结构。见 ItemVariantFields。 */
  nameParts?: ItemNamePart[]
}

/**
 * NBT 变体件的两个可选字段 (Java 侧 WebUiItemJson 追加)。
 *
 * 为什么需要: itemId 与 descriptionId 都是 **Item 级**的, 而本 mod 有一大类靠 NBT 区分变体的物品 ——
 * 枪匠零件的 195 种全部注册在同一个 `miningdim:gunsmith_part` 之下, 平台/部位/品质由 NBT 决定。
 * 不带这两个字段的话, 市场里这 195 行是同名 ("枪匠零件") 同图标的。
 *
 * **两者都可能缺席**, 且绝大多数物品两者都缺席 —— 一 id 一贴图一名字的物品不需要它们。
 * 故判存在一律用 `!== undefined`, 缺席时走既有的 itemId / descriptionId 路径。
 */
export interface ItemVariantFields {
  /**
   * 原版 CustomModelData。前端按 (itemId, 此值) 查 `/mc/variants.json` 取变体贴图,
   * 那张表由 vite 在构建期从 mod 的物品模型 overrides 生成。查不到即回落默认贴图。
   */
  customModelData?: number
  /**
   * 显示名的片段序列 (翻译键与字面量交替)。经 collectNamePartKeys + useItemNames 批量解析后
   * 用 formatNameParts 拼出显示名。
   *
   * 不是一个字符串: 专用服务端不加载 mod 的 lang, 在那边解只能得到原始键。
   */
  nameParts?: ItemNamePart[]
}

/** player.inventory 回执 (PlayerWebUiActions.INVENTORY)。 */
export interface PlayerInventoryResult {
  items: PlayerInventoryItem[]
}

/** player.wallet 入参 (PlayerWebUiActions.WALLET) —— 不读 payload。 */
export type PlayerWalletPayload = EmptyPayload

/** player.wallet 回执 (PlayerWebUiActions.WALLET)。 */
export type PlayerWalletResult = WebUiWallet

/** player.isOp 入参 —— 不读 payload 任何字段。 */
export type PlayerIsOpPayload = EmptyPayload

/**
 * player.isOp 回执 (Java 落点: PlayerWebUiActions, 与 player.inventory/player.wallet 同类同 GSON)。
 *
 * 只用于前端渲染决策 (TabletShell 的 opOnly 导航过滤与 OP 徽标)。红线不变: 每个 admin.* 动作仍各自
 * 在自己的 handler 内独立校验 (MarketAdminActions.requireOp), Gateway (WebUiServerDispatcher.dispatchAndRespond)
 * 不做任何权限兜底 —— 前端拿到 true 也不等于服务端会放行。
 */
export interface PlayerIsOpResult {
  /** = sender.getServer().getPlayerList().isOp(sender.getGameProfile())。 */
  isOp: boolean
}

/** player.itemDetail 入参。 */
export interface PlayerItemDetailPayload {
  /**
   * Inventory.items 下标, 与 player.inventory / market.place 同一索引空间 (合法域 0..inv.items.size()-1,
   * 即主背包 36 槽, 不含护甲/副手)。越界 -> SLOT_OUT_OF_RANGE; 槽位为空 -> SLOT_EMPTY; 缺字段或非整数 -> INVALID_REQUEST。
   */
  slot: number
}

/**
 * 物品详情的一行数值 (player.itemDetail.attributes 的元素)。
 *
 * 刻意不下发 label: 专用服务端不加载 lang, 服务端拼中文违反"直给中文一律删"纪律。文案由前端按 key 自查
 * (与 hub.panels 的 label 留前端同一条纪律)。
 */
export interface ItemDetailStat {
  /** 稳定机器键 (小驼峰), 各 kind 的行表见 PlayerItemDetailResult 注释。 */
  key: string
  /** Java double。unit='percent' 时是"相对基准的增减量"(0.12 = +12%), 服务端已按 (系数 - 1.0) 换算完毕。 */
  value: number
  unit: 'flat' | 'percent'
}

/** 物品大类。判定顺序即声明顺序 (gun -> gunsmith_part -> tarot -> wine -> nano -> plain)。 */
export type ItemDetailKind = 'plain' | 'gun' | 'gunsmith_part' | 'tarot' | 'wine' | 'nano'

/**
 * player.itemDetail 回执 (Java 落点: PlayerWebUiActions + WebUiItemDetailJson, 默认 `new Gson()`,
 * 故凡是条件性 addProperty 的字段一律是缺席键 `?:`, 不存在 JSON null)。
 *
 * 一、前四个字段与变体两字段逐字复用 player.inventory 的既有范式
 * itemId/descriptionId/count 的取法与 PlayerWebUiActions.INVENTORY 完全一致; 变体两字段
 * (customModelData/nameParts) 由 WebUiItemJson.appendVariant 追加, 与 market.list / player.inventory 同一套 ——
 * 否则三处各持一套变体展示口径 (195 种枪匠零件同名同图标那个 bug 就是这么来的)。
 *
 * 二、脏 NBT 一律降级不冒泡
 * 创造模式直给的裸塔罗牌与跨平衡改动的老枪 (GunsmithGunStats.validateCurrentStats 用 Double.compare 把缓存
 * stats 与按当前平衡表重算的值精确比对, 不相等即抛) 都是正常游玩产物; 让它们冒泡等于"正常物品点开就报错"。
 * 故服务端一律用非抛探针判 kind, 取值走属主类内部的降级包装; 降级不静默: kind 落回 'plain' 且 tags 必带
 * 'data.unreadable:<原大类>' 一条。
 */
export interface PlayerItemDetailResult {
  /** 回显入参 slot。 */
  slot: number
  /** = MarketEngine.itemIdOf(stack) (与 player.inventory 同一取法)。 */
  itemId: string
  /** 翻译键 = stack.getDescriptionId(); 过 client.i18n 解中文。 */
  descriptionId: string
  count: number
  /** 仅 stack.hasCustomHoverName() 为真 (铁砧改名) 时存在; 与 PlayerInventoryItem.displayName 同源同判据。 */
  displayName?: string
  /** WebUiItemJson.appendVariant 追加; 无 CustomModelData 时整键缺席。见 ItemVariantFields。 */
  customModelData?: number
  /** WebUiItemJson.appendVariant 追加; 名字与 Item 级默认名一致时整键缺席。见 ItemVariantFields。 */
  nameParts?: ItemNamePart[]
  /** 物品大类; 脏 NBT 降级后恒为 'plain'。 */
  kind: ItemDetailKind
  /**
   * 数值行, 顺序即服务端写入顺序。各 kind 的行表 (key : unit : 来源):
   *  gun          : damage/headshot/range/handling/average/fireRate/verticalRecoil/horizontalRecoil/inaccuracy : percent :
   *                 GunsmithGunStats 同名 getter 减 1.0 (verticalRecoil 用现成的 recoilChange(), inaccuracy 用
   *                 spreadChange(), 二者本身已是 -1.0 口径); 另有 partCount : flat : parts().size()。
   *  gunsmith_part: coefficient : flat : PartData.coefficient(); 非 BASIC 变体另加
   *                 fireRate/verticalRecoil/inaccuracy : percent : variant().xxxMultiplier(coefficient) - 1.0。
   *  tarot        : cardId : flat : TarotCardItem.cardId(stack)。
   *  wine         : vintage : flat : WineNbt.readVintage; strength : flat : WineNbt.strength (变质恒 0)。
   *  nano         : shieldCharges/shieldRegenTick/shieldWindowTick : flat : NanoNbt 同名 getter (单位是 tick,
   *                 服务端不折算秒); qualityHits : flat : NanoNbt.qualityHits (仅护甲板有值)。
   *  plain        : 空数组。
   */
  attributes: ItemDetailStat[]
  /**
   * 稳定机器码标签, 恒存在 (无标签时是空数组, 不是缺席键)。文案由前端按码自解。
   * 形态: 'ns.name' 或带参 'ns.name:<稳定id>'。当前全集:
   *  gun          : 'gun.platform:<platform()>' / 'gun.template:<template()>'
   *  gunsmith_part: 'part.platform:<platform().id()>' / 'part.slot:<part().id()>' /
   *                 'part.variant:<variant().id()>' / 'part.quality:<quality().id()>'
   *  tarot        : 'tarot.quality:<quality().id()>' / 'tarot.upright' 或 'tarot.reversed' / 'tarot.bound'
   *  wine         : 'wine.quality:<readQuality().id()>' / 'wine.spoiled'
   *  nano         : 'nano.effect:<NanoEffect.id()>' (逐个) / 'nano.xpPending'
   *  降级         : 'data.unreadable:gun' / 'data.unreadable:gunsmith_part' / 'data.unreadable:tarot' /
   *                 'data.unreadable:wine' (两种成因: 年份非有限, 或酒章在但品质 id 已被改名认不出)
   */
  tags: string[]
}

/** player.profile 入参 —— 不读 payload 任何字段。 */
export type PlayerProfilePayload = EmptyPayload

export type PlayerRosterPayload = EmptyPayload

/** player.roster 名册里的一行 (Java 落点: PlayerWebUiActions.ROSTER)。 */
export interface PlayerRosterEntry {
  /** = ServerPlayer.getGameProfile().getName()。 */
  name: string
  /** = ServerPlayer.getUUID().toString()。 */
  uuid: string
}

/**
 * player.roster 回执: 在线玩家名册 (Java 落点: PlayerWebUiActions.ROSTER)。
 *
 * 存在的理由是"免输入": marriage.propose 与 admin.economy.balance/set 都按玩家名找人, 而中文输入 (W11)
 * 已推迟 —— 只给一个输入框, 中文 ID 的玩家就永远求不了婚。名册让界面能做成点选。
 *
 * 服务端不做任何过滤, 调用者自己也在名册里 (admin 调账的目标可以是自己); 要排除自己由前端按
 * player.profile 的名字做。
 */
export interface PlayerRosterResult {
  /** 单次最多 200 条 (服务端硬上限)。 */
  players: PlayerRosterEntry[]
  /** 全量在线人数, 不是本次下发条数 —— 截断时前端靠它讲清还有多少人没显示。 */
  total: number
  /** total 超过 200 时为 true。 */
  truncated: boolean
}

/**
 * player.profile 聚合里的单条职业进度 (Java 落点: PlayerWebUiActions)。
 *
 * 刻意无 displayName (纪律: 直给中文一律删): JobId.displayName() 返的是 Component.translatable("job.miningdim."+id),
 * 专用服务端解不出中文。前端按 `job.miningdim.<jobId>` 走 client.i18n 自解。
 */
export interface PlayerJobProgressEntry {
  /** = JobId.id(); 域与顺序 = JobId.values() 声明序, 恒 8 条。 */
  jobId: 'miner' | 'farmer' | 'engineer' | 'tarot' | 'chef' | 'agent' | 'munitions' | 'brewer'
  /** 1..10 = JobProgress.level()。 */
  level: number
  /** = JobProgress.xp(JobId) (FARMER 走 Math.round, 其余 floor)。 */
  totalXp: number
  /** = totalXp - JobXpCurve.cumulativeXpForLevel(level)。本级已获经验。 */
  levelXp: number
  /**
   * **本级跨度** = cumulativeXpForLevel(level+1) - cumulativeXpForLevel(level), 不是"还差多少"。
   * 前端拿 (levelXp / nextLevelXp) 当进度条的 value/max, 只有跨度口径才落在 [0,1]。
   * 满级态 (level === JobXpCurve.MAX_LEVEL === 10): 服务端 **nextLevelXp 固定发 0 且 levelXp 同时发 0**,
   * 前端据 nextLevelXp === 0 判满级并改画一句结论, 不画 0/0 的 NaN 宽度空槽, 也不许靠 level === 10 硬编码。
   */
  nextLevelXp: number
  /**
   * = JobProgress.dailyXp(JobId, todayStamp) 今日已入账有效经验 (已按每日软上限衰减)。
   * todayStamp 由 IEconomyService.currentDayStamp() 给出: 这个只读重载**不翻日**, 只是拿日戳比对后
   * 决定返 0 还是返存量。直接读字段会拿到昨天的脏值 —— 昨天吃满额度、今天没开工时会显示"额度已用尽"。
   */
  dailyXp: number
  /**
   * = JobProgress.dailyRemaining(JobId, todayStamp) 今日还能满额入账多少 (撞 0 后仍能获经验, 但按衰减打折)。
   * 同上用带日戳的只读重载: 清零权只归入账路径, 只读接口顺手翻日等于把衰减档位洗回第 0 档。
   */
  dailyRemaining: number
}

/**
 * player.profile 回执 (Java 落点: PlayerWebUiActions, 默认 `new Gson()`, 全字段恒存在)。
 * 存在的唯一理由是首屏: 不做这条, hub 首页要串行多次 MCEF 往返。
 *
 * 性能约束: 本 action 每次打 3 次 SQLite (creditBalance / heartstoneBalance / 一次合并的 faucet peek),
 * 且派发在服务器主线程 (C2SWebUiRequest 经 enqueueWork 切主线程)。
 * **禁止把 profile 挂上定时轮询**; 现有调用点的 world.revision 触发式重载是上限。
 */
export interface PlayerProfileResult {
  /** = sender.getGameProfile().getName()。 */
  playerName: string
  /** = PlayerList.isOp(GameProfile); 与 player.isOp / hub.panels 的 admin 门同一判定, 不许两套。 */
  isOp: boolean
  /** 复用 WebUiWallet; 取法与 player.wallet 逐字相同, 不另写一遍。 */
  wallet: WebUiWallet
  /** 恒 8 条, 顺序 = JobId.values()。 */
  jobs: PlayerJobProgressEntry[]
  /**
   * 今日信用点 faucet **毛额 (衰减前)**, Java long。
   * 口径写死: 账本里这个计数器落的是 EconomyLedger.recordFaucetGrant 的 rawAmount 累加值, 即衰减主闸
   * **打折之前**的原始额; 玩家实际到手的是打折后的数。字段名带 Gross 就是为了让人一眼看出它不是到账额。
   * 前端文案必须写明"毛额(衰减前)", 严禁笼统写成"今日入账"。
   */
  todayCreditFaucetGross: number
  /**
   * 今日青辉石 **实发额**, Java long。
   * 与上一栏刻意不对称: 青辉石走硬截断, 账本落的是 EconomyLedger.creditAzureDaily 实际入账的量, 天然就是
   * 到手额, 不存在毛额概念。两栏共用 daily_counters 的 KIND_FAUCET 同一张表, 只是 counter_key 不同。
   */
  todayAzureIn: number
}

/** player.prefs.get 入参 —— 不读 payload 任何字段。 */
export type PlayerPrefsGetPayload = EmptyPayload

/**
 * 账号级 UI 偏好 (player.prefs.get 回执 / player.prefs.set 入参与回执, 三处同形)。
 *
 * 落点是 capability (IMiningPlayerData), 跟玩家 player.dat 走, 因此换机器/清浏览器缓存不丢。
 * 只收"前端真有控件在改"的四项: uiScale 与 layout 是像素风时代的遗留, 前端零控件零读取, 不落账号 ——
 * 写进 player.dat 之后想删就要动 deserializeNBT 的兼容分支。
 *
 * 未落账号的一项: 强调色彩度 (brand.chroma)。本批只收色相, 故彩度仍是本机 localStorage 值;
 * SettingsPage 的说明文案必须如实写"色相跟随账号, 彩度只在这台电脑"。
 *
 * 全字段恒存在 (默认 Gson 且四项都有硬默认值), 无 `?:` 无 `| null`。
 */
export interface PlayerPrefs {
  /** 免打扰: 关掉成交/求婚/击杀结算的浮层提示。默认 false。 */
  muteToasts: boolean
  /** 界面语言码 (MC lang code 形态, 如 'zh_cn')。写入侧域 ^[a-z0-9_]{1,16}$; 读取侧非法值回退 'zh_cn'。 */
  language: string
  /** 亮暗档。默认 'dark' (与 lib/theme.ts readStored 的默认档一致)。 */
  theme: 'dark' | 'light'
  /** 强调色 oklch 色相角, Java int, 域 0..360 闭区间。默认 250 (与 lib/brand.ts 的 DEFAULT_BRAND.hue 一致)。 */
  brandHue: number
}

/** player.prefs.get 回执 = 当前完整偏好。 */
export type PlayerPrefsGetResult = PlayerPrefs

/**
 * player.prefs.set 入参 = 一份**完整**偏好 (整份覆盖, 不做部分更新)。
 * 四个字段缺任意一个或类型不符 -> INVALID_REQUEST (params.field 指出是哪一个);
 * 取值域外 (theme 不是 dark/light、brandHue 不在 0..360、language 不符 ^[a-z0-9_]{1,16}$) -> INVALID_REQUEST
 * (params 带 field 与 value)。
 *
 * 为什么不做部分更新: 三态语义 (给了 / 给了 null / 没给) 会把"清空某项"与"不动某项"混在一起,
 * 而前端 SettingsPage 本来就持有完整偏好状态, 整份提交是零成本。
 */
export type PlayerPrefsSetPayload = PlayerPrefs

/**
 * player.prefs.set 回执 = **落盘后**的完整偏好 (与入参同形)。
 * 回发一份而不是 {ok:true}: 前端据此对齐本地状态; 服务端日后若收窄某项取值域, 前端能立刻看到被改写成什么。
 */
export type PlayerPrefsSetResult = PlayerPrefs

// ============================================================
// hub.* — HubWebUiActions.java
// ============================================================

/** hub.panels 入参 —— 不读 payload 任何字段。 */
export type HubPanelsPayload = EmptyPayload

/**
 * 稳定面板 id。域按 router.ts 的实际路由定死, 恒 10 条, 顺序即服务端写入顺序。
 * 与旧 mock 种子的两处差异: quests 剔除 (router.ts 里根本没有这条路由, 任务系统零实现),
 * champion 更名 codex (真实路由 ROUTE_CODEX)。前端的 panelId -> {route,label,iconItemId} 映射表
 * (lib/panels.ts 的 HUB_PANEL_META) 是这份 id 的唯一消费方。
 */
export type HubPanelId =
  | 'home'
  | 'market'
  | 'shop'
  | 'jobs'
  | 'mining'
  | 'codex'
  | 'marriage'
  | 'case'
  | 'settings'
  | 'admin'

/**
 * 一个 hub 面板的**服务端权威部分** (Java 落点: HubWebUiActions)。
 *
 * route / label / iconItemId **一律不下发**。理由: 那三项是纯展示层信息, 改文案/换图标/调路由是纯前端发版
 * (不动 mod jar); 一旦服务端也存一份, 就从"前端发版即生效"变成"两端同时发版才不指错路径", 而路线 A (远端托管
 * + 浏览器缓存) 下这种不同步检测不出来 —— 旧 mock 种子把 champion 面板的 route 写成 '/champion', 而 router.ts
 * 里真实常量是 ROUTE_CODEX='/codex', 这就是已实测的漂移证据。
 * 服务端只权威"这个面板我现在能不能进", 因为它依赖的 OP / 等级 / 婚姻是服务端私有权威数据。
 */
export interface HubPanel {
  panelId: HubPanelId
  /** 该玩家此刻能否进入。当前只有 admin 一条会为 false (非 OP)。 */
  enabled: boolean
  /**
   * 锁定原因的**稳定机器码**, 仅 enabled=false 时存在 (默认 Gson + 条件性 addProperty, 故是缺席键而不是 null)。
   * 与 action 的 errorCode 是两个命名空间, 各有各的表: 前端本地化字典必须分开两张 (lib/panels.ts 的
   * PANEL_LOCK_TEXT 与 lib/errorText.ts 的 ERROR_CODE_TEXT), 撞键会让"锁定原因"与"调用失败"静默串号。
   * 当前全集只有 'NOT_OP' 一条 (Java 侧 HubLockCodes)。
   */
  lockCode?: string
}

/** hub.panels 回执。 */
export interface HubPanelsResult {
  panels: HubPanel[]
}

// ============================================================
// job.* — JobWebUiActions.java + 各职业包内的 *WebUiActions.java
// ============================================================

/** job.progress 入参 (JobWebUiActions) —— 不读 payload 任何字段。 */
export type JobProgressPayload = EmptyPayload

/**
 * job.progress 回执 (Java 落点: com.miningdim.job.JobWebUiActions)。
 * 与 player.profile 的 jobs 同形且同实现 (共用 JobProgressJson.of); 独立成一条只为省掉钱包/faucet 那 3 次 SQLite。
 */
export interface JobProgressResult {
  /**
   * 恒 8 条, 顺序 = JobId.values() 声明序。复用 PlayerJobProgressEntry (本文件上方), 不另造形状 ——
   * 那份同样没有 displayName, 职业名由前端按 `job.miningdim.<jobId>` 走 client.i18n 自解。
   */
  jobs: PlayerJobProgressEntry[]
}

/**
 * 职业面板一行数值展示的量纲 (JobStatLine.unit)。
 * percent: 0.35 显示成 35%; multiplier: 1.15 显示成 x1.15; ticks: 20 tick = 1s, 前端换算显示; flat 原样。
 */
export type JobStatUnit = 'flat' | 'percent' | 'multiplier' | 'seconds' | 'ticks' | 'blocks' | 'credit'

/** 职业被动表的一行 (服务端只发键与量纲, 中文由 client.i18n 解)。 */
export interface JobStatLine {
  /** 稳定机器码, 前端做 rowKey。 */
  key: string
  /** 翻译键, 走 useItemNames 解。 */
  labelKey: string
  value: number
  unit: JobStatUnit
}

/** job.miner.state 入参 (MinerWebUiActions) —— 不读 payload 任何字段。 */
export type MinerStatePayload = EmptyPayload

/** 矿工的一个开关位 (连锁/自动入包/自动熔炼)。 */
export interface MinerToggleState {
  /** = MinerSkill.name().toLowerCase(); 翻译键 = `skill.miningdim.miner.<skillId>` (lang 已有)。 */
  skillId: 'chain' | 'auto_collect' | 'auto_smelt'
  /** 等级是否已解锁该开关 (MinerSkills.chainUnlocked / autoCollectUnlocked / autoSmeltBaseUnlocked)。 */
  unlocked: boolean
  /** 玩家当前是否打开 (MinerChargeState.toggled; 瞬态运行态, 不持久化, 重启/登出即丢)。 */
  enabled: boolean
}

/** job.miner.state 回执 (Java 落点: com.miningdim.job.miner.MinerWebUiActions)。 */
export interface MinerStateResult {
  /** = MinerSystem.minerLevel(Player)。 */
  level: number
  /** 连锁充能池当前量 (取整, MinerChargeState.currentCharge)。由 MinerSystem 每 tick 回充, 本 action 只读不推进。 */
  charge: number
  /** 充能池容量 (MinerSkills.chainChargePool); 未解锁连锁时为 0。 */
  chargeMax: number
  /** 是否免疫挖掘疲劳 (MinerSkills.immuneToMiningFatigue, L4 里程碑)。布尔独立成字段, 不塞进 passives 伪装成 0/1。 */
  miningFatigueImmune: boolean
  /** 恒 3 条, 顺序 chain / auto_collect / auto_smelt。 */
  toggles: MinerToggleState[]
  /** 探矿解锁等级 (MinerConstants.ORE_SCAN_UNLOCK_LEVEL, 常量 3)。 */
  scanUnlockLevel: number
  /** = MinerSkills.oreScanUnlocked(level); 前端必须先看它再读下面两栏。 */
  scanUnlocked: boolean
  /** 探测半径 (格, MinerSkills.oreScanRadius); 未解锁时为 0 (真值, 不是缺省填充)。前端不得放大这个数。 */
  scanRadius: number
  /**
   * 探矿冷却剩余 tick (0 = 已就绪)。
   * 刻意不发 epoch millis: 服务端只有 game tick, 换算成服务端墙钟再与 MCEF 客户端 Date.now() 相减会吃时钟偏移;
   * 且 TPS 掉帧时 tick 与真实秒不成正比。前端收到后自己落成本地 Date.now() + remain*50。
   */
  scanCooldownRemainingTicks: number
  /**
   * 恒 6 条被动数值 (labelKey = `stat.miningdim.miner.<key>`), 顺序与 key 逐字如下:
   *  dig_speed : multiplier : MinerSkills.digSpeedMultiplier
   *  durability_save : percent : MinerSkills.durabilitySaveChance
   *  fortune_extra : flat : MinerSkills.fortuneExtraExpectancy
   *  danger_time_factor : multiplier : MinerSkills.dangerTimeFactor
   *  trap_damage_reduction : percent : MinerSkills.trapDamageReduction
   *  chain_refill_full : ticks : MinerSkills.chainRefillFullTicks
   */
  passives: JobStatLine[]
}

/**
 * job.miner.scan 入参: 空。
 *
 * 刻意不带 oreItemId —— 服务端按固定优先序 (铁>煤>钻>金>残骸) 自选第一个有命中的矿种
 * (OreScanService.scanWorld), 没有任何入参能影响这个选择; 加上"玩家选矿种"等于新开一个信息泄露面。
 * 语义与既有 C2S MinerToggleC2S 一致: 只表达"我要探矿"。
 */
export type MinerScanPayload = EmptyPayload

/** 世界方块坐标。 */
export interface WebUiBlockPos {
  x: number
  y: number
  z: number
}

/**
 * job.miner.scan 回执 (Java 落点: com.miningdim.job.miner.MinerWebUiActions)。
 *
 * 防 X 光四条硬约束全部由被复用的服务端裁决链保证, webui 层只做 JSON 化:
 * 等级门 (MinerSkills.oreScanUnlocked) -> CD 门 (MinerChargeState.cooldownReady) ->
 * OreScanService (region 门 + 半径门 + 单矿种 + ORE_SCAN_MAX_RESULTS=64 硬顶) -> startCooldown。
 * 顺序与 MinerActions.tryOreScan 逐字一致。
 */
export interface MinerScanResult {
  /** 本次命中的矿种物品 id (如 minecraft:iron_ore); 无命中时 null。 */
  oreItemId: string | null
  /** 该矿种的翻译键; 无命中时 null。 */
  oreDescriptionId: string | null
  /**
   * 命中坐标, 个数 <= 64。
   * 空数组是合法成功: 球内无可探矿、不在矿洞 region 内、半径为 0 都会得到空 —— 服务端裁决链本身
   * 不区分这三者 (OreScanService.scan 一律返回空表), 故本回执也不编造原因码。
   */
  hits: WebUiBlockPos[]
  /** 本次生效的探测半径 (格, MinerSkills.oreScanRadius)。 */
  radius: number
  /**
   * 高亮脉冲存活 tick (MinerConstants.SCAN_PULSE_TICKS, 常量 160 = 8s)。前端据此落成本地
   * Date.now() + pulseTicks*50 自行熄灭, 不等下一次 state 覆盖 (理由同 scanCooldownRemainingTicks)。
   */
  pulseTicks: number
  /** 本次已起的冷却全长 tick (= 刚 startCooldown 的量, 也就是剩余量)。 */
  scanCooldownRemainingTicks: number
}

/** job.miner.scan 的业务拒绝码 (MinerWebUiActions 的 scan handler)。 */
export type MinerScanErrorCode = 'SKILL_LOCKED' | 'SKILL_ON_COOLDOWN'

export type MinerScanErrorEnvelope = WebUiBusinessErrorEnvelope<MinerScanErrorCode>

/** job.farmer.state 入参 (FarmerWebUiActions) —— 不读 payload 任何字段。 */
export type FarmerStatePayload = EmptyPayload

/** 收购站认的那一种作物 (FarmerItems.FARMER_WHEAT)。 */
export interface FarmerCropRef {
  /** = miningdim:farmer_wheat。 */
  itemId: string
  /** = item.miningdim.farmer_wheat。 */
  descriptionId: string
}

/** 一档耕地 (FarmerCropTable.Row + FarmerTier)。 */
export interface FarmerTierRow {
  /** = FarmerTier.id(): low/medium/high/premium/supreme。 */
  tierId: string
  /** 该档对应方块的翻译键 (block.miningdim.farmer_farmland_<tierId>)。 */
  nameKey: string
  /** = FarmerTier.unlockLevel()。 */
  unlockLevel: number
  /** = FarmerTier.isUnlockedAt(level); 放置门控真源。 */
  unlocked: boolean
  /** 一株从种下到成熟的目标期望时长 (分钟, FarmerTier.growthIntervalMinutes)。 */
  growthMinutes: number
  /** 一次成熟破坏掉落的小麦株数 (FarmerTier.yieldPerHarvest)。 */
  yieldPerHarvest: number
  /** 派生吞吐: 60 / growthMinutes * yieldPerHarvest (FarmerCropTable.Row.farmerWheatPerHour)。 */
  wheatPerHour: number
}

/** job.farmer.state 回执 (Java 落点: com.miningdim.job.farmer.FarmerWebUiActions)。 */
export interface FarmerStateResult {
  /** = IJobService.level(player, FARMER)。 */
  level: number
  /**
   * 单个对象而不是数组: 全服只有一种可卖收获物 (FarmerWheatSellService 只认 FarmerItems.FARMER_WHEAT),
   * 恒长 1 的数组会诱导前端做出根本不存在的多品类选择器。
   */
  crop: FarmerCropRef
  /** 当日已售株数 (FarmerSavedData.wheatSoldToday, UTC 翻日清零), 收购曲线的档位定位量。 */
  soldToday: number
  /**
   * 收购曲线软上限 (株, FarmerConstants.WHEAT_DAILY_SOFTCAP)。**不是拒收线**: 超过后单价按
   * 0.97^超出量 指数衰减到 basePrice*0.25 地板, 仍然照收。前端文案必须写"超过后降价", 严禁写成"今日额度"。
   */
  dailySoftCap: number
  /** 未衰减时的锚价 (信用点/株, FarmerConstants.WHEAT_BASE_PRICE)。 */
  basePrice: number
  /** 衰减地板比例 (FarmerConstants.WHEAT_PRICE_FLOOR_RATIO = 0.25), 用来画"最多跌到哪"。 */
  priceFloorRatio: number
  /**
   * 下一株的收购单价 (FarmerWheatBuyback.wheatBuyPrice(soldToday + 1, basePrice), 已含曲线衰减且向下取整)。
   * 曲线深处可能整数下取整到 0 —— 那是边际收益归零的真实结果, 不是缺数据。
   */
  nextUnitPrice: number
  /** 恒 5 条, 顺序 = FarmerTier.values()。 */
  farmlandTiers: FarmerTierRow[]
}

/**
 * job.farmer.sell 入参 (FarmerWebUiActions)。
 *
 * 只有 count, **没有 slot**: 服务端按物品种类扫全背包扣 (FarmerWheatSellService.chargeWheat ->
 * Inventory.clearOrCountMatchingItems), 与槽位无关。留着 slot 会让后续维护者以为是按槽结算,
 * 而玩家的小麦本来就可能散在多个未满栈里。可卖上限由前端拿 player.inventory 镜像按 crop.itemId
 * 求和自算, 服务端不另开一条查库存的 action。
 */
export interface FarmerSellPayload {
  /** 请求卖出株数, >= 1 (缺失/非 32 位整数/<1 -> INVALID_REQUEST, params.field=count)。实际卖出取 min(库存, count)。 */
  count: number
}

/**
 * job.farmer.sell 回执 (Java 落点: com.miningdim.job.farmer.FarmerWebUiActions)。
 *
 * 写操作, 全部结算复用 FarmerWheatSellService.sell 单一入口: 先扣物后发钱、收购曲线逐株求和、
 * 经 IEconomyService.grantDaily 过全服 faucet 衰减主闸。webui 层一步都不许自己算。
 */
export interface FarmerSellResult {
  /** 实际卖出株数 (= 实际离手的小麦数)。 */
  soldCount: number
  /**
   * 实发信用点, **已过 faucet 衰减主闸**。
   * soldCount > 0 而 credited === 0 是合法结果: 收购曲线跌到地板后单株单价下取整为 0, 此时物品照扣、
   * 发币为 0。前端必须如实显示, 不许当失败。
   */
  credited: number
  /** 结算后的当日已售株数。 */
  soldToday: number
  /** 结算后下一株的单价 (曲线已下移), 给玩家看"再卖会更便宜"。 */
  nextUnitPrice: number
}

/** job.farmer.sell 的业务拒绝码 (FarmerWebUiActions 的 sell handler)。 */
export type FarmerSellErrorCode = 'INVALID_REQUEST' | 'ECONOMY_OFFLINE' | 'NOTHING_TO_SELL'

export type FarmerSellErrorEnvelope = WebUiBusinessErrorEnvelope<FarmerSellErrorCode>

/** job.chef.state 入参 (ChefWebUiActions) —— 不读 payload 任何字段。 */
export type ChefStatePayload = EmptyPayload

/**
 * ChefEffectRow.magnitudes 的量纲 (随效果种类不同, 见 ChefEffectType 各项注释):
 *  mul_x100 时长/饱食倍率 x100 (120 = x1.2); permille 千分比基点 (50 = 5%, 夹生那条是触发概率);
 *  level 1-based 效果等级; seconds 秒; count 个数 (回甘 99 = 全部); none 该效果不使用 magnitude。
 */
export type ChefEffectUnit = 'mul_x100' | 'permille' | 'level' | 'seconds' | 'count' | 'none'

/** 一档品质 (ChefQuality)。 */
export interface ChefQualityRow {
  /** = ChefQuality.id(): low/medium/high/extraordinary/radiant。 */
  qualityId: string
  /** = ChefQuality.tier(), 0-based 档位索引, 与下面 magnitudes/durationSeconds 数组下标一一对应。 */
  tier: number
  /** = ChefQuality.prefixKey(), 形如 chef.quality.prefix.low。 */
  nameKey: string
  /** = ChefQuality.maxEffects(), 一道菜最多带几个效果。 */
  maxEffects: number
  /** = ChefQuality.noFailure(), 是否零翻车 (超凡/闪耀 = true)。 */
  noFailure: boolean
  /** = ChefQuality.combatUnlocked(), 战斗向效果是否在本档解锁。 */
  combatUnlocked: boolean
  /** = ChefConfig.rawXp(quality), 达成该品质的单菜原始经验。 */
  rawXp: number
}

/**
 * 一种效果在 5 档品质下的数值行 (ChefEffectType x ChefQuality)。
 * 真实数据是 (18 种效果 x 5 档品质) 的矩阵, 压不进"一档一个值"的单列表, 故按效果成行、品质成列。
 */
export interface ChefEffectRow {
  /** = ChefEffectType.id()。 */
  effectId: string
  /** = `chef.effect.<effectId>`, lang 已有全 18 条。 */
  labelKey: string
  /** = ChefEffectType.isCombat(); 战斗向 (仅高/超凡/闪耀解锁, 一菜最多 1 个)。 */
  combat: boolean
  /** = ChefEffectType.isNegative(); 翻车负面 (仅低/中/高会掷出)。 */
  negative: boolean
  /** = ChefEffectType.isWindowed(); 窗口/周期型 (非进食瞬时结算)。 */
  windowed: boolean
  unit: ChefEffectUnit
  /** 恒 5 项 (ChefEffectMagnitude.snapshot), 下标 = ChefQuality.tier()。0 表示该档不掷出/不适用该效果 (真值)。 */
  magnitudes: number[]
  /** 恒 5 项, 下标同上。0 = 该效果无独立持续时间 (进食一次性结算)。 */
  durationSeconds: number[]
}

/**
 * job.chef.state 回执 (Java 落点: com.miningdim.job.chef.ChefWebUiActions)。
 * 全部数值每次调用实时 ChefConfig.*.get() (运营可调), 服务端不缓存, 前端更不许抄静态副本。
 */
export interface ChefStateResult {
  /** = IJobService.level(player, CHEF)。 */
  level: number
  /** = ChefQualityResolver.qualityCapForLevel(level).tier(); 当前等级能做出的最高品质档的 0-based tier。 */
  qualityCapTier: number
  /** 恒 5 条, 顺序 = ChefQuality.values()。 */
  qualities: ChefQualityRow[]
  /** 恒 18 条, 顺序 = ChefEffectType.values()。 */
  effects: ChefEffectRow[]
  /** = ChefConfig.TABLE_USE_COST_CREDIT; 调味台每道菜的信用点花费 (sink); 0 = 运营把收费关了。 */
  seasoningCostCredit: number
}

/** job.brewer.state 入参 (BrewerWebUiActions) —— 不读 payload 任何字段。 */
export type BrewerStatePayload = EmptyPayload

/** 一种酒的永久层数 (WineType + BrewBuffStore)。 */
export interface BrewerBrewEntry {
  /** = WineType.id(): brandy/vodka/gin/rum/tequila/maotai/whiskey/champagne/moonshine。 */
  wineId: string
  /** = miningdim:wine_<wineId> (BrewerItems.itemFor)。 */
  itemId: string
  /** = item.miningdim.wine_<wineId>。 */
  descriptionId: string
  /** = BrewBuffStore.layers(uuid, wineType); 该玩家该酒的永久层数 (喝闪耀酒按年份加层, 死亡清零)。 */
  permanentStacks: number
}

/** 月光满层固化的一条良性词条 (MoonshinePerk)。 */
export interface MoonshinePerkRow {
  /** = MoonshinePerk.id()。 */
  perkId: string
  /** = `brewer.moonshine.<perkId>`。 */
  labelKey: string
}

/** 一条配方的一味原料 (BrewRecipes.Ingredient)。 */
export interface BrewerRecipeInput {
  itemId: string
  descriptionId: string
  count: number
}

/** 一条配方。没有独立 recipeId —— 配方与酒类型是同一个 WineType 枚举, 两个 id 迟早分叉。 */
export interface BrewerRecipeRow {
  wineId: string
  /** 精确匹配: 投料的物品集合与计数必须与本表逐项相等, 多投/错投都不出酒。 */
  inputs: BrewerRecipeInput[]
}

/** job.brewer.state 回执 (Java 落点: com.miningdim.job.brewer.BrewerWebUiActions)。全只读。 */
export interface BrewerStateResult {
  /** = IJobService.level(player, BREWER)。 */
  level: number
  /** = BrewerConstants.MAX_LAYERS_PER_TYPE (5); 每种酒的永久层数上限。 */
  maxLayersPerType: number
  /** 恒 9 条, 顺序 = WineType.values()。 */
  brews: BrewerBrewEntry[]
  /**
   * 月光词条 (BrewBuffStore.moonshinePerks(uuid)), **玩家全局一组** (满 5 层月光时一次性固化 8 选 5),
   * 与具体是哪种酒无关。未满层前是空数组。刻意提到顶层: 挂在每行酒上是维度错误, 会渲染出
   * "伏特加带着月光词条"。
   */
  moonshinePerks: MoonshinePerkRow[]
  /** 恒 9 条, 顺序 = WineType.values()。 */
  recipes: BrewerRecipeRow[]
  /**
   * = BrewerConstants.MILLIS_PER_VINTAGE_YEAR; 陈酿速率: 多少真实毫秒累积 1 个年份
   * (当前 86400000 = 1 现实天 1 年份)。陈酿是酒窖箱按现实挂钟持续累积, 9 种酒共用同一套时钟,
   * **没有 per-配方的陈酿天数**。
   */
  millisPerVintageYear: number
}

// ============================================================
// job.agent.* — com.miningdim.job.agent.AgentWebUiActions
// (Gson serializeNulls: 全部可空字段一律显式 JSON null, 无缺席键, 故本组只用 `| null` 不用 `?:`)
// ============================================================

/** job.agent.state 入参 —— 不读 payload 任何字段。 */
export type AgentStatePayload = EmptyPayload

/**
 * 封印类别 (SealCategory 枚举名)。被动需干员 L3、机制需 L8; 两类在 SealRegistry 里各有一本 CD 账本,
 * 封被动不会锁住机制。
 */
export type AgentSealCategory = 'PASSIVE' | 'MECHANIC'

/**
 * 目标身上的一条可封候选词条 (AgentWebUiActions.entryJson)。
 *
 * decrypted=false 的行, affixId / displayKey / category **三格同时是 JSON null** —— 这是服务端在 webui
 * 回执层刻意做的脱敏 (真值送进浏览器等于在开发者工具里明码给出词条身份, 分级解密就白做了), 不是漏发。因此:
 *   1. 列表 key 只能用行下标, 不能用 affixId;
 *   2. 未解密行必须渲染成占位且不可点 —— 硬提交会被 INVALID_REQUEST (params.field='affixId') 拒掉。
 * sealable 只是 UI 预过滤, 服务端封印时会按当刻状态重算。
 */
export interface AgentAffixEntry {
  /**
   * 值是服务端 AffixDef 枚举名 (如 BURNING / SUMMON_SUPPORT), 不是 namespace:path 注册名;
   * 与精英图鉴 ChampionAffixRow.affixId 同一口径, 可按此 join。未解密行仍是 JSON null (脱敏)。
   */
  affixId: string | null
  displayKey: string | null
  category: AgentSealCategory | null
  decrypted: boolean
  sealable: boolean
  sealed: boolean
}

/**
 * 扫描快照里的一个目标 (AgentWebUiActions.targetsJson)。
 * job.agent.state 与 job.agent.scan 的目标条目**完全同形** (同一份脉冲记录的两次投影), 前端用同一个组件渲染两处。
 */
export interface AgentScanTarget {
  /** 网络实体 id; 快照一过期即作废 (见 AgentStateResult.snapshotRemainingTicks)。 */
  targetNetworkId: number
  star: number
  /** 未取整的欧氏距离 (格)。半径判定是球不是立方体。 */
  distanceBlocks: number
  /** 实体注册 id, 如 minecraft:zombie。 */
  entityTypeId: string
  /** 原版 descriptionId, 如 entity.minecraft.zombie; 中文过 client.i18n 解 (服务端不发中文)。 */
  entityNameKey: string
  /**
   * 脉冲当刻的方块坐标 (不随目标移动刷新)。
   * 精确坐标绑在第四章 L8 那一格 (Glowing 高亮), 且判据取的是**发出那次脉冲时**的干员等级 ——
   * L7 扫完立刻升到 L8, 在旧快照到期前 pos 仍是 null, 要拿坐标必须重扫。null 时只能显示 distanceBlocks。
   */
  pos: WebUiBlockPos | null
  /** 顺序即精英词条原始顺序 (集成层已过滤掉外来/纯防御词条)。 */
  entries: AgentAffixEntry[]
}

/** 干员封印权限表 (AgentSkillTable 的实时查表值; 未解锁档位是真值 0 而不是"无限制")。 */
export interface AgentSealPermissions {
  passiveUnlockLevel: number
  mechanicUnlockLevel: number
  passiveUnlocked: boolean
  mechanicUnlocked: boolean
  /** 可封的最高目标星级 (= 干员等级)。L<3 时是真值 0 (未解锁)。 */
  maxSealableStar: number
  /** 被动窗口/CD 秒数; L<3 恒 0。 */
  passiveWindowSeconds: number
  passiveCooldownSeconds: number
  /** 机制窗口/CD 秒数; L<8 恒 0。 */
  mechanicWindowSeconds: number
  mechanicCooldownSeconds: number
  /** 读 SealRegistry 活账本 (与键位路径同一本); 0 = 就绪。 */
  passiveCooldownRemainingTicks: number
  mechanicCooldownRemainingTicks: number
  /** 槽位数; L<3 恒 0。双槽只在 (目标 8 星+ 且干员 L9+) 时为 2。 */
  slotsDefault: number
  slotsVsStar8Plus: number
  secondSlotUnlockLevel: number
}

/**
 * 干员悬赏**权限表**, 不是悬赏实例列表。
 * 全工程没有"玩家已接的悬赏"这个存储 (BountyDefinition/BountyProgress 是零构造点的逻辑骨架),
 * 故面板上的悬赏板这一屏本轮无数据可渲染, 只能显示这张权限一览。
 */
export interface AgentBountyPermissions {
  dailySlots: number
  weeklySlots: number
  weeklyUnlocked: boolean
  weeklyUnlockLevel: number
  maxBountyStar: number
  worldBossUnlocked: boolean
  worldBossUnlockLevel: number
  /** 单位青辉石; Cap 恒 50 (AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP), 跨 ISO 周清零。 */
  weeklyAzureGranted: number
  weeklyAzureCap: number
  /**
   * F017/F078: 悬赏接取/进度推进/发奖三个环节尚未上线, 恒为 false。以上字段是真实的等级门槛预览 (等级查表),
   * 不是"可接取的悬赏列表"——面板必须据此字段诚实展示"权限预览"而非暗示玩家能接单, 严禁把它当纯装饰位忽略。
   */
  available: boolean
}

/**
 * job.agent.state 回执 (AgentWebUiActions.STATE)。
 * 纯只读: 绝不烧 CD、不发脉冲、不写快照 —— targets 只是上一次脉冲的投影, 且服务端已替前端做完过期判定
 * (过期即空数组), 前端不必自己算过期。
 */
export interface AgentStateResult {
  /** 1-10 (框架对任何玩家默认返 1)。 */
  level: number
  /**
   * = AgentSealSeam.isBound()。false = Champions 未加载, 扫描读不到真精英词条。
   * 此时面板必须显示"扫描离线", 而不是渲染一张空的候选表 —— 两者对玩家是完全不同的意思。
   */
  scanOnline: boolean
  /**
   * 本次生效的有效半径 (格)。L1-L9 直取第四章范围列 (64/96/128/160/200/256/320/384/448);
   * L10 表值是哨兵 -1 (跨区块), 服务端已解析成 max(448, 服务端视距区块 x 16) 的真实数字, 前端拿到的永远是正数。
   */
  scanRadiusBlocks: number
  scanCrossChunk: boolean
  /** = scanPulseCdSeconds(level) x 20; L1=1200 L5=940 L10=600。 */
  scanPulseCooldownTicks: number
  /**
   * 剩余冷却 tick (20 tick/s), 0 = 就绪, 永不为负。
   * 刻意不发绝对时刻: 服务端手里只有 game tick, 转成墙钟让 MCEF 拿 Date.now() 去减既吃时钟偏移、
   * 又在 TPS 掉帧时失真。前端在收到那一刻自己落成本地 epoch 再倒计时 (与 job.miner.* 同纪律)。
   */
  scanCooldownRemainingTicks: number
  /** 与上一字段按设计恒等 (同一 pulseTick 派生); 0 = 无有效快照。归零即 targets 的封印按钮必须变灰。 */
  snapshotRemainingTicks: number
  /**
   * 一次脉冲最多下发 8 个目标 (MAX_SCAN_TARGETS, 回执体积硬上限)。
   * **语义比字面宽**: Java 是在检视下一个候选"是不是精英"之前就置位的, 真正含义是"球内还有未检视的活体实体"
   * (448 格球内几乎必然有普通怪/村民/其它玩家)。故底部文案只能写"仅显示最近 8 个",
   * 严禁写成"还有更多精英未显示"。
   */
  truncated: boolean
  /** 按距离升序, 最多 8 条; 快照过期时是空数组。 */
  targets: AgentScanTarget[]
  seal: AgentSealPermissions
  bounty: AgentBountyPermissions
  /** 倍率 1.0-3.0 (小数原样发, 未取整)。 */
  enhancedRewardMultiplier: number
  /** 整数百分比 5-15。 */
  damageBonusPercent: number
  /** 入职标志: false = 从未做过特勤活计, 加强奖励与对精英伤害放大一分不吃。 */
  activeAgent: boolean
}

/**
 * job.agent.scan 入参 —— 刻意不收目标 id 与半径: 那两个是服务端的门 (与 job.miner.scan 同纪律)。
 */
export type AgentScanPayload = EmptyPayload

/**
 * job.agent.scan 回执 (AgentWebUiActions.SCAN)。
 *
 * 写操作: 成功一次即烧掉整轮 CD 并覆盖旧快照; 扫空 (球内一只精英也没有) 同样烧 CD。
 * 唯一不烧 CD 的是 scanOnline=false (接缝未绑定) —— 此时 targets 为空且两个 RemainingTicks 都是 0, 可立即重试。
 */
export interface AgentScanResult {
  agentLevel: number
  radiusBlocks: number
  crossChunk: boolean
  pulseCooldownTicks: number
  /** false = Champions 未加载: 本次是免费空转, 没烧 CD 也没写快照。 */
  scanOnline: boolean
  /** 语义同 AgentStateResult.truncated (同名同义)。 */
  truncated: boolean
  /** 成功回执里这两栏都等于 pulseCooldownTicks。 */
  scanCooldownRemainingTicks: number
  snapshotRemainingTicks: number
  targets: AgentScanTarget[]
}

/**
 * job.agent.scan 的业务拒绝码。
 * SKILL_ON_COOLDOWN 与矿工探矿**复用同一条码**, params = {skill:'tactical_scan', remainingTicks:'<tick>'},
 * 前端按 params.skill 区分是哪一个技能在冷却。
 */
export type AgentScanErrorCode = 'SKILL_ON_COOLDOWN'

export type AgentScanErrorEnvelope = WebUiBusinessErrorEnvelope<AgentScanErrorCode>

/** job.agent.seal 入参 (AgentWebUiActions.SEAL)。 */
export interface AgentSealPayload {
  /** 必须取自当前有效快照; 快照过期后该 id 立即作废。 */
  targetNetworkId: number
  /**
   * 必须是**已解密**行的 affixId; 未解密行的该字段是 null, 前端根本不该让它可点。
   * 格式见 {@link AgentAffixEntry.affixId} (AffixDef 枚举名, 如 BURNING)。
   */
  affixId: string
}

/**
 * job.agent.seal 的九态结果码, 逐字取自 Java AgentSealSeam.SealOutcome (顺序即声明序)。
 *
 * OK                   占槽并真改成功 (ok 只有这一态为真)
 * NOT_BOUND            Champions 未加载 (本路径实际不可达 —— 未加载时写不出快照, 快照门会先拒; 字典仍应备一条)
 * NO_TARGET            目标非本工程盖章精英 / 已离场 / 网络 id 失效
 * AFFIX_NOT_SEALABLE   外来词条 / 纯防御词条 / 列表里已无该词条 / 真改未生效
 * CATEGORY_LOCKED      被动需 L3、机制需 L8
 * STAR_TOO_HIGH        目标星级 > 可封星级 (= 干员等级)
 * ALL_SLOTS_OCCUPIED   该精英的槽已满 (槽是精英自身容量, 不随在场人数涨)
 * AFFIX_ALREADY_SEALED 该词条已被任意干员封印中 (互斥, 不延长)
 * ON_COOLDOWN          该干员该类别仍在封印 CD 内
 */
export type AgentSealOutcomeCode =
  | 'OK'
  | 'NOT_BOUND'
  | 'NO_TARGET'
  | 'AFFIX_NOT_SEALABLE'
  | 'CATEGORY_LOCKED'
  | 'STAR_TOO_HIGH'
  | 'ALL_SLOTS_OCCUPIED'
  | 'AFFIX_ALREADY_SEALED'
  | 'ON_COOLDOWN'

/**
 * job.agent.seal 回执 (AgentWebUiActions.SEAL)。
 *
 * 服务端不下发中文文案 (专用服务端不加载 lang), 前端必须按 outcomeCode 九态自建文案字典。
 * 两道**前置门**不走 outcomeCode 而是抛 INVALID_REQUEST, 前端要单独识别成两句话:
 *   params.field='targetNetworkId' -> "请先扫描" (没扫过 / 快照已过期 / 该目标不在快照里)
 *   params.field='affixId'         -> "该词条尚未解密, 点不了" (词条不在快照里 / 未解密 / 空白)
 * 两者的 params 除 field 外**必带 value** (回显被拒的入参, 超 64 字符截断并追加 "...")。
 */
export interface AgentSealResult {
  /** 只有 outcomeCode === 'OK' 时为 true。 */
  ok: boolean
  outcomeCode: AgentSealOutcomeCode
  targetNetworkId: number
  /** 原样回显请求里的 affixId; 格式见 {@link AgentAffixEntry.affixId}。 */
  affixId: string
  category: AgentSealCategory
  /** 该等级该类别的公开表值; 失败时同样发 (与占槽时用的是同一张表同一对入参)。 */
  windowSeconds: number
  cooldownSeconds: number
  /** 读 SealRegistry 活账本: 成功后是刚起的整轮 CD, 被 ON_COOLDOWN 拒时是真实剩余量。单位 tick。 */
  categoryCooldownRemainingTicks: number
}

// ============================================================
// job.munitions.state / job.blueprints — com.miningdim.job.munitions.MunitionsWebUiActions
// (Gson serializeNulls; detail 子对象里的键是"按台种类条件写入"的缺席键, 与外层显式 null 语义不同)
// ============================================================

/** job.munitions.state 入参 —— 不读 payload 任何字段。 */
export type MunitionsStatePayload = EmptyPayload

/**
 * 一台机器的特有状态。键随 stationId 变, 前端必须按 stationId 分支取值; pos=null 时本对象是空的 `{}`。
 *
 * 两个 `?: string | null` 是双重可选: 键本身可能不存在 (那台机器没有这个概念),
 * 存在时值也可能是 null (军火台未选口径 / 装配台没放图纸)。
 */
export interface MunitionsStationDetail {
  /** 军火台: 选中口径 (MunitionsCaliber 枚举名小写, 如 'pistol'/'big_pistol'); 未选为 null。 */
  caliberId?: string | null
  /** 军火台: 缓冲内已产发数。**产出权威看这一栏**: TACZ 未装时输出槽恒空而缓冲照常涨。 */
  bufferedRounds?: number
  bufferCap?: number
  locked?: boolean
  refineUnlocked?: boolean
  /** 军火台: 这台实际按几级算产能 (台档会把台主等级压到该档上限)。 */
  effectiveLevel?: number
  continuousCrafting?: boolean
  /** 冲压机: 当前选中平台 (GunsmithPlatform.id, 如 'ar')。 */
  platformId?: string
  /** 冲压机: 当前选中部位 (GunsmithPressPart.id, 如 'core')。 */
  partId?: string
  /** 冲压机: 当前选中品质 (GunsmithPartQuality.id, 如 'common')。 */
  qualityId?: string
  /** 冲压机: 当前选中变体 (GunsmithPartVariant.id, 如 'basic')。 */
  variantId?: string
  /**
   * 装配台: 图纸槽里的图纸 (GunsmithBlueprint.templateId, 如 'ak47'); 没放图纸为 null。
   * 槽里放的是老模板物品 (ModMunitionsItems.M4_ASSEMBLY_TEMPLATE) 时同样会认出来, 值是 'm4a1'。
   */
  blueprintId?: string | null
}

/**
 * 三台机器之一 (MunitionsWebUiActions.stationRow + benchJson/pressJson/assemblyJson)。
 * 数值一律经各自 BE 的 ContainerData 读, 与原生 GUI 同一份权威快照。
 */
export interface MunitionsStation {
  /** 'munitions_bench' | 'gunsmith_press' | 'gunsmith_assembly_bench' (= 方块注册名)。 */
  stationId: string
  /** 方块翻译键; 军火台扫到时换成实际那一档的键 (六档军火台是六个注册名)。 */
  nameKey: string
  /**
   * **null = "这个半径内没扫到"而不是"没造过"** —— 全工程没有"玩家 -> 台位坐标"注册表。
   * 文案必须写"附近未找到", 想说"造了几台"请用 MunitionsStateResult.benchesPlaced。
   */
  pos: WebUiBlockPos | null
  running: boolean
  /**
   * 已进行 tick。三种取值都要处理:
   *   null  没扫到, 或**装配台**(它没有 ContainerData, 已进行 tick 服务端读不出) —— running=true 时应画
   *         不定态进度条 (来回跑), 不要当 0% 画;
   *   0     军火台扫到了但未选口径 (BE 返 0), 此时 requiredTicks 也是 0, 直接相除会得到 NaN;
   *   正数  真进度。注意军火台侧 ContainerData 是按秒过线的, 还原回 tick 只能到 20 tick (1 秒) 的格点,
   *         最多丢 19 tick —— 不是逐 tick 精度。
   */
  progressTicks: number | null
  /** 一次加工所需 tick; 没扫到时 null, 军火台未选口径时 0。装配台是真值常量 160。 */
  requiredTicks: number | null
  /** 输出槽物品注册名; 空槽为 null。 */
  outputItemId: string | null
  /** 输出槽数量; 空槽为 0。 */
  outputCount: number
  detail: MunitionsStationDetail
}

/**
 * job.munitions.state 回执 (MunitionsWebUiActions.STATE)。
 * 纯只读: 绝不调 settleForOwner/onAccess —— 面板刷新若推进产线, 开着平板就成了产能加速器。
 */
export interface MunitionsStateResult {
  /** 军火商职业等级 (JobServices.level, 1-10)。 */
  level: number
  /** 该等级允许拥有的军火台总数上限 (MunitionsLevels.tableCount, 实时 config)。 */
  benchCap: number
  /** 全局已放置军火台数 (MunitionsSavedData 按 UUID 计, 跨维度权威; 与 stations 里扫到几台无关)。 */
  benchesPlaced: number
  /**
   * 就近搜索半径 (格)。实现是"以玩家所在区块为心、dx/dz in [-4,4] 的 9x9 区块盒", 只有下界是保证的:
   * 欧氏 64 格内的台位必被扫到 (上界单轴最远 79 格、对角约 112 格)。
   * 故它只能用来写"没扫到 = 64 格内没有", **不能**反过来假设"扫到的一定在 64 格内"。
   */
  searchRadiusBlocks: number
  /**
   * 枪匠冲压/装配总开关 (MunitionsConfig.GUNSMITH_ENABLED, **默认 false**)。
   * 关着时装配台点开工只会被拒 —— 面板必须先把这件事讲清楚, 否则玩家会以为是 bug。
   */
  gunsmithEnabled: boolean
  /** 恒 3 行且顺序恒定: [0]=军火台 [1]=冲压机 [2]=装配台。 */
  stations: MunitionsStation[]
}

/** job.blueprints 入参 —— 不读 payload 任何字段。 */
export type BlueprintsPayload = EmptyPayload

/** 图纸必需的一个部位。 */
export interface BlueprintPart {
  /** GunsmithPressPart.id, 如 'core'/'barrel'/'slide'。 */
  partId: string
  /** 部位翻译键 'gunsmith.part.<id>' (与零件 tooltip 同一批键)。 */
  labelKey: string
  /** 恒 1 (装配台部位槽 getSlotLimit=1)。 */
  count: number
}

/** 一张枪匠图纸 (GunsmithBlueprint 枚举一项)。 */
export interface Blueprint {
  /** 稳定 id = GunsmithBlueprint.templateId, 如 'm4a1'/'ak47'/'m1911'。 */
  blueprintId: string
  /** 成品枪 id, 恒 tacz 命名空间, 如 'tacz:m4a1'。 */
  gunId: string
  /** 图纸物品名的套壳键 'item.miningdim.gunsmith_blueprint.name', 带一个 %s 占位, 实参是 gunNameKey。 */
  nameKey: string
  /**
   * 枪名键 'tacz.gun.<templateId>.name'。**属 TACZ 的 lang**, 未装 TACZ 的客户端解不出 ——
   * 这是真实情况, 服务端没有伪造本 mod 的替代名。解不出时前端只能退回显示 gunId。
   */
  gunNameKey: string
  /** 平台 id (GunsmithPlatform.id: ar/ak/pistol/bullpup/marksman/sniper/machine_gun)。 */
  platformId: string
  /** 平台翻译键 'gunsmith.platform.<id>'。 */
  platformLabelKey: string
  /**
   * 该图纸必需的部位 (4-6 个)。顺序是 GunsmithPressPart 的**声明序** (构造里做了 EnumSet.copyOf),
   * 不是 GunsmithPlatform 那边 List 声明的顺序 —— 将来加 marksman 图纸时会与冲压机 GUI 的部位顺序不一致。
   */
  requiredParts: BlueprintPart[]
}

/**
 * job.blueprints 回执 (MunitionsWebUiActions.BLUEPRINTS)。纯静态表, 与玩家状态无关, 不分页
 * (枚举编译期定长, 静态表分页只会让前端多一个永远只有一页的翻页器)。
 *
 * 零件的 itemId/descriptionId 提到顶层只发一份: 195 种枪匠零件全注册在同一个 miningdim:gunsmith_part
 * 之下靠 NBT 区分, 在 50 余个部位行里逐行重复是纯浪费。
 */
export interface BlueprintsResult {
  /** 顺序 = GunsmithBlueprint.values() 声明序, 今 9 款。 */
  blueprints: Blueprint[]
  /** = blueprints.length (前端做契约自检用)。 */
  blueprintCount: number
  /** 恒 'miningdim:gunsmith_part'。 */
  partItemId: string
  /** 恒 'item.miningdim.gunsmith_part' (取图标/兜底名用)。 */
  partDescriptionId: string
  /** 与 MunitionsStateResult.gunsmithEnabled 同一个 config 值。 */
  gunsmithEnabled: boolean
}

// ============================================================
// job.engineer.state — com.miningdim.job.engineer.EngineerWebUiActions
// (默认 Gson: 回执里没有任何 null 值, 两个"仅闪耀档存在"的字段是缺席键, 故一律 `?:` 不用 `| null`)
// ============================================================

/** job.engineer.state 入参 —— 不读 payload 任何字段。 */
export type EngineerStatePayload = EmptyPayload

/**
 * 纳米板修复量的量纲。
 * **repairValue 必须连本字段一起读**: 同一个 300 在极品档是"最大耐久的 30%", 在低级档是"300 点耐久",
 * 只画数字就是骗人。
 */
export type NanoRepairUnit = 'durability' | 'permille'

/** 护甲特效数值行的量纲。 */
export type EngineerStatUnit = 'percent' | 'ticks' | 'count' | 'flat'

/** 护甲特效的一行实时数值 (EngineerWebUiActions.stat)。 */
export interface EngineerStatLine {
  /** 数值键 (如 sharedCdTicks/reviveHealthPct/maxCharges)。 */
  key: string
  /** 翻译键 'stat.miningdim.engineer.<key>' —— **lang 条目尚未落地**, 现在解出来就是键本身。 */
  labelKey: string
  /**
   * 原样下发的裁决值, 未取整。注意实现把 IntValue 的 config 全部拓宽成 double,
   * 故 unit='ticks'/'count' 的值在 JSON 里也是 36000.0 / 5.0 这种带小数点的形态 ——
   * 前端不得对它做 Number.isInteger 断言或字符串直显。
   */
  value: number
  unit: EngineerStatUnit
}

/** 一档纳米板 (NanoTier + 默认档表)。 */
export interface NanoTierRow {
  /** 档 id (NanoTier 枚举名小写: low/medium/high/superior/transcendent/radiant)。 */
  tierId: string
  /** 'tier.miningdim.nano.<tierId>' (与生产台 GUI / 套件 tooltip 同一批键, lang 已存在)。 */
  labelKey: string
  /** 稳定序号 0-5。 */
  index: number
  /** 该档解锁所需等级 (1/3/5/7/9/10)。 */
  unlockLevel: number
  unlocked: boolean
  /** 'miningdim:nano_plate_<tierId>'。 */
  plateItemId: string
  plateDescriptionId: string
  /** 单板矿石消耗 (4 铁/5 金/3 钻/1 下界合金/1/2)。 */
  oreCost: number
  /** 单次成功产出板数 (仅极品档为 2)。 */
  outputCount: number
  produceTicks: number
  /** 单档原始经验 (框架再过每日衰减)。 */
  rawXp: number
  /** 该档修复时是否可能掷出护甲特效 (高级板起 true)。 */
  canRollEffect: boolean
  repairUnit: NanoRepairUnit
  /**
   * 修复量: durability 档是绝对耐久点数 (100/250/600); permille 档是最大耐久的千分比 (300/650/1000)。
   * permille 是服务端用常量 1000 喂 NanoRepair.repairAmount 反解出来的 floor 值, 运营把比例调成三位以上
   * 小数时会丢掉不足 1 个千分点的部分 —— 它是展示值, 不是精确比例。
   */
  repairValue: number
  /** 是否必定重掷特效 (仅闪耀档 true)。 */
  guaranteedEffect: boolean
  /** **仅闪耀档存在**: 单次产出成功率 (0..1, 默认 0.5)。 */
  successChance?: number
  /** **仅闪耀档存在**: 失败返还的下界合金碎片数 (默认 1)。 */
  failRefundScrap?: number
}

/**
 * 一个护甲特效 (NanoEffect)。
 * 四个特效**没有各自的等级门**: NanoRepair.rollEffect 是四选一等概率, 它们在"最低的那个能掷特效的档"
 * (默认 = 高级板 L5) 同时解锁, 故四个 unlocked 恒同步翻转 —— 面板可以只显示一句"L5 起可掷出"。
 */
export interface ArmorEffectRow {
  /** 特效 id (reshape/vitality/shield/totem)。 */
  effectId: string
  /** 'effect.miningdim.nano.<effectId>' —— **lang 条目尚未落地**。 */
  labelKey: string
  /** 'effect.miningdim.nano.<effectId>.desc' —— **lang 条目尚未落地**。 */
  descriptionKey: string
  /** 四个特效同值 (= EngineerStateResult.effectUnlockLevel)。 */
  unlockLevel: number
  unlocked: boolean
  /** 该特效的实时数值行 (图腾共享 CD/复活血量、护盾次数/免疫窗、重塑每周期耐久等)。 */
  stats: EngineerStatLine[]
}

/**
 * job.engineer.state 回执 (EngineerWebUiActions.STATE)。
 *
 * 决策 J5: 纳米校准 QTE 的游标/绿区/相位一个字段都不下发 (那是必须在游戏内做的操作, 面板里给出来等于开挂);
 * 但校准的**结果面** (qualityBonusThreshold / qualityBonusPlateChance) 属数值预览, 照发。
 */
export interface EngineerStateResult {
  /** 铸甲师职业等级 (旧存档兼容 id 仍是 engineer)。 */
  level: number
  /** 恒 'job.miningdim.engineer' —— 中文 lang 里就是"铸甲师"。 */
  jobNameKey: string
  /** 当前等级已解锁的最高档 id。 */
  unlockedTierId: string
  /** 护甲特效的解锁等级 (= 最低可掷特效档的解锁等级, 默认档表 = 5)。 */
  effectUnlockLevel: number
  /**
   * 纳米反应堆 (图腾) 人级共享 CD 剩余 tick; 0 = 已就绪。与 NanoReactorHandler 读同一个字段
   * (JobProgress.nanoReactorCdEndTick) 且复用同一判据, 面板说能救而实战不触发即两条路径分叉。
   */
  reactorCooldownRemainingTicks: number
  /** CD 全长 tick (画进度条的分母, 默认 36000 = 30 分钟)。 */
  reactorSharedCdTicks: number
  /** 恒 6 档, 顺序 = NanoTier 声明序 (低/中/高/极品/超凡/闪耀)。 */
  tiers: NanoTierRow[]
  /** 恒 4 个, 顺序 = NanoEffect 声明序 (reshape/vitality/shield/totem)。 */
  armorEffects: ArmorEffectRow[]
  /** 校准命中数达到几次后才有额外产板机会 (默认 4)。 */
  qualityBonusThreshold: number
  /** 达阈值后额外 +1 板的概率 (0..1, 默认 0.5)。 */
  qualityBonusPlateChance: number
  /** 用自己产的板修甲的修复经验加成 (0.5 = +50%)。 */
  ownPlateRepairXpBonus: number
}

// ============================================================
// job.tarot.* — com.miningdim.job.tarot.TarotWebUiActions (Gson serializeNulls)
// ============================================================

/** job.tarot.state 入参 —— 不读 payload 任何字段。 */
export type TarotStatePayload = EmptyPayload

/**
 * 塔罗品质 id (TarotQuality.id(), 等级门依次 L1/L3/L5/L8/L10)。
 * 同一张大阿卡纳可同时持有多张不同品质的实体卡牌 —— 品质是**牌的属性**, 不是牌与品质一对一。
 */
export type TarotQualityId = 'r' | 'sr' | 'ssr' | 'ur' | 'shiny'

/** 卡包种类 (PackKind.id())。闪耀包收 AZURE, 另两种收 CREDIT。 */
export type TarotPackKind = 'common' | 'advanced' | 'shiny'

/** 非闪耀牌的冷却分类 (TarotCooldownManager.Category)。 */
export type TarotCooldownCategory = 'utility' | 'buff' | 'combat'

/** 一档品质 (TarotQuality)。 */
export interface TarotQualityRow {
  qualityId: TarotQualityId
  /** 'tooltip.miningdim.tarot.quality.<qualityId>'。 */
  nameKey: string
  /** 四档缩放索引 0..3; shiny 恒 -1 (它不走四档, 走签名大招)。 */
  tierIndex: number
  requiredLevel: number
  /**
   * 本人当前等级能否打出该品质 (纯等级比较 TarotLeveling.canUseQuality)。
   * **testMode=true 时本栏不成立**: TarotPlayHandler 的等级闸门带 !testMode 前置, 测试模式下任何等级
   * 都打得出任何品质。此时它只表示"正式环境下能否打出"。
   */
  usable: boolean
  rawXp: number
}

/** 四个"满 CD 时长" (tick), **不是剩余量** —— 剩余量上游没有只读入口 (TarotCooldownManager 的三张表全私有)。 */
export interface TarotCooldownTicks {
  gcd: number
  utility: number
  buff: number
  combat: number
}

/** 一张大阿卡纳在本玩家处的持有状况。 */
export interface TarotDeckEntry {
  cardId: number
  arcanaId: string
  /** 'tooltip.miningdim.tarot.arcana.<arcanaId>'。 */
  nameKey: string
  /**
   * 长度恒 5, 下标 = TarotQuality ordinal (r/sr/ssr/ur/shiny)。
   * **只数 ownerUUID == 本人的牌** —— 别人的牌拿在手上也打不出 (TarotPlayHandler 第一道闸门就是 owner 校验)。
   */
  ownedByQuality: number[]
  /** = ownedByQuality 之和; 0 = 未持有。表达"能不能打"。 */
  owned: number
  /**
   * 背包里同 cardId 的全部可读牌 (不论绑定谁) = 开包判"重复转碎片"的口径。
   * 表达"再开出来会不会变碎片" —— 与 owned 是两件事, 前端要用两句话分别讲。
   */
  inInventory: number
  /** cardDataLoaded=false (datapack 重载中或失败) 时为 null —— 发 0 会被画成零冷却。 */
  cooldownCategory: TarotCooldownCategory | null
  /** 同上, cardDataLoaded=false 时为 null。 */
  shinyCooldownTicks: number | null
}

/** 一种卡包的定价 (TarotPackItem + PackKind)。 */
export interface TarotPackRow {
  packKind: TarotPackKind
  itemId: string
  /** 'item.miningdim.tarot_pack_<kind>'。 */
  nameKey: string
  currency: WebUiCurrency
  unitPrice: number
}

/**
 * job.tarot.state 回执 (TarotWebUiActions.STATE)。全只读: 不占冷却、不动碎片、不改计数。
 *
 * 刻意不发钱包余额: player.wallet / player.profile 已经发了, 两处各发一份必然漂移。
 * 前端算"买不买得起"用 wallet 的余额与本页的 unitPrice 自行比对。
 */
export interface TarotStateResult {
  level: number
  /** TarotConfig.TEST_MODE: 开着时买包免费且不计日限, 价格栏必须据此改写文案。 */
  testMode: boolean
  /** 背包 (主背包 + 副手) 里的塔罗碎片总数; 末影箱/盔甲位不计。 */
  shards: number
  /** 攒够多少张可确定性兑换一张指定 SSR。 */
  shardExchangeCost: number
  /** 开包重复牌返几张碎片。 */
  duplicateShardRefund: number
  /** 恒 5 行, 顺序 = TarotQuality 声明序。 */
  qualities: TarotQualityRow[]
  cooldownTicks: TarotCooldownTicks
  /** 牌效 datapack 是否已加载完; false 时 deck 行的两个 CD 字段是 null。 */
  cardDataLoaded: boolean
  /** 恒 22 行, 按 cardId 升序 (即 TarotArcana 声明序)。 */
  deck: TarotDeckEntry[]
  /** 恒 3 行 (普通/高级/闪耀)。 */
  packs: TarotPackRow[]
  /**
   * 当日**已获取**的卡包数 —— 高级包链式派生出来的免费包同样占额度。
   * UI 文案写成"今日购买"会与玩家体验对不上 (开一个高级包可能凭空多消耗几个额度)。
   */
  packsBoughtToday: number
  packDailyLimit: number
  packsRemainingToday: number
  /** 高级包保底进度: streak >= threshold 时下一个高级包首张保底 SSR。 */
  advancedPityStreak: number
  advancedPityThreshold: number
}

/** job.tarot.buyPack 入参 (TarotWebUiActions.BUY_PACK)。 */
export interface TarotBuyPackPayload {
  /** 必填。common/advanced 收 CREDIT, shiny 收 AZURE。 */
  kind: TarotPackKind
  /** 必填, 整数, 域 [1,64]。 */
  count: number
}

/**
 * job.tarot.buyPack 回执 (TarotWebUiActions.BUY_PACK)。
 *
 * 买到的是**卡包物品**, 不是卡牌: 服务端根本不存在"买即开"的入口 (普通/高级包是右键就地开并走
 * TarotPackRevealS2C 演出, 闪耀包要开原生 GUI 自选一张 SSR)。故回执里没有也不会有 drawn / fragmentsGained,
 * 开包动画只能挂在现有客户端揭牌流程上。
 */
export interface TarotBuyPackResult {
  packKind: TarotPackKind
  itemId: string
  nameKey: string
  /** 实际购得的卡包个数 = 入参 count。 */
  count: number
  currency: WebUiCurrency
  unitPrice: number
  /** **实扣额**; 测试模式下恒 0 (免费), 不等于 unitPrice * count —— 前端不得自己乘。 */
  totalPrice: number
  testMode: boolean
  packsBoughtToday: number
  /**
   * 剩余可购。**testMode=true 时本栏不可信**: TarotPackService.buy 在测试模式下把它直接置成 cap,
   * 会与同回执的 packsBoughtToday 分叉; 此时应改读 job.tarot.state 的同名字段。
   */
  packsRemainingToday: number
  packDailyLimit: number
}

/**
 * job.tarot.buyPack 的业务拒绝码。
 *   INVALID_REQUEST     kind/count 缺失或域外 (params.field, 域外再带 params.value)
 *   INSUFFICIENT_FUNDS  余额不足 (params: currency/totalPrice/packKind), 未扣款未发包
 *   RATE_LIMITED        撞每日上限 (params: scope='tarot_pack_daily'/requested/remainingToday/dailyLimit)
 *                       —— 这条是**暂借**的码 (它的文档钉在"开箱请求过快"), 待服务端补 DAILY_LIMIT_REACHED
 *   ECONOMY_OFFLINE     经济子系统未注册且本次真会扣款 (非测试模式且总价 > 0), 无 params
 */
export type TarotBuyPackErrorCode =
  | 'INVALID_REQUEST'
  | 'INSUFFICIENT_FUNDS'
  | 'RATE_LIMITED'
  | 'ECONOMY_OFFLINE'

export type TarotBuyPackErrorEnvelope = WebUiBusinessErrorEnvelope<TarotBuyPackErrorCode>

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
  /** NBT 变体件的贴图选择码。见 ItemVariantFields。 */
  customModelData?: number
  /** NBT 变体件的显示名结构。见 ItemVariantFields。 */
  nameParts?: ItemNamePart[]
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

/**
 * market.history 入参 (MarketActions.HISTORY)。
 * 名字归位: 前端曾用 market.transactions 这个假名避开与本条撞名, W2 接线后整体并入本条, 不存在第二个流水 action。
 */
export interface MarketHistoryPayload {
  /** 缺省 0 (MarketActions.DEFAULT_PAGE)。offset = page * pageSize。 */
  page?: number
  /** 缺省 20 (MarketActions.DEFAULT_PAGE_SIZE); 服务端无上限钳制, 与 market.list 同纪律。 */
  pageSize?: number
}

/**
 * 一条成交流水 (transactions 表一行的对外投影)。
 *
 * 无 customModelData / nameParts: transactions 表只存 item_id, 从不存成交物的 NBT (MiningSchema),
 * 因此枪匠零件这类 NBT 变体件在流水里只能同名同图标。这是数据层缺口而非契约遗漏 —— 要补必须先给表加 NBT 列,
 * 在此留一个永远填不上的键只会让人以为服务端某天会填上。
 */
export interface MarketTransaction {
  /** transactions.id。Java long -> number。 */
  txnId: number
  /** 成交时对应的挂单 id (该挂单可能已 SOLD 或被拆分)。Java long -> number。 */
  listingId: number
  /** 本条流水里"我"的角色。取值沿用 MarketActions.HISTORY 既有注释的 buy / sell。 */
  role: MarketTxnRole
  itemId: string
  /** 翻译键; 物品已从注册表移除时回退为 itemId 本身 (与 MarketListing.descriptionId 同一取法)。 */
  descriptionId: string
  count: number
  /** Java long -> number。 */
  unitPrice: number
  /** = unitPrice * count, 表内直存。Java long -> number。 */
  total: number
  /** 该笔流水记录的手续费。现恒为 0: 费已在挂单时由 market.place 收过, 写入流水时固定传 0。 */
  fee: number
  /** 对手方 UUID 文本 (role=buy 时是卖家, role=sell 时是买家); 表内直存, 恒有值。 */
  counterpartyUuid: string
  /**
   * 对手方玩家名; **对手方当前离线时为 null**。transactions 表没有 listings.seller_name 那样的名字快照列,
   * 服务端只有在线 PlayerList 解析这一条路径。离线时不编名也不拿 UUID 冒充名字 —— 前端拿 counterpartyUuid
   * 自行降级展示。
   */
  counterpartyName: string | null
  /** 成交时刻 epoch millis (transactions.created_at)。Java long -> number。 */
  createdAt: number
}

/** MarketActions.HISTORY 既有注释的取值域 (不是 buyer/seller)。 */
export type MarketTxnRole = 'buy' | 'sell'

/**
 * market.history 回执 (MarketActions.HISTORY)。
 * 带 total —— market.list 缺 total 导致前端算不出总页数是已知缺陷, 本条按 admin.listItems 的
 * page/pageSize/total 三件套抄, 不另造命名。用 GSON_NULLS 序列化: counterpartyName 恒有键 (离线时值为 JSON null)。
 */
export interface MarketHistoryResult {
  transactions: MarketTransaction[]
  page: number
  pageSize: number
  /** 该玩家 (买或卖任一侧命中) 的流水总条数, 供前端算总页数。 */
  total: number
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

/** market.feePreview 入参 (MarketActions.FEE_PREVIEW)。与 market.baseValue 同一索引口径 (物品注册 id)。 */
export interface MarketFeePreviewPayload {
  /** 必填; 缺失自然抛。 */
  itemId: string
  /** 必填, 必须 >= 1; <= 0 服务端抛 INVALID_REQUEST (params: field=unitPrice / value)。 */
  unitPrice: number
  /** 必填, 必须 >= 1; <= 0 服务端抛 INVALID_REQUEST (params: field=count / value)。 */
  count: number
}

/**
 * market.feePreview 回执 (MarketActions.FEE_PREVIEW)。
 *
 * 与 market.place 实收同源: 同一 MarketEngine.resolveBaseValue + 同一 MarketFee.listingFee 纯函数,
 * 前端**不得**再自己镜像一份费率公式。仍非承诺值 —— V0 可能在预览与提交之间被 admin 改动,
 * 最终以 place 回执的 listFee 为准。用 GSON_NULLS 序列化, 故 v0 恒有键。
 */
export interface MarketFeePreviewResult {
  /** 回显入参, 供前端把异步回执对回当前选中标的 (换标的竞态防串行)。 */
  itemId: string
  /** = MarketFee.listingFee(v0, unitPrice, count)。Java long -> number。 */
  listFee: number
  /**
   * 手续费占挂单总价的比例 = listFee / (unitPrice * count)。
   *
   * 刻意不是引擎内部那个 rate (deviationFee 的分母是 max(V0,VR)*count, 不是 unitPrice*count):
   * 玩家心智里的分母是自己挂的总价。**可以 > 1** (极端贱卖时费超过标价), 进度条/染色不得按 0..1 钳死。
   */
  ratio: number
  /** 该物品当前生效基准价 V0; 无锚时为 null (此时走平率)。Java long -> number。 */
  v0: number | null
  /** V0 命中层, 与 market.baseValue 完全同一分层判定。 */
  source: BaseValueSource
}

/** market.p2pCap 入参 (MarketActions.P2P_CAP) —— 不读 payload, 服务端权威取 sender 自己的额度。 */
export type MarketP2pCapPayload = EmptyPayload

/**
 * market.p2pCap 回执 (MarketActions.P2P_CAP)。
 *
 * 口径与 MarketEngine.place 的挂单前 cap 校验**同源同方法**, 不是另算一套数字。
 * **只覆盖铜/铁 6 个 item_id**, 不是全品类额度 —— scopeItemIds 就是为了让面板文案说得出"仅铜铁受限",
 * 而不是笼统的"今日交易额度"。
 *
 * **额度由两段构成, 只有一段随日切释放**: ACTIVE 挂单的占用不看 created_at, 挂着不卖就永久占额度,
 * 撤单才释放; 只有今日已成交的那段在 resetsAt 归零。回执把两段拆开就是为了让面板说得出这条区别 ——
 * 讲成"额度在 X 全部重置"是服务端兑现不了的承诺, 玩家到点发现数字纹丝不动只会认定系统在骗人。
 */
export interface MarketP2pCapResult {
  /** = activeHeld + soldToday, 即 place 拿去与 capPerDay 比对的那个总量。 */
  usedToday: number
  /**
   * 当前 ACTIVE 挂单的 count 之和。**不随日切释放** —— 计数 SQL 对 listing 侧不加 created_at 条件,
   * 只要挂单还在架上就一直占额度, 唯一的释放路径是撤单或卖出。
   */
  activeHeld: number
  /** 今日 (resetsAt 所属窗口内) 已 SOLD 的 count 之和; 只有这一段会在 resetsAt 归零。 */
  soldToday: number
  /** = MarketConstants.COPPER_IRON_DAILY_P2P_CAP (当前 512)。 */
  capPerDay: number
  /**
   * = max(0, capPerDay - usedToday)。下钳到 0 是展示钳制而非空值掩盖: cap 被调小后 usedToday 可能超出旧数据,
   * 负余额对玩家无意义; 真实已用量始终原样放在 usedToday 里。
   */
  remaining: number
  /**
   * **soldToday 那一段**的归零时刻 (epoch millis) = 服务器**系统默认时区**的次日零点 (不是 UTC 翻日)。
   * activeHeld 不受这个时刻影响, 故文案严禁把它写成"额度重置"。
   * 后端唯一的当日窗口算法用 ZoneId.systemDefault(), 且 place 的 cap 判定就吃这个口径;
   * 按 UTC 展示会让倒计时与真实重置错位。
   */
  resetsAt: number
  /** 受本额度约束的标的 item_id (服务端按字典序排好); 当前恒为铜/铁的原矿/粗矿/锭 6 项。 */
  scopeItemIds: string[]
}

/** market.pendingPayout 入参 (MarketActions.PENDING_PAYOUT) —— 不读 payload, 服务端权威取 sender 自己的待结款。 */
export type MarketPendingPayoutPayload = EmptyPayload

/**
 * market.pendingPayout 回执 (MarketActions.PENDING_PAYOUT)。
 *
 * **只读 peek, 绝不发放、绝不清空**: 真实发放只在玩家登录时由 MarketEngine.settlePendingOnLogin 走
 * drainPendingPayout 完成。本条读同一批行但不删 —— 复用 drain 会让玩家开一次收件箱就把货款冲掉。
 *
 * **没有 items 字段**: pending_payout 表只有 id/seller_uuid/amount/currency/created_at, 唯一写入点也只传金额,
 * "被卖掉的是什么物品"从未被持久化过。要补得开 schema 迁移, 不是漏了一个 DAO 方法。
 */
export interface MarketPendingPayoutResult {
  /** 待结信用点合计 (与 settlePendingOnLogin 的求和口径逐字一致)。Java long -> number。 */
  credit: number
  /** 待结条目数 (行数), 供面板显示"共 N 笔离线成交"。 */
  entryCount: number
}

/**
 * market.tradable 入参 (MarketActions.TRADABLE)。
 *
 * **入参是 slot 不是 itemId**: 220 张塔罗牌 x 5 档品质全部注册在同一个 miningdim:tarot_card 之下,
 * 品质只活在 NBT 里。只给 itemId, 服务端对塔罗牌永远判不出品质 —— 那会做出一个"看着接通了、实则规则是假的"接口。
 */
export interface MarketTradablePayload {
  /** Inventory.items 下标, 与 market.place 的 slot / player.inventory / player.itemDetail 同一索引空间 (0..35)。 */
  slot: number
}

/**
 * market.tradable 回执 (MarketActions.TRADABLE)。
 *
 * 判定与 market.place 共用同一份实现 (MarketTradeWhitelist.judge): 本条回 tradable=false 的那一格,
 * place 也必然拒绝; 反之亦然。前端灰按钮显示的规则因此不是假的。
 * 用 GSON_NULLS 序列化, reasonCode / reason 恒有键 (可交易时值为 JSON null)。
 */
export interface MarketTradableResult {
  /** 回显入参 slot, 供前端把异步回执对回当前选中格 (换格竞态防串行)。 */
  slot: number
  /** 该格物品的注册 id。 */
  itemId: string
  tradable: boolean
  /**
   * 拒绝的稳定机器码; 可交易时 null。取值与 place 拒绝时抛出的 errorCode 完全同一个值 (ITEM_NOT_TRADABLE),
   * 故 lib/errorText.ts 一条文案同时服务"提前灰掉按钮"与"硬提交被拒"。
   */
  reasonCode: string | null
  /** 服务端写给玩家看的中文原因 (区分"品质不够低"与"牌数据不完整"两种情形); 可交易时 null。 */
  reason: string | null
}

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

// ============================================================
// economy.* — com.miningdim.economy.EconomyWebUiActions (Gson serializeNulls)
// ============================================================

/** economy.status 入参 —— 不读 payload 任何字段。 */
export type EconomyStatusPayload = EmptyPayload

/**
 * economy.status 回执 (EconomyWebUiActions.STATUS)。
 *
 * 零 SQLite 读 (纯内存态), 是经济三条里唯一可以较高频轮询的。
 * 冻结判据是"无挖掘 && 无显著位移"**两条同时成立**: 服务端只持久化了挖掘侧 (lastBreakTick),
 * 位移侧只有一个滑动锚点, 因此不存在"静止了多久"这个数。故 afkNoMineTicks 不能画成单条进度条,
 * 文案必须说明还要位移条件同时成立。
 */
export interface EconomyStatusResult {
  /** 挂机经济冻结态: true 期间挖到的高价矿既不计当日产量也不发钱。 */
  afkFrozen: boolean
  /**
   * 距上次挖掘的 game tick 数; null = 从未挖过 (不是 0 —— 0 的意思是"刚刚挖过")。
   * **只被矿山维度实例区内的有效挖掘刷新**, 在主世界挖一天也不会变, 文案要写"距上次在矿区挖矿"。
   */
  ticksSinceLastMine: number | null
  /** 无挖掘判据阈值 (tick, 恒 2400 = 2 分钟)。 */
  afkNoMineTicks: number
  /** 无位移判据阈值 (方块数, 恒 4.0) —— 这是另一条独立判据, 不是倒计时。 */
  afkNoMoveBlocks: number
  /** tick/秒 换算率 (恒 20); 前端不得自己写死。 */
  ticksPerSecond: number
}

/** economy.today 入参 —— 不读 payload 任何字段。 */
export type EconomyTodayPayload = EmptyPayload

/**
 * economy.today 回执 (EconomyWebUiActions.TODAY)。
 *
 * 两栏口径**刻意不对称**, 字段名就是唯一提示: 信用点是毛额 (账本记的就是 rawCredit), 青辉石是实发额。
 * 前端严禁把两者合成一句"今日入账"。
 *
 * 支出侧 (sink) 全库没有当日计数器也没有流水表, 故本回执只有收入两栏 —— 支出面板需要先补账本层。
 * 本 action 打 1 次 SQLite 且跑在服务器主线程, **禁止定时轮询**。
 */
export interface EconomyTodayResult {
  /** 当日 UTC 日戳 (epochDay), 与 faucet 计数器翻日判据同一时钟。 */
  dayStamp: number
  /**
   * 翻日时刻 = (dayStamp + 1) 天的 UTC 零点, epoch 毫秒。
   * 本组唯一一个墙钟字段 (与"服务端一律发 tick"的规矩不同), 因为翻日的自变量本来就是 UTC 挂钟。
   */
  resetsAtUtcMillis: number
  /** 全服统一信用点 faucet 计数键 (恒 "credit_faucet")。卖矿/卖菜/悬赏/精英贡献共用它, 故没有分渠道明细。 */
  creditFaucetKey: string
  /** 今日信用点收入【毛额】: 衰减主闸打折之前的 rawCredit 累计, 不是到手额。 */
  todayCreditFaucetGross: number
  /**
   * 衰减主闸**单档大小** (恒 60000 毛收入/档)。**不是每日上限** ——
   * 每日实发总额远大于它 (几何主项前 10 档约 14.9 万才是正常落点), 标成"今日上限 60000"是把玩家数值观带偏。
   */
  creditFaucetTier: number
  /** 当前档位下"再赚 1 点毛收入"的实发系数 (1.0 = 尚未衰减, 最低 0.01 地板), 由主闸函数现算。 */
  creditFaucetNextFactor: number
  /** 今日青辉石入账【实发额】(硬截断口径, 天然是到手额)。 */
  todayAzureIn: number
  /** 青辉石每人每日硬上限 (恒 30; 撞顶即不发, 不是衰减)。 */
  azureDailyCap: number
}

/** economy.priceTable 入参 —— 不读 payload 任何字段。 */
export type EconomyPriceTablePayload = EmptyPayload

/** 高价矿枚举名 (HighValueOre)。货币层只接这三种最大 faucet 龙头, 煤/铁/红石压根没有收购价。 */
export type HighValueOreId = 'DIAMOND' | 'GOLD' | 'NETHERITE_SCRAP'

/**
 * 一种高价矿的定价现况。
 *
 * 展示建议: 主数字用 nextUnitNetCredit (到手), 划掉的原价用 anchorPrice,
 * 中间态 nextUnitGrossCredit 用来解释"为什么降了" —— 是产量降的还是主闸降的。
 */
export interface EconomyPriceAnchor {
  oreId: HighValueOreId
  /** 定价产物的物品 id (minecraft:diamond / minecraft:gold_ingot / minecraft:netherite_scrap)。 */
  itemId: string
  /** 翻译键, 过 client.i18n 出中文名。 */
  descriptionId: string
  /** ShopPriceTable 静态锚价 (500 / 120 / 4500)。**是浮点** (Java double), 别按整数解析。 */
  anchorPrice: number
  /** **本人**今日该矿种已产出个数 (跨日的旧计数按 0 读)。服务端没有全服口径的矿物计数器。 */
  minedToday: number
  /** 该矿种每日软上限 (64 / 256 / 8); 超出后逐矿单价开始 0.97 递减。 */
  dailySoftCap: number
  /** 下一颗的逐矿毛值 = floor(锚价 x max(1%, 0.97^超限数)), 整数。 */
  nextUnitGrossCredit: number
  /**
   * 下一颗经衰减主闸后的净入账, **未取整浮点** (可能是 290.99999999999994 这种), 展示前自行 round。
   * 服务端不提前取整是因为业务入账走跨笔小数 carry, 提前取整会与到手额恒差一点。
   */
  nextUnitNetCredit: number
}

/**
 * economy.priceTable 回执 (EconomyWebUiActions.PRICE_TABLE)。
 * 两层串联顺序与 EconomyService.settleOreSale 逐字一致: 逐矿 steering -> 衰减主闸。
 * 恒 3 行且顺序固定 (DIAMOND/GOLD/NETHERITE_SCRAP), 无分页。
 */
export interface EconomyPriceTableResult {
  dayStamp: number
  /** 主闸的自变量: 今日信用点 faucet 累计毛额 (与 economy.today 同名同口径)。 */
  todayCreditFaucetGross: number
  anchors: EconomyPriceAnchor[]
}

// ============================================================
// marriage.* — com.miningdim.marriage.MarriageWebUiActions (Gson serializeNulls)
//
// 时间一律 overworld gameTime tick, 不是 epoch millis (服务端无可信墙钟)。1 天 = 1728000 tick。
// marriage.state 另发 nowTick, 前端用 (nowTick 基准 + 本地计时) 自行推进倒计时。
// ============================================================

/** marriage.state 入参 —— payload 完全忽略。 */
export type MarriageStatePayload = EmptyPayload

/**
 * 关系态。四者**并非互斥** (冷却中照样能有已接受婚约), 服务端按 married > engaged > cooldown > single
 * 取优先级; 典礼会不会被 REMARRY_COOLDOWN 拦下的真判据是 remarryCooldownTicks, 前端不许只看 status 判冷却。
 */
export type MarriageStatus = 'single' | 'engaged' | 'married' | 'cooldown'

/**
 * 一个婚姻里程碑。真实数据只有"领没领过"两个布尔, 没有 label 也没有达成时刻。
 * 全系统当前只定义了一个 id: 'first_marriage'。
 */
export interface MarriageMilestone {
  milestoneId: string
  /** 本段关系内是否已领。 */
  claimedInCurrentMarriage: boolean
  /**
   * 这对 UUID 历史上是否领过 (跨结离婚去重的真源, 那正是复婚不重发福利的原因)。
   * **只在 status='married' 时有意义**: 单身 (含离婚后) 时它与"没有关系记录"绑定, 恒 false ——
   * 此时不得用它推断"首次结婚福利还能不能领"。
   */
  claimedByPair: boolean
}

/** 别人向我发出的婚约。 */
export interface MarriageIncomingProposal {
  /** 恒等于求婚方 UUID 字符串 (一人同时只持一条 outgoing 意向, 该 UUID 已是完整主键)。 */
  proposalId: string
  proposerUuid: string
  /** **仅求婚方在线时有值** —— 全库零 GameProfileCache 用法, 离线拿不到名字。 */
  proposerName: string | null
  proposerOnline: boolean
  accepted: boolean
}

/** 我发出的那一条婚约。 */
export interface MarriageOutgoingProposal {
  /** 恒等于我自己的 UUID 字符串。 */
  proposalId: string
  targetUuid: string
  /** 仅对方在线时有值。 */
  targetName: string | null
  targetOnline: boolean
  accepted: boolean
}

/**
 * marriage.state 回执 (MarriageWebUiActions.STATE)。
 *
 * 关系态取 MarriageRegistry.forPlayer (权威 + 自带陈旧索引自愈), 不读 capability 指针。
 * 婚约表 (MarriageProposals) 是**不落盘的瞬态表**: 既不记时间戳也没有过期机制, 只在服务端重启时随进程清空 ——
 * 故没有 createdAt / expiresAt 可发, 编一个出来就是假数据。
 */
export interface MarriageStateResult {
  /** 服务端当前 overworld gameTime tick。 */
  nowTick: number
  status: MarriageStatus
  divorceCount: number
  /** 剩余 tick, 0 = 无冷却。冷却 = remarryCooldownDays x (1 + 离婚次数), 随每次离婚递增。 */
  remarryCooldownTicks: number
  marriageId: number | null
  spouseUuid: string | null
  /** 仅配偶在线时有值; 已婚但配偶离线时 spouseUuid 恒有值而本栏为 null, 前端要备占位显示。 */
  spouseName: string | null
  spouseOnline: boolean
  weddedAtTick: number | null
  /** 婚龄整数天。 */
  marriedDays: number
  /**
   * 共享背包等级与该级暴露格数 (未婚均为 0)。按婚龄经 MarriageTuning **现算**, 与真菜单同源;
   * MarriageState 里那个持久 sharedInvLevel 字段全库无写入方, 不作依据。
   */
  sharedInvLevel: number
  sharedInvSlots: number
  /** 扫主背包 36 格算出的真值。 */
  engagementRingOwned: boolean
  /** 三个价格实时读 config, 运营改 toml 立刻生效。 */
  ringPriceCredit: number
  /** 典礼总价, 双方各付一半 (总价为奇数时发起方多付 1)。 */
  weddingCostCredit: number
  divorceCostCredit: number
  milestones: MarriageMilestone[]
  /**
   * 硬上限 32 条 (防撞回执体积收口)。顺序 = 各求婚方登记进表的先后, 每次刷新被截掉的是同一批人。
   * 真服大服里可能有几百人向同一人求婚, 故 truncated=true 时前端必须给出提示。
   */
  incomingProposals: MarriageIncomingProposal[]
  incomingProposalTotal: number
  incomingProposalsTruncated: boolean
  outgoingProposal: MarriageOutgoingProposal | null
}

/** marriage.buyRing 入参 —— 不读 payload 任何字段。 */
export type MarriageBuyRingPayload = EmptyPayload

/**
 * marriage.buyRing 回执 (MarriageWebUiActions.BUY_RING)。
 *
 * 无 ok 字段: 两种失败都走业务异常 (见 MarriageBuyRingErrorCode), 前端在 catch 分支处理。
 * 扣款/发货顺序由 MarriageEngine 定死 —— 扣不动就一分不扣也不发 (事务安全)。
 */
export interface MarriageBuyRingResult {
  costCredit: number
  /** 扣款后的双币余额, 形状与 player.wallet 一致, 可复用同一钱包组件。 */
  wallet: WebUiWallet
  /**
   * 扫背包得到的真值, **不是恒 true**: 背包满时引擎把戒指掉在玩家脚下 (玩家已付费, 不吞货),
   * 那一次它是 false 而钱已扣。前端必须提示"检查脚下", 严禁把它当成"买失败"。
   */
  engagementRingOwned: boolean
}

/**
 * marriage.buyRing 的业务拒绝码。
 *   ECONOMY_OFFLINE     经济子系统未注册 (无 params)
 *   INSUFFICIENT_FUNDS  余额不足 (params: cost / currency='CREDIT' / balance, 三者都是字符串化数字)
 */
export type MarriageBuyRingErrorCode = 'ECONOMY_OFFLINE' | 'INSUFFICIENT_FUNDS'

export type MarriageBuyRingErrorEnvelope = WebUiBusinessErrorEnvelope<MarriageBuyRingErrorCode>

/**
 * marriage.propose 入参 (MarriageWebUiActions.PROPOSE)。
 *
 * **只认在线玩家**, 大小写不敏感, 命中在线列表中第一个同名者。找不到 / 传了自己 / 缺字段 / 类型不对
 * 一律 INVALID_REQUEST + params.field='targetName' —— 但只有"取值域外"那一档 (找不到 / 是自己) 才另带
 * params.value (缺字段与类型不对走 requiredField/wrongType, params 里只有 field)。
 *
 * 界面必须同时提供"在线玩家点选"入口: 中文输入 (W11) 已推迟, 只给输入框会让中文 ID 玩家永远求不了婚。
 */
export interface MarriageProposePayload {
  targetName: string
}

/**
 * marriage.propose 回执 (MarriageWebUiActions.PROPOSE)。
 * 副作用与命令层一致: 覆盖自己旧的那条 outgoing 意向, 并给对方发一条聊天提示 ——
 * **没有 S2C 推送**, 对方面板要等下次拉 marriage.state 才更新。
 */
export interface MarriageProposeResult {
  /** 恒等于求婚方 (自己) 的 UUID 字符串。 */
  proposalId: string
  proposerUuid: string
  targetUuid: string
  targetName: string
  /** 刚发出恒 false。 */
  accepted: boolean
}

/** marriage.respond 入参 (MarriageWebUiActions.RESPOND)。 */
export interface MarriageRespondPayload {
  /** 必须是**求婚方 UUID 字符串**; 形状不对回 INVALID_REQUEST + params {field:'proposalId', value:原值}。 */
  proposalId: string
  /** false 直接清掉该意向 (无"已拒绝"的第三态, 求婚方只能靠下次刷新发现意向没了)。 */
  accept: boolean
}

/**
 * marriage.respond 回执 (MarriageWebUiActions.RESPOND)。
 * 服务端强制校验"这条意向确实指向本人" —— 少了这道校验任何人都能凭一个 UUID 一键拆散别人。
 */
export interface MarriageRespondResult {
  proposalId: string
  proposerUuid: string
  /** 仅求婚方在线时有值。 */
  proposerName: string | null
  /** 与入参 accept 同值。 */
  accepted: boolean
  /** 接受后通常是 'engaged' 而不是 'married' —— 典礼是 marriage.wed 那一步。 */
  status: MarriageStatus
  /** 未婚恒 null (接受求婚 != 已婚)。 */
  spouseName: string | null
}

/**
 * marriage.wed 入参 (MarriageWebUiActions.WED)。
 *
 * partnerName 可省: 省略时服务端按"已接受婚约唯一确定"自动定位伴侣; 有 2 份及以上时拒绝替玩家猜 ——
 * 抛 INVALID_REQUEST + params {field:'partnerName', candidateCount:'N'}, 前端据此把面板从"办典礼"切成"选一位"。
 * 注意 candidateCount 统计的是**全部**已接受婚约, 而 marriage.state.incomingProposals 有 32 条硬上限,
 * 超过 32 份时候选行取不全, 前端必须保留手填 partnerName 的兜底入口。
 */
export interface MarriageWedPayload {
  partnerName?: string
}

/**
 * marriage.wed 的九态结果码。
 * 前七个是 MarriageEngine.Reason 的 Java 真值逐字原样, 后两个是引擎之前就短路的本 action 自有码:
 *   NO_ACCEPTED_PROPOSAL 没有已接受的婚约
 *   PARTNER_OFFLINE      唯一那位已接受的伴侣不在线 (典礼要求双方在场)
 */
export type MarriageWedOutcomeCode =
  | 'OK'
  | 'SELF_MARRIAGE'
  | 'ALREADY_MARRIED'
  | 'NO_ENGAGEMENT_RING'
  | 'INSUFFICIENT_FUNDS'
  | 'NO_ECONOMY'
  | 'REMARRY_COOLDOWN'
  | 'NO_ACCEPTED_PROPOSAL'
  | 'PARTNER_OFFLINE'

/**
 * marriage.wed 回执 (MarriageWebUiActions.WED)。
 *
 * 失败是**正常业务结果**, 走 success=true 的回执体 (ok:false + outcomeCode), 不占 WebUiErrorCodes 命名空间。
 * messageKey 是 /marriage 命令对同一结果所用的 lang 键 (目的是让面板与聊天栏不出现两套口径),
 * 前端仍必须自备一套按 outcomeCode 的文案 —— messageKey 可能为 null, 且带 %s 的键要用 messageArgs 填参。
 */
export interface MarriageWedResult {
  ok: boolean
  outcomeCode: MarriageWedOutcomeCode
  messageKey: string | null
  /** 与该键的 %s 一一对应; 无占位符则空数组。 */
  messageArgs: string[]
  partnerUuid: string | null
  partnerName: string | null
  /** 仅 ok=true。 */
  marriageId: number | null
  /** 仅 ok=true; gameTime tick。 */
  weddedAtTick: number | null
}

/** marriage.divorce 入参 —— 不读 payload 任何字段。 */
export type MarriageDivorcePayload = EmptyPayload

/** marriage.divorce 的四态结果码 (MarriageDivorce.Result 全集, OK 之外三条是失败)。 */
export type MarriageDivorceOutcomeCode = 'OK' | 'NOT_MARRIED' | 'INSUFFICIENT_FUNDS' | 'NO_ECONOMY'

/**
 * marriage.divorce 回执 (MarriageWebUiActions.DIVORCE)。
 *
 * 离婚会把共享背包内容全部退回**发起方** (背包满则落地) 并强制关闭双方已打开的共享背包窗口,
 * 故成功后应立即重拉 marriage.state 与 marriage.sharedInv。
 */
export interface MarriageDivorceResult {
  ok: boolean
  outcomeCode: MarriageDivorceOutcomeCode
  messageKey: string
  messageArgs: string[]
  /** 本次离婚的**定价**而非已扣额 —— 三种失败一分未扣。前端据 ok 决定说"已扣"还是"需要"。 */
  costCredit: number
  /** 结算后重读。 */
  divorceCount: number
  /** 结算后重读的剩余 tick (默认首次离婚 = 14 天 = 24192000 tick)。 */
  remarryCooldownTicks: number
  /** 仅 ok=true。 */
  formerSpouseUuid: string | null
}

/** marriage.sharedInv 入参 —— 不读 payload 任何字段。 */
export type MarriageSharedInvPayload = EmptyPayload

/**
 * marriage.sharedInv 回执 (MarriageWebUiActions.SHARED_INV)。纯只读, 一个字节都不写容器; 取放仍走原版 menu。
 *
 * 未婚不是错误而是正常答案 (同 player.isOp 的纪律): 回 married:false + level/slots 为 0 + items 空数组。
 *
 * items 复用 PlayerInventoryItem: 槽位 JSON 与 player.inventory **逐字同形** (含变体两字段),
 * 前端必须复用同一个格子渲染组件 —— 另发明一套的后果是 195 种枪匠零件在其中一套里退回同名同图标。
 * 注意那三个变体字段是"条件追加"的**缺席键** (`?:`), 与本组其余显式 null 的字段语义不同。
 */
export interface MarriageSharedInvResult {
  /** 恒 54 (容器固定大小)。 */
  capacity: number
  married: boolean
  marriageId: number | null
  /** 未婚 0。 */
  level: number
  /** 当前等级暴露的格数 (默认 1 级 9 格), 未婚 0。 */
  slots: number
  /** 只回当前等级暴露的前 slots 格; 超出可见面的格子即使有货也一格不发。 */
  items: PlayerInventoryItem[]
}

// ============================================================
// mining.* — com.miningdim.entry.MiningWebUiActions (Gson serializeNulls)
//
// R1 模型: 全服只有 3 块常驻共享区域 (每难度一块), 回执里没有"创建实例/我的副本"概念。
// 时间一律矿山维度 game tick, 每个回执都附带当前 gameTime 作换算基准。
// ============================================================

/** 矿洞三难度的规范小写名 (Difficulty.configName())。入参大小写不敏感, 回执一律回显规范小写名。 */
export type MiningDifficulty = 'easy' | 'medium' | 'hard'

/** 区域实例的生成态 (GenState 枚举名)。 */
export type MiningGenState =
  | 'PENDING'
  | 'GENERATING'
  | 'READY'
  | 'READY_FALLBACK'
  | 'RESETTING'
  | 'FAILED'
  | 'RECYCLED'

/** mining.overview 入参 —— 不读 payload 任何字段。 */
export type MiningOverviewPayload = EmptyPayload

/**
 * 一个难度的常驻区域 (MiningWebUiActions.OVERVIEW 的一行)。
 *
 * available=false 时 instanceId/genState/playersInside/shared/regionOriginX/regionOriginZ **这 6 个**为 null,
 * enterable 仍是布尔 (false)。该态只可能出现在开服重建未完成的极早期, 前端按不可进入渲染。
 */
export interface MiningInstanceRow {
  difficulty: MiningDifficulty
  /** 翻译键 'difficulty.miningdim.<难度>'; 服务端不发中文。 */
  nameKey: string
  /** 代码权威取 MinerLevelGate = 1/4/8 (GateResult 头注释里的 10/25 是过期文档口径)。 */
  requiredMinerLevel: number
  unlocked: boolean
  /** false = 该难度的常驻区域此刻不存在。 */
  available: boolean
  instanceId: number | null
  genState: MiningGenState | null
  enterable: boolean
  playersInside: number | null
  shared: boolean | null
  regionOriginX: number | null
  regionOriginZ: number | null
  /** 0 = 该难度关闭定时刷新。 */
  autoResetHours: number
  /**
   * 上次**定时刷新**的矿山维度 game tick。手动/管理台重置不写它, 故文案不能写成"上次重置"。
   * 现实中拿不到 null (调度器在 ServerStartedEvent 就写好了基准)。
   */
  lastResetGameTime: number | null
  /**
   * = lastReset + autoResetHours * 72000; autoResetHours <= 0 时为 null (此时不许画倒计时)。
   * **它是预警起点不是换图时刻**: 真正的清场 + 重置发生在其后 autoResetWarnSeconds 秒,
   * 即真实换图 = nextResetGameTime + autoResetWarnSeconds * 20 tick。
   */
  nextResetGameTime: number | null
}

/** mining.overview 回执 (MiningWebUiActions.OVERVIEW)。恒 3 行, 顺序 = Difficulty.values() (easy/medium/hard)。 */
export interface MiningOverviewResult {
  instances: MiningInstanceRow[]
  minerLevel: number
  /** 矿山维度当前 game tick, 与行内 last/nextResetGameTime 同一时钟。 */
  gameTime: number
  autoResetWarnSeconds: number
  myDifficulty: MiningDifficulty | null
}

/** mining.myStatus 入参 —— 不读 payload 任何字段。 */
export type MiningMyStatusPayload = EmptyPayload

/**
 * mining.myStatus 回执 (MiningWebUiActions.MY_STATUS)。
 *
 * 不在矿洞时 5 个区域字段一律 JSON null 而不是 0 —— 发 0 会被画成"你在原点那块区域"。
 * currentInstanceId 与 instanceId 是两个独立事实 (前者是传送前写下的 capability 指针, 后者是几何反查),
 * 二者不一致本身就是要给运维看的现场, 故都发。
 */
export interface MiningMyStatusResult {
  /** [维度 == miningdim:mining] 与 [regionAt(x,z) 命中] **同时成立**才为 true。 */
  inside: boolean
  inMiningDimension: boolean
  difficulty: MiningDifficulty | null
  instanceId: number | null
  genState: MiningGenState | null
  regionOriginX: number | null
  regionOriginZ: number | null
  /** capability 的实例指针; 不在任何实例时是哨兵 **-1** (不是 0, 也不是 null)。 */
  currentInstanceId: number
  gameTime: number
  /** 出生保护截止 game tick; 从未进过矿洞为 0。 */
  spawnFreezeUntilGameTime: number
  /** = max(0, until - now); 已过期恒 0, 绝不为负。 */
  spawnFreezeRemainingTicks: number
  minerLevel: number
}

/** mining.enter 入参 (MiningWebUiActions.ENTER)。difficulty 大小写不敏感。 */
export interface MiningEnterPayload {
  difficulty: MiningDifficulty
}

/** mining.enter 的同步拒绝码 (LEVEL_TOO_LOW 逐字取自 GateResult 枚举名)。 */
export type MiningEnterReasonCode = 'LEVEL_TOO_LOW' | 'ALREADY_INSIDE'

/**
 * mining.enter 回执 (MiningWebUiActions.ENTER)。
 *
 * 复用 EntryGateway.requestEnter 这条唯一权威路径 (它做难度门控 + 写回退现场快照 + 等生成就绪 +
 * 等区块 FULL 再传送, 防掉虚空)。
 *
 * **accepted 只表示"已交给权威入场链路", 不表示已经传送进去了**: 真正的传送发生在之后若干 tick,
 * 其成败只经原生 TeleportResult S2C 下发 (不走 webui 通道), 面板要确认是否真进去了必须轮询 mining.myStatus。
 */
export interface MiningEnterResult {
  difficulty: MiningDifficulty
  requiredMinerLevel: number
  minerLevel: number
  instanceId: number | null
  accepted: boolean
  reasonCode: MiningEnterReasonCode | null
  /** 翻译键 (message.miningdim.gate.level_too_low / message.miningdim.enter.already_inside); accepted=true 时 null。 */
  reasonKey: string | null
}

/** mining.leave 入参 —— 不读 payload 任何字段。 */
export type MiningLeavePayload = EmptyPayload

/**
 * mining.leave 回执 (MiningWebUiActions.LEAVE)。
 * 整条逻辑委派 EntrySystem.leaveToFallback (传回退点 + 释放强加载/唤醒排队 + 清 currentInstanceId/spawnFreeze)。
 * 本就不在实例内返回 left=false, 不抛业务异常; 成功文案由前端自己写 (服务端不发)。
 */
export interface MiningLeaveResult {
  left: boolean
  reasonCode: 'NOT_INSIDE' | null
  /** 翻译键 message.miningdim.leave.not_inside; left=true 时 null。 */
  reasonKey: string | null
}

// ============================================================
// champion.* — com.miningdim.champion.ChampionWebUiActions
// (默认 Gson: 无 null 值, "仅部分词条有"的副数值是缺席键, 故一律 `?:` 不用 `| null`)
// ============================================================

/** 词条品质 (AffixQuality 枚举名)。 */
export type ChampionAffixQuality = 'COMMON' | 'UNCOMMON' | 'RARE' | 'EPIC' | 'LEGENDARY'

/** 词条池 (AffixPool 枚举名)。 */
export type ChampionAffixPool = 'SURVIVAL' | 'COMBAT' | 'MOBILITY' | 'SKILL'

/**
 * champion.codex 的难度枚举名 (**大写**, Difficulty.name())。
 * 刻意不与 MiningDifficulty 合并: 同一行里的 configName 才是小写那套, 混用会让 TS 类型对了而运行期取值全不匹配。
 */
export type ChampionDifficulty = 'EASY' | 'MEDIUM' | 'HARD'

/** champion.codex 入参 —— 不读 payload 任何字段。 */
export type ChampionCodexPayload = EmptyPayload

/** 一档品质的成本系数与展示色。 */
export interface ChampionQualityRow {
  qualityId: ChampionAffixQuality
  /** 0-4, 即 primaryValues / costs / availableTiers 的数组下标。 */
  tier: number
  /** 1.0 / 1.6 / 2.5 / 4.0 / 6.5。 */
  costMultiplier: number
  /** 展示色, 十进制 RGB 整数。 */
  displayColorRgb: number
}

/**
 * 一条词条的全档定义 (AffixDef 一项)。
 *
 * primaryUnit 词表 (前端据此格式化, **严禁跨词条比大小**):
 *   fraction_damage_reduction / fraction_dodge_chance / fraction_reflect  0-1 比率
 *   fraction_maxhp / fraction_maxhp_per_second / fraction_maxhp_per_second_per_stack  %maxHP 系
 *   flat_hp_per_second  绝对 HP/秒
 *   flat_hp_damage_cap  绝对 HP 的单次伤害封顶 (刚毅护盾; **数值越小越强**, 别按大即强排序)
 *   fraction_max_health_bonus / fraction_max_health_penalty  血量增/减
 *   fraction_vulnerability_per_stack  每层易伤
 *   fraction_damage_bonus  伤害增幅
 *   fraction_move_speed_bonus  移速增幅
 *   durability_points_per_hit  每击护甲耐久损耗点数
 *   hit_count / count  跳数 / 个数
 *   seconds_cooldown  施放周期秒 (**数值越小越强**)
 *   seconds_duration  持续秒
 *   multiplier  纯倍率
 *   flag  数值恒 1 无结算意义, 只表示"带没带这条"
 * 注意 fraction_move_speed_bonus / fraction_max_health_bonus / fraction_damage_bonus **可以 > 1**
 * (超速最高 2.50 = +250%, 巨大化最高 1.80, 重炮最高 1.00), 按 0..1 钳制会把超速压成 100%。
 * secondaryUnit 词表: fraction_size_bonus / fraction_size_penalty / fraction_slow_per_stack /
 * strike_count / concurrent_count。
 */
export interface ChampionAffixRow {
  /** AffixDef 枚举名, 如 COMPOSITE_ARMOR。 */
  affixId: string
  /** 翻译键 'affix.champions.<小写枚举名>'; 服务端不下发中文。 */
  nameKey: string
  pool: ChampionAffixPool
  baseCost: number
  minStar: number
  isSkill: boolean
  /** MutexFlag 枚举名; **无互斥是字符串 'NONE'**, 不是 null —— 判空要写 === 'NONE'。 */
  mutexFlag: string
  minQuality: ChampionAffixQuality
  primaryUnit: string
  /** 长度恒 5, 下标 = ChampionQualityRow.tier。 */
  primaryValues: number[]
  /**
   * 长度恒 5; false = 该档不存在。
   * **必须按它灰掉对应格**: primaryValues 里的 0 全是占位而非真值 (重型护甲/刚毅护盾前两档、
   * 小男孩/命定之死前三档、自我修复的中级档), 照直画会多出一排"减伤 0%"的假档位。
   */
  availableTiers: boolean[]
  /** 长度恒 5; = ceil(baseCost x costMultiplier)。ceil 是防小数成本破整数点池预算的业务规则, 前端不得自己乘。 */
  costs: number[]
  /** 仅 5 条词条有 (巨大化/缩小化/寒霜/天雷/支援); 无副数值时整键不出现。 */
  secondaryUnit?: string
  /** 与 secondaryUnit 同进同出, 长度恒 5。 */
  secondaryValues?: number[]
}

/** 一个星级的预算与红线 (StarRank 一项)。 */
export interface ChampionStarRow {
  /** 1-10, 顺序即 1..10。 */
  star: number
  survivalBudget: number
  combatBudget: number
  mobilityBudget: number
  skillBudget: number
  /** 总词条上限 (含技能词条)。 */
  maxAffixes: number
  /** 技能数上限, 1-2 星为 0。 */
  maxSkills: number
  maxQuality: ChampionAffixQuality
  /** 基础有效 HP; 6 星起破原版 1024。 */
  baseEffectiveHp: number
  /** 该星普通单击的设计基线, 0-1 小数的 %maxHP。 */
  baseSingleHitPct: number
  /** 红线 3 单击硬上限, 0-1 小数: 1-5 星 0.4 / 6-7 星 0.5 / 8-10 星 0.6。与设计基线是两回事。 */
  normalHitCapPct: number
  usesCustomBloodPool: boolean
  /** BOSS 血条 signature 色, 十进制 RGB 整数。 */
  barColorRgb: number
}

/**
 * 一个难度的精英分布。
 * **没有权重表**: ChampionSpawnPolicy.rollStar 就是"区间 [minStar,maxStar] 内均匀取整",
 * 要画分布图请自己按均匀分布铺 (均匀性由顶层 starRollMode 声明)。
 */
export interface ChampionDistributionRow {
  difficulty: ChampionDifficulty
  configName: MiningDifficulty
  /** 升格率 0-1 小数: 0.06 / 0.1 / 0.15。 */
  promoteChance: number
  minStar: number
  maxStar: number
}

/**
 * champion.codex 回执 (ChampionWebUiActions.CODEX)。纯静态表, 与玩家无关。
 * 四个数组全是枚举基数 (affixes 恒 35 / qualities 恒 5 / stars 恒 10 / distribution 恒 3), 无分页。
 */
export interface ChampionCodexResult {
  /** 恒 6: 该星级起走自定义血池 (StarRank.CUSTOM_BLOOD_POOL_MIN_STAR)。 */
  customBloodPoolMinStar: number
  /** 掷星方式常量, 当前恒 'UNIFORM_INCLUSIVE' (区间内均匀取整, 无权重表)。 */
  starRollMode: string
  qualities: ChampionQualityRow[]
  affixes: ChampionAffixRow[]
  stars: ChampionStarRow[]
  distribution: ChampionDistributionRow[]
}

/**
 * champion.inspect 入参 (ChampionWebUiActions.INSPECT)。
 * 页面自己拿不到网络实体 id, 需要 MCEF 客户端侧把准星/选中实体的 Entity.getId() 传进页面 (客户端与服务端
 * 的网络实体 id 是同一套)。
 */
export interface ChampionInspectPayload {
  /** 只在发送者所在维度内查。 */
  entityId: number
}

/** champion.inspect 拒绝时 params.reason 的取值 (专用错误码补上前的临时区分手段)。 */
export type ChampionInspectRejectReason = 'ENTITY_NOT_FOUND' | 'NOT_A_CHAMPION'

/** 一条已掷出的词条 (champion.inspect 专用: 带实际品质与实际数值)。 */
export interface ChampionInspectAffix {
  affixId: string
  nameKey: string
  pool: ChampionAffixPool
  isSkill: boolean
  /** 该条实际掷到的品质。 */
  quality: ChampionAffixQuality
  /** 0-4, = quality 的档位下标。 */
  tier: number
  /** 该品质档的实际点数成本。 */
  cost: number
  primaryUnit: string
  primaryValue: number
  /** 仅有副数值的词条出现; 无则整键不出现。 */
  secondaryUnit?: string
  secondaryValue?: number
}

/**
 * champion.inspect 回执 (ChampionWebUiActions.INSPECT)。
 *
 * 【血量口径 —— 本条最要紧的一点】6 星起 (或低星被巨大化撑破 1024 的怪) 的战斗权威是自定义血池,
 * vanilla 那份被 generic.max_health 的 1024 硬上限钳住, 只是渲染镜像 (实测 7 星: maxHealth=5312.8 而
 * vanillaMaxHealth=1024)。**画血条一律只用 health/maxHealth/healthFraction**, 拿 vanilla 那一对算比例必错。
 *
 * 两种拒绝都是 errorCode=INVALID_REQUEST, params={field:'entityId', value:'<传入的 id>',
 * reason:'ENTITY_NOT_FOUND'|'NOT_A_CHAMPION'} —— 服务端绝不会返回一份 star=0 的成功回执冒充"什么都没有"。
 */
export interface ChampionInspectResult {
  entityId: number
  /** 实体注册 id, 如 minecraft:zombie。 */
  entityTypeId: string
  /** 实体翻译键, 如 entity.minecraft.zombie; 服务端不下发中文。 */
  entityDescriptionId: string
  star: number
  /** 该星允许的最高品质。 */
  maxQuality: ChampionAffixQuality
  /** true = 支援召唤词条召出的援军 (不参与货币/经验/掉落/贡献结算)。 */
  summonedByAffix: boolean
  /**
   * 盖章时算出的有效血 (星表基础血 x 生存点剩余曲线 x 体型乘数)。
   * 走血池时与 maxHealth 相等; 走 vanilla 时 maxHealth 被 1024 钳住而它不会 ——
   * 要显示"这只怪设计上多硬"该用本栏。
   */
  effectiveHp: number
  /** 取的是血池注册表的在册事实, 不是按 star>=6 反推 (低星巨大化那一类也会建池)。 */
  customBloodPool: boolean
  /** 与 customBloodPool 同义, 供文案直接取用。 */
  healthSource: 'BLOOD_POOL' | 'VANILLA_MAX_HEALTH'
  /** 权威当前血。 */
  health: number
  /** 权威最大血; 走血池时可远超 1024。 */
  maxHealth: number
  /** health / maxHealth 的 [0,1] 钳制值 (服务端算好, float 精度)。 */
  healthFraction: number
  /** vanilla 当前血, **仅供对账诊断**。 */
  vanillaHealth: number
  /** vanilla 最大血, 同上。 */
  vanillaMaxHealth: number
  /** 顺序 = AffixDef 声明序 (与 codex 的 affixes 同序, 可直接按 affixId join)。条数上限 = 星表 maxAffixes。 */
  affixes: ChampionInspectAffix[]
}

// ============================================================
// admin.economy.* / admin.job.* / admin.mining.* — 各子系统的管理台 action
// (EconomyAdminWebUiActions / JobAdminWebUiActions / MiningAdminWebUiActions)
//
// 权限现状 (前端文案字典必须照这个现实写): 全库有两套 OP 拒绝口径 ——
//   admin.economy.*  抛 WebUiBusinessException, errorCode=INVALID_REQUEST + params={field:'permission',value:'op'}
//   admin.job.setLevel / admin.mining.reset / 存量 admin.*  抛裸 IllegalStateException, 回执**没有 errorCode**
// 待服务端补 ADMIN_ONLY / NOT_OPERATOR 后两处统一切。
// ============================================================

/** admin.economy.balance 入参 (EconomyAdminWebUiActions.BALANCE)。 */
export interface AdminEconomyBalancePayload {
  /**
   * 在线玩家名, 大小写不敏感。**只认在线玩家**: 解析不到抛 INVALID_REQUEST +
   * params={field:'playerName', value:<截断后的入参>}, 绝不返回空钱包 ——
   * 前端必须把这条拒绝显示成"该玩家不在线/名字不对", 不能显示成"余额 0"。
   */
  playerName: string
}

/** admin.economy.balance 回执 (默认 Gson, 无 null 值)。 */
export interface AdminEconomyBalanceResult {
  /** 解析到的 GameProfile **真名** (不是回显入参; 入参大小写可能不同)。 */
  playerName: string
  /** 账本真正的键。无 uuid 时操作者无法确认自己改的是不是同名的另一个人。 */
  playerUuid: string
  wallet: WebUiWallet
}

/** admin.economy.set 入参 (EconomyAdminWebUiActions.SET)。 */
export interface AdminEconomySetPayload {
  /** 同 admin.economy.balance: 只认在线玩家。 */
  playerName: string
  currency: WebUiCurrency
  /**
   * 目标【绝对值】不是增量; 必须是 0 到 9007199254740991 (2^53-1) 之间的整数。
   * 上界是 JS Number 的无损整数上限而非账本上限 (账本是 long) —— 超过它的"整数"在到达服务端前就已经变形了。
   * 负数直接拒 (账本无欠款语义)。拒绝码 params.field 分别是 permission / playerName / currency / amount。
   */
  amount: number
}

/**
 * admin.economy.set 回执 (EconomyAdminWebUiActions.SET)。
 *
 * 语义是"设成绝对值", 实现为同一事务内读当前值再补差额 (delta>0 走 grant, delta<0 走 tryCharge,
 * delta==0 一次账本写都不发生), 全程经账本门面。
 * 本入口走普通 grant/tryCharge, **不计入**当日 faucet 衰减计数器 (与 /economy grant 同纪律) ——
 * 管理台文案不要暗示"调账会占用玩家今日额度"。每笔调账另落一行服务端日志, 那是事后唯一追溯来源。
 */
export interface AdminEconomySetResult {
  playerName: string
  playerUuid: string
  /** 被改的币种回显。 */
  currency: WebUiCurrency
  /** 改【前】双币快照 —— 真服无调账流水表, 这是唯一一次看见改前值的机会。 */
  before: WebUiWallet
  /** 改【后】双币快照。只动 currency 指定的那一种, 另一种在 before/wallet 里同值。 */
  wallet: WebUiWallet
}

/**
 * 职业稳定 id。派生自既有的 PlayerJobProgressEntry 而不是另抄一份字面量联合:
 * 两处各写一份的话, JobId 枚举日后增删时只会有一处被改到。
 */
export type WebUiJobId = PlayerJobProgressEntry['jobId']

/** admin.job.setLevel 入参 (JobAdminWebUiActions.SET_LEVEL)。 */
export interface AdminJobSetLevelPayload {
  /**
   * 目标玩家档案名, 大小写不敏感; **只认在线玩家** (离线玩家的 capability 不在内存里, 也没有 syncTo 的对象,
   * 一律 INVALID_REQUEST + params.field='playerName')。管理台的玩家选择器必须只列在线玩家。
   */
  playerName: string
  /**
   * 服务端另接受历史别名 armorer / 铸甲师 (JobId.byId 的既有兼容分支, 均归一化成 engineer),
   * 但面板一律发规范 id; 未知 id -> INVALID_REQUEST + params.field='jobId'。
   */
  jobId: WebUiJobId
  /** 1-10 (含两端); 越界 -> INVALID_REQUEST + params.field='level'。 */
  level: number
}

/**
 * admin.job.setLevel 回执 (JobAdminWebUiActions.SET_LEVEL)。
 *
 * **副作用不止 level**: JobProgress.setLevel 同时把累计经验对齐到该级整级线 (只改 level 不改 xp 的话,
 * 下一次入账会被 JobXpCurve 按旧 xp 重新派生回原等级)。故回执发的是改完后读回来的两个真值,
 * 前端应把 totalXp 一并刷新到界面, 否则 OP 会以为自己只动了等级。
 *
 * 改级后服务端已立刻 syncTo 下发全职业 S2C 同步包, 前端无需另外触发任何同步 action。
 */
export interface AdminJobSetLevelResult {
  /** 服务端解析到的目标玩家真名 (按 GameProfile 回填, 不是入参原样回显)。 */
  playerName: string
  playerUuid: string
  /** 归一化后的稳定 id (传 armorer 会拿回 engineer)。 */
  jobId: WebUiJobId
  /** 改后从 capability 读回的真值。 */
  level: number
  /** 改后从 capability 读回的累计有效经验 (Java long; 当前满级毕业线远小于 2^53)。 */
  totalXp: number
}

/** admin.mining.reset 入参 (MiningAdminWebUiActions.RESET)。 */
export interface AdminMiningResetPayload {
  /** 与 mining.enter 共用同一个解析器, 同样大小写不敏感; 回执回显规范小写名。 */
  difficulty: MiningDifficulty
  /** 可选, 缺省 true = NEW_SEED 换图; 给了就必须是布尔, 字符串会被 INVALID_REQUEST 拒。 */
  reseed?: boolean
}

/** admin.mining.reset 的同步拒绝码。 */
export type AdminMiningResetReasonCode = 'NOT_RESETTABLE' | 'OCCUPIED'

/**
 * admin.mining.reset 回执 (MiningAdminWebUiActions.RESET)。
 *
 * **确认弹窗的责任在前端**: 活跃的 /mining reset 没有二次确认, 服务端照旧不加。
 *
 * 受理前先做与 ResetSystem.reset 逐条对齐的前置裁决, 被拒时同步回 accepted=false + reasonCode 且绝不下发重置
 * (否则面板会回"已受理"而 future 在后台静默失败)。受理后重置本身异步, 终局只进服务端日志。
 * 另有一条未进本结构的失败形态: 该难度的常驻区域实例不存在时抛裸 IllegalStateException,
 * 前端收到的是无 errorCode 的通用失败回执 —— 只处理 reasonCode 两态会漏掉它。
 *
 * 本 action **不更新** AutoResetData.lastReset, 故 mining.overview 的 lastResetGameTime 不含手动重置。
 */
export interface AdminMiningResetResult {
  difficulty: MiningDifficulty
  mode: 'NEW_SEED' | 'SAME_SEED'
  /** 受理那一刻的矿山维度 game tick (不是重置完成时刻, 更不是 epoch millis)。 */
  requestedAtGameTime: number
  instanceId: number
  genState: MiningGenState
  /** 被踢出该区域的玩家数 (= 受理那一刻的在场人数, 含离线待撤离者); 被拒时也如实回报该数字而不是 0。 */
  evictedPlayers: number
  accepted: boolean
  reasonCode: AdminMiningResetReasonCode | null
}
