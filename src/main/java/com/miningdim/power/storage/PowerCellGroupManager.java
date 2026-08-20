package com.miningdim.power.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 每个 ServerLevel 一个实例，维护该维度全部储电组。范式直接复刻 {@link com.miningdim.power.grid.EnergyNetworkManager}
 * 的线缆网维护：放置时增量并组、破坏时只对该组剩余成员重新 flood-fill，结算与读写路径里绝不 flood-fill。
 *
 * 与线缆网的两处刻意不同：
 *  1. 储电组没有每 tick 的结算循环——组不搬电，电网仍然按每块储电各自的 capability 结算，组只做速率与
 *     读数的聚合，所以这里没有 activeNetworks 那一层调度集合。
 *  2. 分档判定必须按方块规格而不是方块实体类型：三档储电共用同一个 BlockEntityType，靠 {@code instanceof}
 *     只会把三档混装进一个组。
 */
public final class PowerCellGroupManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final Map<ServerLevel, PowerCellGroupManager> INSTANCES = new HashMap<>();

    /** 储电坐标 -> 所属组 (O(1) 反查，供 BE 的 capability 与菜单读聚合值)。组实例只由本表持有。 */
    private final Map<BlockPos, PowerCellGroup> byCell = new HashMap<>();
    private final ResourceKey<Level> dimension;

    private PowerCellGroupManager(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    public static PowerCellGroupManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, l -> new PowerCellGroupManager(l.dimension()));
    }

    /** 由 PowerSystem 接线：维度卸载时释放实例，否则整张分组索引会随存档一起泄漏。 */
    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(PowerCellGroupManager::onLevelUnload);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            INSTANCES.remove(serverLevel);
        }
    }

    /**
     * 储电并组：无同档相邻则自成一组，有则并入并把相邻的多个组一起吃掉。
     *
     * 幂等：onLoad 是唯一入口，重复触发（同一坐标再次注册）直接返回，不会把同一坐标算两遍成员数。
     */
    public void addCell(BlockPos pos, PowerCellSpec spec) {
        BlockPos key = pos.immutable();
        if (byCell.containsKey(key)) {
            return;
        }

        Set<PowerCellGroup> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PowerCellGroup> neighbours = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            PowerCellGroup neighbour = byCell.get(key.relative(direction));
            if (neighbour != null && neighbour.spec() == spec && seen.add(neighbour)) {
                neighbours.add(neighbour);
            }
        }
        neighbours.sort(Comparator.comparing(PowerCellGroup::anchor, PowerCellGroup.BLOCK_POS_ORDER));

        int mergedSize = 1;
        for (PowerCellGroup neighbour : neighbours) {
            mergedSize += neighbour.size();
        }
        if (mergedSize > PowerCellGroup.MAX_MEMBERS) {
            // 拒绝并入而不是静默合并: 上限是为了让重算成本与容量溢出风险都封顶, 悄悄放行等于没有上限。
            LOGGER.info("power cell group size limit reached dimension={} placedAt={} tier={} mergedSize={} limit={}",
                    dimension.location(), key, spec.id(), mergedSize, PowerCellGroup.MAX_MEMBERS);
            neighbours = List.of();
        }

        PowerCellGroup group;
        if (neighbours.isEmpty()) {
            group = new PowerCellGroup(spec);
        } else {
            group = neighbours.get(0);
            for (int i = 1; i < neighbours.size(); i++) {
                PowerCellGroup other = neighbours.get(i);
                for (BlockPos member : other.members()) {
                    group.addMember(member);
                    byCell.put(member, group);
                }
            }
        }
        group.addMember(key);
        byCell.put(key, group);
    }

    /** 储电离组：拆掉旧组并对剩余成员重新分连通分量（可能一分为多）。开销限于该组自身。 */
    public void removeCell(BlockPos pos) {
        PowerCellGroup group = byCell.get(pos);
        if (group == null) {
            return;
        }
        Set<BlockPos> survivors = new TreeSet<>(PowerCellGroup.BLOCK_POS_ORDER);
        survivors.addAll(group.members());
        survivors.remove(pos);
        for (BlockPos member : group.members()) {
            byCell.remove(member);
        }
        reindexComponents(group.spec(), survivors);
    }

    @Nullable
    public PowerCellGroup groupAt(BlockPos pos) {
        return byCell.get(pos);
    }

    /** 该坐标所属组的成员数；不属于任何组返回 0。 */
    public int groupSizeAt(BlockPos pos) {
        PowerCellGroup group = byCell.get(pos);
        return group == null ? 0 : group.size();
    }

    /** 两坐标是否属于同一组（同一实例）。 */
    public boolean sameGroup(BlockPos a, BlockPos b) {
        PowerCellGroup group = byCell.get(a);
        return group != null && group == byCell.get(b);
    }

    private void reindexComponents(PowerCellSpec spec, Set<BlockPos> members) {
        Set<BlockPos> remaining = new TreeSet<>(PowerCellGroup.BLOCK_POS_ORDER);
        remaining.addAll(members);
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            PowerCellGroup component = new PowerCellGroup(spec);
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos member = queue.poll();
                component.addMember(member);
                byCell.put(member, component);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbour = member.relative(direction);
                    if (remaining.remove(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
        }
    }
}
