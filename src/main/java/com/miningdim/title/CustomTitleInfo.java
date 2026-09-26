package com.miningdim.title;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一名玩家的赞助与专属称号状态快照, 供 {@code /mtitle custom info}、{@code /mtitle sponsor info / list}
 * 与 P2 编辑卡片展示 (Title_System_DesignSpec 13.7 / 13.8)。有效与否、何时可改都由服务端按自己的时钟算好,
 * 展示方不再自行比较时间。
 *
 * @param player        玩家
 * @param sponsor       赞助资格; 从未发放或已撤销为 null
 * @param sponsorActive 资格当前是否有效
 * @param custom        专属称号记录; 未设置为 null
 * @param nextEditAt    下次可修改的时间 (毫秒); 0 表示现在即可修改 (含没有记录、冷却已过)
 */
public record CustomTitleInfo(UUID player, @Nullable SponsorStatus sponsor, boolean sponsorActive,
                              @Nullable CustomTitle custom, long nextEditAt) {

    public CustomTitleInfo {
        Objects.requireNonNull(player, "player");
    }
}
