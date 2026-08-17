package com.miningdim.webui.server;

/**
 * Web UI 业务错误码全集 (WebUI 接线契约 W1 / 决策 D4)。
 *
 * 收编范围: 只收真正出现在 {@link WebUiBusinessException} 里的码 —— 它们会经
 * {@link WebUiServerDispatcher#businessErrorJson} 下发给页面, 是前端本地化字典的键, 属对外契约。
 * 走通用异常兜底 (Gateway 的 {@code catch (Exception)} 分支) 的失败没有 errorCode, 不在本表内;
 * 把它们回填成业务异常是另一个题目, 不许顺手往本表加没有抛出点的码。
 *
 * 与面板锁定码分表: hub.panels 的 lockCode 是"这个面板此刻能不能进"的原因码, 与本表是两个命名空间,
 * 各有各的常量类与各自的前端文案字典。合表会让"锁定原因"与"调用失败"静默串号。
 *
 * 码本身即前后端的稳定契约: 值一旦下发就不许改名 (改名 = 前端文案字典整条失配, 玩家看到英文原码)。
 */
public final class WebUiErrorCodes {

    private WebUiErrorCodes() {
    }

    /**
     * 入参形状或取值非法 (缺必填字段 / 类型不符 / 取值域外)。
     * 抛出点: {@code CaseWebUiActions.invalidRequest}; W1 起 player.itemDetail 与 player.prefs.set 复用,
     * 后者带 params {@code field} 与 {@code value} 指出是哪个字段的哪个值被拒。
     * W2 起 {@code MarketActions.FEE_PREVIEW} 复用 (unitPrice/count &lt;= 0, 同带 params {@code field}/{@code value}):
     * 手续费预览刻意不为非法入参编一个 0 出来 —— 那是给玩家看一个真金白银的假数字。
     */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /**
     * slot 不在 {@code [0, sender.getInventory().items.size())} 内。params: {@code slot} 与 {@code size}。
     * 抛出点: player.itemDetail; W2 起 {@code MarketActions.TRADABLE} 复用 (两者同一槽位索引空间, 逐字同形)。
     */
    public static final String SLOT_OUT_OF_RANGE = "SLOT_OUT_OF_RANGE";

    /**
     * slot 合法但该格 {@code ItemStack.isEmpty()}。params: {@code slot}。
     * 抛出点: player.itemDetail; W2 起 {@code MarketActions.TRADABLE} 复用。
     */
    public static final String SLOT_EMPTY = "SLOT_EMPTY";

    /**
     * 该标的被市场白名单禁止挂单 (当前唯一规则: 塔罗牌只有最低品质 R 可挂)。
     * params: {@code itemId} 与 {@code rule} ({@code TAROT_QUALITY_ABOVE_R} / {@code TAROT_IDENTITY_UNREADABLE},
     * 前端据此把一条码分成两句话)。
     *
     * 抛出点只有一个: {@code MarketEngine.place} (判定源自 {@code MarketTradeWhitelist.judge})。
     * {@code market.tradable} 回执的 reasonCode 回的是同一个值但**不抛** —— 灰按钮与硬提交被拒因此共用一条文案,
     * 不会出现两套口径。
     */
    public static final String ITEM_NOT_TRADABLE = "ITEM_NOT_TRADABLE";

    /**
     * 该主动技能的等级未解锁。抛出点: {@code MinerWebUiActions} 的 job.miner.scan handler (等级门, 对应
     * 键位路径 {@code MinerActions.notLearned})。params: {@code skill} / {@code requiredLevel} / {@code currentLevel}。
     *
     * 命名刻意不带 MINER_ 前缀: 后续别的职业的主动技能面板会撞上同一种拒绝, 由 params.skill 区分是哪一个,
     * 而不是每个职业各造一个码让前端文案字典按职业翻倍。
     */
    public static final String SKILL_LOCKED = "SKILL_LOCKED";

    /**
     * 该主动技能仍在冷却。抛出点同 {@link #SKILL_LOCKED} (CD 门, 对应 {@code MinerActions.onCooldown})。
     * params: {@code skill} / {@code remainingTicks} (发 tick 不发墙钟: 服务端手里只有 game tick, 换算成
     * 服务端墙钟再让页面拿 Date.now() 去减, 既吃时钟偏移又在 TPS 掉帧时失真)。
     */
    public static final String SKILL_ON_COOLDOWN = "SKILL_ON_COOLDOWN";

    /**
     * 经济子系统未注册, 本次不扣物也不发币。抛出点: {@code FarmerWebUiActions} 的 job.farmer.sell handler,
     * 判 {@code FarmerWheatSellService.SellResult.economyOffline()}。无 params。
     *
     * 与装配缺陷分开成业务码的理由: 卖菜路径本就把"经济没起来"当作正常短路 (不扣物不发币直接返回), 而不是
     * 抛异常; 前端必须能把它与"卖成功了但发币 0"区分开来。
     */
    public static final String ECONOMY_OFFLINE = "ECONOMY_OFFLINE";

    /**
     * 经济已就绪但背包里没有可卖作物 (soldCount &lt;= 0)。抛出点同 {@link #ECONOMY_OFFLINE} 的 handler。
     * params: {@code itemId}。
     */
    public static final String NOTHING_TO_SELL = "NOTHING_TO_SELL";

    /**
     * 出售被职业精通等级门拒绝 (反洗钱身份门)。抛出点同 {@link #ECONOMY_OFFLINE} 的 handler, 判
     * {@code FarmerWheatSellService.SellResult.belowMastery()}。params: {@code job} / {@code requiredLevel} /
     * {@code currentLevel}。
     *
     * 必须与 {@link #NOTHING_TO_SELL} 分开: 后者的文案是"背包里没有可卖的东西", 而这条拒绝发生时玩家手里
     * 确实有货, 只是等级不够 —— 复用会让面板显示一句与事实相反的话。
     *
     * 命名不带 FARMER_ 前缀, 同 {@link #SKILL_LOCKED} 的理由: 别的职业若也要给出售加身份门, 由 params.job
     * 区分, 不为每个职业各造一个码。
     */
    public static final String SELL_LEVEL_TOO_LOW = "SELL_LEVEL_TOO_LOW";

    /** 任务系统已被配置关闭。抛出点: 所有 {@code quest.*} action 的统一前置门。 */
    public static final String QUEST_DISABLED = "QUEST_DISABLED";

    /** 开箱系统已关闭, 或 TaCZ / 武器箱资源包未就绪。抛出点: {@code CaseOpeningService.open}。 */
    public static final String CASE_DISABLED = "CASE_DISABLED";

    /** 余额不足 (信用点或青辉石)。抛出点: {@code CaseOpeningService.open}。 */
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    /** TaCZ 或武器箱资源包未就绪, 无法应用枪械皮肤。抛出点: {@code CaseOpeningService.apply}。 */
    public static final String TACZ_UNAVAILABLE = "TACZ_UNAVAILABLE";

    /** 玩家不拥有该皮肤资产。抛出点: {@code CaseOpeningService.apply}。 */
    public static final String ASSET_NOT_OWNED = "ASSET_NOT_OWNED";

    /** 该开箱事务已退款, 必须换新 openingId 重开。抛出点: {@code CaseOpeningService} 的两处退款对账。 */
    public static final String OPENING_REFUNDED = "OPENING_REFUNDED";

    /** 开箱请求过快 (新开箱冷却)。抛出点: {@code CaseOpeningService.enforceNewOpenRateLimit}。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** openingId 已属于其它玩家或其它箱子。抛出点: {@code CaseOpeningService.validateIdentity}。 */
    public static final String OPENING_ID_CONFLICT = "OPENING_ID_CONFLICT";

    /**
     * 回执体积超出下行 {@code writeUtf} 上限, 已被替换成本条定长回执。
     *
     * 与本表其余码不同, 它的抛出点不在任何 action handler 内, 而是
     * {@link WebUiServerDispatcher#respond} 这个 Gateway 收口 —— 因为撑爆回执的既可能是业务拒绝里回显的
     * 客户端入参, 也可能是聚合类 action 自己长出来的成功回执, 收口是唯一能一次盖住两者的位置。
     */
    public static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";

    /**
     * 权限不足 (当前只有 OP 一档)。抛出点: {@link WebUiPermissions#requireOp}, 由每条 admin.* 动作各自调用。
     *
     * 单立一码而不复用 {@link #INVALID_REQUEST}: 后者的语义是"入参形状或取值非法", 拿它表达权限拒绝会让
     * 前端把"你不是 OP"渲染成"某个字段填错了"。而在补出本码之前, 各 admin 动作只能二选一 —— 要么套
     * INVALID_REQUEST, 要么抛裸 {@code IllegalStateException} 落进 Gateway 的通用兜底; 两种都出现过,
     * 于是同一种拒绝有了两种回执形状。
     *
     * 抛业务异常而非裸异常还有一层: 派发器对裸异常走 {@code LOGGER.warn(..., e)} 打整条堆栈, 而权限拒绝是
     * 任何人都能无限次触发的 —— 那正是派发器注释里点名要防的 WARN 堆栈刷屏。
     */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /**
     * 该 requestId 已被处理过 (防重放窗口命中)。
     *
     * 与 {@link #RESPONSE_TOO_LARGE} 同类: 抛出点不在任何 action handler 内, 而在
     * {@link WebUiServerDispatcher#dispatchAndRespond} 这个 Gateway 收口的判重短路处产生。无 params。
     */
    public static final String DUPLICATE_REQUEST = "DUPLICATE_REQUEST";

    /**
     * Gateway 级每玩家令牌桶超限。抛出点: {@link WebUiServerDispatcher#dispatchAndRespond} 的限流门。无 params。
     *
     * 为什么不复用 {@link #RATE_LIMITED}: 后者的抛出点只有 {@code CaseOpeningService.enforceNewOpenRateLimit}
     * (只管 case.open 的新开箱冷却), 且前端刻意把开箱那一组码排除在 errorText 表外由 CasePage 自己展示原文;
     * 两者合表会让"整条 WebUI 通道被限流"渲染成一句开箱专属文案。
     */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    /**
     * 查注册表落空 (action 名未注册)。抛出点: {@link WebUiServerDispatcher#dispatchAndRespond}。无 params。
     *
     * 改用业务码而非裸异常的理由: 未知 action 是任何改版客户端都能无限触发的失败, 走裸异常会让每个垃圾包在
     * WARN 里写一条完整堆栈 (本表 {@link #PERMISSION_DENIED} 的 javadoc 已经点名过这个刷屏问题), 业务异常
     * 不分配堆栈也不打 WARN。
     */
    public static final String UNKNOWN_ACTION = "UNKNOWN_ACTION";

    /**
     * 该 action 不许进 system.batch。抛出点: {@code WebUiBatchAction} 的白名单门, 逐条判定 (不整批拒)。
     *
     * 存在的理由是聚合请求绕开了防重放: 一整批只占一个 requestId, 于是同一批里的 handler 无法各自享有
     * "同 id 只执行一次"的保护。只读 action 重放无害, 写 action 重放会二次扣款/二次发货, 故白名单是安全
     * 边界而不是性能清单。前端把某条写 action 误塞进批量时必须得到一个明确的拒绝码, 而不是静默照跑。
     */
    public static final String ACTION_NOT_BATCHABLE = "ACTION_NOT_BATCHABLE";

    /**
     * 单批条数超出 {@code WebUiBatchAction.MAX_CALLS}。抛出点同上, 但<b>整批拒</b> —— 条数本身非法时没有
     * "哪几条能跑"可言。params: {@code count} 与 {@code max}。
     */
    public static final String BATCH_TOO_LARGE = "BATCH_TOO_LARGE";

    /**
     * 挂单托管物的注册 id 当前解析不出来 (通常是托管时所属 mod 已被卸载, 或该物品 id 已变更):
     * 反序列化落地的是 {@code ItemStack.EMPTY} (1.20.1 的 defaulted 注册表兜底), 不是真实物品。
     * params: {@code listingId} 与 {@code itemId}。
     *
     * 抛出点两处: {@code MarketEngine.buy} 与 {@code MarketEngine.cancel}, 均在反序列化之后、
     * 任何扣款/改状态之前拦下, 拒绝时状态干净。
     */
    public static final String ESCROW_UNRESOLVABLE = "ESCROW_UNRESOLVABLE";
}
