package com.miningdim.job.tarot;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.pack.PackGachaService;
import com.miningdim.job.tarot.pack.TarotPackSavedData;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.UUID;

/**
 * fix/tarot-guards 配套 GameTest: F022 (冷却持久) / F074 (演出补偿) / F075 (裸牌) / F079 (卡包账本)。
 * 只走真实闸门 (TEST_MODE 默认 false), 断言全部落到具体计数/状态, 删被测那段生产代码必挂。
 *
 * 不与 {@link TarotGameTests} / {@link TarotWebUiGameTests} 共享任何方法或字段 (各自归属不同 agent)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotGuardsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarot";

    // ============================================================
    // F022: 冷却不随会话内的周期性回收清空; 只有停服 clearAll() 才整表清
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shinyCooldownSurvivesSessionPurgeButClearAllResetsIt(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        TarotCooldownManager cd = new TarotCooldownManager();
        int cardId = 7;
        int shinyCdTicks = 14400; // 闪耀级分钟量级 CD (spec 9.5), 数量级足以在测试窗口内保持"未到期"。

        helper.assertTrue(cd.tryUse(player, cardId, shinyCdTicks, 0, true),
                "首次闪耀用牌必须通过并占用闪耀级 CD 表");
        helper.assertFalse(cd.tryUse(player, cardId, shinyCdTicks, 0, true),
                "同一闪耀牌紧接着再用必须被拒 (仍在冷却)");

        // 会话内的周期性回收 (F022 核心不变量): 立即 purge 不得把一个远未到期的闪耀 CD 冲掉,
        // 否则公服断线重连几十秒就能把 12 分钟量级的闪耀冷却白嫖归零, 闸门形同虚设。
        long now = helper.getLevel().getServer().getTickCount();
        cd.purgeExpired(now);
        helper.assertFalse(cd.tryUse(player, cardId, shinyCdTicks, 0, true),
                "purgeExpired() 不得清掉一个 endTick 远在 now 之后的活跃闪耀 CD (删掉这条保护本条必挂)");

        // 只有整表清 (停服路径) 才重置; 之后同一张闪耀牌走"换存档路径"必须能再次通过。
        cd.clearAll();
        helper.assertTrue(cd.tryUse(player, cardId, shinyCdTicks, 0, true),
                "clearAll() 之后同一张闪耀牌必须能再次 tryUse 通过 (删掉 clearAll 的清表逻辑本条必挂)");
        helper.succeed();
    }

    /**
     * F022 的回收验证 (purgeExpired 确实回收已过期的每卡 CD 条目, 不是空转)。
     *
     * 与任务原始描述的偏差 (主动报告): {@link TarotCooldownManager#trackedPlayers()} 的实现无条件并入
     * gcdEnd 的 key 集合, 而 purgeExpired 按其自身注释 ("GCD 表不在回收范围内") 从不清 gcdEnd; 只要调用过
     * 一次 tryUse, 该玩家 UUID 就会永远留在 gcdEnd 里, trackedPlayers() 在 purgeExpired 之后因而不可能
     * 降到 0。这不是每卡 CD 回收逻辑本身的缺陷, 只是 trackedPlayers() 这个测试探针把三张表无差别并集所致。
     * 故本用例改用"回收后可立即再次 tryUse 通过"作为可观测证据: purgeExpired 接受显式 now 参数 (不依赖
     * 真实 tick 推进), 若把它的移除逻辑删掉, 陈旧的 cardEnd 条目 (endTick=首次占用时刻+1) 仍会挡住这次
     * 用同一真实时钟 (未推进) 发起的重试, 断言必挂; 若移除逻辑真的生效, 表项已清, 重试必过。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purgeExpiredReclaimsExpiredPerCardCooldownEntries(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        TarotCooldownManager cd = new TarotCooldownManager();
        int cardId = 3;

        helper.assertTrue(cd.tryUse(player, cardId, 1, 0, false), "用极短 (1 tick) 的每卡 CD 占用表项");
        helper.assertTrue(cd.trackedPlayers() == 1, "占用后 trackedPlayers() 必须恰为 1");

        long now = helper.getLevel().getServer().getTickCount();
        cd.purgeExpired(now + 5); // 显式传入一个已越过 endTick(now+1) 的 now, 不依赖真实 tick 推进。

        helper.assertTrue(cd.tryUse(player, cardId, 1, 0, false),
                "purgeExpired 必须真的删掉已过期的每卡 CD 条目, 否则同一真实时钟下的重试会被陈旧 endTick 挡住");
        helper.succeed();
    }

    // ============================================================
    // F074: 演出被打断时补偿卡牌与冷却; 正常结算路径绝不触发补偿 (不许白送)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void interruptedCastRefundsCardAndClearsCooldown(GameTestHelper helper) {
        helper.assertFalse(TarotConfig.TEST_MODE.get(),
                "前置条件: 测试模式必须关 (开着后 owner/等级/CD 三道闸门全被跳过, 本用例的全部断言靠真实闸门生效)");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 本用例要真实等过 GCD + 结算延迟 (数十 tick), 是 tarot 批次里少数会存活多个 tick 的用例。
        // MockGameTestPlayers 不摆位置, 默认落在关卡固定出生点 (0.5, 61, 0.5) —— 本批次其余用例的 mock 玩家
        // 用完即在同一 tick 内 succeed, 不会撞上任何清场; 但本用例要跨 tick 存活, 若不挪进自己结构区域, 会在
        // 等待期间被"批次里另一个用例的结构区域清场"当成误入的实体一并丢弃 (playerHash 相同、removed 从
        // false 变 true, 实测复现: 首次 tryPlay 前 capability 尚在, 数十 tick 后二次 tryPlay 前玩家已被移除)。
        BlockPos ownStructurePos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.setPos(ownStructurePos.getX() + 0.5D, ownStructurePos.getY(), ownStructurePos.getZ() + 0.5D);
        player.getInventory().clearContent();
        int cardId = TarotArcana.FOOL.cardId(); // 正位 R = self_heal_over_time, 结算安全 (不会致死/瞬移)

        ItemStack stack = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, TarotQuality.R, true, player.getUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        boolean played = TarotPlayHandler.tryPlay(helper.getLevel(), player, stack, InteractionHand.MAIN_HAND);
        helper.assertTrue(played, "全新自持一级可用的 R 卡必须打出成功");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "提交语义: 打出的牌必须在提交时立刻扣掉, 不能等结算才扣 (F074 类头说明)");
        helper.assertFalse(TarotRuntime.cooldown().tryUse(player, cardId, 1, 0, false),
                "提交后演出未结束前, 该 cardId 必须处于冷却 (GCD 与每卡 CD 至少一项在挡)");

        TarotRuntime.castManager().cancel(player);

        int refundedCount = countMatchingCards(player, cardId, TarotQuality.R, true, player.getUUID());
        helper.assertTrue(refundedCount == 1,
                "cancel() 必须恰好退回 1 张同 cardId/同品质/同朝向/owner 为本人的牌, 实得 " + refundedCount);

        // GCD (spec 9.5, 默认 30 ticks) 不随本次退款清空 (F074: 只退每卡 CD 与卡本身); 必须真实推进 tick
        // 越过 GCD 窗口, 才能把"每卡 CD 被清掉"和"只是 GCD 还没到"这两件事区分开。此时若 clearCard 的
        // 移除逻辑被删掉, Fool 的 buff 档 CD (config 默认 500 tick, 远大于本次等待窗口) 仍会挡住 tryUse。
        int gcdTicks = TarotConfig.GCD_TICKS.get();
        helper.runAfterDelay(gcdTicks + 2, () -> {
            boolean cdCleared = TarotRuntime.cooldown().tryUse(player, cardId, 1, 0, false);
            helper.assertTrue(cdCleared,
                    "cancel() 必须清掉该卡的每卡 CD (F074); GCD 已过期, 此刻唯一可能挡住的只有陈旧的每卡 CD");

            helper.runAfterDelay(5, () -> {
                // 反向断言: 重新走一次正常 tryPlay, 结算完成后不得再补一张牌 (不许白送)。
                player.getInventory().clearContent();
                ItemStack second = TarotCardItem.create(
                        TarotRegistry.TAROT_CARD.get(), cardId, TarotQuality.R, true, player.getUUID());
                player.setItemInHand(InteractionHand.MAIN_HAND, second);
                boolean playedAgain = TarotPlayHandler.tryPlay(helper.getLevel(), player, second, InteractionHand.MAIN_HAND);
                helper.assertTrue(playedAgain, "GCD 与每卡 CD 窗口都已让开, 第二次打出必须成功");

                helper.runAfterDelay(TarotCastTiming.EFFECT_RESOLVE_TICKS + 4, () -> {
                    // 生产环境由 TarotSystem 的 ServerTickEvent 逐 tick 驱动同一个单例 castManager,
                    // 此处补调一次是幂等收尾 (pending 为空时直接 return), 不改变已发生的结算结果。
                    TarotRuntime.castManager().tick(helper.getLevel().getServer());
                    int leftover = countMatchingCards(player, cardId, TarotQuality.R, true, player.getUUID());
                    helper.assertTrue(leftover == 0,
                            "正常结算路径绝不能再触发一次丢弃补偿 (那等于白送一张牌; F074 回归红线), 实得 " + leftover);
                    helper.succeed();
                });
            });
        });
    }

    // ============================================================
    // F075: 裸牌 (无 NBT) 用牌流程必须友好拒绝, 不炸异常, 不被消耗
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bareCardIsRejectedByTryPlayWithoutThrowing(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack bareCard = new ItemStack(TarotRegistry.TAROT_CARD.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, bareCard);

        boolean played = false;
        try {
            played = TarotPlayHandler.tryPlay(helper.getLevel(), player, bareCard, InteractionHand.MAIN_HAND);
        } catch (RuntimeException thrown) {
            helper.fail("裸牌 (无 CardId/Quality/Upright NBT) 打出流程绝不能抛异常 (F075), 实际抛出了 " + thrown);
        }
        helper.assertFalse(played, "裸牌必须被拒绝, 不能当成合法牌打出");

        ItemStack stillInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        helper.assertTrue(!stillInHand.isEmpty()
                        && stillInHand.getItem() == TarotRegistry.TAROT_CARD.get()
                        && stillInHand.getCount() == 1,
                "被拒绝的裸牌必须原样留在主手, 不被消耗");
        helper.succeed();
    }

    // ============================================================
    // F079: 开包重复判定基于持久化账本 (TarotPackSavedData), 不是当前背包快照
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicatePackDrawUsesPersistedLedgerNotInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        PackGachaService gacha = new PackGachaService();
        long seed = 20260816L;

        PackGachaService.OpenResult first = gacha.openCommon(player, RandomSource.create(seed));
        helper.assertTrue(first.cards().size() == 1,
                "全新玩家的第一次普通开包必须发一张真牌, 实得 " + first.cards().size());
        int cardId = TarotCardItem.cardId(first.cards().get(0));
        for (ItemStack card : first.cards()) {
            ItemHandlerHelper.giveItemToPlayer(player, card);
        }

        // 模拟"放进箱子": 把刚拿到的牌从背包里彻底移除, 背包不再持有任何这张牌存在过的证据。
        player.getInventory().clearContent();

        TarotPackSavedData savedData = TarotPackSavedData.get(helper.getLevel().getServer().overworld());
        helper.assertTrue(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.R),
                "持久化账本必须在牌离开背包之后仍记着这个 cardId+R 品质已收集过, 账本键 " + cardId);

        // 同一颗种子重建 RandomSource: 第一次调用产出的 cardId 必然与上面相同, 精确复现"再次抽到同一张"。
        PackGachaService.OpenResult second = gacha.openCommon(player, RandomSource.create(seed));
        boolean drewSameIdAgain = second.cards().stream()
                .anyMatch(s -> TarotCardItem.cardId(s) == cardId);
        helper.assertFalse(drewSameIdAgain,
                "背包已清空的情况下重抽到同一 cardId 仍必须判重复 (F079: 只看背包会被绕过), 实得含真牌");
        helper.assertTrue(second.cards().isEmpty(),
                "普通包只产 1 张; 命中重复时这张必须整包转碎片, 不产出物品, 实得 " + second.cards().size() + " 张");
        helper.assertTrue(second.shardRefund() == TarotConfig.DUPLICATE_SHARD_REFUND.get(),
                "重复必须精确返还 DUPLICATE_SHARD_REFUND 张碎片, 期望 " + TarotConfig.DUPLICATE_SHARD_REFUND.get()
                        + " 实得 " + second.shardRefund());
        helper.succeed();
    }

    /**
     * 复核追加 (finding 1/7): 塔罗牌是消耗品, 净额账本必须品质独立且随牌被烧掉而释放, 否则集齐 22 张后
     * 卡包永久停摆, 只剩 40 碎片一张的兑换通道, 掐死"买包-用牌"核心循环。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void consumedCardReleasesLedgerAndQualityIsIndependent(GameTestHelper helper) {
        helper.assertFalse(TarotConfig.TEST_MODE.get(),
                "前置条件: 测试模式必须关 (开着后 tryPlay 的三道闸门被跳过, 本用例靠真实用牌路径触发释放)");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        TarotPackSavedData savedData = TarotPackSavedData.get(helper.getLevel().getServer().overworld());
        int cardId = TarotArcana.FOOL.cardId(); // 正位 R = self_heal_over_time, 结算安全 (同 F074 用例选择)

        // 品质独立: R 品质记过收集不得连带挡住同 cardId 的 SSR 首次判重 (finding 1 原始报告场景之一)。
        savedData.markCollected(player.getUUID(), cardId, TarotQuality.R);
        helper.assertTrue(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.R),
                "R 品质 markCollected 之后 hasCollected(R) 必须为真");
        helper.assertFalse(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.SSR),
                "R 品质记过收集不能连带挡住同 cardId 的 SSR 首次判重 (复核 finding 1: 账本不分品质会永久锁死同名 SSR)");

        // 消耗品语义: 打出一张牌之后, 该 cardId+quality 的净额必须归零, 允许再从卡包拿回来。
        ItemStack stack = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, TarotQuality.R, true, player.getUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        boolean played = TarotPlayHandler.tryPlay(helper.getLevel(), player, stack, InteractionHand.MAIN_HAND);
        helper.assertTrue(played, "全新自持一级可用的 R 卡必须打出成功");
        helper.assertFalse(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.R),
                "打出的牌必须释放账本净额 (消耗品语义), 否则玩家用掉唯一一张牌后卡包永远只发碎片 (复核 finding 1 核心场景), 删掉释放调用本条必挂");
        helper.succeed();
    }

    /**
     * 复核追加 (finding 6/7): 碎片兑换是开包之外唯一的"确定性发牌"通道, 若不入账, "放进箱子规避重复判定"
     * 对这条通道完全无效 —— 兑到的牌只要转手放箱子, 账本空白, 下次开包照样能再抽到一张真牌。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shardExchangeEntersCollectedLedger(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        TarotPackSavedData savedData = TarotPackSavedData.get(helper.getLevel().getServer().overworld());
        int cardId = TarotArcana.MAGICIAN.cardId();

        int cost = TarotConfig.SHARD_EXCHANGE_COST.get();
        ItemStack shards = new ItemStack(TarotRegistry.TAROT_SHARD.get(), cost);
        ItemHandlerHelper.giveItemToPlayer(player, shards);

        helper.assertFalse(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.SSR),
                "兑换前账本不应已记这张牌");
        TarotShardExchange.ExchangeResult result = TarotShardExchange.exchange(player, cardId, true);
        helper.assertTrue(result.success(), "碎片足额 (" + cost + " 张) 时兑换必须成功");
        helper.assertTrue(savedData.hasCollected(player.getUUID(), cardId, TarotQuality.SSR),
                "碎片兑换发出的牌必须同步计入净额账本 (复核 finding 6/7), 否则该牌能无限重开真牌绕开毕业线");
        helper.succeed();
    }

    // ============================================================
    // 共享断言辅助
    // ============================================================

    /** 逐格扫描主背包 + 副手, 精确计数匹配 cardId/品质/朝向/owner 的塔罗牌张数 (不接受 !isEmpty 弱判据)。 */
    private static int countMatchingCards(ServerPlayer player, int cardId, TarotQuality quality,
                                           boolean upright, UUID owner) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (matchesCard(stack, cardId, quality, upright, owner)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (matchesCard(stack, cardId, quality, upright, owner)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean matchesCard(ItemStack stack, int cardId, TarotQuality quality,
                                        boolean upright, UUID owner) {
        if (stack.isEmpty() || !(stack.getItem() instanceof TarotCardItem)
                || !TarotCardItem.hasReadableCardIdentity(stack)) {
            return false;
        }
        return TarotCardItem.cardId(stack) == cardId
                && TarotCardItem.quality(stack) == quality
                && TarotCardItem.upright(stack) == upright
                && owner.equals(TarotCardItem.owner(stack));
    }

    private TarotGuardsGameTests() {
    }
}
