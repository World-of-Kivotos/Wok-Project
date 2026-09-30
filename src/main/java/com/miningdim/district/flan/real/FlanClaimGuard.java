package com.miningdim.district.flan.real;

import com.miningdim.district.core.PlotArea;
import com.miningdim.district.guard.DistrictWorldGuards;
import com.miningdim.district.guard.DistrictZoneSnapshot;
import com.miningdim.district.guard.GuardView;
import com.miningdim.district.guard.PersonalClaimGuard;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimBox;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.player.PlayerClaimData;
import io.github.flemmli97.flan.player.display.DisplayBox;
import io.github.flemmli97.flan.player.display.EnumDisplayType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 两个 Flan mixin (设计文档 22.20) 的方法体转调这里: F1 ({@code ClaimStorage.createClaim} 的 HEAD) 与 F2
 * ({@code ClaimStorage.resizeClaim} 的 HEAD)。这里只做 Flan 那一侧的事 (把 mixin 传进来的 {@code Object} 转成
 * {@link ClaimStorage} / {@link Claim}、挡金锄头的陈旧编辑 (22.22)、读框与管理员标志、画红框), 禁圈区的判定全在
 * {@link PersonalClaimGuard}。
 *
 * <p>返回 true = 拒绝 (mixin 就 {@code setReturnValue(false)}): 在 Flan 自己的任何检查之前拒绝, 什么都没消耗 (圈地冷却、
 * 领地格数都只在成功时才动)。任何异常一律放行 (P42), 记进放行次数。
 */
public final class FlanClaimGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private FlanClaimGuard() {
    }

    /**
     * F1: 新圈个人领地。候选领地在动手的人所在的世界里 (Flan 的 {@code new Claim(pos1, pos2, player)} 也这么取),
     * 两角任意顺序, 只看 X/Z (3D 领地同样)。
     */
    public static boolean createDenied(BlockPos pos1, BlockPos pos2, ServerPlayer player) {
        try {
            GuardView view = DistrictWorldGuards.view();
            if (!view.personalClaimsActive()) {
                return false;
            }
            ServerLevel level = player.serverLevel();
            PlotArea box = new PlotArea(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getZ(), pos2.getZ()),
                    Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getZ(), pos2.getZ()));
            return settle(player, level, PersonalClaimGuard.decideCreate(view, level, player, box), false);
        } catch (RuntimeException | LinkageError failure) {
            PersonalClaimGuard.failOpen("create", failure);
            return false;
        }
    }

    /**
     * F2: 改一块顶层领地的范围。先挡金锄头的陈旧编辑 ({@link #staleEdit}, 对谁都拒, 也在管理员领地之前); 然后管理员领地
     * (含自管区父领地) 不管; 新框照抄 Flan 的公式 ({@link PersonalClaimGuard#resizedBox}), 不许新占禁圈区里的列。禁圈区按
     * 领地自己所在的世界查。
     *
     * @param storageObject 被注入的 Flan {@code ClaimStorage} (mixin 的 {@code this})
     * @param claimObject   Flan 的 {@code Claim} (mixin 里写成 {@code @Coerce Object}: mixin 包不许出现 Flan 的包名)
     */
    public static boolean resizeDenied(Object storageObject, Object claimObject, BlockPos from, BlockPos to,
                                       ServerPlayer player) {
        try {
            GuardView view = DistrictWorldGuards.view();
            if (!view.personalClaimsActive()) {
                return false;
            }
            ClaimStorage storage = (ClaimStorage) storageObject;
            Claim claim = (Claim) claimObject;
            if (staleEdit(storage, claim, player)) {
                refuseStaleEdit(storage, claim, player);
                return true;
            }
            if (claim.isAdminClaim()) {
                return false;
            }
            ClaimBox box = claim.getDimensions();
            PlotArea old = new PlotArea(box.minX(), box.minZ(), box.maxX(), box.maxZ());
            PlotArea next = PersonalClaimGuard.resizedBox(box.minX(), box.minZ(), box.maxX(), box.maxZ(), from.getX(),
                    from.getZ(), to.getX(), to.getZ());
            ServerLevel level = claim.getLevel();
            return settle(player, level, PersonalClaimGuard.decideResize(view, level, player, false, old, next), true);
        } catch (RuntimeException | LinkageError failure) {
            PersonalClaimGuard.failOpen("resize", failure);
            return false;
        }
    }

    /** 编辑状态与登记不一致时一律拒 (22.22)。 */
    static boolean staleEdit(ClaimStorage storage, Claim claim, ServerPlayer player) {
        return claim.isRemoved() || claim.getLevel() != player.serverLevel()
                || storage.getFromUUID(claim.getClaimID()) != claim;
    }

    /** 陈旧编辑的拒绝: 记一行 INFO (谁、哪块、为什么), 红字请动手的人重新点角。记日志、发提示出错都不影响拒绝。 */
    private static void refuseStaleEdit(ClaimStorage storage, Claim claim, ServerPlayer player) {
        try {
            ServerLevel claimLevel = claim.getLevel();
            LOGGER.info("[miningdim] district: refused a stale claim edit by {} (claim {}, removed={}, claim level {}, "
                            + "player level {}, registered in this storage={})", player.getGameProfile().getName(),
                    claim.getClaimID(), claim.isRemoved(),
                    claimLevel == null ? "?" : claimLevel.dimension().location(),
                    player.serverLevel().dimension().location(), storage.getFromUUID(claim.getClaimID()) == claim);
            PersonalClaimGuard.tellStaleEdit(player);
        } catch (RuntimeException failure) {
            LOGGER.debug("[miningdim] district: reporting the stale claim edit by {} failed: {}",
                    player.getGameProfile().getName(), failure.toString());
        }
    }

    /** 拒绝: 红字、画出禁圈区, 返回 true; OP 例外: 金字, 放行; 其余放行。 */
    private static boolean settle(ServerPlayer player, ServerLevel level, PersonalClaimGuard.Decision decision,
                                  boolean resize) {
        if (decision.outcome() == PersonalClaimGuard.Outcome.ALLOW) {
            return false;
        }
        PersonalClaimGuard.tell(player, decision, resize);
        if (!decision.denied()) {
            return false;
        }
        DistrictZoneSnapshot.BanHit hit = decision.hit();
        if (hit != null) {
            showZone(player, level, hit.zone());
        }
        return true;
    }

    /**
     * 用 Flan 的显示接口画出禁圈区 (冲突的红框, 全高; FTB Chunks 那条接缝画冲突区块用的是同样的 DisplayBox 与高度)。
     * 尽力而为: 单独接住异常, 失败只记 DEBUG, 不影响拒绝; 假玩家不画。
     */
    static void showZone(ServerPlayer player, ServerLevel level, PlotArea zone) {
        if (player instanceof FakePlayer) {
            return;
        }
        try {
            PlayerClaimData.get(player).addDisplayClaim(new DisplayBox(zone.minX(), level.getMinBuildHeight(),
                    zone.minZ(), zone.maxX(), level.getMaxBuildHeight(), zone.maxZ()), EnumDisplayType.CONFLICT,
                    player.blockPosition().getY());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("[miningdim] district: drawing the no-claim zone for {} failed: {}",
                    player.getGameProfile().getName(), failure.toString());
        }
    }
}
