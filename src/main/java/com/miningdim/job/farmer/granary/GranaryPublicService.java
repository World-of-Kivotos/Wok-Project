package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 官仓公开模式的服务面 (Spoilage_Granary_DesignSpec「合建: 入册与权限」)。
 *
 * 与 {@link GranaryTrustService} 同一个位置: 命令、界面、两条存取的路径都调这里，判定只有一处 ——
 * <ol>
 *   <li><b>只有官仓能开</b>: 档位读**账本**里的登记项 (不看方块实体上的镜像), 粮棚与冷库一律拒绝;</li>
 *   <li><b>只有囤主能改</b>: 房主与名单上的人都不能替别人的囤开这个口子;</li>
 *   <li><b>配额按人按天</b>: 单位是「组」(一次整堆), 日序号按北京 08:00 翻 (见 {@link GranaryPublic});</li>
 *   <li><b>捐粮只收未腐烂的易腐食物</b>: 与一键存入同一条口径 (豁免品、不腐烂的、已经腐烂的一律不收)。</li>
 * </ol>
 */
public final class GranaryPublicService {

    private GranaryPublicService() {
    }

    /** 命令与界面能做的四件事。 */
    public enum Action {
        /** 打开公开捐粮 (顺带设每天几组)。 */
        DONATE_ON,
        /** 关掉公开捐粮。 */
        DONATE_OFF,
        /** 打开公开取用 (顺带设每天几组)。 */
        TAKE_ON,
        /** 关掉公开取用。 */
        TAKE_OFF
    }

    /** 一次操作的结果码。 */
    public enum Outcome {
        /** 开了。 */
        ENABLED,
        /** 关了。 */
        DISABLED,
        /** 这囤不是你的。 */
        NOT_OWNER,
        /** 这座粮仓不是官仓 (公开模式只有官仓有)。 */
        NOT_STATE
    }

    /** 一次操作的完整回执 (结果码 + 操作后的设置)。 */
    public record Result(Outcome outcome, GranaryPublic.Settings settings) {

        public boolean accepted() {
            return outcome == Outcome.ENABLED || outcome == Outcome.DISABLED;
        }
    }

    // ============================================================
    // 改设置 (命令与界面共用)
    // ============================================================

    public static Result apply(ServerLevel level, GranaryBinBlockEntity bin, ServerPlayer actor,
                               Action action, int groups) {
        if (bin == null || actor == null || action == null) {
            return new Result(Outcome.NOT_OWNER, GranaryPublic.Settings.OFF);
        }
        if (!actor.getUUID().equals(bin.owner())) {
            return new Result(Outcome.NOT_OWNER, bin.publicSettings());
        }
        boolean opening = action == Action.DONATE_ON || action == Action.TAKE_ON;
        if (opening && !isStateGranary(level, bin)) {
            return new Result(Outcome.NOT_STATE, bin.publicSettings());
        }
        GranaryPublic.Settings settings = bin.publicSettings();
        GranaryPublic.Settings next = switch (action) {
            case DONATE_ON -> settings.withDonate(true, groups);
            case DONATE_OFF -> settings.withDonate(false, 0);
            case TAKE_ON -> settings.withTake(true, groups);
            case TAKE_OFF -> settings.withTake(false, 0);
        };
        bin.setPublicSettings(next);
        return new Result(opening ? Outcome.ENABLED : Outcome.DISABLED, next);
    }

    // ============================================================
    // 查询
    // ============================================================

    /** 这个囤所在的粮仓登记项 (不在任何粮仓里返回 null)。 */
    public static GranaryRecord recordOf(ServerLevel level, GranaryBinBlockEntity bin) {
        if (level == null || bin == null) {
            return null;
        }
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        ResourceLocation dimension = level.dimension().location();
        long gid = data.granaryOfBin(dimension, bin.getBlockPos());
        return gid < 0L ? null : data.granary(gid);
    }

    /** 这座粮仓是不是官仓 (公开模式的前提)。 */
    public static boolean isStateGranary(ServerLevel level, GranaryBinBlockEntity bin) {
        GranaryRecord record = recordOf(level, bin);
        return record != null && GranaryPublic.tierAllows(record.tier());
    }

    /** 公开捐粮现在开着吗 (档位已降回冷库的一律算关 —— 不需要额外的"降档时关掉"钩子)。 */
    public static boolean donationOpen(ServerLevel level, GranaryBinBlockEntity bin) {
        return bin.publicSettings().donate() && isStateGranary(level, bin);
    }

    /** 公开取用现在开着吗。 */
    public static boolean takeOpen(ServerLevel level, GranaryBinBlockEntity bin) {
        return bin.publicSettings().take() && isStateGranary(level, bin);
    }

    /**
     * 这位玩家现在能不能靠「公开捐粮」往这个囤里放东西。
     *
     * 名单上的人 (含囤主) 走自己那条路, 与公开模式无关 —— 这里只回答"陌生人能不能靠公开模式进来"。
     */
    public static boolean mayDonate(ServerLevel level, GranaryBinBlockEntity bin, ServerPlayer player,
                                    long nowMinute) {
        if (level == null || bin == null || player == null) {
            return false;
        }
        if (GranaryAccess.mayStore(bin.owner(), bin.access(), player.getUUID())) {
            return false;
        }
        if (!donationOpen(level, bin)) {
            return false;
        }
        long day = GranaryPublic.dayOf(nowMinute);
        return GranaryPublic.remainingDonations(bin.publicSettings(), bin.publicUsage(player.getUUID(), day)) > 0;
    }

    /** 这位玩家现在能不能靠「公开取用」从这个囤里拿东西。 */
    public static boolean mayTake(ServerLevel level, GranaryBinBlockEntity bin, ServerPlayer player,
                                  long nowMinute) {
        if (level == null || bin == null || player == null) {
            return false;
        }
        if (GranaryAccess.mayTake(bin.owner(), bin.access(), player.getUUID())) {
            return false;
        }
        if (!takeOpen(level, bin)) {
            return false;
        }
        long day = GranaryPublic.dayOf(nowMinute);
        return GranaryPublic.remainingTakes(bin.publicSettings(), bin.publicUsage(player.getUUID(), day)) > 0;
    }

    /** 这个人今天在这个囤还能捐几组 (界面与命令的提示用)。 */
    public static int remainingDonations(ServerLevel level, GranaryBinBlockEntity bin, ServerPlayer player,
                                         long nowMinute) {
        if (level == null || bin == null || player == null) {
            return 0;
        }
        return GranaryPublic.remainingDonations(bin.publicSettings(),
                bin.publicUsage(player.getUUID(), GranaryPublic.dayOf(nowMinute)));
    }

    /** 这个人今天在这个囤还能取几组。 */
    public static int remainingTakes(ServerLevel level, GranaryBinBlockEntity bin, ServerPlayer player,
                                     long nowMinute) {
        if (level == null || bin == null || player == null) {
            return 0;
        }
        return GranaryPublic.remainingTakes(bin.publicSettings(),
                bin.publicUsage(player.getUUID(), GranaryPublic.dayOf(nowMinute)));
    }

    /**
     * 一件东西能不能捐进官仓: 未腐烂的易腐食物, 且不是开服迁移的老货 (那批额度已记满, 进仓等于白送)。
     *
     * 与一键存入同一个口径 ({@link GranaryDeposit}), 判据的顺序也一样: 迁移货排在腐烂之后、额度之前。
     */
    public static boolean donatable(ItemStack stack, long nowMinute) {
        if (stack.isEmpty() || !FreshnessCategories.spoils(stack) || FreshnessCategories.isExempt(stack)) {
            return false;
        }
        if (FreshnessData.isMigrated(stack)) {
            return false;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
        return !FreshnessData.isRotten(stack, shelfLifeHours, nowMinute);
    }

    /** 现在这一刻 (分钟) —— 只有服务端能读挂钟。 */
    public static long nowMinute(ServerLevel level) {
        return FreshnessClockData.nowMinute(level);
    }
}
