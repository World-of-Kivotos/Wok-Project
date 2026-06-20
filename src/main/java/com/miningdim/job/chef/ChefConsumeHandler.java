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
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

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
        List<ChefEffectInstance> effects = ChefQualityNbt.readEffects(stack);

        // 失败品: 销毁菜肴 (销毁已发生于物理消耗; 此处不返还任何饱食/不施加其它效果, 直接清空其余结算)。
        for (ChefEffectInstance inst : effects) {
            if (inst.type() == ChefEffectType.SPOILED) {
                // 失败品意味着这口白吃: 把刚获得的进食回复扣回 (原版已加, 这里抵消该菜默认 food/sat)。
                revertVanillaFood(entity, stack);
                return;
            }
        }

        for (ChefEffectInstance inst : effects) {
            applyEffect(entity, quality, inst, stack);
        }
    }

    private void applyEffect(LivingEntity entity, ChefQuality quality, ChefEffectInstance inst, ItemStack stack) {
        switch (inst.type()) {
            case NOURISH_FOOD -> multiplyFood(entity, inst.magnitude());
            case AFTERTASTE_SAT -> multiplySaturation(entity, inst.magnitude());
            case SATED_JUMP -> entity.addEffect(new MobEffectInstance(
                    MobEffects.JUMP, 60 * 20, inst.magnitude() - 1, false, true));
            case AMPLIFY -> amplifyExistingBuffs(entity, stack, inst.magnitude());
            case NOURISH_HEAL -> applyHeal(entity, inst.magnitude());
            case PURIFY -> purifyDebuffs(entity, inst.magnitude());
            case REFRESH -> applyRefresh(entity, inst.magnitude());
            case NIGHT_SIGHT -> entity.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, inst.magnitude() * 20, 0, false, true));
            case ENDURANCE -> stampWindow(entity, ChefEffectType.ENDURANCE, inst.magnitude(),
                    ChefConfig.enduranceSeconds(quality));
            case SHIELD -> applyShield(entity, inst.magnitude(), ChefConfig.SHIELD_WINDOW_SECONDS.get());
            case GREASE -> stampWindow(entity, ChefEffectType.GREASE, inst.magnitude(),
                    ChefConfig.GREASE_WINDOW_SECONDS.get());
            case AFTERTASTE_REGEN -> stampWindow(entity, ChefEffectType.AFTERTASTE_REGEN, inst.magnitude(),
                    ChefConfig.REGEN_WINDOW_SECONDS.get());
            case STABLE_AIM -> stampWindow(entity, ChefEffectType.STABLE_AIM, inst.magnitude(),
                    ChefConfig.STABLE_AIM_WINDOW_SECONDS.get());
            case OVERSALT -> halveSaturation(entity);
            case UNDERDONE -> applyUnderdone(entity, quality, inst.magnitude());
            case SCORCHED -> applyScorched(entity, inst.magnitude());
            case NAUSEA -> applyNausea(entity, quality, inst.magnitude());
            case SPOILED -> { /* 已在 onFinishEating 前置处理。 */ }
        }
    }

    // ---- 饱食/饱和 (增量/回味, 受 20 上限自限) ----

    private void multiplyFood(LivingEntity entity, int mulX100) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        int current = food.getFoodLevel();
        int boosted = (int) Math.round(current * (mulX100 / 100.0D));
        food.setFoodLevel(Math.min(MAX_FOOD, boosted)); // 自限 20。
    }

    private void multiplySaturation(LivingEntity entity, int mulX100) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        FoodData food = player.getFoodData();
        float current = food.getSaturationLevel();
        float boosted = current * (mulX100 / 100.0F);
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

    // ---- 提神: 清挖掘疲劳/缓慢 + 急速 ----

    private void applyRefresh(LivingEntity entity, int hasteLevel) {
        entity.removeEffect(MobEffects.DIG_SLOWDOWN);
        entity.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        entity.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 240 * 20, hasteLevel - 1, false, true));
    }

    // ---- 增香: 乘 "本菜自带 buff" 时长 (黑名单跳过金苹果/FID 战斗效果, 只乘时长不乘等级) ----

    private void amplifyExistingBuffs(LivingEntity entity, ItemStack stack, int mulX100) {
        if (SeasoningBlacklist.isItemBlacklisted(stack)) {
            return; // 物品级黑名单 (金苹果/附魔金苹果): 整菜不增香。
        }
        // spec 6.1 / 第 20 行: 增香只放大 "别的 mod 菜自带的 buff", 即本菜 FoodProperties 声明的 food effects。
        var props = stack.getFoodProperties(entity);
        if (props == null) {
            return; // 非食物 (理论不达, 盖章只在食物上): 无自带 buff 可放大。
        }
        // 本菜声明的 food effect 集合 (1.20.1 返回 List<Pair<MobEffectInstance, Float>>, Pair.first 为效果实例)。
        java.util.Set<MobEffect> ownEffects = new java.util.HashSet<>();
        for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> pair : props.getEffects()) {
            MobEffectInstance declared = pair.getFirst();
            if (declared != null) {
                ownEffects.add(declared.getEffect());
            }
        }
        amplifyDeclaredBuffs(entity, ownEffects, mulX100);
    }

    /**
     * 增香核心 (Chef_Job_DesignSpec 6.1 平衡红线): 仅放大 "本菜自带 buff" 的时长 (ownEffects = 本菜
     * FoodProperties 声明的 MobEffect 集合)。严禁对身上任意活跃 BENEFICIAL 效果放大 —— 那会乘到原版战斗
     * 药水 (力量/速度/抗性/再生/吸收)、信标 buff、前一道增香菜的残留窗外 buff, 直接破 "战斗向一律 %最大血量、
     * 不破枪战 attrition" 红线, 且连吃两道增香菜会对前菜已放大时长再乘一次 (复利叠加, 无上限)。
     *
     * 抽出为包级 + 显式 ownEffects 入参: 生产路径 {@link #amplifyExistingBuffs} 从 FoodProperties 算出集合传入;
     * GameTest 直接喂一个声明集合驱动同一逻辑 (无需注册带 buff 的测试食物即可断言 "外来 buff 不被改写")。
     *
     * @param entity     吃菜实体
     * @param ownEffects 本菜 FoodProperties 声明的效果集合 (空集 = 本菜不自带 buff, 无可放大)
     * @param mulX100    时长倍率 x100 (只乘时长不乘等级)
     */
    void amplifyDeclaredBuffs(LivingEntity entity, java.util.Set<MobEffect> ownEffects, int mulX100) {
        if (ownEffects.isEmpty()) {
            return; // 本菜不自带任何 buff: 无可增香 (避免乘到外来增益)。
        }
        // 仅放大活跃效果中其 MobEffect 属于本菜声明集合的实例 (信标/药水/前菜残留 buff 不在集合内, 一律跳过)。
        List<MobEffectInstance> snapshot = new ArrayList<>(entity.getActiveEffects());
        for (MobEffectInstance inst : snapshot) {
            if (!ownEffects.contains(inst.getEffect())) {
                continue; // 非本菜自带 buff: 不放大 (外来增益/前菜残留不被乘)。
            }
            if (SeasoningBlacklist.isEffectBlacklisted(inst)) {
                continue; // 效果级黑名单 (FID 战斗向/HARMFUL): 不放大。
            }
            int newDuration = (int) Math.min(Integer.MAX_VALUE, (long) inst.getDuration() * mulX100 / 100L);
            // 重新施加同等级、放大时长的实例 (覆盖原实例; 只乘时长, amplifier 不变)。
            entity.addEffect(new MobEffectInstance(inst.getEffect(), newDuration, inst.getAmplifier(),
                    inst.isAmbient(), inst.isVisible(), inst.showIcon()));
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

    // ---- 失败品: 抵消该菜默认进食回复 ----

    private void revertVanillaFood(LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }
        var props = stack.getFoodProperties(entity);
        if (props == null) {
            return;
        }
        FoodData food = player.getFoodData();
        // 抵消该菜默认进食回复 (失败品销毁 = 零回复语义): 先抵饱食, 再抵饱和。原版 eat() 同时加 food 与
        // saturation (satGained = nutrition * saturationModifier * 2, 钳 <= foodLevel), 只回退 food 会白送饱和
        // (隐藏续航), 与 "销毁菜肴" 语义不符。
        int newFoodLevel = Math.max(0, food.getFoodLevel() - props.getNutrition());
        food.setFoodLevel(newFoodLevel);
        float satGained = props.getNutrition() * props.getSaturationModifier() * 2.0F;
        // 饱和钳到 [0, 当前饱食]: 原版 saturation 不变量恒 <= foodLevel, 回退后维持此不变量。
        float newSat = Math.min(newFoodLevel, Math.max(0.0F, food.getSaturationLevel() - satGained));
        food.setSaturation(newSat);
    }

    // ---- 窗口型统一盖章入口 ----

    private void stampWindow(LivingEntity entity, ChefEffectType type, int magnitude, int windowSeconds) {
        if (entity instanceof ServerPlayer player) {
            ChefWindowEffectState.stamp(player, type, magnitude, windowSeconds);
        }
    }
}
