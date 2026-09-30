package com.miningdim.district.guard;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.core.PlotArea;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 个人圈地限制的判定 (设计文档 22.20, 关 P32): 自管区内和外围 {@link DistrictLimits#BUFFER_BLOCKS} 格内, 玩家不能新圈、
 * 也不能扩进个人 Flan 领地。只用 Minecraft 类型与整数, 不引用 Flan: 两个 mixin (F1 {@code createClaim}、F2
 * {@code resizeClaim}) 经 {@code flan.real.FlanClaimGuard} 转调这里, 不装 Flan 的 GameTest 也能直接调。
 *
 * <ul>
 *   <li>禁圈区就是 22.2 的禁放区: 按<b>库里</b>的范围 (空间索引快照), 全高, 只在该区的维度。</li>
 *   <li>新圈: 候选领地的 X/Z 框碰到任何一个禁圈区就拒 (3D 领地同样只看 X/Z)。</li>
 *   <li>改范围: 不许新占禁圈区里的列 ({@link DistrictZoneSnapshot#newBanColumns}); 管理员领地不管。在这之前
 *       {@code flan.real.FlanClaimGuard} 先挡金锄头的陈旧编辑 (22.22: 编辑状态与登记不一致), 对谁都拒, 提示
 *       {@link #KEY_STALE_EDIT}。</li>
 *   <li>OP 例外与机械动力禁令同一口径 ({@link GuardActors#isExemptOp}: 2 级、不是假玩家); 碰到禁圈区时放行, 另发一条
 *       金色提示。</li>
 *   <li>门面 OFF (功能关着、GameTest 默认)、急停开关关着、没有区域: 一律放行。</li>
 * </ul>
 * 出错一律放行 (P42), 由调用方接住异常后调 {@link #failOpen}: 记进放行次数 ({@code /district status}), 每小时至多一条
 * ERROR。
 */
public final class PersonalClaimGuard {

    public static final String KEY_CREATE_DENIED = "district.miningdim.guard.claim_create_denied";
    public static final String KEY_RESIZE_DENIED = "district.miningdim.guard.claim_resize_denied";
    public static final String KEY_OP_EXEMPT = "district.miningdim.guard.claim_op_exempt";
    public static final String KEY_INCOMPLETE_OP = "district.miningdim.guard.claim_incomplete_op";
    /** 金锄头的陈旧编辑被拒 (22.22)。没有参数。 */
    public static final String KEY_STALE_EDIT = "district.miningdim.guard.claim_stale_edit";

    /** 运行时出错、按放行处理的次数 (开服以来)。 */
    private static final AtomicLong FAIL_OPENS = new AtomicLong();

    private PersonalClaimGuard() {
    }

    /** 判定的结论。 */
    public enum Outcome {
        /** 不碰任何禁圈区, 或限制没生效, 或管理员领地: 放行, 不提示。 */
        ALLOW,
        /** 拒绝: 红字提示, 画出禁圈区。 */
        DENY,
        /** 碰到了禁圈区, 但动手的是 OP: 放行, 金色提示。 */
        OP_EXEMPT
    }

    /** 一次判定: 结论与碰到的 (第一个) 禁圈区。 */
    public record Decision(Outcome outcome, @Nullable DistrictZoneSnapshot.BanHit hit) {

        public static final Decision ALLOW = new Decision(Outcome.ALLOW, null);

        public Decision {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome != Outcome.ALLOW) {
                Objects.requireNonNull(hit, "hit");
            }
        }

        public boolean denied() {
            return outcome == Outcome.DENY;
        }
    }

    // ================================================================
    // 判定
    // ================================================================

    /**
     * 新圈 (F1): 候选领地的 X/Z 框 (两角任意顺序) 碰到禁圈区就拒, OP 例外。
     *
     * @param actor 动手的人 (F1 里就是将来的主人)
     */
    public static Decision decideCreate(GuardView view, Level level, @Nullable Player actor, PlotArea box) {
        if (!view.personalClaimsActive() || level.isClientSide) {
            return Decision.ALLOW;
        }
        DistrictZoneSnapshot.BanHit hit = view.zones().banHit(level, box.minX(), box.minZ(), box.maxX(), box.maxZ());
        return verdict(actor, hit);
    }

    /**
     * 改范围 (F2): 旧框 old 改成新框 next 时不许新占禁圈区里的列, OP 例外; 管理员领地 (含自管区父领地) 不管。
     *
     * @param actor      动手的人 (不一定是主人: 有 EDITCLAIM 的组员, 或开着 bypass 的 OP); 例外按动手的人算
     * @param adminClaim 被改的领地是不是管理员领地
     */
    public static Decision decideResize(GuardView view, Level level, @Nullable Player actor, boolean adminClaim,
                                        PlotArea old, PlotArea next) {
        if (adminClaim || !view.personalClaimsActive() || level.isClientSide) {
            return Decision.ALLOW;
        }
        return verdict(actor, view.zones().newBanColumns(level, old, next));
    }

    private static Decision verdict(@Nullable Player actor, @Nullable DistrictZoneSnapshot.BanHit hit) {
        if (hit == null) {
            return Decision.ALLOW;
        }
        return GuardActors.isExemptOp(actor) ? new Decision(Outcome.OP_EXEMPT, hit) : new Decision(Outcome.DENY, hit);
    }

    /**
     * Flan 1.11.16 的 {@code ClaimStorage.resizeClaim} 算新框的那几行 (字节码偏移 0–121) 的照抄: 对角取
     * {@code (minX == from.x ? maxX : minX, minZ == from.z ? maxZ : minZ)}, 新框是对角与 to 两点张成的 X/Z 框。
     * {@code /flan expand} 也是先按朝向取一个角当 from、外推出 to, 同一个公式。真 Flan 的用例拿 Flan 改完之后的范围核对它。
     */
    public static PlotArea resizedBox(int oldMinX, int oldMinZ, int oldMaxX, int oldMaxZ, int fromX, int fromZ,
                                      int toX, int toZ) {
        int oppositeX = oldMinX == fromX ? oldMaxX : oldMinX;
        int oppositeZ = oldMinZ == fromZ ? oldMaxZ : oldMinZ;
        return new PlotArea(Math.min(oppositeX, toX), Math.min(oppositeZ, toZ), Math.max(oppositeX, toX),
                Math.max(oppositeZ, toZ));
    }

    // ================================================================
    // 提示
    // ================================================================

    /**
     * 这次判定给动手的人看的那一行 (聊天栏, 不是动作栏: Flan 自己的圈地提示也在聊天栏): 拒绝是红字
     * ({@link #KEY_CREATE_DENIED} / {@link #KEY_RESIZE_DENIED}), OP 例外是金字 ({@link #KEY_OP_EXEMPT}); 放行为 null。
     */
    @Nullable
    public static Component message(Decision decision, boolean resize) {
        DistrictZoneSnapshot.BanHit hit = decision.hit();
        if (hit == null) {
            return null;
        }
        return switch (decision.outcome()) {
            case ALLOW -> null;
            case DENY -> Component.translatable(resize ? KEY_RESIZE_DENIED : KEY_CREATE_DENIED, hit.districtName(),
                    DistrictLimits.BUFFER_BLOCKS).withStyle(ChatFormatting.RED);
            case OP_EXEMPT -> Component.translatable(KEY_OP_EXEMPT, hit.districtName(), DistrictLimits.BUFFER_BLOCKS,
                    hit.districtId()).withStyle(ChatFormatting.GOLD);
        };
    }

    /** 发给动手的人 (假玩家不发; 两下点击才触发一次, 不限频)。 */
    public static void tell(@Nullable Player actor, Decision decision, boolean resize) {
        if (actor == null || actor instanceof FakePlayer) {
            return;
        }
        Component message = message(decision, resize);
        if (message != null) {
            actor.displayClientMessage(message, false);
        }
    }

    /**
     * 金锄头的陈旧编辑被拒 (22.22, 判定在 {@code flan.real.FlanClaimGuard}, 要读 Flan 的存储): 红字请动手的人重新点一下领地的
     * 角 (假玩家不发)。
     */
    public static void tellStaleEdit(@Nullable Player actor) {
        if (actor == null || actor instanceof FakePlayer) {
            return;
        }
        actor.displayClientMessage(Component.translatable(KEY_STALE_EDIT).withStyle(ChatFormatting.RED), false);
    }

    // ================================================================
    // 出错放行 (P42)
    // ================================================================

    /** 判定或提示出错: 放行, 计数, 每小时至多一条 ERROR (与别的守卫同一个节流)。 */
    public static void failOpen(String where, Throwable failure) {
        FAIL_OPENS.incrementAndGet();
        DistrictWorldGuards.failOpen("personal claim " + where, failure);
    }

    /** 开服以来出错放行的次数 ({@code /district status})。 */
    public static long failOpenCount() {
        return FAIL_OPENS.get();
    }

    /** 本节的语言键 (语言文件的核对用)。 */
    public static Set<String> keys() {
        return Set.of(KEY_CREATE_DENIED, KEY_RESIZE_DENIED, KEY_OP_EXEMPT, KEY_INCOMPLETE_OP, KEY_STALE_EDIT);
    }
}
