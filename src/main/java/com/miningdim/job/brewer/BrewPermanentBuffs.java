package com.miningdim.job.brewer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.UUID;

/**
 * 9 种闪耀永久特殊的施加 / 清除 / 周期维持 (阶段 5(iv))。所有特殊都是【一条命】: 喝闪耀酒固化层数 -> 据层
 * 施加; 死亡经 {@link #clearAll} 清空全部修饰/效果; 登录据 {@link BrewBuffStore} 存的层数经 {@link #remountAll}
 * 重挂 (属性/effect 不跨会话, 故必须重挂, 与塔罗防泄漏同纪律)。
 *
 * 分类:
 *  - 属性类 (固定 UUID transient 修饰, 改层即 reapply): 金酒最大生命 ({@link GinMaxHealthManager}, 带跨职业全局帽)、
 *    朗姆移速 (MOVEMENT_SPEED MULTIPLY_BASE)、龙舌兰近战 (ATTACK_DAMAGE ADDITION)。
 *  - 效果类 (永久长时 MobEffect, 登录重挂 + 周期刷新): 白兰地急迫 (按层放大, 满 5 = 急迫 III)、月光夜视词条。
 *  - 周期回血类 ({@link #tickPeriodicHeal}): 威士忌每 30 秒回 5%×层 最大血; 香槟每秒回 1%×层 最大血。
 *  - 减伤类: 伏特加烈酒钝感, 由 {@link #vodkaReductionRate} 供注册的命名减伤源连乘 (不在本类挂修饰, 在受击结算读层)。
 *  - 经验类: 茅台职业经验加成, 由 {@link #maotaiXpMultiplier} 供酿酒台发经验时乘 (在 brewer 包内的发放点乘, 不改框架)。
 *
 * 本类持金酒 maxHP 管理器实例 (单进程服务端单例, 由 {@link BrewerSystem} 装配)。
 */
public final class BrewPermanentBuffs {

    /** 永久效果近无限时长 (登录重挂 + 周期刷新维持; 不用 Integer.MAX_VALUE 防个别 mod 溢出, 取 30 天 tick)。 */
    public static final int PERMANENT_EFFECT_DURATION_TICKS = 20 * 60 * 60 * 24 * 30;

    /** 朗姆移速固定 UUID (MOVEMENT_SPEED 乘算修饰)。 */
    private static final UUID RUM_SPEED_UUID = UUID.fromString("c2e4f6a8-1b3d-4e5f-9a0c-2b4d6e8f0a1c");
    /** 龙舌兰近战固定 UUID (ATTACK_DAMAGE 加算修饰)。 */
    private static final UUID TEQUILA_ATTACK_UUID = UUID.fromString("d3f5a7b9-2c4e-4f6a-8b1d-3c5e7a9b1d2f");

    private final GinMaxHealthManager ginMaxHealth;

    public BrewPermanentBuffs(GinMaxHealthManager ginMaxHealth) {
        this.ginMaxHealth = ginMaxHealth;
    }

    // ---- 登录重挂 / 死亡清 (闭环) ----

    /** 登录据存的层数重挂全部永久特殊 (属性 + 效果 + 月光词条)。先清再挂, 防重复登录叠两份。 */
    public void remountAll(ServerPlayer player, BrewBuffStore store, double tarotBonus) {
        clearAttributesAndEffects(player);
        UUID id = player.getUUID();
        applyGin(player, store.layers(id, WineType.GIN), tarotBonus);
        applyRumSpeed(player, store.layers(id, WineType.RUM));
        applyTequilaAttack(player, store.layers(id, WineType.TEQUILA));
        applyBrandyHaste(player, store.layers(id, WineType.BRANDY));
        applyMoonshinePerks(player, store.moonshinePerks(id));
    }

    /** 死亡清空全部永久修饰/效果 (一条命语义)。层数由 {@link BrewBuffStore#clearAll} 另清。 */
    public void clearAll(ServerPlayer player) {
        clearAttributesAndEffects(player);
    }

    private void clearAttributesAndEffects(ServerPlayer player) {
        ginMaxHealth.remove(player);
        removeAttribute(player, Attributes.MOVEMENT_SPEED, RUM_SPEED_UUID);
        removeAttribute(player, Attributes.ATTACK_DAMAGE, TEQUILA_ATTACK_UUID);
        removeBrandyPermanentHaste(player); // 白兰地永久急迫; 只删本系统来源, 不误删厨师提神/信标/指令急迫 (F084)。
        for (MoonshinePerk perk : MoonshinePerk.values()) {
            perk.remove(player);
        }
    }

    // ---- 单类型施加 (喝酒固化 / 登录重挂共用) ----

    /** 金酒: base × 10%/层 最大生命, 经跨职业全局帽钳 (tarotBonus = 塔罗等其它额外最大生命之和)。 */
    public void applyGin(ServerPlayer player, int layers, double tarotBonus) {
        ginMaxHealth.apply(player, layers, tarotBonus);
    }

    /** 朗姆: +6%/层 移速 (MOVEMENT_SPEED MULTIPLY_BASE)。0 层即移除。 */
    public void applyRumSpeed(ServerPlayer player, int layers) {
        AttributeInstance inst = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) {
            return;
        }
        removeAttribute(player, Attributes.MOVEMENT_SPEED, RUM_SPEED_UUID);
        if (layers <= 0) {
            return;
        }
        double amount = BrewerConfig.RUM_MOVE_SPEED_PCT_PER_LAYER.get() * layers;
        inst.addTransientModifier(new AttributeModifier(RUM_SPEED_UUID, "miningdim.brewer.rum.speed",
                amount, AttributeModifier.Operation.MULTIPLY_BASE));
    }

    /** 龙舌兰: +3/层 近战攻击 (ATTACK_DAMAGE ADDITION; 枪走自己管线天然不吃)。0 层即移除。 */
    public void applyTequilaAttack(ServerPlayer player, int layers) {
        AttributeInstance inst = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (inst == null) {
            return;
        }
        removeAttribute(player, Attributes.ATTACK_DAMAGE, TEQUILA_ATTACK_UUID);
        if (layers <= 0) {
            return;
        }
        double amount = BrewerConfig.TEQUILA_ATTACK_PER_LAYER.get() * layers;
        inst.addTransientModifier(new AttributeModifier(TEQUILA_ATTACK_UUID, "miningdim.brewer.tequila.attack",
                amount, AttributeModifier.Operation.ADDITION));
    }

    /** 白兰地: 永久急迫, 按层放大 (满 5 = 急迫 III)。0 层即移除。永久长时 + 登录重挂 + 周期刷新维持。 */
    public void applyBrandyHaste(ServerPlayer player, int layers) {
        if (layers <= 0) {
            removeBrandyPermanentHaste(player); // 只删本系统来源 (F084), 不动其它来源的急迫。
            return;
        }
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED,
                PERMANENT_EFFECT_DURATION_TICKS, brandyHasteAmplifier(layers), true, false, true));
    }

    /** 白兰地永久急迫的来源识别门槛 (tick; 见 {@link #isBrandyPermanentHaste} 的判据修正说明)。 */
    private static final int BRANDY_HASTE_IDENTITY_DURATION_TICKS = 72_000;

    /**
     * 白兰地永久急迫的清理只删本系统那条 (F084, 判据经复核修正)。本系统施加的实例固定是
     * (PERMANENT_EFFECT_DURATION_TICKS≈30 天, ambient=true, visible=false, showIcon=true) —— 见上方
     * addEffect 调用参数。
     *
     * 判据【只看 duration】, 不再叠 isAmbient()/isVisible() (复核修正: 原三条合取会被 vanilla 就地改写打穿)。
     * 用 forge-1.20.1-47.3.0 官方源码核实 {@code MobEffectInstance.update} (合并同类效果时无条件执行的两行):
     * <pre>
     * if (!pOther.ambient &amp;&amp; this.ambient || flag) { this.ambient = pOther.ambient; }
     * if (pOther.visible != this.visible) { this.visible = pOther.visible; }
     * </pre>
     * 只要玩家之后被施加任何一次外来 DIG_SPEED (厨师提神 ambient=false/visible=true, 或原版信标
     * ambient=true/visible=true), 且外来 amplifier &lt;= 本系统当前层数对应的 amplifier (二者常见量级
     * 交叠), 上面两行会无条件把【本系统这一条已存在的实例】的 ambient/visible 永久改写掉 —— isAmbient()/
     * isVisible() 因此不可信。duration 不在此列: 同一分支下 duration 只在 "外来 amplifier 更高且外来更短"
     * 或 "同 amplifier 且外来更短" 时才会被替换 (:96-97/:101), 而本工程内全部已知外来来源的最长时长
     * (厨师提神 600 秒=12000 tick、信标数百 tick) 均远小于本系统的 30 天, 故 duration 恒不会被"更短"的
     * 外来效果覆盖 —— 唯一会覆盖 duration 的路径是外来 amplifier 严格更高 (如厨师满级 5 档=amp 4 > 本系统
     * 满层 amp 2), 此时 vanilla 会把本系统原实例整体推进 hiddenEffect 链 (数据不丢, 只是暂不在
     * getEffect() 的顶层可见), 待外来效果自然到期后 (:182-186 promote) 原样浮回顶层, 期间
     * duration&lt;=72000 让本判据正确识别"当前顶层不是我方", 跳过误删。
     *
     * 72000 tick (1 小时) 门槛取自"厨师提神最长档 600 秒=12000 tick、信标数百 tick"的来源判别阈值,
     * 不是平衡数值, 不进 config。无 DIG_SPEED 效果或判据不通过 (外来急迫当前顶层) 时不动玩家身上的效果。
     */
    private static void removeBrandyPermanentHaste(ServerPlayer player) {
        MobEffectInstance current = player.getEffect(MobEffects.DIG_SPEED);
        if (current != null && isBrandyPermanentHaste(current)) {
            player.removeEffect(MobEffects.DIG_SPEED);
        }
    }

    private static boolean isBrandyPermanentHaste(MobEffectInstance instance) {
        return instance.getDuration() > BRANDY_HASTE_IDENTITY_DURATION_TICKS;
    }

    /** 月光满层固化的良性词条逐条施加 (空 list 即无)。 */
    public void applyMoonshinePerks(ServerPlayer player, List<MoonshinePerk> perks) {
        for (MoonshinePerk perk : perks) {
            perk.apply(player);
        }
    }

    /**
     * 白兰地急迫放大等级 (0-indexed; 满 5 层 = 急迫 III = amp 2; 设计: 按层放大, 满 5 = III)。
     * 1-2 层 -> I(0), 3-4 层 -> II(1), 5 层 -> III(2)。
     */
    public static int brandyHasteAmplifier(int layers) {
        if (layers >= 5) {
            return 2;
        }
        if (layers >= 3) {
            return 1;
        }
        return 0;
    }

    // ---- 周期回血 (威士忌 / 香槟; 子系统 tick 调) ----

    /**
     * 周期回血结算 (玩家 tick 调): 威士忌按配置周期 (默认 600 tick = 30 秒) 回 5%×层 最大血; 香槟按配置周期
     * (默认 20 tick = 1 秒) 回 1%×层 最大血。永久效果 (急迫/夜视) 用 30 天长时 + 登录重挂维持, 不靠本 tick
     * 刷新 (故本方法只管回血)。
     *
     * @param player        玩家
     * @param whiskeyLayers 威士忌层 (0..5)
     * @param champagneLayers 香槟层 (0..5)
     */
    public void tickPeriodicHeal(ServerPlayer player, int whiskeyLayers, int champagneLayers) {
        float max = player.getMaxHealth();
        long t = player.tickCount;
        if (whiskeyLayers > 0 && t % BrewerConfig.WHISKEY_HEAL_INTERVAL_TICKS.get() == 0) {
            player.heal(periodicHealAmount(max, whiskeyLayers, BrewerConfig.WHISKEY_HEAL_PCT_PER_LAYER.get()));
        }
        if (champagneLayers > 0 && t % BrewerConfig.CHAMPAGNE_HEAL_INTERVAL_TICKS.get() == 0) {
            player.heal(periodicHealAmount(max, champagneLayers, BrewerConfig.CHAMPAGNE_HEAL_PCT_PER_LAYER.get()));
        }
    }

    /** 周期回血量 = 最大血 × 每层% × 层数 (纯函数, 便于测)。 */
    public static float periodicHealAmount(float maxHealth, int layers, double pctPerLayer) {
        return (float) (maxHealth * pctPerLayer * layers);
    }

    // ---- 减伤 (伏特加烈酒钝感; 注册的命名减伤源读层) ----

    /** 伏特加烈酒钝感减伤率 = 5%×层 (满 5 层 = 0.25); 由注册的命名减伤源连乘进单点结算。 */
    public static double vodkaReductionRate(int layers) {
        return BrewerConfig.VODKA_REDUCTION_PER_LAYER.get() * Math.max(0, layers);
    }

    // ---- 经验 (茅台职业经验加成; 酿酒台发经验时乘) ----

    /** 茅台职业经验乘子 = 1 + 10%×层 (满 5 层 = 1.5; 在 brewer 包内的发放点乘原始经验, 不改框架)。 */
    public static double maotaiXpMultiplier(int layers) {
        return 1.0D + BrewerConfig.MAOTAI_XP_PCT_PER_LAYER.get() * Math.max(0, layers);
    }

    /**
     * 读塔罗当前贡献的额外最大生命 (跨职业全局帽加总用; 塔罗已有公开只读 getter, 无需改塔罗)。塔罗运行期未装配
     * (极端时序/已 ServerStopping reset) 时返回 0 —— 全局帽降级为仅金酒自封顶, 不抛 (帽算是防叠不是硬约束)。
     */
    public static double tarotMaxHealthBonus(ServerPlayer player) {
        try {
            return com.miningdim.job.tarot.TarotRuntime.maxHealth().aggregateDelta(player);
        } catch (IllegalStateException notReady) {
            return 0.0D;
        }
    }

    private static void removeAttribute(LivingEntity entity, net.minecraft.world.entity.ai.attributes.Attribute attr, UUID uuid) {
        AttributeInstance inst = entity.getAttribute(attr);
        if (inst != null && inst.getModifier(uuid) != null) {
            inst.removeModifier(uuid);
        }
    }
}
