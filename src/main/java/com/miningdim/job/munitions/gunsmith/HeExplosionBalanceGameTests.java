package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * HE 爆炸平衡 (平衡方案 C1、C3、C4) 的 GameTest。
 *
 * dev GameTest 不加载 TaCZ (build.gradle 对它只 compileOnly), 所以:
 * - C1 当量帽测 {@link HeExplosionCapHandler} 的纯函数, 入参取 TaCZ 1.1.8 默认枪包的枪数据原值
 *   (tacz_default_gun/data/tacz/data/guns/*_data.json 的 bullet.damage / bullet_amount / bullet.explosion);
 *   "缓存确实被改写、子弹确实读到改写值"与启动自检只能在装了 TaCZ 的测试端手测。
 * - C3 用替身实体充当 TaCZ 子弹、替身谓词代替 instanceof EntityKineticBullet, 直接驱动两个事件方法,
 *   再经 Forge 总线走一遍 LivingAttackEvent, 核对 HIGHEST 订阅真的接上。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class HeExplosionBalanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "he_explosion";
    private static final float EPS = 1.0e-4F;

    private HeExplosionBalanceGameTests() {
    }

    // ---- C1 配件爆炸当量帽 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void m1014HeExplosionCapsAtPerPelletDirectDamage(GameTestHelper helper) {
        // M1014: bullet.damage 40、8 颗弹丸、原生 explode=false、模板爆炸 42 → 每颗 40/8 = 5.0。
        assertRewrite(helper, "M1014", 42.0F, 40.0F, 8, 5.0F);
        // 方案 C1 下一发合计不超过整发直伤: M1014 40, M870 36, SPAS 64, M107 55, RPK 10。
        helper.assertTrue(Math.abs(expectRewrite(helper, 42.0F, 40.0F, 8).damage() * 8 - 40.0F) < EPS,
                "M1014 one shot of 8 pellets totals 40 explosion damage");
        assertRewrite(helper, "M870", 50.0F, 36.0F, 9, 4.0F);
        assertRewrite(helper, "SPAS-12", 64.0F, 64.0F, 8, 8.0F);
        assertRewrite(helper, "M107", 100.0F, 55.0F, 1, 55.0F);
        assertRewrite(helper, "RPK", 22.0F, 10.0F, 1, 10.0F);
        // 模板本身低于帽值时取模板: Taurus 500 直伤 40、模板 30; 没写 explosion 段的枪走缺省模板 2 (MP5 直伤 6)。
        assertRewrite(helper, "Taurus 500", 30.0F, 40.0F, 1, 30.0F);
        assertRewrite(helper, "MP5 default template", 2.0F, 6.0F, 1, 2.0F);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nativeExplosiveWeaponsAndPlainGunsAreLeftAlone(GameTestHelper helper) {
        // RPG7 (直伤 20、模板 120、原生 explode=true) 与 M320 (10 / 50 / true): 原生爆炸武器不改写。
        helper.assertTrue(HeExplosionCapHandler.rewrite(true, 120.0F, true, 20.0F, 1, 1.0D) == null,
                "RPG7 native explosion must keep its 120 template");
        helper.assertTrue(HeExplosionCapHandler.rewrite(true, 50.0F, true, 10.0F, 1, 1.0D) == null,
                "M320 native explosion must keep its 50 template");
        // 比例设成 0 也不碰原生爆炸武器: 0 只关配件爆炸。
        helper.assertTrue(HeExplosionCapHandler.rewrite(true, 120.0F, true, 20.0F, 1, 0.0D) == null,
                "ratio 0 must not disable RPG7");
        // 没装爆炸配件 (缓存 explode=false) 的枪不动。
        helper.assertTrue(HeExplosionCapHandler.rewrite(false, 42.0F, false, 40.0F, 8, 1.0D) == null,
                "M1014 without HE keeps its non-exploding cache");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capRatioScalesAndZeroDisablesAttachmentExplosion(GameTestHelper helper) {
        HeExplosionCapHandler.Rewrite half = HeExplosionCapHandler.rewrite(true, 42.0F, false, 40.0F, 8, 0.5D);
        helper.assertTrue(half != null && half.explode() && Math.abs(half.damage() - 2.5F) < EPS,
                "ratio 0.5 halves the M1014 per-pellet cap to 2.5, got " + half);
        // 比例 0: 关掉配件爆炸 (explode=false), 而不是留一个 0 伤的空爆炸。
        HeExplosionCapHandler.Rewrite off = HeExplosionCapHandler.rewrite(true, 42.0F, false, 40.0F, 8, 0.0D);
        helper.assertTrue(off != null && !off.explode(), "ratio 0 turns the attachment explosion off, got " + off);
        HeExplosionCapHandler.Rewrite negative = HeExplosionCapHandler.rewrite(true, 42.0F, false, 40.0F, 8, -1.0D);
        helper.assertTrue(negative != null && !negative.explode(), "negative ratio behaves as 0, got " + negative);
        // 模板是天花板: 比例调到上限 20 也只回到模板 42。
        HeExplosionCapHandler.Rewrite ceiling = HeExplosionCapHandler.rewrite(true, 42.0F, false, 40.0F, 8, 20.0D);
        helper.assertTrue(ceiling != null && ceiling.explode() && Math.abs(ceiling.damage() - 42.0F) < EPS,
                "a huge ratio never exceeds the template 42, got " + ceiling);
        // 弹丸数缺省/为 0 时按 1 颗算, 不除零。
        helper.assertTrue(Math.abs(HeExplosionCapHandler.perProjectileCap(40.0F, 0, 1.0D) - 40.0F) < EPS,
                "pellet count 0 is treated as 1");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void explosionConfigDefaultsMatchDecidedValues(GameTestHelper helper) {
        MunitionsConfig.ensureLoadedForTest();
        helper.assertTrue(MunitionsConfig.HE_EXPLOSION_PER_PROJECTILE_CAP_RATIO.getDefault() == 1.0D,
                "heExplosionPerProjectileCapRatio defaults to 1.0 (per-pellet explosion <= per-pellet direct damage)");
        helper.assertTrue(MunitionsConfig.BULLET_EXPLOSION_SCALE.getDefault() == 0.0D,
                "bulletExplosionScale defaults to 0 (bullet explosions never hurt players)");
        helper.succeed();
    }

    // ---- C3 子弹爆炸对玩家 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bulletExplosionOnPlayerIsCanceledBeforeHurt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player victim = helper.makeMockPlayer();
        Player shooter = helper.makeMockPlayer();
        Snowball bullet = new Snowball(EntityType.SNOWBALL, level);
        BulletExplosionPlayerDamageHandler handler = handlerFor(bullet, 0.0D);

        // 队友的 HE: 攻击者是另一名玩家。
        DamageSource friendly = level.damageSources().explosion(bullet, shooter);
        LivingAttackEvent attack = new LivingAttackEvent(victim, friendly, 10.0F);
        handler.onLivingAttack(attack);
        helper.assertTrue(attack.isCanceled(), "a teammate's bullet explosion is canceled at LivingAttackEvent");
        // 射手自伤: 攻击者就是受击者本人。
        LivingAttackEvent self = new LivingAttackEvent(shooter, friendly, 10.0F);
        handler.onLivingAttack(self);
        helper.assertTrue(self.isCanceled(), "the shooter's own bullet explosion is canceled");
        // LivingHurtEvent 兜底同样取消 (绕过 LivingAttackEvent 的调用路径)。
        LivingHurtEvent hurt = new LivingHurtEvent(victim, friendly, 10.0F);
        handler.onLivingHurt(hurt);
        helper.assertTrue(hurt.isCanceled(), "the LivingHurtEvent fallback also cancels at scale 0");

        // 经 Forge 总线再走一遍, 核对 @SubscribeEvent 订阅接得上。替身谓词只认本用例的替身实体, 不影响并发用例。
        MinecraftForge.EVENT_BUS.register(handler);
        try {
            boolean canceled = MinecraftForge.EVENT_BUS.post(new LivingAttackEvent(victim, friendly, 10.0F));
            helper.assertTrue(canceled, "the handler registered on the Forge bus cancels the bullet explosion");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(handler);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonZeroScaleShrinksBulletExplosionAtHurtStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player victim = helper.makeMockPlayer();
        Player shooter = helper.makeMockPlayer();
        Snowball bullet = new Snowball(EntityType.SNOWBALL, level);
        DamageSource explosion = level.damageSources().explosion(bullet, shooter);

        BulletExplosionPlayerDamageHandler half = handlerFor(bullet, 0.5D);
        LivingAttackEvent attack = new LivingAttackEvent(victim, explosion, 10.0F);
        half.onLivingAttack(attack);
        helper.assertFalse(attack.isCanceled(), "scale 0.5 lets the attack through");
        LivingHurtEvent hurt = new LivingHurtEvent(victim, explosion, 10.0F);
        half.onLivingHurt(hurt);
        helper.assertTrue(!hurt.isCanceled() && Math.abs(hurt.getAmount() - 5.0F) < EPS,
                "scale 0.5 turns 10 into 5, got " + hurt.getAmount());

        BulletExplosionPlayerDamageHandler full = handlerFor(bullet, 1.0D);
        LivingHurtEvent untouched = new LivingHurtEvent(victim, explosion, 10.0F);
        full.onLivingHurt(untouched);
        helper.assertTrue(!untouched.isCanceled() && Math.abs(untouched.getAmount() - 10.0F) < EPS,
                "scale 1 keeps the TaCZ value, got " + untouched.getAmount());
        helper.assertTrue(Math.abs(BulletExplosionPlayerDamageHandler.scaledDamage(10.0F, 2.0D) - 10.0F) < EPS,
                "scale is clamped to at most 1");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void otherDamageIsNotTouched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player victim = helper.makeMockPlayer();
        Player shooter = helper.makeMockPlayer();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            helper.fail("EntityType.ZOMBIE.create returned null");
            return;
        }
        Snowball bullet = new Snowball(EntityType.SNOWBALL, level);
        Snowball notABullet = new Snowball(EntityType.SNOWBALL, level);
        BulletExplosionPlayerDamageHandler handler = handlerFor(bullet, 0.0D);

        // PvE 不变: 玩家的子弹爆炸打精英、普通怪照常结算。
        assertUntouched(helper, handler, zombie, level.damageSources().explosion(bullet, shooter),
                "a player's bullet explosion on a mob");
        // 攻击者不是玩家 (持枪生物) 的子弹爆炸不归本类管。
        assertUntouched(helper, handler, victim, level.damageSources().explosion(bullet, zombie),
                "a mob's bullet explosion on a player");
        // 直接实体不是 TaCZ 子弹 (苦力怕、TNT、别的弹射物) 的爆炸不管。
        assertUntouched(helper, handler, victim, level.damageSources().explosion(notABullet, shooter),
                "a non-bullet explosion on a player");
        // 子弹直伤不是爆炸, 不管 (PvP 直伤另议)。
        assertUntouched(helper, handler, victim, level.damageSources().thrown(bullet, shooter),
                "a non-explosion bullet hit on a player");
        helper.succeed();
    }

    // ---- helpers ----

    private static BulletExplosionPlayerDamageHandler handlerFor(Entity bullet, double scale) {
        return new BulletExplosionPlayerDamageHandler(entity -> entity == bullet, () -> scale);
    }

    private static HeExplosionCapHandler.Rewrite expectRewrite(GameTestHelper helper, float template,
                                                               float gunDamage, int pellets) {
        HeExplosionCapHandler.Rewrite rewrite =
                HeExplosionCapHandler.rewrite(true, template, false, gunDamage, pellets, 1.0D);
        if (rewrite == null) {
            helper.fail("attachment explosion (template " + template + ") must be rewritten");
            throw new IllegalStateException("unreachable");
        }
        return rewrite;
    }

    private static void assertRewrite(GameTestHelper helper, String gun, float template, float gunDamage,
                                      int pellets, float expected) {
        HeExplosionCapHandler.Rewrite rewrite = expectRewrite(helper, template, gunDamage, pellets);
        helper.assertTrue(rewrite.explode() && Math.abs(rewrite.damage() - expected) < EPS,
                gun + " + HE per-pellet explosion should be " + expected + ", got " + rewrite);
    }

    private static void assertUntouched(GameTestHelper helper, BulletExplosionPlayerDamageHandler handler,
                                        LivingEntity target, DamageSource source,
                                        String what) {
        LivingAttackEvent attack = new LivingAttackEvent(target, source, 10.0F);
        handler.onLivingAttack(attack);
        LivingHurtEvent hurt = new LivingHurtEvent(target, source, 10.0F);
        handler.onLivingHurt(hurt);
        helper.assertTrue(!attack.isCanceled() && !hurt.isCanceled() && Math.abs(hurt.getAmount() - 10.0F) < EPS,
                what + " must pass through untouched");
    }
}
