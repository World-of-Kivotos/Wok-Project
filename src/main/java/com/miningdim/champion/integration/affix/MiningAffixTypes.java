package com.miningdim.champion.integration.affix;

import com.miningdim.champion.AffixDef;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import top.theillusivec4.champions.api.AffixRegistry;
import top.theillusivec4.champions.api.IAffix;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 本工程 35 词条向 Champions 注册表的注册中心 (Champions 集成层; 仿内置 {@code AffixTypes} 范本)。
 *
 * 注册真相 (javap 核实 AffixRegistry): {@code AffixRegistry.AFFIXES} 是 Champions 模组持有并已在它自己的
 * modEventBus 上 attach 的 {@code DeferredRegister<IAffix>}。我方只能对同一实例调
 * {@code .register(name, supplier)} 追加条目, 条目随 Champions 的 RegisterEvent 一并注册; 严禁再
 * {@code AFFIXES.register(ourModBus)} (双重 attach 抛 IllegalStateException, 见调研 risks)。故本类 static 初始化
 * 即 register 全部 35 条目 (类加载时机由 ChampionSystem 在 ModList 守卫下触发, 早于 RegisterEvent)。
 *
 * 单类多供应器 (架构决策): 35 词条复用单一 {@link MiningAffix} 类, 每条注册一个独立 {@code () -> new MiningAffix(def)}
 * 供应器 (注册名唯一即可, IAffix 实例无需各建一个具体类)。理由: 净减伤/DoT/反伤一律走集中式单点 handler
 * (9A.2 净减伤单点铁律 + 第五章易伤单点), 词条本体不持各自的 onHurt 串行减伤逻辑, 故无须 35 个差异化子类;
 * 词条间差异 (池/数值/互斥) 全在纯逻辑 {@link AffixDef} 表, 由 handler 读 {@link MiningAffix#def()} 解释。
 *
 * compileOnly 隔离: 本类直接静态引用 {@code AffixRegistry.AFFIXES} —— Champions 未加载则类加载即
 * NoClassDefFoundError。落地铁律: 仅在 {@code ModList.isLoaded("champions")} 为真时由 ChampionSystem 调
 * {@link #register()} 触发本类初始化, 未加载整条路径不被触达 (dev GameTest 安全)。
 */
public final class MiningAffixTypes {

    private MiningAffixTypes() {
    }

    /** 注册名 = 词条枚举名小写 (与 affix_setting JSON 的 type 字段 champions:&lt;name&gt; 全限定匹配)。 */
    private static final Map<AffixDef, RegistryObject<IAffix>> REGISTERED = new EnumMap<>(AffixDef.class);

    static {
        for (AffixDef def : AffixDef.values()) {
            String name = registryName(def);
            Supplier<IAffix> supplier = () -> new MiningAffix(def);
            REGISTERED.put(def, AffixRegistry.AFFIXES.register(name, supplier));
        }
    }

    /** 词条注册名 (枚举名小写, 如 COMPOSITE_ARMOR -> composite_armor)。affix_setting JSON 的 type path 同此。 */
    public static String registryName(AffixDef def) {
        return def.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 词条注册 ResourceLocation。namespace 恒为 champions: —— 词条经 Champions 的 {@code AffixRegistry.AFFIXES}
     * (DeferredRegister, modid=champions) 注册, 真 id = champions:&lt;name&gt;; 与之不符则 affix_setting 绑定 /
     * 特勤封印守卫 / HUD 显示全按错 id 查空 (= 35 词条整体失效, 历史 bug)。
     */
    public static ResourceLocation registryId(AffixDef def) {
        return new ResourceLocation(com.miningdim.champion.ChampionSystem.CHAMPIONS_MODID, registryName(def));
    }

    /** 取某词条已注册的真 IAffix 实例 (盖章用); 注册未完成时 RegistryObject 抛, 自然冒泡 (不掩盖装配缺陷)。 */
    public static IAffix affixOf(AffixDef def) {
        RegistryObject<IAffix> ro = REGISTERED.get(def);
        if (ro == null) {
            throw new IllegalStateException("affix not registered for def: " + def);
        }
        return ro.get();
    }

    /**
     * 触发本类 static 初始化 (= 注册全部 35 条目到 AffixRegistry.AFFIXES)。空体: 引用本类即触发 static block。
     * 仅 ChampionSystem 在 ModList 守卫下调用一次。不对 AFFIXES 再 register(modBus) (Champions 已 attach)。
     */
    public static void register() {
        // 引用 REGISTERED 强制类初始化完成 (static block 已执行注册)。
        if (REGISTERED.size() != AffixDef.values().length) {
            throw new IllegalStateException(
                    "affix registration incomplete: " + REGISTERED.size() + "/" + AffixDef.values().length);
        }
    }
}
