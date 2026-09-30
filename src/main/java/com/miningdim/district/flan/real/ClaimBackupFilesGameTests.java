package com.miningdim.district.flan.real;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.flan.FlanResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 领地备份的文件一侧 (设计文档 20.5, {@link ClaimBackupFiles}): 放在任何 data/claims 之外、后缀不是 .json、原子写、
 * 10 分钟节流、按池轮换且只删符合命名规则的文件、写不出来时如实报失败。本类不碰任何 Flan 类型, 没有 Flan 也能跑;
 * 从 Flan 取 JSON 的那一半 ({@code FlanClaimBackups}) 留给真 Flan 的用例 (20.10, 探路结果 S3 之后)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ClaimBackupFilesGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_flan_backups";
    private static final String DIMENSION = "minecraft:overworld";

    private static JsonArray sample(String id) {
        JsonArray claims = new JsonArray();
        JsonObject claim = new JsonObject();
        claim.addProperty("ID", id);
        claim.add("SubClaims", new JsonArray());
        claims.add(claim);
        return claims;
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backupsLiveOutsideDataClaimsAndAreWrittenAtomically(GameTestHelper helper) throws IOException {
        Path world = Files.createTempDirectory("district-backup-test");
        try {
            helper.assertTrue(ClaimBackupFiles.insideClaimsFolder(world.resolve("DIM-1/data/claims/sub"))
                            && !ClaimBackupFiles.insideClaimsFolder(world.resolve("miningdim/district-flan-backups")),
                    "认得出任何一层 data/claims");
            boolean refused = false;
            try {
                new ClaimBackupFiles(world.resolve("data/claims/backups"), () -> 0L, 10, 30);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "根目录落在 data/claims 之内: 直接拒绝 (Flan 会把里面的文件当真领地读)");

            AtomicLong clock = new AtomicLong(1_790_000_000_000L);
            ClaimBackupFiles files = new ClaimBackupFiles(world.resolve("miningdim/district-flan-backups"), clock::get,
                    10, 30);
            helper.assertTrue(!files.recent(DIMENSION), "还没有备份");
            FlanResult<String> made = files.write(DIMENSION, "batch", sample("abc"));
            helper.assertTrue(made.ok() && made.value() != null
                            && ClaimBackupFiles.NAME.matcher(made.value()).matches()
                            && made.value().endsWith(".AdminClaims.json.bak"),
                    "文件名符合命名规则, 后缀不是 .json, 实为 " + made.value());
            Path written = files.directory(DIMENSION).resolve(made.value());
            helper.assertTrue(files.directory(DIMENSION).getFileName().toString().equals("minecraft_overworld")
                            && !ClaimBackupFiles.insideClaimsFolder(written),
                    "维度目录把 : 换成 _, 不在任何 data/claims 之内");
            JsonArray back = JsonParser.parseString(Files.readString(written, StandardCharsets.UTF_8)).getAsJsonArray();
            helper.assertTrue(back.equals(sample("abc")), "内容是原样的 JSON 数组");
            try (Stream<Path> left = Files.list(files.directory(DIMENSION))) {
                helper.assertTrue(left.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "先写 .tmp 再原子改名, 不留半成品");
            }
            helper.assertTrue(files.recent(DIMENSION), "刚备过: 10 分钟内算最近");
            clock.addAndGet(ClaimBackupFiles.THROTTLE_MS - 1);
            helper.assertTrue(files.recent(DIMENSION), "差 1 毫秒到 10 分钟仍算最近");
            clock.addAndGet(1);
            helper.assertTrue(!files.recent(DIMENSION), "满 10 分钟就要再备一份");
            helper.assertTrue(made.value().equals(files.latest(DIMENSION)), "latest 给出最新一份");
        } finally {
            deleteTree(world);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotationKeepsTheNewestPerPoolAndOnlyTouchesBackups(GameTestHelper helper) throws IOException {
        Path world = Files.createTempDirectory("district-backup-rotate");
        try {
            AtomicLong clock = new AtomicLong(1_790_000_000_000L);
            ClaimBackupFiles files = new ClaimBackupFiles(world.resolve("backups"), clock::get, 2, 3);
            Path directory = files.directory(DIMENSION);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("notes.txt"), "keep me");
            Files.writeString(directory.resolve("20000101-000000-000-batch.json"), "[]");
            for (int i = 0; i < 4; i++) {
                clock.addAndGet(1000);
                files.write(DIMENSION, "start", sample("s" + i));
            }
            for (int i = 0; i < 5; i++) {
                clock.addAndGet(1000);
                files.write(DIMENSION, i % 2 == 0 ? "batch" : "resync", sample("b" + i));
            }
            // 同一毫秒里连写两份: 序号区分, 不覆盖。
            files.write(DIMENSION, "orphan", sample("same-ms-1"));
            files.write(DIMENSION, "orphan", sample("same-ms-2"));
            List<String> kept = files.list(DIMENSION);
            long start = kept.stream().filter(name -> name.endsWith("-start.AdminClaims.json.bak")).count();
            long other = kept.size() - start;
            helper.assertTrue(start == 2 && other == 3, "start 池留 2 份, 其余原因合计留 3 份, 实为 " + kept);
            helper.assertTrue(kept.get(kept.size() - 1).contains("-2-orphan"),
                    "同一毫秒的第二份带序号且排在最后, 实为 " + kept);
            helper.assertTrue(Files.exists(directory.resolve("notes.txt"))
                            && Files.exists(directory.resolve("20000101-000000-000-batch.json")),
                    "只删符合命名规则的文件");
            JsonArray newestStart = JsonParser.parseString(Files.readString(directory.resolve(kept.stream()
                    .filter(name -> name.endsWith("-start.AdminClaims.json.bak")).reduce((a, b) -> b).orElseThrow()),
                    StandardCharsets.UTF_8)).getAsJsonArray();
            helper.assertTrue(newestStart.equals(sample("s3")), "留下的是最新的几份");
        } finally {
            deleteTree(world);
        }
        helper.succeed();
    }

    /**
     * 写不出来: 如实报失败、不算"最近备过"; 之后一分钟内直接报失败、不再重试 (不在服务器线程上一遍遍序列化全部管理员
     * 领地); 满一分钟、目录恢复之后照常写成。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unwritableBackupFailsAndIsNotCountedAsRecent(GameTestHelper helper) throws IOException {
        Path world = Files.createTempDirectory("district-backup-fail");
        try {
            Path blocker = world.resolve("backups");
            Files.writeString(blocker, "a file where the backup directory should be");
            AtomicLong clock = new AtomicLong(1_790_000_000_000L);
            ClaimBackupFiles files = new ClaimBackupFiles(blocker, clock::get, 10, 30);
            FlanResult<String> failed = files.write(DIMENSION, "batch", sample("x"));
            helper.assertTrue(!failed.ok() && DistrictTexts.FLAN_BACKUP_FAILED.equals(failed.error()),
                    "写不出来: 如实报领地备份失败, 实为 " + failed);
            helper.assertTrue(!files.recent(DIMENSION), "失败的备份不算数, 下一批照样先备份 (写不成就一直不写 Flan)");
            helper.assertTrue(files.coolingDown(DIMENSION), "失败之后进入一分钟的冷却");

            Files.delete(blocker);
            clock.addAndGet(ClaimBackupFiles.RETRY_AFTER_FAILURE_MS - 1);
            FlanResult<String> tooSoon = files.write(DIMENSION, "batch", sample("x"));
            helper.assertTrue(!tooSoon.ok() && DistrictTexts.FLAN_BACKUP_FAILED.equals(tooSoon.error())
                            && !Files.exists(blocker),
                    "冷却之内直接报失败, 连目录都不去建, 实为 " + tooSoon);
            clock.addAndGet(1);
            FlanResult<String> retried = files.write(DIMENSION, "batch", sample("x"));
            helper.assertTrue(retried.ok() && files.recent(DIMENSION) && !files.coolingDown(DIMENSION),
                    "满一分钟、目录恢复之后照常写成, 实为 " + retried);
        } finally {
            deleteTree(world);
        }
        helper.succeed();
    }

    /** 轮换顺带清掉超过一分钟的残留临时文件 (写到一半失败又没删掉的); 新鲜的临时文件与别的文件不动。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotationRemovesStaleTempFiles(GameTestHelper helper) throws IOException {
        Path world = Files.createTempDirectory("district-backup-temp");
        try {
            AtomicLong clock = new AtomicLong(1_790_000_000_000L);
            ClaimBackupFiles files = new ClaimBackupFiles(world.resolve("backups"), clock::get, 10, 30);
            Path directory = files.directory(DIMENSION);
            Files.createDirectories(directory);
            Path stale = directory.resolve("20260101-000000-000-batch.AdminClaims.json.bak.tmp");
            Path fresh = directory.resolve("20260101-000000-001-batch.AdminClaims.json.bak.tmp");
            Path other = directory.resolve("notes.tmp");
            Files.writeString(stale, "[");
            Files.writeString(fresh, "[");
            Files.writeString(other, "keep me");
            Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()
                    - ClaimBackupFiles.STALE_TEMP_MS - 5_000L));
            Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(0L));
            helper.assertTrue(files.write(DIMENSION, "batch", sample("x")).ok(), "前提: 这一份写成");
            helper.assertTrue(!Files.exists(stale) && Files.exists(fresh) && Files.exists(other),
                    "超过一分钟的残留临时文件删掉, 新鲜的与名字不符的不动");
        } finally {
            deleteTree(world);
        }
        helper.succeed();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
