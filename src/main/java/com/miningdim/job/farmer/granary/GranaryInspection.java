package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
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

        // 没验收、损坏: 一律全排待启用 (规格「只有启用的囤才能存、才停表」)。
        int supported = record != null && record.acceptsDeposits()
                ? scan.supportedBins(record.tier().needsColdSources()) : 0;
        GranaryEnablement.Decision enablement = record == null
                ? new GranaryEnablement.Decision(0, 0) : data.applyEnablement(gid, supported);

        List<GranaryAcceptance.Verdict> verdicts = new ArrayList<>();
        for (GranaryTier tier : GranaryTier.values()) {
            verdicts.add(GranaryAcceptance.check(tier, scan));
        }
        return new Report(gid, record, scan, verdicts, enablement);
    }

    /**
     * 验收: 服务器在这一刻读点击者的农夫等级, 决定这一下能验收的最高档。
     *
     * 先重算再判 (不拿旧结构验收), 通过就把档位锁进登记项并立刻重排启用名单 —— 库存原样保留。
     */
    public static Acceptance accept(ServerLevel level, BlockPos centerPos, ServerPlayer player) {
        if (player instanceof FakePlayer || player.isRemoved()) {
            return new Acceptance(null, null, Rejection.FAKE_PLAYER);
        }
        Report report = inspect(level, centerPos);
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
        GranaryRecord accepted = report.record().accepted(candidate, player.getUUID(), nowHour(level));
        data.putGranary(accepted);
        data.applyEnablement(report.gid(), report.scan().supportedBins(candidate.needsColdSources()));
        return new Acceptance(candidate, verdict, Rejection.NONE);
    }

    /** 验收时刻按整点记 (与停表同一把挂钟)。 */
    private static long nowHour(ServerLevel level) {
        return FreshnessClockData.nowMinute(level) / 60L;
    }
}
