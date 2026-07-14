package com.miningdim.power.grid;

import com.miningdim.power.cable.ConductorMaterial;
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
 *  3. 端点集合缓存, 仅 endpointsDirty 时重算一次; 结算只遍历缓存端点; 网温/材料剖面仅重建时算并缓存。
 *
 * 反双计边界: 线缆对外只 receiveEnergy (见 EnergyCableBlockEntity), 故消费端一律由本管理器 push, 不会与
 * "端点自拉 + 管理器 push" 双计。端点分类: canExtract 者为生产端(拉), canReceive 且非 canExtract 者为消费端(推);
 * 两者皆真的电池 v1 当生产端 (避免来回 churn, 专用储能块后续单独设计其单向面)。
 *
 * 热学: 有效吞吐 = 额定 × eff(网温)(见 {@link CableThermics}); 过载则本 settlement 送达量 > 75%额定, 网温升,
 * eff 降, 下 settlement 吞吐随之降 —— 自限于安全线附近。网温每张网一个值, 每 settlement O(1) 推进。
 */
public final class EnergyNetworkManager {

    private static final Map<ServerLevel, EnergyNetworkManager> INSTANCES = new HashMap<>();

    /** 线缆坐标 -> 所属网 (O(1) 查网, 供 BE capability 读缓冲/网温用)。 */
    private final Map<BlockPos, EnergyNetwork> byCable = new HashMap<>();
    /** 本维度全部网。 */
    private final Set<EnergyNetwork> networks = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 测试用合成端点 (仅 GameTest 注入; 生产路径恒空, capAt 每次一个 map.get 开销可忽略)。 */
    private final Map<BlockPos, IEnergyStorage> syntheticEndpoints = new HashMap<>();

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
    public void addCable(BlockPos pos, ConductorMaterial material) {
        EnergyNetwork existing = byCable.get(pos);
        if (existing != null) {
            existing.cables.put(pos, material);
            existing.recomputeProfile();
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
                for (Map.Entry<BlockPos, ConductorMaterial> entry : other.cables.entrySet()) {
                    net.cables.put(entry.getKey(), entry.getValue());
                    byCable.put(entry.getKey(), net);
                }
                net.stored += other.stored;
                // 合并两网时取较热者为并网后网温 (保守: 不因并网凭空散热)。
                net.temperatureC = Math.max(net.temperatureC, other.temperatureC);
                networks.remove(other);
            }
        }

        net.cables.put(pos, material);
        byCable.put(pos, net);
        net.recomputeProfile();
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
        Map<BlockPos, ConductorMaterial> survivors = new HashMap<>(net.cables);
        survivors.remove(pos);
        int carriedStored = net.stored;
        double carriedTemp = net.temperatureC;

        for (BlockPos member : net.cables.keySet()) {
            byCable.remove(member);
        }
        networks.remove(net);

        reindexComponents(survivors, carriedStored, carriedTemp);
    }

    /** 邻居方块变化 (可能新增/移除能量端点): 只标脏, 端点在下次结算前惰性重算。 */
    public void markEndpointsDirty(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net != null) {
            net.endpointsDirty = true;
        }
    }

    private void reindexComponents(Map<BlockPos, ConductorMaterial> members, int carriedStored, double carriedTemp) {
        Set<BlockPos> remaining = new java.util.HashSet<>(members.keySet());
        boolean firstComponent = true;
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            EnergyNetwork net = new EnergyNetwork();
            net.temperatureC = carriedTemp;
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
            net.recomputeProfile();
            net.endpointsDirty = true;
            if (firstComponent) {
                net.stored = Math.min(carriedStored, net.bufferCap());
                firstComponent = false;
            }
            networks.add(net);
        }
    }

    // ---- BE capability 读写瞬态缓冲 / 网温 (线缆只收不放, 见 EnergyCableBlockEntity) ----------------

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

    /** 该坐标所属网的网温 (°C); 无网返回环境温 (供 BE / Jade 读)。 */
    public double networkTemperatureAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? CableThermics.AMBIENT_C : net.temperatureC;
    }

    /** 该坐标所属网上一 settlement 的负载率 = 送达量 / 额定 (0..1+; 供 Jade 显示)。 */
    public double networkLoadRatioAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net == null || net.ratedCap <= 0) {
            return 0.0;
        }
        return (double) net.lastLoad / net.ratedCap;
    }

    // ---- 测试用只读探针 / 合成端点 (GameTest 用; 不参与生产逻辑) --------------------------------------

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

    /** 该网上一 settlement 实际送达用电端的 FE (断言过载降效用)。 */
    public int debugLastLoadAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.lastLoad;
    }

    /** 该网额定吞吐帽 R (断言降效相对基准用)。 */
    public int debugRatedCapAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.ratedCap;
    }

    /** 注入一个合成端点 (仅 GameTest 用): 令真实 settle 循环像遇到真机器一样拉/推它, 无需注册测试方块。 */
    public void debugPutSyntheticEndpoint(BlockPos pos, IEnergyStorage storage) {
        syntheticEndpoints.put(pos, storage);
        for (EnergyNetwork net : networks) {
            net.endpointsDirty = true;
        }
    }

    /** 清空合成端点 (GameTest 收尾)。 */
    public void debugClearSyntheticEndpoints() {
        syntheticEndpoints.clear();
        for (EnergyNetwork net : networks) {
            net.endpointsDirty = true;
        }
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
        int rated = net.ratedCap;
        if (rated <= 0 || net.endpoints.isEmpty()) {
            // 无吞吐则负载 0, 网温向环境回落。
            net.lastLoad = 0;
            net.temperatureC = CableThermics.advanceTemperature(net.temperatureC, 0, rated);
            return;
        }

        // 有效吞吐帽 = 额定 × eff(网温): 过热则本 settlement 能搬的电变少。
        double eff = CableThermics.efficiency(net.temperatureC, net.insulationMaxTempC, net.degradeFloor);
        int effCap = (int) Math.floor(rated * eff);

        // 拉: 从生产端 (canExtract) 抽入缓冲, 单 settlement 封顶 effCap。
        int room = Math.min(net.bufferCap() - net.stored, effCap);
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

        // 推: 从缓冲发给消费端 (canReceive 且非 canExtract), 封顶 effCap; 送达量即本 settlement 负载。
        int pushable = Math.min(net.stored, effCap);
        int delivered = 0;
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
            delivered += sent;
        }

        // 依实际送达负载推进网温 (过载升、低载冷)。
        net.lastLoad = delivered;
        net.temperatureC = CableThermics.advanceTemperature(net.temperatureC, delivered, rated);
    }

    private void recomputeEndpoints(ServerLevel level, EnergyNetwork net) {
        net.endpoints.clear();
        for (BlockPos cable : net.cables.keySet()) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = cable.relative(dir);
                if (byCable.containsKey(neighbour)) {
                    continue;
                }
                if (capAt(level, neighbour, dir.getOpposite()) != null) {
                    net.endpoints.put(neighbour, dir.getOpposite());
                }
            }
        }
        net.endpointsDirty = false;
    }

    private IEnergyStorage capAt(ServerLevel level, BlockPos pos, Direction side) {
        IEnergyStorage synthetic = syntheticEndpoints.get(pos);
        if (synthetic != null) {
            return synthetic;
        }
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
