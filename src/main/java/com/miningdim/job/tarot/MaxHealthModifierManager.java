package com.miningdim.job.tarot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 最大生命增减管理 (TarotReader spec 第五/十二章红线)。教皇/女皇/世界/月亮/倒吊人等牌临时增减玩家 maxHealth。
 *
 * 红线 (spec 第十二章): 用固定 UUID 的 transient AttributeModifier (不写存档), 到期/登出/死亡/Clone/换维度
 * 统一 removeModifier 防泄漏 ("反复进出矿洞维度是最高频泄漏路径")。本类是 maxHealth 修饰符的唯一写入点。
 *
 * 多来源聚合 (C 修正): 两张最大生命牌可同时生效 (如教皇 +40 与世界逆位 -40)。本类按 "来源 token -> delta" 记账,
 * 单一固定 UUID 的 transient modifier 值 = 全部来源 delta 之和; apply/remove 都先按来源改账再重算聚合 modifier。
 * 这样: (1) 后施加的牌不会抹掉前一张的修饰; (2) 某来源到期回调 {@link #remove(LivingEntity, UUID)} 只回退本
 * 来源那一份, 不整体清零, 其余仍生效的牌不受影响。
 *
 * 增减语义 (spec 第五章): 增有上限 (capUp), 减有下限 (floorDown, maxHealth 修饰后不得低于此)。封顶/保底在
 * 每个来源 delta 入账时按 "当前 base + 已有聚合" 计算后钳制 (见 {@link #effectiveDelta})。
 */
public final class MaxHealthModifierManager {

    /** 固定修饰符 UUID (spec 第十二章: 全 mod 唯一一处, 便于无歧义 removeModifier; 值=全部来源聚合)。 */
    private static final UUID MAX_HEALTH_MODIFIER_UUID = UUID.fromString("8b1d0c2a-4f3e-4a6b-9c7d-1e2f3a4b5c6d");
    private static final String MODIFIER_NAME = "miningdim.tarot.max_health";

    /** 每个实体的来源账本 (sourceToken -> 该来源贡献的 delta)。聚合 modifier 值 = 各 delta 之和。 */
    private final Map<UUID, Map<UUID, Double>> sources = new HashMap<>();

    /**
     * 给实体施加一个来源的 maxHealth 修饰 (按来源累加, 不覆盖其它来源)。
     *
     * @param entity     目标 (玩家或生物)
     * @param source     本次施加的唯一来源 token (一张牌一次施加分配一个, 到期按此回退)
     * @param delta      最大生命增减量 (正增负减, 绝对 HP)
     * @param capUp      增向上限 (delta>0 时, 本来源增量不超过 capUp)
     * @param floorDown  减向下限 (delta<0 时, 聚合后 maxHealth 不低于 floorDown)
     */
    public void apply(LivingEntity entity, UUID source, double delta, double capUp, double floorDown) {
        AttributeInstance inst = entity.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) {
            throw new IllegalStateException("entity has no MAX_HEALTH attribute: " + entity);
        }
        Map<UUID, Double> ledger = sources.computeIfAbsent(entity.getUUID(), k -> new HashMap<>());
        double base = baseValue(inst); // 移除聚合修饰后的 maxHealth (含其它属性来源, 不含本管理器)。
        double currentAggregate = aggregate(ledger);
        double applied = effectiveDelta(delta, capUp, floorDown, base, currentAggregate);
        if (applied == 0.0D) {
            return; // 无净效果 (如减向已触底): 不入账, 不分配空来源。
        }
        ledger.put(source, applied);
        reapply(inst, ledger);
        clampCurrentHealth(entity);
    }

    /** 回退某来源的修饰 (到期/单源清理); 其余来源仍生效。无该来源则 no-op 幂等。 */
    public void remove(LivingEntity entity, UUID source) {
        Map<UUID, Double> ledger = sources.get(entity.getUUID());
        if (ledger == null || ledger.remove(source) == null) {
            return;
        }
        AttributeInstance inst = entity.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null) {
            reapply(inst, ledger);
        }
        if (ledger.isEmpty()) {
            sources.remove(entity.getUUID());
        }
    }

    /** 移除实体身上全部来源的 maxHealth 修饰 (登出/死亡/换维度统一调; 无修饰则 no-op 幂等)。 */
    public void remove(LivingEntity entity) {
        AttributeInstance inst = entity.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null) {
            removeFrom(inst);
        }
        sources.remove(entity.getUUID());
    }

    /** 当前是否有本管理器施加的 maxHealth 修饰 (测试/诊断)。 */
    public boolean hasModifier(ServerPlayer player) {
        AttributeInstance inst = player.getAttribute(Attributes.MAX_HEALTH);
        return inst != null && inst.getModifier(MAX_HEALTH_MODIFIER_UUID) != null;
    }

    /** 当前聚合 delta (全部来源之和; 测试/诊断)。 */
    public double aggregateDelta(ServerPlayer player) {
        Map<UUID, Double> ledger = sources.get(player.getUUID());
        return ledger == null ? 0.0D : aggregate(ledger);
    }

    /** 把一个来源 delta 钳到合法范围: 增向不超 capUp; 减向使聚合后 (base+aggregate+delta) 不低于 floorDown。 */
    private static double effectiveDelta(double delta, double capUp, double floorDown,
                                         double base, double currentAggregate) {
        if (delta > 0.0D) {
            return Math.min(delta, capUp);
        }
        if (delta < 0.0D) {
            double reducibleToFloor = Math.max(0.0D, base + currentAggregate - floorDown);
            return -Math.min(-delta, reducibleToFloor);
        }
        return 0.0D;
    }

    /** 用 ledger 聚合值重建单一 transient modifier (先移旧再加新, 保证 UUID 幂等)。 */
    private static void reapply(AttributeInstance inst, Map<UUID, Double> ledger) {
        removeFrom(inst);
        double total = aggregate(ledger);
        if (total == 0.0D) {
            return;
        }
        inst.addTransientModifier(new AttributeModifier(
                MAX_HEALTH_MODIFIER_UUID, MODIFIER_NAME, total, AttributeModifier.Operation.ADDITION));
    }

    /** 移除本管理器修饰后的 maxHealth (含其它属性来源, 不含本管理器聚合)。 */
    private static double baseValue(AttributeInstance inst) {
        boolean hadOurs = inst.getModifier(MAX_HEALTH_MODIFIER_UUID) != null;
        double ourDelta = hadOurs ? inst.getModifier(MAX_HEALTH_MODIFIER_UUID).getAmount() : 0.0D;
        return inst.getValue() - ourDelta;
    }

    private static double aggregate(Map<UUID, Double> ledger) {
        double sum = 0.0D;
        for (double d : ledger.values()) {
            sum += d;
        }
        return sum;
    }

    private static void clampCurrentHealth(LivingEntity entity) {
        // 减最大生命后若当前 HP 超过新 max, 显式钳 (原版多数情况会自动钳, 此处确保)。
        if (entity.getHealth() > entity.getMaxHealth()) {
            entity.setHealth(entity.getMaxHealth());
        }
    }

    private static void removeFrom(AttributeInstance inst) {
        if (inst.getModifier(MAX_HEALTH_MODIFIER_UUID) != null) {
            inst.removeModifier(MAX_HEALTH_MODIFIER_UUID);
        }
    }

    /** 供 attribute 工具 (静态读 attribute UUID 的测试) 暴露固定 UUID。 */
    public static UUID modifierUuid() {
        return MAX_HEALTH_MODIFIER_UUID;
    }

    static Attribute maxHealthAttribute() {
        return Attributes.MAX_HEALTH;
    }
}
