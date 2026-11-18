package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FryerJournalGameTests {
    private static final ResourceLocation DISH = new ResourceLocation(MiningConstants.MODID, "journal_test");
    private FryerJournalGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void failedBackgroundWriteRetainsDataAndCanRecover(GameTestHelper helper) throws IOException {
        Path dir = Files.createTempDirectory("fryer-journal-recovery-");
        Path file = dir.resolve("journal.dat"), blocked = dir.resolve("journal.dat.tmp");
        FryerPlayerData data = new FryerPlayerData(); UUID player = UUID.randomUUID();
        try {
            data.record(player, DISH, 1, 350, System.currentTimeMillis()); data.sync(file.toFile());
            byte[] before = Files.readAllBytes(file);
            // 普通库存/进度触发后台保存，文件系统拒绝临时文件写入；不伪造异常 future。
            Files.createDirectory(blocked);
            data.record(player, DISH, 1, 400, System.currentTimeMillis());
            data.flushAsync(file.toFile()).handle((ignored, failure) -> null).join();
            data.flushAsync(file.toFile());
            helper.assertTrue(data.isDirty() && Arrays.equals(before, Files.readAllBytes(file)),
                    "Background failure preserves both pending state and the previous durable journal");
            helper.assertTrue(data.flushAsync(file.toFile()) == null && data.isDirty(),
                    "Persistent failure is throttled instead of retrying every tick");
            boolean rejected = false;
            try { data.sync(file.toFile()); }
            catch (UncheckedIOException expected) { rejected = true; }
            helper.assertTrue(rejected && data.isDirty(), "Transaction sync must reject an undurable write");
            Files.delete(blocked); data.sync(file.toFile());
            FryerPlayerData loaded = FryerPlayerData.load(NbtIo.readCompressed(file.toFile()).getCompound("data"));
            helper.assertTrue(!data.isDirty() && loaded.progress(player, DISH) == 2 && loaded.best(player, DISH) == 400,
                    "A later successful sync persists all changes and clears the failed future");
            helper.succeed();
        } finally { cleanup(dir); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void journalFailureDoesNotAbortVanillaStorageSave(GameTestHelper helper) throws IOException {
        Path dir = Files.createTempDirectory("fryer-journal-storage-");
        Path file = dir.resolve("miningdim_fryer_players.dat"), blocked = dir.resolve("miningdim_fryer_players.dat.tmp");
        FryerPlayerData data = new FryerPlayerData(); UUID player = UUID.randomUUID();
        DimensionDataStorage storage = new DimensionDataStorage(dir.toFile(), helper.getLevel().getServer().getFixerUpper());
        SavedData other = new SavedData() {
            @Override public CompoundTag save(CompoundTag tag) { tag.putInt("Value", 73); return tag; }
        };
        storage.set("miningdim_fryer_players", data); storage.set("other", other);
        try {
            data.setDirty(); data.sync(file.toFile()); byte[] before = Files.readAllBytes(file);
            // 原版自动保存/停服依次保存各 SavedData；炸锅 I/O 失败不能打断这个真实调用链。
            Files.createDirectory(blocked); other.setDirty();
            data.record(player, DISH, 1, 420, System.currentTimeMillis());
            data.flushAsync(file.toFile()).handle((ignored, failure) -> null).join();
            storage.save();
            helper.assertTrue(data.isDirty() && !other.isDirty()
                    && NbtIo.readCompressed(dir.resolve("other.dat").toFile()).getCompound("data").getInt("Value") == 73,
                    "Failed pending journal does not stop other SavedData from reaching disk");
            storage.save();
            helper.assertTrue(data.isDirty() && Arrays.equals(before, Files.readAllBytes(file)),
                    "Synchronous save failure also retains dirty state and the previous file");
            Files.delete(blocked); storage.save();
            FryerPlayerData loaded = FryerPlayerData.load(NbtIo.readCompressed(file.toFile()).getCompound("data"));
            helper.assertTrue(!data.isDirty() && loaded.progress(player, DISH) == 1 && loaded.best(player, DISH) == 420,
                    "Vanilla save recovers the journal once the filesystem is writable");
            helper.succeed();
        } finally { cleanup(dir); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void syncIncludesChangesMadeAfterBackgroundSnapshot(GameTestHelper helper) throws IOException {
        Path dir = Files.createTempDirectory("fryer-journal-newer-"); Path file = dir.resolve("journal.dat");
        FryerPlayerData data = new FryerPlayerData(); UUID player = UUID.randomUUID();
        try {
            data.record(player, DISH, 1, 350, System.currentTimeMillis()); data.flushAsync(file.toFile());
            // 后台快照提交后，主线程又完成一份菜；旧快照完成不能清掉这次修改。
            data.record(player, DISH, 1, 450, System.currentTimeMillis()); data.sync(file.toFile());
            FryerPlayerData loaded = FryerPlayerData.load(NbtIo.readCompressed(file.toFile()).getCompound("data"));
            helper.assertTrue(!data.isDirty() && loaded.progress(player, DISH) == 2 && loaded.best(player, DISH) == 450,
                    "Sync waits for the old snapshot, then persists the latest complete state");
            helper.succeed();
        } finally { cleanup(dir); }
    }

    private static void cleanup(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(dir);
    }
}
