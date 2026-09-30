package com.miningdim.district.flan;

import com.miningdim.district.core.PlotArea;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 对一块领地的一次写入 (设计文档 20.4 按差异写)。{@link ClaimDiff} 比较期望与现状得到一串操作, 按 {@link #phase()}
 * "先收后放"排好序再执行:
 * <ol start="0">
 *   <li>改范围 (地块);</li>
 *   <li>删多余的组、移出成员、清空假玩家与药水、改名;</li>
 *   <li>写假与 UNSET;</li>
 *   <li>写真;</li>
 *   <li>加入成员 (或换组)。</li>
 * </ol>
 * 中途失败时, 领地多半停在更严的一侧; 下一次推送或对账会补齐。
 */
public sealed interface FlanOp {

    /** 执行顺序 (0 最先)。 */
    int phase();

    /** 写一次。 */
    FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim);

    /** 日志里的一行, 例如 "p_abydos-01_friend flan:break false -> true"。 */
    String describe();

    /** 改地块范围。 */
    record Resize(PlotArea from, PlotArea to) implements FlanOp {
        public int phase() {
            return 0;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.resizePlotClaim(claim, to);
        }

        public String describe() {
            return "bounds " + text(from) + " -> " + text(to);
        }
    }

    /** 改名。 */
    record SetName(String from, String to) implements FlanOp {
        public int phase() {
            return 1;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.setClaimName(claim, to);
        }

        public String describe() {
            return "name '" + from + "' -> '" + to + "'";
        }
    }

    /** 删掉一个不该有的组 (连同它的成员)。 */
    record DeleteGroup(String group, int memberCount) implements FlanOp {
        public int phase() {
            return 1;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.deleteGroup(claim, group);
        }

        public String describe() {
            return "group " + group + " deleted (" + memberCount + " member(s))";
        }
    }

    /** 移出一个不该在的成员。 */
    record RemoveMember(UUID member, String from) implements FlanOp {
        public int phase() {
            return 1;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.setMember(claim, member, null);
        }

        public String describe() {
            return "member " + member + " " + from + " -> -";
        }
    }

    /** 清空假玩家白名单、药水与六张放行清单。 */
    record ClearExtras(int fakePlayers, int potions, int allowListEntries) implements FlanOp {
        public int phase() {
            return 1;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.clearExtras(claim);
        }

        public String describe() {
            return "cleared " + fakePlayers + " fake player(s), " + potions + " potion(s) and " + allowListEntries
                    + " allow-list entr" + (allowListEntries == 1 ? "y" : "ies");
        }
    }

    /** 把 2D 父领地的底补到世界底 (Flan 的 extendDownwards; 只在对账里出现, 不进 ClaimDiff)。 */
    record ExtendToBottom(int fromY, int toY) implements FlanOp {
        public int phase() {
            return 0;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.extendDistrictClaimToBottom(claim);
        }

        public String describe() {
            return "bottom y " + fromY + " -> " + toY;
        }
    }

    /** 写一格组权限。 */
    record SetGroupPerm(String group, String permission, @Nullable Boolean from, PermValue to) implements FlanOp {
        public int phase() {
            return to == PermValue.TRUE ? 3 : 2;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.setGroupPermission(claim, group, permission, to);
        }

        public String describe() {
            return group + " " + permission + " " + valueText(from) + " -> " + to.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 写一格默认 (全局) 权限。 */
    record SetDefaultPerm(String permission, @Nullable Boolean from, PermValue to) implements FlanOp {
        public int phase() {
            return to == PermValue.TRUE ? 3 : 2;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.setDefaultPermission(claim, permission, to);
        }

        public String describe() {
            return "default " + permission + " " + valueText(from) + " -> "
                    + to.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 放进一个组 (或换组)。 */
    record SetMember(UUID member, @Nullable String from, String to) implements FlanOp {
        public int phase() {
            return 4;
        }

        public FlanResult<Void> apply(FlanGateway gateway, ClaimHandle claim) {
            return gateway.setMember(claim, member, to);
        }

        public String describe() {
            return "member " + member + " " + (from == null ? "-" : from) + " -> " + to;
        }
    }

    private static String valueText(@Nullable Boolean value) {
        return value == null ? "unset" : value.toString();
    }

    private static String text(PlotArea area) {
        return "(" + area.minX() + ", " + area.minZ() + ") ~ (" + area.maxX() + ", " + area.maxZ() + ")";
    }
}
