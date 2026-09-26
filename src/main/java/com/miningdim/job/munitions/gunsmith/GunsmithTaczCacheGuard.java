package com.miningdim.job.munitions.gunsmith;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * TaCZ 射手属性缓存守卫 (V04)。
 *
 * TaCZ 把配件与枪匠倍率算好的属性缓存挂在射手实体上, 只在 draw、切射击模式、改装、卸配件、登录等时机重建。
 * 同图纸、无皮肤的两把枪在同一快捷栏槽原地互换时, TaCZ 客户端的 isSame 只比 GunId 与 GunDisplayId, 不发 draw,
 * 服务端缓存就停在上一把枪上: 开火用 A 的伤害、射速、散布, 扣的却是 B 的耐久, A 报废了也照样打出传奇性能。
 *
 * 这里记下每一份缓存是用哪一个 ItemStack 对象构建的, 开火前核对: 每次扣扳机 (GunShootEvent) 核一次, 连发推迟到
 * 后续 tick 的各轮 (GunFireEvent) 逐轮再核一次。AttachmentPropertyEvent 本身不带实体, 但 TaCZ
 * 写射手缓存只有 AttachmentPropertyManager.postChangeEvent 一条路: 先拿新建的缓存对象抛该事件, 紧接着把同一个
 * 对象 updateCacheProperty 挂到实体上。所以"实体当前的缓存 -> 构建它的那把枪"就是该实体最近一次构建缓存所用的枪,
 * 不必逐个去挂 draw、改装、卸配件、登录、热重载这些入口。缓存 (键) 与枪 (值) 都只弱引用, 实体换缓存、实体或枪
 * 被回收后条目自然消失。缓存类没有覆写 equals/hashCode, WeakHashMap 按对象身份区分。
 *
 * 本类不引用任何 TaCZ 类型 (dev GameTest 不加载 TaCZ), 事件接线在 {@link GunsmithTaczStatsHandler}。
 * 单人游戏里服务端线程与客户端渲染线程共用这张表, 读写一律加锁。
 */
public final class GunsmithTaczCacheGuard {

    /**
     * TaCZ 1.1.8 每发都会改写的枪 NBT 键 (GunItemDataAccessor): 弹匣余量、膛内弹、虚拟弹药、枪管热量与过热锁。
     * 只用于客户端的宽松比对; 漏掉某个逐发变化的键只会让客户端多重建一次缓存, 不影响服务端判定。
     */
    private static final List<String> PER_SHOT_TACZ_KEYS = List.of(
            "GunCurrentAmmoCount", "HasBulletInBarrel", "DummyAmmo", "HeatAmount", "OverHeated");

    /** TaCZ 1.1.8 服务端射击冷却的余量: 冷却 = 间隔 - 距上一发 - 5ms, 大于 0 才拦。 */
    private static final long TACZ_SHOOT_COOLDOWN_SLACK_MILLIS = 5L;

    private static final Map<Object, WeakReference<ItemStack>> BUILT_WITH = new WeakHashMap<>();

    private GunsmithTaczCacheGuard() {
    }

    /** TaCZ 刚用 gun 构建出 cache (AttachmentPropertyEvent), 紧接着就会把它挂到射手身上。 */
    public static void recordBuild(Object cache, ItemStack gun) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(gun, "gun");
        synchronized (BUILT_WITH) {
            BUILT_WITH.put(cache, new WeakReference<>(gun));
        }
    }

    /**
     * 开火前核对射手当前的缓存是不是用本次开火的这把枪构建的。返回 true 时调用方必须先重建缓存再放行。
     * 缓存为空或没有构建记录一律按"要重建"处理。
     *
     * 服务端只认同一个 ItemStack 对象: 服务端槽里的枪在开火扣弹、扣耐久时都是原地改写, 对象变了就是真的换过枪,
     * 内容一模一样也照样重建, 不给"内容恰好相同"留判断空间。
     * 客户端放宽: 服务端每发都把改过弹药数和耐久的枪整份同步回来, 客户端槽里的对象几乎每发都是新的。客户端缓存只管
     * 本机后坐与开镜表现, 不参与结算, 所以除逐发变化的字段外 NBT 全同就认作同一把枪, 并改认新对象, 免得连发时每发都重建。
     */
    public static boolean needsRebuild(@Nullable Object cache, ItemStack firing, boolean clientSide) {
        Objects.requireNonNull(firing, "firing");
        if (cache == null) {
            return true;
        }
        synchronized (BUILT_WITH) {
            WeakReference<ItemStack> reference = BUILT_WITH.get(cache);
            ItemStack builtWith = reference == null ? null : reference.get();
            if (builtWith == firing) {
                return false;
            }
            if (builtWith == null || !clientSide || !sameCacheInputs(builtWith, firing)) {
                return true;
            }
            BUILT_WITH.put(cache, new WeakReference<>(firing));
            return false;
        }
    }

    /**
     * 射速嫁接复核, 返回 true 表示本发作废。TaCZ 服务端射击冷却 (LivingEntityShoot.getShootCoolDown) 在 GunShootEvent
     * 之前就按射手身上的旧缓存算间隔。改包客户端可以先把快枪 A 换进槽里发一次切射击模式 (缓存重建为 A, 该包没有冷却),
     * 再把 B 换回来开火, B 就按 A 的射速过了冷却; 守卫随后才把缓存重建成 B, 只纠正得了子弹属性。
     *
     * 所以重建之后: 旧缓存的间隔比新缓存短, 说明刚才的冷却判定被放宽过, 按新间隔复核一次, 距该射手上一发放行的服务端时间
     * 不足新间隔 (与 TaCZ 同样让 5ms) 就作废。旧缓存不比新缓存宽 (间隔相同或更长) 时 TaCZ 已经判得够严, 不复核,
     * 免得 TaCZ 用的客户端时间轴与这里的服务端时钟之间的抖动误伤; 没有上一发记录 (刚进服、实体刚生成) 时放行。
     * 原版玩家同槽原地换枪要开背包, 早就过了新间隔, 不受影响。纯函数, 供 GameTest 直接驱动。
     */
    public static boolean rebuiltCacheRejectsShot(long staleIntervalMillis, long rebuiltIntervalMillis,
                                                  @Nullable Long lastShotMillis, long nowMillis) {
        if (staleIntervalMillis >= rebuiltIntervalMillis || lastShotMillis == null) {
            return false;
        }
        return nowMillis - lastShotMillis < rebuiltIntervalMillis - TACZ_SHOOT_COOLDOWN_SLACK_MILLIS;
    }

    /** 两把枪喂给 TaCZ 属性缓存的输入是否相同: 同一物品, 且去掉逐发变化的字段 (弹药、枪温、枪匠耐久) 后 NBT 全同。 */
    static boolean sameCacheInputs(ItemStack first, ItemStack second) {
        return ItemStack.isSameItem(first, second)
                && Objects.equals(withoutPerShotState(first.getTag()), withoutPerShotState(second.getTag()));
    }

    @Nullable
    private static CompoundTag withoutPerShotState(@Nullable CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        CompoundTag stripped = tag.copy();
        PER_SHOT_TACZ_KEYS.forEach(stripped::remove);
        if (stripped.contains(GunsmithGunStats.ROOT_KEY, Tag.TAG_COMPOUND)) {
            stripped.getCompound(GunsmithGunStats.ROOT_KEY).remove(GunsmithGunDurability.DURABILITY_KEY);
        }
        return stripped;
    }
}
