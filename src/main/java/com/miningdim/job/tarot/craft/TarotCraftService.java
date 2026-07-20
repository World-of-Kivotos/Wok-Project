package com.miningdim.job.tarot.craft;

import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotLeveling;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端合成裁决 (TarotReader spec 第八章)。2 张同品质牌 -> 1 张高一档牌, 四结果按档位概率裁决:
 *  成功 (升档) / 逆转 (升档 + 翻面, 改正逆唯一途径) / 破碎 (耗 1 张返 1 碎片) / 大破碎 (耗 2 张返 2 碎片)。
 *
 * UR->闪耀需 L10 (spec 第八章: 闪耀品质牌唯一来源), 且无逆转结果 (闪耀不分正逆)。概率从 {@link TarotConfig}
 * 实时读 (C6); 大破碎概率 = 1 - 成功 - 逆转 - 破碎 (派生, 保证四率和恒 1)。RNG 服务端权威 (客户端无权预知)。
 */
public final class TarotCraftService {

    /** Exact server-configured outcome distribution used by both the roll and the client preview. */
    public record CraftChances(double success, double reverse, double shatter, double bigShatter) {
    }

    /** 四结果 (spec 第八章表)。 */
    public enum Result {
        SUCCESS, REVERSE, SHATTER, BIG_SHATTER
    }

    /** 合成产出: 结果 + 产物牌 (成功/逆转有, 破碎/大破碎为空) + 返还碎片数。 */
    public record CraftOutcome(Result result, ItemStack product, int shardRefund) {
    }

    /**
     * 裁决一次合成。输入两张牌必须同品质 (调用方/menu 已校验), 取首张的 cardId/朝向作产物基准。
     *
     * @param player    合成者 (UR->闪耀 校验 L10; 逆位翻面经 owner 重盖)
     * @param inputA    输入牌 A (取其 cardId 与正逆作产物基准)
     * @param inputB    输入牌 B (同品质)
     * @param rng       服务端 RNG
     * @return 四结果之一的产出 (product 为空表示无产物)
     */
    public CraftOutcome resolve(ServerPlayer player, ItemStack inputA, ItemStack inputB, RandomSource rng) {
        TarotQuality from = TarotCardItem.quality(inputA);
        TarotQuality to = from.next();
        if (to == null) {
            throw new IllegalArgumentException("cannot craft above top quality: " + from);
        }
        if (TarotCardItem.quality(inputB) != from) {
            throw new IllegalArgumentException("craft inputs must share quality: "
                    + from + " vs " + TarotCardItem.quality(inputB));
        }
        // UR->闪耀 等级门控 (spec 第八章): 需 L10。不足则裁决前直接拒绝 (调用 menu 已提示)。
        if (to == TarotQuality.SHINY && TarotLeveling.level(player) < TarotQuality.SHINY.requiredLevel()) {
            throw new IllegalStateException("UR->Shiny craft requires tarot L10");
        }

        Result result = decide(from, rng);
        int cardId = TarotCardItem.cardId(inputA);
        boolean upright = TarotCardItem.upright(inputA);

        return switch (result) {
            case SUCCESS -> {
                ItemStack product = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, to, upright, player.getUUID());
                TarotLeveling.grantCraftXp(player);
                yield new CraftOutcome(Result.SUCCESS, product, 0);
            }
            case REVERSE -> {
                // 逆转: 升档且翻面 (闪耀无此分支, reverse=0 不会落入)。
                ItemStack product = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, to, !upright, player.getUUID());
                TarotLeveling.grantCraftXp(player);
                yield new CraftOutcome(Result.REVERSE, product, 0);
            }
            // 破碎: 耗 1 张返 1 碎片 (调用方据此只消耗 1 张输入)。
            case SHATTER -> new CraftOutcome(Result.SHATTER, ItemStack.EMPTY, TarotConfig.DUPLICATE_SHARD_REFUND.get());
            // 大破碎: 耗 2 张返 2 碎片。
            case BIG_SHATTER -> new CraftOutcome(Result.BIG_SHATTER, ItemStack.EMPTY, TarotConfig.DUPLICATE_SHARD_REFUND.get() * 2);
        };
    }

    /**
     * 纯裁决: 仅按档位概率 + RNG 决定四结果之一, 无任何副作用 (不发经验/不造产物)。供 {@link #resolve} 与
     * TDD 概率断言共用 (测试用此路径不触 JobServices, 避免把未接线异常计成 success 污染统计; Minor 修正)。
     */
    public Result decide(TarotQuality from, RandomSource rng) {
        CraftChances chances = chances(from);
        double success = chances.success();
        double reverse = chances.reverse();
        double shatter = chances.shatter();
        double roll = rng.nextDouble();
        if (roll < success) {
            return Result.SUCCESS;
        }
        if (roll < success + reverse) {
            return Result.REVERSE;
        }
        if (roll < success + reverse + shatter) {
            return Result.SHATTER;
        }
        return Result.BIG_SHATTER;
    }

    /** Returns the complete four-way probability table for one source quality. */
    public static CraftChances chances(TarotQuality from) {
        double success = successChance(from);
        double reverse = reverseChance(from);
        double shatter = shatterChance(from);
        double bigShatter = Math.max(0.0D, 1.0D - success - reverse - shatter);
        return new CraftChances(success, reverse, shatter, bigShatter);
    }

    /** 产出一份塔罗碎片堆 (破碎/大破碎返还)。 */
    public static ItemStack makeShards(int count) {
        ItemStack shards = new ItemStack(TarotRegistry.TAROT_SHARD.get());
        shards.setCount(count);
        return shards;
    }

    private static double successChance(TarotQuality from) {
        return switch (from) {
            case R -> TarotConfig.CRAFT_R_SUCCESS.get();
            case SR -> TarotConfig.CRAFT_SR_SUCCESS.get();
            case SSR -> TarotConfig.CRAFT_SSR_SUCCESS.get();
            case UR -> TarotConfig.CRAFT_UR_SUCCESS.get();
            case SHINY -> throw new IllegalArgumentException("Shiny cannot be a craft input quality");
        };
    }

    private static double reverseChance(TarotQuality from) {
        return switch (from) {
            case R -> TarotConfig.CRAFT_R_REVERSE.get();
            case SR -> TarotConfig.CRAFT_SR_REVERSE.get();
            case SSR -> TarotConfig.CRAFT_SSR_REVERSE.get();
            case UR -> 0.0D; // UR->闪耀 无逆转 (spec 第八章)。
            case SHINY -> throw new IllegalArgumentException("Shiny cannot be a craft input quality");
        };
    }

    private static double shatterChance(TarotQuality from) {
        return switch (from) {
            case R -> TarotConfig.CRAFT_R_SHATTER.get();
            case SR -> TarotConfig.CRAFT_SR_SHATTER.get();
            case SSR -> TarotConfig.CRAFT_SSR_SHATTER.get();
            case UR -> TarotConfig.CRAFT_UR_SHATTER.get();
            case SHINY -> throw new IllegalArgumentException("Shiny cannot be a craft input quality");
        };
    }
}
