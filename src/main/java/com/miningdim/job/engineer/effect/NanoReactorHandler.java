package com.miningdim.job.engineer.effect;

import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.NanoEffect;
import com.miningdim.job.engineer.NanoNbt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;

/**
 * 纳米末影心肺反应器 (图腾) 的拦截致死执行 (MillenniumEngineer_Mod_DesignSpec 6.2)。订阅
 * {@link LivingDeathEvent}: 拦截本将致死的一击 (非血量阈值, 防高爆发 overshoot), 复活到 % 最大血量 +
 * 短伤害免疫窗; 读写人级共享 CD (nanoReactorCdEndTick, 存 capability); 每件带此效果的甲各扣 % 最大耐久。
 *
 * 共享 CD (人级 30min): 叠穿多件图腾甲共享同一 CD, CD 内不再拦截 (玩家正常死亡)。这是防多命的核心铁律。
 * 触发后 broadcastEntityEvent((byte)35) 全屏图腾动画 + 扣耐久后 broadcastChanges 刷新耐久条 (6.4 同步坑)。
 *
 * 职业进度读写经 entry 唯一权威 capability ({@link MiningCapabilities}, 第 2.3 节并入); 不再走已删的
 * job.JobCapability。nanoReactorCdEndTick 落在 ENGINEER 的 JobProgress 上, 随 entry capability 持久化/Clone。
 */
public final class NanoReactorHandler {

    /** 原版图腾使用的实体事件 id (全屏图腾激活动画)。 */
    private static final byte TOTEM_ANIM_EVENT = 35;

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 至少一件穿戴中护甲带图腾效果才触发。
        boolean hasTotem = false;
        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty() && NanoNbt.hasEffect(armor, NanoEffect.TOTEM)) {
                hasTotem = true;
                break;
            }
        }
        if (!hasTotem) {
            return;
        }

        Optional<JobProgress> progress = MiningCapabilities.get(player).map(d -> d.jobProgress(JobId.ENGINEER));
        if (progress.isEmpty()) {
            return; // 能力未挂载 (极端): 不拦, 正常死亡。
        }
        JobProgress engineer = progress.get();

        // 人级共享 CD 跨维度: 取全服权威主世界时钟 (overworld), 而非 player.level() 的 per-ServerLevel 计时,
        // 避免某维度被卸载/暂停 tick 或自定义时钟时跨维度比较 cdEndTick 出现就绪误判 (Minor 边界隐患修复)。
        long now = player.server.overworld().getGameTime();
        if (!NanoReactor.cooldownReady(now, engineer.nanoReactorCdEndTick())) {
            return; // 共享 CD 中: 不拦, 玩家正常死亡 (叠穿仍只救一次)。
        }

        // 拦截致死: 取消死亡, 复活到 % 最大血量 + 免疫窗。
        event.setCanceled(true);
        player.setHealth(NanoReactor.reviveHealth(player.getMaxHealth()));
        player.invulnerableTime = EngineerConfig.TOTEM_INVULN_TICKS.get();
        player.clearFire();

        // 写人级共享 CD。
        engineer.setNanoReactorCdEndTick(NanoReactor.nextCdEndTick(now));

        // 每件带图腾效果的甲各扣 % 最大耐久 (耐久代价; 6.2)。
        deductDurability(player);
        player.containerMenu.broadcastChanges();

        // 全屏图腾动画 (6.4 第一批)。
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.broadcastEntityEvent(player, TOTEM_ANIM_EVENT);
        }
    }

    /** 每件带图腾效果的甲各扣 % 最大耐久 (不汇聚, 逐件; 不可破坏/无耐久件跳过)。 */
    private void deductDurability(ServerPlayer player) {
        double pct = EngineerConfig.TOTEM_DURABILITY_COST_PCT.get();
        for (ItemStack armor : player.getInventory().armor) {
            if (armor.isEmpty() || !NanoNbt.hasEffect(armor, NanoEffect.TOTEM)) {
                continue;
            }
            if (!armor.isDamageableItem() || armor.getMaxDamage() <= 0) {
                continue;
            }
            int cost = (int) Math.floor(armor.getMaxDamage() * pct);
            int newDamage = Math.min(armor.getMaxDamage(), armor.getDamageValue() + cost);
            armor.setDamageValue(newDamage);
        }
    }
}
