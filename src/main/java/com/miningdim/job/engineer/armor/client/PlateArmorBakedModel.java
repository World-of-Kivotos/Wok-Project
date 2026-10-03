package com.miningdim.job.engineer.armor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 直接绘制离线烘焙网格的人形护甲模型，替代原先 49 个手写 addBox 的 *ArmorModel。
 *
 * <p>骨架是一套没有任何方块的原版人形部位（头、帽子层、躯干、双臂、双腿，原版默认位姿），只用来承接动作：
 * Forge 的 getGenericArmorModel 会在每次渲染前把原模型的部位位姿与可见性拷到这里，所以走路摆臂、潜行、
 * 细手臂下移半像素、儿童与小盔甲架缩放都自动跟随，与预览中的部位挂点完全一致。</p>
 *
 * <p>渲染逐顶点复刻原版 ModelPart.Cube.compile：位置 = 位姿矩阵 × (x/16, y/16, z/16, 1)，
 * 法线 = 法线矩阵 × 网格法线，再按 (位置, 颜色, uv, overlay, 光照, 法线) 交给 VertexConsumer。
 * 只画躯干与双臂，头、帽子层与双腿永远为空。渲染只在客户端主线程进行，变换用的两个临时向量复用实例字段，
 * 每帧不分配对象（PoseStack 自身的 push/pop 与 translateAndRotate 属原版行为）。</p>
 *
 * <p>不支持盔甲纹饰（Trim）：原版纹饰层也走这个模型，但用的是纹饰图集的 sprite，网格里按本件图集归一化的 uv
 * 映射过去会是错乱的图案。生存里拿不到（插板护甲不在 #minecraft:trimmable_armor 里），只有用命令写入 Trim NBT
 * 才会触发；以后要支持得为纹饰单独导出一套 uv，或在物品侧剔除 Trim。</p>
 */
public final class PlateArmorBakedModel extends HumanoidModel<LivingEntity> {

    /** 原版 HumanoidModel 传给 AgeableListModel 的儿童身体缩放与下移，父类字段是私有的，只能照抄常量。 */
    private static final float BABY_BODY_SCALE = 2.0F;
    private static final float BABY_BODY_Y_OFFSET = 24.0F;

    /** 空骨架只定义一次；每个模型实例各自 bakeRoot，避免多个外观共用同一组可变 ModelPart。 */
    private static final LayerDefinition EMPTY_SKELETON = createEmptySkeleton();

    private final PlateArmorMesh mesh;
    private final Vector4f position = new Vector4f();
    private final Vector3f normal = new Vector3f();

    public PlateArmorBakedModel(PlateArmorMesh mesh) {
        super(EMPTY_SKELETON.bakeRoot());
        this.mesh = mesh;
    }

    public PlateArmorMesh mesh() {
        return mesh;
    }

    private static LayerDefinition createEmptySkeleton() {
        MeshDefinition definition = new MeshDefinition();
        PartDefinition root = definition.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        // 骨架上没有方块，贴图尺寸不参与任何 uv 计算；真实 uv 已在网格里归一化到 0 至 1。
        return LayerDefinition.create(definition, 64, 64);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        if (young) {
            // 与 AgeableListModel 的儿童分支一致：身体部位缩小一半并下移；头部部位为空，无需处理。
            poseStack.pushPose();
            float scale = 1.0F / BABY_BODY_SCALE;
            poseStack.scale(scale, scale, scale);
            poseStack.translate(0.0F, BABY_BODY_Y_OFFSET / 16.0F, 0.0F);
            renderParts(poseStack, consumer, packedLight, packedOverlay, red, green, blue, alpha);
            poseStack.popPose();
        } else {
            renderParts(poseStack, consumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }

    private void renderParts(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
                             float red, float green, float blue, float alpha) {
        renderPart(body, mesh.body(), poseStack, consumer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(rightArm, mesh.rightArm(), poseStack, consumer, packedLight, packedOverlay,
                red, green, blue, alpha);
        renderPart(leftArm, mesh.leftArm(), poseStack, consumer, packedLight, packedOverlay,
                red, green, blue, alpha);
    }

    private void renderPart(ModelPart part, float[] quads, PoseStack poseStack, VertexConsumer consumer,
                            int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        if (!part.visible || quads.length == 0) {
            return;
        }
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        if (!part.skipDraw) {
            emitQuads(poseStack.last(), quads, consumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
        poseStack.popPose();
    }

    private void emitQuads(PoseStack.Pose pose, float[] quads, VertexConsumer consumer, int packedLight,
                           int packedOverlay, float red, float green, float blue, float alpha) {
        Matrix4f poseMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        for (int base = 0; base < quads.length; base += PlateArmorMesh.QUAD_FLOATS) {
            int n = base + PlateArmorMesh.NORMAL_OFFSET;
            normalMatrix.transform(quads[n], quads[n + 1], quads[n + 2], normal);
            float nx = normal.x();
            float ny = normal.y();
            float nz = normal.z();
            for (int vertex = 0; vertex < 4; vertex++) {
                int v = base + vertex * PlateArmorMesh.VERTEX_FLOATS;
                poseMatrix.transform(quads[v] / 16.0F, quads[v + 1] / 16.0F, quads[v + 2] / 16.0F, 1.0F, position);
                consumer.vertex(position.x(), position.y(), position.z(), red, green, blue, alpha,
                        quads[v + 3], quads[v + 4], packedOverlay, packedLight, nx, ny, nz);
            }
        }
    }
}
