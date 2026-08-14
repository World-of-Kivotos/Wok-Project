package com.miningdim.job.miner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * 矿工面板的 job.miner.* WebUiAction (只读态 + 探矿)。
 *
 * 服务端权威 (架构铁律 1): 等级/充能/CD/命中坐标一律服务端重算, 与键位路径 ({@link MinerActions}) 共用同一份
 * {@link MinerChargeState} 与同一条裁决链 —— 面板与键位是同一个玩家的同一个技能, 两条路径判据分叉即等于开后门。
 *
 * 前端契约 (webui/src/lib/types.ts):
 *  - job.miner.state -&gt; {level,charge,chargeMax,miningFatigueImmune,toggles[3],scanUnlockLevel,scanUnlocked,
 *    scanRadius,scanCooldownRemainingTicks,passives[6]}
 *  - job.miner.scan  -&gt; {oreItemId,oreDescriptionId,hits[],radius,pulseTicks,scanCooldownRemainingTicks}
 *
 * 时间一律发剩余 tick, 不发 epoch millis: 服务端手里只有 game tick, 转成服务端墙钟再让 MCEF 客户端拿
 * Date.now() 去减, 既吃时钟偏移又在 TPS 掉帧时失真。前端在收到的那一刻自行落成本地 epoch。
 */
public final class MinerWebUiActions {

    /**
     * 本类专用的 Gson: 必须 serializeNulls。
     *
     * 默认 Gson 的 JsonWriter 在 serializeNulls=false 下会把 {@link JsonNull} 成员整键丢掉, 于是"无命中时
     * oreItemId 为 null"会变成"根本没有 oreItemId 这个键" —— 前端契约写的是 {@code string | null}, 拿到
     * undefined 就是契约破裂。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 错误码 params 里标识是哪个主动技能 (SKILL_LOCKED / SKILL_ON_COOLDOWN 由它区分, 见 WebUiErrorCodes)。 */
    private static final String SKILL_ORE_SCAN = "ore_scan";

    private MinerWebUiActions() {
    }

    /** 把两条 job.miner.* action 注册进派发器 (由 {@link MinerSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.miner.state", STATE);
        WebUiServerDispatcher.register("job.miner.scan", SCAN);
    }

    // ============================================================
    // job.miner.state: {} -> 面板一屏所需的全部只读态
    // ============================================================

    /**
     * 矿工面板只读态。本 action <b>不推进</b>任何状态: 充能由 {@link MinerSystem#onServerTick} 每 tick 回充,
     * 这里只读当前值; CD 同理只读不清。
     */
    static final WebUiAction STATE = (sender, payload) -> {
        MinerSystem system = MinerSystem.get();
        MinerChargeState state = system.stateOf(sender);
        int level = system.minerLevel(sender);
        long now = sender.serverLevel().getGameTime();

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        result.addProperty("charge", state.currentCharge());
        result.addProperty("chargeMax", MinerSkills.chainChargePool(level));
        // 布尔独立成字段而不是塞进 passives 伪装成 0/1: 它是里程碑开关不是数值, 混进数值表前端还得反解量纲。
        result.addProperty("miningFatigueImmune", MinerSkills.immuneToMiningFatigue(level));

        JsonArray toggles = new JsonArray();
        toggles.add(toggleJson(state, MinerSkill.CHAIN, MinerSkills.chainUnlocked(level)));
        toggles.add(toggleJson(state, MinerSkill.AUTO_COLLECT, MinerSkills.autoCollectUnlocked(level)));
        toggles.add(toggleJson(state, MinerSkill.AUTO_SMELT, MinerSkills.autoSmeltBaseUnlocked(level)));
        result.add("toggles", toggles);

        result.addProperty("scanUnlockLevel", MinerConstants.ORE_SCAN_UNLOCK_LEVEL);
        result.addProperty("scanUnlocked", MinerSkills.oreScanUnlocked(level));
        // 未解锁时半径真的是 0 (MinerSkills.oreScanRadius 的未解锁分支), 不是缺省填充; 前端不得放大它。
        result.addProperty("scanRadius", MinerSkills.oreScanRadius(level));
        result.addProperty("scanCooldownRemainingTicks", scanCooldownRemainingTicks(state, now));

        JsonArray passives = new JsonArray();
        passives.add(statLine("dig_speed", "multiplier", MinerSkills.digSpeedMultiplier(level)));
        passives.add(statLine("durability_save", "percent", MinerSkills.durabilitySaveChance(level)));
        passives.add(statLine("fortune_extra", "flat", MinerSkills.fortuneExtraExpectancy(level)));
        passives.add(statLine("danger_time_factor", "multiplier", MinerSkills.dangerTimeFactor(level)));
        passives.add(statLine("trap_damage_reduction", "percent", MinerSkills.trapDamageReduction(level)));
        passives.add(statLine("chain_refill_full", "ticks", MinerSkills.chainRefillFullTicks(level)));
        result.add("passives", passives);

        return GSON.toJson(result);
    };

    // ============================================================
    // job.miner.scan: {} -> 一次探矿的命中坐标
    // ============================================================

    /**
     * 探矿 (写操作: 会烧掉一次 CD)。
     *
     * 防 X 光的四条硬约束逐条由被复用的服务端裁决链保证, 本层只做 JSON 化, 顺序与 {@link MinerActions} 的
     * tryOreScan 逐字一致, 一步不许重排或跳过:
     *  1. 等级门 {@link MinerSkills#oreScanUnlocked};
     *  2. CD 门 {@link MinerChargeState#cooldownReady};
     *  3. {@link OreScanService#scanDetailed} (矿洞 region 门 + 半径门 + 单矿种 + 64 条硬顶);
     *  4. {@link MinerChargeState#startCooldown}。
     *
     * 入参刻意为空 (不收 oreItemId): 服务端按硬编码优先序自选第一个有命中的矿种, 给玩家开"指定矿种"等于新开
     * 一个信息泄露面。
     *
     * 无命中同样烧 CD, 与键位路径同行为 —— 让"探空"免费重试就等于把 CD 变成"探到为止"。
     */
    static final WebUiAction SCAN = (sender, payload) -> {
        MinerSystem system = MinerSystem.get();
        MinerChargeState state = system.stateOf(sender);
        int level = system.minerLevel(sender);
        long now = sender.serverLevel().getGameTime();

        if (!MinerSkills.oreScanUnlocked(level)) {
            throw new WebUiBusinessException(WebUiErrorCodes.SKILL_LOCKED,
                    "矿物探测需要矿工 " + MinerConstants.ORE_SCAN_UNLOCK_LEVEL + " 级", false,
                    Map.of("skill", SKILL_ORE_SCAN,
                            "requiredLevel", Integer.toString(MinerConstants.ORE_SCAN_UNLOCK_LEVEL),
                            "currentLevel", Integer.toString(level)));
        }
        if (!state.cooldownReady(MinerSkill.ORE_SCAN, now)) {
            long remaining = state.cooldownReadyAt(MinerSkill.ORE_SCAN) - now;
            throw new WebUiBusinessException(WebUiErrorCodes.SKILL_ON_COOLDOWN,
                    "矿物探测冷却中, 还需 " + remaining + " tick", false,
                    Map.of("skill", SKILL_ORE_SCAN, "remainingTicks", Long.toString(remaining)));
        }

        OreScanService.ScanHit hit = OreScanService.scanDetailed(sender, level);
        int cooldownTicks = MinerSkills.oreScanCooldownTicks(level);
        state.startCooldown(MinerSkill.ORE_SCAN, now, cooldownTicks);

        JsonObject result = new JsonObject();
        if (hit.ore() == null) {
            // 无命中: 两个展示字段发真 null 而不是空串 —— 空串会被前端渲染成一个没有名字的矿种行。
            result.add("oreItemId", JsonNull.INSTANCE);
            result.add("oreDescriptionId", JsonNull.INSTANCE);
        } else {
            Item ore = hit.ore().representativeItem();
            result.addProperty("oreItemId", ForgeRegistries.ITEMS.getKey(ore).toString());
            result.addProperty("oreDescriptionId", ore.getDescriptionId());
        }

        JsonArray hits = new JsonArray();
        for (BlockPos pos : hit.positions()) {
            JsonObject p = new JsonObject();
            p.addProperty("x", pos.getX());
            p.addProperty("y", pos.getY());
            p.addProperty("z", pos.getZ());
            hits.add(p);
        }
        result.add("hits", hits);
        result.addProperty("radius", MinerSkills.oreScanRadius(level));
        result.addProperty("pulseTicks", MinerConstants.SCAN_PULSE_TICKS);
        // 刚起的 CD 全长即此刻剩余量 (同一 tick 内 readyAt - now 恒等于 cooldownTicks)。
        result.addProperty("scanCooldownRemainingTicks", cooldownTicks);
        return GSON.toJson(result);
    };

    // ============================================================
    // JSON helper
    // ============================================================

    /** 一个开关位。skillId 与 lang 键 {@code skill.miningdim.miner.<skillId>} 同源, 不另造一套命名。 */
    private static JsonObject toggleJson(MinerChargeState state, MinerSkill skill, boolean unlocked) {
        JsonObject json = new JsonObject();
        json.addProperty("skillId", skill.name().toLowerCase());
        json.addProperty("unlocked", unlocked);
        json.addProperty("enabled", state.toggled(skill));
        return json;
    }

    /**
     * 一行被动数值。服务端只发键与量纲, 中文由前端 client.i18n 解 (专用服务端不加载 lang)。
     * value 原样发未经四舍五入的裁决值 —— 展示位数是前端的事, 服务端提前取整会让面板永远看不到真实曲线。
     */
    private static JsonObject statLine(String key, String unit, double value) {
        JsonObject json = new JsonObject();
        json.addProperty("key", key);
        json.addProperty("labelKey", "stat.miningdim.miner." + key);
        json.addProperty("value", value);
        json.addProperty("unit", unit);
        return json;
    }

    /**
     * 探矿 CD 剩余 tick (0 = 已就绪)。
     *
     * 必须先判 {@link MinerChargeState#cooldownReady} 再做减法: 从未用过该技能时 cooldownReadyAt 返回
     * {@link Long#MIN_VALUE}, 直接 {@code readyAt - now} 会整数下溢成一个巨大的正数, 面板会显示"冷却中数亿秒"。
     */
    private static long scanCooldownRemainingTicks(MinerChargeState state, long now) {
        if (state.cooldownReady(MinerSkill.ORE_SCAN, now)) {
            return 0L;
        }
        return state.cooldownReadyAt(MinerSkill.ORE_SCAN) - now;
    }
}
