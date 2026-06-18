package com.miningdim.job.tarot.pack;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端开包 RNG (TarotReader spec 第七章, 服务端权威)。
 *  - 普通包: 1 张 R + 随机 cardId + 随机正逆 (50/50, spec 13.4)。
 *  - 高级包: drawCount 张, 每张独立 SSR 概率 (其余 SR) + 派生包概率 (E<1 几何收敛, 派生只产高级包, 不升级)。
 *  - pity: 连续 N 个高级包未出 SSR 则下个高级包保底首张 SSR (spec 第七章; N 进 config)。
 *
 * 全部概率/张数从 {@link TarotConfig} 实时读 (C6 硬编码即缺陷)。开出的牌一律盖 ownerUUID (绑定; spec 第十章)。
 *
 * pity 计数内存态 (UUID -> 连续未出 SSR 的高级包数); 登出清理。注: 跨重启不持久 (见 notes; 需持久化时挂 SavedData)。
 * 派生包不产出物品 ItemStack 本身的 "包" —— 直接返回额外卡牌的 OpenResult.derivedPacks 计数, 由 use 层再开。
 */
public final class PackGachaService {

    /** 一次开包结果: 给玩家的牌 + 触发的派生高级包个数 (派生包就地再开, 并入产物)。 */
    public record OpenResult(List<ItemStack> cards, int derivedPacks) {
    }

    private final Map<UUID, Integer> advancedNoSsrStreak = new HashMap<>();

    /**
     * 开一个普通包: 1 张 R, 随机 cardId + 随机正逆 (spec 第七章)。
     */
    public OpenResult openCommon(ServerPlayer player, RandomSource rng) {
        List<ItemStack> out = new ArrayList<>(1);
        out.add(makeCard(player, randomCardId(rng), TarotQuality.R, rng.nextBoolean()));
        return new OpenResult(out, 0);
    }

    /**
     * 开一个高级包 (从 {@link TarotConfig} 实时读旋钮): drawCount 张, 每张独立 SSR/SR; pity 命中则首张保底 SSR;
     * 计派生包个数 (几何收敛)。委派至显式参数重载 {@link #openAdvanced(ServerPlayer, RandomSource, int, double, double, int)}。
     */
    public OpenResult openAdvanced(ServerPlayer player, RandomSource rng) {
        return openAdvanced(player, rng,
                TarotConfig.ADVANCED_DRAW_COUNT.get(),
                TarotConfig.ADVANCED_SSR_CHANCE.get(),
                TarotConfig.ADVANCED_DERIVED_CHANCE.get(),
                TarotConfig.PITY_SSR_PACKS.get());
    }

    /**
     * 显式参数版高级包开包 (TDD 注入用: 可控 ssrChance/pityN 精确断言 pity 保底语义, 不依赖 config 加载)。
     * 派生期望硬约束 (spec 第七章): E = draws * derivedChance < 1 才几何收敛, 入口断言冒泡防印钞口。
     */
    public OpenResult openAdvanced(ServerPlayer player, RandomSource rng,
                                   int draws, double ssrChance, double derivedChance, int pityN) {
        if (draws * derivedChance >= 1.0D) {
            throw new IllegalStateException(
                    "advanced pack derived expectation must be < 1 (geometric convergence); got drawCount="
                            + draws + " * derivedChance=" + derivedChance + " = " + (draws * derivedChance));
        }
        UUID id = player.getUUID();
        int streak = advancedNoSsrStreak.getOrDefault(id, 0);

        List<ItemStack> out = new ArrayList<>(draws);
        boolean gotSsr = false;
        for (int i = 0; i < draws; i++) {
            // pity: 已连续 streak 个包未出 SSR 且本包首张, streak >= pityN 则首张保底 SSR。
            boolean pityForce = (i == 0 && streak >= pityN);
            boolean ssr = pityForce || rng.nextDouble() < ssrChance;
            TarotQuality q = ssr ? TarotQuality.SSR : TarotQuality.SR;
            if (ssr) {
                gotSsr = true;
            }
            out.add(makeCard(player, randomCardId(rng), q, rng.nextBoolean()));
        }
        advancedNoSsrStreak.put(id, gotSsr ? 0 : streak + 1);

        // 派生包: 每张产出独立判定一次派生 (期望 = draws * derivedChance, 已在入口断言 < 1 收敛)。
        int derived = 0;
        for (int i = 0; i < draws; i++) {
            if (rng.nextDouble() < derivedChance) {
                derived++;
            }
        }
        return new OpenResult(out, derived);
    }

    /**
     * 开一个闪耀包: 不在此直接产牌 (spec 第七章: 开出后自选一张 SSR, 走 {@link ShinyPackSelectMenu})。
     * 本方法仅校验并构造一张玩家自选的 SSR 牌 (服务端校验合法性后给物)。
     *
     * @param cardId 玩家在自选 GUI 选的牌 (0-21, 任意大阿卡纳; spec 13.8 自选范围=任意 SSR)
     */
    public ItemStack grantShinySelection(ServerPlayer player, int cardId, RandomSource rng) {
        if (cardId < 0 || cardId >= TarotArcana.COUNT) {
            throw new IllegalArgumentException("shiny pack selection cardId out of range: " + cardId);
        }
        // 闪耀包只产 SSR 品质 (spec 第七章: 不含 UR/闪耀); 正逆随机。
        return makeCard(player, cardId, TarotQuality.SSR, rng.nextBoolean());
    }

    /** 登出清 pity 内存态 (跨会话不持久; 见 notes)。 */
    public void clear(UUID player) {
        advancedNoSsrStreak.remove(player);
    }

    private static int randomCardId(RandomSource rng) {
        return rng.nextInt(TarotArcana.COUNT);
    }

    private static ItemStack makeCard(ServerPlayer owner, int cardId, TarotQuality quality, boolean upright) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, quality, upright, owner.getUUID());
    }
}
