package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.MunitionsBenchAssets;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.block.MunitionsBenchCounter;
import com.miningdim.job.munitions.block.MunitionsBenchGeometry;
import com.miningdim.job.munitions.block.MunitionsBenchLights;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 军火台 (WIDE) 弹药箱计数屏的画字部分 (方案 C, 由 {@link MunitionsBenchRenderer} 在运动件之后调用): 掀开的箱盖内面凹窗里的
 * 满度条与 4x7 大字发数, 以及箱身正面的口径黄漆模板字。画什么 (矩形、颜色角色) 由 {@link MunitionsBenchCounter#rects} 决定,
 * 摆在哪由 {@link MunitionsBenchCounter#blockCorners} 决定 (COUNTER_* 常量 + 台子朝向, 含箱盖的旋转与顶点顺序; GameTest 与
 * tools/munitions_bench/check_parity.mjs 核对的就是它), 这里只上色、交给 BER 的 poseStack。与 counter.mjs 的预览同一套规则:
 * <ul>
 *   <li>姿态: 朝向 rotY (与运动件同一个角) → scale(1/16) (不做运动件那种 y 翻转, 否则绕序会再反一次) → 窗面再绕 x 转箱盖的
 *       22.5°; 箱身正面不转。每个矩形浮在面外 COUNTER_LIFT_PX (1/128 格), 仍在框条前沿之后。都在 blockCorners 里算好。</li>
 *   <li>顶点顺序 = 从正面看 左上 → 左下 → 右下 → 右上 (逆时针, 与原版 FaceInfo.NORTH 相同): textBackground 剔除背面, 反了字就没了。</li>
 *   <li>{@link RenderType#textBackground()} (POSITION_COLOR_LIGHTMAP, 没有贴图, 不按法线打光): 窗里的字用
 *       {@link LightTexture#FULL_BRIGHT}, 颜色原样, 不随台子朝向或昼夜变; 箱身模板字用方块实体的 packedLight, 颜色再乘
 *       {@code level.getShade(朝向, true)}, 与箱身这一面的静态明暗一致, 看上去是漆上去的。不要换成 entity* 渲染类型
 *       (两盏方向光会让竖直面朝东西时的字只剩一半亮度)。</li>
 *   <li>相机离主格中心超过 COUNTER_MAX_DISTANCE_BLOCKS 格不画 (字已经读不出, 也避开远处的深度精度)。LEGACY 台子不走到这里。</li>
 * </ul>
 * 颜色: 工作 (ACTIVE) 时是档位灯色, 待机约为工作的 0.7; 空箱 = 暗色 "0" + 空条; 满仓 (缓冲装不下下一批) 条变琥珀色。
 * <p>
 * 字之后, 同一个 VertexConsumer 里接着画运行灯效 ({@link MunitionsBenchLights}, 按档位: 工位指示灯 / 冲压闪光 / 落箱脉冲 / 满仓提示
 * 全档, 运行呼吸高级起, 皮带追光极品起, 宝石脉冲闪耀): 渲染类型相同不结束批次, 额外 draw call 0; 距离门同上; 朝向变换与字同一个
 * ({@link MunitionsBenchCounter#benchToBlock}); {@link LightTexture#FULL_BRIGHT}, 颜色乘目标面的静态明暗。
 */
final class MunitionsBenchCounterRenderer {

    private static final double MAX_DISTANCE_SQR = (double) MunitionsBenchGeometry.COUNTER_MAX_DISTANCE_BLOCKS
            * MunitionsBenchGeometry.COUNTER_MAX_DISTANCE_BLOCKS;

    /**
     * 每台最近一次排好的矩形与摆好的角: 客户端的计数屏状态只在收到同步包时换 (换的是新对象), 朝向只在方块状态变时变,
     * 两样都没变就原样复用, 每帧不分配。
     */
    private final Map<MunitionsBenchBlockEntity, Cached> cache = new WeakHashMap<>();
    /** 方块 → 档位下标 (颜色表的行), 按注册名查一次。 */
    private final Map<Block, Integer> tiers = new IdentityHashMap<>();
    /**
     * 运行灯效这一帧的覆盖层与摆好的角 / 顶点色: 方块实体在渲染线程上一台一台画, 一份就够, 每帧复用、不分配
     * (灯效随程序时间逐帧变, 不像计数屏那样按状态缓存)。
     */
    private final MunitionsBenchLights.Frame lights = new MunitionsBenchLights.Frame();
    private final float[] lightCorners = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.CORNER_FLOATS];
    private final float[] lightColours = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.COLOUR_FLOATS];

    private record Cached(MunitionsBenchCounter.Shown shown, float yRotation, int[] rects, float[] corners) {
    }

    /**
     * 画计数屏, 再在同一个 textBackground 的 VertexConsumer 里画运行灯效 (渲染类型相同, 不结束批次, 额外 draw call 0)。
     *
     * @param programTicks 程序时间的整 tick (与运动件相同: 游戏 tick − 程序起点; 待机时不用, 负数 = 停在首帧)
     * @param partialTick  渲染的 partialTick
     */
    void render(MunitionsBenchBlockEntity blockEntity, BlockState state, Direction facing, boolean working,
                long programTicks, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                Vec3 cameraPosition) {
        Level level = blockEntity.getLevel();
        if (level == null || cameraPosition == null) {
            return;
        }
        double distanceSqr = blockEntity.getBlockPos().distToCenterSqr(cameraPosition);
        if (distanceSqr >= MAX_DISTANCE_SQR) {
            return;
        }
        float yRotation = MunitionsBenchBlock.partsYRotationDegrees(facing);
        Cached cached = cachedFor(blockEntity, yRotation);
        int tier = tierOf(state.getBlock());
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.textBackground());
        Matrix4f matrix = poseStack.last().pose();
        drawCounter(consumer, matrix, cached, level.getShade(facing, true), tier, working, packedLight);
        drawLights(consumer, matrix, level, tier, working, cached.shown().full(), programTicks, partialTick,
                Math.sqrt(distanceSqr), yRotation);
    }

    private static void drawCounter(VertexConsumer consumer, Matrix4f matrix, Cached cached, float shade, int tier,
                                    boolean working, int packedLight) {
        int[] rects = cached.rects();
        float[] corners = cached.corners();
        for (int r = 0; r * MunitionsBenchCounter.RECT_INTS < rects.length; r++) {
            int i = r * MunitionsBenchCounter.RECT_INTS;
            // 窗里: 全亮, 颜色原样; 箱身模板字: 方块光 + 这一面的静态明暗
            boolean window = rects[i] == MunitionsBenchCounter.FACE_WINDOW;
            float faceShade = window ? 1.0F : shade;
            int light = window ? LightTexture.FULL_BRIGHT : packedLight;
            int rgb = MunitionsBenchCounter.colour(tier, rects[i + 1], working);
            int red = Math.round((rgb >> 16 & 0xFF) * faceShade);
            int green = Math.round((rgb >> 8 & 0xFF) * faceShade);
            int blue = Math.round((rgb & 0xFF) * faceShade);
            int c = r * MunitionsBenchCounter.CORNER_FLOATS;
            // 左上 → 左下 → 右下 → 右上 (blockCorners 的顺序, 从正面看逆时针)
            for (int k = 0; k < MunitionsBenchCounter.CORNER_FLOATS; k += 3) {
                consumer.vertex(matrix, corners[c + k], corners[c + k + 1], corners[c + k + 2])
                        .color(red, green, blue, 255).uv2(light).endVertex();
            }
        }
    }

    /**
     * 运行灯效 ({@link MunitionsBenchLights}): 这一帧的覆盖四边形 (角已按朝向摆进主格, 顶点色已定), 这里只乘目标面的静态明暗
     * ({@code level.getShade(面方向按朝向转过去, 目标的 shade 标志)}: 宝石 shade:false 各面 × 1.0, 其余与静态面一样北南 0.8 / 东西 0.6),
     * α 按 {@link MunitionsBenchLights#alphaByte} 取整, 全亮。程序时间与运动件同一个时钟; 待机满仓的闪烁用客户端的 gameTime。
     */
    private void drawLights(VertexConsumer consumer, Matrix4f matrix, Level level, int tier, boolean working, boolean full,
                            long programTicks, float partialTick, double distance, float yRotation) {
        int count = MunitionsBenchLights.compute(lights, tier, working, full, programTicks, level.getGameTime(),
                partialTick, distance);
        if (count == 0) {
            return;
        }
        MunitionsBenchLights.blockCorners(lights, yRotation, lightCorners, lightColours);
        for (int q = 0; q < count; q++) {
            int target = lights.target[q];
            Direction face = Direction.from3DDataValue(
                    MunitionsBenchLights.worldFace(MunitionsBenchGeometry.LIGHT_TARGET_FACES[target], yRotation));
            float shade = level.getShade(face, MunitionsBenchGeometry.LIGHT_TARGET_SHADED[target]);
            // 四个角的顺序 = blockCorners 的顺序 (从面外看逆时针), 每个角自己的颜色 (追光彗尾是顶点色渐变)
            for (int k = 0; k < 4; k++) {
                int p = q * MunitionsBenchLights.CORNER_FLOATS + k * 3;
                int c = q * MunitionsBenchLights.COLOUR_FLOATS + k * 4;
                consumer.vertex(matrix, lightCorners[p], lightCorners[p + 1], lightCorners[p + 2])
                        .color(Math.round(lightColours[c] * shade), Math.round(lightColours[c + 1] * shade),
                                Math.round(lightColours[c + 2] * shade), MunitionsBenchLights.alphaByte(lightColours[c + 3]))
                        .uv2(LightTexture.FULL_BRIGHT).endVertex();
            }
        }
    }

    private Cached cachedFor(MunitionsBenchBlockEntity blockEntity, float yRotation) {
        MunitionsBenchCounter.Shown shown = blockEntity.clientCounter();
        Cached cached = cache.get(blockEntity);
        if (cached == null || cached.yRotation() != yRotation || !cached.shown().equals(shown)) {
            int caliber = shown.caliberIndex();
            String label = caliber >= 0 && caliber < MunitionsCaliber.values().length
                    ? MunitionsCaliber.byIndex(caliber).shortLabel() : null;
            int[] rects = MunitionsBenchCounter.rects(shown.rounds(), label, shown.cap(), shown.full());
            cached = new Cached(shown, yRotation, rects, MunitionsBenchCounter.blockCorners(rects, yRotation));
            cache.put(blockEntity, cached);
        }
        return cached;
    }

    private int tierOf(Block block) {
        return tiers.computeIfAbsent(block, candidate -> {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(candidate);
            if (id != null) {
                for (int tier = 0; tier < MunitionsBenchAssets.TIER_IDS.length; tier++) {
                    if (MunitionsBenchAssets.TIER_IDS[tier].equals(id.getPath())) {
                        return tier;
                    }
                }
            }
            return 0;
        });
    }
}
