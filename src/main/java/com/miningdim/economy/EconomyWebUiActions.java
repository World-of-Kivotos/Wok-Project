package com.miningdim.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 玩家侧经济面板的 economy.* WebUiAction (挂机冻结态 / 今日 faucet 口径 / 高价矿收购价表)。
 *
 * 存在的理由是"最大 faucet 目前完全不可见": 挖矿收入同时受两层衰减挤压 —— 逐矿 steering
 * ({@link AbuseGuard#buyPrice}, 0.97 递减至 1% 地板) 与全服每人每日衰减主闸
 * ({@link AbuseGuard#faucetCreditAfterDecayExact}, 0.6 递减 / 60000 毛收入一档 / 1% 地板) —— 而玩家在游戏里
 * 没有任何入口能看到自己此刻处在哪一档, 只能感觉到"钱越挖越少"。本组 action 把这两层的真实自变量与真实
 * 结果原样露出来。
 *
 * 衰减公式在本层<b>一行都不重写</b>: 单价一律调 {@link AbuseGuard#buyPrice}, 主闸系数一律由
 * {@link AbuseGuard#faucetCreditAfterDecayExact} 对"再来一点毛收入"积分得出。抄一份 0.6^k 到面板层的后果是
 * 两份公式各自漂移, 而症状 (面板说 ×0.6, 到手却按 ×0.36) 极难归因。
 *
 * 为什么要 {@link EconomySystem} 实例而不是只经 {@link EconomyServices} 门面: 当日矿物计数
 * ({@link PlayerAbuseState#dailyOreCount}) 与最近一次有效挖掘 tick 只存在于经济子系统自持的玩家态表里,
 * {@link IEconomyService} 未开只读出口。面板必须读<b>事件路径正在写的那一份</b>态 —— 生产环境
 * {@link EconomyService} 的 stateResolver 就是 {@link EconomySystem#playerState}, 两条路径同一实例;
 * 面板另起一份则等于显示一个谁也没在用的影子账。
 *
 * 时间一律发 tick 与 UTC epochDay, 不发中文文案: 展示与本地化归前端 (专用服务端不加载 lang)。
 */
public final class EconomyWebUiActions {

    /**
     * 本类专用的 Gson: 必须 serializeNulls。
     *
     * economy.status 的 ticksSinceLastMine 在"从未有过有效挖掘"时是真 null (不是 0 —— 0 的意思是"刚刚挖过",
     * 与"从来没挖过"是相反的两件事)。默认 Gson 会把 null 成员整键丢掉, 前端拿到 undefined 即契约破裂。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 一个 UTC 日的毫秒数: faucet 计数器按 UTC epochDay 翻日, 翻日时刻即 (dayStamp + 1) 天的零点。 */
    private static final long MILLIS_PER_UTC_DAY = 86_400_000L;

    /**
     * 由 {@link #registerAll} 注入的经济子系统实例 (进程内唯一, 生命周期 = mod 进程)。
     *
     * volatile: 注入发生在 mod 构造线程, 读发生在服务器主线程 (派发经 enqueueWork 切主线程), 需要发布可见性。
     */
    private static volatile EconomySystem liveSystem;

    private EconomyWebUiActions() {
    }

    /**
     * 把三条 economy.* action 注册进派发器 (由 {@link EconomySystem#register} 调用一次)。
     *
     * 形参不是范式噪音: 面板要读的当日矿物计数与挂机态只存在于这个子系统实例里 (见类注释), 拿不到它就只能
     * 另造一份空态冒充真数据。
     */
    public static void registerAll(EconomySystem economySystem) {
        if (economySystem == null) {
            throw new IllegalArgumentException("EconomyWebUiActions requires the live EconomySystem instance");
        }
        liveSystem = economySystem;
        WebUiServerDispatcher.register("economy.status", STATUS);
        WebUiServerDispatcher.register("economy.today", TODAY);
        WebUiServerDispatcher.register("economy.priceTable", PRICE_TABLE);
    }

    /** 取已注入的经济子系统 (未接线时抛, 不返回 null 掩盖装配缺陷; 同 {@link EconomyServices#economyService} 纪律)。 */
    static EconomySystem system() {
        EconomySystem system = liveSystem;
        if (system == null) {
            throw new IllegalStateException(
                    "EconomyWebUiActions: EconomySystem not injected yet (EconomySystem.register calls registerAll)");
        }
        return system;
    }

    // ============================================================
    // economy.status: {} -> 挂机冻结态与它的两个判据阈值
    // ============================================================

    /**
     * 挂机经济冻结态 (18.4)。冻结期间挖到的高价矿既不计当日产量也不发钱, 而玩家侧此前只能靠"怎么挖都不涨钱"
     * 反推, 这条把它摆到台面上。
     *
     * afkFrozen 走 {@link IEconomyService#isAfkFrozen} 门面 (只读, 不触发评估 —— 评估时机归经济子系统的降频
     * tick); 距上次有效挖掘的 tick 数无门面出口, 读子系统自持的同一份玩家态 (见类注释)。
     *
     * 只发挖掘信号不发位移信号: 冻结判据是"无挖掘 && 无显著位移"两条同时成立, 但位移侧只留了一个滑动锚点
     * ({@link PlayerAbuseState#resetMoveAnchor}), 服务端并不记"静止了多久"。编一个位移秒数出来等于发假数据,
     * 故只发挖掘侧的真实值与两条判据各自的阈值, 由前端说明冻结需要两条同时成立。
     */
    static final WebUiAction STATUS = (sender, payload) -> {
        PlayerAbuseState state = system().playerState(sender.getUUID());

        JsonObject result = new JsonObject();
        result.addProperty("afkFrozen", EconomyServices.economyService().isAfkFrozen(sender));

        long lastBreakTick = state.lastBreakTick();
        if (lastBreakTick == Long.MIN_VALUE) {
            // 从未在矿山实例内挖过一次: 发 null 而不是 0 或一个巨大的差值 (直接相减会整数下溢成天文数字)。
            result.add("ticksSinceLastMine", JsonNull.INSTANCE);
        } else {
            result.addProperty("ticksSinceLastMine", sender.serverLevel().getGameTime() - lastBreakTick);
        }
        result.addProperty("afkNoMineTicks", EconomyConstants.AFK_NO_BREAK_TICKS);
        result.addProperty("afkNoMoveBlocks", EconomyConstants.AFK_NO_MOVE_BLOCKS);
        // 换算率随服务端发, 前端不得自己写死 20 (tick/秒换算错一次, 面板上的冷却与阈值全是错的)。
        result.addProperty("ticksPerSecond", EconomyConstants.TICKS_PER_SECOND);
        return GSON.toJson(result);
    };

    // ============================================================
    // economy.today: {} -> 今日 faucet 两栏 (口径刻意不对称) + 翻日时刻
    // ============================================================

    /**
     * 今日信用点/青辉石 faucet 的当日累计与当前衰减档位。
     *
     * 两栏口径不对称, 字段名就是唯一提示 (与 player.profile 同一套命名, 不另起):
     *  - {@code todayCreditFaucetGross} 是衰减主闸打折<b>之前</b>的毛额 (账本记的就是 rawCredit), 不是到手额;
     *  - {@code todayAzureIn} 走硬截断, 天然是实发额。
     * 前端必须逐栏写明, 严禁笼统合成一句"今日入账"。
     *
     * 只有一条信用点 faucet 计数器: 卖矿 / 卖菜 / 悬赏 / 精英贡献全部并入
     * {@link EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_KEY} 这一个键 (决策 3/4 的共享天花板), 服务端不存在
     * 分渠道的当日明细, 故本回执发一条汇总而不是一张分渠道表。
     */
    static final WebUiAction TODAY = (sender, payload) -> {
        IEconomyService economy = EconomyServices.economyService();
        // 两个计数器一次 SELECT 取回 (同表同 kind, 只是 counter_key 不同), 与 player.profile 同一取法。
        long[] today = economy.todayFaucetGross(sender, List.of(
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.AZURE_DAILY_FAUCET_KEY));
        // 日戳取经济子系统的时钟 (todayFaucetGross 内部用的也是它), 本层不另算一套 UTC epochDay ——
        // 两套时钟在跨午夜的那一次请求里会给出差一整天的答案, 且两边都"看起来对"。
        long dayStamp = economy.currentDayStamp();

        JsonObject result = new JsonObject();
        result.addProperty("dayStamp", dayStamp);
        result.addProperty("resetsAtUtcMillis", (dayStamp + 1L) * MILLIS_PER_UTC_DAY);
        result.addProperty("creditFaucetKey", EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY);
        result.addProperty("todayCreditFaucetGross", today[0]);
        // 档大小不是"每日总上限": 当日累计毛收入每满一档系数再乘 0.6, 每日实发总额远大于一档 (前 10 档 ≈ 14.9 万)。
        result.addProperty("creditFaucetTier", EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        result.addProperty("creditFaucetNextFactor", marginalCreditFactor(today[0]));
        result.addProperty("todayAzureIn", today[1]);
        result.addProperty("azureDailyCap", EconomyConstants.AZURE_DAILY_FAUCET_CAP);
        return GSON.toJson(result);
    };

    // ============================================================
    // economy.priceTable: {} -> 三种高价矿的锚价 + 今日实际收购价
    // ============================================================

    /**
     * 高价矿收购价表: 锚价 (静态) 与"下一颗到手多少"(随当日产量与当日 faucet 累计双向递减)。
     *
     * 两层串联的顺序与 {@link EconomyService#settleOreSale} 逐字一致, 一步不许重排:
     *  1. 逐矿 steering 毛值 = floor({@link AbuseGuard#buyPrice}(ore, minedToday + 1, 锚价));
     *  2. 该毛值再经衰减主闸 {@link AbuseGuard#faucetCreditAfterDecayExact} 折成净入账。
     * countSoFar 传 minedToday + 1 是因为 buyPrice 的 n 含本块: 玩家关心的是"我再挖一颗值多少", 传 minedToday
     * 会把已经卖掉的那一颗的价格当成下一颗的。
     *
     * minedToday 是<b>本人</b>当日产量 (18.3 的软上限本就是每玩家一份), 不是全服产量 —— 服务端不存在全服口径
     * 的矿物计数器。
     */
    static final WebUiAction PRICE_TABLE = (sender, payload) -> {
        IEconomyService economy = EconomyServices.economyService();
        AbuseGuard guard = system().abuseGuard();
        PlayerAbuseState state = system().playerState(sender.getUUID());

        long dayStamp = economy.currentDayStamp();
        long grossSoFar = economy.todayFaucetGross(
                sender, List.of(EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY))[0];

        JsonObject result = new JsonObject();
        result.addProperty("dayStamp", dayStamp);
        result.addProperty("todayCreditFaucetGross", grossSoFar);

        JsonArray anchors = new JsonArray();
        for (HighValueOre ore : HighValueOre.values()) {
            // 翻日清零由降频 tick 批量做, 且只覆盖当时在矿山维度内的玩家; 面板是纯查询, 绝不能顺手替它翻日
            // (一次查询把玩家的当日计数洗掉是灾难性副作用)。故日戳不符即按 0 读, 那一行原样留给 tick 去处理 ——
            // 与账本展示路径 peekFaucetToday 同一纪律。
            int minedToday = state.dayStamp() == dayStamp ? state.dailyOreCount(ore) : 0;
            double anchorPrice = ShopPriceTable.oreBasePrice(ore);
            long nextGross = (long) Math.floor(guard.buyPrice(ore, minedToday + 1, anchorPrice));

            JsonObject row = new JsonObject();
            row.addProperty("oreId", ore.name());
            Item item = pricedItem(ore);
            row.addProperty("itemId", ForgeRegistries.ITEMS.getKey(item).toString());
            // 翻译键: 中文名由前端经 client.i18n 解 (专用服务端不加载 lang)。
            row.addProperty("descriptionId", item.getDescriptionId());
            row.addProperty("anchorPrice", anchorPrice);
            row.addProperty("minedToday", minedToday);
            row.addProperty("dailySoftCap", guard.dailySoftCap(ore));
            row.addProperty("nextUnitGrossCredit", nextGross);
            row.addProperty("nextUnitNetCredit", netCreditFor(guard, grossSoFar, nextGross));
            anchors.add(row);
        }
        result.add("anchors", anchors);
        return GSON.toJson(result);
    };

    // ============================================================
    // 衰减取值 (一律委派 AbuseGuard, 本层不重写任何公式)
    // ============================================================

    /**
     * 当前档位下"再来 1 点毛收入"的实发系数 (1.0 = 尚未进入衰减)。
     *
     * 用主闸本尊对 raw=1 积分而不是自己算 {@code max(1%, 0.6^k)}: 档位边界、地板夹取、以及将来任何一次曲线
     * 调整都只有一处真值。返回值即系数本身 (积分 1 点毛收入的结果就是该点的系数)。
     */
    private static double marginalCreditFactor(long grossSoFar) {
        return system().abuseGuard().faucetCreditAfterDecayExact(
                grossSoFar, 1L, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
    }

    /**
     * 逐矿毛值经衰减主闸后的净入账 (未取整: 业务入账走 carry 跨笔累进小数, 面板提前取整会让玩家看到的价格与
     * 到手额恒差一点)。
     *
     * 毛值 &lt;= 0 时早返 0 而不是裸调主闸: 其契约要求 rawCredit &gt; 0 否则抛 ILLEGAL_AMOUNT。当前三种锚价在
     * 1% 地板下单颗毛值均 &gt;= 1 (钻 5 / 金 1.2 / 残骸 45) 不会触发, 与 {@link EconomyService#settleOreSale}
     * 的同款早返同纪律 —— 它守的是锚价被调低后不由面板炸在玩家脸上。
     */
    private static double netCreditFor(AbuseGuard guard, long grossSoFar, long nextGross) {
        if (nextGross <= 0L) {
            return 0.0D;
        }
        return guard.faucetCreditAfterDecayExact(
                grossSoFar, nextGross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
    }

    /**
     * 该矿种锚价所定价的<b>产物</b>物品。
     *
     * 计数按矿石方块 (18.3 用 BlockState 分类), 但 {@link ShopPriceTable} 的锚价挂在产物上 (其 javadoc:
     * 钻石 500 / 金锭 120 / 下界残骸 4500), 面板要显示的图标与名字也是产物。两者不是同一个东西, 故此处显式
     * 映射而不是拿矿石方块的物品形态凑数 (金矿石掉原矿、残骸要冶炼, 拿方块会把价目表标错标的)。
     */
    private static Item pricedItem(HighValueOre ore) {
        return switch (ore) {
            case DIAMOND -> Items.DIAMOND;
            case GOLD -> Items.GOLD_INGOT;
            case NETHERITE_SCRAP -> Items.NETHERITE_SCRAP;
        };
    }
}
