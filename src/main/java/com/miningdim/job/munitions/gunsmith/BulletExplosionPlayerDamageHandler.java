package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * 子弹爆炸对玩家的伤害 (平衡方案 C3): TaCZ 子弹爆炸 (直接实体是 TaCZ 子弹、带原版爆炸标签、攻击者是玩家)
 * 打到玩家时, 伤害乘 bulletExplosionScale, 默认 0 即直接取消。
 *
 * 范围: "攻击者是玩家"一条同时覆盖射手自伤、队友误伤与 PvP; 攻击者不是玩家的子弹爆炸, 以及打在非玩家目标 (精英、普通怪) 上的爆炸
 * 一概不动, PvE 不变。原生爆炸武器 (RPG7、M320) 打到玩家同样适用。本类不是 PvP 伤害守门器, 不钳任何直伤。
 *
 * 结算点:
 * - 系数为 0: LivingAttackEvent (HIGHEST) 直接取消。它在受伤流程最前面 (ServerPlayer 的出生保护与 PvP 开关判完之后),
 *   取消后受击者不挂无敌帧、不播受伤动画, 插板与各减伤层也不会被这次爆炸磨损或扣电。若拖到 LivingHurtEvent 才取消,
 *   原版已先写下 20 tick 无敌帧, 队友的 HE 反而会替人挡掉随后半秒内的一部分真实伤害。
 * - 系数在 (0, 1): LivingHurtEvent (HIGHEST) 乘系数, 早于插板 (PlateArmorDamageHandler, LOW) 与职业减伤,
 *   后续各层按缩放后的值结算。LivingHurtEvent 在系数为 0 时也取消一次, 兜住绕过 LivingAttackEvent 的调用路径。
 * 爆炸击退不在受伤流程里 (TaCZ ProjectileExplosion 在 hurt 之后另算), 本类不管; HE 配件自身不开击退, 击退只看枪数据模板。
 *
 * 类加载: 本类不引用 TaCZ, "直接实体是 TaCZ 子弹"由构造时注入的谓词判定; 生产谓词在 {@link TaczBullets},
 * 只有 {@link #register} 触到它, 而 register 只在 TaCZ 已加载时由 MunitionsSystem 调用。
 * GameTest 用替身实体与替身谓词直接驱动两个事件方法。
 */
public final class BulletExplosionPlayerDamageHandler {

    private final Predicate<Entity> gunBullet;
    private final DoubleSupplier scale;

    BulletExplosionPlayerDamageHandler(Predicate<Entity> gunBullet, DoubleSupplier scale) {
        this.gunBullet = Objects.requireNonNull(gunBullet, "gunBullet");
        this.scale = Objects.requireNonNull(scale, "scale");
    }

    /** 只能在 TaCZ 已加载时调用; 系数实时读 miningdim-munitions.toml [explosion] bulletExplosionScale。 */
    public static void register(IEventBus forgeBus) {
        forgeBus.register(new BulletExplosionPlayerDamageHandler(TaczBullets::isGunBullet,
                MunitionsConfig.BULLET_EXPLOSION_SCALE::get));
    }

    /** 本类管辖的伤害: 受击者是玩家、带爆炸标签、攻击者是玩家、直接实体是枪弹。 */
    boolean governs(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof Player) || !(source.getEntity() instanceof Player)
                || !source.is(DamageTypeTags.IS_EXPLOSION)) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        return direct != null && gunBullet.test(direct);
    }

    /** 按系数缩放的伤害, 系数钳到 [0, 1]。 */
    static float scaledDamage(float amount, double scale) {
        return (float) (amount * Mth.clamp(scale, 0.0D, 1.0D));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingAttack(LivingAttackEvent event) {
        if (governs(event.getEntity(), event.getSource()) && scale.getAsDouble() <= 0.0D) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingHurt(LivingHurtEvent event) {
        if (!governs(event.getEntity(), event.getSource())) {
            return;
        }
        double current = scale.getAsDouble();
        if (current <= 0.0D) {
            event.setCanceled(true);
        } else if (current < 1.0D) {
            event.setAmount(scaledDamage(event.getAmount(), current));
        }
    }

    /** TaCZ 子弹判定; 只经 {@link #register} 触达。 */
    private static final class TaczBullets {

        private TaczBullets() {
        }

        private static boolean isGunBullet(Entity entity) {
            return entity instanceof EntityKineticBullet;
        }
    }
}
