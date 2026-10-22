package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 开服迁移的存档 (主世界): 启用时刻 + 老玩家名单 + 谁已经迁移过。
 *
 * 为什么单独一份 SavedData 而不是塞进时钟那份: 时钟那份的键已经被冻结 (改键会废掉旧存档),
 * 而迁移状态是一批一次性数据 (名单与已迁移集合), 生命周期与时钟无关。名字同样一旦下发不许改名。
 *
 * 「老玩家名单」是**迁移启用那一刻**从存档目录的 playerdata 里数出来的: 那之后新注册的玩家一律按现在盖章
 * (他们没有"时间胶囊"要处理)。名单只在这里比一次, 不参与任何权限判定。
 */
public final class FreshnessMigrationData extends SavedData {

    private static final int VERSION = 1;
    private static final String KEY_VERSION = "Version";
    private static final String KEY_ENABLED_AT = "EnabledAtMinute";
    private static final String KEY_ROSTER_CAPTURED = "RosterCaptured";
    private static final String KEY_ROSTER = "Roster";
    private static final String KEY_MIGRATED_PLAYERS = "MigratedPlayers";

    /** 未定值: 0 表示"还没定启用时刻" (真实分钟序号不可能落在纪元第 0 分钟)。 */
    public static final long UNSET_MINUTE = 0L;

    private long enabledAtMinute = UNSET_MINUTE;
    private boolean rosterCaptured;
    private final Set<UUID> roster = new LinkedHashSet<>();
    private final Set<UUID> migratedPlayers = new LinkedHashSet<>();

    public static FreshnessMigrationData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FreshnessMigrationData::load, FreshnessMigrationData::new,
                FreshnessConstants.MIGRATION_DATA_NAME);
    }

    /** 迁移启用时刻 (分钟序号); 还没定过返回 {@link #UNSET_MINUTE}。 */
    public long enabledAtMinute() {
        return enabledAtMinute;
    }

    /**
     * 定下启用时刻: 已经有值就直接用它 (重启不会把启用日往后推, 否则老货每重启一次就多活 6 小时)。
     * 配置给了启用时刻就用配置, 没给就用**第一次启动的现在**。
     *
     * @param configuredMinute 配置里的启用时刻; 0 表示没配 (用现在)
     * @return 最终采用的启用时刻
     */
    public long resolveEnabledAt(long nowMinute, long configuredMinute) {
        if (enabledAtMinute != UNSET_MINUTE) {
            return enabledAtMinute;
        }
        enabledAtMinute = configuredMinute != UNSET_MINUTE ? configuredMinute : nowMinute;
        setDirty();
        return enabledAtMinute;
    }

    public boolean rosterCaptured() {
        return rosterCaptured;
    }

    public int rosterSize() {
        return roster.size();
    }

    /** 记下"启用之前就已经在存档里的玩家"; 只生效一次 (第二次调用返回 false)。 */
    public boolean captureRosterIfNeeded(Collection<UUID> existingPlayers) {
        if (rosterCaptured) {
            return false;
        }
        roster.addAll(existingPlayers);
        rosterCaptured = true;
        setDirty();
        return true;
    }

    /** 这位玩家是不是"启用之前就在的老玩家" (只有他们名下的东西需要迁移)。 */
    public boolean isInRoster(UUID player) {
        return player != null && roster.contains(player);
    }

    public boolean isPlayerMigrated(UUID player) {
        return player != null && migratedPlayers.contains(player);
    }

    /** 记下"这个人的背包与末影箱已经迁移过"; 返回是不是这一次记下的。 */
    public boolean markPlayerMigrated(UUID player) {
        if (player == null || !migratedPlayers.add(player)) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_VERSION, VERSION);
        tag.putLong(KEY_ENABLED_AT, enabledAtMinute);
        tag.putBoolean(KEY_ROSTER_CAPTURED, rosterCaptured);
        tag.put(KEY_ROSTER, writeUuids(roster));
        tag.put(KEY_MIGRATED_PLAYERS, writeUuids(migratedPlayers));
        return tag;
    }

    public static FreshnessMigrationData load(CompoundTag tag) {
        FreshnessMigrationData data = new FreshnessMigrationData();
        data.enabledAtMinute = tag.getLong(KEY_ENABLED_AT);
        data.rosterCaptured = tag.getBoolean(KEY_ROSTER_CAPTURED);
        data.roster.addAll(readUuids(tag.getList(KEY_ROSTER, Tag.TAG_STRING)));
        data.migratedPlayers.addAll(readUuids(tag.getList(KEY_MIGRATED_PLAYERS, Tag.TAG_STRING)));
        return data;
    }

    private static ListTag writeUuids(Set<UUID> uuids) {
        ListTag list = new ListTag();
        for (UUID uuid : uuids) {
            list.add(StringTag.valueOf(uuid.toString()));
        }
        return list;
    }

    private static Set<UUID> readUuids(ListTag list) {
        Set<UUID> uuids = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                uuids.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException malformed) {
                // 存档里出现非 UUID 的字符串: 跳过, 不让一条脏数据把整个名单读废。
            }
        }
        return uuids;
    }

    /** 仅供用例: 清空成出厂状态。 */
    void resetForTest() {
        enabledAtMinute = UNSET_MINUTE;
        rosterCaptured = false;
        roster.clear();
        migratedPlayers.clear();
        setDirty();
    }
}
