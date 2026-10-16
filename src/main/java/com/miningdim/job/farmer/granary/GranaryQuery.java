package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 粮仓的查询面 (Spoilage_Granary_DesignSpec「界面」「/granary 命令」)。
 *
 * 命令与账台右键读的是**同一批数字**: 结构与验收清单来自 {@link GranaryInspection}(每次调用都是一次重算),
 * 囤的清单与全服盘点来自账本。这里只做查询与组行, 不写任何状态 —— 写状态的入口只有 {@link GranaryInspection}
 * 与 {@link GranaryRecheck}。
 */
public final class GranaryQuery {

    /** 找到的一座粮仓 (玩家就站在它的范围里, 或者命令点名了它)。 */
    public record Nearby(long gid, BlockPos center, GranaryRecord record) {
    }

    /** 一个囤的一行 (谁在哪、什么状态、连在哪座粮仓上)。 */
    public record BinRow(ResourceLocation dimension, BlockPos pos, GranaryBinState state, long gid) {
    }

    /** 全服盘点的一行。 */
    public record AuditRow(long gid, GranaryRecord record, int bins, int active, int stopped, int pending) {
    }

    private GranaryQuery() {
    }

    /**
     * 玩家正看着的那个储粮囤 (够得着才算)。
     *
     * 界面做出来之前, 开放名单与「存进正看着的那个囤」都靠它选目标 —— 与右键同一个准星, 玩家不用记坐标。
     *
     * @param reach 能够到的距离 (格); 超过这个距离即使看着也不算
     */
    public static Optional<GranaryBinBlockEntity> lookedAtBin(ServerPlayer player, double reach) {
        // partialTicks 取 1.0: 命令判的是「**现在**看着哪一格」, 而 0.0 会按上一 tick 的位置插值
        // (刚传送过的玩家会从原地开始射一条线, 于是站在囤前面也选不中)。
        net.minecraft.world.phys.BlockHitResult hit = (net.minecraft.world.phys.BlockHitResult) player.pick(
                reach, 1.0F, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        BlockPos pos = hit.getBlockPos();
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > reach * reach) {
            return Optional.empty();
        }
        return player.level().getBlockEntity(pos) instanceof GranaryBinBlockEntity bin
                ? Optional.of(bin) : Optional.empty();
    }

    // ============================================================
    // 找粮仓
    // ============================================================

    /**
     * 找玩家**站着**的那座粮仓: 取以他为中心的水平 ±24 / 垂直 ±12 里最近的一座账台 (就是屋子判定的那个范围)。
     *
     * 站在两座之间时按距离取近的; 站在屋外够远就返回空 —— 命令不该去猜「你想看哪座」。
     */
    public static Optional<Nearby> nearest(ServerLevel level, Vec3 position) {
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        ResourceLocation dimension = level.dimension().location();
        Nearby best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GranarySavedData.PosKey key : data.centers()) {
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            BlockPos center = key.pos();
            if (Math.abs(center.getX() + 0.5D - position.x) > GranaryStructureScanner.HORIZONTAL_RADIUS
                    || Math.abs(center.getZ() + 0.5D - position.z) > GranaryStructureScanner.HORIZONTAL_RADIUS
                    || Math.abs(center.getY() + 0.5D - position.y) > GranaryStructureScanner.VERTICAL_RADIUS) {
                continue;
            }
            double distance = center.distToCenterSqr(position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Nearby(data.centerGranary(dimension, center), center, data.granary(
                        data.centerGranary(dimension, center)));
            }
        }
        return Optional.ofNullable(best);
    }

    /** 这座粮仓里的囤 (按入册先后)。 */
    public static List<BinRow> binsOf(ServerLevel level, long gid) {
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        List<BinRow> rows = new ArrayList<>();
        for (GranarySavedData.PosKey key : data.binsOf(gid)) {
            rows.add(new BinRow(key.dimension(), key.pos(),
                    data.binState(key.dimension(), key.pos()), gid));
        }
        return rows;
    }

    /** 某人的全部囤 (全服合计, 按维度 + 坐标排)。 */
    public static List<BinRow> binsOf(ServerLevel level, UUID owner) {
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        List<BinRow> rows = new ArrayList<>();
        for (GranarySavedData.PosKey key : data.bins()) {
            if (owner.equals(data.ownerOf(key.dimension(), key.pos()))) {
                rows.add(new BinRow(key.dimension(), key.pos(),
                        data.binState(key.dimension(), key.pos()),
                        data.granaryOfBin(key.dimension(), key.pos())));
            }
        }
        rows.sort(java.util.Comparator
                .comparing((BinRow row) -> row.dimension().toString())
                .thenComparingLong(row -> row.pos().asLong()));
        return rows;
    }

    /** 全服盘点 (按粮仓编号)。 */
    public static List<AuditRow> audit(ServerLevel level) {        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        List<AuditRow> rows = new ArrayList<>();
        for (GranaryRecord record : data.granaries()) {
            int active = 0;
            int stopped = 0;
            int pending = 0;
            List<GranarySavedData.PosKey> bins = data.binsOf(record.id());
            for (GranarySavedData.PosKey key : bins) {
                switch (data.binState(key.dimension(), key.pos())) {
                    case ACTIVE -> active++;
                    case STOPPED -> stopped++;
                    case PENDING -> pending++;
                }
            }
            rows.add(new AuditRow(record.id(), record, bins.size(), active, stopped, pending));
        }
        return rows;
    }

    /** 这位玩家在这座粮仓里被停用的囤数 (上线提醒与状态页都用它)。 */
    public static int suspendedBins(ServerLevel level, long gid, UUID owner) {
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        int count = 0;
        for (GranarySavedData.PosKey key : data.binsOf(gid)) {
            if (owner.equals(data.ownerOf(key.dimension(), key.pos()))
                    && data.binState(key.dimension(), key.pos()) == GranaryBinState.STOPPED) {
                count++;
            }
        }
        return count;
    }

    // ============================================================
    // 组行 (账台右键与 /granary status 共用)
    // ============================================================

    /**
     * 结构与状态页的文字版: 顶栏一行 + 下一步该干什么 (有损坏就先说损坏与宽限)。
     *
     * 账台右键与 {@code /granary status} 读的是同一份, 免得两处说法不一样。
     */
    public static List<Component> statusLines(ServerLevel level, GranaryInspection.Report report,
                                              ServerPlayer viewer) {
        List<Component> lines = new ArrayList<>();
        GranaryRecord record = report.record();
        lines.add(Component.translatable("message.miningdim.granary.report",
                report.gid(), tierName(record.tier()),
                Component.translatable(record.status().translationKey()),
                report.enablement().enabled(), report.enablement().unsupported()));

        long nowHour = GranaryClock.nowHour(level);
        if (record.status() == GranaryRecord.Status.DAMAGED) {
            String cause = GranaryDamage.describe(record.tier(), report.scan());
            long anchor = GranarySavedData.get(level.getServer().overworld())
                    .graceLogin(record.id(), viewer.getUUID());
            long remaining = record.damagedAtHour() <= 0L ? 0L
                    : GranaryGrace.remainingHours(nowHour, record.damagedAtHour(), anchor);
            lines.add(remaining > 0L
                    ? Component.translatable("message.miningdim.granary.command.damage", cause, remaining)
                    : Component.translatable("message.miningdim.granary.command.damage_expired", cause));
            return lines;
        }

        GranaryTier current = record.tier();
        GranaryTier candidate = current == null ? GranaryTier.SHED : current.next();
        if (candidate == current) {
            lines.add(Component.translatable("message.miningdim.granary.report_top", tierName(current)));
            return lines;
        }
        GranaryAcceptance.Verdict verdict = report.verdictOf(candidate);
        int farmerLevel = JobServices.jobService().level(viewer, JobId.FARMER);
        if (!verdict.accepted()) {
            lines.add(Component.translatable("message.miningdim.granary.report_gap",
                    tierName(candidate), verdict.checklist()));
        } else if (farmerLevel < candidate.unlockLevel()) {
            lines.add(Component.translatable("message.miningdim.granary.report_level",
                    tierName(candidate), candidate.unlockLevel(), farmerLevel));
        } else {
            lines.add(Component.translatable("message.miningdim.granary.report_ready", tierName(candidate)));
        }
        return lines;
    }

    /** 档位名 (还没验收就是「未验收」)。 */
    public static Component tierName(GranaryTier tier) {
        return Component.translatable(tier == null ? "granary.tier.none" : tier.translationKey());
    }

    /** 认人: 在线就写名字, 不在线写 UUID 前八位 (GameTest 服务端没有档案缓存, 不能去查名字)。 */
    public static String nameOf(MinecraftServer server, UUID player) {
        if (player == null) {
            return "-";
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online != null ? online.getGameProfile().getName() : player.toString().substring(0, 8);
    }

    /** 囤方块的位置提示 (命令里写坐标, 点一下能跳过去)。 */
    public static Component where(BlockPos pos) {
        return Component.translatable("message.miningdim.granary.command.where",
                pos.getX(), pos.getY(), pos.getZ(),
                GranaryBlocks.BIN.get().getName());
    }
}
