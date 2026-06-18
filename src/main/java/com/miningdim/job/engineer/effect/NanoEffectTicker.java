package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.NanoEffect;
import com.miningdim.job.engineer.NanoNbt;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * tick 类纳米特效驱动 (MillenniumEngineer_Mod_DesignSpec 6.2 / 6.3)。订阅 {@link TickEvent.PlayerTickEvent}
 * (END phase, 服务端), 每隔 {@link EngineerConfig#EFFECT_TICK_INTERVAL} 遍历穿戴中护甲槽 (仅穿戴中参与, 6.3):
 *  - 纳米重塑: 回护甲自身耐久 (损失 > 阈值失效; 按件独立, 不汇聚);
 *  - 纳米机能修复: 回穿戴者血量 (% 最大血量; 递减安全阀 100/50/25/12.5% 防滚雪球, 耐久 < 50% 该件失效);
 *  - 纳米多重护盾: 推进再生倒计时 (到点生成一次充能, 5 次用尽); 推进当前免疫窗倒计时。
 *
 * 服务端 sendParticles 广播视觉 (6.4 第一批): 重塑青色 / 机能修复绿色治疗 / 护盾蓝色。粒子与数值解耦 (便宜)。
 */
public final class NanoEffectTicker {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || !player.isAlive()) {
            return;
        }
        // 节流: 仅每 interval tick 结算一次 (重塑/机能修复以周期建模)。护盾倒计时也按周期推进, 与回血同拍。
        int interval = EngineerConfig.EFFECT_TICK_INTERVAL.get();
        if (player.tickCount % interval != 0) {
            return;
        }

        List<ItemStack> worn = wornArmor(player);

        tickReshape(player, worn);
        tickVitality(player, worn);
        tickShield(player, worn);
    }

    /** 仅穿戴中的护甲槽 (背包/箱子内不参与 tick; 6.3)。 */
    private static List<ItemStack> wornArmor(ServerPlayer player) {
        List<ItemStack> list = new ArrayList<>(4);
        for (ItemStack piece : player.getInventory().armor) {
            if (!piece.isEmpty()) {
                list.add(piece);
            }
        }
        return list;
    }

    /** 纳米重塑: 逐件回护甲自身耐久 (按件独立, 不汇聚); 损失超阈值的件不回 (6.2)。 */
    private void tickReshape(ServerPlayer player, List<ItemStack> worn) {
        int perTick = EngineerConfig.RESHAPE_DURABILITY_PER_TICK.get();
        boolean any = false;
        for (ItemStack armor : worn) {
            if (NanoEffects.reshapeActive(armor) && armor.getDamageValue() > 0) {
                armor.setDamageValue(Math.max(0, armor.getDamageValue() - perTick));
                any = true;
            }
        }
        if (any) {
            // 重塑改了耐久, 刷新客户端耐久条 (6.4 同步坑)。
            player.containerMenu.broadcastChanges();
            spawnParticle(player, ParticleTypes.SCRAPE);
        }
    }

    /**
     * 纳米机能修复: 回穿戴者血量, 按生效件数走递减安全阀 (100/50/25/12.5%), 四件合计约 1.875 倍非 4 倍。
     * 耐久 < 50% 的件不计入生效件 (6.2)。
     */
    private void tickVitality(ServerPlayer player, List<ItemStack> worn) {
        // 收集生效件 (耐久充足 + 带特效), 按入场顺序套安全阀。
        List<ItemStack> active = new ArrayList<>(4);
        for (ItemStack armor : worn) {
            if (NanoEffects.vitalityActive(armor)) {
                active.add(armor);
            }
        }
        if (active.isEmpty()) {
            return;
        }
        if (player.getHealth() >= player.getMaxHealth()) {
            return; // 满血不回 (避免无意义粒子)。
        }
        double fraction = NanoEffects.vitalityTotalHealFraction(active.size());
        float heal = (float) (player.getMaxHealth() * fraction);
        if (heal > 0.0f) {
            player.heal(heal);
            spawnParticle(player, ParticleTypes.HEART);
        }
    }

    /**
     * 纳米多重护盾 (反应式语义; 6.2 "触发后 X 秒免疫"): ticker 只推进两个倒计时, 不主动开窗、不主动耗充能。
     *  - 免疫窗倒计时 (windowTick > 0): 递减到 0 即免疫窗结束 (开窗由 {@link NanoShieldHandler} 受击时触发);
     *  - 再生倒计时 (regenTick): 递减到 <= 0 即 "充能就绪 (armed)", 停在 0 等待受击触发消耗 (不自动开窗)。
     *
     * 旧实现到点无条件开窗 + 耗充能, 玩家不被攻击也每 60s 自动耗一次充能并白进 40 tick 免疫, 5 次充能被时钟
     * 在 5 分钟内耗尽 (与战斗无关), 且在枪战中提供可预测 60s 节律免疫窗 (attrition 风险)。改为反应式: 充能 =
     * 真正的 5 次救命资源, 仅受击时消耗。
     */
    private void tickShield(ServerPlayer player, List<ItemStack> worn) {
        int interval = EngineerConfig.EFFECT_TICK_INTERVAL.get();
        boolean shielded = false;
        for (ItemStack armor : worn) {
            if (!NanoNbt.hasEffect(armor, NanoEffect.SHIELD)) {
                continue;
            }
            // 反应式状态机单一权威在 NanoEffects: 此处只推进两个倒计时, 不开窗/不耗充能 (开窗由 hurt handler 受击触发)。
            if (NanoEffects.advanceShieldTimers(armor, interval)) {
                shielded = true;
            }
        }
        if (shielded) {
            spawnParticle(player, ParticleTypes.SOUL_FIRE_FLAME);
        }
    }

    private static void spawnParticle(ServerPlayer player, net.minecraft.core.particles.ParticleOptions particle) {
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(particle,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    4, 0.3, 0.5, 0.3, 0.01);
        }
    }
}
