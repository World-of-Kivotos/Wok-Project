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
 * 双槽位 (互不覆盖): "探测脉冲槽" (探矿=绿 / 陷阱=红, ~8s 脉冲) 与 "连锁预览槽" (青, ~15tick 短存活由持续请求刷新)。
 * 各自独立 expireTick、独立熄灭, 新一次探测只顶掉探测槽、新一次预览只顶掉预览槽 (故连锁预览不会抹掉刚探出的矿脉高亮)。
 * RenderLevelStageEvent(AFTER_TRANSLUCENT_BLOCKS) 逐槽用线框盒画轮廓。
 *
 * 不持有任何服务端态; 只缓存 S2C 携带的服务端结果 (服务端权威, 客户端不自算)。
 */
@Mod.EventBusSubscriber(modid = com.miningdim.core.MiningConstants.MODID, value = Dist.CLIENT)
public final class MinerHighlightRenderer {

    private MinerHighlightRenderer() {
    }

    /** 探测脉冲槽 (探矿/陷阱; 整体替换式; volatile 保证网络主线程写、渲染线程读可见)。 */
    private static volatile Slot probe = null;
    /** 连锁预览槽 (按住连锁期间的候选轮廓; 与探测槽互不覆盖)。 */
    private static volatile Slot preview = null;

    /** 一个高亮槽: 熄灭 tick + 坐标列表 + 线框颜色 (rgba)。 */
    private record Slot(long expireTick, List<BlockPos> positions, float[] rgba) {
    }

    /** 探矿高亮颜色 (绿)。 */
    private static final float[] ORE_RGBA = {0.20f, 1.00f, 0.30f, 0.85f};
    /** 陷阱高亮颜色 (红)。 */
    private static final float[] TRAP_RGBA = {1.00f, 0.20f, 0.20f, 0.85f};
    /** 连锁预览颜色 (青): 刻意区别于探矿绿/陷阱红, 一眼可辨这是"将连锁的范围"而非探测结果。 */
    private static final float[] PREVIEW_RGBA = {0.30f, 0.85f, 1.00f, 0.85f};

    /** 由探矿/陷阱 S2C 客户端 handler 调用: 整体替换探测脉冲槽 (矿=绿/陷阱=红)。 */
    public static void accept(byte kind, long expireTick, List<BlockPos> positions) {
        float[] color = kind == MinerHighlightS2C.KIND_TRAP ? TRAP_RGBA : ORE_RGBA;
        probe = new Slot(expireTick, List.copyOf(positions), color);
    }

    /** 由连锁预览 S2C 客户端 handler 调用: 整体替换连锁预览槽 (青)。 */
    public static void acceptPreview(long expireTick, List<BlockPos> positions) {
        preview = new Slot(expireTick, List.copyOf(positions), PREVIEW_RGBA);
    }

    /** 松开连锁键时客户端本地即清预览槽 (跟手; 不等 expire)。 */
    public static void clearPreview() {
        preview = null;
    }

    /** 当前有效连锁预览的候选数 (供 HUD 画"连锁 N"); 无预览/已过期返回 0。 */
    public static int activePreviewCount(long gameTime) {
        Slot p = preview;
        return (p != null && gameTime < p.expireTick()) ? p.positions().size() : 0;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Slot probeSlot = probe;
        Slot previewSlot = preview;
        if (probeSlot == null && previewSlot == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        long now = mc.level.getGameTime();
        // 逐槽脉冲熄灭: 各自到 expireTick 后清对应槽 (互不影响)。
        if (probeSlot != null && now >= probeSlot.expireTick()) {
            probe = null;
            probeSlot = null;
        }
        if (previewSlot != null && now >= previewSlot.expireTick()) {
            preview = null;
            previewSlot = null;
        }
        if (probeSlot == null && previewSlot == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        if (probeSlot != null) {
            drawSlot(pose, lines, probeSlot);
        }
        if (previewSlot != null) {
            drawSlot(pose, lines, previewSlot);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
        RenderSystem.applyModelViewMatrix();
    }

    /** 画一个槽的全部方块轮廓 (共用同一 rgba)。 */
    private static void drawSlot(PoseStack pose, VertexConsumer lines, Slot slot) {
        for (BlockPos p : slot.positions()) {
            drawBoxOutline(pose, lines, p, slot.rgba());
        }
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
