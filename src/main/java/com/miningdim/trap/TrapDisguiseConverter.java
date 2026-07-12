package com.miningdim.trap;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.registry.ModBlocks;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态陷阱协议级伪装落地器 (用户裁决: 世界里不允许存在可被客户端识破的 trap_ore 方块)。订阅
 * {@link ChunkEvent.Load}: 矿洞维度区块加载时, 把 datapack {@code minecraft:ore} feature 布下的
 * {@link TrapOreBlock} 就地换成一块真原版矿石 ({@link TrapDisguise} 按难度矿池随机选皮), 陷阱身份 (坐标+种类)
 * 登记进 {@link TrapRegistry}。此刻区块尚未发给任何客户端, 直接改 section 方块态即可 (伪装块与 trap_ore 皆普通实心,
 * 光照/heightmap 一致, 无需通知)。
 *
 * 性能 (已转换区块重载近零开销): 每 section 先 {@link LevelChunkSection#maybeHas} palette 预检 trap_ore, 无命中即跳过 ——
 * 转换后该 section palette 已无 trap_ore, 重载时预检直接返回 false。转换天然幂等: 已转换区块扫不到 trap_ore, 注册表不动。
 *
 * 线程/时序: 事件在服务端主线程的区块加载路径触发。{@link ChunkEvent.Load} javadoc 警告"可能在 chunk 升为 FULL 前触发,
 * 触碰其它区块会致加载死锁" —— 故本器只读写事件自身的 ChunkAccess (section 直改 + 同区块内垂直邻格采样), 绝不触碰邻区块。
 */
public final class TrapDisguiseConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/trap/disguise");

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return; // 客户端区块加载不承载权威陷阱身份 (服务端权威)。
        }
        if (!level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return; // 陷阱只在矿洞维度; 其它维度区块无 trap_ore, 提前短路。
        }
        ChunkAccess chunk = event.getChunk();
        // 难度按区块几何解析 (region 与区块对齐, 整块单一难度); 缓冲带/网格外 (null) 无 feature 布点, 无需转换。
        int minBlockX = chunk.getPos().getMinBlockX();
        int minBlockZ = chunk.getPos().getMinBlockZ();
        Difficulty difficulty = Difficulty.forBlock(minBlockX, minBlockZ);
        if (difficulty == null) {
            return;
        }
        convertChunk(level, chunk, difficulty);
    }

    /**
     * 扫本区块全部 trap_ore 换伪装矿并登记。每 section 先 palette 预检; 命中 section 内逐格换皮 + 登记。
     * 有实际转换才 {@code setUnsaved(true)} (伪装落盘, 下次加载预检即跳过, 保证幂等且不重复随机化)。
     */
    private void convertChunk(ServerLevel level, ChunkAccess chunk, Difficulty difficulty) {
        TrapOreBlock trapOreBlock = (TrapOreBlock) ModBlocks.TRAP_ORE.get();
        LevelChunkSection[] sections = chunk.getSections();
        TrapRegistry registry = TrapRegistry.get(level);
        RandomSource random = level.getRandom();
        int minBlockX = chunk.getPos().getMinBlockX();
        int minBlockZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int converted = 0;

        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(trapOreBlock))) {
                continue; // 空 section / 无 trap_ore: palette 预检跳过 (已转换区块的近零开销来源)。
            }
            int sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!(state.getBlock() instanceof TrapOreBlock)) {
                            continue;
                        }
                        cursor.set(minBlockX + localX, sectionBaseY + localY, minBlockZ + localZ);
                        StaticTrapKind kind = state.getValue(TrapOreBlock.KIND);
                        boolean deepslate = deepslateContext(chunk, cursor, difficulty);
                        BlockState skin = TrapDisguise.pickSkin(difficulty, deepslate, random);
                        section.setBlockState(localX, localY, localZ, skin);
                        registry.put(cursor.immutable(), kind);
                        converted++;
                    }
                }
            }
        }
        if (converted > 0) {
            chunk.setUnsaved(true); // 伪装落盘: 下次加载 palette 预检即跳过, 保证幂等且不重复随机化。
            LOGGER.debug("[miningdim] disguised {} trap ore(s) in chunk {} ({})",
                    converted, chunk.getPos(), difficulty.configName());
        }
    }

    /**
     * 该陷阱位是否处深板岩上下文 (决定伪装矿石取石头变体还是深板岩变体)。优先采样同区块内垂直邻格 (下、上) 的
     * 真实地面方块取地面真值 —— 对齐原版 surface_rule 的实际落点 (含 medium 24~40 梯度带的逐位随机结果);
     * 邻格采样不定 (空气暴露/越界) 才回退 {@link TrapDisguise#deepslateByModel} 的 y 分层模型。只读同区块方块,
     * 不触碰邻区块 (守 ChunkEvent.Load 的加载死锁红线)。
     */
    private boolean deepslateContext(ChunkAccess chunk, BlockPos pos, Difficulty difficulty) {
        for (int dy : new int[]{-1, 1}) {
            int y = pos.getY() + dy;
            if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
                continue; // 越出建筑高度: 无同区块邻格可采。
            }
            BlockState neighbor = chunk.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
            if (TrapDisguise.isDeepslateFamily(neighbor)) {
                return true;
            }
            if (TrapDisguise.isStoneFamily(neighbor)) {
                return false;
            }
        }
        return TrapDisguise.deepslateByModel(difficulty, pos.getY());
    }
}
