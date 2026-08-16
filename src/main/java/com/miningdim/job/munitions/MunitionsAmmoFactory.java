package com.miningdim.job.munitions;

import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * TACZ 弹药物化隔离层 (Munitions_Job_DesignSpec 三/十章; compileOnly 铁律的唯一触点)。把 "产 N 发某口径" 的纯逻辑
 * (在 {@link MunitionsProduction}) 与 "物化成真 TACZ 弹 ItemStack" 分离: 本类是工程里唯一 import com.tacz.* 的点。
 *
 * compileOnly 铁律 (build.gradle TACZ compileOnly, dev 不加载): 任何会加载 com.tacz.* 类的代码路径不能被 dev
 * GameTest 触达, 否则 NoClassDefFoundError。故:
 *  - {@link #materializeWithTacz} (默认 {@link #materializer}) 内才调
 *    {@code AmmoItemBuilder.create().setId().setCount().build()} (javap 核实 API);
 *  - 调用前用 {@link #isTaczLoaded()} (ModList isLoaded) 短路: TACZ 未加载返回 {@link ItemStack#EMPTY}, 不进
 *    会加载 TACZ 类的路径, 优雅短路不抛 (dev/未装 TACZ 的服都安全);
 *  - GameTest 经 {@link #registerMaterializer} 注入替身后可安全调用 {@link #materialize} (不再落进 com.tacz.* 路径);
 *    未注入替身时仍不得调用本类任何方法, 真造弹在正式服 (TACZ 已加载) 验。
 *
 * 真 AmmoId: ResourceLocation(tacz, &lt;caliber path&gt;), path 取自 {@link MunitionsCaliber#defaultAmmoPath()}
 * (来源 TACZ 默认枪包 data/tacz/index/ammo/*.json, 24 口径 javap+unzip 核实)。
 */
public final class MunitionsAmmoFactory {

    /** TACZ modid (ModList isLoaded 短路 + recipe_filter datapack 命名空间)。 */
    public static final String TACZ_MODID = "tacz";

    /**
     * 弹药物化替身接口 (F001 测试盲区): {@link #isTaczLoaded()} 短路使 dev/GameTest 下 {@link #materialize} 恒返
     * {@link ItemStack#EMPTY}, 结构上无法覆盖 "输出槽 count 超单栈上限" 这类需要真 ItemStack.getMaxStackSize 的
     * Critical 缺陷。留一个替身注入口, 由 GameTest 注册返回原版物品 (自带真实 getMaxStackSize) 的实现,
     * 真断言 count &lt;= maxStackSize; 正式服不注册, 走默认 TACZ 实现。
     */
    @FunctionalInterface
    public interface AmmoMaterializer {
        ItemStack materialize(MunitionsCaliber caliber, int count);
    }

    private static AmmoMaterializer materializer = MunitionsAmmoFactory::materializeWithTacz;

    private MunitionsAmmoFactory() {
    }

    /** TACZ 是否在运行期加载 (dev compileOnly 不加载返 false; 物化前必查, 防 NoClassDefFoundError)。 */
    public static boolean isTaczLoaded() {
        return ModList.get().isLoaded(TACZ_MODID);
    }

    /**
     * 把 "产 count 发某口径" 物化成真弹 ItemStack (十章: AmmoItemBuilder 构造合法弹药)。委派给当前注册的
     * {@link #materializer} (默认 {@link #materializeWithTacz}), GameTest 可经 {@link #registerMaterializer}
     * 替换成返回原版物品的替身。
     *
     * @param caliber 目标口径 (取 defaultAmmoPath 构造 AmmoId; null 返回 EMPTY)
     * @param count   产出发数 (>=1; 受弹药 stack_size 上限约束, 调用方按需分栈)
     * @return 物化后的 ItemStack; TACZ 未加载 / 非法入参返回 EMPTY
     */
    public static ItemStack materialize(MunitionsCaliber caliber, int count) {
        return materializer.materialize(caliber, count);
    }

    /** GameTest 注入替身实现 (null 抛 IllegalArgumentException; 范式对齐 JobServices.registerJobService)。 */
    public static void registerMaterializer(AmmoMaterializer override) {
        if (override == null) {
            throw new IllegalArgumentException("Cannot register null AmmoMaterializer");
        }
        materializer = override;
    }

    /** 恢复默认 TACZ 物化实现 (供 GameTest 用例结束后复位, 防跨用例污染)。 */
    public static void resetMaterializer() {
        materializer = MunitionsAmmoFactory::materializeWithTacz;
    }

    /**
     * 默认物化实现 (十章: AmmoItemBuilder 构造合法弹药)。
     *
     * compileOnly 短路: TACZ 未加载 (dev / 未装) 或 count &lt;= 0 时返回 {@link ItemStack#EMPTY} (不进
     * AmmoItemBuilder 路径, 不加载 com.tacz.*)。注入到 BE 缓冲槽前调用方须判 isEmpty (空表示物化失败/TACZ 缺)。
     */
    private static ItemStack materializeWithTacz(MunitionsCaliber caliber, int count) {
        if (caliber == null || count <= 0 || !isTaczLoaded()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation ammoId = new ResourceLocation(MunitionsCaliber.TACZ_NAMESPACE, caliber.defaultAmmoPath());
        return buildTaczAmmo(ammoId, count);
    }

    /**
     * 真 TACZ AmmoItemBuilder 调用 (javap 核实链: create().setId(ResourceLocation).setCount(int).build())。
     * 抽出为单独 private 方法, 使加载 com.tacz.* 的字节码集中此处一点; 上层 {@link #materializeWithTacz} 守卫后才进。
     * 本方法被调用即意味着 TACZ 已加载 (materializeWithTacz 已 isTaczLoaded 短路)。
     */
    private static ItemStack buildTaczAmmo(ResourceLocation ammoId, int count) {
        return AmmoItemBuilder.create().setId(ammoId).setCount(count).build();
    }
}
