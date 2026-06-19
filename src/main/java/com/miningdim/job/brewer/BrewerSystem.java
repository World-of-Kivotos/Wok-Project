package com.miningdim.job.brewer;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 酿酒师子系统入口 (模块化铁律 3 自注册)。等级/经验走共享职业框架 capability (JobId.BREWER), 故须在
 * JobFrameworkSystem 之后注册 (见 MiningDim.registerSubsystems)。
 *
 * 本子系统是"至少七天周期性制造职业": 酿酒台酿基酒 (按等级 roll 品质) -> 酒窖箱陈酿年份 (干小麦门控, 年份
 * 时钟与潮汐 Tide 同读原版 level 时钟) -> 喝酒按 S = 年份×品质系数 获增益, 闪耀档触发永久 (一条命) 特殊增益。
 *
 * 地基阶段 (本提交): 落地纯逻辑层 (品质/类型枚举 + 年份时钟 + NBT 盖章), 子系统占位登记; 物品 / 酿酒台 /
 * 酒窖箱 / 喝酒效果 / 永久增益 / 配方 在后续阶段于此 register 内逐步接入 (DeferredRegister + forge 事件),
 * 零结构改动。
 */
public final class BrewerSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/brewer");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        LOGGER.info("[miningdim] brewer subsystem registered (foundation: wine quality/type + vintage clock + nbt)");
    }

    @Override
    public String name() {
        return "BrewerSystem";
    }
}
