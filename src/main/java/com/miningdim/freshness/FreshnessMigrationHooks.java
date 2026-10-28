package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 开服迁移的接线 (规格「开服迁移」): 启用时刻与老玩家名单、区块存档、实体内容、老玩家第一次登录。
 *
 * 迁移入口的**优先级都是 HIGHEST**, 因为默认优先级那一批观察点会把"没章的东西"当场盖成**现在**——
 * 迁移必须在它们之前把老货改成"启用时刻剩 6 小时", 否则时间胶囊里的东西一到手就变成刚出炉的新货,
 * 迁移就等于没做 (顺序错的反面同样是灾难: 迁移盖完章之后观察点不会再动它, 因为章已经在)。
 *
 * 三个"不覆盖"的容器 (CNPC 银行、邮件、MTS 货箱) 按规格走开服公告 + {@code /freshness audit}, 这里不碰。
 */
public final class FreshnessMigrationHooks {

    private FreshnessMigrationHooks() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    /** ChunkDataEvent.Save 收到的是新 NBT 根, 因此在 ChunkAccess 上无法暂存的标记需保留到服务停止。 */
    private static final Map<ChunkIdentity, Long> PENDING_MARKERS = new ConcurrentHashMap<>();
    private static final String CHUNK_MIGRATION_MARKER = "miningdim_fresh_mig";
    private static final int CHUNK_MIGRATION_MARKER_VERSION = 1;

    private record ChunkIdentity(ResourceKey<Level> dimension, long chunkPos) {
    }

    /** 迁移是否启用 (总开关关掉, 或整个模块处于关闭档, 都不迁移)。 */
    private static boolean enabled() {
        return FreshnessConfig.MIGRATION_ENABLED.get() && FreshnessConfig.stampingEnabled();
    }

    private static int graceHours() {
        return FreshnessConfig.MIGRATION_GRACE_HOURS.get();
    }

    private static int stopCapHours() {
        return FreshnessConfig.STOP_CAP_HOURS.get();
    }

    // ============================================================
    // 开服: 定启用时刻 + 数老玩家名单
    // ============================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStarted(ServerStartedEvent event) {
        if (!enabled()) {
            LOGGER.info("[miningdim] freshness migration: 未启用 (配置里关掉了或模块不在盖章档), 不做开服迁移");
            return;
        }
        FreshnessMigrationData data = ensureMigrationStartedSynchronized(event.getServer().overworld());
        logMigrationStarted(data);
    }

    /** LevelEvent.Load 在主线程、启动区块加载之前发布; 在这里先初始化启动补偿与迁移名单。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelLoad(LevelEvent.Load event) {
        if (enabled() && event.getLevel() instanceof ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            FreshnessMigrationData data = ensureMigrationStartedSynchronized(level);
            logMigrationStarted(data);
        }
    }

    /** ChunkDataEvent.Load 可能早于 ServerStarted; LevelEvent.Load 已先处理启动区块, 此处仍作幂等兜底。 */
    private static FreshnessMigrationData ensureMigrationStarted(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server.overworld();
        FreshnessClockData clock = FreshnessClockData.get(overworld);
        long startupCompensation = clock.onServerStarted(
                FreshnessClock.currentWallHour(), FreshnessClock.currentWallMinute());
        if (startupCompensation > 0L) {
            LOGGER.info("[miningdim] freshness clock: 停服补偿 {} 小时 (迁移启动时提前完成)", startupCompensation);
        }
        FreshnessMigrationData data = FreshnessMigrationData.get(overworld);
        data.resolveEnabledAt(clock.nowMinute(),
                FreshnessConfig.MIGRATION_ENABLED_AT_MINUTE.get());
        if (!data.rosterCaptured()) {
            data.captureRosterIfNeeded(scanExistingPlayers(server));
        }
        return data;
    }

    /** 迁移元数据通常从区块 IO 线程加载; 同步这一小段避免同时扫描并反复定时。 */
    private static synchronized FreshnessMigrationData ensureMigrationStartedSynchronized(ServerLevel level) {
        return ensureMigrationStarted(level);
    }

    private static void logMigrationStarted(FreshnessMigrationData data) {
        LOGGER.info("[miningdim] freshness migration: 启用时刻=第 {} 分钟 (宽限 {} 小时), 老玩家名单 {} (已确保)",
                data.enabledAtMinute(), graceHours(), data.rosterSize());
    }

    /**
     * 数出"启用之前就已经在存档里的玩家": 存档目录下 {@code playerdata/*.dat} 的文件名就是 UUID。
     *
     * 这是唯一不依赖任何第三方接口、也不需要玩家在线就能拿到全量名单的办法; 读不出来 (目录不存在等)
     * 就当名单为空 —— 那只会少迁移几个人 (他们的东西随后被观察点按现在盖章), 不会误伤新玩家。
     */
    private static Set<UUID> scanExistingPlayers(MinecraftServer server) {
        Set<UUID> players = new LinkedHashSet<>();
        Path playerData = server.getWorldPath(LevelResource.ROOT).resolve("playerdata");
        if (!Files.isDirectory(playerData)) {
            return players;
        }
        try (Stream<Path> files = Files.list(playerData)) {
            files.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> {
                String name = path.getFileName().toString();
                try {
                    players.add(UUID.fromString(name.substring(0, name.length() - ".dat".length())));
                } catch (IllegalArgumentException malformed) {
                    // 不是 UUID 的文件名: 跳过 (目录里可能有别的模组留下的东西)。
                }
            });
        } catch (IOException unreadable) {
            LOGGER.warn("[miningdim] freshness migration: 读不出玩家存档目录 {}, 老玩家名单按空处理",
                    playerData, unreadable);
        }
        return players;
    }

    // ============================================================
    // 区块: 存档读入、方块实体还没创建的那一刻
    // ============================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChunkLoad(ChunkDataEvent.Load event) {
        if (!enabled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessMigrationData data = ensureMigrationStartedSynchronized(level);
        long enabledAt = data.enabledAtMinute();
        if (enabledAt == FreshnessMigrationData.UNSET_MINUTE) {
            return;
        }
        CompoundTag chunkData = event.getData();
        ChunkIdentity identity = identity(level, event.getChunk().getPos().toLong());
        if (hasCurrentMigrationMarker(chunkData, enabledAt)) {
            PENDING_MARKERS.put(identity, enabledAt);
            return;
        }
        int migrated = FreshnessMigration.migrateTag(chunkData, enabledAt, graceHours(), stopCapHours());
        PENDING_MARKERS.put(identity, enabledAt);
        if (migrated > 0) {
            LOGGER.info("[miningdim] freshness migration: 区块 {} 里迁移了 {} 件老货 (启用时刻剩 {} 小时)",
                    event.getChunk().getPos(), migrated, graceHours());
        }
    }

    /** 新生成区块不含启用前遗留货物, 只记录代号供下一次 ChunkDataEvent.Save 持久化。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onNewChunk(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !enabled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessMigrationData data = ensureMigrationStartedSynchronized(level);
        long enabledAt = data.enabledAtMinute();
        if (enabledAt != FreshnessMigrationData.UNSET_MINUTE) {
            PENDING_MARKERS.put(identity(level, event.getChunk().getPos().toLong()), enabledAt);
        }
    }

    /** 保存时把启用代号写回区块根; marker 是跨重启判断是否迁移过的唯一来源。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Long generation = PENDING_MARKERS.get(identity(level, event.getChunk().getPos().toLong()));
        if (generation != null) {
            writeMigrationMarker(event.getData(), generation);
        }
    }

    /** 服务停止时清掉当前进程的写回队列; 持久状态仍只以区块 NBT marker 为准。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_MARKERS.clear();
    }

    private static ChunkIdentity identity(ServerLevel level, long chunkPos) {
        return new ChunkIdentity(level.dimension(), chunkPos);
    }

    static boolean hasCurrentMigrationMarker(CompoundTag chunkData, long generation) {
        if (!chunkData.contains(CHUNK_MIGRATION_MARKER, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag marker = chunkData.getCompound(CHUNK_MIGRATION_MARKER);
        return marker.getInt("Version") == CHUNK_MIGRATION_MARKER_VERSION
                && marker.contains("Generation", net.minecraft.nbt.Tag.TAG_LONG)
                && marker.getLong("Generation") == generation;
    }

    static void writeMigrationMarker(CompoundTag chunkData, long generation) {
        CompoundTag marker = new CompoundTag();
        marker.putInt("Version", CHUNK_MIGRATION_MARKER_VERSION);
        marker.putLong("Generation", generation);
        chunkData.put(CHUNK_MIGRATION_MARKER, marker);
    }

    static Object identityForTest(ResourceKey<Level> dimension, long chunkPos) {
        return new ChunkIdentity(dimension, chunkPos);
    }

    // ============================================================
    // 实体: 从存档读出来的展示框 / 盔甲架 / 运输矿车 / 驴骡羊驼 / 掉落物
    // ============================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!enabled() || !event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FreshnessMigrationData data = FreshnessMigrationData.get(level);
        long enabledAt = data.enabledAtMinute();
        if (enabledAt == FreshnessMigrationData.UNSET_MINUTE) {
            return;
        }
        int migrated = migrateEntity(event.getEntity(), enabledAt);
        if (migrated > 0) {
            LOGGER.info("[miningdim] freshness migration: 实体 {} 里迁移了 {} 件老货",
                    event.getEntity().getType(), migrated);
        }
    }

    /** 按实体各自的接口取值: 展示框与盔甲架不是容器, 得单独看; 其余走物品接口能力。 */
    private static int migrateEntity(Entity entity, long enabledAt) {
        int grace = graceHours();
        int cap = stopCapHours();
        int migrated = 0;
        if (entity instanceof ItemFrame frame) {
            if (FreshnessMigration.stampMigrated(frame.getItem(), enabledAt, grace, cap)) {
                frame.setItem(frame.getItem());
                migrated++;
            }
        }
        if (entity instanceof ItemEntity itemEntity) {
            if (FreshnessMigration.stampMigrated(itemEntity.getItem(), enabledAt, grace, cap)) {
                itemEntity.setItem(itemEntity.getItem());
                migrated++;
            }
        }
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            // 盔甲架与任何生物身上的装备 (手上 + 四件护甲)。
            for (ItemStack worn : living.getAllSlots()) {
                migrated += FreshnessMigration.stampMigrated(worn, enabledAt, grace, cap) ? 1 : 0;
            }
        }
        if (entity instanceof Container container) {
            migrated += FreshnessMigration.migrateContainer(container, enabledAt, grace, cap);
        } else if (entity instanceof AbstractMinecartContainer || entity instanceof ChestBoat
                || entity instanceof net.minecraft.world.entity.animal.horse.AbstractChestedHorse) {
            // 这三种在原版里各有各的存法, 统一走物品接口能力 (见下), 这里只是把意图写明白。
            migrated += 0;
        }
        migrated += migrateCapability(entity, enabledAt, grace, cap);
        return migrated;
    }

    /** 任何挂了物品接口能力的实体 (运输矿车、箱子船、驴骡羊驼、第三方容器实体)。 */
    private static int migrateCapability(Entity entity, long enabledAt, int graceHours, int stopCapHours) {
        IItemHandler handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (handler == null) {
            return 0;
        }
        int migrated = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!FreshnessMigration.stampMigrated(stack, enabledAt, graceHours, stopCapHours)) {
                continue;
            }
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, stack);
            }
            migrated++;
        }
        return migrated;
    }

    // ============================================================
    // 玩家: 老玩家第一次登录
    // ============================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        FreshnessMigrationData data = FreshnessMigrationData.get(level);
        long enabledAt = data.enabledAtMinute();
        if (enabledAt == FreshnessMigrationData.UNSET_MINUTE
                || !data.isInRoster(player.getUUID())
                || data.isPlayerMigrated(player.getUUID())) {
            return; // 新玩家: 照常按现在盖章 (观察点那一条路), 不需要也不应该迁移
        }
        int migrated = FreshnessMigration.migrateContainer(player.getInventory(), enabledAt,
                graceHours(), stopCapHours());
        migrated += FreshnessMigration.migrateContainer(player.getEnderChestInventory(), enabledAt,
                graceHours(), stopCapHours());
        data.markPlayerMigrated(player.getUUID());
        LOGGER.info("[miningdim] freshness migration: 老玩家 {} 第一次登录, 迁移了 {} 件 (背包 + 末影箱)",
                player.getName().getString(), migrated);
    }

    /** 仅供用例: 清掉本次启动的区块记账。 */
    static void resetWalkedChunksForTest() {
        PENDING_MARKERS.clear();
    }
}
