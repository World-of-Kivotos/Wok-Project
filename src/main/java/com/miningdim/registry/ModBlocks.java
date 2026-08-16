package com.miningdim.registry;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.entrance.EntranceBlock;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块的 DeferredRegister holder (设计文档 5.2)。在 mod 构造期声明, Forge 在 RegisterEvent(BLOCKS) 注入。
 *
 * 阶段0 真实集合:
 *  - mining_portal: 进入矿山的传送门触发方块 (玩家右键触发 enter 流程, 行为由阶段后续子系统在事件层接线)。
 *  - fake_ore:      陷阱系统用的假矿石占位块 (外观似矿石、挖掘行为异常), 见第九章 TrapSystem。
 *
 * 阶段1 新增 (R4 入口方块): 三个难度入口方块 (Easy/Medium/Hard), 右键/踩踏触发对应难度区域进入,
 * 携带浮空字方块实体 (见 entrance 包 EntranceBlock/EntranceBlockEntity)。属性 copy 原版 LODESTONE
 * (不可被活塞推动、坚固) 作合理默认, 不需新 PNG, 模型 JSON 引用现有 vanilla 纹理。
 */
public final class ModBlocks {

    private ModBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    /** 矿山传送门触发方块。 */
    public static final RegistryObject<Block> MINING_PORTAL =
            BLOCKS.register("mining_portal",
                    () -> new Block(BlockBehaviour.Properties.copy(Blocks.OBSIDIAN)));

    /** 假矿石占位块 (陷阱用)。 */
    public static final RegistryObject<Block> FAKE_ORE =
            BLOCKS.register("fake_ore",
                    () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE)));

    /**
     * 静态陷阱触发方块 (方案 C, vanilla-noise datapack 布点)。单块经 KIND 属性承载 4 个静态陷阱种类, 由四个
     * {@code minecraft:ore} 型 configured_feature 各自指定一种在矿洞石层散布; 挖到时 {@link com.miningdim.trap.StaticTrapTrigger}
     * 按 KIND 分发爆炸/岩浆/落石。noLootTable: 挖到即触发, 不进背包 (伪装块不作为物品存在, 故不注册 BlockItem/创造栏)。
     */
    public static final RegistryObject<Block> TRAP_ORE =
            BLOCKS.register("trap_ore",
                    () -> new TrapOreBlock(BlockBehaviour.Properties.copy(Blocks.STONE).noLootTable()));

    // ---- R4 难度入口方块 (entrance 子系统) ----

    /** Easy 难度入口方块。 */
    public static final RegistryObject<Block> ENTRANCE_EASY =
            BLOCKS.register("entrance_easy",
                    () -> new EntranceBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE), Difficulty.EASY));

    /** Medium 难度入口方块。 */
    public static final RegistryObject<Block> ENTRANCE_MEDIUM =
            BLOCKS.register("entrance_medium",
                    () -> new EntranceBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE), Difficulty.MEDIUM));

    /** Hard 难度入口方块。 */
    public static final RegistryObject<Block> ENTRANCE_HARD =
            BLOCKS.register("entrance_hard",
                    () -> new EntranceBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE), Difficulty.HARD));

    /** 由子系统入口在 mod 构造期调用一次 (经 ModRegistration)。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
