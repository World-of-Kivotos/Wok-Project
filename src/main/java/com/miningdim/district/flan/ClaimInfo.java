package com.miningdim.district.flan;

import com.miningdim.district.core.PlotArea;

import java.util.Objects;

/**
 * 一块领地的几何与杂项 (设计文档 20.1 inspectClaim): 对账用它发现"不再是管理员领地""范围被金锄头改过""不是全高"
 * "有人加了假玩家白名单、药水或放行清单"。
 *
 * @param minY            领地的底 (Flan 的 getDimensions().minY(), 已按 defaultClaimDepth 等配置折算)
 * @param worldMinY       所在世界的底 (level.getMinBuildHeight()); flat 且 minY 不高于它才是全高
 * @param flat            2D 领地 (Flan 的 !is3d())
 * @param adminClaim      管理员领地 (owner 为 null); 地块是管理员领地的子领地, 同样为真
 * @param name            Flan 里存着的名字 (getClaimName() 的结果; 本模块写的名字不含 %, 原样返回)
 * @param fakePlayers     假玩家白名单的条数
 * @param potions         药水效果的条数
 * @param allowListEntries 六张放行清单 (allowedItems、allowedUseBlocks、allowedPlaceBlocks、allowedBreakBlocks、
 *                        allowedEntityAttack、allowedEntityUse) 的条目合计。Flan 在顶层领地上先查它们、命中即放行,
 *                        根本不走组与默认 (20.3), 所以一条都不许留
 */
public record ClaimInfo(ClaimHandle handle, int minX, int minZ, int maxX, int maxZ, int minY, int worldMinY,
                        boolean flat, boolean adminClaim, String name, int fakePlayers, int potions,
                        int allowListEntries) {

    public ClaimInfo {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(name, "name");
    }

    /** 水平范围。 */
    public PlotArea area() {
        return new PlotArea(minX, minZ, maxX, maxZ);
    }

    /**
     * 2D 且底不高于世界底: 自管区与地块都应当如此。Flan 在 defaultClaimDepth = -1 时把 2D 领地的底折算成世界底再往下
     * 10 格, 所以比的是"不高于"而不是"相等"。
     */
    public boolean fullHeight() {
        return flat && minY <= worldMinY;
    }

    /** 2D 但底高于世界底: 可以用 Flan 自己的 extendDownwards 往下补到世界底 (3D 领地补不了)。 */
    public boolean shallow() {
        return flat && minY > worldMinY;
    }

    /** 有假玩家白名单、药水或放行清单 (对账要清空)。 */
    public boolean hasExtras() {
        return fakePlayers > 0 || potions > 0 || allowListEntries > 0;
    }
}
