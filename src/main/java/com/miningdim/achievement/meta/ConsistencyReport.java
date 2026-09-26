package com.miningdim.achievement.meta;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 一次一致性校验 (Achievement_System_DesignSpec 4.1) 的结果。
 *
 * @param checked                本次核对的本模块进度数
 * @param problems               不一致之处, 每条一句, 按进度 id 排序; 为空即一致
 * @param metaWithoutAdvancement 有元数据、但服务端没有加载对应进度的 id。加载条件不满足 (如没装 TaCZ) 的进度本来就
 *                               不存在, 这不算错误, 只作为信息给出
 */
public record ConsistencyReport(int checked, List<String> problems, List<ResourceLocation> metaWithoutAdvancement) {

    public ConsistencyReport {
        problems = List.copyOf(problems);
        metaWithoutAdvancement = List.copyOf(metaWithoutAdvancement);
    }

    public boolean isClean() {
        return problems.isEmpty();
    }
}
