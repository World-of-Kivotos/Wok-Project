package com.miningdim.worldgen;

import com.miningdim.core.BaseMaterial;
import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.VoxelOccupancy;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 自定义 ChunkGenerator (设计文档 5.3 / 7.8)。唯一职责: 把世界坐标映射到 region 本地坐标, 查冻结体素,
 * 填 air 或 solid。绝不在任何回调里跑跨区块算法 (7.8.1 / D2)。
 *
 * Codec (1.20.1, 非 MapCodec): 元素类型 Codec<? extends ChunkGenerator>, 经 RegisterEvent 注册到
 * Registries.CHUNK_GENERATOR; JSON 结构对齐 dimension/mining.json 的 generator 段:
 *   biome_source (内嵌 BiomeSource, 走 BiomeSource.CODEC) + settings (region 几何)。
 *
 * 体素来源: 经 core.IInstanceManager.regionAt(worldX,worldZ) 拿 region (InstanceState), 再经
 * worldgen 的 MiningVoxelLookup.resolve(instanceId) 取冻结体素 (阶段2 由集成层把 instance 的 voxelsOf
 * 接入该 seam, 见 MiningVoxelLookup 注释)。region 外/未就绪/无体素 -> 填实心墙 (7.7.1)。
 *
 * 基材 (R3, 修旧 bug): 填实心时按所在实例的难度调色板 (Difficulty.palette) + 局部坐标确定性选基材令牌,
 * 与绝对 worldY 无关 (旧 bug: 按 worldY<0 选深板岩, 使 Medium 在 y0 以上整段变纯石头)。整块 384 高都按该
 * 难度填: Easy 石头(偶尔安山/闪长)、Medium 石头深板岩混合(越深深板岩越多)、Hard 深板岩为主(偶尔凝灰/黑石)。
 * 令牌 -> BlockState 的映射是 worldgen 职责 (core 只产 BaseMaterial 令牌, 满足模块化铁律)。
 * region 外/未就绪的 SOLID 兜底列无实例难度可取, 退化为纯石头墙 (玩家不可达, 仅需一个合法实心)。
 */
public final class MiningChunkGenerator extends ChunkGenerator {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** BaseMaterial 令牌 -> vanilla BlockState 的映射 (R3; core 只产令牌, 此处落地为方块)。 */
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DIORITE = Blocks.DIORITE.defaultBlockState();
    private static final BlockState TUFF = Blocks.TUFF.defaultBlockState();
    private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();

    /** 令牌 -> BlockState 落地。集中一处, 保证 fillFromNoise/getBaseColumn 两路查表一致。 */
    private static BlockState blockFor(BaseMaterial material) {
        return switch (material) {
            case STONE -> STONE;
            case DEEPSLATE -> DEEPSLATE;
            case ANDESITE -> ANDESITE;
            case DIORITE -> DIORITE;
            case TUFF -> TUFF;
            case BLACKSTONE -> BLACKSTONE;
        };
    }

    /**
     * region 几何参数 (dimension/mining.json 的 generator.settings 段)。字段名与 JSON 键一一对应:
     * region_size_x / region_size_z / region_gap / min_y / height。这些是 datapack 权威值 (4.4),
     * 运行时几何以 InstanceState.regionBox 为准 (实例分配时定); 本 settings 主要供 codec 往返与
     * getMinY/getGenDepth 等高度查询使用。
     */
    public record Settings(int regionSizeX, int regionSizeZ, int regionGap, int minY, int height) {
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.fieldOf("region_size_x").forGetter(Settings::regionSizeX),
                Codec.INT.fieldOf("region_size_z").forGetter(Settings::regionSizeZ),
                Codec.INT.fieldOf("region_gap").forGetter(Settings::regionGap),
                Codec.INT.fieldOf("min_y").forGetter(Settings::minY),
                Codec.INT.fieldOf("height").forGetter(Settings::height)
        ).apply(inst, Settings::new));
    }

    public static final Codec<MiningChunkGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            Settings.CODEC.fieldOf("settings").forGetter(g -> g.settings)
    ).apply(inst, inst.stable(MiningChunkGenerator::new)));

    private final Settings settings;

    public MiningChunkGenerator(BiomeSource biomeSource, Settings settings) {
        super(biomeSource);
        this.settings = settings;
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ---- 核心落方块: 纯查表 (7.8.2 / 7.8.3) ----

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender,
                                                        RandomState randomState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int minBuild = chunk.getMinBuildHeight();
        int maxBuild = chunk.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkMinX + lx;
                int wz = chunkMinZ + lz;
                ColumnSource col = columnSourceAt(wx, wz);
                for (int wy = minBuild; wy < maxBuild; wy++) {
                    BlockState state = col.stateAt(wx, wy, wz);
                    if (state != AIR) {
                        // ProtoChunk 默认全空气, 只需写实心, 省一半 setBlockState 调用。
                        pos.set(wx, wy, wz);
                        chunk.setBlockState(pos, state, false);
                    }
                }
            }
        }
        // 查表无需异步, 同步完成即可 (7.8.2)。
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        // 与 fillFromNoise 同源查表, 保证一致 (7.8.2)。
        int minBuild = height.getMinBuildHeight();
        int count = height.getHeight();
        BlockState[] column = new BlockState[count];
        ColumnSource col = columnSourceAt(x, z);
        for (int i = 0; i < count; i++) {
            int wy = minBuild + i;
            column[i] = col.stateAt(x, wy, z);
        }
        return new NoiseColumn(minBuild, column);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState randomState) {
        // 该列最高实心面的世界 Y + 1 (原版 getBaseHeight 语义: 第一个非阻挡高度)。
        ColumnSource col = columnSourceAt(x, z);
        int minBuild = height.getMinBuildHeight();
        int maxBuild = height.getMaxBuildHeight();
        for (int wy = maxBuild - 1; wy >= minBuild; wy--) {
            if (col.stateAt(x, wy, z) != AIR) {
                return wy + 1;
            }
        }
        return minBuild;
    }

    /**
     * 为单列解析体素来源: region 外/未就绪/无体素 -> 全实心列 (无难度, 纯石头兜底); 否则绑定该实例
     * 体素 + regionBox + seed + 难度调色板 (R3 基材按难度)。每列解析一次, 列内逐 y 复用
     * (避免每格重复 regionAt 线性扫描, 也避免每格重取 InstanceState)。
     */
    private ColumnSource columnSourceAt(int worldX, int worldZ) {
        InstanceState inst = MiningServices.instanceManager().regionAt(worldX, worldZ);
        if (inst == null || !inst.genState().isEnterable()) {
            return ColumnSource.SOLID;
        }
        VoxelOccupancy voxels = MiningVoxelLookup.resolve(inst.instanceId());
        if (voxels == null) {
            return ColumnSource.SOLID;
        }
        return new ColumnSource(inst.regionBox(), voxels, inst.seed(), inst.difficulty());
    }

    /**
     * 单列体素查表器。null voxels (SOLID 单例) 表示该列恒实心且无实例难度 (region 外/未就绪),
     * 退化为纯石头墙。否则按难度调色板 (R3) 逐格确定性选基材令牌再落地为 BlockState。
     */
    private static final class ColumnSource {
        static final ColumnSource SOLID = new ColumnSource(null, null, 0L, null);

        private final RegionBox box;
        private final VoxelOccupancy voxels;
        private final long seed;
        private final Difficulty difficulty;

        ColumnSource(RegionBox box, VoxelOccupancy voxels, long seed, Difficulty difficulty) {
            this.box = box;
            this.voxels = voxels;
            this.seed = seed;
            this.difficulty = difficulty;
        }

        BlockState stateAt(int wx, int wy, int wz) {
            if (voxels == null || !box.containsWorld(wx, wy, wz)) {
                // box 外 (含封顶封底/缓冲带) 或恒实心列 -> 填实心 (6.2)。
                return solidAt(wx, wy, wz);
            }
            int idx = box.worldVoxelIndex(wx, wy, wz);
            return voxels.isAir(idx) ? AIR : solidAt(wx, wy, wz);
        }

        /**
         * 实心格基材 (R3): 有实例难度时走调色板 (与绝对 Y 无关, 整块同一难度, Medium 越深越多深板岩);
         * 无难度的 SOLID 兜底列退化为纯石头 (玩家不可达, 仅需合法实心)。
         */
        private BlockState solidAt(int wx, int wy, int wz) {
            if (difficulty == null) {
                return STONE;
            }
            int localY = box.worldToLocalY(wy);
            BaseMaterial material = difficulty.palette()
                    .select(seed, wx, wy, wz, localY, MiningConstants.REGION_HEIGHT);
            return blockFor(material);
        }
    }

    // ---- no-op / 高度参数 (7.8.2) ----

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // no-op: 雕刻已在离线 NoiseCarving 阶段完成, 禁止原版 carver 二次破坏拓扑 (7.8.2)。
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        // no-op: 矿洞无地表概念, 不调用 SurfaceRules (7.8.2)。
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // no-op: 自然刷怪由 biome spawners 基线 + 第十章压力系统接管, 不在区块生成期刷 (6.4)。
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("MiningDim: lookup-table chunk generator (offline voxels)");
    }

    @Override
    public int getGenDepth() {
        // 维度可建造垂直格数 = height (与 dimension_type JSON height 一致)。
        return settings.height();
    }

    @Override
    public int getMinY() {
        return settings.minY();
    }

    @Override
    public int getSeaLevel() {
        // 无海: 返回 region 底 (7.8.2)。
        return settings.minY();
    }
}
