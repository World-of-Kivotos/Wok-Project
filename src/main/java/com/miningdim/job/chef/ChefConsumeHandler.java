package com.miningdim.job.chef;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 吃菜时结算 (Chef_Job_DesignSpec 第三/六章; 订阅 {@link LivingEntityUseItemEvent.Finish})。
 *
 * 任何人吃带品质章的菜 (跨 mod 通用, 经 {@link ChefQualityNbt} 读 NBT) 在进食完成时按品质 + 效果实例结算:
 *  - 增量/回味: 乘饱食/饱和 (受 20 饱食条上限自限);
 *  - 饱食: addEffect(JUMP);
 *  - 增香: 乘原 mod 菜自带 buff 时长 (查 {@link SeasoningBlacklist} 跳过金苹果/FID 战斗效果, 只乘时长);
 *  - 膳香: heal(%最大血量) (战斗向, 进食可打断由 Finish 仅在进食成功才触发天然保证);
 *  - 回甘: 清 N 个 debuff (战斗向解控);
 *  - 提神/夜照: addEffect (急速/夜视);
 *  - 窗口型 (耐饥/披甲/凝脂/余韵/稳膛): 经 {@link ChefWindowEffectState#stamp} 盖窗口 (eat-time 盖章,
 *    进食可打断兜底 = 吃完才盖);
 *  - 翻车负面 (夹生/烧焦/倒胃/多盐): 仅低/中/高 (盖章时 noFailure 档已不掷, 此处按 instance 直接结算)。
 *
 * 战斗向回血/护盾用 %最大血量 (服务器 80 血环境铁律); 严禁改最大生命值属性 (披甲走 absorption, 不改 maxHealth)。
 * 战斗向减伤/易伤经共享 ModEffects 仲裁, 本类不挂 LivingHurtEvent (凝脂减伤迁入玩家减伤单点结算, 见 {@link ChefGreaseReduction})。
 */
public final class ChefConsumeHandler {

    /** 原版饱食条上限 (增量自限)。 */
    private static final int MAX_FOOD = 20;

    /** 正在食用的一口菜事务；只在 Start->Finish 的短窗口存在，离开窗口一律清除。 */
    private static final Map<UUID, ConsumptionSnapshot> CONSUMPTIONS = new ConcurrentHashMap<>();

    record EffectSnapshot(int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon) {
        private MobEffectInstance restore(MobEffect effect) {
            return new MobEffectInstance(effect, duration, amplifier, ambient, visible, showIcon);
        }
    }

    private static final class ConsumptionSnapshot {
        private final net.minecraft.world.item.Item item;
        private final ChefQuality quality;
        private final int food;
        private final float saturation;
        private final java.util.Set<MobEffect> declaredTypes;
        private final Map<MobEffect, EffectSnapshot> declaredEffects;

        private ConsumptionSnapshot(ItemStack stack, LivingEntity entity, ChefQuality quality) {
            this.item = stack.getItem();
            this.quality = quality;
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                FoodData data = player.getFoodData();
                this.food = data.getFoodLevel();
                this.saturation = data.getSaturationLevel();
            } else {
                this.food = 0;
                this.saturation = 0.0F;
            }
            this.declaredTypes = declaredEffectTypes(stack, entity);
            this.declaredEffects = declaredEffectsBefore(entity, declaredTypes);
        }

        private boolean matches(ItemStack stack, ChefQuality currentQuality) {
            return item == stack.getItem() && quality == currentQuality;
        }
    }

    @SubscribeEvent
    public void onStartEating(LivingEntityUseItemEvent.Start event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        ChefQuality quality = ChefQualityNbt.readQuality(event.getItem());
        if (quality != null) {
            CONSUMPTIONS.put(entity.getUUID(), new ConsumptionSnapshot(event.getItem(), entity, quality));
        }
    }

    @SubscribeEvent
    public void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        ItemStack stack = event.getItem();
        ChefQuality quality = ChefQualityNbt.readQuality(stack);
        if (quality == null) {
            return; // 非厨师菜: 不结算。
        }
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return; // 服务端权威结算。
        }
        ConsumptionSnapshot snapshot = CONSUMPTIONS.remove(entity.getUUID());
        if (snapshot == null || !snapshot.matches(stack, quality)) {
            return;
        }
        List<ChefEffectInstance> effects = ChefQualityNbt.readEffects(stack);

        // 失败品: 销毁菜肴 (销毁已发生于物理消耗; 此处不返还任何饱食/不施加其它效果, 直接清空其余结算)。
        for (ChefEffectInstance inst : effects) {
            if (inst.type() == ChefEffectType.SPOILED) {
                // 失败品只撤销此事务带来的资源和本菜实际写入/刷新的效果，绝不倒扣食用前旧资源。
                restoreFailedConsumption(entity, snapshot);
                return;
            }
        }

        // 先扩饱食净增量，再扩饱和净增量，避免 NBT 效果顺序导致饱和提前被旧饱食值截断。
        effects.stream().filter(inst -> inst.type() == ChefEffectType.NOURISH_FOOD)
                .forEach(inst -> applyEffect(entity, quality, inst, stack, snapshot));
        effects.stream().filter(inst -> inst.type() == ChefEffectType.AFTERTASTE_SAT)
                .forEach(inst -> applyEffect(entity, quality, inst, stack, snapshot));
        effects.stream().filter(inst -> inst.type() != ChefEffectType.NOURISH_FOOD
                        && inst.type() != ChefEffectType.AFTERTASTE_SAT)
                .forEach(inst -> applyEffect(entity, quality, inst, stack, snapshot));
    }

    @SubscribeEvent
    public void onStopEating(LivingEntityUseItemEvent.Stop event) {
        CONSUMPTIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        CONSUMPTIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CONSUMPTIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        CONSUMPTIONS.remove(event.getEntity().getUUID());
    }

    private void applyEffect(LivingEntity entity, ChefQuality quality, ChefEffectInstance inst, ItemStack stack,
                             ConsumptionSnapshot snapshot) {
        switch (inst.type()) {
            case NOURISH_FOOD -> multiplyFoodNetGain(entity, inst.magnitude(), snapshot.food);
            case AFTERTASTE_SAT -> multiplySaturationNetGain(entity, inst.magnitude(), snapshot.saturation);
            case SATED_JUMP -> entity.addEffect(new MobEffectInstance(
                    MobEffects.JUMP, ChefConfig.satedJumpSeconds() * 20, inst.magnitude() - 1, false, true));
            case AMPLIFY -> amplifyDeclaredBuffs(entity, stack, inst.magnitude(), snapshot.declaredEffects);
            case NOURISH_HEAL -> applyHeal(entity, inst.magnitude());
            case PURIFY -> purifyDebuffs(entity, inst.magnitude());
            case REFRESH -> applyRefresh(entity, quality, inst.magnitude());
            case NIGHT_SIGHT -> entity.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, inst.magnitude() * 20, 0, false, true));
            case ENDURANCE -> stampWindow(entity, ChefEffectType.ENDURANCE, inst.magnitude(),
                    ChefConfig.enduranceSeconds(quality));
            case SATIATION -> stampWindow(entity, ChefEffectType.SATIATION, 0, ChefConfig.satiationSeconds(quality));
            case SHIELD -> applyShield(entity, inst.magnitude(), ChefConfig.shieldWindowSeconds());
            case GREASE -> stampWindow(entity, ChefEffectType.GREASE, inst.magnitude(),
                    ChefConfig.greaseWindowSeconds());
            case AFTERTASTE_REGEN -> stampWindow(entity, ChefEffectType.AFTERTASTE_REGEN, inst.magnitude(),
                    ChefConfig.regenWindowSeconds());
            case STABLE_AIM -> {
                entity.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                stampWindow(entity, ChefEffectType.STABLE_AIM, inst.magnitude(),
                        ChefConfig.stableAimWindowSeconds());
            }
            case FIRE_QUELL -> applyFireQuell(entity, quality, inst.magnitude());
            case GILLS -> applyGills(entity, inst.magnitude());
            case FEATHER -> applyFeather(entity, inst.magnitude());
            case FIREFLY -> applyFirefly(entity, inst.magnitude());
            case OVERSALT -> halveSaturation(entity);
            case UNDERDONE -> applyUnderdone(entity, quality, inst.magnitude());
            case SCORCHED -> applyScorched(entity, inst.magnitude());
            case NAUSEA -> applyNausea(entity, quality, inst.magnitude());
            case SPOILED -> { /* 已在 onFinishEating 前置处理。 */ }
        }
    }

    // ---- 饱食/饱和 (增量/回味, 受 20 上限自限) ----

    private void multiplyFoodNetGain(LivingEntity entity, int mulX100, int beforeFood) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        int gained = food.getFoodLevel() - beforeFood;
        if (gained <= 0) {
            return;
        }
        int boostedGain = (int) Math.round(gained * (mulX100 / 100.0D));
        food.setFoodLevel(Math.min(MAX_FOOD, beforeFood + boostedGain));
    }

    private void multiplySaturationNetGain(LivingEntity entity, int mulX100, float beforeSaturation) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        float gained = food.getSaturationLevel() - beforeSaturation;
        if (gained <= 0.0F) {
            return;
        }
        float boosted = beforeSaturation + gained * (mulX100 / 100.0F);
        // 饱和 <= 饱食自限。
        food.setSaturation(Math.min(food.getFoodLevel(), boosted));
    }

    private void halveSaturation(LivingEntity entity) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        food.setSaturation(food.getSaturationLevel() * 0.5F);
    }

    // ---- 膳香: %最大血量回血 (战斗向; 进食可打断由 Finish 天然保证) ----

    /** @param perMille %最大血量千分比基点 (1000 = 满血)。 */
    public void applyHeal(LivingEntity entity, int perMille) {
        float maxHp = entity.getMaxHealth();
        float heal = maxHp * (perMille / 1000.0F);
        if (heal > 0.0F) {
            entity.heal(heal);
        }
    }

    // ---- 披甲: 黄心护盾 (absorption, 严禁改 maxHealth) ----

    private void applyShield(LivingEntity entity, int perMille, int windowSeconds) {
        // 授予 absorption + 记窗口 + 过期回收, 全在状态机内 (单一真源; 非玩家实体不挂窗口故不授盾)。
        if (entity instanceof ServerPlayer player) {
            ChefWindowEffectState.stampShield(player, perMille, windowSeconds);
        }
    }

    // ---- 回甘: 清 N 个 debuff (战斗向解控) ----

    private void purifyDebuffs(LivingEntity entity, int count) {
        List<MobEffect> harmful = new ArrayList<>();
        for (MobEffectInstance inst : entity.getActiveEffects()) {
            if (inst.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                harmful.add(inst.getEffect());
            }
        }
        int toRemove = count >= 99 ? harmful.size() : Math.min(count, harmful.size());
        for (int i = 0; i < toRemove; i++) {
            entity.removeEffect(harmful.get(i));
        }
    }

    // ---- 提神: 清挖掘疲劳 + 急速 ----

    private void applyRefresh(LivingEntity entity, ChefQuality quality, int hasteLevel) {
        entity.removeEffect(MobEffects.DIG_SLOWDOWN);
        // chef-02: 急速时长按品质逐级 (90/150/240/360/600s), 取代旧硬编码 240s (与同文件夜照/耐饥等按品质分级一致)。
        int seconds = ChefConfig.refreshSeconds(quality);
        entity.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, seconds * 20, hasteLevel - 1, false, true));
    }

    private void applyFireQuell(LivingEntity entity, ChefQuality quality, int seconds) {
        entity.clearFire();
        stampWindow(entity, ChefEffectType.FIRE_QUELL, seconds, Math.max(1, seconds));
        if (seconds > 0) {
            entity.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, seconds * 20, 0, false, true));
        }
    }

    private void applyGills(LivingEntity entity, int seconds) {
        stampWindow(entity, ChefEffectType.GILLS, 0, seconds);
        entity.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, seconds * 20, 0, false, true));
        entity.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, seconds * 20, 0, false, true));
    }

    private void applyFeather(LivingEntity entity, int seconds) {
        if (seconds <= 0) {
            return;
        }
        stampWindow(entity, ChefEffectType.FEATHER, 0, seconds);
        entity.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, seconds * 20, 0, false, true));
    }

    private void applyFirefly(LivingEntity entity, int seconds) {
        stampWindow(entity, ChefEffectType.FIREFLY, 0, seconds);
        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, seconds * 20, 0, false, true));
    }

    // ---- 增香: 乘 "本菜自带 buff" 时长 (黑名单跳过金苹果/FID 战斗效果, 只乘时长不乘等级) ----

    private void amplifyDeclaredBuffs(LivingEntity entity, ItemStack stack, int mulX100,
                                     Map<MobEffect, EffectSnapshot> before) {
        if (SeasoningBlacklist.isItemBlacklisted(stack)) {
            return; // 物品级黑名单 (金苹果/附魔金苹果): 整菜不增香。
        }
        amplifyDeclaredBuffs(entity, declaredEffectTypes(stack, entity), mulX100, before);
    }

    /**
     * 按进食前快照放大本菜真实新增/刷新的效果时长；显式传入声明集合便于在冻结注册表的 GameTest
     * 中覆盖同一生产算法，无需运行时伪造或注册测试物品。
     */
    void amplifyDeclaredBuffs(LivingEntity entity, java.util.Set<MobEffect> ownEffects, int mulX100,
                              Map<MobEffect, EffectSnapshot> before) {
        for (MobEffect effect : ownEffects) {
            MobEffectInstance after = entity.getEffect(effect);
            if (after == null || SeasoningBlacklist.isEffectBlacklisted(after)) {
                continue;
            }
            EffectSnapshot old = before.get(effect);
            int oldDuration = old == null ? 0 : old.duration;
            boolean amplifierRefreshed = old != null && after.getAmplifier() != old.amplifier;
            int freshDuration = amplifierRefreshed
                    ? after.getDuration()
                    : after.getDuration() - oldDuration;
            if (freshDuration <= 0) {
                continue;
            }
            long extra = (long) freshDuration * (mulX100 - 100) / 100L;
            int duration = (int) Math.min(Integer.MAX_VALUE, after.getDuration() + extra);
            entity.addEffect(new MobEffectInstance(effect, duration, after.getAmplifier(), after.isAmbient(),
                    after.isVisible(), after.showIcon()));
        }
    }

    // ---- 翻车负面 (仅低/中/高) ----

    private void applyUnderdone(LivingEntity entity, ChefQuality quality, int chancePerMille) {
        if (entity.getRandom().nextInt(1000) >= chancePerMille) {
            return; // 未触发。
        }
        int seconds = ChefEffectMagnitude.underdoneSeconds(quality);
        // 随机轻 debuff (缓慢/挖掘疲劳/虚弱三选一), 短时低级。
        MobEffect[] light = {MobEffects.MOVEMENT_SLOWDOWN, MobEffects.DIG_SLOWDOWN, MobEffects.WEAKNESS};
        MobEffect picked = light[entity.getRandom().nextInt(light.length)];
        entity.addEffect(new MobEffectInstance(picked, seconds * 20, 0, false, true));
    }

    private void applyScorched(LivingEntity entity, int pctPerMille) {
        float maxHp = entity.getMaxHealth();
        float selfDamage = maxHp * (pctPerMille / 1000.0F);
        // 留 1 血兜底: 自伤不致死 (服务器死亡不掉落但仍不该被一口菜烧死)。
        float survivable = Math.min(selfDamage, Math.max(0.0F, entity.getHealth() - 1.0F));
        if (survivable > 0.0F) {
            entity.setHealth(entity.getHealth() - survivable);
        }
    }

    private void applyNausea(LivingEntity entity, ChefQuality quality, int poisonLevel) {
        // 中毒时长随品质分档 (spec 第十一章: 低 8s/中 6s/高 4s), 走 config 不硬编码 (C6); 等级在 magnitude。
        int seconds = ChefEffectMagnitude.nauseaSeconds(quality);
        entity.addEffect(new MobEffectInstance(MobEffects.POISON, seconds * 20, poisonLevel - 1, false, true));
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            FoodData food = player.getFoodData();
            food.setFoodLevel(Math.max(0, food.getFoodLevel() - 2)); // 扣饱食。
        }
    }

    // ---- 失败品: 回滚本次进食事务 ----

    private void restoreFailedConsumption(LivingEntity entity, ConsumptionSnapshot snapshot) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        food.setFoodLevel(snapshot.food);
        food.setSaturation(Math.min(snapshot.food, snapshot.saturation));
        for (MobEffect effect : snapshot.declaredTypes) {
            entity.removeEffect(effect);
            EffectSnapshot before = snapshot.declaredEffects.get(effect);
            if (before != null) {
                entity.addEffect(before.restore(effect));
            }
        }
    }

    static Map<MobEffect, EffectSnapshot> declaredEffectsBefore(LivingEntity entity,
                                                                 java.util.Set<MobEffect> types) {
        Map<MobEffect, EffectSnapshot> out = new HashMap<>();
        for (MobEffect effect : types) {
            MobEffectInstance instance = entity.getEffect(effect);
            if (instance != null) {
                out.put(effect, new EffectSnapshot(instance.getDuration(), instance.getAmplifier(), instance.isAmbient(),
                        instance.isVisible(), instance.showIcon()));
            }
        }
        return out;
    }

    private static java.util.Set<MobEffect> declaredEffectTypes(ItemStack stack, LivingEntity entity) {
        var props = stack.getFoodProperties(entity);
        java.util.Set<MobEffect> out = new java.util.HashSet<>();
        if (props == null) {
            return out;
        }
        for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> pair : props.getEffects()) {
            if (pair.getFirst() != null) {
                out.add(pair.getFirst().getEffect());
            }
        }
        return out;
    }


    // ---- 窗口型统一盖章入口 ----

    private void stampWindow(LivingEntity entity, ChefEffectType type, int magnitude, int windowSeconds) {
        if (entity instanceof ServerPlayer player) {
            ChefWindowEffectState.stamp(player, type, magnitude, windowSeconds);
        }
    }
}
