package com.miningdim.job.engineer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.engineer.effect.NanoReactor;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 铸甲师面板的 job.engineer.state WebUiAction (纳米板六档表 + 四个护甲特效 + 反应堆共享 CD)。全只读。
 *
 * <h2>职业名</h2>
 * 玩家可见职业名是<b>铸甲师</b>; {@code engineer} 只是旧存档/旧命令的兼容 id ({@link JobId#byId} 里 armorer
 * 与"铸甲师"都映射到 ENGINEER)。故本 action 一个中文都不发, 只发 {@code job.miningdim.engineer} 这类翻译键,
 * 中文由前端 client.i18n 解 —— 专用服务端不加载 lang 文件, 在服务端解出来的只会是键本身。
 *
 * <h2>QTE 不进面板 (决策 J5)</h2>
 * 纳米校准的游标位置是每 tick 变化的服务端时序权威值, 判定手感直接吃网络延迟, 决策 J5 定为不进 MCEF。故本
 * action <b>不</b>下发 {@link NanoCalibration} 的任何游标/绿区/相位字段。校准影响到的<b>结果面</b>照发
 * (品质阈值与额外产板概率是数值预览, 不是交互), 玩家在平板里看得到"打得准有什么用", 但只能回到原生 GUI 去打。
 *
 * <h2>四个特效没有各自的等级门</h2>
 * {@code NanoRepair.rollEffect} 是四选一等概率, 全工程没有"哪个特效更晚解锁"这回事。故四个特效的 unlocked
 * 恒同步翻转, 判据是"最低的那个能掷特效的档"的解锁等级 (默认档表 = 高级板 L5), 由 {@link NanoTier} 现算。
 *
 * <h2>实时读 config</h2>
 * 全部数值每次调用现读 {@link EngineerConfig}/{@link NanoTier} 的 {@code .get()}。抄一份静态副本等于让面板
 * 永远显示进程启动那一刻的数值, 运营改完 miningdim-engineer.toml 看不到任何变化。
 */
public final class EngineerWebUiActions {

    /**
     * 本类不发任何 null 值, 故用默认 Gson (drops-null)。
     *
     * 这不是省事: 回执里每一个键都有确定的数值/布尔真值 —— 未解锁的档发的是 {@code unlocked:false} 而不是
     * 把整档抽掉, 特效同理。契约里没有 {@code T | null} 这一档, 前端不必写任何 {@code ?:}。
     */
    private static final Gson GSON = new Gson();

    /**
     * 修复量的探针最大耐久 (千分之一粒度)。
     *
     * 档位表要回答"这档能修多少", 但极品/超凡/闪耀三档的修复量是<b>按目标护甲最大耐久的百分比</b>算的,
     * 脱离具体护甲没有绝对值。此处拿 1000 点最大耐久喂唯一的修复量实现 {@link NanoRepair#repairAmount}
     * 反解: 定值档 (低/中/高) 的返回值与 maxDamage 无关, 直接就是配置的耐久点数; 百分比档返回
     * {@code floor(1000 * pct)} 即千分比。这样面板层不必再抄一份 "档 -&gt; 哪个 config 键" 的映射 ——
     * 抄了就会和 NanoRepair 分叉, 而分叉的表现是面板上的数字与真修出来的耐久对不上。
     */
    private static final int REPAIR_PROBE_MAX_DURABILITY = 1000;

    private EngineerWebUiActions() {
    }

    /** 把 job.engineer.state 注册进派发器 (由 {@link EngineerSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.engineer.state", STATE);
    }

    static final WebUiAction STATE = (sender, payload) -> {
        int level = JobServices.jobService().level(sender, JobId.ENGINEER);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        // 键按 JobId.displayName 的同一规则拼 (job.miningdim.<稳定id>): 稳定 id 仍是 engineer, 而这个键在
        // 中文 lang 里就是"铸甲师"。面板拿键去解, 服务端不发中文, 也就不存在两处文案分叉。
        result.addProperty("jobNameKey", "job.miningdim." + JobId.ENGINEER.id());
        result.addProperty("unlockedTierId", tierId(EngineerLevels.unlockedTier(level)));
        result.addProperty("effectUnlockLevel", effectUnlockLevel());

        // 反应堆 (图腾) 共享 CD 是人级的, 与穿了几件带图腾的甲无关。发剩余 tick 不发 epoch millis: 服务端手里
        // 只有 game tick, 换算成服务端墙钟再让 MCEF 客户端拿 Date.now() 去减, 既吃时钟偏移又在 TPS 掉帧时失真。
        result.addProperty("reactorCooldownRemainingTicks", reactorCooldownRemainingTicks(sender));
        result.addProperty("reactorSharedCdTicks", EngineerConfig.TOTEM_SHARED_CD_TICKS.get());

        JsonArray tiers = new JsonArray();
        for (NanoTier tier : NanoTier.values()) {
            tiers.add(tierJson(tier, level));
        }
        result.add("tiers", tiers);

        JsonArray effects = new JsonArray();
        for (NanoEffect effect : NanoEffect.values()) {
            effects.add(effectJson(effect, level));
        }
        result.add("armorEffects", effects);

        // 品质 (校准命中数) 的两个结果面: 达阈值后有概率额外 +1 板。J5 之下玩家只能在原生 GUI 打校准,
        // 但"打准了值多少"属于数值预览, 面板必须讲得出。
        result.addProperty("qualityBonusThreshold", EngineerConfig.CALIBRATION_QUALITY_BONUS_THRESHOLD.get());
        result.addProperty("qualityBonusPlateChance", EngineerConfig.CALIBRATION_BONUS_PLATE_CHANCE.get());
        result.addProperty("ownPlateRepairXpBonus", EngineerConfig.OWN_PLATE_REPAIR_XP_BONUS.get());
        return GSON.toJson(result);
    };

    // ============================================================
    // 档位表
    // ============================================================

    /** 一档纳米维修套件。全部数值实时取 {@link NanoTier} 的 config 直读方法。 */
    private static JsonObject tierJson(NanoTier tier, int level) {
        Item plate = ModEngineerItems.plate(tier).get();

        JsonObject row = new JsonObject();
        row.addProperty("tierId", tierId(tier));
        // 与生产台 GUI / 套件 tooltip 同一批 lang 键 (tier.miningdim.nano.<档>), 不另起一套命名。
        row.addProperty("labelKey", "tier.miningdim.nano." + tierId(tier));
        row.addProperty("index", tier.index());
        row.addProperty("unlockLevel", tier.unlockLevel());
        row.addProperty("unlocked", EngineerLevels.isTierUnlocked(level, tier));
        row.addProperty("plateItemId", ForgeRegistries.ITEMS.getKey(plate).toString());
        row.addProperty("plateDescriptionId", plate.getDescriptionId());
        row.addProperty("oreCost", tier.oreCost());
        row.addProperty("outputCount", tier.outputCount());
        row.addProperty("produceTicks", tier.produceTicks());
        row.addProperty("rawXp", tier.rawXp());
        row.addProperty("canRollEffect", tier.canRollEffect());

        boolean percentRepair = tier.isPercentRepair() || tier.isRadiant();
        // durability = 绝对耐久点数; permille = 目标护甲最大耐久的千分比 (1000 = 修满)。
        row.addProperty("repairUnit", percentRepair ? "permille" : "durability");
        row.addProperty("repairValue", NanoRepair.repairAmount(tier, REPAIR_PROBE_MAX_DURABILITY));

        // 闪耀是唯一"必定重掷特效"的档 (其余高档是按 base + coef*品质 的概率掷), 单独一位而不是让前端从
        // canRollEffect + tierId 反推。
        row.addProperty("guaranteedEffect", tier.isRadiant());
        if (tier.isRadiant()) {
            // 闪耀是唯一概率产出的档: 失败返还下界合金碎片。两个数只对这一档有意义, 故只在这一档发。
            row.addProperty("successChance", EngineerConfig.RADIANT_SUCCESS_CHANCE.get());
            row.addProperty("failRefundScrap", EngineerConfig.RADIANT_FAIL_REFUND.get());
        }
        return row;
    }

    private static String tierId(NanoTier tier) {
        return tier.name().toLowerCase();
    }

    // ============================================================
    // 护甲特效
    // ============================================================

    /**
     * 一个纳米护甲特效 + 它的实时数值。
     *
     * 解锁判据是算出来的不是写死的: 特效只有"能掷特效的档"才会掷出 ({@link NanoTier#canRollEffect}),
     * 故四个特效同时在最低的那一个可掷档解锁 ({@link #effectUnlockLevel})。四个特效之间没有各自的等级门 ——
     * {@code NanoRepair.rollEffect} 是四选一等概率, 给它们编出不同的解锁等级就是凭空造规则。
     */
    private static JsonObject effectJson(NanoEffect effect, int level) {
        JsonObject row = new JsonObject();
        row.addProperty("effectId", effect.id());
        // 与既有 tier.miningdim.nano.* 同构的键空间 (effect.miningdim.nano.<id>)。lang 条目尚未落地, 已报备。
        row.addProperty("labelKey", "effect.miningdim.nano." + effect.id());
        row.addProperty("descriptionKey", "effect.miningdim.nano." + effect.id() + ".desc");
        row.addProperty("unlockLevel", effectUnlockLevel());
        row.addProperty("unlocked", level >= effectUnlockLevel());

        JsonArray stats = new JsonArray();
        switch (effect) {
            case RESHAPE -> {
                stats.add(stat("durabilityPerTick", EngineerConfig.RESHAPE_DURABILITY_PER_TICK.get(), "flat"));
                stats.add(stat("failDamagePct", EngineerConfig.RESHAPE_FAIL_DAMAGE_PCT.get(), "percent"));
                stats.add(stat("intervalTicks", EngineerConfig.EFFECT_TICK_INTERVAL.get(), "ticks"));
            }
            case VITALITY -> {
                stats.add(stat("healPctPerTick", EngineerConfig.VITALITY_HEAL_PCT_PER_TICK.get(), "percent"));
                stats.add(stat("failDurabilityPct", EngineerConfig.VITALITY_FAIL_DURABILITY_PCT.get(), "percent"));
                stats.add(stat("intervalTicks", EngineerConfig.EFFECT_TICK_INTERVAL.get(), "ticks"));
            }
            case SHIELD -> {
                stats.add(stat("immunityTicks", EngineerConfig.SHIELD_IMMUNITY_TICKS.get(), "ticks"));
                stats.add(stat("regenIntervalTicks", EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get(), "ticks"));
                stats.add(stat("maxCharges", EngineerConfig.SHIELD_MAX_CHARGES.get(), "count"));
            }
            case TOTEM -> {
                stats.add(stat("sharedCdTicks", EngineerConfig.TOTEM_SHARED_CD_TICKS.get(), "ticks"));
                stats.add(stat("reviveHealthPct", EngineerConfig.TOTEM_REVIVE_HEALTH_PCT.get(), "percent"));
                stats.add(stat("invulnTicks", EngineerConfig.TOTEM_INVULN_TICKS.get(), "ticks"));
                stats.add(stat("durabilityCostPct", EngineerConfig.TOTEM_DURABILITY_COST_PCT.get(), "percent"));
            }
        }
        row.add("stats", stats);
        return row;
    }

    /**
     * 特效解锁等级 = 最低的那个"可掷特效"档的解锁等级 (默认档表下是高级板的 L5)。
     *
     * 从 {@link NanoTier} 现算而不是写常量 5: 改档表 (比如把掷特效下放到中级板) 时面板必须跟着变。
     * 一个可掷档都没有是档表被改坏了, 就地抛而不是回一个 1 假装人人解锁。
     */
    private static int effectUnlockLevel() {
        int lowest = Integer.MAX_VALUE;
        for (NanoTier tier : NanoTier.values()) {
            if (tier.canRollEffect()) {
                lowest = Math.min(lowest, tier.unlockLevel());
            }
        }
        if (lowest == Integer.MAX_VALUE) {
            throw new IllegalStateException("纳米档表里没有任何可掷特效的档, 护甲特效解锁等级无从谈起");
        }
        return lowest;
    }

    /** 一行数值。unit: percent = 0..1 的比例 (前端 x100 显示); ticks = 游戏刻; count/flat = 个数/绝对值。 */
    private static JsonObject stat(String key, double value, String unit) {
        JsonObject json = new JsonObject();
        json.addProperty("key", key);
        json.addProperty("labelKey", "stat.miningdim.engineer." + key);
        json.addProperty("value", value);
        json.addProperty("unit", unit);
        return json;
    }

    // ============================================================
    // 反应堆共享 CD
    // ============================================================

    /**
     * 反应堆共享 CD 的剩余 tick (0 = 已就绪)。
     *
     * 与 {@code NanoReactorHandler} 读的是同一个字段 ({@link com.miningdim.job.JobProgress#nanoReactorCdEndTick}),
     * 判据也复用 {@code NanoReactor.cooldownReady} —— 面板说"能救"而实战不触发 (或反之) 就是两条路径分叉。
     */
    private static long reactorCooldownRemainingTicks(ServerPlayer sender) {
        IMiningPlayerData data = MiningCapabilities.get(sender).orElseThrow(() -> new IllegalStateException(
                "玩家 " + sender.getGameProfile().getName() + " 未挂载矿山玩家数据 capability"));
        long now = sender.serverLevel().getGameTime();
        long cdEndTick = data.jobProgress(JobId.ENGINEER).nanoReactorCdEndTick();
        if (NanoReactor.cooldownReady(now, cdEndTick)) {
            return 0L;
        }
        return cdEndTick - now;
    }
}
