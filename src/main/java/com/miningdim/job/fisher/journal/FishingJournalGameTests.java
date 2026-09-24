package com.miningdim.job.fisher.journal;

import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.FishingSystem;
import com.miningdim.job.fisher.size.FishRecord;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FishingJournalGameTests {
    private static final ResourceLocation SALMON = new ResourceLocation("minecraft", "salmon");
    private static final ResourceLocation COD = new ResourceLocation("minecraft", "cod");

    private FishingJournalGameTests() {
    }

    @GameTest(template = "empty", batch = "fishing_journal")
    public static void catalogAndCraftingEntryAreLoaded(GameTestHelper helper) {
        // 不装 Tide 的正常启动必须保留原版图鉴与玩家可合成的入口。
        for (String fish : List.of("cod", "salmon", "pufferfish", "tropical_fish")) {
            helper.assertTrue(FishingJournalCatalog.INSTANCE.contains(new ResourceLocation("minecraft", fish)),
                    "Vanilla fish missing from loaded journal: " + fish);
        }
        boolean supportedTide = ModList.get().getModContainerById("tide")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.6.5")).orElse(false);
        helper.assertTrue(FishingJournalCatalog.INSTANCE.entries().size() == (supportedTide ? 75 : 9),
                "Journal must include five ore fish and four vanilla fish, plus 66 fish with Tide 1.6.5");
        ResourceLocation recipeId = new ResourceLocation(MiningConstants.MODID, "fishing_journal");
        var recipe = helper.getLevel().getRecipeManager().byKey(recipeId).orElseThrow();
        helper.assertTrue(recipe.getResultItem(helper.getLevel().registryAccess()).is(FishingSystem.JOURNAL.get()),
                "The journal recipe must produce the registered WOK journal");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fishing_journal")
    public static void heldFishCollectOnceAndSurviveRemoval(GameTestHelper helper) {
        // 同一 tick 内从容器取鱼又放回也须收录，仅查看箱子里的鱼不得收录。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.SALMON, 12));
        chest.setItem(1, new ItemStack(Items.COD));
        ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
        player.containerMenu = menu;
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, menu));
        helper.assertTrue(FishingJournalService.snapshot(player).collected().isEmpty(),
                "Fish displayed in a container must not be collected before taking them");
        menu.clicked(0, 0, ClickType.PICKUP, player);
        menu.broadcastChanges();
        menu.clicked(0, 0, ClickType.PICKUP, player);
        menu.broadcastFullState();
        helper.assertTrue(menu.getCarried().isEmpty()
                        && FishingJournalService.snapshot(player).collected().equals(Set.of(SALMON)),
                "Taking and returning a fish before the next tick must still collect it");
        menu.clicked(1, 0, ClickType.QUICK_MOVE, player);
        menu.broadcastChanges();
        player.getInventory().setItem(1, new ItemStack(Items.STONE));
        helper.assertTrue(FishingJournalService.snapshot(player).collected().equals(Set.of(SALMON, COD)),
                "Quick-moving fish to inventory must also collect it");
        FishingJournalSavedData data = FishingJournalSavedData.get(player.serverLevel());
        data.setDirty(false);
        helper.assertTrue(!FishingJournalService.scanInventory(player) && !data.isDirty(),
                "Repeated inventory scans must not dirty an unchanged collection");
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(FishingJournalService.snapshot(player).collected().equals(Set.of(SALMON, COD)),
                "Selling or storing fish must retain collection progress");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fishing_journal")
    public static void saveRoundTripKeepsOwnersAndUnavailableFish(GameTestHelper helper) {
        // 重启、重生或暂时卸载 Tide 后，收藏按稳定 UUID/item ID 保留。
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ResourceLocation tideFish = new ResourceLocation("tide", "trout");
        FishingJournalSavedData data = new FishingJournalSavedData();
        data.collect(first, SALMON);
        data.collect(first, tideFish);
        data.collect(second, COD);
        FishingJournalSavedData restored = FishingJournalSavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(restored.collected(first).equals(Set.of(SALMON, tideFish)),
                "Temporarily unavailable fish must remain in saved collection");
        helper.assertTrue(restored.collected(second).equals(Set.of(COD)), "Player collections must remain isolated");
        helper.assertTrue(!restored.isDirty(), "Loading must not mark collection dirty");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fishing_journal")
    public static void networkRoundTripPreservesDirectoryAndProgress(GameTestHelper helper) {
        // 打开图鉴/自动刷新共用快照协议，客户端必须收到同一份目录、个人收藏与亲手钓获记录。
        FishingJournalSnapshot snapshot = new FishingJournalSnapshot(FishingJournalCatalog.INSTANCE.entries(), Set.of(SALMON),
                Map.of(SALMON, new FishRecord(3, 1187, 18_450_000L), COD, new FishRecord(1, 612, 2_300_000L)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var original = new FishingJournalNetwork.SnapshotPacket(snapshot, true);
            FishingJournalNetwork.encode(original, buffer);
            var decoded = FishingJournalNetwork.decode(buffer);
            helper.assertTrue(decoded.equals(original), "Journal metadata, collection and records must round-trip exactly");
            helper.assertTrue(buffer.readableBytes() == 0, "Journal packet must consume all encoded fields");
        } finally {
            buffer.release();
        }
        // 记录只允许指向本次目录里的条目: 指向目录外物品的记录必须整包拒绝。
        FishingJournalSnapshot foreign = new FishingJournalSnapshot(
                List.of(FishingJournalCatalog.INSTANCE.entries().get(0)), Set.of(),
                Map.of(new ResourceLocation("minecraft", "stick"), new FishRecord(1, 100, 1_000L)));
        FriendlyByteBuf foreignBuffer = new FriendlyByteBuf(Unpooled.buffer());
        boolean rejected = false;
        try {
            FishingJournalNetwork.encode(new FishingJournalNetwork.SnapshotPacket(foreign, false), foreignBuffer);
            FishingJournalNetwork.decode(foreignBuffer);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        } finally {
            foreignBuffer.release();
        }
        helper.assertTrue(rejected, "A record for an item outside the directory must reject the whole snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fishing_journal")
    public static void invalidReloadPreservesPreviousDirectory(GameTestHelper helper) {
        // 管理员数据包意外重复声明同一鱼种，失败的 reload 不得发布半份新目录。
        String json = """
                {"entries":[{"item":"minecraft:salmon","category":"freshwater",
                "description":"fish.description","habitat":"fish.habitat","conditions":"fish.conditions"}]}
                """;
        FishingJournalCatalog catalog = new FishingJournalCatalog();
        var resource = JsonParser.parseString(json);
        var manager = helper.getLevel().getServer().getResourceManager();
        catalog.apply(Map.of(new ResourceLocation("miningdim", "first"), resource), manager, InactiveProfiler.INSTANCE);
        boolean rejected = false;
        try {
            catalog.apply(Map.of(new ResourceLocation("miningdim", "first"), resource,
                    new ResourceLocation("miningdim", "duplicate"), resource), manager, InactiveProfiler.INSTANCE);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && catalog.entries().size() == 1 && catalog.contains(SALMON),
                "Invalid reload must fail and retain the previous complete directory");
        helper.succeed();
    }
}
