package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.JobId;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryPendingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_pending";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimHandsBackTheVerySameStacks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper);
        GranarySavedData data = GranarySavedData.get(level);
        long nowMinute = GranaryClock.nowMinute(level);

        ItemStack stored = new ItemStack(Items.BREAD, 5);
        FreshnessData.stampAt(stored, nowMinute - 600L, 7);          // 三天前的一批, 停过 7 小时
        ItemStack plain = new ItemStack(Items.STONE, 12);
        GranaryPending.put(data, owner.getUUID(), 9L, GranaryPending.Reason.REMOVED, 400L,
                List.of(stored, plain));

        GranaryPending.ClaimResult result = GranaryPending.claim(data, owner, nowMinute);

        helper.assertTrue(result.claimed() == 17, "17 件都领到手, 实际 " + result.claimed());
        helper.assertTrue(result.claimedBins() == 1, "那一个囤领完了");
        helper.assertTrue(result.remaining() == 0, "单子空了");
        helper.assertTrue(data.pendingBins(owner.getUUID()) == 0, "额度归还");
        ItemStack got = find(owner, Items.BREAD);
        helper.assertTrue(!got.isEmpty() && got.getCount() == 5, "面包 5 个回来了, 实际 " + got);
        // 保鲜章原样: 批次时刻与已停表小时都不许动 (领回来接着按原样算, 不重新盖章)。
        helper.assertTrue(FreshnessData.batchMinute(got) == nowMinute - 600L,
                "批次时刻原样, 实际 " + FreshnessData.batchMinute(got));
        helper.assertTrue(FreshnessData.stoppedHours(got) == 7,
                "已停表小时原样, 实际 " + FreshnessData.stoppedHours(got));
        helper.assertTrue(ItemStack.isSameItemSameTags(got, stored), "整枚章一模一样");
        helper.assertTrue(find(owner, Items.STONE).getCount() == 12, "石头也回来了");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whatDoesNotFitStaysOnTheList(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper);
        GranarySavedData data = GranarySavedData.get(level);
        long nowMinute = GranaryClock.nowMinute(level);

        // 背包塞满 (每格都顶到 64): 一件也塞不进去。
        for (int slot = 0; slot < 36; slot++) {
            owner.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        GranaryPending.put(data, owner.getUUID(), 3L, GranaryPending.Reason.LEFT, 100L,
                List.of(new ItemStack(Items.BREAD, 64)));

        GranaryPending.ClaimResult full = GranaryPending.claim(data, owner, nowMinute);
        helper.assertTrue(full.claimed() == 0, "背包满了就一件都领不出来, 实际 " + full.claimed());
        helper.assertTrue(full.remaining() == 1, "条目留在单子上");
        helper.assertTrue(data.pendingBins(owner.getUUID()) == 1, "额度照占");
        helper.assertTrue(data.pendingOf(owner.getUUID()).get(0).count() == 64, "一件都没少");
        helper.assertTrue(find(owner, Items.BREAD).isEmpty(), "背包里不该凭空多出面包");

        // 腾出半格 (63 个石头 + 空格): 先并进去 1 个, 剩下的还留着。
        owner.getInventory().setItem(0, new ItemStack(Items.STONE, 63));
        owner.getInventory().setItem(1, ItemStack.EMPTY);
        GranaryPending.ClaimResult partial = GranaryPending.claim(data, owner, nowMinute);
        helper.assertTrue(partial.claimed() == 64, "腾出格子后能全领完, 实际 " + partial.claimed());
        helper.assertTrue(partial.remaining() == 0, "这次领空了");
        helper.assertTrue(find(owner, Items.BREAD).getCount() == 64, "64 个面包都在背包里");
        helper.assertTrue(data.pendingBins(owner.getUUID()) == 0, "领完才归还额度");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimTakesTheOldestEntryFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper);
        GranarySavedData data = GranarySavedData.get(level);
        long nowMinute = GranaryClock.nowMinute(level);

        GranaryPending.put(data, owner.getUUID(), 1L, GranaryPending.Reason.LEFT, 100L,
                List.of(new ItemStack(Items.BREAD, 1)));
        GranaryPending.put(data, owner.getUUID(), 2L, GranaryPending.Reason.REMOVED, 200L,
                List.of(new ItemStack(Items.COOKED_BEEF, 1)));

        // 只留「面包那一格差一个满」: 先进单的那一条并得进去, 后进单的那一条没处放。
        owner.getInventory().setItem(0, new ItemStack(Items.BREAD, 63));
        for (int slot = 1; slot < 36; slot++) {
            owner.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        GranaryPending.ClaimResult result = GranaryPending.claim(data, owner, nowMinute);
        helper.assertTrue(result.claimed() == 1, "只领得下第一件, 实际 " + result.claimed());
        helper.assertTrue(result.claimedBins() == 1, "先进先出: 先入单的那一条领完了");
        helper.assertTrue(result.remaining() == 1, "后入单的还在");
        List<GranaryPending.Entry> left = data.pendingOf(owner.getUUID());
        helper.assertTrue(left.size() == 1 && left.get(0).gid() == 2L,
                "留下的是 2 号粮仓那一条, 实际 " + left);
        helper.assertTrue(find(owner, Items.BREAD).getCount() == 64, "面包那一格补满了");
        helper.assertTrue(find(owner, Items.COOKED_BEEF).isEmpty(), "牛排还没领到");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rottenForThirtyDaysIsDestroyed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper);
        GranarySavedData data = GranarySavedData.get(level);
        long nowMinute = GranaryClock.nowMinute(level);

        ItemStack bread = new ItemStack(Items.BREAD, 1);
        int shelfLife = FreshnessCategories.shelfLifeHours(bread);
        helper.assertTrue(shelfLife > 0, "夹具: 面包是会烂的东西 (保质期 " + shelfLife + " 小时)");
        ItemStack longGone = bread.copy();
        // 烂了 30 天零 1 小时: 批次时刻 = 现在 − 保质期 − 30 天 − 1 小时。
        FreshnessData.stampAt(longGone, nowMinute - (long) shelfLife * 60L
                - (long) GranaryPending.AUTO_CLEAR_ROTTEN_HOURS * 60L - 60L, 0);
        ItemStack justRotten = bread.copy();
        FreshnessData.stampAt(justRotten, nowMinute - (long) shelfLife * 60L - 60L, 0);
        helper.assertTrue(GranaryPending.isAutoCleared(longGone, nowMinute), "烂满 30 天的该销毁");
        helper.assertTrue(!GranaryPending.isAutoCleared(justRotten, nowMinute), "才烂一小时的还留着");
        helper.assertTrue(!GranaryPending.isAutoCleared(new ItemStack(Items.STONE, 1), nowMinute),
                "不腐烂的东西永远不会被销毁");

        GranaryPending.put(data, owner.getUUID(), 4L, GranaryPending.Reason.REMOVED, 100L,
                List.of(longGone, justRotten));

        GranaryPending.PurgeResult purged = GranaryPending.purge(data, owner.getUUID(), nowMinute);
        helper.assertTrue(purged.destroyed() == 1, "销毁了一件, 实际 " + purged.destroyed());
        helper.assertTrue(purged.clearedBins() == 0, "条目还有东西, 额度不归还");
        helper.assertTrue(data.pendingBins(owner.getUUID()) == 1, "那个囤还占着额度");
        List<GranaryPending.Entry> left = data.pendingOf(owner.getUUID());
        helper.assertTrue(left.size() == 1 && left.get(0).count() == 1, "剩下的那一件还在");
        helper.assertTrue(!GranaryPending.purge(data, owner.getUUID(), nowMinute).changed(),
                "没得清的时候如实报「没动」");

        // 剩下的那件也烂满 30 天之后: 条目清空, 额度归还。
        long later = nowMinute + (long) GranaryPending.AUTO_CLEAR_ROTTEN_HOURS * 60L;
        GranaryPending.PurgeResult second = GranaryPending.purge(data, owner.getUUID(), later);
        helper.assertTrue(second.clearedBins() == 1, "条目清空");
        helper.assertTrue(data.pendingBins(owner.getUUID()) == 0, "额度归还");
        helper.assertTrue(data.pendingOf(owner.getUUID()).isEmpty(), "单子空了");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GranarySavedData data = GranarySavedData.get(level);
        long nowMinute = GranaryClock.nowMinute(level);
        ItemStack stamped = new ItemStack(Items.BREAD, 2);
        FreshnessData.stampAt(stamped, nowMinute - 120L, 3);

        data.putPending(first, new GranaryPending.Entry(7L, GranaryPending.Reason.REMOVED, 500L,
                List.of(stamped, new ItemStack(Items.STONE, 4))));
        data.putPending(second, new GranaryPending.Entry(0L, GranaryPending.Reason.PACKED, 600L,
                List.of(new ItemStack(Items.COOKED_BEEF, 1))));

        GranarySavedData reloaded = GranarySavedData.load(data.save(new CompoundTag()));

        List<GranaryPending.Entry> firstBack = reloaded.pendingOf(first);
        helper.assertTrue(firstBack.size() == 1, "第一个玩家的条目回来了");
        helper.assertTrue(firstBack.get(0).gid() == 7L
                && firstBack.get(0).reason() == GranaryPending.Reason.REMOVED
                && firstBack.get(0).atHour() == 500L, "来路照读: " + firstBack.get(0));
        helper.assertTrue(firstBack.get(0).items().size() == 2, "两件东西都在");
        helper.assertTrue(ItemStack.isSameItemSameTags(firstBack.get(0).items().get(0), stamped),
                "保鲜章随存档往返");
        helper.assertTrue(reloaded.pendingBins(second) == 1, "第二个玩家的也在");
        helper.assertTrue(reloaded.pendingBins(UUID.randomUUID()) == 0, "没单子的人是 0");
        // 账本是全服共用的 (同批别的用例也在往里放东西), 所以只断言这两位在名单上。
        helper.assertTrue(reloaded.pendingPlayers().contains(first)
                && reloaded.pendingPlayers().contains(second), "两个玩家都在名单上");

        // 老档没有这张表: 按空表读, 不是读不进来。
        CompoundTag withoutPending = data.save(new CompoundTag());
        withoutPending.remove(GranaryConstants.KEY_PENDING);
        helper.assertTrue(GranarySavedData.load(withoutPending).pendingBins(first) == 0,
                "这张表落地之前的档照读 (按空表)");
        // 用例自己还干净: 世界存档跨轮保留, 别把随机玩家的单子留在里面。
        data.setPending(first, List.of());
        data.setPending(second, List.of());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void badPendingStructureIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        UUID player = UUID.randomUUID();
        data.putPending(player, new GranaryPending.Entry(1L, GranaryPending.Reason.LEFT, 10L,
                List.of(new ItemStack(Items.BREAD, 1))));

        // 缺 Player: 不知道是谁的单子, 直接报错。
        CompoundTag noPlayer = data.save(new CompoundTag());
        ((CompoundTag) ((ListTag) noPlayer.get(GranaryConstants.KEY_PENDING)).get(0))
                .remove(GranaryConstants.KEY_PLAYER);
        helper.assertTrue(rejects(noPlayer), "缺玩家直接报错");

        // 认不出的原因: 那是坏档, 不许悄悄按「自己退出」放过去。
        CompoundTag badReason = data.save(new CompoundTag());
        ((CompoundTag) ((ListTag) badReason.get(GranaryConstants.KEY_PENDING)).get(0))
                .putString(GranaryConstants.KEY_REASON, "TELEPORTED");
        helper.assertTrue(rejects(badReason), "认不出的原因直接报错");

        // 空条目 (27 格一个都没有) 白占额度, 读档时丢掉。
        CompoundTag emptyEntry = data.save(new CompoundTag());
        ListTag list = (ListTag) emptyEntry.get(GranaryConstants.KEY_PENDING);
        CompoundTag empty = (CompoundTag) list.get(0).copy();
        empty.put(GranaryConstants.KEY_ITEMS, new ListTag());
        list.add(empty);
        GranarySavedData loaded = GranarySavedData.load(emptyEntry);
        helper.assertTrue(loaded.pendingBins(player) == 1, "空条目被丢掉, 只留下有货的那一条");
        data.setPending(player, List.of());
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        String unique = "pend_" + UUID.randomUUID().toString().substring(0, 6);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), unique));
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"))
                .jobProgress(JobId.FARMER)
                .setLevel(1);
        return player;
    }

    private static ItemStack find(ServerPlayer player, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean rejects(CompoundTag tag) {
        try {
            GranarySavedData.load(tag);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
