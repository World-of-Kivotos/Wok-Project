package com.miningdim.job.brewer;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 月光闪耀的永久良性词条池 (阶段 5(iv) 第 9 种特殊)。满 5 层月光 = 确定性抽中 5 条不重复词条 (玩家用
 * UUID.hashCode + 层 index 派生的确定性种子抽, 严禁 Math.random/挂钟; 抽中结果存进 {@link BrewBuffStore}
 * 以便登录重挂)。
 *
 * 全部词条都是【可干净重挂/移除】的良性增益 (固定 UUID 属性修饰 或 长时永久效果), 因 attribute/effect 不跨
 * 会话, 登录必须按存的选择重挂。每个词条一个稳定固定 UUID (移除无歧义); NIGHT_VISION 走 30 天长时 MobEffect
 * (死亡清, 登录重挂)。
 *
 * 池大小 8 (>5), 给抽取留随机空间; 满层抽 5 条不重复。月光以"赌博"立身, 但永久档良性 (设计: 永久随机良性,
 * 临时档才有翻车惩罚)。
 */
public enum MoonshinePerk {

    /** 击退抗性 +0.2 (站桩更稳)。 */
    KNOCKBACK_RES("knockback_res", Attributes.KNOCKBACK_RESISTANCE, 0.2D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0001-4001-8001-000000000001"),
    /** 护甲 +2。 */
    PLATED("plated", Attributes.ARMOR, 2.0D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0002-4002-8002-000000000002"),
    /** 护甲韧性 +2。 */
    TOUGH("tough", Attributes.ARMOR_TOUGHNESS, 2.0D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0003-4003-8003-000000000003"),
    /** 幸运 +1 (掉落/钓鱼小加成)。 */
    LUCKY("lucky", Attributes.LUCK, 1.0D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0004-4004-8004-000000000004"),
    /** 移速 +4% (小额机动)。 */
    SWIFT("swift", Attributes.MOVEMENT_SPEED, 0.04D, AttributeModifier.Operation.MULTIPLY_BASE,
            "f1a0c001-0005-4005-8005-000000000005"),
    /** 攻击击退 +0.5 (打得更远)。 */
    BRUTE("brute", Attributes.ATTACK_KNOCKBACK, 0.5D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0006-4006-8006-000000000006"),
    /** 近战攻击 +1 (小额近战; 枪走自己管线不吃)。 */
    VIGOR("vigor", Attributes.ATTACK_DAMAGE, 1.0D, AttributeModifier.Operation.ADDITION,
            "f1a0c001-0007-4007-8007-000000000007"),
    /** 永久夜视 (走长时 MobEffect, 登录重挂 + 周期刷新)。 */
    NIGHT_VISION("night_vision", null, 0.0D, null, "f1a0c001-0008-4008-8008-000000000008");

    private final String id;
    private final Attribute attribute;
    private final double amount;
    private final AttributeModifier.Operation operation;
    private final UUID modifierUuid;

    MoonshinePerk(String id, Attribute attribute, double amount, AttributeModifier.Operation operation, String uuid) {
        this.id = id;
        this.attribute = attribute;
        this.amount = amount;
        this.operation = operation;
        this.modifierUuid = UUID.fromString(uuid);
    }

    /** 稳定小写 id (持久化键 / lang key)。 */
    public String id() {
        return id;
    }

    /** 是否走 MobEffect 而非属性修饰 (仅夜视)。 */
    public boolean isEffect() {
        return attribute == null;
    }

    /** 永久夜视效果 (近无限时长, 登录重挂 + 周期刷新; 仅 NIGHT_VISION 词条用)。 */
    public MobEffect effect() {
        if (this != NIGHT_VISION) {
            throw new IllegalStateException("only NIGHT_VISION perk carries an effect: " + this);
        }
        return MobEffects.NIGHT_VISION;
    }

    /** 按小写 id 反查; 未知返 null (调用方短路, 不静默默认)。 */
    public static MoonshinePerk fromId(String id) {
        for (MoonshinePerk p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 确定性抽 {@code count} 条不重复词条 (玩家 UUID + 月光层固化时刻派生种子, 严禁挂钟/Math.random)。
     * 同一玩家同一固化输入恒得同一组 (登录重挂据存的 id 直接还原, 此处只是首次固化时的确定性抽取)。
     *
     * @param playerId 玩家 UUID (种子源 1)
     * @param count    抽取条数 (满 5 层 = 5; 须 1..{@link #values()}.length)
     * @return 不重复词条数组 (长度 = count)
     */
    public static MoonshinePerk[] rollDistinct(UUID playerId, int count) {
        MoonshinePerk[] all = values();
        if (count < 1 || count > all.length) {
            throw new IllegalArgumentException("moonshine perk count out of [1," + all.length + "]: " + count);
        }
        // Fisher-Yates 部分洗牌, 确定性种子 = UUID 两段 hashCode 异或 + 抽取条数 (层) 混入 (设计: UUID hashCode +
        // 层 index 派生; 严禁 Math.random/挂钟, 故登录据存还原前抽取本身也确定)。
        long seed = ((long) playerId.hashCode() << 32)
                ^ (long) Long.hashCode(playerId.getLeastSignificantBits())
                ^ (0x9E3779B97F4A7C15L * count);
        java.util.Random det = new java.util.Random(seed);
        MoonshinePerk[] pool = all.clone();
        for (int i = 0; i < count; i++) {
            int j = i + det.nextInt(pool.length - i);
            MoonshinePerk tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }
        MoonshinePerk[] picked = new MoonshinePerk[count];
        System.arraycopy(pool, 0, picked, 0, count);
        return picked;
    }

    /** 给玩家挂上本词条 (属性修饰固定 UUID 幂等, 或永久夜视效果)。 */
    public void apply(ServerPlayer player) {
        if (isEffect()) {
            // 永久夜视: 30 天长时 + ambient/不可见粒子 (死亡清, 登录重挂; 不靠 tick 刷新)。
            player.addEffect(new MobEffectInstance(effect(),
                    BrewPermanentBuffs.PERMANENT_EFFECT_DURATION_TICKS, 0, true, false, true));
            return;
        }
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return; // 玩家无此属性 (极端时序): 不抛, 静默跳过本词条 (其余词条不受影响)。
        }
        if (inst.getModifier(modifierUuid) != null) {
            inst.removeModifier(modifierUuid); // 幂等: 先移旧再加, 防重复登录叠两份。
        }
        inst.addTransientModifier(new AttributeModifier(modifierUuid, "miningdim.brewer.moonshine." + id, amount, operation));
    }

    /** 移除本词条 (死亡清 / 重挂前清)。无修饰则 no-op 幂等。 */
    public void remove(LivingEntity entity) {
        if (isEffect()) {
            entity.removeEffect(MobEffects.NIGHT_VISION);
            return;
        }
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null && inst.getModifier(modifierUuid) != null) {
            inst.removeModifier(modifierUuid);
        }
    }
}
