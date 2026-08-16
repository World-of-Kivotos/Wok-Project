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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端开包 RNG (TarotReader spec 第七章, 服务端权威)。
 *  - 普通包: 1 张 R + 随机 cardId + 随机正逆 (50/50, spec 13.4)。
 *  - 高级包: drawCount 张, 每张独立 SSR 概率 (其余 SR) + 派生包概率 (E<1 几何收敛, 派生只产高级包, 不升级)。
 *  - pity: 连续 N 个高级包未出 SSR 则下个高级包保底首张 SSR (spec 第七章; N 进 config)。
 *
 * 全部概率/张数从 {@link TarotConfig} 实时读 (C6 硬编码即缺陷)。开出的牌一律盖 ownerUUID (绑定; spec 第十章)。
 *
 * 重复牌转碎片 (spec 第七/十三章 6 反非酋承诺, F079 复核修正口径): 开包给牌前判定"是否净持有同 cardId+quality
 * 的牌"取 {@link TarotPackSavedData} 里持久化的已发-已耗净额账本, 并集当前背包与本包已发集合三者之一命中即算
 * 重复。为什么不能只看背包 —— 只看背包时开包前把牌存进箱子就能让"重复"永远判不中, 碎片毕业线形同虚设; 持久化
 * 账本记过的净额不会因为牌被转移出背包而清零。但塔罗牌是消耗品 (用牌/合成材料会真烧掉), 账本因而按
 * cardId+quality 精确记净额而非"是否曾经拿过"这个永久布尔: 打出的牌经 {@link com.miningdim.job.tarot.TarotPlayHandler}
 * 释放净额, 品质独立 (R 收过不挡 SSR 首次) —— 否则集齐 22 张后卡包永久停摆, 唯一补货口是碎片兑换, 掐死
 * "买包-用牌"核心循环 (复核追加发现)。已持则改发 {@link TarotConfig#DUPLICATE_SHARD_REFUND} 张碎片而非重复
 * 牌。攒够碎片经 {@code /tarot exchange <id>} (见 {@link com.miningdim.job.tarot.TarotSystem}) 确定性兑换
 * 指定牌 —— 给非洲玩家毕业线。
 *
 * pity 计数写入 {@link TarotPackSavedData}, 因而跨登出与服务端重启保留。
 * 派生包不产出物品 ItemStack 本身的 "包" —— 直接返回额外卡牌的 OpenResult.derivedPacks 计数, 由 use 层再开。
 */
public final class PackGachaService {

    /**
     * 一次开包结果: 给玩家的牌 + 重复牌转出的碎片总数 + 触发的派生高级包个数 (派生包就地再开, 并入产物)。
     * 重复牌不进 cards (改记 shardRefund), 由 use 层据 shardRefund 给等量碎片。
     */
    public record OpenResult(List<ItemStack> cards, int shardRefund, int derivedPacks) {
    }

    /**
     * 开一个普通包: 1 张 R, 随机 cardId + 随机正逆 (spec 第七章)。重复牌 (已持同 cardId) 改转碎片。
     */
    public OpenResult openCommon(ServerPlayer player, RandomSource rng) {
        TarotPackSavedData savedData = TarotPackSavedData.get(player.getServer().overworld());
        List<ItemStack> out = new ArrayList<>(1);
        Set<Integer> grantedThisPack = new HashSet<>();
        int shards = grantOrRefund(player, savedData, randomCardId(rng), TarotQuality.R, rng.nextBoolean(),
                out, grantedThisPack);
        return new OpenResult(out, shards, 0);
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
        TarotPackSavedData savedData = TarotPackSavedData.get(player.getServer().overworld());
        int streak = savedData.advancedNoSsrStreak(player.getUUID());

        List<ItemStack> out = new ArrayList<>(draws);
        Set<Integer> grantedThisPack = new HashSet<>();
        int shards = 0;
        boolean gotSsr = false;
        for (int i = 0; i < draws; i++) {
            // pity: 已连续 streak 个包未出 SSR 且本包首张, streak >= pityN 则首张保底 SSR。
            boolean pityForce = (i == 0 && streak >= pityN);
            boolean ssr = pityForce || rng.nextDouble() < ssrChance;
            TarotQuality q = ssr ? TarotQuality.SSR : TarotQuality.SR;
            if (ssr) {
                gotSsr = true;
            }
            // 重复牌转碎片 (spec 第七章): 已持同 cardId (或本包已发过) 改发碎片。品质保底 (pity/ssr) 仍按计数前判定,
            // 故 pity 不会因转碎片而失效 (pity 是 "本包必出 SSR 品质" 的承诺, 与具体 cardId 是否重复无关)。
            shards += grantOrRefund(player, savedData, randomCardId(rng), q, rng.nextBoolean(), out, grantedThisPack);
        }
        savedData.setAdvancedNoSsrStreak(player.getUUID(), gotSsr ? 0 : streak + 1);

        // 派生包: 每张产出独立判定一次派生 (期望 = draws * derivedChance, 已在入口断言 < 1 收敛)。
        int derived = 0;
        for (int i = 0; i < draws; i++) {
            if (rng.nextDouble() < derivedChance) {
                derived++;
            }
        }
        return new OpenResult(out, shards, derived);
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
        // 闪耀包是玩家显式自选 (青辉石高价), 不走重复转碎片 (自选即想要该牌, 转碎片反损玩家利益): 直接给 SSR。
        ItemStack card = makeCard(player, cardId, TarotQuality.SSR, rng.nextBoolean());
        // 自选拿到的牌同样计入净额账本 (F079 复核修正): 不 mark 的话, 玩家把这张牌放进箱子后, 普通/高级包
        // 还会再发一张真牌; 品质固定 SSR, 与 R/SR 档互不干扰。
        TarotPackSavedData.get(player.getServer().overworld())
                .markCollected(player.getUUID(), cardId, TarotQuality.SSR);
        return card;
    }

    /**
     * 给牌或转碎片 (spec 第七章重复牌转碎片, F079 复核修正口径): 玩家在持久化账本里对同 cardId+quality 的净额
     * 是否 > 0, 或背包 (含副手) 已持有同 cardId+quality, 或本包已发过同 cardId, 三者任一命中都不发重复牌, 改记
     * {@link TarotConfig#DUPLICATE_SHARD_REFUND} 张碎片返还; 否则发牌并记入本包已发集合与账本净额。
     *
     * 品质独立: R 已持不挡同 cardId 的 SSR 首次判重 (账本 key 含 quality)。playerOwnsCard 保留不删 —— 它是
     * op /give 等绕过账本发放的牌的兜底: 玩家手里确实有同 cardId+quality 的牌, 即便账本从没记过 (未经
     * PackGachaService/TarotShardExchange/TarotCraftMenu 发放), 命中它这一分支时顺带 markCollected 补录。
     *
     * @return 本次因重复转出的碎片数 (0 = 发了真牌)
     */
    private static int grantOrRefund(ServerPlayer player, TarotPackSavedData savedData, int cardId,
                                     TarotQuality quality, boolean upright,
                                     List<ItemStack> out, Set<Integer> grantedThisPack) {
        UUID id = player.getUUID();
        boolean duplicate = savedData.hasCollected(id, cardId, quality)
                || playerOwnsCard(player, cardId, quality)
                || grantedThisPack.contains(cardId);
        if (duplicate) {
            savedData.markCollected(id, cardId, quality);
            return TarotConfig.DUPLICATE_SHARD_REFUND.get();
        }
        out.add(makeCard(player, cardId, quality, upright));
        grantedThisPack.add(cardId);
        savedData.markCollected(id, cardId, quality);
        return 0;
    }

    /** 玩家背包 (含副手) 是否已持有某 cardId+quality 的塔罗牌 (任意朝向; 品质独立判重, 见类头说明)。 */
    private static boolean playerOwnsCard(ServerPlayer player, int cardId, TarotQuality quality) {
        for (ItemStack s : player.getInventory().items) {
            if (isCard(s, cardId, quality)) {
                return true;
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (isCard(s, cardId, quality)) {
                return true;
            }
        }
        return false;
    }

    /** 身份不可读的裸牌 (F075: 缺 CardId/Quality 键或键值越界) 视为"不是这张牌", 不参与重复判定 —— 见类头 F075 说明。 */
    private static boolean isCard(ItemStack stack, int cardId, TarotQuality quality) {
        return !stack.isEmpty() && stack.getItem() instanceof TarotCardItem
                && TarotCardItem.hasReadableCardIdentity(stack)
                && TarotCardItem.cardId(stack) == cardId
                && TarotCardItem.quality(stack) == quality;
    }

    private static int randomCardId(RandomSource rng) {
        return rng.nextInt(TarotArcana.COUNT);
    }

    private static ItemStack makeCard(ServerPlayer owner, int cardId, TarotQuality quality, boolean upright) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, quality, upright, owner.getUUID());
    }
}
