package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;

/**
 * 枪匠属性缓存守卫 (V04) 与连发末轮耐久 (V20)。
 *
 * dev GameTest 不加载 TaCZ, 本类不 import 任何 com.tacz.* 类型: 缓存对象用普通 Object 代替 (守卫只按对象身份认它),
 * 枪用铁锄装配出的枪匠 NBT 代替, TaCZ 的枪 NBT 键直接写字面量。TaCZ 开火链本身 (GunShootEvent 触发重建、
 * GunFireEvent 逐轮结算) 在真服手测。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithStatsCacheGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_stats_cache";

    private GunsmithStatsCacheGameTests() {
    }

    // ============================================================
    // V04: 同图纸两把枪在同一快捷栏槽原地互换, TaCZ 不发 draw, 射手缓存停在上一把枪上。
    // 开火前守卫必须认出"缓存不是这把枪构建的"并要求重建。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serverGuardRebuildsWhenSameBlueprintGunIsSwappedIntoTheSlot(GameTestHelper helper) {
        ItemStack legendary = m4WithAllParts(GunsmithPartQuality.LEGENDARY);
        ItemStack common = m4WithAllParts(GunsmithPartQuality.COMMON);
        GunsmithGunStats legendaryStats = GunsmithGunStats.from(legendary);
        GunsmithGunStats commonStats = GunsmithGunStats.from(common);
        helper.assertTrue(legendaryStats != null && commonStats != null, "两把 M4 都必须是合法枪匠枪");
        // 前提: 同图纸 -> 同 gunId, TaCZ 的 isSame 认为是同一把枪; 但枪匠属性确实不同, 借错缓存就是白嫖传奇性能。
        helper.assertTrue(legendaryStats.gunId().equals(commonStats.gunId()),
                "同图纸的两把枪必须共用同一个 gunId, 这正是 TaCZ 不发 draw 的原因");
        helper.assertTrue(legendaryStats.damage() > commonStats.damage(),
                "传奇件 M4 的伤害系数必须高于普通件, 否则本用例测不出嫁接");

        Object drawnCache = new Object();
        GunsmithTaczCacheGuard.recordBuild(drawnCache, legendary);
        helper.assertFalse(GunsmithTaczCacheGuard.needsRebuild(drawnCache, legendary, false),
                "同一把枪连续开火不得重建缓存");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(drawnCache, common, false),
                "槽里换成另一把同图纸枪后, 开火前必须重建缓存");

        // 重建后 TaCZ 用普通件那把抛 AttachmentPropertyEvent, 射手换上新的缓存对象。
        Object rebuiltCache = new Object();
        GunsmithTaczCacheGuard.recordBuild(rebuiltCache, common);
        helper.assertFalse(GunsmithTaczCacheGuard.needsRebuild(rebuiltCache, common, false),
                "重建过的缓存对当前这把枪必须直接放行");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(rebuiltCache, legendary, false),
                "再换回传奇枪同样要重建, 不能沿用普通件的缓存");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serverGuardOnlyTrustsTheExactStackObject(GameTestHelper helper) {
        ItemStack gun = m4WithAllParts(GunsmithPartQuality.MILSPEC);
        Object cache = new Object();
        GunsmithTaczCacheGuard.recordBuild(cache, gun);

        // 服务端: 内容一模一样的另一个对象也必须重建 —— 对象变了就是真的换过枪, 不按内容猜。
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(cache, gun.copy(), false),
                "服务端只认构建缓存的那个 ItemStack 对象, 内容相同的副本也要重建");
        // 同一对象原地改写 (TaCZ 扣弹、枪匠扣耐久) 不算换枪。
        gun.getOrCreateTag().putInt("GunCurrentAmmoCount", 29);
        GunsmithGunDurability.consumeShot(gun);
        helper.assertFalse(GunsmithTaczCacheGuard.needsRebuild(cache, gun, false),
                "同一个枪对象原地扣弹扣耐久后仍是同一把枪, 不得每发重建");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void guardRebuildsWhenCacheIsMissingOrHasNoBuildRecord(GameTestHelper helper) {
        ItemStack gun = m4WithAllParts(GunsmithPartQuality.COMMON);
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(null, gun, false),
                "服务端射手没有缓存时必须重建");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(null, gun, true),
                "客户端射手没有缓存时必须重建");
        Object unrecorded = new Object();
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(unrecorded, gun, false),
                "没有构建记录的缓存 (来源不明) 在服务端必须重建");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(unrecorded, gun, true),
                "没有构建记录的缓存在客户端同样必须重建");
        helper.succeed();
    }

    // ============================================================
    // 客户端: 服务端每发都把改过弹药数和耐久的枪整份同步回来, 槽里的对象几乎每发都是新的。
    // 只差逐发字段的新对象不得触发重建 (否则连发每发重建), 但换成另一把枪、改了配件或皮肤必须重建。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clientGuardIgnoresPerShotSyncButCatchesADifferentGun(GameTestHelper helper) {
        ItemStack held = withTaczGunState(m4WithAllParts(GunsmithPartQuality.COMMON), 30, true);
        Object cache = new Object();
        GunsmithTaczCacheGuard.recordBuild(cache, held);

        // 模拟服务端开火后同步回来的新对象: 弹匣少一发、膛内弹状态变化、枪温上升、枪匠耐久少一点。
        ItemStack synced = held.copy();
        CompoundTag syncedTag = synced.getOrCreateTag();
        syncedTag.putInt("GunCurrentAmmoCount", 29);
        syncedTag.putBoolean("HasBulletInBarrel", false);
        syncedTag.putFloat("HeatAmount", 12.5F);
        GunsmithGunDurability.consumeShot(synced);
        helper.assertTrue(GunsmithGunDurability.view(synced).current() < GunsmithGunDurability.view(held).current(),
                "前提: 同步回来的那份耐久确实少了");
        helper.assertFalse(GunsmithTaczCacheGuard.needsRebuild(cache, synced, true),
                "客户端: 只差弹药、膛内弹、枪温和枪匠耐久的同步副本仍是同一把枪, 不得重建");
        helper.assertFalse(GunsmithTaczCacheGuard.needsRebuild(cache, synced, false),
                "客户端放行后必须改认新对象, 下一发按对象身份直接命中");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(cache, held, false),
                "改认之后旧对象不再是这份缓存的构建者");

        ItemStack otherGun = withTaczGunState(m4WithAllParts(GunsmithPartQuality.LEGENDARY), 29, false);
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(cache, otherGun, true),
                "客户端: 原地换成另一把同图纸枪必须重建, 否则本机后坐与开镜仍是上一把枪的");

        ItemStack scoped = synced.copy();
        CompoundTag scope = new CompoundTag();
        scope.putString("id", "tacz:attachment");
        scoped.getOrCreateTag().put("AttachmentSCOPE", scope);
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(cache, scoped, true),
                "客户端: 配件不同的同型号枪必须重建");

        ItemStack skinned = synced.copy();
        skinned.getOrCreateTag().putString("GunDisplayId", "miningdim:m4a1_skin");
        helper.assertTrue(GunsmithTaczCacheGuard.needsRebuild(cache, skinned, true),
                "客户端: 皮肤不同的枪不在逐发字段之列, 必须重建");
        helper.succeed();
    }

    // ============================================================
    // V20: TaCZ 连发每一轮先抛 GunFireEvent、后 reduceAmmoOnce, 弹匣打空后的那一轮也会抛事件。
    // 耐久只按真正射出的发数扣。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void roundFeedsFollowsTaczReduceAmmoOnce(GameTestHelper helper) {
        GunsmithTaczDurabilityHandler.BoltAction closed = GunsmithTaczDurabilityHandler.BoltAction.CLOSED_BOLT;
        GunsmithTaczDurabilityHandler.BoltAction open = GunsmithTaczDurabilityHandler.BoltAction.OPEN_BOLT;
        GunsmithTaczDurabilityHandler.BoltAction manual = GunsmithTaczDurabilityHandler.BoltAction.MANUAL_ACTION;

        // 闭膛: 弹匣有弹吃弹匣, 弹匣空了打膛内那一发, 都空才是空转轮。
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, true, false, false, 1),
                "闭膛: 弹匣 1 + 膛内 1 能射出");
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, false, false, false, 1),
                "闭膛: 只剩弹匣 1 发也能射出");
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, true, false, false, 0),
                "闭膛: 弹匣空但膛内有弹能射出");
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, false, false, false, 0),
                "闭膛: 弹匣与膛内都空是空转轮");
        // 开膛: 只看弹匣, 膛内标记不算数。
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, open, false, false, false, 1),
                "开膛: 弹匣有弹能射出");
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, open, true, false, false, 0),
                "开膛: 弹匣空时膛内标记不算数");
        // 手动枪机: 只看膛内。
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, manual, true, false, false, 0),
                "手动枪机: 膛内有弹能射出");
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, manual, false, false, false, 5),
                "手动枪机: 膛内没弹时弹匣有弹也打不出");
        // 背包供弹: 看背包有没有弹, 不看弹匣余量。
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, false, true, true, 0),
                "背包供弹闭膛: 背包有弹能射出");
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, false, true, false, 30),
                "背包供弹闭膛: 背包没弹、膛内也空时弹匣数字不算数");
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(true, closed, true, true, false, 0),
                "背包供弹闭膛: 背包没弹但膛内有弹仍能射出");
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, open, false, true, false, 30),
                "背包供弹开膛: 背包没弹是空转轮");
        // 不消耗弹药 (创造模式等): TaCZ 不调 reduceAmmoOnce, 每轮都射出。
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(false, closed, false, false, false, 0),
                "不消耗弹药时每一轮都会射出");
        helper.assertTrue(GunsmithTaczDurabilityHandler.roundFeeds(false, null, false, false, false, 0),
                "不消耗弹药时连枪机类型都不看");
        // 查不到枪机类型: TaCZ reduceAmmoOnce 直接返回 false。
        helper.assertFalse(GunsmithTaczDurabilityHandler.roundFeeds(true, null, true, false, false, 30),
                "消耗弹药但查不到枪机类型时 TaCZ 不会射出");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void burstTailOnlyWearsTheRoundsThatActuallyFire(GameTestHelper helper) {
        // 弹匣 1 + 膛内 1: 三连发只射出 2 发, 修复前扣 3 点。
        assertBurstWear(helper, 1, true, 2, "弹匣 1 + 膛内 1");
        // 只剩膛内 1 发: 射出 1 发, 修复前扣 2 点 (第 3 轮不会执行)。
        assertBurstWear(helper, 0, true, 1, "只剩膛内 1 发");
        // 弹药充足: 三发都射出, 照常扣 3 点。
        assertBurstWear(helper, 29, true, 3, "弹药充足");
        helper.succeed();
    }

    private static void assertBurstWear(GameTestHelper helper, int magazine, boolean barrel,
                                        int expectedFired, String label) {
        ItemStack gun = m4WithAllParts(GunsmithPartQuality.COMMON);
        int before = GunsmithGunDurability.view(gun).current();
        int fired = closedBoltBurst(gun, magazine, barrel, 3);
        int worn = before - GunsmithGunDurability.view(gun).current();
        helper.assertTrue(fired == expectedFired,
                label + ": 三连发应射出 " + expectedFired + " 发, 实得 " + fired);
        helper.assertTrue(worn == fired,
                label + ": 耐久必须按实际射出的发数扣, 射出 " + fired + " 发却扣了 " + worn + " 点");
    }

    /**
     * 按 TaCZ 1.1.8 ModernKineticGunScriptAPI.lambda$shootOnce$2 的顺序走一次闭膛连发: 每一轮先抛 GunFireEvent
     * (处理器在这里判定本轮能否射出并结算耐久), 再 reduceAmmoOnce (闭膛先吃弹匣、弹匣空了打膛内、都空返回 false
     * 结束循环)。弹药推进在这里按 TaCZ 独立手写, 不复用被测判定, 以免自证。
     */
    private static int closedBoltBurst(ItemStack gun, int magazine, boolean barrel, int burstCount) {
        int fired = 0;
        for (int round = 0; round < burstCount; round++) {
            if (GunsmithTaczDurabilityHandler.roundFeeds(true,
                    GunsmithTaczDurabilityHandler.BoltAction.CLOSED_BOLT, barrel, false, false, magazine)) {
                GunsmithGunDurability.consumeShot(gun);
            }
            if (magazine > 0) {
                magazine--;
            } else if (barrel) {
                barrel = false;
            } else {
                break;
            }
            fired++;
        }
        return fired;
    }

    /** 补上 TaCZ 枪 NBT 的几个键 (GunItemDataAccessor 的键名), 让比对面对的是真实形态的枪。 */
    private static ItemStack withTaczGunState(ItemStack gun, int magazine, boolean barrel) {
        CompoundTag tag = gun.getOrCreateTag();
        tag.putString("GunId", "tacz:m4a1");
        tag.putString("GunFireMode", "AUTO");
        tag.putInt("GunCurrentAmmoCount", magazine);
        tag.putBoolean("HasBulletInBarrel", barrel);
        return gun;
    }

    private static ItemStack m4WithAllParts(GunsmithPartQuality quality) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
            parts.put(part, GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, part, quality));
        }
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
    }
}
