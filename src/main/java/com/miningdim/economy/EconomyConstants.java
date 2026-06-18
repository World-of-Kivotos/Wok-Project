package com.miningdim.economy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 反滥用经济闸门的默认数值 (设计文档第十八章 18.7 ForgeConfigSpec 表, 标 PENDING待校验 的初值照抄)。
 *
 * 为什么这些常量落在本子系统而非 {@link com.miningdim.core.IMiningConfig}:
 * core 的 {@code IMiningConfig} 门面 (阶段0 定稿, 不得改签名) 未暴露 18.7 的 {@code abuse.*} 键
 * (它只覆盖 16.2 的实例/分层/矿物/陷阱/danger/刷怪/出生/重置/性能各组)。在配置子系统 (ConfigSystem)
 * 把 {@code abuse.*} 接入 ForgeConfigSpec 并扩展配置门面之前, 本闸门子系统自带这些初值作为唯一来源,
 * 与 18.7 表逐项对齐。一旦 ConfigSystem 暴露 {@code abuse.*} getter, 这些常量应改为读配置 (留待接线),
 * 当前阶段它们是闸门生效所必需的真实数值, 不是占位。
 *
 * 注: 与重置冷却相关, core 的 {@code IMiningConfig.resetCooldownSeconds()} (16.2.9, 默认 300s=6000tick)
 * 与 18.2 的 {@code reset.cooldownTicks} (默认 6000tick) 数值同源等价; {@link AbuseGuard} 优先读配置门面的
 * 秒值换算 tick, 本类的 {@link #RESET_COOLDOWN_TICKS} 仅作配置缺省时的对齐基准与文档锚点。
 */
public final class EconomyConstants {

    private EconomyConstants() {
    }

    /** 每秒 tick 数 (原版固定 20), 用于秒<->tick 换算。 */
    public static final int TICKS_PER_SECOND = 20;

    // ---- 18.2 实例重置闸门 ----

    /** reset.cooldownTicks: 单实例两次重置最小冷却 (默认 6000 tick = 5 min)。 */
    public static final int RESET_COOLDOWN_TICKS = 6000;

    /** reset.costItem: 重置成本物品 (默认 minecraft:diamond)。 */
    public static final ResourceLocation RESET_COST_ITEM = new ResourceLocation("minecraft", "diamond");

    /** reset.costAmount: 重置成本数量 (默认 2)。 */
    public static final int RESET_COST_AMOUNT = 2;

    /** reset.dailyLimitPerInstance: 单实例每日重置次数上限 (默认 8)。 */
    public static final int RESET_DAILY_LIMIT_PER_INSTANCE = 8;

    /** reset.dayMode: 翻日口径 (默认 REAL = 服务端真实 UTC 日序)。 */
    public static final DayMode RESET_DAY_MODE = DayMode.REAL;

    // ---- 18.3 矿物产出软上限与收购价递减 ----

    /** economy.daily.diamond: 钻石每玩家每日软上限 (默认 64)。 */
    public static final int DAILY_SOFTCAP_DIAMOND = 64;

    /** economy.daily.netherite_scrap: 下界残骸每玩家每日软上限 (默认 8)。 */
    public static final int DAILY_SOFTCAP_NETHERITE_SCRAP = 8;

    /** economy.daily.gold: 金每玩家每日软上限 (默认 256)。 */
    public static final int DAILY_SOFTCAP_GOLD = 256;

    /** economy.decayBase: 逐矿收购价 steering 递减底数 (per-ore, 默认 0.97); buyPrice(n)=base*max(floor, decayBase^max(0,n-cap))。
     *  保留 0.97 仅作"逐矿吸引力 steering": 软上限内全额、超后逐矿温和递减, 引导玩家在撞主闸前优先卖高价矿。它不是收入封顶手段
     *  (封顶由下方衰减主闸 {@link #FAUCET_DECAY_BASE} 负责), 故底数与主闸 0.6 语义不同、不可共用 (第十一章决策 2/3)。 */
    public static final double ECONOMY_DECAY_BASE = 0.97D;

    /** 收购价/faucet 两层统一地板比例 (第十一章决策 1: 0.25 -> 0.01, 即 1%)。
     *  同时喂给 {@link AbuseGuard#buyPrice} (逐矿 steering 地板) 与 {@link AbuseGuard#faucetCreditAfterDecay} (衰减主闸地板):
     *  改这一处即令两层地板同时落到 1%。为何砍到 1%: 决策要求深档仍保留极薄收益 (不归零) 但远低于首档。注意 1% 地板使深档
     *  (累计毛收入 >=60 万) 不归零而留极薄线性收益 (每 60000 毛 +600, 不收敛、无数学硬顶); 该深档只有 xray/自动化挖得到,
     *  实操封顶靠反矿透/反挂机巡查 (用户决策: 保留 1% 地板 + 巡查兜底)。 */
    public static final double ECONOMY_PRICE_FLOOR_RATIO = 0.01D;

    // ---- 第十一章 衰减主闸 (取代硬上限): 统一信用点 faucet 软上限 + 几何衰减 ----

    /** 衰减主闸递减底数 (第十一章决策 2: 0.6)。当日累计毛收入每跨过一个完整 {@link #GLOBAL_DAILY_CREDIT_FAUCET_TIER} 档,
     *  本档内信用点的实发系数再乘一次 0.6, 夹 {@link #ECONOMY_PRICE_FLOOR_RATIO} (1%) 地板。
     *
     *  为何 0.6 而非逐矿的 0.97: 主闸是"收入封顶"手段。几何主项前 10 档 ≈ 14.9 万 (sum 60000*0.6^k, k=0..9 = 149093),
     *  正常游玩落点 ~10 万 (正常) / ~14.9 万 (硬肝), 基本撞顶。但 0.6^10≈0.006<0.01, 自第 10 档 (累计毛收入 >=60 万) 起
     *  系数被 1% 地板钳住恒定, 此后每多 60000 毛收入恒发 +600, 线性、不收敛、无数学硬顶; 该深档只有 xray/自动化挖得到,
     *  实操封顶靠反矿透/反挂机巡查 (用户决策: 保留 1% 地板 + 巡查兜底)。逐矿 0.97 衰减太缓只作引导, 故二者底数分离。 */
    public static final double FAUCET_DECAY_BASE = 0.6D;

    /** 衰减主闸单档大小 (第十一章决策 2: 60000 信用点毛收入/档)。注意是"每档毛收入", 不是"每日总上限":
     *  当日累计毛收入每满 60000 进入下一档, 下一档系数 ×0.6。每日实发总额不是 60000: 几何主项前 10 档 ≈ 14.9 万 (正常落点),
     *  其后 1% 地板留极薄线性尾巴 (每 60000 毛 +600, 不收敛、无数学硬顶, 靠巡查兜底)。所有信用点 faucet (矿工卖矿 / 农夫卖菜 /
     *  任务 / 刷怪) 传同一档值即并入同一每人每日衰减主闸 (决策 3/4)。 */
    public static final long GLOBAL_DAILY_CREDIT_FAUCET_TIER = 60000L;

    /** 全服统一信用点 faucet 计数键 (第十一章决策 3/4): 所有信用点 faucet 传同一键即并入同一 (playerId, key) 每日累计计数器,
     *  共享同一衰减主闸天花板。矿工卖矿 ({@link EconomyService#settleOreSale}) 与农夫卖菜 (FarmerWheatSellService) 均引用本常量,
     *  消灭字符串字面量副本漂移 (此前农夫私有声明同值字面量)。 */
    public static final String GLOBAL_DAILY_CREDIT_FAUCET_KEY = "credit_faucet";

    // ---- 18.4 AFK / 挂机检测 ----

    /** afk.noBreakTicks: 距上次有效挖掘超此 tick 判定无挖掘信号 (默认 2400 tick = 2 min)。 */
    public static final int AFK_NO_BREAK_TICKS = 2400;

    /** afk.noMoveBlocks: 滑动窗口内位移低于此格数判定无显著位移 (默认 4.0 格)。 */
    public static final double AFK_NO_MOVE_BLOCKS = 4.0D;

    // ---- 18.5 danger 重入冷却 ----

    /** reentry.cooldownTicks: 离开某实例后再进同实例的重入冷却 (默认 1200 tick = 1 min)。 */
    public static final int REENTRY_COOLDOWN_TICKS = 1200;

    /** reentry.retainRatio: 冷却内重入时 danger 的离开值保留系数 (默认 0.8)。 */
    public static final double REENTRY_RETAIN_RATIO = 0.8D;

    // ---- 18.6 死亡惩罚 ----

    /** death.reentryCooldownTicks: 死亡后再进入任意实例的全局冷却 (默认 1200 tick = 1 min)。 */
    public static final int DEATH_REENTRY_COOLDOWN_TICKS = 1200;

    /** death.dangerOnDeath: 死亡时 danger 处理 (默认 RESET_TO_ZERO, 死亡是 danger 的合法出口)。 */
    public static final DangerOnDeath DEATH_DANGER_MODE = DangerOnDeath.RESET_TO_ZERO;

    /** death.dropMode: 死亡掉落处理 (默认 KEEP_IN_PLACE, 掉落留在死亡点)。 */
    public static final DropMode DEATH_DROP_MODE = DropMode.KEEP_IN_PLACE;

    /** death.lockInstanceTicks: >0 时死亡后该玩家对该实例加再入冷却 (默认 0 = 不锁实例)。 */
    public static final int DEATH_LOCK_INSTANCE_TICKS = 0;

    // ---- 18.3 软上限映射所用的方块 / 物品 (用挖出方块对应掉落物计数) ----
    // 高价矿物按"挖出的矿石方块"计数 (BlockEvent.BreakEvent 拿 BlockState), 残骸用 ancient_debris 方块。

    /** 钻石矿石方块 (含深层): 命中即计入 diamond 当日计数。 */
    public static final Block ORE_DIAMOND = Blocks.DIAMOND_ORE;
    public static final Block ORE_DEEPSLATE_DIAMOND = Blocks.DEEPSLATE_DIAMOND_ORE;

    /** 金矿石方块 (含深层 + 下界金矿)。 */
    public static final Block ORE_GOLD = Blocks.GOLD_ORE;
    public static final Block ORE_DEEPSLATE_GOLD = Blocks.DEEPSLATE_GOLD_ORE;
    public static final Block ORE_NETHER_GOLD = Blocks.NETHER_GOLD_ORE;

    /** 古残骸方块: 命中即计入 netherite_scrap 当日计数 (冶炼前的方块即代表残骸产出)。 */
    public static final Block ORE_ANCIENT_DEBRIS = Blocks.ANCIENT_DEBRIS;

    /** 重置默认成本物品对应的 Items 句柄缓存键, 由 AbuseGuard 经 ForgeRegistries 反查; 此处仅暴露默认钻石。 */
    public static final net.minecraft.world.item.Item RESET_COST_ITEM_DEFAULT = Items.DIAMOND;

    /** 翻日口径枚举 (18.7 reset.dayMode)。 */
    public enum DayMode {
        /** 按维度 dayTime/24000 取整变化触发翻日。 */
        GAME,
        /** 按服务端真实 UTC 日序触发翻日。 */
        REAL
    }

    /** 死亡时 danger 处理枚举 (18.7 death.dangerOnDeath)。 */
    public enum DangerOnDeath {
        /** 死亡清零 danger (18.6 默认: 死亡是 danger 合法出口, 以掉落+冷却为代价)。 */
        RESET_TO_ZERO,
        /** 死亡保留 danger (硬核向, 死亡不缓解压力)。 */
        KEEP
    }

    /** 死亡掉落处理枚举 (18.7 death.dropMode)。 */
    public enum DropMode {
        /** 掉落物留在死亡点 region。 */
        KEEP_IN_PLACE,
        /** 缩短掉落物 despawn。 */
        DESPAWN_FAST,
        /** 直接清除掉落物 (硬核向)。 */
        VOID
    }

    /** 18.3 三类高价矿物的逻辑分类 (用于 dailyOreCount 的 key 与软上限映射)。 */
    public enum HighValueOre {
        DIAMOND,
        GOLD,
        NETHERITE_SCRAP
    }
}
