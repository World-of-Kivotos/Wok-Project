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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 开服迁移的接线 (规格「开服迁移」): 启用时刻与老玩家名单、区块存档、实体内容、老玩家第一次登录。
 *
 * 四个入口的**优先级都是 HIGHEST**, 因为默认优先级那一批观察点会把"没章的东西"当场盖成**现在**——
 * 迁移必须在它们之前把老货改成"启用时刻剩 6 小时", 否则时间胶囊里的东西一到手就变成刚出炉的新货,
 * 迁移就等于没做 (顺序错的反面同样是灾难: 迁移盖完章之后观察点不会再动它, 因为章已经在)。
 *
 * 三个"不覆盖"的容器 (CNPC 银行、邮件、MTS 货箱) 按规格走开服公告 + {@code /freshness audit}, 这里不碰。
 */
public final class FreshnessMigrationHooks {

    private FreshnessMigrationHooks() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    /** 本次启动已经走过的区块 (每区块每启动周期最多走一遍, 免得每次加载都把整棵 NBT 树读一遍)。 */
    private static final Set<Long> WALKED_CHUNKS = new LinkedHashSet<>();

    /** 上面那个集合的上限: 超过之后不再记账 (大服长时间跑不该为了省几次遍历把内存吃满)。 */
    private static final int MAX_WALKED_CHUNKS = 200_000;

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
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        FreshnessMigrationData data = FreshnessMigrationData.get(overworld);
        long enabledAt = data.resolveEnabledAt(FreshnessClockData.nowMinute(overworld),
                FreshnessConfig.MIGRATION_ENABLED_AT_MINUTE.get());
        boolean captured = data.captureRosterIfNeeded(scanExistingPlayers(server));
        LOGGER.info("[miningdim] freshness migration: 启用时刻=第 {} 分钟 (宽限 {} 小时), 老玩家名单 {}{}",
                enabledAt, graceHours(), data.rosterSize(), captured ? " (本次记下)" : " (沿用存档里的)");
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
        long chunkKey = event.getChunk().getPos().toLong();
        if (WALKED_CHUNKS.contains(chunkKey)) {
            return;
        }
        if (WALKED_CHUNKS.size() < MAX_WALKED_CHUNKS) {
            WALKED_CHUNKS.add(chunkKey);
        }
        FreshnessMigrationData data = FreshnessMigrationData.get(level);
        long enabledAt = data.enabledAtMinute();
        if (enabledAt == FreshnessMigrationData.UNSET_MINUTE) {
            return; // 还没定启用时刻 (理论上 onServerStarted 已定); 不定就不迁移, 免得算出个荒唐的章
        }
        CompoundTag chunkData = event.getData();
        int migrated = FreshnessMigration.migrateTag(chunkData, enabledAt, graceHours(), stopCapHours());
        if (migrated > 0) {
            LOGGER.info("[miningdim] freshness migration: 区块 {} 里迁移了 {} 件老货 (启用时刻剩 {} 小时)",
                    event.getChunk().getPos(), migrated, graceHours());
        }
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
        WALKED_CHUNKS.clear();
    }
}
