package com.miningdim.power.grid;

import com.miningdim.power.cable.CableProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final Map<ServerLevel, EnergyNetworkManager> INSTANCES = new HashMap<>();
    private static final Comparator<BlockPos> BLOCK_POS_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);
    /** 冷却进入该观测精度后回写环境温，使无端点网络能从活跃调度集合收敛退出。 */
    private static final double COOLING_EPSILON_C = 0.01;

    /** 线缆坐标 -> 所属网 (O(1) 查网, 供 BE capability 读缓冲/网温用)。 */
    private final Map<BlockPos, EnergyNetwork> byCable = new HashMap<>();
    /** 本维度全部网。 */
    private final Set<EnergyNetwork> networks = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 仅含端点脏、有缓存端点或仍需冷却的网络；稳态 tick 不遍历孤立线缆网。 */
    private final Set<EnergyNetwork> activeNetworks = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 测试用精确面端点；生产路径恒空。 */
    private final Map<EnergyEndpointKey, IEnergyStorage> syntheticEndpoints = new HashMap<>();
    /** 旧测试兼容的任意面合成端点；新测试应使用精确面重载。 */
    private final Map<BlockPos, IEnergyStorage> syntheticAnySideEndpoints = new HashMap<>();
    private final ResourceKey<Level> dimension;

    private EnergyNetworkManager(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    /** 取(或建)指定维度的管理器; 线缆 BE 在服务端 onLoad/setRemoved/邻居变化时调用。 */
    public static EnergyNetworkManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, l -> new EnergyNetworkManager(l.dimension()));
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
    public void addCable(BlockPos pos, CableProfile material) {
        EnergyNetwork existing = byCable.get(pos);
        if (existing != null) {
            int previousCapacity = existing.bufferCap();
            int previousStored = existing.stored;
            existing.cables.put(pos, material);
            existing.recomputeProfile();
            enforceBufferCapacity(existing, pos, List.of(previousStored), List.of(previousCapacity));
            existing.endpointsDirty = true;
            activeNetworks.add(existing);
            return;
        }

        Set<EnergyNetwork> neighbourNets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction dir : Direction.values()) {
            EnergyNetwork nn = byCable.get(pos.relative(dir));
            if (nn != null) {
                neighbourNets.add(nn);
            }
        }

        List<EnergyNetwork> orderedNeighbours = new ArrayList<>(neighbourNets);
        orderedNeighbours.sort(Comparator.comparing(this::networkAnchor, BLOCK_POS_ORDER));
        List<Integer> previousStored = orderedNeighbours.stream().map(net -> net.stored).toList();
        List<Integer> previousCapacities = orderedNeighbours.stream().map(EnergyNetwork::bufferCap).toList();

        EnergyNetwork net;
        if (neighbourNets.isEmpty()) {
            net = new EnergyNetwork();
            networks.add(net);
        } else {
            net = orderedNeighbours.get(0);
            for (int i = 1; i < orderedNeighbours.size(); i++) {
                EnergyNetwork other = orderedNeighbours.get(i);
                for (Map.Entry<BlockPos, CableProfile> entry : other.cables.entrySet()) {
                    net.cables.put(entry.getKey(), entry.getValue());
                    byCable.put(entry.getKey(), net);
                }
                net.stored += other.stored;
                net.totalLossFe += other.totalLossFe;
                net.lastLossFe = Math.max(net.lastLossFe, other.lastLossFe);
                net.faults.addAll(other.faults);
                // 合并两网时取较热者为并网后网温 (保守: 不因并网凭空散热)。
                net.temperatureC = Math.max(net.temperatureC, other.temperatureC);
                networks.remove(other);
                activeNetworks.remove(other);
            }
        }

        net.cables.put(pos, material);
        byCable.put(pos, net);
        net.recomputeProfile();
        enforceBufferCapacity(net, pos, previousStored, previousCapacities);
        net.endpointsDirty = true;
        activeNetworks.add(net);
    }

    /** 线缆离网: 拆掉旧网并对剩余成员重新 flood-fill 分量 (可能一分为多)。开销限于该网自身, 不涉全局。 */
    public void removeCable(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net == null) {
            return;
        }
        Map<BlockPos, CableProfile> survivors = new HashMap<>(net.cables);
        survivors.remove(pos);

        for (BlockPos member : net.cables.keySet()) {
            byCable.remove(member);
        }
        networks.remove(net);
        activeNetworks.remove(net);

        reindexComponents(survivors, net);
    }

    /** 邻居方块变化 (可能新增/移除能量端点): 只标脏, 端点在下次结算前惰性重算。 */
    public void markEndpointsDirty(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        if (net != null) {
            net.endpointsDirty = true;
            activeNetworks.add(net);
        }
    }

    private void reindexComponents(Map<BlockPos, CableProfile> members, EnergyNetwork source) {
        Set<BlockPos> remaining = new TreeSet<>(BLOCK_POS_ORDER);
        remaining.addAll(members.keySet());
        List<EnergyNetwork> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            EnergyNetwork net = new EnergyNetwork();
            net.temperatureC = source.temperatureC;
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos cable = queue.poll();
                net.cables.put(cable, members.get(cable));
                for (Direction dir : Direction.values()) {
                    BlockPos neighbour = cable.relative(dir);
                    if (remaining.remove(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            net.recomputeProfile();
            net.endpointsDirty = true;
            net.coolingState = source.coolingState;
            if (source.faults.contains(EnergyNetworkFault.BUFFER_OVERFLOW)) {
                net.faults.add(EnergyNetworkFault.BUFFER_OVERFLOW);
            }
            normalizeFaults(net);
            components.add(net);
        }
        components.sort(Comparator.comparing(this::networkAnchor, BLOCK_POS_ORDER));
        if (components.isEmpty()) {
            return;
        }

        long totalCapacity = components.stream().mapToLong(EnergyNetwork::bufferCap).sum();
        if (source.stored > totalCapacity) {
            throw new IllegalStateException("split capacity invariant broken in " + dimension.location()
                    + ": stored=" + source.stored + ", newCapacity=" + totalCapacity);
        }
        long[] storedAllocation = allocateByCapacity(source.stored, components, totalCapacity);
        long[] lastLossAllocation = allocateByCapacity(source.lastLossFe, components, totalCapacity);
        long[] totalLossAllocation = allocateByCapacity(source.totalLossFe, components, totalCapacity);
        for (int i = 0; i < components.size(); i++) {
            EnergyNetwork component = components.get(i);
            component.stored = Math.toIntExact(storedAllocation[i]);
            component.lastLossFe = Math.toIntExact(lastLossAllocation[i]);
            component.totalLossFe = totalLossAllocation[i];
            for (BlockPos cable : component.cables.keySet()) {
                byCable.put(cable, component);
            }
            networks.add(component);
            activeNetworks.add(component);
        }
    }

    private long[] allocateByCapacity(long total, List<EnergyNetwork> components, long totalCapacity) {
        long[] allocation = new long[components.size()];
        List<Integer> remainderOrder = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < components.size(); i++) {
            long weighted = Math.multiplyExact(total, components.get(i).bufferCap());
            allocation[i] = weighted / totalCapacity;
            allocated += allocation[i];
            remainderOrder.add(i);
        }
        remainderOrder.sort(Comparator
                .<Integer>comparingLong(i -> Math.multiplyExact(total, components.get(i).bufferCap()) % totalCapacity)
                .reversed()
                .thenComparing(i -> networkAnchor(components.get(i)), BLOCK_POS_ORDER));
        long undistributed = total - allocated;
        for (int i = 0; i < undistributed; i++) {
            allocation[remainderOrder.get(i)]++;
        }
        return allocation;
    }

    private void enforceBufferCapacity(EnergyNetwork net, BlockPos changedAt,
                                       List<Integer> previousStored, List<Integer> previousCapacities) {
        if (net.stored <= net.bufferCap()) {
            normalizeFaults(net);
            return;
        }
        int storedBefore = net.stored;
        int loss = storedBefore - net.bufferCap();
        net.stored = net.bufferCap();
        net.lastLossFe = loss;
        net.totalLossFe += loss;
        net.faults.add(EnergyNetworkFault.BUFFER_OVERFLOW);
        normalizeFaults(net);
        LOGGER.warn("energy network buffer overflow dimension={} changedAt={} participatingNetworks={} "
                        + "priorStored={} priorCapacities={} storedBefore={} newCapacity={} lost={} "
                        + "cableCount={} voltageLimit={}",
                dimension.location(), changedAt, previousStored.size(), previousStored, previousCapacities, storedBefore,
                net.bufferCap(), loss, net.cables.size(), net.voltageLimit);
    }

    private BlockPos networkAnchor(EnergyNetwork net) {
        return net.cables.keySet().stream().min(BLOCK_POS_ORDER)
                .orElseThrow(() -> new IllegalStateException("energy network has no cables in " + dimension.location()));
    }

    private void normalizeFaults(EnergyNetwork net) {
        if (net.faults.size() > 1) {
            net.faults.remove(EnergyNetworkFault.NONE);
        } else if (net.faults.isEmpty()) {
            net.faults.add(EnergyNetworkFault.NONE);
        }
    }

    // ---- BE capability 读写瞬态缓冲 / 网温 (线缆只收不放, 见 EnergyCableBlockEntity) ----------------

    /** 推式发电机经线缆 receiveEnergy 把电推入本网缓冲。 */
    public int receiveIntoNetwork(BlockPos pos, int amount, boolean simulate) {
        EnergyNetwork net = byCable.get(pos);
        if (net == null || amount <= 0) {
            return 0;
        }
        int room = net.bufferCap() - net.stored;
        if (room < 0) {
            throw new IllegalStateException("network buffer exceeds capacity in " + dimension.location()
                    + " at " + pos + ": stored=" + net.stored + ", capacity=" + net.bufferCap());
        }
        int accepted = Math.min(room, amount);
        if (!simulate) {
            net.stored += accepted;
            if (accepted > 0) {
                activeNetworks.add(net);
            }
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

    /** 复制该坐标所属网的完整只读状态；无网表示线缆尚未在服务端完成 onLoad。 */
    public Optional<EnergyNetworkSnapshot> snapshotAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? Optional.empty() : Optional.of(net.snapshot());
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

    /** 该网当前缓存的精确面端点数；调用前需先经过一次结算完成惰性重建。 */
    public int debugEndpointCountAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net == null ? 0 : net.endpoints.size();
    }

    /** 该坐标所属网当前是否进入 tick 调度；用于守住孤立稳态网络不参与结算的性能契约。 */
    public boolean debugNetworkActiveAt(BlockPos pos) {
        EnergyNetwork net = byCable.get(pos);
        return net != null && activeNetworks.contains(net);
    }

    /** 注入一个合成端点 (仅 GameTest 用): 令真实 settle 循环像遇到真机器一样拉/推它, 无需注册测试方块。 */
    public void debugPutSyntheticEndpoint(BlockPos pos, IEnergyStorage storage) {
        syntheticAnySideEndpoints.put(pos, storage);
        markAllEndpointsDirty();
    }

    /** 注入一个带精确查询面的合成端点，用于验证同一方块的多面能力。 */
    public void debugPutSyntheticEndpoint(BlockPos pos, Direction side, IEnergyStorage storage) {
        syntheticEndpoints.put(new EnergyEndpointKey(pos, side), storage);
        markAllEndpointsDirty();
    }

    private void markAllEndpointsDirty() {
        for (EnergyNetwork net : networks) {
            net.endpointsDirty = true;
            activeNetworks.add(net);
        }
    }

    /** 清空合成端点 (GameTest 收尾)。 */
    public void debugClearSyntheticEndpoints() {
        syntheticEndpoints.clear();
        syntheticAnySideEndpoints.clear();
        markAllEndpointsDirty();
    }

    // ---- 结算 (每 tick END, O(端点)) -----------------------------------------------------------

    private void settle(ServerLevel level) {
        for (EnergyNetwork net : List.copyOf(activeNetworks)) {
            if (!networks.contains(net)) {
                activeNetworks.remove(net);
                continue;
            }
            settleNetwork(level, net);
            if (!needsSettlement(net)) {
                activeNetworks.remove(net);
            }
        }
    }

    private boolean needsSettlement(EnergyNetwork net) {
        return net.endpointsDirty
                || !net.endpoints.isEmpty()
                || net.temperatureC > CableThermics.AMBIENT_C + COOLING_EPSILON_C;
    }

    private void settleNetwork(ServerLevel level, EnergyNetwork net) {
        if (net.endpointsDirty) {
            recomputeEndpoints(level, net);
        }
        net.faults.remove(EnergyNetworkFault.OVER_VOLTAGE);
        normalizeFaults(net);
        int rated = net.ratedCap;
        if (rated <= 0 || net.endpoints.isEmpty()) {
            // 无吞吐则负载 0, 网温向环境回落。
            net.lastLoad = 0;
            net.temperatureC = CableThermics.advanceTemperature(net.temperatureC, 0, rated);
            convergeCoolingToAmbient(net);
            return;
        }

        // 有效吞吐帽 = 额定 × eff(网温): 过热则本 settlement 能搬的电变少。
        int effCap = net.effectiveCap();

        // 电压检查独立于缓冲余量；满缓冲时自研发电端仍必须收到过压回报。
        for (EnergyEndpointKey endpoint : net.endpoints) {
            IEnergyStorage storage = capAt(level, endpoint);
            if (storage instanceof VoltageAwareEnergyStorage voltageAware
                    && storage.canExtract()
                    && voltageAware.outputVoltage().isHigherThan(net.voltageLimit)) {
                voltageAware.reportOvervoltage(net.voltageLimit);
                net.faults.add(EnergyNetworkFault.OVER_VOLTAGE);
                normalizeFaults(net);
            }
        }

        // 拉: 从生产端 (canExtract) 抽入缓冲, 单 settlement 封顶 effCap。
        int room = Math.min(net.bufferCap() - net.stored, effCap);
        int endpointCount = net.endpoints.size();
        int producerStart = Math.floorMod(net.producerCursor, endpointCount);
        int producerScanned = 0;
        while (producerScanned < endpointCount && room > 0) {
            EnergyEndpointKey endpoint = net.endpoints.get((producerStart + producerScanned) % endpointCount);
            producerScanned++;
            IEnergyStorage storage = capAt(level, endpoint);
            if (storage == null || !storage.canExtract()) {
                continue;
            }
            if (storage instanceof VoltageAwareEnergyStorage voltageAware
                    && voltageAware.outputVoltage().isHigherThan(net.voltageLimit)) {
                continue;
            }
            int got = storage.extractEnergy(room, false);
            net.stored += got;
            room -= got;
        }
        if (producerScanned > 0) {
            net.producerCursor = (producerStart + producerScanned) % endpointCount;
        }

        // 推: 从缓冲发给消费端 (canReceive 且非 canExtract), 封顶 effCap; 送达量即本 settlement 负载。
        int pushable = Math.min(net.stored, effCap);
        int delivered = 0;
        int consumerStart = Math.floorMod(net.consumerCursor, endpointCount);
        int consumerScanned = 0;
        while (consumerScanned < endpointCount && pushable > 0) {
            EnergyEndpointKey endpoint = net.endpoints.get((consumerStart + consumerScanned) % endpointCount);
            consumerScanned++;
            IEnergyStorage storage = capAt(level, endpoint);
            if (storage == null || !storage.canReceive() || storage.canExtract()) {
                continue;
            }
            int sent = storage.receiveEnergy(pushable, false);
            net.stored -= sent;
            pushable -= sent;
            delivered += sent;
        }
        if (consumerScanned > 0) {
            net.consumerCursor = (consumerStart + consumerScanned) % endpointCount;
        }

        // 依实际送达负载推进网温 (过载升、低载冷)。
        net.lastLoad = delivered;
        net.temperatureC = CableThermics.advanceTemperature(net.temperatureC, delivered, rated);
        convergeCoolingToAmbient(net);
    }

    private void convergeCoolingToAmbient(EnergyNetwork net) {
        if (net.temperatureC - CableThermics.AMBIENT_C <= COOLING_EPSILON_C) {
            net.temperatureC = CableThermics.AMBIENT_C;
        }
    }

    private void recomputeEndpoints(ServerLevel level, EnergyNetwork net) {
        Set<EnergyEndpointKey> rebuilt = new TreeSet<>();
        for (BlockPos cable : net.cables.keySet()) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = cable.relative(dir);
                if (byCable.containsKey(neighbour)) {
                    continue;
                }
                EnergyEndpointKey endpoint = new EnergyEndpointKey(neighbour, dir.getOpposite());
                if (capAt(level, endpoint) != null) {
                    rebuilt.add(endpoint);
                }
            }
        }
        net.endpoints.clear();
        net.endpoints.addAll(rebuilt);
        net.endpointsDirty = false;
    }

    private IEnergyStorage capAt(ServerLevel level, EnergyEndpointKey endpoint) {
        IEnergyStorage synthetic = syntheticEndpoints.get(endpoint);
        if (synthetic != null) {
            return synthetic;
        }
        synthetic = syntheticAnySideEndpoints.get(endpoint.pos());
        if (synthetic != null) {
            return synthetic;
        }
        if (!level.hasChunkAt(endpoint.pos())) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(endpoint.pos());
        if (be == null) {
            return null;
        }
        return be.getCapability(ForgeCapabilities.ENERGY, endpoint.direction()).resolve().orElse(null);
    }
}
