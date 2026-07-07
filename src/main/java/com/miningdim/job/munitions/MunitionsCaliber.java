package com.miningdim.job.munitions;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 军火商口径档枚举 (Munitions_Job_DesignSpec 6.1 可造口径 + 6.3 价格表)。每档绑定:
 *  - 稳定序号 index()/byIndex() (ContainerData int 同步 + NBT 持久化稳定);
 *  - 解锁等级 unlockLevel() (6.1 等级门: 手枪 L1 / 步枪 L3 / 霰弹 L4 / 战斗机枪 L5 / 狙击 L6 / 大口径手枪 L7 /
 *    反器材 L8 / 爆炸 L9 / 特种 L10);
 *  - defaultAmmoPath(): 该档默认绑定的 TACZ 默认枪包弹药 id 的 path 段 (命名空间恒 tacz; 真值取自
 *    jar 内 data/tacz/index/ammo/*.json 文件名, javap+unzip 核实 24 个; 仅 path 字符串, 不在本枚举构造
 *    ResourceLocation/触 TACZ 类, 保持纯逻辑层无 TACZ 依赖);
 *  - 商店价/售价/缩产系数经 {@link MunitionsConfig} 实时 get (6.3; 步枪基准缩产系数 1.0, 高阶弹 < 1.0)。
 *
 * compileOnly 铁律: 本枚举不引用 com.tacz.* 任何类。口径 -> 真 AmmoId 的 ResourceLocation 构造只在物化层
 * {@link MunitionsAmmoFactory} 发生 (那里才被 ModList isLoaded 守卫)。
 *
 * 默认弹药 id 来源标注: TACZ 默认枪包 tacz_default_gun (data/tacz/index/ammo/), 命名空间 tacz。玩家若装第三方
 * 枪包会注册更多 id, 本工程无法编译期穷举; 故仅内置默认 24 口径中代表档的 id, 未来扩档经 config 覆盖
 * (PENDING 11.2 逐口径细化)。
 */
public enum MunitionsCaliber {

    /** 手枪/SMG: L1; 默认 9mm。商店 10 / 售 7.5; 步枪基准缩产 1.0。 */
    PISTOL(0, 1, "9mm", Prices.PISTOL, Category.PISTOL, "9MM"),

    /** 步枪 (762x39/556): L3; 默认 762x39 (TACZ DefaultAssets.DEFAULT_AMMO_ID)。商店 20 / 售 15; 缩产 1.0。 */
    RIFLE(1, 3, "762x39", Prices.RIFLE, Category.RIFLE, "7.62"),

    /** 霰弹 (12g, 每发 10 弹丸): L4; 默认 12g。商店 35 / 售 26; 缩产 0.6。 */
    SHOTGUN(2, 4, "12g", Prices.SHOTGUN, Category.SHOTGUN, "12G"),

    /** 战斗步枪/机枪: L5; 默认 762x54。商店 30 / 售 22.5; 缩产 0.7。 */
    BATTLE(3, 5, "762x54", Prices.BATTLE, Category.RIFLE, "54R"),

    /** 狙击 (30_06/338): L6; 默认 338。商店 80 / 售 60; 缩产 0.4。 */
    SNIPER(4, 6, "338", Prices.SNIPER, Category.SNIPER, ".338"),

    /** 大口径手枪 (50ae): L7; 默认 50ae。商店 60 / 售 45; 缩产 0.5。 */
    BIG_PISTOL(5, 7, "50ae", Prices.BIG_PISTOL, Category.PISTOL, "50AE"),

    /** 反器材 (50bmg): L8; 默认 50bmg。商店 200 / 售 150; 缩产 0.25。 */
    ANTI_MATERIEL(6, 8, "50bmg", Prices.ANTI_MATERIEL, Category.SNIPER, "BMG"),

    /** 爆炸 (40mm/rpg): L9; 默认 40mm。商店 400-800 / 售 300-600; 缩产 0.15。 */
    EXPLOSIVE(7, 9, "40mm", Prices.EXPLOSIVE, Category.EXPLOSIVE, "40M"),

    /** 特种弹: L10 毕业; 默认 68x51fury。价格 PENDING 11.2 暂沿用狙击档; 缩产 0.4。 */
    SPECIAL(8, 10, "68x51fury", Prices.SPECIAL, Category.RIFLE, "68X"),

    /** 步枪弹: 5.56x45, TACZ 默认枪包 path 为 556x45。 */
    RIFLE_556(9, 3, "556x45", Prices.RIFLE, Category.RIFLE, "5.56");

    public enum Category {
        PISTOL("手枪弹"),
        RIFLE("步枪弹"),
        SHOTGUN("霰弹"),
        SNIPER("狙击弹"),
        EXPLOSIVE("爆破弹");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 各口径价格档 config 三元组 (商店价/售价/缩产系数), 由枚举构造绑定, 避免散落 switch。 */
    private record Prices(ForgeConfigSpec.IntValue shop, ForgeConfigSpec.IntValue sell,
                          ForgeConfigSpec.DoubleValue yield) {
        static final Prices PISTOL = new Prices(
                MunitionsConfig.SHOP_PRICE_PISTOL, MunitionsConfig.SELL_PRICE_PISTOL, MunitionsConfig.YIELD_FACTOR_PISTOL);
        static final Prices RIFLE = new Prices(
                MunitionsConfig.SHOP_PRICE_RIFLE, MunitionsConfig.SELL_PRICE_RIFLE, MunitionsConfig.YIELD_FACTOR_RIFLE);
        static final Prices SHOTGUN = new Prices(
                MunitionsConfig.SHOP_PRICE_SHOTGUN, MunitionsConfig.SELL_PRICE_SHOTGUN, MunitionsConfig.YIELD_FACTOR_SHOTGUN);
        static final Prices BATTLE = new Prices(
                MunitionsConfig.SHOP_PRICE_BATTLE, MunitionsConfig.SELL_PRICE_BATTLE, MunitionsConfig.YIELD_FACTOR_BATTLE);
        static final Prices SNIPER = new Prices(
                MunitionsConfig.SHOP_PRICE_SNIPER, MunitionsConfig.SELL_PRICE_SNIPER, MunitionsConfig.YIELD_FACTOR_SNIPER);
        static final Prices BIG_PISTOL = new Prices(
                MunitionsConfig.SHOP_PRICE_BIG_PISTOL, MunitionsConfig.SELL_PRICE_BIG_PISTOL, MunitionsConfig.YIELD_FACTOR_BIG_PISTOL);
        static final Prices ANTI_MATERIEL = new Prices(
                MunitionsConfig.SHOP_PRICE_ANTI_MATERIEL, MunitionsConfig.SELL_PRICE_ANTI_MATERIEL, MunitionsConfig.YIELD_FACTOR_ANTI_MATERIEL);
        static final Prices EXPLOSIVE = new Prices(
                MunitionsConfig.SHOP_PRICE_EXPLOSIVE, MunitionsConfig.SELL_PRICE_EXPLOSIVE, MunitionsConfig.YIELD_FACTOR_EXPLOSIVE);
        static final Prices SPECIAL = new Prices(
                MunitionsConfig.SHOP_PRICE_SPECIAL, MunitionsConfig.SELL_PRICE_SPECIAL, MunitionsConfig.YIELD_FACTOR_SPECIAL);
    }

    /** TACZ 弹药命名空间 (恒 tacz; data/ 后那段, javap 核实非 tacz_default_gun)。 */
    public static final String TACZ_NAMESPACE = "tacz";

    private final int index;
    private final int unlockLevel;
    private final String defaultAmmoPath;
    private final Prices prices;
    private final Category category;
    private final String shortLabel;

    MunitionsCaliber(int index, int unlockLevel, String defaultAmmoPath, Prices prices,
                     Category category, String shortLabel) {
        this.index = index;
        this.unlockLevel = unlockLevel;
        this.defaultAmmoPath = defaultAmmoPath;
        this.prices = prices;
        this.category = category;
        this.shortLabel = shortLabel;
    }

    /** 稳定序号 (clickMenuButton caliberIndex / NBT 持久化用)。 */
    public int index() {
        return index;
    }

    /** 该口径档解锁所需军火商等级 (6.1 等级门)。 */
    public int unlockLevel() {
        return unlockLevel;
    }

    /**
     * 该档默认 TACZ 弹药 id 的 path 段 (命名空间恒 {@link #TACZ_NAMESPACE})。来源: TACZ 默认枪包
     * data/tacz/index/ammo/&lt;path&gt;.json 文件名 (核实于 jar)。仅纯字符串, 不构造 ResourceLocation。
     */
    public String defaultAmmoPath() {
        return defaultAmmoPath;
    }

    public Category category() {
        return category;
    }

    public String shortLabel() {
        return shortLabel;
    }

    /** 该档商店价/发 (实时 config; 6.3, ×10 锚价)。 */
    public int shopPrice() {
        return prices.shop().get();
    }

    /** 该档军火商售价/发 (实时 config; 6.3 = 商店 75%)。 */
    public int sellPrice() {
        return prices.sell().get();
    }

    /**
     * 该档缩产系数 (实时 config; 四章高阶弹 "单发料重出弹数按比例减")。步枪基准 1.0; 反器材/爆炸 < 1.0。
     * 实产发数 = floor(基准发数 × 本系数)。
     */
    public double yieldFactor() {
        return prices.yield().get();
    }

    /** 稳定序号还原档位 (NBT/网络越界回 PISTOL, 不掩盖业务但防数组越界崩溃; 与 NanoTier.byIndex 同范式)。 */
    public static MunitionsCaliber byIndex(int idx) {
        for (MunitionsCaliber caliber : values()) {
            if (caliber.index == idx) {
                return caliber;
            }
        }
        return PISTOL;
    }
}
