package com.miningdim.trap;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.MinerSurvival;
import com.miningdim.job.miner.TrapScanService;
import com.miningdim.registry.ModBlocks;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 静态陷阱 (方案 C) 核心契约 GameTest。锁死审计 Critical 的闭合: 静态陷阱现为真实世界方块 (TrapOreBlock),
 * 探测扫真实世界能命中并按等级分级, 触发效果一律走矿脉抗性可识别的原版环境伤类型 (故减伤自动覆盖静态陷阱)。
 *
 * 断言具体业务结果 (删被测逻辑必挂, 禁弱校验): 探测命中数与致死过滤 / 每种陷阱的伤害类型映射与其被 isTrapSource
 * 识别 / KIND<->TrapType 双射与致死分类。纯逻辑/真实 ServerLevel 直读, 不依赖外部 mod。
 * 真实 fuse/落地效果 (爆炸/岩浆/落石的世界写) 属 vanilla 机制, 需 runClient/真服观测, 不在 dev GameTest 断言范围。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TrapGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "trap";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldFindsTrapOreAndFiltersLethal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 四种陷阱各放一格 (2 非致死: 假矿/崩塌; 2 致死: TNT/岩浆袋)。
        setTrap(helper, new BlockPos(0, 1, 0), StaticTrapKind.FAKE_ORE);
        setTrap(helper, new BlockPos(1, 1, 0), StaticTrapKind.COLLAPSING_TUNNEL);
        setTrap(helper, new BlockPos(2, 1, 0), StaticTrapKind.TNT_VEIN);
        setTrap(helper, new BlockPos(3, 1, 0), StaticTrapKind.LAVA_POCKET);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 0));

        // L5-7 (lethalAllowed=false): 只下发 2 个非致死。
        List<BlockPos> nonLethal = TrapScanService.scanWorld(level, center, 8, false);
        helper.assertTrue(nonLethal.size() == 2,
                "L5-7 scan finds only the 2 non-lethal traps (fake_ore + collapsing), got " + nonLethal.size());
        // L8+ (lethalAllowed=true): 4 个全收。
        List<BlockPos> all = TrapScanService.scanWorld(level, center, 8, true);
        helper.assertTrue(all.size() == 4, "L8+ scan finds all 4 static traps, got " + all.size());
        // 半径 0 -> 空 (与 scan 上游门控一致)。
        helper.assertTrue(TrapScanService.scanWorld(level, center, 0, true).isEmpty(), "radius 0 yields no hits");
        // 非陷阱方块 (石头) 不被误判为陷阱。
        helper.setBlock(new BlockPos(5, 1, 0), net.minecraft.world.level.block.Blocks.STONE);
        helper.assertTrue(TrapScanService.scanWorld(level, center, 8, true).size() == 4,
                "plain stone is not counted as a trap (still 4)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapEffectDamageTypesAreRecognizedByVeinResistance(GameTestHelper helper) {
        // 每种陷阱触发效果的伤害类型固定映射 (改触发效果引入战斗向来源即挂)。
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.TNT_VEIN) == DamageTypes.EXPLOSION,
                "TNT_VEIN triggers a (non-player) EXPLOSION");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.FAKE_ORE) == DamageTypes.EXPLOSION,
                "FAKE_ORE triggers a (non-player) EXPLOSION");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.LAVA_POCKET) == DamageTypes.LAVA,
                "LAVA_POCKET triggers LAVA damage");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.COLLAPSING_TUNNEL) == DamageTypes.FALLING_BLOCK,
                "COLLAPSING_TUNNEL triggers FALLING_BLOCK damage");

        // 且每种映射类型都被矿脉抗性识别为陷阱专属来源 -> 减伤 (L5+, 矿洞内) 自动覆盖全部静态陷阱, 闭合审计 Critical。
        for (StaticTrapKind kind : StaticTrapKind.values()) {
            ResourceKey<DamageType> dt = StaticTrapTrigger.damageTypeFor(kind);
            helper.assertTrue(MinerSurvival.isTrapSource(sourceOf(helper, dt)),
                    kind + " effect damage type " + dt.location() + " must be recognized by miner vein resistance");
        }
        // 反向红线: 玩家点燃的 TNT (PLAYER_EXPLOSION) 与近战 (PLAYER_ATTACK) 不得被当陷阱伤软化 (守 PvP attrition)。
        helper.assertFalse(MinerSurvival.isTrapSource(sourceOf(helper, DamageTypes.PLAYER_EXPLOSION)),
                "PLAYER_EXPLOSION is NOT a trap source (PvP red line)");
        helper.assertFalse(MinerSurvival.isTrapSource(sourceOf(helper, DamageTypes.PLAYER_ATTACK)),
                "PLAYER_ATTACK is NOT a trap source (combat red line)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void staticTrapKindMapsToTrapTypeAndLethality(GameTestHelper helper) {
        // KIND <-> TrapType 双射。
        helper.assertTrue(StaticTrapKind.TNT_VEIN.trapType() == TrapType.TNT_VEIN, "TNT_VEIN -> TrapType.TNT_VEIN");
        helper.assertTrue(StaticTrapKind.LAVA_POCKET.trapType() == TrapType.LAVA_POCKET, "LAVA_POCKET -> TrapType.LAVA_POCKET");
        helper.assertTrue(StaticTrapKind.COLLAPSING_TUNNEL.trapType() == TrapType.COLLAPSING_TUNNEL, "COLLAPSING -> TrapType.COLLAPSING_TUNNEL");
        helper.assertTrue(StaticTrapKind.FAKE_ORE.trapType() == TrapType.FAKE_ORE, "FAKE_ORE -> TrapType.FAKE_ORE");
        // 致死分类 (探测按此分级: L5-7 仅非致死 / L8+ 含致死)。
        helper.assertTrue(StaticTrapKind.TNT_VEIN.trapType().lethal(), "TNT_VEIN is lethal");
        helper.assertTrue(StaticTrapKind.LAVA_POCKET.trapType().lethal(), "LAVA_POCKET is lethal");
        helper.assertFalse(StaticTrapKind.FAKE_ORE.trapType().lethal(), "FAKE_ORE is non-lethal");
        helper.assertFalse(StaticTrapKind.COLLAPSING_TUNNEL.trapType().lethal(), "COLLAPSING_TUNNEL is non-lethal");

        // TrapOreBlock KIND 属性对每个 kind 可 setValue/getValue 往返 (布点/触发/探测据此读种类)。
        for (StaticTrapKind k : StaticTrapKind.values()) {
            BlockState st = ModBlocks.TRAP_ORE.get().defaultBlockState().setValue(TrapOreBlock.KIND, k);
            helper.assertTrue(st.getValue(TrapOreBlock.KIND) == k, "TrapOreBlock KIND round-trips for " + k);
        }
        helper.succeed();
    }

    // ---- 测试辅助 ----

    /** 在 helper 世界某相对坐标放一格指定种类的 trap_ore。 */
    private static void setTrap(GameTestHelper helper, BlockPos rel, StaticTrapKind kind) {
        BlockState state = ModBlocks.TRAP_ORE.get().defaultBlockState().setValue(TrapOreBlock.KIND, kind);
        helper.setBlock(rel, state);
    }

    /** 由 DamageType 键构造一个该类型的 DamageSource (供断言 isTrapSource 分类; 从伤害类型注册表取 Holder)。 */
    private static DamageSource sourceOf(GameTestHelper helper, ResourceKey<DamageType> key) {
        return new DamageSource(helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key));
    }
}
