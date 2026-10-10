package com.miningdim.job.chef.station;

import com.miningdim.job.chef.station.CookingStationBlock.Kind;
import com.miningdim.job.chef.station.CookingStationBlock.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.function.ToIntFunction;

/**
 * 九台烹饪台的构造工厂, 供 {@link com.miningdim.job.chef.ChefBlocks} 注册时调用。
 *
 * 每台的形状、遮挡 (noOcclusion)、光照与粒子逐条照 station-preview 方案 meta.json 的实装说明; 各方案的要求
 * 彼此不同, 不要"顺手统一":
 *  - 炸锅 C 中式: 明确要求不调 noOcclusion, 否则灶身侧墙、底面贴边面的 cullface 失效;
 *  - 炸锅 A 田园: 说明未要求, 照农夫乐事厨锅 (同为坐在炉灶上的锅) 不调;
 *  - 其余七台: 必须 noOcclusion, 模型不是实心整格, 不加的话贴着它的邻居面会被剔除、内部的面会发黑。
 * 环境光遮蔽 (AO) 由模型的 ambientocclusion 决定 (C 家族三台都关), 方块代码不另作处理。
 * 碰撞箱一律不做严格整格: 1.20.1 遇到整格碰撞时模型所有轴对齐的面都去取邻格的光, 台面摆件、内凹面会在
 * 旁边是不透光整格时发黑。需要整格观感的台子 (木质备餐台、中式案台) 选择框仍是整格, 碰撞箱并上一块摆件。
 * 方块都不要求"正确工具才掉落": 台子造价不低, 徒手拆也该拿回来; 挖掘工具标签只决定挖掘速度。
 */
public final class CookingStations {

    private CookingStations() {
    }

    // ---- 炸锅 ----

    /** 炉上油锅: 无自带光源 (坐在炉灶上, 光由炉灶出); 体量与材质照农夫乐事厨锅。 */
    public static Block deepFryerRustic() {
        return new StovetopFryerBlock(castIronPot(), StationParticles.STOVETOP_FRYER);
    }

    /**
     * 商用炸炉: 自带加热。形状必须是非整格的"台面 + 背板" (整格会让内凹面取邻格光照而发暗), 必须 noOcclusion。
     * 方案说明未给发光等级, 指示灯只是 shade:false 的全亮面, 方块本身不发光。
     */
    public static Block deepFryerSteel() {
        return new CookingStationBlock(metal().noOcclusion(), Kind.DEEP_FRYER, Style.STEEL,
                StationShape.rotated(Shapes.or(
                        Block.box(0.0D, 0.0D, 0.0D, 16.0D, 11.0D, 16.0D),
                        Block.box(0.0D, 11.0D, 12.0D, 16.0D, 16.0D, 16.0D))),
                StationParticles.COMMERCIAL_FRYER);
    }

    /**
     * 中式炸灶: 自带灶火, 不认下方热源。形状"灶身 + 铁锅"前后左右对称; 不调 noOcclusion (见类注释)。
     * 光照工作中 13 (同农夫乐事炉灶点火)、待机 3 (余火)。
     */
    public static Block deepFryerChinese() {
        return new CookingStationBlock(brick(MapColor.COLOR_RED).lightLevel(litLight(13, 3)),
                Kind.DEEP_FRYER, Style.CHINESE,
                StationShape.symmetric(Shapes.or(
                        Block.box(0.0D, 0.0D, 0.0D, 16.0D, 10.0D, 16.0D),
                        Block.box(2.0D, 10.0D, 2.0D, 14.0D, 15.0D, 14.0D))),
                StationParticles.CHINESE_FRYER);
    }

    // ---- 烤炉 ----

    /**
     * 砖砌烤炉: 形状是挖空炉口的整格 (照原版炼药锅), 选择框与碰撞箱相同。整格碰撞箱会让模型每个面都去取
     * 邻格光照, 炉膛顶棚、拱角底面取到地面的 0 级光而全黑; 挖空后不贴边界的面取本格光照。烟囱、搁板、
     * 木铲靠模型 cullface 剔除, 不并进形状。光照工作中 13、待机 0。烟囱高出方块顶 4 像素, 上方放非整格方块
     * (台阶、玻璃等不剔除它的) 会穿模, 物品提示里提醒上方留空。
     */
    public static Block bakingOvenRustic() {
        return new CookingStationBlock(brick(MapColor.COLOR_RED).noOcclusion().lightLevel(litLight(13, 0)),
                Kind.BAKING_OVEN, Style.RUSTIC,
                StationShape.rotated(Shapes.join(Shapes.block(),
                        Block.box(4.0D, 5.0D, 0.0D, 12.0D, 12.0D, 11.0D), BooleanOp.ONLY_FIRST)),
                StationParticles.BRICK_OVEN, true);
    }

    /**
     * 铸铁烤箱灶: noOcclusion 并且不挡视线; 选择框与碰撞箱都是"灶面 y12 + 背板 z13..16", 门把手不进任何形状。
     * 说明给的发光区间是工作中 10-13, 取上沿 13, 与原版熔炉、农夫乐事炉灶点火一致。
     *
     * 与方案说明不同: 说明写的是"碰撞箱整格"。但 1.20.1 只要碰撞箱是严格整格, 模型每个轴对齐的面都去取朝向那
     * 一侧邻格的光 (带不带 AO 两条渲染路径都一样): 炉架两端 (x1/x15)、四片朝内的炉架内壁 (取到对侧邻格)、旋钮
     * 和指示灯的侧面、支脚缝里看得见的柜底都会在旁边是不透光整格 (农夫乐事炉灶、橱柜) 时取到 0 级光而发黑,
     * 而方案的标准摆法正是和炉灶接成一排。同一产品线的商用炸炉, 方案说明本身就因为"整格会让内凹面发暗"
     * 要求用非整格形状, 所以这里照炸炉的做法让碰撞箱等于选择框。要改回说明的整格, 改成 StationShape.rotated(这个形状, Shapes.block())。
     */
    public static Block bakingOvenSteel() {
        return new CookingStationBlock(metal().noOcclusion().isViewBlocking(CookingStations::never)
                        .lightLevel(litLight(13, 0)),
                Kind.BAKING_OVEN, Style.STEEL,
                StationShape.rotated(Shapes.or(
                        Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D),
                        Block.box(0.0D, 12.0D, 13.0D, 16.0D, 16.0D, 16.0D))),
                StationParticles.CAST_IRON_RANGE);
    }

    /**
     * 吊炉: noOcclusion (倒角和台基外圈不能让邻居面被剔掉); 形状是台基 + 炉身 + 两级炉肩 + 烟口的阶梯,
     * 中心对称, 挑杆、果木不进形状。光照工作中 13、待机 3 (余火, 与炸锅 C 同口径)。主体是黄泥, 用泥砖的音效。
     */
    public static Block bakingOvenChinese() {
        return new CookingStationBlock(brick(MapColor.TERRACOTTA_YELLOW).sound(SoundType.PACKED_MUD).noOcclusion()
                .lightLevel(litLight(13, 3)),
                Kind.BAKING_OVEN, Style.CHINESE,
                StationShape.symmetric(Shapes.or(
                        Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D),
                        Block.box(1.0D, 2.0D, 1.0D, 15.0D, 12.0D, 15.0D),
                        Block.box(2.0D, 12.0D, 2.0D, 14.0D, 13.0D, 14.0D),
                        Block.box(4.0D, 13.0D, 4.0D, 12.0D, 14.0D, 12.0D),
                        Block.box(6.0D, 14.0D, 6.0D, 10.0D, 16.0D, 10.0D))),
                StationParticles.HANGING_OVEN);
    }

    // ---- 备餐台 ----

    /**
     * 木质备餐台: noOcclusion (旋转过的菜刀面取的是本格光照, 不透光整格那里是 0 级光)。选择框整格; 碰撞箱用
     * 方案说明写好的退路: 整格再并上后沿调料架 [1,16,11]-[15,21,15], 让它不再是严格整格。
     *
     * 说明原本要"实测发黑再改", 但这一步可以直接从 1.20.1 的代码推出来: 碰撞箱严格整格时, 模型所有轴对齐的面
     * 一律取朝向那一侧邻格的光, 砧板东面、盘子西面、调料架和瓶子的侧面在旁边贴着农夫乐事炉灶、橱柜 (说明里的
     * 标准摆法) 时取到 0 级光。说明里配套的"重写 propagatesSkylightDown 返回 false"不用另写: 原版默认实现看的
     * 是选择框, 选择框仍是整格, 结果本来就是 false。台顶承重面的切片仍是整面, 火把、红石照常能放。
     * 摆件高出方块顶 5 像素, 物品提示里提醒上方留空。
     */
    public static Block prepCounterRustic() {
        return new CookingStationBlock(wood().noOcclusion(), Kind.PREP_COUNTER, Style.RUSTIC,
                StationShape.rotated(Shapes.block(),
                        Shapes.or(Shapes.block(), Block.box(1.0D, 16.0D, 11.0D, 15.0D, 21.0D, 15.0D))),
                StationParticles.NONE, true);
    }

    /**
     * 冷藏备餐台: noOcclusion, 不挡视线、不使实体窒息 (支脚缝、砧板上方、格栅都是空的)。选择框用"前半台面
     * y12 + 后半冷藏槽到顶", 说明允许碰撞箱用同一组形状, 这样贴不到边界的内凹面取本格光照。
     * 工作中翻开的掀盖会伸进上方一格, 上方有东西时换"掀盖合着"的模型, 见 {@link RefrigeratedPrepBlock}。
     */
    public static Block prepCounterSteel() {
        return new RefrigeratedPrepBlock(metal().noOcclusion()
                        .isViewBlocking(CookingStations::never)
                        .isSuffocating(CookingStations::never),
                StationShape.rotated(Shapes.or(
                        Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D),
                        Block.box(0.0D, 12.0D, 7.0D, 16.0D, 16.0D, 16.0D))),
                StationParticles.REFRIGERATED_PREP);
    }

    /**
     * 中式案台: noOcclusion (缩进的对开门和台面摆件不能按满方块取 0 级光)。选择框按说明的整格; 碰撞箱与木质
     * 备餐台同理不能是严格整格 (这台关了 AO, 取到 0 级光的面会直接全黑), 整格再并上东侧后方的蒸笼
     * [9,16,9]-[15,22,15] (待机高度)。摆件最高到 y23.8, 物品提示里提醒上方留空。
     */
    public static Block prepCounterChinese() {
        return new CookingStationBlock(brick(MapColor.COLOR_RED).noOcclusion(), Kind.PREP_COUNTER, Style.CHINESE,
                StationShape.rotated(Shapes.block(),
                        Shapes.or(Shapes.block(), Block.box(9.0D, 16.0D, 9.0D, 15.0D, 22.0D, 15.0D))),
                StationParticles.CHINESE_PREP, true);
    }

    // ---- 材质 ----
    // 台子是家具, 四种材质都不让怪物在台面上生成 (原版工作台会, 这里不照搬: 怪物会和台面摆件叠在一起)。

    /** 铸铁锅: 与农夫乐事厨锅相同的强度与音效。 */
    private static BlockBehaviour.Properties castIronPot() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.5F, 6.0F).sound(SoundType.LANTERN)
                .isValidSpawn(CookingStations::noSpawns);
    }

    /** 不锈钢 / 铸铁灶具: 硬度与抗爆同原版熔炉 (3.5 / 3.5), 音符盒音色同铁块。 */
    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                .strength(3.5F).sound(SoundType.METAL).isValidSpawn(CookingStations::noSpawns);
    }

    /** 砖砌 / 黄泥灶具: 强度同原版红砖块。 */
    private static BlockBehaviour.Properties brick(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASEDRUM)
                .strength(2.0F, 6.0F).sound(SoundType.STONE).isValidSpawn(CookingStations::noSpawns);
    }

    /** 木质柜台: 强度、音效同原版工作台。 */
    private static BlockBehaviour.Properties wood() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS)
                .strength(2.5F).sound(SoundType.WOOD).ignitedByLava().isValidSpawn(CookingStations::noSpawns);
    }

    private static ToIntFunction<BlockState> litLight(int lit, int idle) {
        return state -> state.getValue(CookingStationBlock.LIT) ? lit : idle;
    }

    private static boolean never(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    private static boolean noSpawns(BlockState state, BlockGetter level, BlockPos pos, EntityType<?> type) {
        return false;
    }
}
