package com.miningdim.power.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 六向相邻的同档储电自动聚合成的松散组。没有控制器方块、不校验形状：玩家要的效果只是"放一起就是一个
 * 整体"，规则多方块（形状校验 + 控制器）的成本远超这点收益。
 *
 * 组只是逻辑聚合层，不持有任何余额：每块储电仍把自己的份额写在自己的 NBT 里，拆掉一块它就带走自己那份
 * （靠三张储电战利品表的 {@code minecraft:copy_nbt} 把 storedFe 拷进掉落物的 BlockEntityTag，放回去时由
 * 原版 BlockItem 回写；界面读的是整组聚合值，若掉落不带 NBT，这层聚合会把"挖一块蒸发一份余额"完全藏住），
 * 因此不存在"拆分时如何仲裁"的问题，存档格式一行都不用动。组负责三件事：
 *  1. 把总容量/总速率线性叠加成对外的一份读数；
 *  2. 把一次读写按成员均摊下去（先均分，装不下的再顺位溢流）；
 *  3. 守住"整组每 tick 只有一份速率额度"这条反刷电铁律，见 {@link #remainingReceiveBudget}。
 *
 * 整数铁律：组容量按成员数线性叠加，三块未来储电就是 26.5 亿，已越过 int 上限。组内一律 long；只有在
 * 对外暴露 Forge 的 int 版 IEnergyStorage 时才饱和截断，绝不允许强转。
 */
public final class PowerCellGroup {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");

    /**
     * 单组成员上限。不设上限的 flood-fill 会让并组重算成本与容量溢出风险一起失控（64 块未来储电已是
     * 566 亿 FE），超限时新方块自成一组而不是静默并入。
     *
     * 已知代价：被拒的方块不会因为大组后来变小而自动补并——增量维护不做全局回溯，只有把它拆了重放才会
     * 重新评估。这是"绝不在 tick 路径 flood-fill"换来的。
     */
    public static final int MAX_MEMBERS = 64;

    static final Comparator<BlockPos> BLOCK_POS_ORDER = Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    private final PowerCellSpec spec;
    private final NavigableSet<BlockPos> members = new TreeSet<>(BLOCK_POS_ORDER);
    /** 已经报过一次"解析不到"的成员坐标；结算路径每 tick 都会走一遍，不去重就是刷屏。 */
    private final Set<BlockPos> unresolvedMembers = new HashSet<>();

    /** 额度所属的游戏刻；与发电机 extractionTick 同范式，靠时间戳惰性清零，不需要额外 ticker。 */
    private long budgetTick = Long.MIN_VALUE;
    private long budgetReceivedFe;
    private long budgetExtractedFe;

    PowerCellGroup(PowerCellSpec spec) {
        this.spec = spec;
    }

    public PowerCellSpec spec() {
        return spec;
    }

    public int size() {
        return members.size();
    }

    /** 组内坐标序最小的成员；合并顺序与日志都以它为稳定标识。 */
    public BlockPos anchor() {
        if (members.isEmpty()) {
            throw new IllegalStateException("power cell group has no members");
        }
        return members.first();
    }

    public Set<BlockPos> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean contains(BlockPos pos) {
        return members.contains(pos);
    }

    void addMember(BlockPos pos) {
        if (members.size() >= MAX_MEMBERS && !members.contains(pos)) {
            throw new IllegalStateException("power cell group exceeded " + MAX_MEMBERS + " members at " + pos);
        }
        members.add(pos.immutable());
    }

    /** 组总容量 = 成员数 x 单块容量。同档全体共用一份服务器配置，故不需要逐块读值累加。 */
    public long capacityFe() {
        return (long) members.size() * spec.runtime().capacityFe();
    }

    /** 组总传输速率 = 成员数 x 单块速率，线性叠加不设边际递减。 */
    public long transferFePerTick() {
        return (long) members.size() * spec.runtime().transferFePerTick();
    }

    public long storedFe(ServerLevel level) {
        long total = 0L;
        for (PowerCellBlockEntity cell : resolveMembers(level)) {
            total += cell.storedFeLong();
        }
        return total;
    }

    public long lastReceivedFe(ServerLevel level) {
        long total = 0L;
        for (PowerCellBlockEntity cell : resolveMembers(level)) {
            total += cell.lastReceivedFe();
        }
        return total;
    }

    public long lastExtractedFe(ServerLevel level) {
        long total = 0L;
        for (PowerCellBlockEntity cell : resolveMembers(level)) {
            total += cell.lastExtractedFe();
        }
        return total;
    }

    /**
     * 电网侧收电。额度按 tick 计而不是按调用计，这正是端点去重的落点：一组若有多块贴着同一张线缆网，
     * 电网会把每块登记成独立的 EnergyEndpointKey 并逐个 receiveEnergy；若额度按调用计，整组实收就会被
     * 接触面数乘一遍（两块贴网 = 两份组速率），等于凭空刷电。共享额度让第一个被服务到的面吃掉整份速率，
     * 其余面本 tick 一律返回 0。
     */
    int receive(ServerLevel level, int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        long budget = remainingReceiveBudget(level.getGameTime());
        if (budget <= 0L) {
            return 0;
        }
        List<PowerCellBlockEntity> resolved = resolveMembers(level);
        long room = resolvedCapacityFe(resolved) - totalStored(resolved);
        if (room <= 0L) {
            return 0;
        }
        int accepted = (int) Math.min(Math.min(maxReceive, budget), room);
        if (accepted <= 0) {
            return 0;
        }
        if (!simulate) {
            distributeReceive(resolved, accepted);
            chargeBudget(level.getGameTime(), accepted, 0);
        }
        return accepted;
    }

    /** 电网侧放电，与收电共用同一套 tick 额度语义（收发各一份，互不挤占）。 */
    int extractForNetwork(ServerLevel level, int maxExtract, boolean simulate) {
        long budget = remainingExtractBudget(level.getGameTime());
        return extract(level, maxExtract, simulate, budget, true);
    }

    /**
     * 玩家手动给随身装备充电的抽取口。刻意不吃 tick 额度：这条路径靠一次右键内的循环把装备一次灌满，
     * 若并入每 tick 的组额度，一次右键只能灌一份速率，交互就退化成要玩家连点上千次。它仍走同一本账
     * （余额从组内成员真实扣减），只是不把电网的每 tick 吞吐上限套在玩家的一次交互上。
     */
    int extractForCharging(ServerLevel level, int maxExtract) {
        return extract(level, maxExtract, false, transferFePerTick(), false);
    }

    private int extract(ServerLevel level, int maxExtract, boolean simulate, long budget, boolean chargeBudget) {
        if (maxExtract <= 0 || budget <= 0L) {
            return 0;
        }
        List<PowerCellBlockEntity> resolved = resolveMembers(level);
        long stored = totalStored(resolved);
        if (stored <= 0L) {
            return 0;
        }
        int extracted = (int) Math.min(Math.min(maxExtract, budget), stored);
        if (extracted <= 0) {
            return 0;
        }
        if (!simulate) {
            distributeExtract(resolved, extracted);
            if (chargeBudget) {
                chargeBudget(level.getGameTime(), 0, extracted);
            }
        }
        return extracted;
    }

    private long remainingReceiveBudget(long gameTime) {
        long used = budgetTick == gameTime ? budgetReceivedFe : 0L;
        return transferFePerTick() - used;
    }

    private long remainingExtractBudget(long gameTime) {
        long used = budgetTick == gameTime ? budgetExtractedFe : 0L;
        return transferFePerTick() - used;
    }

    private void chargeBudget(long gameTime, int received, int extracted) {
        if (budgetTick != gameTime) {
            budgetTick = gameTime;
            budgetReceivedFe = 0L;
            budgetExtractedFe = 0L;
        }
        budgetReceivedFe += received;
        budgetExtractedFe += extracted;
    }

    /**
     * 均摊注入：先按成员数等分（余数给坐标序靠前的成员），装不下的再顺位溢流到还有空间的成员。
     * 均分而非顺序填满是为了让"拆掉任意一块损失的份额"接近平均值，而不是取决于玩家拆的是哪一块。
     */
    private void distributeReceive(List<PowerCellBlockEntity> resolved, int amount) {
        int count = resolved.size();
        long perMemberCapacity = spec.runtime().capacityFe();
        long base = amount / count;
        long extra = amount % count;
        long placed = 0L;
        for (int i = 0; i < count; i++) {
            PowerCellBlockEntity cell = resolved.get(i);
            long want = base + (i < extra ? 1L : 0L);
            long give = Math.min(want, perMemberCapacity - cell.storedFeLong());
            if (give > 0L) {
                cell.addShare(give);
                placed += give;
            }
        }
        long leftover = amount - placed;
        for (int i = 0; i < count && leftover > 0L; i++) {
            PowerCellBlockEntity cell = resolved.get(i);
            long give = Math.min(leftover, perMemberCapacity - cell.storedFeLong());
            if (give > 0L) {
                cell.addShare(give);
                leftover -= give;
            }
        }
        if (leftover != 0L) {
            throw new IllegalStateException("power cell group failed to place " + leftover
                    + " FE across " + count + " members at " + anchor());
        }
    }

    /** 均摊抽取，与 {@link #distributeReceive} 镜像：先等分，取不够的再顺位从还有余额的成员补。 */
    private void distributeExtract(List<PowerCellBlockEntity> resolved, int amount) {
        int count = resolved.size();
        long base = amount / count;
        long extra = amount % count;
        long taken = 0L;
        for (int i = 0; i < count; i++) {
            PowerCellBlockEntity cell = resolved.get(i);
            long want = base + (i < extra ? 1L : 0L);
            long take = Math.min(want, cell.storedFeLong());
            if (take > 0L) {
                cell.removeShare(take);
                taken += take;
            }
        }
        long leftover = amount - taken;
        for (int i = 0; i < count && leftover > 0L; i++) {
            PowerCellBlockEntity cell = resolved.get(i);
            long take = Math.min(leftover, cell.storedFeLong());
            if (take > 0L) {
                cell.removeShare(take);
                leftover -= take;
            }
        }
        if (leftover != 0L) {
            throw new IllegalStateException("power cell group failed to draw " + leftover
                    + " FE across " + count + " members at " + anchor());
        }
    }

    private static long totalStored(Collection<PowerCellBlockEntity> resolved) {
        long total = 0L;
        for (PowerCellBlockEntity cell : resolved) {
            total += cell.storedFeLong();
        }
        return total;
    }

    /**
     * 本次读写真正能参与的容量。解析不到的成员绝不能算进可注入空间，否则 distributeReceive 会收下一笔
     * 谁都装不下的电，最后在"failed to place"那行炸掉。
     */
    private long resolvedCapacityFe(Collection<PowerCellBlockEntity> resolved) {
        return (long) resolved.size() * spec.runtime().capacityFe();
    }

    /**
     * 成员上限 64 是这里 O(N) 全量解析的前提：一次读写最多摸 64 个方块实体，成本有界。
     *
     * 两条刻意的防御，都是因为本方法挂在电网每 tick 的结算路径上：
     *  1. 先判 hasChunkAt 再取方块实体——{@code level.getBlockEntity} 会经 getChunkAt 同步强制加载区块，
     *     一次读写就能拖着 64 个坐标各触发一次同步加载。
     *  2. 解析不到就按"本次读写不含这块"处理并告警，而不是抛异常。抛出去就是拿一次索引不一致换服务端
     *     主线程崩溃；余额本来就随该方块自己的 NBT 走，跳过它不会凭空抹掉任何 FE。
     */
    private List<PowerCellBlockEntity> resolveMembers(ServerLevel level) {
        List<PowerCellBlockEntity> resolved = new ArrayList<>(members.size());
        for (BlockPos member : members) {
            BlockEntity be = level.hasChunkAt(member) ? level.getBlockEntity(member) : null;
            if (be instanceof PowerCellBlockEntity cell && cell.spec() == spec) {
                if (unresolvedMembers.remove(member)) {
                    LOGGER.warn("power cell group member resolvable again at {} in {}",
                            member, level.dimension().location());
                }
                resolved.add(cell);
                continue;
            }
            if (unresolvedMembers.add(member.immutable())) {
                LOGGER.warn("power cell group member unresolved at {} in {}; expected tier {}, found {}",
                        member, level.dimension().location(), spec.id(), be);
            }
        }
        return resolved;
    }
}
