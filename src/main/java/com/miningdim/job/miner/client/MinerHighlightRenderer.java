package com.miningdim.job.miner.client;

import com.miningdim.job.miner.network.MinerHighlightS2C;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 探矿/陷阱高亮渲染 (Miner_Job_DesignSpec 第十一章: 客户端 RenderLevelStageEvent 画轮廓)。仅客户端逻辑端加载
 * (经 {@link MinerHighlightS2C#handle} 的 DistExecutor 隔离调 {@link #accept})。
 *
 * 收到一组高亮坐标 (矿=绿 / 陷阱=红) 缓存到熄灭 tick (~8s 脉冲); RenderLevelStageEvent(AFTER_TRANSLUCENT_BLOCKS)
 * 用线框盒画轮廓, 到熄灭 tick 后停画。整体替换式更新 (新一次探测覆盖旧缓存)。
 *
 * 不持有任何服务端态; 只缓存 S2C 携带的服务端结果 (服务端权威, 客户端不自算)。
 */
@Mod.EventBusSubscriber(modid = com.miningdim.core.MiningConstants.MODID, value = Dist.CLIENT)
public final class MinerHighlightRenderer {

    private MinerHighlightRenderer() {
    }

    /** 当前高亮快照 (整体替换式更新; volatile 保证网络主线程写、渲染线程读可见)。 */
    private static volatile Highlight current = null;

    private record Highlight(byte kind, long expireTick, List<BlockPos> positions) {
    }

    /** 矿物高亮颜色 (绿)。 */
    private static final float[] ORE_RGBA = {0.20f, 1.00f, 0.30f, 0.85f};
    /** 陷阱高亮颜色 (红)。 */
    private static final float[] TRAP_RGBA = {1.00f, 0.20f, 0.20f, 0.85f};

    /** 由 S2C 客户端 handler 在客户端主线程调用: 整体替换高亮快照。 */
    public static void accept(byte kind, long expireTick, List<BlockPos> positions) {
        current = new Highlight(kind, expireTick, List.copyOf(positions));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Highlight hl = current;
        if (hl == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // 脉冲熄灭: 到 expireTick 后清缓存停画。
        if (mc.level.getGameTime() >= hl.expireTick) {
            current = null;
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float[] c = hl.kind == MinerHighlightS2C.KIND_TRAP ? TRAP_RGBA : ORE_RGBA;

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (BlockPos p : hl.positions) {
            drawBoxOutline(pose, lines, p, c);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
        RenderSystem.applyModelViewMatrix();
    }

    /** 画一个单位方块的线框轮廓 (12 条棱)。 */
    private static void drawBoxOutline(PoseStack pose, VertexConsumer lines, BlockPos p, float[] c) {
        float x0 = p.getX();
        float y0 = p.getY();
        float z0 = p.getZ();
        float x1 = x0 + 1.0f;
        float y1 = y0 + 1.0f;
        float z1 = z0 + 1.0f;
        // 底面 4 棱。
        edge(pose, lines, x0, y0, z0, x1, y0, z0, c);
        edge(pose, lines, x1, y0, z0, x1, y0, z1, c);
        edge(pose, lines, x1, y0, z1, x0, y0, z1, c);
        edge(pose, lines, x0, y0, z1, x0, y0, z0, c);
        // 顶面 4 棱。
        edge(pose, lines, x0, y1, z0, x1, y1, z0, c);
        edge(pose, lines, x1, y1, z0, x1, y1, z1, c);
        edge(pose, lines, x1, y1, z1, x0, y1, z1, c);
        edge(pose, lines, x0, y1, z1, x0, y1, z0, c);
        // 4 条竖棱。
        edge(pose, lines, x0, y0, z0, x0, y1, z0, c);
        edge(pose, lines, x1, y0, z0, x1, y1, z0, c);
        edge(pose, lines, x1, y0, z1, x1, y1, z1, c);
        edge(pose, lines, x0, y0, z1, x0, y1, z1, c);
    }

    /** 一条线段 (RenderType.lines 需每顶点带法线)。 */
    private static void edge(PoseStack pose, VertexConsumer lines,
                             float ax, float ay, float az, float bx, float by, float bz, float[] c) {
        var matrix = pose.last().pose();
        var normal = pose.last().normal();
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-5f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        lines.vertex(matrix, ax, ay, az).color(c[0], c[1], c[2], c[3]).normal(normal, nx, ny, nz).endVertex();
        lines.vertex(matrix, bx, by, bz).color(c[0], c[1], c[2], c[3]).normal(normal, nx, ny, nz).endVertex();
    }
}
