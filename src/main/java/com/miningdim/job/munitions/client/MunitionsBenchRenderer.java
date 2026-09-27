package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.block.MunitionsBenchGeometry;
import com.miningdim.job.munitions.block.MunitionsBenchProgram;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 军火台 (WIDE 布局, 弹药流水线) 的运动件渲染器: 皮带上的弹、出弹、压弹头冲头、底火冲杆、装药管
 * ({@link MunitionsBenchParts})。静态机身是区块网格里的 JSON 模型, 这里只画运动件, 待机也画 (停在待机布局)。
 * <p>
 * 姿态取自 {@link MunitionsBenchProgram}: 工作时按 "游戏 tick - 程序起点 + partialTick" 取样, 起点是这个客户端看到
 * ACTIVE 由假变真的 tick (或区块更新标签带来的服务端起点, 见 {@link MunitionsBenchBlockEntity#programStartTickOr}),
 * 与服务端播冲压音用的是同一张帧表; 冲头压到底 ({@link MunitionsBenchProgram#STRIKE_TICK}) 的那一 tick 从冲压点放火花。
 * 停机时直接回到待机布局 (中途停也一样, 不做缓动)。运动件之后画弹药箱计数屏 ({@link MunitionsBenchCounterRenderer}:
 * 箱盖窗里的发数 + 满度条, 箱身的口径模板字)。LEGACY 老台子没有运动件也没有计数屏, 整个跳过。
 */
public final class MunitionsBenchRenderer implements BlockEntityRenderer<MunitionsBenchBlockEntity> {

    /** 火花看的是 "这一帧与上一帧之间有没有跨过冲压 tick"; 隔得太久 (台子刚回到视野里) 就不补放。 */
    private static final long MAX_SPARK_CATCH_UP_TICKS = 3L;
    /** 副格从朝北整台坐标 x = 16 px 起 (ModelPart 单位)。 */
    private static final float EXTENSION_START_UNITS = 16.0F * MunitionsBenchParts.UNITS_PER_PX;

    private final ModelPart[] parts;
    private final boolean[] fullBright;
    private final ModelPart root;
    private final MunitionsBenchProgram.Pose pose = new MunitionsBenchProgram.Pose();
    // render() 每帧都跑, 火花是世界粒子: 记下每台上一次画到的程序 tick, 每次冲压只放一次。
    private final Map<MunitionsBenchBlockEntity, Long> lastDrawnProgramTicks = new WeakHashMap<>();
    /** 弹药箱计数屏 (箱盖窗里的发数 + 满度条, 箱身口径), 画在运动件之后。 */
    private final MunitionsBenchCounterRenderer counter = new MunitionsBenchCounterRenderer();
    private final BlockEntityRenderDispatcher dispatcher;

    public MunitionsBenchRenderer(BlockEntityRendererProvider.Context context) {
        dispatcher = context.getBlockEntityRenderDispatcher();
        root = MunitionsBenchParts.createBodyLayer().bakeRoot();
        parts = new ModelPart[MunitionsBenchParts.PARTS.length];
        fullBright = new boolean[MunitionsBenchParts.PARTS.length];
        for (int index = 0; index < parts.length; index++) {
            String name = MunitionsBenchParts.PARTS[index];
            parts[index] = root.getChild(name);
            fullBright[index] = Arrays.asList(MunitionsBenchParts.FULL_BRIGHT).contains(name);
        }
    }

    @Override
    public void render(MunitionsBenchBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState state = blockEntity.getBlockState();
        Level level = blockEntity.getLevel();
        if (level == null || !(state.getBlock() instanceof MunitionsBenchBlock)
                || state.getValue(MunitionsBenchBlock.LAYOUT) != MunitionsBenchBlock.Layout.WIDE) {
            return;
        }
        Direction facing = state.getValue(MunitionsBenchBlock.FACING);
        boolean running = state.getValue(MunitionsBenchBlock.ACTIVE);
        if (running) {
            long now = level.getGameTime();
            // 更新标签带来的服务端起点可能比本地时钟快一两 tick (客户端时钟落后一个网络延迟): 先停在首帧等它。
            long elapsed = now - blockEntity.programStartTickOr(now);
            if (elapsed < 0L) {
                MunitionsBenchProgram.sample(0.0F, pose);
            } else {
                MunitionsBenchProgram.sample(elapsed, partialTick, pose);
            }
            emitStrikeSparksIfDue(blockEntity, level, facing, elapsed);
        } else {
            lastDrawnProgramTicks.remove(blockEntity);
            MunitionsBenchProgram.idle(pose);
        }
        MunitionsBenchParts.applyPose(root, pose);
        // 运动件按它此刻在哪一格取光照 (packedLight 是主格的): 副格上的弹和两根杆用副格的光, 灯只放在副格旁边时
        // 它们不会比副格的静态机身暗一截。件的 x 就是它在朝北整台坐标里的中心 (ModelPart 单位)。
        int extensionLight = LevelRenderer.getLightColor(level,
                MunitionsBenchBlock.extensionPos(blockEntity.getBlockPos(), state));

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(MunitionsBenchBlock.partsYRotationDegrees(facing)));
        poseStack.translate(-0.5D, 0.0D, -0.5D);
        // 件按 4 倍尺寸建 (ModelPart 再把单位 / 16 换成格), y 翻转 (ModelPart 的 y 朝下); 翻转后面的绕序反了, 所以用 NoCull。
        // 法线矩阵自己设: 1.20.1 的 PoseStack.scale 在三个缩放之积为负时把它算坏 (Mth.fastInvCubeRoot 不收负数, 法线被放大
        // 约 1e25 倍, 写进顶点时每个分量截成 ±1, 运动件的明暗随视角乱跳)。diag(s, -s, s) 的逆转置与 diag(1, -1, 1) 同向,
        // 所以缩放前的法线矩阵右乘 diag(1, -1, 1) 就是正确的单位法线矩阵。
        Matrix3f normal = new Matrix3f(poseStack.last().normal());
        poseStack.scale(MunitionsBenchParts.PART_SCALE, -MunitionsBenchParts.PART_SCALE, MunitionsBenchParts.PART_SCALE);
        poseStack.last().normal().set(normal).scale(1.0F, -1.0F, 1.0F);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(MunitionsBenchParts.TEXTURE));
        for (int index = 0; index < parts.length; index++) {
            // root 本身不画 (它没有 cube, 也不带变换); 隐藏的件 ModelPart.render 自己会跳过。
            int light = parts[index].x >= EXTENSION_START_UNITS ? extensionLight : packedLight;
            parts[index].render(poseStack, consumer, fullBright[index] ? glow(light) : light, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();

        Camera camera = dispatcher.camera;
        counter.render(blockEntity, state, facing, running, poseStack, bufferSource, packedLight,
                camera == null ? null : camera.getPosition());
    }

    /** 自发光件 (热压模) 至少按 {@link MunitionsBenchParts#FULL_BRIGHT_LIGHT} 的方块光 / 天空光画。 */
    private static int glow(int packedLight) {
        return LightTexture.pack(
                Math.max(LightTexture.block(packedLight), MunitionsBenchParts.FULL_BRIGHT_LIGHT),
                Math.max(LightTexture.sky(packedLight), MunitionsBenchParts.FULL_BRIGHT_LIGHT));
    }

    /**
     * 冲头压到底的那一 tick 从冲压点 (压弹头位那发的壳口) 放一把火花, 与服务端的冲压音同拍。
     * 看的是上一帧到这一帧之间有没有跨过冲压 tick, 所以帧率低于 20 时也不漏; 同一 tick 的多帧只放一次。
     */
    private void emitStrikeSparksIfDue(MunitionsBenchBlockEntity blockEntity, Level level, Direction facing,
                                       long elapsed) {
        if (elapsed < 0L) {
            return;
        }
        Long previous = lastDrawnProgramTicks.put(blockEntity, elapsed);
        if (previous != null && previous > elapsed) {
            // 客户端时钟被服务端的对时包往回校了: 这段已经放过, 不重放。
            return;
        }
        long since = previous == null ? elapsed - 1L : previous;
        if (elapsed == since || elapsed - since > MAX_SPARK_CATCH_UP_TICKS
                || MunitionsBenchProgram.nextStrikeTickAfter(since) > elapsed) {
            return;
        }
        Vec3 at = MunitionsBenchBlock.benchPixelToWorld(blockEntity.getBlockPos(), facing,
                MunitionsBenchGeometry.SPARK_X, MunitionsBenchGeometry.SPARK_Y, MunitionsBenchGeometry.SPARK_Z);
        RandomSource random = level.random;
        for (int i = 0; i < 3; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                    (random.nextDouble() - 0.5D) * 0.12D,
                    0.02D + random.nextDouble() * 0.05D,
                    (random.nextDouble() - 0.5D) * 0.12D);
        }
        if (random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.SMOKE, at.x, at.y + 0.04D, at.z, 0.0D, 0.015D, 0.0D);
        }
    }
}
