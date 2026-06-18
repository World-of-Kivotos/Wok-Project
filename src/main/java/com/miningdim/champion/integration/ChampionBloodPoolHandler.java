package com.miningdim.champion.integration;

import com.miningdim.champion.ChampionRedlines;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.MiningConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 6★+ 冠军自定义血池受击接线 (Champions 集成层; ChampionStarAffix spec 6.2 血池 + 红线 1 净减伤单点 + 9A.2 铁律)。
 *
 * 单点净减伤铁律 (9A.2): 严禁用 Champions 逐词条 onHurt 串行减伤 (多源相乘穿透 49%)。所有减伤源
 * (子弹抗性 + 复合 ramp + 偏斜 EV + 刚毅折算 + 缩小化体型折算) 在本 {@link LivingHurtEvent} 单点收集 rates 后
 * 经 {@link ChampionRedlines#clampNetKeepFactor} 一次性连乘钳制 (keep = max(∏(1-rᵢ), 0.51))。本任务交付血池
 * 拦死 + 渲染镜像 + 净减伤单点骨架; 各词条减伤源 rates 的逐源采集在 b 阶段按词条数值层接入 (此处先以血池为权威
 * 扣净伤 + 拦死, rates 数组当前为空连乘 = keep 1.0, 待词条减伤接入后填入)。
 *
 * 事件优先级 (EventPriority.LOWEST): 易伤放大由全局 {@code VulnerabilityHurtHandler} 在默认优先级先乘
 * (撕裂词条经易伤系统放大对冠军的伤害), 本血池减伤聚合须在其后读已放大的 {@code event.getAmount()} 再做净减伤,
 * 故挂 LOWEST 保证语义次序 (放大 -> 净减伤 -> 扣影子血)。
 *
 * 血池权威 (6.2 #2): 6★+ 冠军 vanilla getHealth/max_health 仅渲染镜像, 一切判定读影子血池。本 handler 把
 * event 的伤害从影子血池扣 (经净减伤), 并把 vanilla 这次伤害取消 (setCanceled) —— vanilla 不再扣自己那点
 * ≤1024 的血, 避免双重扣血/原版提前判死。拦死 (6.2 #3): 影子血池 wouldDieFrom 致死则主动 entity.kill();
 * 否则取消 vanilla 伤害, 影子血扣完由本 handler 权威推进。渲染镜像 (6.2 #2): 受击分支即时刷 + ServerTickEvent
 * 每 tick 对在册血池实体统一按 {@link BloodPool#displayHealth()} 刷 (含回血同步与绕过本 handler 的伤害路径
 * 兜底), 保血条不滞后/不诈活。
 *
 * compileOnly 隔离: 本类只 import 本工程纯逻辑 + Forge/原版事件, 不 import top.theillusivec4.champions.* ——
 * 冠军判定经 {@link BloodPoolRegistry} (其成员由 ChampionPromoter 在 spawn 期经 IChampion 建池写入, 即"经
 * IChampion 守卫"的结果)。但本类仍属 integration 包 (仅 Champions 加载时由 ChampionSystem 挂 forgeBus), 因其
 * 与升格/IChampion 路径同生命周期, 集中隔离便于守卫。
 */
public final class ChampionBloodPoolHandler {

    /**
     * 6★+ 冠军受击: 经净减伤后从影子血池扣血, 拦死, 取消 vanilla 本次伤害 (影子血池为权威)。
     * 非血池冠军 (1-5★ 或普通怪) 不在册, 直接放行走 vanilla。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        BloodPool pool = BloodPoolRegistry.get(victim.getUUID());
        if (pool == null) {
            return; // 非 6★+ 血池冠军, vanilla 自理。
        }

        float incoming = event.getAmount();
        if (incoming <= 0.0F) {
            return;
        }

        // 净减伤单点连乘钳制 (红线 1): 当前减伤源 rates 为空 (词条减伤接入待 b 阶段), keep=1.0; 接入后此处
        // 收集 bullet_resistance/复合ramp/偏斜EV/刚毅/缩小化 各 rate 一次性连乘, max(∏(1-rᵢ),0.51) 钳死 49%。
        double keep = ChampionRedlines.clampNetKeepFactor();
        double netDamage = incoming * keep;

        if (pool.wouldDieFrom(netDamage)) {
            // 影子血池致死 (6.2 #3 拦死单一判据): 扣到 0, 取消本次 vanilla 伤害, 先摘池再 kill。
            pool.applyDamage(netDamage);
            event.setCanceled(true);

            // 先落账再致死 (缺陷1 修复): kill() 内部走 hurt(OUT_OF_WORLD, MAX_VALUE) 会同步重入 LivingHurtEvent
            // (归因 OUT_OF_WORLD 不记) 紧接 LivingDeathEvent -> RewardHandler.onChampionDeath 在嵌套里 drain 清账。
            // 若不在 kill 前补记, 这笔致命单击 (常是最高伤) 要等嵌套返回后才被 RewardHandler.onChampionHurt 记入,
            // 此时账本已 drain -> 既漏算瓜分权重又泄漏到 ServerStopping。故在 kill 前用与 RewardHandler 同一归因
            // (resolvePlayerAttacker 复用, 不另造) 把这笔致命单击按"有效伤害"口径 (event.getAmount(), 与受击累计
            // 一致用名义入伤而非净伤) 落账, 使嵌套 drain 时最后一击已在账内。归因为 null (环境/召唤物致死) 时不记。
            ServerPlayer killer = ChampionRewardHandler.resolvePlayerAttacker(event);
            if (killer != null) {
                long nowTick = victim.level().getGameTime();
                ContributionTracker.record(victim.getUUID(), killer.getUUID(), incoming, nowTick);
            }

            // 摘池关键 (防 kill 重入): 若池仍在册则重入受击 wouldDieFrom 再判致死 -> 再取消 -> 再 kill 形成递归。
            // 先 remove 让重入的受击找不到池而放行到 vanilla, 由 vanilla 的 MAX_VALUE 伤害走真死路径。
            BloodPoolRegistry.remove(victim.getUUID());
            victim.setHealth(0.0F);
            victim.kill();
            return;
        }

        // 未致死: 影子血池扣净伤, 取消 vanilla 本次伤害 (影子血权威, vanilla 不重复扣其 ≤1024 血)。
        pool.applyDamage(netDamage);
        event.setCanceled(true);
        // 渲染镜像即时同步 (6.2 #2): vanilla 血条按影子血占比映射到 [0,1024], 保血条不因取消伤害而卡满。
        mirrorToVanilla(victim, pool);
    }

    /**
     * 冠军死亡: 回收影子血池 (无论真死路径如何, 死亡即清池防泄漏)。奖励结算由
     * {@link ChampionRewardHandler#onChampionDeath} 在另一 handler 处理 (职责分离: 本 handler 只管血池生命周期)。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        BloodPoolRegistry.remove(event.getEntity().getUUID());
    }

    /**
     * 把影子血池映射回 vanilla getHealth 供原版血条渲染 (缺陷2 修复 a): 单一来源 = {@link BloodPool#displayHealth()}
     * (已 clamp 到 [0,1024], 与影子血池占比一致), 不再用 fraction()×vanillaMax —— 后者在某伤害路径绕过本 handler
     * 时会把破 1024 的有效血压回 vanilla 顶高血条致"诈活/低血诈活"。displayHealth>0 (池未死) 才写 vanilla 血,
     * 池已死 (displayHealth==0) 由致死分支 kill() 路径处理, 此处不强写 0 避免与 kill 打架。
     */
    private static void mirrorToVanilla(LivingEntity victim, BloodPool pool) {
        float mirrored = pool.displayHealth();
        if (mirrored > 0.0F) {
            victim.setHealth(mirrored);
        }
    }

    /**
     * 每 tick 对在册血池实体统一刷 vanilla 血条 (缺陷2 修复 b): 受击瞬刷只覆盖"被本 handler 处理的受击", 而回血
     * tick ({@link BloodPool#heal}) 与"绕过本 handler 的伤害路径"(直接 setHealth/秒杀效果/更高优先级 mod 先
     * setCanceled 截走) 都不会触发受击镜像, 导致血条滞后或顶高 (诈活)。故移到 ServerTickEvent END 每 tick 对
     * {@link BloodPoolRegistry} 在册实体 (通常极少) 统一按 displayHealth 刷一遍, 含回血同步。受击分支保留即时刷
     * (低血阈值等当 tick 即时反馈)。
     *
     * 性能: 只遍在册血池快照 (6★+ 冠军, 数量极少), 非全世界实体扫描。compileOnly 隔离: 本类不 import 任何
     * Champions 类, 仅由 ChampionIntegrationBootstrap 在 ModList.isLoaded("champions") 守卫下挂上本 handler,
     * 故 dev (Champions 未加载) 本 tick 永不注册。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Map<UUID, BloodPool> pools = BloodPoolRegistry.snapshot();
        if (pools.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return; // 维度未加载 (启动早期/配置异常): 本 tick 跳过镜像, 不刷屏。
        }
        for (Map.Entry<UUID, BloodPool> entry : pools.entrySet()) {
            Entity entity = mining.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living && living.isAlive()) {
                mirrorToVanilla(living, entry.getValue());
            }
        }
    }
}
