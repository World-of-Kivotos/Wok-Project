package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.miningdim.job.munitions.block.GunsmithArmProgram;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlock;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.block.GunsmithGunBed;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public final class GunsmithAssemblyBenchRenderer
        implements BlockEntityRenderer<GunsmithAssemblyBenchBlockEntity> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            MiningConstants.MODID, "textures/entity/gunsmith_assembly_arm.png");

    private final ModelPart root;
    private final ModelPart shoulder;
    private final ModelPart upperArm;
    private final ModelPart forearm;
    private final ModelPart wrist;
    private final ModelPart tool;
    private final ModelPart leftClaw;
    private final ModelPart rightClaw;
    private final ModelPart payloadBolt;
    private final ModelPart payloadStock;
    // Ticks to ease back to the dock when the server ends the assembly before the client's program has parked the arm.
    private static final float RETURN_TICKS = 6.0F;
    // Middle of the longest gun the bed takes (bench pixels): the point whose camera distance picks high-poly or LOD.
    private static final double GUN_CENTRE_X = GunsmithGunBed.BUTT_X - GunsmithGunBed.MAX_LENGTH / 2.0D;
    private static final double GUN_CENTRE_Y = GunsmithGunBed.TOP_Y + GunsmithGunBed.ENVELOPE_THICKNESS / 2.0D;
    private static final double GUN_CENTRE_Z = GunsmithGunBed.AXIS_Z;
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith-assembly");
    // Set when the TaCZ helper class itself cannot link against the installed TaCZ; no gun is drawn after that.
    private static boolean taczGunBroken;

    // Fallback clock for benches that were already ACTIVE when their chunk loaded (no ACTIVE flip was seen, so the
    // block entity has no start tick): the program then runs from the first frame this renderer sees.
    private final Map<GunsmithAssemblyBenchBlockEntity, Long> animationStartTicks = new WeakHashMap<>();
    // Last program time drawn while running, so a stop mid-program can ease back from where the arm actually was.
    private final Map<GunsmithAssemblyBenchBlockEntity, LastDrawn> lastDrawn = new WeakHashMap<>();
    private final Map<GunsmithAssemblyBenchBlockEntity, ReturnToDock> returns = new WeakHashMap<>();
    // render() runs every frame; sparks are world particles, so they are limited to one burst per game tick.
    private final Map<GunsmithAssemblyBenchBlockEntity, Long> lastSparkTicks = new WeakHashMap<>();
    // TaCZ is an optional dependency: without it the bench shows no gun, and the TaCZ helper class is never loaded.
    private final boolean taczLoaded = MunitionsAmmoFactory.isTaczLoaded();
    private final GunsmithArmProgram.Pose pose = new GunsmithArmProgram.Pose();
    private final GunsmithArmProgram.Pose idlePose = GunsmithArmProgram.idle(new GunsmithArmProgram.Pose());
    private final float[] contact = new float[3];
    // Place drops {bolt station, stock station} for the bench being rendered, from the gun it is about to draw.
    private final float[] drops = new float[2];
    private final double[] world = new double[3];

    // Game time stays a long: a float cannot hold sub-tick precision once a world is a few million ticks old.
    private record ReturnToDock(float fromProgramTick, long startTick, float startPartial) {
    }

    // Mutable and reused per bench: it is rewritten every frame while the program runs.
    private static final class LastDrawn {
        private float programTick;
        private long gameTick;
    }

    public GunsmithAssemblyBenchRenderer(BlockEntityRendererProvider.Context context) {
        root = createBodyLayer().bakeRoot();
        shoulder = root.getChild("shoulder");
        upperArm = shoulder.getChild("upper_arm");
        ModelPart elbow = upperArm.getChild("elbow");
        forearm = elbow.getChild("forearm");
        wrist = forearm.getChild("wrist");
        tool = wrist.getChild("tool");
        ModelPart gripper = tool.getChild("gripper");
        leftClaw = gripper.getChild("left_claw");
        rightClaw = gripper.getChild("right_claw");
        payloadBolt = gripper.getChild("payload_bolt");
        payloadStock = gripper.getChild("payload_stock");
    }

    private static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition shoulder = root.addOrReplaceChild("shoulder",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.5F, -1.0F, -2.5F, 5.0F, 1.0F, 5.0F)
                        .texOffs(21, 0).addBox(-2.0F, -3.0F, -2.0F, 4.0F, 2.0F, 4.0F)
                        .texOffs(38, 0).addBox(-1.5F, -5.0F, -2.0F, 3.0F, 2.0F, 1.0F)
                        .texOffs(38, 0).addBox(-1.5F, -5.0F, 1.0F, 3.0F, 2.0F, 1.0F),
                PartPose.offset(18.5F, 4.5F, 15.5F));
        PartDefinition upperArm = shoulder.addOrReplaceChild("upper_arm",
                CubeListBuilder.create()
                        .texOffs(47, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F)
                        .texOffs(0, 7).addBox(-1.0F, -7.0F, -1.0F, 2.0F, 6.0F, 2.0F),
                PartPose.offset(0.0F, -4.0F, 0.0F));
        PartDefinition elbow = upperArm.addOrReplaceChild("elbow",
                CubeListBuilder.create()
                        .texOffs(9, 7).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 3.0F)
                        .texOffs(22, 7).addBox(-1.0F, -1.0F, -2.0F, 2.0F, 2.0F, 4.0F),
                PartPose.offset(0.0F, -7.0F, 0.0F));
        PartDefinition forearm = elbow.addOrReplaceChild("forearm",
                CubeListBuilder.create()
                        .texOffs(35, 7).addBox(-1.0F, 1.0F, -1.0F, 2.0F, 6.0F, 2.0F),
                PartPose.ZERO);
        PartDefinition wrist = forearm.addOrReplaceChild("wrist",
                CubeListBuilder.create()
                        .texOffs(47, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));
        PartDefinition tool = wrist.addOrReplaceChild("tool",
                CubeListBuilder.create()
                        .texOffs(44, 7).addBox(-1.5F, 1.0F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.ZERO);
        PartDefinition gripper = tool.addOrReplaceChild("gripper",
                CubeListBuilder.create()
                        .texOffs(0, 16).addBox(-2.0F, 0.0F, -1.0F, 4.0F, 1.0F, 2.0F),
                PartPose.offset(0.0F, 2.0F, 0.0F));
        gripper.addOrReplaceChild("left_claw",
                CubeListBuilder.create()
                        .texOffs(13, 16).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F),
                PartPose.offset(-1.5F, 1.0F, 0.0F));
        gripper.addOrReplaceChild("right_claw",
                CubeListBuilder.create()
                        .texOffs(13, 16).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F),
                PartPose.offset(1.5F, 1.0F, 0.0F));
        gripper.addOrReplaceChild("payload_bolt",
                CubeListBuilder.create()
                        .texOffs(18, 16).addBox(-1.0F, 2.0F, -0.5F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        gripper.addOrReplaceChild("payload_stock",
                CubeListBuilder.create()
                        .texOffs(25, 16).addBox(-1.0F, 2.0F, -1.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.08F))
                        .texOffs(32, 16).addBox(-1.0F, 2.0F, 0.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void render(GunsmithAssemblyBenchBlockEntity blockEntity, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, int packedOverlay) {
        Direction facing = blockEntity.getBlockState().getValue(GunsmithAssemblyBenchBlock.FACING);
        float rotation = switch (facing) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
        // The gun goes first: the arm lowers the carried part onto whatever the bench is actually going to draw.
        boolean gunDrawn = prepareDisplayGun(blockEntity, rotation);
        boolean running = applyPose(blockEntity, partialTick, drops[0], drops[1]);

        poseStack.pushPose();
        poseStack.translate(0.5D, 1.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        // ModelPart converts both joint offsets and cube vertices from model pixels to blocks.
        poseStack.scale(1.0F, -1.0F, 1.0F);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        root.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();

        if (gunDrawn) {
            drawDisplayGun(rotation, poseStack, packedLight);
        }

        if (running && pose.spark) {
            emitWeldSparks(blockEntity, rotation);
        }
    }

    /**
     * Picks the model the bench is about to draw (high-poly, LOD, or none) and fills {@link #drops} with the two place
     * drops for it. With no gun drawn (empty bench, no TaCZ, model still loading or broken) both drops are
     * {@link GunsmithArmProgram#MAX_PLACE_DROP}, which lowers the part onto the empty bed. All TaCZ types stay inside the
     * helper, which is only reached once TaCZ is known to be loaded.
     */
    private boolean prepareDisplayGun(GunsmithAssemblyBenchBlockEntity blockEntity, float rotation) {
        drops[0] = GunsmithArmProgram.MAX_PLACE_DROP;
        drops[1] = GunsmithArmProgram.MAX_PLACE_DROP;
        ResourceLocation gunId = blockEntity.clientDisplayGunId();
        if (gunId == null || !taczLoaded || taczGunBroken) {
            return false;
        }
        BlockPos pos = blockEntity.getBlockPos();
        benchToWorld(pos, rotation, GUN_CENTRE_X, GUN_CENTRE_Y, GUN_CENTRE_Z, world);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distanceSq = camera.distanceToSqr(world[0], world[1], world[2]);
        try {
            if (GunsmithBenchGunRenderer.prepare(gunId, pos.asLong(), distanceSq, drops)) {
                return true;
            }
        } catch (LinkageError e) {
            // The helper handles TaCZ mismatches inside its own calls; this only catches the helper class itself
            // failing to link against the installed TaCZ.
            taczGunBroken = true;
            LOGGER.error("The gunsmith assembly bench cannot load its TaCZ gun renderer; benches show no gun", e);
        }
        drops[0] = GunsmithArmProgram.MAX_PLACE_DROP;
        drops[1] = GunsmithArmProgram.MAX_PLACE_DROP;
        return false;
    }

    /**
     * The real TaCZ gun lying on the bed. Same anchor and facing turn as the arm, but without the arm's ModelPart y flip:
     * the helper places the gun in bench pixels itself.
     */
    private static void drawDisplayGun(float rotation, PoseStack poseStack, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 1.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        try {
            GunsmithBenchGunRenderer.drawPrepared(poseStack, packedLight);
        } catch (LinkageError e) {
            taczGunBroken = true;
            LOGGER.error("The gunsmith assembly bench cannot load its TaCZ gun renderer; benches show no gun", e);
        }
        poseStack.popPose();
    }

    /**
     * North-facing bench pixels -> world position, turned the same way render() turns the model; out = {x, y, z}.
     */
    private static double[] benchToWorld(BlockPos pos, float rotationDegrees, double pixelX, double pixelY,
                                         double pixelZ, double[] out) {
        double localX = pixelX / 16.0D - 0.5D;
        double localZ = pixelZ / 16.0D - 0.5D;
        double radians = Math.toRadians(rotationDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        out[0] = pos.getX() + 0.5D + localX * cos + localZ * sin;
        out[1] = pos.getY() + pixelY / 16.0D;
        out[2] = pos.getZ() + 0.5D - localX * sin + localZ * cos;
        return out;
    }

    /** Poses the arm from the shared keyframe program; returns whether the assembly program is running. */
    private boolean applyPose(GunsmithAssemblyBenchBlockEntity blockEntity, float partialTick,
                              float boltDrop, float stockDrop) {
        boolean running = blockEntity.isAnimating();
        long now = blockEntity.getLevel().getGameTime();
        if (running) {
            returns.remove(blockEntity);
            long startedAt = blockEntity.clientProgramStartTick();
            if (startedAt <= 0L) {
                Long firstSeen = animationStartTicks.get(blockEntity);
                if (firstSeen == null) {
                    firstSeen = now;
                    animationStartTicks.put(blockEntity, firstSeen);
                }
                startedAt = firstSeen;
            }
            float programTick = Math.max(0.0F, now - startedAt + partialTick);
            LastDrawn last = lastDrawn.get(blockEntity);
            if (last == null) {
                last = new LastDrawn();
                lastDrawn.put(blockEntity, last);
            }
            last.programTick = programTick;
            last.gameTick = now;
            GunsmithArmProgram.sample(programTick, boltDrop, stockDrop, pose);
        } else {
            animationStartTicks.remove(blockEntity);
            lastSparkTicks.remove(blockEntity);
            LastDrawn stopped = lastDrawn.remove(blockEntity);
            // Only ease if the arm was on screen when it stopped; a bench that finished out of view is simply docked.
            if (stopped != null && now - stopped.gameTick <= 2L
                    && stopped.programTick % GunsmithArmProgram.CYCLE_TICKS < GunsmithArmProgram.PARKED_TICK) {
                returns.put(blockEntity, new ReturnToDock(stopped.programTick, now, partialTick));
            }
            easeToDock(blockEntity, now, partialTick, boltDrop, stockDrop);
        }
        shoulder.yRot = pose.yaw;
        upperArm.zRot = pose.upperArm;
        forearm.zRot = pose.forearm;
        // Cancelling both arm joints keeps the tool vertical, so picks and placements read as straight plunges.
        wrist.zRot = -(pose.upperArm + pose.forearm);
        tool.yRot = pose.toolSpin;
        leftClaw.zRot = pose.claw;
        rightClaw.zRot = -pose.claw;
        payloadBolt.visible = pose.payload == GunsmithArmProgram.PAYLOAD_BOLT;
        payloadStock.visible = pose.payload == GunsmithArmProgram.PAYLOAD_STOCK;
        return running;
    }

    /**
     * Idle pose, or a short joint-space blend towards it when the assembly ended while the client's program was still
     * mid-move (its clock started late). Six ticks of blending is not collision-swept like the program itself, but a
     * brief brush is far less jarring than the arm teleporting off the rifle with a part in its claws.
     */
    private void easeToDock(GunsmithAssemblyBenchBlockEntity blockEntity, long now, float partialTick,
                            float boltDrop, float stockDrop) {
        ReturnToDock back = returns.get(blockEntity);
        float s = back == null ? 1.0F
                : Math.max(0.0F, (now - back.startTick()) + partialTick - back.startPartial()) / RETURN_TICKS;
        if (s >= 1.0F) {
            returns.remove(blockEntity);
            GunsmithArmProgram.idle(pose);
            return;
        }
        // Same drops as the running program, so a stop mid-weld eases from the lowered pose instead of jumping up first.
        // The drops are lifted out over the first third of the blend: the joint-space path from a lowered pose would
        // otherwise swing the claw through the tray's stock part on its way back.
        float lift = Math.max(0.0F, 1.0F - s / 0.35F);
        GunsmithArmProgram.sample(back.fromProgramTick(), boltDrop * lift, stockDrop * lift, pose);
        float e = s * s * (3.0F - 2.0F * s);
        pose.yaw += (idlePose.yaw - pose.yaw) * e;
        pose.upperArm += (idlePose.upperArm - pose.upperArm) * e;
        pose.forearm += (idlePose.forearm - pose.forearm) * e;
        pose.toolSpin += (idlePose.toolSpin - pose.toolSpin) * e;
        pose.claw += (idlePose.claw - pose.claw) * e;
        // The part counts as fitted once the server has finished; sparks only belong to a running program.
        pose.payload = GunsmithArmProgram.PAYLOAD_NONE;
        pose.spark = false;
    }

    private void emitWeldSparks(GunsmithAssemblyBenchBlockEntity blockEntity, float rotationDegrees) {
        Level level = blockEntity.getLevel();
        long now = level.getGameTime();
        Long last = lastSparkTicks.put(blockEntity, now);
        if (last != null && last == now) {
            return;
        }
        // The pose already carries the place drops, so the sparks sit where the part meets the gun (or the empty bed).
        GunsmithArmProgram.contactPosition(pose, contact);
        benchToWorld(blockEntity.getBlockPos(), rotationDegrees, contact[0], contact[1], contact[2], world);
        double x = world[0];
        double y = world[1];
        double z = world[2];
        RandomSource random = level.random;
        for (int i = 0; i < 2; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z,
                    (random.nextDouble() - 0.5D) * 0.12D,
                    0.02D + random.nextDouble() * 0.06D,
                    (random.nextDouble() - 0.5D) * 0.12D);
        }
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.04D, z, 0.0D, 0.015D, 0.0D);
        }
    }
}
