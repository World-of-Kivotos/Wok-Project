package com.miningdim.combat;

import com.miningdim.config.MiningServerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 玩家基础最大生命落地 (枪匠平衡方案 F1)。全服战斗数值 (精英 %maxHP 伤害与处决、金酒 +10%/层、塔罗、护甲与枪械
 * 平衡) 都按玩家 80 血建模, 但原版玩家 generic.max_health 的基础值是 20, 此前仓库里没有任何代码把它抬上去。
 * 本 handler 把【基础值】抬到 miningdim-server.toml [player] baseMaxHealth (默认 80):
 *
 *  - 只升不降: 只在 base &lt; 配置值时 setBaseValue(配置值)。base 已更高的玩家 (生产服 KubeJS test_hp.js 给测试员设的
 *    500) 原样保留, 两边不会每 5 秒互相覆盖。
 *  - 改基础值, 不挂修饰: 金酒 GinMaxHealthManager 以 getBaseValue 为锚算 +10%/层, 跨职业额外生命全局帽也按 base 算;
 *    用修饰补 60 会让金酒满层只 +10 (20 × 50%)。判定同样只看 base, 不看含修饰的 getMaxHealth。
 *  - 时机 (HIGHEST, 先于金酒登录重挂等读 base 的监听): 登录、Clone、重生、换维, 外加每 100 tick 校正一次兜底。
 *    1.20.1 的 ServerPlayer.restoreFrom 不搬属性, 死亡重生与末地出口回主世界都会新建一个 base=20 的玩家实体,
 *    必须在 Clone 里抬回; 换维不换实体, 那里与周期校正一样只是兜底。
 *  - 当前血量: 死亡重生回满; 末地出口 (Clone 非死亡) 保留原血量 —— restoreFrom 按新实体 20 的上限把血量钳掉了,
 *    这里按旧实体的血量还原; 其余路径只把"第一次抬到这个水位"的差值加到当前血量 (20/20 -> 80/80, 15/20 -> 75/80)。
 *    已补偿水位记在 PlayerPersisted 里 (restoreFrom 会随死亡重生搬走), base 被外部改低后再抬回不重复补血,
 *    改低再抬回的循环也就刷不出回血。
 */
public final class PlayerBaseHealthHandler {

    /** 周期校正间隔 (tick; 与 deploy/kubejs/test_hp.js 的核对周期同为 5 秒)。 */
    static final int CORRECTION_INTERVAL_TICKS = 100;

    /** PlayerPersisted 下记"已补偿到的 base 水位"的键 (缺省 0 = 从未补偿)。 */
    static final String COMPENSATED_BASE_KEY = "miningdim.baseMaxHealthCompensated";

    /** 包级可见: 仅 {@link CombatSystem} 实例化一次挂 forgeBus; GameTest 同包直接调各 handler 方法。 */
    PlayerBaseHealthHandler() {
    }

    /** 当前配置的玩家基础最大生命 (实时读, 不缓存; /reload 后即时生效)。 */
    public static double configuredBaseMaxHealth() {
        return MiningServerConfig.PLAYER_BASE_MAX_HEALTH.get();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            raiseAndTopUp(player, configuredBaseMaxHealth());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        raiseBase(player, configuredBaseMaxHealth());
        if (event.isWasDeath()) {
            player.setHealth(player.getMaxHealth());
        } else {
            // 末地出口: restoreFrom 已 setHealth(旧血量), 但被新实体 base=20 的上限钳过; 按旧实体血量还原 (setHealth 自钳上限)。
            player.setHealth(event.getOriginal().getHealth());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        raiseBase(player, configuredBaseMaxHealth());
        if (!event.isEndConquered()) {
            player.setHealth(player.getMaxHealth()); // 死亡重生回满; 末地出口的血量已在 Clone 里还原, 不动。
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            raiseAndTopUp(player, configuredBaseMaxHealth());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % CORRECTION_INTERVAL_TICKS != 0) {
            return;
        }
        raiseAndTopUp(player, configuredBaseMaxHealth());
    }

    /**
     * 只升不降: base &lt; target 时把 MAX_HEALTH 的【基础值】设为 target。
     *
     * @return true = 本次抬了 base; false = base 已不低于 target (或无该属性), 未改动
     */
    static boolean raiseBase(Player player, double target) {
        AttributeInstance inst = player.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null || inst.getBaseValue() >= target) {
            return false;
        }
        inst.setBaseValue(target);
        return true;
    }

    /**
     * 抬 base, 并把"第一次抬到该水位"的差值加到当前血量: 补血量 = target - max(抬之前的 base, 已补偿水位)。
     * 首次迁移 20 -> 80 补 60; 外部改低后抬回原水位补 0; 配置调高到 100 时只补多出来的 20。已死亡 (血量 0) 不补。
     */
    static void raiseAndTopUp(Player player, double target) {
        AttributeInstance inst = player.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) {
            return;
        }
        double before = inst.getBaseValue();
        if (!raiseBase(player, target)) {
            return;
        }
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        double compensated = persisted.getDouble(COMPENSATED_BASE_KEY);
        double topUp = target - Math.max(before, compensated);
        // 停在死亡界面下线的玩家登录时血量为 0: 只记水位不补血, 否则会被原地"复活"; 真重生走 Clone/Respawn 回满。
        if (topUp > 0.0D && !player.isDeadOrDying()) {
            player.setHealth(player.getHealth() + (float) topUp); // setHealth 自钳到新上限。
        }
        persisted.putDouble(COMPENSATED_BASE_KEY, Math.max(compensated, target));
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }
}
