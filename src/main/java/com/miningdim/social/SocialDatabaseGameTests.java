package com.miningdim.social;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SocialDatabaseGameTests {
    private static final String EMPTY = "empty";
    private static final String BATCH = "social_database";
    private static final UUID ALPHA = UUID.fromString("6f133c11-81d8-4e86-9efb-5d7ed48714a1");
    private static final UUID BETA = UUID.fromString("a4bd0fb8-1ad5-455e-a602-ae0294ccbb36");

    private SocialDatabaseGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyJsonMigratesWithoutReimport(GameTestHelper helper) {
        inTemp(helper, dir -> {
            Path jsonFile = dir.resolve("social.json");
            long now = System.currentTimeMillis();
            String thread = SocialStore.threadKey(ALPHA, BETA);
            String json = """
                    {"version":1,"profiles":{"%1$s":{"uuid":"%1$s","name":"Alpha","signature":"","academy":"","theme":"abydos","unlockedThemes":["abydos"],"lastSeen":%3$d},"%2$s":{"uuid":"%2$s","name":"Beta","signature":"","academy":"","theme":"halolink","unlockedThemes":[],"lastSeen":%3$d}},
                     "friends":{"%1$s":["%2$s"],"%2$s":["%1$s"]},"incoming":{},"outgoing":{},
                     "moments":[{"id":"moment-1","author":"%1$s","authorName":"Alpha","text":"legacy moment","image":"","createdAt":%3$d,"likes":[],"comments":[{"author":"%2$s","authorName":"Beta","text":"legacy comment","createdAt":%3$d}]}],
                     "chats":{"%4$s":[{"id":"direct-1","thread":"%4$s","sender":"%1$s","senderName":"Alpha","text":"legacy direct","createdAt":%3$d}]},"world":[]}
                    """.formatted(ALPHA, BETA, now, thread);
            byte[] original = json.getBytes(StandardCharsets.UTF_8);
            Files.write(jsonFile, original);

            try (SocialStore store = new SocialStore(jsonFile)) {
                store.load();
                assertMigratedData(helper, store);
                helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(jsonFile)),
                        "migration must leave the legacy JSON bytes unchanged");
                List<Path> backups = filesWithPrefix(dir, "social.json.migration-backup-");
                helper.assertTrue(backups.size() == 1 && java.util.Arrays.equals(original, Files.readAllBytes(backups.get(0))),
                        "migration must keep one byte-identical backup");
            }
            try (SocialStore reopened = new SocialStore(jsonFile)) {
                reopened.load();
                assertMigratedData(helper, reopened);
                helper.assertTrue(filesWithPrefix(dir, "social.json.migration-backup-").size() == 1,
                        "an initialized database must not import the legacy JSON again");
                helper.assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(jsonFile)),
                        "reopening must still leave the legacy JSON unchanged");
            }
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void corruptLegacyJsonIsRejectedAndPreserved(GameTestHelper helper) {
        inTemp(helper, dir -> {
            Path jsonFile = dir.resolve("social.json");
            byte[] corrupt = "{\"version\":1,\"profiles\":[".getBytes(StandardCharsets.UTF_8);
            Files.write(jsonFile, corrupt);
            try (SocialStore store = new SocialStore(jsonFile)) {
                boolean rejected = false;
                try {
                    store.load();
                } catch (IllegalStateException expected) {
                    rejected = expected.getMessage().contains("未使用空档覆盖原数据");
                }
                helper.assertTrue(rejected, "truncated legacy JSON must fail loading");
            }
            helper.assertTrue(java.util.Arrays.equals(corrupt, Files.readAllBytes(jsonFile)),
                    "failed migration must leave the damaged source bytes unchanged");
            List<Path> backups = filesWithPrefix(dir, "social.json.corrupt-");
            helper.assertTrue(backups.size() == 1 && java.util.Arrays.equals(corrupt, Files.readAllBytes(backups.get(0))),
                    "damaged source must have a byte-identical recovery copy");
            try (SocialDatabase database = new SocialDatabase(dir.resolve("social.db"))) {
                helper.assertTrue(!database.initialized(), "failed migration must not mark an empty database initialized");
            }
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retentionBoundaryAndCleanupBatch(GameTestHelper helper) {
        inTemp(helper, dir -> {
            long boundaryNow = System.currentTimeMillis();
            long cutoff = boundaryNow - SocialDatabase.RETENTION_MILLIS;
            try (SocialDatabase boundary = new SocialDatabase(dir.resolve("boundary.db"))) {
                boundary.initialize(List.of(
                        new SocialDatabase.Row("world", "at-cutoff", "world", cutoff, "{}"),
                        new SocialDatabase.Row("world", "after-cutoff", "world", cutoff + 1L, "{}"),
                        new SocialDatabase.Row("profile", ALPHA.toString(), "", 0L, "{}"),
                        new SocialDatabase.Row("relations", ALPHA.toString(), "", 0L, "{}")));
                List<SocialDatabase.Row> rows = boundary.history("world", "world", 0L, 10, boundaryNow);
                helper.assertTrue(rows.size() == 1 && rows.get(0).id().equals("after-cutoff"),
                        "history must exclude the exact seven-day cutoff and keep cutoff plus one millisecond");
            }

            long cleanupNow = System.currentTimeMillis();
            long physicalCutoff = cleanupNow - SocialDatabase.RETENTION_MILLIS;
            List<SocialDatabase.Row> cleanupRows = new ArrayList<>();
            for (int i = 0; i < 130; i++) {
                cleanupRows.add(new SocialDatabase.Row("world", "expired-" + i, "world",
                        physicalCutoff - 60_000L, "{}"));
            }
            cleanupRows.add(new SocialDatabase.Row("world", "retained", "world", physicalCutoff + 60_000L, "{}"));
            cleanupRows.add(new SocialDatabase.Row("profile", ALPHA.toString(), "", 0L, "{}"));
            cleanupRows.add(new SocialDatabase.Row("relations", ALPHA.toString(), "", 0L, "{}"));
            try (SocialDatabase cleanup = new SocialDatabase(dir.resolve("cleanup.db"))) {
                cleanup.initialize(cleanupRows);
                cleanup.writeNextBatch();
                List<SocialDatabase.Row> afterOneBatch = cleanup.permanent("world");
                long expired = afterOneBatch.stream().filter(row -> row.createdAt() <= physicalCutoff).count();
                helper.assertTrue(expired == 2L && afterOneBatch.stream().anyMatch(row -> row.id().equals("retained")),
                        "one cleanup pass must delete no more than 128 expired rows and retain newer history");
                cleanup.writeNextBatch();
                helper.assertTrue(cleanup.permanent("world").size() == 1,
                        "the next cleanup pass must remove the remaining expired rows");
                helper.assertTrue(cleanup.permanent("profile").size() == 1
                                && cleanup.permanent("relations").size() == 1,
                        "profile and relation rows must remain permanent");
            }
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backgroundWritesAreImmediatelyReadableAndPersisted(GameTestHelper helper) {
        inTemp(helper, dir -> {
            Path jsonFile = dir.resolve("social.json");
            int count = 130 + new Random(20261021L).nextInt(10);
            List<String> expected = new ArrayList<>(count);
            try (SocialStore store = new SocialStore(jsonFile)) {
                store.load();
                for (int i = 0; i < count; i++) {
                    String text = "world-" + i;
                    expected.add(text);
                    store.sendWorld(ALPHA, "Alpha", text);
                }
                assertWorldOrder(helper, expected, store.worldHistory(count));
            }
            try (SocialStore reopened = new SocialStore(jsonFile)) {
                reopened.load();
                assertWorldOrder(helper, expected, reopened.worldHistory(count));
            }
        });
    }

    private static void assertMigratedData(GameTestHelper helper, SocialStore store) {
        helper.assertTrue(store.areFriends(ALPHA, BETA) && store.areFriends(BETA, ALPHA),
                "migration must preserve both directions of the friendship");
        helper.assertTrue(store.theme(ALPHA).equals("abydos"), "migration must preserve the selected theme");
        List<SocialData.Moment> moments = store.pageMoments(10, 0L);
        helper.assertTrue(moments.size() == 1 && moments.get(0).comments.size() == 1
                        && moments.get(0).comments.get(0).text.equals("legacy comment"),
                "migration must preserve the moment and its comment");
        List<SocialData.ChatMessage> direct = store.directHistory(ALPHA, BETA, 10);
        helper.assertTrue(direct.size() == 1 && direct.get(0).text.equals("legacy direct"),
                "migration must preserve private chat");
    }

    private static void assertWorldOrder(GameTestHelper helper, List<String> expected,
                                         List<SocialData.ChatMessage> actual) {
        helper.assertTrue(actual.size() == expected.size(), "all world messages must remain in history");
        for (int i = 0; i < expected.size(); i++) {
            helper.assertTrue(expected.get(i).equals(actual.get(i).text), "world message order changed at index " + i);
        }
    }

    private static List<Path> filesWithPrefix(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private static void inTemp(GameTestHelper helper, TempAction action) {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path dir = null;
        try {
            dir = Files.createTempDirectory(root, "wok-social-db-").toAbsolutePath().normalize();
            if (!dir.getParent().equals(root)) throw new IllegalStateException("temporary directory escaped TEMP");
            action.run(dir);
            helper.succeed();
        } catch (Exception error) {
            helper.fail("social database GameTest failed: " + error);
        } finally {
            if (dir != null) deleteTempDir(dir, root);
        }
    }

    private static void deleteTempDir(Path dir, Path root) {
        Path target = dir.toAbsolutePath().normalize();
        if (!target.getParent().equals(root) || !target.getFileName().toString().startsWith("wok-social-db-")) {
            throw new IllegalArgumentException("refusing to delete outside the test TEMP directory: " + target);
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) {
            throw new IllegalStateException("cannot clean the test TEMP directory: " + target, error);
        }
    }

    @FunctionalInterface
    private interface TempAction {
        void run(Path dir) throws Exception;
    }
}
