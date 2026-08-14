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
