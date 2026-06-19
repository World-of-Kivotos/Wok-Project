package com.miningdim.job.brewer;

/**
 * 酒类型 (酿酒师 第三节): 九种酒, 喝下后增益差异极大。类型即物品身份 —— 每种酒一个独立 item, 品质与年份
 * 存 ItemStack NBT ({@link WineNbt}); 故本枚举只承载身份, 不把类型重复写进 NBT。
 *
 * 原料与效果以下表列出 (原料由酿酒台配方消费, 效果由喝酒结算消费; 二者在后续阶段落地):
 * <pre>
 *  类型            普通效果              闪耀 (永久·一条命)        原料
 *  BRANDY 白兰地   急迫 (挖矿酒)          永久急迫                  小麦(大)·苹果
 *  VODKA  伏特加   抗性提升              永久 20% 减伤             小麦(超大)
 *  GIN    金酒     金心吸收             永久生命上限 (带帽)        小麦·糖
 *  RUM    朗姆酒   速度                  永久移速                  甘蔗·小麦
 *  TEQUILA 龙舌兰  力量                  永久力量                  萝卜·小麦
 *  MAOTAI 茅台     给经验值              职业经验加成              小麦·稻米
 *  WHISKEY 威士忌  瞬间恢复             周期性瞬间恢复            小麦
 *  CHAMPAGNE 香槟  生命恢复             常驻生命恢复              小麦
 *  MOONSHINE 月光  赌博 (随机好/坏)      赌博 (永久随机好/坏)      烈酒·小麦
 * </pre>
 */
public enum WineType {

    BRANDY("brandy"),
    VODKA("vodka"),
    GIN("gin"),
    RUM("rum"),
    TEQUILA("tequila"),
    MAOTAI("maotai"),
    WHISKEY("whiskey"),
    CHAMPAGNE("champagne"),
    MOONSHINE("moonshine");

    private final String id;

    WineType(String id) {
        this.id = id;
    }

    /** 小写稳定 id (物品注册名 "wine_brandy" 的后缀 / lang key / 配方键用)。 */
    public String id() {
        return id;
    }

    /** 物品注册名 (九种酒各一物品: wine_<id>)。 */
    public String itemRegistryName() {
        return "wine_" + id;
    }

    /** 按小写 id 反查; 未知返回 null (调用方短路, 不静默默认)。 */
    public static WineType fromId(String id) {
        for (WineType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
