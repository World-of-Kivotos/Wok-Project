package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 账台这一下的全部动作 (Spoilage_Granary_DesignSpec「建造与验收」「容量怎么算」)。
 *
 * 把「右键账台会发生什么」收在一个地方, 界面与命令以后都走同一个入口, 免得三处各写一遍判定:
 * <ol>
 *   <li><b>验算</b>: 从账台起洪泛这间屋, 数室内格数、账台、气窗、冷源, 并列出屋里整个在内的囤;</li>
 *   <li><b>连线</b>: 屋里的囤连到这座粮仓 (结构不成立时不连 —— 破了洞的屋子不该往里收囤);</li>
 *   <li><b>排启用</b>: 已验收且完好的粮仓按部件重排启用名单, 没验收/损坏的粮仓一律全排「待启用」
 *       (规格: 只有启用的囤才能存、才停表);</li>
 *   <li><b>裁决</b>: 三档各出一份清单, 界面直接显示。</li>
 * </ol>
 *
 * 只读判定 + 写账本, 不动方块; 每次调用都是一次完整的重算 (方块变动后的重算节流由调用方决定)。
 */
public final class GranaryInspection {

    /** 一次验算的全部产出。 */
    public record Report(long gid, GranaryRecord record, ScanResult scan,
                         List<GranaryAcceptance.Verdict> verdicts, GranaryEnablement.Decision enablement) {

        public Report {
            verdicts = List.copyOf(verdicts);
        }

        /** 还没开号 (账本里这座账台没有粮仓)。 */
        public boolean orphan() {
            return record == null;
        }

        /** 某一档的清单。 */
        public GranaryAcceptance.Verdict verdictOf(GranaryTier tier) {
            for (GranaryAcceptance.Verdict verdict : verdicts) {
                if (verdict.tier() == tier) {
                    return verdict;
                }
            }
            throw new IllegalArgumentException("no verdict for " + tier);
        }

        /** 现在这一档 (还没验收过是空的)。 */
        public Optional<GranaryTier> tier() {
            return record == null ? Optional.empty() : Optional.ofNullable(record.tier());
        }
    }

    /** 验收没通过的原因 (给提示用; 通过了就是 {@link #NONE})。 */
    public enum Rejection {
        NONE,
        /** 这不是一座粮仓 (账本里没开号)。 */
        NOT_A_GRANARY,
        /** 机器不能验收。 */
        FAKE_PLAYER,
        /** 已经是最高档。 */
        TOP_TIER,
        /** 验收人的农夫等级不够。 */
        LEVEL_TOO_LOW,
        /** 结构或部件还不满足这一档。 */
        NOT_READY
    }

    /** 一次验收的结果。 */
    public record Acceptance(GranaryTier tier, GranaryAcceptance.Verdict verdict, Rejection rejection) {

        public boolean accepted() {
            return tier != null;
        }
    }

    private GranaryInspection() {
    }

    /** 验算一座账台 (右键看结构、以及验收前的重算都走它)。 */
    public static Report inspect(ServerLevel level, BlockPos centerPos) {
        return inspect(level, centerPos, nowHour(level));
    }

    /**
     * 同上, 但把「现在」显式传进来 (复查节流与用例都要能指定时刻)。
     */
    public static Report inspect(ServerLevel level, BlockPos centerPos, long nowHour) {
        GranarySavedData data = GranarySavedData.get(level);
        ResourceLocation dimension = level.dimension().location();
        long gid = data.centerGranary(dimension, centerPos);
        GranaryRecord record = gid == 0L ? null : data.granary(gid);
        ScanResult scan = GranaryStructureScanner.scan(level, centerPos);

        if (record != null && scan.ok()) {
            // 结构成立才收囤: 屋里的囤连到这座粮仓。连线只增不减 —— 囤的位置不会变 (拆了就归还额度),
            // 所以入册的囤不会因为别人改屋子而掉出名单。
            for (BlockPos bin : scan.binPositions()) {
                if (data.ownerOf(dimension, bin) != null) {
                    data.linkBin(dimension, bin, gid);
                }
            }
        }

        record = refreshStatus(data, record, scan, nowHour);

        // 没验收、损坏: 一律全排待启用 (规格「只有启用的囤才能存、才停表」)。
        int supported = record != null && record.acceptsDeposits()
                ? scan.supportedBins(record.tier().needsColdSources()) : 0;
        GranaryEnablement.Decision enablement = record == null
                ? new GranaryEnablement.Decision(0, 0) : data.applyEnablement(gid, supported, nowHour);
        if (record != null) {
            applyBinClocks(level, data, record, nowHour);
        }

        List<GranaryAcceptance.Verdict> verdicts = new ArrayList<>();
        for (GranaryTier tier : GranaryTier.values()) {
            verdicts.add(GranaryAcceptance.check(tier, scan));
        }
        return new Report(gid, record, scan, verdicts, enablement);
    }

    /**
     * 损坏与修好两条状态切换 (规格的状态机)。
     *
     * 未验收的粮仓不判损坏 —— 它本来就不能存, 没有「坏掉」可言 (状态机里 {@code U} 只通向验收)。
     * 损坏只记**第一次**被发现的时刻: 宽限从那一刻起算, 反复验算不刷新它。
     */
    private static GranaryRecord refreshStatus(GranarySavedData data, GranaryRecord record,
                                               ScanResult scan, long nowHour) {
        if (record == null || !record.accepted()) {
            return record;
        }
        boolean broken = GranaryDamage.structural(record.tier(), scan);
        if (broken && record.status() == GranaryRecord.Status.OK) {
            // 出事时刻至少是 1 (0 是「没坏过」的哨兵; 挂钟刚开始走的世界里也别把宽限算成没有)。
            GranaryRecord damaged = record.damaged(Math.max(1L, nowHour));
            data.putGranary(damaged);
            return damaged;
        }
        if (!broken && record.status() == GranaryRecord.Status.DAMAGED) {
            GranaryRecord repaired = record.repaired();
            data.putGranary(repaired);
            // 修好之后宽限锚点清掉: 下次再坏是另一段宽限。
            data.clearGraceLogins(record.id());
            return repaired;
        }
        return record;
    }

    /**
     * 每个囤的停表开关 ({@code GranaryBinBlockEntity#stopping})。
     *
     * 规矩与顺序照规格:
     * <ol>
     *   <li>粮仓完好 → 启用的囤照常停表; 修好时如果这颗囤**在过宽限之后**才被停掉, 重新起算
     *       (「重新存满 24 小时起恢复停表」: 换一个新的入仓锚点, 本趟已计归零; 已经记在物品上的照旧留着);</li>
     *   <li>粮仓损坏 → 宽限期内照常停表 (这就是「考试周回来东西照样完好」的全部实现);
     *       过了宽限就**结算到宽限截止那一刻**再关掉 —— 结算到「发现的那一刻」等于离线一个月也能被补记;</li>
     *   <li>未验收 / 待启用 / 停用 → 关掉。</li>
     * </ol>
     *
     * 只动已经在内存里的囤方块实体: 区块没加载时保持原状态 (规格「房子跨到未加载区块时不判」),
     * 等它下次加载或被打开时再对齐。
     */
    private static void applyBinClocks(ServerLevel level, GranarySavedData data, GranaryRecord record,
                                       long nowHour) {
        ResourceLocation dimension = level.dimension().location();
        for (GranarySavedData.PosKey key : data.binsOf(record.id())) {
            BlockPos pos = key.pos();
            if (!(level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin)) {
                continue;
            }
            boolean clocking = shouldClock(data, record, key, nowHour);
            if (clocking == bin.stopping()) {
                continue;
            }
            if (bin.stopping()) {
                // 先按原来的状态结算到**宽限截止那一刻**, 再切换状态 (过期了才走到这里)。
                bin.settleAll(clockedUntil(data, record, key, nowHour));
            } else {
                // 从「没在停表」转回停表: 只有修好这一条路, 重新起算。
                bin.restartClock(nowHour);
            }
            bin.setStopping(clocking);
        }
    }

    /**
     * 每颗囤现在该不该停表。
     *
     * 注意「能不能存」与「表还在不在走」是两件事: 损坏之后囤立刻变成只能取 (不能存), 但**宽限期内照常停表** ——
     * 这正是「考试周回来东西照样完好」的全部实现, 所以这里必须先判损坏/停用, 不能拿「能不能存」当判据。
     */
    private static boolean shouldClock(GranarySavedData data, GranaryRecord record,
                                       GranarySavedData.PosKey key, long nowHour) {
        GranaryBinState state = data.binState(key.dimension(), key.pos());
        if (record.acceptsDeposits()) {
            if (state.acceptsDeposits()) {
                return true;
            }
            // 部件撑不住被停用的那几个「同样走宽限期」: 宽限内表照走, 过了才停。
            return state == GranaryBinState.STOPPED
                    && inGrace(data, record.id(), key,
                            data.binSuspendedAt(key.dimension(), key.pos()), nowHour);
        }
        // 损坏: 原来启用的囤 (现在只能取) 在宽限期内照常停表; 从没启用过的没表可走。
        return state != GranaryBinState.PENDING
                && inGrace(data, record.id(), key, record.damagedAtHour(), nowHour);
    }

    /** 结算到哪一刻: 还在宽限期就是现在, 过期了就是宽限截止那一刻 (不补记过期的那些小时)。 */
    private static long clockedUntil(GranarySavedData data, GranaryRecord record,
                                     GranarySavedData.PosKey key, long nowHour) {
        long anchor = record.acceptsDeposits()
                ? data.binSuspendedAt(key.dimension(), key.pos())
                : record.damagedAtHour();
        if (anchor <= 0L) {
            // 不知道从什么时候起算 (旧档): 保守地结算到现在, 不白送停表。
            return nowHour;
        }
        return Math.min(nowHour, GranaryGrace.deadline(anchor,
                data.graceLogin(record.id(), data.ownerOf(key.dimension(), key.pos()))));
    }

    private static boolean inGrace(GranarySavedData data, long gid, GranarySavedData.PosKey key,
                                   long anchor, long nowHour) {
        if (anchor <= 0L) {
            return false;
        }
        return GranaryGrace.inGrace(nowHour, anchor,
                data.graceLogin(gid, data.ownerOf(key.dimension(), key.pos())));
    }

    /**
     * 验收: 服务器在这一刻读点击者的农夫等级, 决定这一下能验收的最高档。
     *
     * 先重算再判 (不拿旧结构验收), 通过就把档位锁进登记项, 然后**再整体重算一遍** ——
     * 启用名单与每个囤的停表开关都要按新档位重新落一遍。库存原样保留。
     */
    public static Acceptance accept(ServerLevel level, BlockPos centerPos, ServerPlayer player) {
        if (player instanceof FakePlayer || player.isRemoved()) {
            return new Acceptance(null, null, Rejection.FAKE_PLAYER);
        }
        return accept(level, centerPos, player, nowHour(level));
    }

    /** 同上, 但把「现在」显式传进来 (用例与复查节流用)。 */
    public static Acceptance accept(ServerLevel level, BlockPos centerPos, ServerPlayer player, long nowHour) {
        if (player instanceof FakePlayer || player.isRemoved()) {
            return new Acceptance(null, null, Rejection.FAKE_PLAYER);
        }
        Report report = inspect(level, centerPos, nowHour);
        if (report.orphan()) {
            return new Acceptance(null, null, Rejection.NOT_A_GRANARY);
        }
        GranaryTier current = report.record().tier();
        GranaryTier candidate = current == null ? GranaryTier.SHED : current.next();
        if (candidate == current) {
            return new Acceptance(null, report.verdictOf(candidate), Rejection.TOP_TIER);
        }
        int farmerLevel = JobServices.jobService().level(player, JobId.FARMER);
        GranaryAcceptance.Verdict verdict = report.verdictOf(candidate);
        if (farmerLevel < candidate.unlockLevel()) {
            return new Acceptance(null, verdict, Rejection.LEVEL_TOO_LOW);
        }
        if (!verdict.accepted()) {
            return new Acceptance(null, verdict, Rejection.NOT_READY);
        }
        GranarySavedData data = GranarySavedData.get(level);
        GranaryRecord accepted = report.record().accepted(candidate, player.getUUID(), nowHour);
        data.putGranary(accepted);
        // 档位锁上之后再整体算一遍: 启用名单与每个囤的停表开关都跟着新档位走 (只调 applyEnablement 的话,
        // 囤方块实体上的停表开关还停在「未验收」那一趟算出来的结果上)。
        inspect(level, centerPos, nowHour);
        return new Acceptance(candidate, verdict, Rejection.NONE);
    }

    /** 验收时刻按整点记 (与停表同一把挂钟)。 */
    private static long nowHour(ServerLevel level) {
        return FreshnessClockData.nowMinute(level) / 60L;
    }
}
