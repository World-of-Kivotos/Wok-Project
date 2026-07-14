package com.miningdim.power.grid;

import com.miningdim.power.cable.CableTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 每个 ServerLevel 一个实例, 维护该维度全部线缆网并每 tick 结算。抗掉刻的三条架构承诺:
 *  1. 线缆方块实体没有 ticker; 全部工作集中在本管理器的 settlement, 成本 O(活跃端点数) 而非 O(线缆块数)。
 *  2. 连通图只在放置(并网)/破坏(拆网)/邻居变化(端点脏)时增量更新, 结算路径里绝不 flood-fill。
 *  3. 端点集合缓存, 仅 endpointsDirty 时重算一次; 结算只遍历缓存端点。
 *
 * 反双计边界: 线缆对外只 receiveEnergy (见 EnergyCableBlockEntity), 故消费端一律由本管理器 push, 不会与
 * "端点自拉 + 管理器 push" 双计。端点分类: canExtract 者为生产端(拉), canReceive 且非 canExtract 者为消费端(推);
 * 两者皆真的电池 v1 当生产端 (避免来回 churn, 专用储能块后续单独设计其单向面)。
 */
public final class EnergyNetworkManager {

    private static final Map<ServerLevel, EnergyNetworkManager> INSTANCES = new HashMap<>();

    /** 线缆坐标 -> 所属网 (O(1) 查网, 供 BE capability 读缓冲用)。 */
    private final Map<BlockPos, EnergyNetwork> byCable = new HashMap<>();
    /** 本维度全部网。 */
    private final Set<EnergyNetwork> networks = Collections.newSetFromMap(new IdentityHashMap<>());

    private EnergyNetworkManager() {
    }

    /** 取(或建)指定维度的管理器; 线缆 BE 在服务端 onLoad/setRemoved/邻居变化时调用。 */
    public static EnergyNetworkManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, l -> new EnergyNetworkManager());
    }

    /** 由 PowerSystem 接线: 每 level tick END 结算, level 卸载时释放实例 (防内存泄漏)。 */
    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(EnergyNetworkManager::onLevelTick);
        forgeBus.addListener(EnergyNetworkManager::onLevelUnload);
    }

    private static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide()) {
            return;
        }
        if (event.level instanceof ServerLevel serverLevel) {
            EnergyNetworkManager manager = INSTANCES.get(serverLevel);
            if (manager != null) {
                manager.settle(serverLevel);
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            INSTANCES.remove(serverLevel);
        }
    }

    // ---- 拓扑维护 (增量, 不在结算路径) ----------------------------------------------------------

    /** 线缆并入网: 无相邻线缆则建新网, 一个则加入, 多个则合并 (典型 add 为 O(1))。 */
    public void addCable(BlockPos pos, CableTier tier) {
        EnergyNetwork existing = byCable.get(pos);
        if (existing != null) {
            existing.cables.put(pos, tier);
            existing.recomputeTransferCap();
            existing.endpointsDirty = true;
            return;
        }

        Set<EnergyNetwork> neighbourNets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction dir : Direction.values()) {
            EnergyNetwork nn = byCable.get(pos.relative(dir));
            if (nn != null) {
                neighbourNets.add(nn);
            }
        }

        EnergyNetwork net;
        if (neighbourNets.isEmpty()) {
            net = new EnergyNetwork();
            networks.add(net);
        } else {
            Iterator<EnergyNetwork> it = neighbourNets.iterator();
            net = it.next();
            while (it.hasNext()) {
                EnergyNetwork other = it.next();
                for (Map.Entry<BlockPos, CableTier> entry : other.cables.entrySet()) {
                    net.cables.put(entry.getKey(), entry.getValue());
                    byCable.put(entry.getKey(), net);
                }
                net.stored += other.stored;
                networks.remove(other);
            }
        }

        net.cables.put(pos, tier);
        byCable.put(pos, net);
        net.recomputeTransferCap();
        if (net.stored > net.bufferCap()) {
            net.stored = net.bufferCap();
        }
        net.endpointsDirty = true;
    }

    /** 线缆离网: 拆掉旧网并对剩余成员重新 flood-fill 分量 (可能一分为多)。开销限于该网自身, 不涉全局。 */
    public void removeCable(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net == null) {
            return;
        }
        Map<BlockPos, CableTier> survivors = new HashMap<>(net.cables);
        survivors.remove(pos);
        int carriedStored = net.stored;

        for (BlockPos member : net.cables.keySet()) {
            byCable.remove(member);
        }
        networks.remove(net);

        reindexComponents(survivors, carriedStored);
    }

    /** 邻居方块变化 (可能新增/移除能量端点): 只标脏, 端点在下次结算前惰性重算。 */
    public void markEndpointsDirty(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net != null) {
            net.endpointsDirty = true;
        }
    }

    private void reindexComponents(Map<BlockPos, CableTier> members, int carriedStored) {
        Set<BlockPos> remaining = new java.util.HashSet<>(members.keySet());
        boolean firstComponent = true;
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            EnergyNetwork net = new EnergyNetwork();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos cable = queue.poll();
                net.cables.put(cable, members.get(cable));
                byCable.put(cable, net);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbour = cable.relative(dir);
                    if (remaining.remove(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            net.recomputeTransferCap();
            net.endpointsDirty = true;
            if (firstComponent) {
                net.stored = Math.min(carriedStored, net.bufferCap());
                firstComponent = false;
            }
            networks.add(net);
        }
    }

    // ---- BE capability 读写瞬态缓冲 (线缆只收不放, 见 EnergyCableBlockEntity) --------------------

    /** 推式发电机经线缆 receiveEnergy 把电推入本网缓冲。 */
    public int receiveIntoNetwork(BlockPos pos, int amount, boolean simulate) {
        EnergyNetwork net = byCable.get(pos);
        if (net == null || amount <= 0) {
            return 0;
        }
        int accepted = Math.max(0, Math.min(net.bufferCap() - net.stored, amount));
        if (!simulate) {
            net.stored += accepted;
        }
        return accepted;
    }

    public int storedAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.stored;
    }

    public int capacityAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.bufferCap();
    }

    // ---- 测试用只读探针 (GameTest 断言拓扑; 不参与运行期逻辑) --------------------------------------

    /** 该坐标所属网的线缆数; 无网返回 0。 */
    public int debugNetworkSize(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.cables.size();
    }

    /** 两坐标是否属于同一张网 (同一实例)。 */
    public boolean debugSameNetwork(BlockPos a, BlockPos b) {
        EnergyNetwork net = byCable.get(a);
        return net != null && net == byCable.get(b);
    }

    // ---- 结算 (每 tick END, O(端点)) -----------------------------------------------------------

    private void settle(ServerLevel level) {
        for (EnergyNetwork net : networks) {
            settleNetwork(level, net);
        }
    }

    private void settleNetwork(ServerLevel level, EnergyNetwork net) {
        if (net.endpointsDirty) {
            recomputeEndpoints(level, net);
        }
        int cap = net.transferCap;
        if (cap <= 0 || net.endpoints.isEmpty()) {
            return;
        }

        // 拉: 从生产端 (canExtract) 抽入缓冲, 单 settlement 封顶 cap。
        int room = Math.min(net.bufferCap() - net.stored, cap);
        for (Map.Entry<BlockPos, Direction> endpoint : net.endpoints.entrySet()) {
            if (room <= 0) {
                break;
            }
            IEnergyStorage storage = capAt(level, endpoint.getKey(), endpoint.getValue());
            if (storage == null || !storage.canExtract()) {
                continue;
            }
            int got = storage.extractEnergy(room, false);
            net.stored += got;
            room -= got;
        }

        // 推: 从缓冲发给消费端 (canReceive 且非 canExtract), 单 settlement 封顶 cap。
        int pushable = Math.min(net.stored, cap);
        for (Map.Entry<BlockPos, Direction> endpoint : net.endpoints.entrySet()) {
            if (pushable <= 0) {
                break;
            }
            IEnergyStorage storage = capAt(level, endpoint.getKey(), endpoint.getValue());
            if (storage == null || !storage.canReceive() || storage.canExtract()) {
                continue;
            }
            int sent = storage.receiveEnergy(pushable, false);
            net.stored -= sent;
            pushable -= sent;
        }
    }

    private void recomputeEndpoints(ServerLevel level, EnergyNetwork net) {
        net.endpoints.clear();
        for (BlockPos cable : net.cables.keySet()) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = cable.relative(dir);
                if (byCable.containsKey(neighbour) || !level.hasChunkAt(neighbour)) {
                    continue;
                }
                if (capAt(level, neighbour, dir.getOpposite()) != null) {
                    net.endpoints.put(neighbour, dir.getOpposite());
                }
            }
        }
        net.endpointsDirty = false;
    }

    private static IEnergyStorage capAt(ServerLevel level, BlockPos pos, Direction side) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        return be.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
    }
}
