package com.miningdim.job.chef;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 增香黑名单 (Chef_Job_DesignSpec 第八/十二章第 5 项 + 平衡红线)。增香只乘原 buff 时长, 但必须排除:
 *  1. 原版金苹果 / 附魔金苹果 (其自带吸收/抗性是强战斗资源, 放大破红线);
 *  2. FID ~34 个状态效果里的战斗向 (糖浆装甲/火爆狂攻/椒麻麻痹/刺穿流血/酸蚀穿透 等)。
 *
 * 判定分两层 (鲁棒, 不依赖任一 mod 加载):
 *  A. 物品级: 被吃物是金苹果/附魔金苹果 -> 该物品所有 buff 都不增香;
 *  B. 效果级: 单个 buff 的 MobEffect 命中 FID 战斗向 id 集合 -> 仅该 buff 不增香; 同时 MobEffectCategory.HARMFUL
 *     的效果也不增香 (放大 debuff 时长无意义且对战斗向 debuff 反而有害), 作为兜底防漏判 FID 未登记 id。
 *
 * FID 战斗向 id 集合: 从仓库根 flavor_immersed_daily-1.1.0.3-forge-1.20.1.jar 的 lang en_us.json 实核
 * 34 个 effect.flavor_immersed_daily.* 注册 id, 据 spec 给出的战斗向样例 (装甲/狂攻/麻痹/流血/穿透/护盾)
 * 逐个判定。pinyin 与英文双拼写都在 jar 里出现, 此处取 jar 实际注册的英文 id (lang 即注册名)。
 * 软依赖: FID 未加载时该集合不命中任何效果 (id 解析返回 null), 不报错。
 */
public final class SeasoningBlacklist {

    private SeasoningBlacklist() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/chef");

    private static final String FID = "flavor_immersed_daily";

    /**
     * FID 战斗向调味效果注册 id (jar lang 实核; spec 第八章样例: 装甲/狂攻/麻痹/流血/穿透/护盾/灼烧)。
     * 不含纯增益续航向 (verdantvigor/marrownourishment/fennelserenity 等) 与纯负面已被 HARMFUL 兜底捕获。
     */
    private static final Set<String> FID_COMBAT_EFFECT_IDS = Set.of(
            "sugararmor",          // 糖浆装甲 (护盾/吸收)
            "syrupmania",          // 糖浆狂热 (攻击向)
            "fieryfrenzy",         // 火爆狂攻
            "numbingbind",         // 椒麻麻痹
            "blooding",            // 流血
            "acidicpenetration",   // 酸蚀穿透
            "gingeraegis",         // 生姜护盾 (减伤)
            "umbraguard",          // 暗影护盾
            "amberglue",           // 琥珀胶 (束缚/控制)
            "exoticscorch",        // 异域灼烧
            "fermentedinferno",    // 发酵地狱火
            "soulofthegrill",      // 烤魂 (攻击向)
            "garlicbanishment",    // 大蒜驱散 (控制)
            "tearfulmiasma",       // 催泪瘴气 (控制)
            "crimsonwarmth"        // 绯红温热 (战斗续航/狂暴向)
    );

    /**
     * 整个物品是否禁止增香 (物品级: 金苹果/附魔金苹果)。命中则该物品所有 buff 都不放大时长。
     */
    public static boolean isItemBlacklisted(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /**
     * 单个 buff 是否禁止增香 (效果级)。命中 FID 战斗向 id, 或 HARMFUL 分类 (兜底), 则该 buff 不放大。
     * 增香只该作用于 BENEFICIAL/NEUTRAL 的续航/探索类 buff。
     */
    public static boolean isEffectBlacklisted(MobEffectInstance instance) {
        MobEffect effect = instance.getEffect();
        // 兜底: 任何 HARMFUL 效果不增香 (放大 debuff 时长无意义, 且 FID 战斗 debuff 放大破红线)。
        if (effect.getCategory() == MobEffectCategory.HARMFUL) {
            return true;
        }
        var key = ForgeRegistries.MOB_EFFECTS.getKey(effect);
        if (key == null) {
            return false;
        }
        if (!FID.equals(key.getNamespace())) {
            return false;
        }
        return FID_COMBAT_EFFECT_IDS.contains(key.getPath());
    }

    /** 诊断: 启动期打印命中 FID 集合的实际加载效果数 (无 FID 时为 0, 不报错)。 */
    public static void logLoadedFidCombatEffects() {
        int hit = 0;
        for (String id : FID_COMBAT_EFFECT_IDS) {
            if (ForgeRegistries.MOB_EFFECTS.containsKey(new net.minecraft.resources.ResourceLocation(FID, id))) {
                hit++;
            }
        }
        LOGGER.info("[miningdim] chef amplify blacklist matched {} of {} declared FID combat effect ids loaded",
                hit, FID_COMBAT_EFFECT_IDS.size());
    }
}
