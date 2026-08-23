package com.miningdim.job.chef;

import com.miningdim.effect.ModJobEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 厨师窗口效果的持久化协调器。效果是否有效、剩余时间和数值快照均以实体上的真实
 * {@link MobEffectInstance} 为唯一真源；服务器重启、登出和换维度不依赖进程内 UUID 计时表。
 *
 * <p>披甲额外保存本模块实际新增的黄心份额。玩家受到吸收伤害时优先消耗厨师份额；效果结束时只回收
 * 尚未消耗的厨师份额，不会误扣金苹果或其他模组授予的黄心。</p>
 */
public final class ChefWindowEffectState {

    private static final String SHIELD_TAG = "MiningChefShield";
    private static final String SHIELD_REMAINING = "remaining";
    private static final String SHIELD_LAST_ABSORPTION = "lastAbsorption";

    ChefWindowEffectState() {
    }

    /** 吃完后盖真实窗口效果；同种效果的刷新与强度裁决交给原版 MobEffectInstance。 */
    public static void stamp(ServerPlayer player, ChefEffectType type, int magnitude, int windowSeconds) {
        if (!type.isWindowed()) {
            throw new IllegalArgumentException("stamp called for non-windowed effect: " + type);
        }
        if (type == ChefEffectType.SHIELD) {
            throw new IllegalArgumentException("shield must be stamped through stampShield");
        }
        player.addEffect(new MobEffectInstance(effectFor(type), windowSeconds * 20,
                magnitude, false, false, true));
    }

    /** 授予披甲窗口并只登记实际新增的厨师黄心。刷新不会叠加；已有外来黄心会先计入目标总量。 */
    public static void stampShield(ServerPlayer player, int perMille, int windowSeconds) {
        float requested = player.getMaxHealth() * (perMille / 1000.0F);
        if (requested <= 0.0F) {
            return;
        }
        settleShieldUse(player);
        if (!active(player, ChefEffectType.SHIELD) && hasShieldState(player)) {
            reclaimShield(player);
        }

        float currentAbsorption = player.getAbsorptionAmount();
        float ownedBefore = shieldRemaining(player);
        float foreignAbsorption = Math.max(0.0F, currentAbsorption - ownedBefore);
        float requestedOwned = Math.max(0.0F, requested - foreignAbsorption);
        float ownedAfter = Math.max(ownedBefore, requestedOwned);
        float added = Math.max(0.0F, ownedAfter - ownedBefore);
        if (added > 0.0F) {
            player.setAbsorptionAmount(currentAbsorption + added);
        }
        writeShieldState(player, ownedAfter);
        player.addEffect(new MobEffectInstance(effectFor(ChefEffectType.SHIELD), windowSeconds * 20,
                perMille, false, false, true));
    }

    /** 生产逻辑只读取实体真实效果。 */
    public static boolean active(LivingEntity entity, ChefEffectType type) {
        return entity.hasEffect(effectFor(type));
    }

    /** MobEffect amplifier 保存配置快照的原始整数值，而不是传统的“等级减一”。 */
    public static int magnitudeOf(LivingEntity entity, ChefEffectType type) {
        MobEffectInstance instance = entity.getEffect(effectFor(type));
        return instance == null ? 0 : instance.getAmplifier();
    }

    static MobEffect effectFor(ChefEffectType type) {
        return switch (type) {
            case ENDURANCE -> ModJobEffects.CHEF_ENDURANCE.get();
            case SATIATION -> ModJobEffects.CHEF_SATIATION.get();
            case SHIELD -> ModJobEffects.CHEF_SHIELD.get();
            case GREASE -> ModJobEffects.CHEF_GREASE.get();
            case AFTERTASTE_REGEN -> ModJobEffects.CHEF_AFTERTASTE_REGEN.get();
            case STABLE_AIM -> ModJobEffects.CHEF_STABLE_AIM.get();
            case FIRE_QUELL -> ModJobEffects.CHEF_FIRE_QUELL.get();
            case GILLS -> ModJobEffects.CHEF_GILLS.get();
            case FEATHER -> ModJobEffects.CHEF_FEATHER.get();
            case FIREFLY -> ModJobEffects.CHEF_FIREFLY.get();
            default -> throw new IllegalArgumentException("not a chef window effect: " + type);
        };
    }

    /** 在线时持续核销已被伤害消耗的厨师黄心，并在真实效果到期后回收剩余份额。 */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        settleShieldUse(player);
        if (hasShieldState(player) && !active(player, ChefEffectType.SHIELD)) {
            reclaimShield(player);
        }
        tickAftertasteRegen(player);
        tickFirefly(player);
    }

    /** 登录可恢复跨重启保存的效果与披甲所有权；失配的旧披甲记录会立即安全回收。 */
    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settleShieldUse(player);
            if (hasShieldState(player) && !active(player, ChefEffectType.SHIELD)) {
                reclaimShield(player);
            }
        }
    }

    /** 登出只结算已消耗份额，真实效果和持久披甲记录随玩家 NBT 保存。 */
    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settleShieldUse(player);
        }
    }

    /** 换维度不清除窗口；只同步一次吸收份额，效果由原版实体迁移。 */
    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settleShieldUse(player);
        }
    }

    /** 死亡由原版清除效果和吸收值，本模块同步删除披甲所有权记录。 */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            player.getPersistentData().remove(SHIELD_TAG);
        }
    }

    static void settleShieldUse(ServerPlayer player) {
        if (!hasShieldState(player)) {
            return;
        }
        CompoundTag tag = player.getPersistentData().getCompound(SHIELD_TAG);
        float current = player.getAbsorptionAmount();
        float previous = tag.getFloat(SHIELD_LAST_ABSORPTION);
        if (current < previous) {
            float consumed = previous - current;
            tag.putFloat(SHIELD_REMAINING,
                    Math.max(0.0F, tag.getFloat(SHIELD_REMAINING) - consumed));
        }
        tag.putFloat(SHIELD_LAST_ABSORPTION, current);
        player.getPersistentData().put(SHIELD_TAG, tag);
    }

    static float shieldRemaining(ServerPlayer player) {
        return hasShieldState(player)
                ? player.getPersistentData().getCompound(SHIELD_TAG).getFloat(SHIELD_REMAINING)
                : 0.0F;
    }

    static void reclaimShield(ServerPlayer player) {
        settleShieldUse(player);
        if (!hasShieldState(player)) {
            return;
        }
        float owned = shieldRemaining(player);
        if (owned > 0.0F) {
            player.setAbsorptionAmount(Math.max(0.0F, player.getAbsorptionAmount() - owned));
        }
        player.getPersistentData().remove(SHIELD_TAG);
    }

    private static void writeShieldState(ServerPlayer player, float remaining) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(SHIELD_REMAINING, remaining);
        tag.putFloat(SHIELD_LAST_ABSORPTION, player.getAbsorptionAmount());
        player.getPersistentData().put(SHIELD_TAG, tag);
    }

    private static boolean hasShieldState(ServerPlayer player) {
        return player.getPersistentData().contains(SHIELD_TAG, Tag.TAG_COMPOUND);
    }

    private static void tickAftertasteRegen(ServerPlayer player) {
        if (player.tickCount % 20 != 0 || !active(player, ChefEffectType.AFTERTASTE_REGEN)) {
            return;
        }
        int totalPerMille = magnitudeOf(player, ChefEffectType.AFTERTASTE_REGEN);
        float heal = player.getMaxHealth() * (totalPerMille / 1000.0F) / ChefConfig.regenWindowSeconds();
        if (heal > 0.0F) {
            player.heal(heal);
        }
    }

    private static void tickFirefly(ServerPlayer player) {
        if (!active(player, ChefEffectType.FIREFLY)
                || player.tickCount % ChefConfig.fireflyParticleIntervalTicks() != 0) {
            return;
        }
        player.serverLevel().sendParticles(ParticleTypes.GLOW,
                player.getX(), player.getY() + 0.8D, player.getZ(), ChefConfig.fireflyParticleCount(),
                0.35D, 0.45D, 0.35D, 0.01D);
    }
}
