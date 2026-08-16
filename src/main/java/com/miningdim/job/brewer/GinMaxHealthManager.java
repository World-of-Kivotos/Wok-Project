package com.miningdim.job.brewer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 金酒永久最大生命管理器 (阶段 5(iv) 第 1 种特殊)。范式照搬塔罗 {@link com.miningdim.job.tarot.MaxHealthModifierManager}
 * 的固定 UUID + transient ADDITION 算法, 但用【金酒自己独立的固定 UUID】(不同于塔罗那一处), 使金酒与塔罗的
 * 最大生命修饰互不抹除、各自移除无歧义。本类是金酒 maxHP 修饰的唯一写入点。
 *
 * 单来源即可 (金酒按层数一次施加一个聚合 delta, 不像塔罗有多张牌并发来源), 故每玩家只记一个 delta。
 *
 * 跨职业全局帽 (设计锁定): 施加金酒前把【塔罗管理器 + 金酒管理器】的额外最大生命加总, 超
 * {@link BrewerConfig#GLOBAL_BONUS_MAX_HEALTH_CAP_PCT}×base 就把金酒本次削到不超帽 (防生命叠叠乐)。
 * 帽位钳算是纯函数 {@link #clampToGlobalCap}, 便于 GameTest 直测; 读塔罗总量在施加点经
 * {@code TarotRuntime.maxHealth().aggregateDelta} (塔罗已有公开只读 getter, 无需改塔罗)。
 *
 * transient (不写存档) + 一条命语义: 死亡/登出经子系统统一 {@link #remove} 清, 登录按层数重挂 (与塔罗防泄漏同纪律)。
 */
public final class GinMaxHealthManager {

    /** 金酒专属固定修饰 UUID (与塔罗 MaxHealthModifierManager 那一处不同, 避免互相 removeModifier 抹除)。 */
    private static final UUID GIN_MAX_HEALTH_UUID = UUID.fromString("a7c3e9f2-1b4d-4e6a-8f0c-3d5b7a9e1c2f");
    private static final String MODIFIER_NAME = "miningdim.brewer.gin.max_health";

    /** 玩家 UUID -> 当前金酒贡献的额外最大生命 (绝对 HP)。 */
    private final Map<UUID, Double> ginDelta = new HashMap<>();

    /**
     * 施加金酒永久最大生命 (按层数, 经全局帽钳后落属性)。先按 base × 每层% × 层数 算目标增量, 再用
     * {@code otherBonus} (塔罗等其它已有额外最大生命) 钳到全局帽, 最后落一条聚合 transient ADDITION 修饰。
     *
     * @param player     目标玩家
     * @param layers     当前金酒层数 (0..{@link BrewerConstants#MAX_LAYERS_PER_TYPE})
     * @param otherBonus 其它职业已贡献的额外最大生命之和 (如塔罗聚合 delta; 不含金酒本身)
     */
    public void apply(ServerPlayer player, int layers, double otherBonus) {
        AttributeInstance inst = player.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) {
            throw new IllegalStateException("player has no MAX_HEALTH attribute: " + player);
        }
        if (layers <= 0) {
            remove(player);
            return;
        }
        double base = inst.getBaseValue(); // 服 base (80 血), 修饰前的稳定锚。
        double desired = base * BrewerConfig.GIN_MAX_HEALTH_PCT_PER_LAYER.get() * layers;
        double allowed = clampToGlobalCap(desired, otherBonus, base);
        removeFrom(inst);
        if (allowed <= 0.0D) {
            ginDelta.remove(player.getUUID());
            return;
        }
        ginDelta.put(player.getUUID(), allowed);
        inst.addTransientModifier(new AttributeModifier(GIN_MAX_HEALTH_UUID, MODIFIER_NAME, allowed,
                AttributeModifier.Operation.ADDITION));
    }

    /** 移除金酒最大生命修饰 (死亡/登出/换维度; 无修饰 no-op 幂等)。当前 HP 超新 max 时显式钳。 */
    public void remove(LivingEntity entity) {
        AttributeInstance inst = entity.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null) {
            removeFrom(inst);
            if (entity.getHealth() > entity.getMaxHealth()) {
                entity.setHealth(entity.getMaxHealth());
            }
        }
        ginDelta.remove(entity.getUUID());
    }

    /** 当前金酒贡献的额外最大生命 (跨职业全局帽加总时供金酒自身那一份; 无则 0)。 */
    public double ginDelta(ServerPlayer player) {
        return ginDelta.getOrDefault(player.getUUID(), 0.0D);
    }

    /**
     * 把金酒本次拟增量钳到全局帽内 (纯函数, 便于测): 全局帽 = base × {@link BrewerConstants#GLOBAL_BONUS_MAX_HEALTH_CAP_PCT};
     * 金酒可占的额度 = 帽 - 其它来源已占 (otherBonus); 返回 max(0, min(desired, 剩余额度))。
     *
     * @param desired    金酒拟增的额外最大生命
     * @param otherBonus 其它职业已贡献的额外最大生命 (塔罗等)
     * @param base       玩家最大生命 base (服 80)
     * @return 金酒本次实际可施加的额外最大生命 (>=0)
     */
    public static double clampToGlobalCap(double desired, double otherBonus, double base) {
        double globalCap = base * BrewerConfig.GLOBAL_BONUS_MAX_HEALTH_CAP_PCT.get();
        double remaining = globalCap - Math.max(0.0D, otherBonus);
        if (remaining <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(desired, remaining));
    }

    private static void removeFrom(AttributeInstance inst) {
        if (inst.getModifier(GIN_MAX_HEALTH_UUID) != null) {
            inst.removeModifier(GIN_MAX_HEALTH_UUID);
        }
    }

    /** 金酒固定 UUID (测试/诊断)。 */
    public static UUID modifierUuid() {
        return GIN_MAX_HEALTH_UUID;
    }
}
