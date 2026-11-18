package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FryerChunkPersistenceGameTests {
    private FryerChunkPersistenceGameTests() {}
    private static final class TestWorker extends IOWorker {
        TestWorker(Path path) { super(path, true, "fryer-test"); }
    }
    private static CompoundTag chunk(BlockPos pos, CompoundTag snapshot) {
        CompoundTag entity = snapshot.copy();
        entity.putInt("x", pos.getX()); entity.putInt("y", pos.getY()); entity.putInt("z", pos.getZ());
        ListTag entities = new ListTag(); entities.add(entity);
        CompoundTag result = new CompoundTag(); result.put("block_entities", entities); return result;
    }
    private static void close(TestWorker worker, Path dir) {
        try {
            worker.close();
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        } catch (IOException e) { throw new RuntimeException(e); }
    }
    private static CompoundTag put(FryerPlayerData data, GameTestHelper helper, BlockPos pos, int value) {
        CompoundTag snapshot = new CompoundTag(); snapshot.putInt("Value", value);
        data.checkpoint(helper.getLevel(), pos, snapshot);
        return data.checkpoint(helper.getLevel(), pos);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support", timeoutTicks = 400)
    public static void realStoreAndFailureControlCollection(GameTestHelper helper) throws IOException {
        Path dir = Files.createTempDirectory("fryer-chunk-test-"); TestWorker worker = new TestWorker(dir);
        FryerPlayerData data = FryerPlayerData.get(helper.getLevel());
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, 0)); ChunkPos cp = new ChunkPos(pos);
        CompoundTag saved = put(data, helper, pos, 1), tag = chunk(pos, saved);
        CompletableFuture<Void> success = FryerChunkPersistence.track(helper.getLevel(), worker, cp, tag, worker.store(cp, tag));
        helper.succeedWhen(() -> {
            helper.assertTrue(success.isDone(), "Waiting for actual store, flush and main-thread acknowledgement");
            try {
                success.join();
                helper.assertTrue(data.checkpoint(helper.getLevel(), pos) == null, "Confirmed chunk collects its checkpoint");
                CompoundTag current = put(data, helper, pos, 2);
                CompletableFuture<Void> failed = new CompletableFuture<>();
                CompletableFuture<Void> result = FryerChunkPersistence.track(helper.getLevel(), worker, cp, chunk(pos, current), failed);
                failed.completeExceptionally(new IOException("Injected store failure"));
                try { result.join(); helper.fail("Failed store must remain exceptional"); }
                catch (CompletionException expected) {
                    helper.assertTrue(data.checkpoint(helper.getLevel(), pos) != null, "Failed store retains checkpoint and logs error");
                }
            } finally { data.clearCheckpoint(helper.getLevel(), pos); close(worker, dir); }
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void migratedCheckpointMatchesOriginalChunk(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, 0));
        CompoundTag inventory = new CompoundTag();
        inventory.putInt("Size", 9);
        inventory.put("Items", new ListTag());
        CompoundTag entity = new CompoundTag();
        entity.putInt("FryerVersion", 2);
        entity.put("Inventory", inventory);
        entity.putBoolean("InProgress", false);
        entity.put("BufferedOutputs", new ListTag());
        entity.put("ReturnedItems", new ListTag());

        CompoundTag journal = new FryerPlayerData().save(new CompoundTag());
        journal.putInt("Version", 2);
        journal.remove("NextRevision");
        CompoundTag checkpoint = new CompoundTag();
        checkpoint.putString("Dimension", helper.getLevel().dimension().location().toString());
        checkpoint.putLong("Pos", pos.asLong());
        checkpoint.put("Snapshot", entity.copy());
        journal.getList("Checkpoints", 10).add(checkpoint);
        CompoundTag migrated = FryerPlayerData.load(journal).checkpoint(helper.getLevel(), pos);
        helper.assertTrue(migrated != null && FryerChunkPersistence.covered(migrated, entity),
                "An unchanged v2 chunk covers the migrated checkpoint even without a revision field");
        helper.assertTrue(FryerChunkPersistence.covered(migrated, null), "A removed v2 fryer covers its empty checkpoint");
        entity.putBoolean("InProgress", true);
        helper.assertTrue(!FryerChunkPersistence.covered(migrated, entity), "Different v2 contents must retain the checkpoint");
        migrated.getList("ReturnedItems", 10).add(new CompoundTag());
        helper.assertTrue(!FryerChunkPersistence.covered(migrated, null), "A missing fryer must retain pending items");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support", timeoutTicks = 400)
    public static void supersededWriteCannotCollectNewerCheckpoint(GameTestHelper helper) throws IOException {
        Path dir = Files.createTempDirectory("fryer-chunk-test-"); TestWorker worker = new TestWorker(dir);
        FryerPlayerData data = FryerPlayerData.get(helper.getLevel());
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, 0)); ChunkPos cp = new ChunkPos(pos);
        CompoundTag older = put(data, helper, pos, 1), latest = put(data, helper, pos, 2);
        CompoundTag a = chunk(pos, latest), b = chunk(pos, older);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> first = FryerChunkPersistence.track(helper.getLevel(), worker, cp, a, gate);
        FryerChunkPersistence.track(helper.getLevel(), worker, cp, b, worker.store(cp, b));
        gate.complete(null);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(first.isDone(), "Waiting for superseded receipt"))
                .thenExecute(() -> {
                    first.join();
                    helper.assertTrue(data.checkpoint(helper.getLevel(), pos) != null, "Superseded receipt retains newer checkpoint");
                }).thenExecute(() -> {
                    CompletableFuture<Void> last = FryerChunkPersistence.track(helper.getLevel(), worker, cp, a, worker.store(cp, a));
                    helper.succeedWhen(() -> {
                        helper.assertTrue(last.isDone(), "Waiting for current store and flush");
                        try {
                            last.join();
                            helper.assertTrue(data.checkpoint(helper.getLevel(), pos) == null, "Current durable snapshot collects checkpoint");
                        } finally { data.clearCheckpoint(helper.getLevel(), pos); close(worker, dir); }
                    });
                });
    }
}
