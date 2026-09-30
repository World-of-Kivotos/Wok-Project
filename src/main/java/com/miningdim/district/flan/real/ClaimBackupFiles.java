package com.miningdim.district.flan.real;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.flan.FlanResult;
import com.miningdim.district.flan.LogThrottle;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 领地备份的文件一侧 (设计文档 20.5): 目录、命名、原子写、节流、轮换。<b>不引用任何 Flan 类型</b> (JSON 由
 * {@link FlanClaimBackups} 从 Flan 取出后交进来), 所以没有 Flan 的 GameTest 也能核对这些规则。
 *
 * <ul>
 *   <li>位置: {@code <世界>/miningdim/district-flan-backups/<维度>/}, 与各维度的 data/claims 平级; 构造时若根目录落在
 *       任何 data/claims 之内直接拒绝 —— Flan 的 read() 递归遍历 data/claims, 放在里面的 !AdminClaims.json 会被当成
 *       真领地再读一遍, 别的名字的 .json 会让 UUID.fromString 抛异常, 世界直接打不开。</li>
 *   <li>文件名: {@code <yyyyMMdd-HHmmss-SSS>[-<序号>]-<原因>.AdminClaims.json.bak}; 后缀刻意不是 .json (双保险)。
 *       先写同目录的 .tmp 并落盘 (force), 再原子改名。</li>
 *   <li>节流: {@link #recent} 判断这个维度 10 分钟内有没有过备份。</li>
 *   <li>轮换: 按维度、按池各自只留最新的若干份 (start 池, 其余原因合计一个池), 只删本目录下符合命名规则的文件与超过
 *       一分钟的残留临时文件; 尽力而为, 删不掉不算备份失败。</li>
 *   <li>写不出来 (磁盘满、没有权限): 删掉写了一半的临时文件, 回"领地备份失败，本次没有写入领地", 调用方不写 Flan;
 *       之后一分钟内直接回失败 ({@link #coolingDown}), 不再重试; ERROR 每小时只记一次。</li>
 * </ul>
 */
public final class ClaimBackupFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 每批改动前"最近一份备份"的有效期。 */
    public static final long THROTTLE_MS = 10L * 60_000L;
    public static final int KEEP_START = 10;
    public static final int KEEP_OTHER = 30;

    /** 备份文件名的规则 (只删符合它的文件)。 */
    public static final Pattern NAME = Pattern.compile(
            "^(\\d{8}-\\d{6}-\\d{3})(?:-(\\d+))?-(start|batch|create|bind|recreate|resync|orphan)"
                    + "\\.AdminClaims\\.json\\.bak$");
    private static final Set<String> REASONS = Set.of("start", "batch", "create", "bind", "recreate", "resync",
            "orphan");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 备份失败之后多久内直接报失败、不再重试。 */
    public static final long RETRY_AFTER_FAILURE_MS = 60_000L;
    /** 残留的临时文件多旧才删 (比任何一次正常的写入都长得多)。 */
    static final long STALE_TEMP_MS = 60_000L;
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path root;
    private final LongSupplier clock;
    private final int keepStart;
    private final int keepOther;
    private final LogThrottle throttle;
    private final Map<String, Long> lastBackup = new HashMap<>();
    private final Map<String, Long> lastFailure = new HashMap<>();

    public ClaimBackupFiles(Path root, LongSupplier clock, int keepStart, int keepOther) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.keepStart = keepStart;
        this.keepOther = keepOther;
        this.throttle = new LogThrottle(clock);
        if (insideClaimsFolder(this.root)) {
            throw new IllegalArgumentException("Flan claim backups must never live under a data/claims folder: "
                    + this.root);
        }
    }

    public Path root() {
        return root;
    }

    /** 维度的备份目录 (维度 id 里的 : 与 / 换成 _)。 */
    public Path directory(String dimension) {
        return root.resolve(dimension.replace(':', '_').replace('/', '_'));
    }

    /** 这个维度 10 分钟内有没有过备份。 */
    public synchronized boolean recent(String dimension) {
        Long last = lastBackup.get(dimension);
        return last != null && clock.getAsLong() - last < THROTTLE_MS;
    }

    /**
     * 上一次备份失败还不到 {@link #RETRY_AFTER_FAILURE_MS}: 调用方直接报"备份失败", 不再在服务器线程上把全部管理员领地
     * 序列化一遍 (磁盘满或目录被占着时, 每一次推送、每一个对账条目都会再试一次)。
     */
    public synchronized boolean coolingDown(String dimension) {
        Long failed = lastFailure.get(dimension);
        return failed != null && clock.getAsLong() - failed < RETRY_AFTER_FAILURE_MS;
    }

    /**
     * 写一份备份 (内容是管理员领地的 JsonArray); 返回文件名。写不出来时回"领地备份失败，本次没有写入领地", 删掉写了一半
     * 的临时文件, 之后 {@link #RETRY_AFTER_FAILURE_MS} 内直接失败。先写同目录的临时文件并落盘 (force), 再原子改名:
     * 断电之后, 带正式名字的备份不会是空的。轮换只是尽力而为, 删不掉旧文件不算备份失败。
     */
    public synchronized FlanResult<String> write(String dimension, String reason, JsonArray claims) {
        if (!REASONS.contains(reason)) {
            throw new IllegalArgumentException("unknown backup reason " + reason);
        }
        if (coolingDown(dimension)) {
            return FlanResult.failure(DistrictTexts.FLAN_BACKUP_FAILED);
        }
        long now = clock.getAsLong();
        Path directory = directory(dimension);
        Path temp = null;
        try {
            Files.createDirectories(directory);
            String stamp = STAMP.format(Instant.ofEpochMilli(now));
            Path target = directory.resolve(stamp + "-" + reason + ".AdminClaims.json.bak");
            for (int n = 2; Files.exists(target); n++) {
                target = directory.resolve(stamp + "-" + n + "-" + reason + ".AdminClaims.json.bak");
            }
            temp = directory.resolve(target.getFileName() + TEMP_SUFFIX);
            byte[] bytes = GSON.toJson(claims).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            lastBackup.put(dimension, now);
            lastFailure.remove(dimension);
            rotate(dimension, directory);
            return FlanResult.success(target.getFileName().toString());
        } catch (IOException | RuntimeException failure) {
            lastFailure.put(dimension, now);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException | RuntimeException ignored) {
                    // 删不掉的临时文件由下一次成功备份时的轮换按"超过一分钟"清掉。
                }
            }
            if (throttle.loud("backup|" + dimension)) {
                LOGGER.error("[miningdim] district: backing up the Flan admin claims of {} to {} failed; no Flan write "
                        + "happens without a backup (retrying at most once a minute; repeats within the hour are logged "
                        + "at DEBUG)", dimension, directory, failure);
            } else {
                LOGGER.debug("[miningdim] district: backing up the Flan admin claims of {} failed again: {}", dimension,
                        failure.toString());
            }
            return FlanResult.failure(DistrictTexts.FLAN_BACKUP_FAILED);
        }
    }

    /** 最新一份备份的文件名 (/district status 显示); 没有为 null。 */
    @Nullable
    public synchronized String latest(String dimension) {
        List<Path> files = matching(directory(dimension));
        return files.isEmpty() ? null : files.get(files.size() - 1).getFileName().toString();
    }

    /** 本目录下符合命名规则的备份, 按时间从旧到新 (测试与状态显示用)。 */
    public synchronized List<String> list(String dimension) {
        List<String> names = new ArrayList<>();
        matching(directory(dimension)).forEach(file -> names.add(file.getFileName().toString()));
        return names;
    }

    /**
     * 按池各自只留最新的若干份, 另删掉超过一分钟的残留临时文件 (写到一半失败、又没删掉的)。尽力而为: 某个文件删不掉
     * (Windows 上被备份软件或杀毒软件占着) 只记一条节流的 WARN, 不让这一次备份失败。
     */
    private void rotate(String dimension, Path directory) {
        List<Path> start = new ArrayList<>();
        List<Path> other = new ArrayList<>();
        for (Path file : matching(directory)) {
            Matcher matcher = NAME.matcher(file.getFileName().toString());
            if (matcher.matches()) {
                ("start".equals(matcher.group(3)) ? start : other).add(file);
            }
        }
        List<Path> doomed = new ArrayList<>();
        doomed.addAll(start.subList(0, Math.max(0, start.size() - keepStart)));
        doomed.addAll(other.subList(0, Math.max(0, other.size() - keepOther)));
        doomed.addAll(staleTemps(directory));
        for (Path file : doomed) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException failure) {
                if (throttle.loud("rotate|" + dimension)) {
                    LOGGER.warn("[miningdim] district: could not delete the old Flan backup {} (in use?); it stays "
                            + "until a later rotation", file, failure);
                }
            }
        }
    }

    /** 本目录下超过一分钟的 *.AdminClaims.json.bak.tmp (按文件的修改时间与墙钟比)。 */
    private static List<Path> staleTemps(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - STALE_TEMP_MS;
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".AdminClaims.json.bak" + TEMP_SUFFIX) && Files.isRegularFile(file)
                        && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    stale.add(file);
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug("[miningdim] district: listing leftover Flan backup temp files in {} failed: {}", directory,
                    failure.toString());
        }
        return stale;
    }

    /** 本目录下符合命名规则的文件, 按时间从旧到新。 */
    private static List<Path> matching(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> NAME.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(ClaimBackupFiles::sortKey)
                            .thenComparing(file -> file.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            LOGGER.warn("[miningdim] district: listing Flan backups in {} failed", directory, failure);
            return List.of();
        }
    }

    /** 时间戳 + 同一毫秒内的序号 (两位补零), 字典序即时间序。 */
    private static String sortKey(Path file) {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            return file.getFileName().toString();
        }
        String sequence = matcher.group(2) == null ? "01" : String.format(Locale.ROOT, "%02d",
                Integer.parseInt(matcher.group(2)));
        return matcher.group(1) + "-" + sequence;
    }

    /** 路径上有没有连续的 data/claims 两段。 */
    public static boolean insideClaimsFolder(Path path) {
        Path previous = null;
        for (Path part : path) {
            if (previous != null && "data".equals(previous.toString()) && "claims".equals(part.toString())) {
                return true;
            }
            previous = part;
        }
        return false;
    }
}
