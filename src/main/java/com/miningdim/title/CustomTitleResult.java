package com.miningdim.title;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 专属称号预览 / 设置的结果 (Title_System_DesignSpec 13.3 / 13.4)。
 *
 * @param status     结果
 * @param violations {@link Status#INVALID} 时的全部不合格项, 其余情况为空
 * @param style      {@link Status#VALID} / {@link Status#APPLIED} 时为校验后的外观, 其余情况为 null
 * @param nextEditAt 下次可修改的时间 (毫秒); 0 表示现在即可修改。{@link Status#ON_COOLDOWN} 时为冷却结束时间;
 *                   {@link Status#APPLIED} 时为玩家此后下次可修改的时间 (玩家自己修改即新一轮冷却的结束时间,
 *                   管理员代设置不动冷却, 为玩家原有的冷却状态); {@link Status#VALID} 时为当前冷却状态
 */
public record CustomTitleResult(Status status, List<CustomTitleViolation> violations,
                                @Nullable CustomTitleStyle style, long nextEditAt) {

    public CustomTitleResult {
        Objects.requireNonNull(status, "status");
        violations = List.copyOf(violations);
    }

    static CustomTitleResult rejected(Status status) {
        return new CustomTitleResult(status, List.of(), null, 0L);
    }

    static CustomTitleResult onCooldown(long nextEditAt) {
        return new CustomTitleResult(Status.ON_COOLDOWN, List.of(), null, nextEditAt);
    }

    static CustomTitleResult invalid(List<CustomTitleViolation> violations) {
        return new CustomTitleResult(Status.INVALID, violations, null, 0L);
    }

    static CustomTitleResult accepted(Status status, CustomTitleStyle style, long nextEditAt) {
        return new CustomTitleResult(status, List.of(), style, nextEditAt);
    }

    public enum Status {
        /** 已保存并立即生效 (正在佩戴时三处显示已刷新)。 */
        APPLIED,
        /** 预览: 校验通过, 未写库, 不消耗冷却。 */
        VALID,
        /** 校验不通过, 见 violations; 未写库, 不消耗冷却。 */
        INVALID,
        /** 没有有效的赞助资格。 */
        NOT_SPONSOR,
        /** 已被管理员锁定, 玩家不能修改。 */
        LOCKED,
        /** 距上次成功修改未满冷却期, 见 nextEditAt。 */
        ON_COOLDOWN
    }
}
