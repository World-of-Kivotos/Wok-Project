package com.miningdim.job.agent.panel;

import com.miningdim.job.agent.SealCategory;

/**
 * 战术扫描面板单条词条快照 (SpecialAgent_Job_DesignSpec 五章战术扫描面板)。一行 = 目标精英的一条词条经分级解密
 * 后的对客户端可见态: 词条注册名 + 显示名 lang key + 封印类别 + 三个布尔门 (是否已解密 / 是否可封 / 是否封印中)。
 *
 * 纯数据值对象 (record), 不触 Champions/实体: 真词条到 (affixId/displayKey/category) 的读取落在集成层
 * (compileOnly, ModList 守卫); 本 record 只承载推送给客户端面板的快照字段, 服务端构建 + 网络编解码 + 客户端渲染
 * 三处同口径。
 *
 * 解密语义 (五章: 只给原始数据不给弱点结论): {@code decrypted=false} 的条目客户端只显示 "加密/未解析" 占位且不可点;
 * {@code decrypted=true} 才显示真名 (displayKey) 并据 {@code sealable} 决定能否点击发封印请求。{@code sealed=true}
 * 表示该词条当前已被某干员封印中 (面板标注, 不可重复点)。
 *
 * @param affixId    词条全限定注册名 (namespace:path; 与 {@link com.miningdim.job.agent.SealRegistry} 账本键 +
 *                   {@code AgentSealHandler.requestSeal} 的 affixId 同口径; 客户端回点封印请求按此回传)
 * @param displayKey 词条显示名 lang key (集成层提供; 客户端 Component.translatable 渲染; 未解密条目为占位空串)
 * @param category   封印类别 (被动/机制; 决定窗口门控)。未解密条目类别对客户端隐藏, 仍随快照传以便面板可在解密后
 *                   即时着色, 但 {@code decrypted=false} 时客户端不得据此泄漏 (面板侧只在 decrypted 为真时显示类别)
 * @param decrypted  本条目在当前干员等级下是否已解密 (由 {@link AgentScanTier#visibleAffixCount} 词条数 + 类别门
 *                   逐条裁决; false = 加密占位)
 * @param sealable   本条目在当前干员等级 + 目标星级下是否可封 (经 {@code SealPlan} 三门; false = 不可封, 点击无效)
 * @param sealed     本条目当前是否已被封印中 (经 {@link com.miningdim.job.agent.SealRegistry} 活跃账本; true = 面板
 *                   标注封印中, 不可重复点)
 */
public record AgentScanEntry(
        String affixId,
        String displayKey,
        SealCategory category,
        boolean decrypted,
        boolean sealable,
        boolean sealed) {

    public AgentScanEntry {
        if (affixId == null) {
            throw new IllegalArgumentException("affixId must not be null");
        }
        if (displayKey == null) {
            throw new IllegalArgumentException("displayKey must not be null (use empty string for encrypted entry)");
        }
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
    }
}
