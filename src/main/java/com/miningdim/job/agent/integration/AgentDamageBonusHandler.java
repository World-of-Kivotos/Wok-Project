package com.miningdim.job.agent.integration;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentSkillTable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 特勤伤害加成接线 (SpecialAgent_Job_DesignSpec 第四章伤害加成支线; FF14 生产职铁律: 战斗只少量加成, 严防战力)。
 *
 * 设计哲学硬约束 (用户已定): 干员是 PVE 经济/情报职, 战斗仅少量加成且仅对精英/冠军生效 (不泛化全怪, 防战力
 * 叠叠乐)。加成按等级 +5% -> +15% ({@link AgentSkillTable#damageBonusFraction}), 是对【精英有效血】的少量百分比
 * 放大乘子, 非线性数值膨胀。只对本工程盖章精英放大, 普通怪/外来冠军不享。
 *
 * 单点放大 (EventPriority 选择): 挂 {@link EventPriority#NORMAL} 默认优先级, 在易伤系统放大之前/血池净减伤
 * (LOWEST) 之前对干员打精英的名义伤害做一次乘子放大 —— 放大后的 event.getAmount() 再走下游易伤/净减伤/血池扣血,
 * 故加成是"干员对精英的输出乘子", 与净减伤红线 (LOWEST 单点连乘) 不冲突 (本 handler 不读不改减伤, 只抬名义输出)。
 *
 * 攻击者门 (严防战力 + 修复福利泄漏 Major): 仅服务端真玩家直接伤害享加成 (召唤物/弹射物 owner 归因不享, 与战斗
 * 输出统计分离); 且攻击者须【做过特勤工作】(SavedData 入职标志 isActiveAgent) 才放大。严禁用 AGENT 等级作门 ——
 * 框架 IJobService.level 对任何玩家恒返 1 级默认, 用等级判会把对精英 +5%->+15% 伤害放大泄漏给全服每个打到精英的
 * 玩家; 入职标志确保只有真做过特勤活计 (如封印申请成功) 的玩家吃放大, 非特勤零加成。
 *
 * 探测源已改自研 {@link MiningChampions#get}, 不再触任何 top.theillusivec4.champions.*, 由 {@link AgentIntegrationBootstrap}
 * 挂 forgeBus。
 */
public final class AgentDamageBonusHandler {

    /**
     * 干员对本工程盖章精英造成伤害时, 按等级少量放大名义伤害 (+5%->+15%)。非玩家来源 / 非精英 / 非本工程精英 /
     * 攻击者非干员 均不放大。
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onAgentHurtChampion(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return; // 非真玩家直接伤害 (含召唤物/环境): 不加成。
        }
        LivingEntity victim = event.getEntity();
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程盖章精英: 不加成 (普通怪/外来冠军不享, 防战力泛化)。
        }

        // 入职标志门: 非特勤 (从没做过特勤工作) 不享伤害放大 (等级对全员默认 1 级, 不可作门; 见类注释)。
        if (!AgentBountySavedData.get(attacker.server.overworld()).isActiveAgent(attacker.getUUID())) {
            return;
        }

        int level = JobServices.jobService().level(attacker, JobId.AGENT);
        double fraction = AgentSkillTable.damageBonusFraction(level);
        if (fraction <= 0.0D) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        // 少量放大: 名义伤害 ×(1+fraction)。下游易伤/净减伤/血池扣血读放大后值, 故这是对精英的输出乘子。
        event.setAmount(amount * (float) (1.0D + fraction));
    }
}
