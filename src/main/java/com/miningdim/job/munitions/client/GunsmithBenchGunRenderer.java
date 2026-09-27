package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.block.GunsmithArmProgram;
import com.miningdim.job.munitions.block.GunsmithBenchGunLayout;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 组装台台面上的那把真枪: 按同步过来的枪 id 取 TaCZ 自己的枪模, 侧躺着摆到枪床上 (摆法见 {@link GunsmithBenchGunLayout})。
 * <p>
 * 本类的字段与方法体都带 TaCZ 类型, 只能在 MunitionsAmmoFactory.isTaczLoaded() 之后触达; 公开的两个入口
 * ({@link #prepare}, {@link #drawPrepared}) 签名里没有 TaCZ 类型, 组装台渲染器先查再调, 没装 TaCZ 的客户端根本不会加载本类。
 * 每台每帧先 prepare (决定画哪个模型、算出两个安装点的放件下沉量, 机械臂要先知道它才能摆姿态), 再 drawPrepared。
 * <p>
 * 不走 ItemRenderer.renderStatic(FIXED): TaCZ 默认配置 (GunLodRenderDistance = 0) 下世界里永远画低模, 也不看透明贴图开关。
 * 这里直接调 BedrockGunModel.render, 前面接的是 TaCZ FIXED 分支 (GunItemRendererWrapper.renderByItem) 里
 * scale(-1,-1,1) 加 applyPositioningNodeTransform 的同一串变换 (fixed 骨骼的枢轴落到原点、它的旋转被抵消),
 * 只是 TaCZ 的 fixed 缩放换成了枪床的 k。包围盒也在这同一个 FIXED 定位系里量, 摆出来的就是画出来的那把枪。
 * <p>
 * 显示件、模型、包围盒都会在 TaCZ 重载时换新 (资源重载、进服同步枪包), 所以显示实例每帧经 TimelessAPI 重新查,
 * 模型与显示实例只进弱引用的按身份缓存。除了后台加载模型, 全部在渲染线程上跑, 不加锁。
 */
public final class GunsmithBenchGunRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith-assembly");

    /**
     * 没有低模的枪只能画高模, 超过 32 格干脆不画。有低模时画不画高模 (约 8 格内、最多 3 台) 由 {@link GunsmithBenchGunBudget} 定。
     */
    private static final double NO_LOD_DISTANCE_SQ = 32.0D * 32.0D;
    /** 建不出显示件 (枪包索引还没到, 或这把枪不存在) 时, 同一个 id 最多每秒再试一次。 */
    private static final long STACK_RETRY_MILLIS = 1000L;
    /** 连显示实例都没查出来就抛了异常 (认不出是不是重载过) 时, 隔这么久才再试。 */
    private static final long LOOKUP_RETRY_MILLIS = 5000L;

    /**
     * 裸枪显示 stack (不带配件) 下 TaCZ 1.1.8 不画的方块, 纯按骨骼结构判定、不读运行时的 visible (与 JS 预览 raster.mjs
     * taczBareDrawn 同一条规则, 改一边必须改另一边), 这些方块不计入包围盒与安装点枪顶:
     * (a) lefthand_pos / righthand_pos 的整棵子树 (两只手只在第一人称画);
     * (b) 下面其余名字的整棵子树 (枪口火焰、弹药、瞄具座、扩容弹匣、战术护木、备用弹匣、激光, 裸枪默认都不显示);
     * (c) attachment_adapter 的每个子节点的整棵子树, 见 {@link #ATTACHMENT_ADAPTER}。
     */
    private static final Set<String> HIDDEN_BONES = Set.of(
            "lefthand_pos", "righthand_pos",
            "muzzle_flash", "bullet_in_barrel", "bullet_in_mag", "bullet_chain", "mount", "sight_folded",
            "mag_extended_1", "mag_extended_2", "mag_extended_3", "handguard_tactical", "additional_magazine",
            "laser_beam");
    /**
     * 配件转接节点: BedrockGunModel.attachmentAdapterNodeRender (BedrockGunModel.java:141-155, 构造时在 :102 挂上) 每次渲染
     * 把它的每个子节点设成 "名字在 adapterToRender 里才显示", 无名子节点一律不显示; adapterToRender 每次 render 先清空
     * (:244), 只由枪上配件的 adapterNodeName 填入 (:265-267), 裸枪为空。所以裸枪时它的全部子节点 —— 具名骨骼,
     * 以及新格式加载时把它自己带 rotation 的方块包成的无名子节点 (BedrockModel.java:124-135) —— 连同子树都不画;
     * 它自己不带 rotation 的方块照画 (函数渲染器返回 null, 走普通 compile)。
     */
    private static final String ATTACHMENT_ADAPTER = "attachment_adapter";
    /** 枪口参考点: 按顺序取第一个存在的节点 (不论画不画)。 */
    private static final List<String> BORE_NODES = List.of("muzzle_pos", "muzzle_flash", "muzzle_default");

    private static final Map<ResourceLocation, ItemStack> DISPLAY_STACKS = new HashMap<>();
    private static final Map<ResourceLocation, Long> STACK_RETRY_AT = new HashMap<>();
    /**
     * 每个显示实例一次后台加载: 在 Util.backgroundExecutor() 上调 getGunModel() 与 getLodModel()。TaCZ 没有 "是否已加载"
     * 的公开查询, 这两个取模型方法没加载完时会在调用线程上同步加载或 join 它自己的预载任务, 放在后台线程调就不会卡渲染线程
     * (TaCZ 自己的预载线程也是在 loadLock 下这么调的)。任务完成且正常结束之后渲染线程再取, 两个方法都立刻返回。
     */
    private static final Map<GunDisplayInstance, CompletableFuture<Void>> LOADS = new WeakHashMap<>();
    /** 模型实例 → 摆法; 只由模型本身决定 (可见性按结构判定), 几个枪 id 共用一份显示时 (枪匠三连发枪挂原枪的 display) 共用。 */
    private static final Map<BedrockGunModel, Fit> FITS = new WeakHashMap<>();
    private static final Map<ResourceLocation, Failure> FAILURES = new HashMap<>();

    /**
     * TaCZ 与编译时的版本对不上 (NoSuchMethodError / NoSuchFieldError 之类): 报一次, 本次游戏不再碰 TaCZ,
     * 台上不摆枪、机械臂按空床放件。
     */
    private static boolean disabled;

    // prepare 选好、drawPrepared 要画的那一台 (同一次方块实体渲染里成对调用, 只在渲染线程上用)
    @Nullable
    private static Fit pendingFit;
    @Nullable
    private static BedrockGunModel pendingModel;
    @Nullable
    private static ItemStack pendingStack;
    @Nullable
    private static ResourceLocation pendingGunId;
    @Nullable
    private static GunDisplayInstance pendingDisplay;
    /** 画枪用的姿态栈, 每次复用 (TaCZ 中途抛异常留下没弹出的 push 时换一个新的)。 */
    @Nullable
    private static PoseStack gunPose;

    /**
     * 一个模型实例的摆法。placement 为 null = 这个模型摆不出来 (没有画出来的方块), 不画。
     * pose / normal 是枪床坐标系 → TaCZ 零件坐标系那一整串常量变换 (摆放平移、侧躺旋转、缩放 k、FIXED 定位) 的矩阵,
     * 每帧只要左乘组装台的姿态; 法线矩阵单独累乘, 与逐步 PoseStack 操作的结果相同。
     * 不能持有模型本身: 它是 FITS 这张弱键表的键。
     */
    private record Fit(@Nullable GunsmithBenchGunLayout.Placement placement, float boltDrop, float stockDrop,
                       Matrix4f pose, Matrix3f normal, @Nullable RenderType renderType) {
    }

    /** 某个 id 画挂了: 记下当时的显示实例 (弱引用), 只有 TaCZ 重载换了新实例才再试; 连实例都没拿到时按时间重试。 */
    private record Failure(@Nullable WeakReference<GunDisplayInstance> display, long retryAtMillis) {
    }

    private GunsmithBenchGunRenderer() {
    }

    /**
     * 选好这台这一帧要画的模型, 写出两个安装点的放件下沉量; 返回 false = 这一帧台上不画枪 (建不出显示件、模型还在后台加载、
     * 加载失败、摆不出来、没有低模又太远、或 TaCZ 已停用), 此时 dropsOut 两项都是 {@link GunsmithArmProgram#MAX_PLACE_DROP}。
     * 返回 true 时同一次渲染里接着调 {@link #drawPrepared}。
     *
     * @param benchPos           组装台主格的 BlockPos.asLong(), 高模名额、资格与距离滞回都按它记
     * @param distanceSqToCamera 相机到台面枪的距离平方 (格²), 决定高模、低模还是不画
     * @param dropsOut           {枪机安装点, 枪托件安装点} 的下沉量 (px), 交给 GunsmithArmProgram.sample
     */
    public static boolean prepare(ResourceLocation gunId, long benchPos, double distanceSqToCamera, float[] dropsOut) {
        clearPending();
        dropsOut[0] = GunsmithArmProgram.MAX_PLACE_DROP;
        dropsOut[1] = GunsmithArmProgram.MAX_PLACE_DROP;
        if (disabled) {
            return false;
        }
        long now = Util.getMillis();
        Failure failure = FAILURES.get(gunId);
        if (failure != null && failure.display() == null && now < failure.retryAtMillis()) {
            return false;
        }
        GunDisplayInstance display = null;
        try {
            ItemStack stack = displayStack(gunId, now);
            if (stack.isEmpty()) {
                return false;
            }
            display = TimelessAPI.getGunDisplay(stack).orElse(null);
            if (display == null) {
                return false;
            }
            if (failure != null) {
                if (failure.display() != null && failure.display().get() == display) {
                    return false;   // 还是画挂的那一份资源, 等重载
                }
                FAILURES.remove(gunId);
            }
            if (!loaded(display)) {
                return false;
            }
            if (!choose(display, benchPos, distanceSqToCamera)) {
                return false;
            }
            Fit fit = pendingFit;
            dropsOut[0] = fit.boltDrop();
            dropsOut[1] = fit.stockDrop();
            pendingStack = stack;
            pendingGunId = gunId;
            pendingDisplay = display;
            return true;
        } catch (RuntimeException e) {
            // TaCZ 或别的枪包内容出错不能让客户端每帧崩一次: 记下这一份资源, 只报一次, 重载前不再画这把枪。
            clearPending();
            recordFailure(gunId, display, now, e);
            return false;
        } catch (LinkageError e) {
            clearPending();
            disable(e);
            return false;
        }
    }

    /**
     * 画 {@link #prepare} 选好的那把枪; 没有准备好的枪时什么也不做。
     *
     * @param poseStack 已平移到主格 (0.5, 1, 0.5) 并按朝向转好 (机械臂那一步 y 翻转之前), 即枪床坐标
     *                  (x, y, z) px 对应 ((x - 8) / 16, (y - 16) / 16, (z - 8) / 16) 格
     */
    public static void drawPrepared(PoseStack poseStack, int packedLight) {
        Fit fit = pendingFit;
        BedrockGunModel model = pendingModel;
        ItemStack stack = pendingStack;
        ResourceLocation gunId = pendingGunId;
        GunDisplayInstance display = pendingDisplay;
        clearPending();
        if (fit == null || model == null || stack == null || gunId == null || disabled) {
            return;
        }
        try {
            // 另起一个姿态栈: TaCZ 中途抛异常会留下没弹出的 push, 不能弄脏方块实体渲染器共用的那个。
            PoseStack pose = gunPoseStack();
            pose.last().pose().set(poseStack.last().pose()).mul(fit.pose());
            pose.last().normal().set(poseStack.last().normal()).mul(fit.normal());
            // 必须用带 ItemStack 的这个重载: 它先按枪填好配件表, 无参的 BedrockModel.render 会让护木节点读到空表而 NPE。
            // 它不认调用方的 MultiBufferSource, 直接写 Minecraft 的主 bufferSource 并当场 endBatch, 在方块实体渲染里没问题。
            model.render(pose, stack, ItemDisplayContext.FIXED, fit.renderType(), packedLight, OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException e) {
            recordFailure(gunId, display, Util.getMillis(), e);
        } catch (LinkageError e) {
            disable(e);
        }
    }

    private static void clearPending() {
        pendingFit = null;
        pendingModel = null;
        pendingStack = null;
        pendingGunId = null;
        pendingDisplay = null;
    }

    private static PoseStack gunPoseStack() {
        if (gunPose == null || !gunPose.clear()) {
            gunPose = new PoseStack();
        }
        return gunPose;
    }

    /** 每个 id 建一次显示用的枪 (只有 GunId, 不带配件); 建不出来不缓存空结果, 限速重试。 */
    private static ItemStack displayStack(ResourceLocation gunId, long now) {
        ItemStack stack = DISPLAY_STACKS.get(gunId);
        if (stack != null) {
            return stack;
        }
        Long retryAt = STACK_RETRY_AT.get(gunId);
        if (retryAt != null && now < retryAt) {
            return ItemStack.EMPTY;
        }
        // build() 只在 TaCZ 的通用枪索引里查得到这把枪时才出东西; 进服后 TaCZ 先清空索引、等同步包到了才有。
        stack = GunItemBuilder.create().setId(gunId).build();
        if (stack.isEmpty()) {
            STACK_RETRY_AT.put(gunId, now + STACK_RETRY_MILLIS);
            return ItemStack.EMPTY;
        }
        STACK_RETRY_AT.remove(gunId);
        DISPLAY_STACKS.put(gunId, stack);
        return stack;
    }

    /**
     * 这个显示实例的模型加载完了没有: 第一次见到时交给后台线程加载, 之后每帧只看任务是否已结束 (不等)。
     * 任务异常结束 (只可能是调用本身出错, TaCZ 自己的加载失败被它内部吞掉、表现为取回 null) 时抛出原因, 由 prepare 记失败或停用。
     */
    private static boolean loaded(GunDisplayInstance display) {
        CompletableFuture<Void> load = LOADS.get(display);
        if (load == null) {
            load = CompletableFuture.runAsync(() -> {
                display.getGunModel();
                display.getLodModel();
            }, Util.backgroundExecutor());
            LOADS.put(display, load);
            return false;
        }
        if (!load.isDone()) {
            return false;
        }
        if (load.isCompletedExceptionally()) {
            try {
                load.join();
            } catch (CompletionException | CancellationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof LinkageError linkageError) {
                    throw linkageError;
                }
                throw new IllegalStateException("loading the TaCZ gun model failed", cause);
            }
        }
        return true;
    }

    /**
     * 选模型: 有低模时, 拿到 {@link GunsmithBenchGunBudget} 的高模名额 (约 8 格内, 带滞回; 最多 3 台) 才画高模, 否则画低模;
     * 没有低模只能画高模 (不占名额), 32 格外不画。高模加载失败时直接画低模 (不去要名额, 免得白占一个还画低模);
     * 两个都没有就不摆 (TaCZ 自己在这种情况下也只剩 2D 图标)。选中后备好 pendingFit / pendingModel, 返回是否要画。
     */
    private static boolean choose(GunDisplayInstance display, long benchPos, double distanceSqToCamera) {
        Pair<BedrockGunModel, ResourceLocation> lod = display.getLodModel();
        boolean hasLod = lod != null && lod.getLeft() != null && lod.getRight() != null;
        if (!hasLod && distanceSqToCamera > NO_LOD_DISTANCE_SQ) {
            return false;
        }
        BedrockGunModel highPoly = display.getGunModel();
        BedrockGunModel model = null;
        ResourceLocation texture = null;
        if (highPoly != null && (!hasLod || GunsmithBenchGunBudget.claimHighPoly(benchPos, distanceSqToCamera))) {
            model = highPoly;
            texture = display.getModelTexture();
        }
        if (model == null) {
            if (!hasLod) {
                return false;
            }
            model = lod.getLeft();
            texture = lod.getRight();
        }
        Fit fit = FITS.get(model);
        if (fit == null) {
            fit = measure(model, texture, display.enablesTransparency());
            FITS.put(model, fit);
        }
        if (fit.placement() == null) {
            return false;
        }
        pendingFit = fit;
        pendingModel = model;
        return true;
    }

    /**
     * 量一个模型 (每个模型实例只量一次, 结果只取决于模型本身): 画出来的方块的包围盒与枪口参考点 → 摆法 →
     * 两个安装点的放件下沉量 → 摆放那一串常量变换的矩阵。逐方块的 AABB 只在这里用来算安装点枪顶, 算完即弃:
     * 摆法与安装点都是常量, 同一个模型的下沉量永远相同。
     */
    private static Fit measure(BedrockGunModel model, ResourceLocation texture, boolean transparent) {
        PoseStack frame = new PoseStack();
        applyFixedFrame(frame, model.getFixedOriginPath());
        FixedFrameBounds bounds = new FixedFrameBounds();
        for (BedrockPart root : model.getShouldRender()) {
            bounds.walk(root, frame, false);
        }
        GunsmithBenchGunLayout.Placement placement = bounds.place();
        float[] drops = GunsmithBenchGunLayout.placeDrops(placement, bounds.cubes, bounds.cubeCount, new float[2]);
        if (placement == null) {
            return new Fit(null, drops[0], drops[1], new Matrix4f(), new Matrix3f(), null);
        }
        PoseStack chain = new PoseStack();
        chain.translate((placement.translateX() - 8.0F) / 16.0F, (placement.translateY() - 16.0F) / 16.0F,
                (placement.translateZ() - 8.0F) / 16.0F);
        chain.mulPose(Axis.XP.rotationDegrees(placement.side().xRotationDegrees()));
        chain.scale(placement.scale(), placement.scale(), placement.scale());
        applyFixedFrame(chain, model.getFixedOriginPath());
        RenderType renderType = transparent ? RenderType.entityTranslucent(texture) : RenderType.entityCutout(texture);
        return new Fit(placement, drops[0], drops[1], new Matrix4f(chain.last().pose()),
                new Matrix3f(chain.last().normal()), renderType);
    }

    /**
     * TaCZ FIXED 分支的定位变换 (缩放取 1): renderStatic 的 translate(-0.5)、TaCZ 的 translate(0.5, 2, 0.5)、
     * scale(-1,-1,1) 与 applyPositioningNodeTransform 首尾的 ±1.5 化简之后, 就是 scale(-1,-1,1) · 骨骼链逆变换 · translate(0, -1.5, 0)。
     * 逐步照抄那个循环 (而不是 mulPoseMatrix(getPositioningNodeInverse(...))): PoseStack.mulPoseMatrix 不更新法线矩阵, 光照会错。
     * 没有 fixed 骨骼时 TaCZ 跳过定位, 只剩 translate(0, 1.5, 0) · scale(-1,-1,1)。
     */
    private static void applyFixedFrame(PoseStack poseStack, @Nullable List<BedrockPart> fixedPath) {
        if (fixedPath == null) {
            poseStack.translate(0.0F, 1.5F, 0.0F);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            return;
        }
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        for (int i = fixedPath.size() - 1; i >= 0; i--) {
            BedrockPart part = fixedPath.get(i);
            poseStack.mulPose(Axis.XN.rotation(part.xRot));
            poseStack.mulPose(Axis.YN.rotation(part.yRot));
            poseStack.mulPose(Axis.ZN.rotation(part.zRot));
            if (part.getParent() != null) {
                poseStack.translate(-part.x / 16.0F, -part.y / 16.0F, -part.z / 16.0F);
            } else {
                poseStack.translate(-part.x / 16.0F, 1.5F - part.y / 16.0F, -part.z / 16.0F);
            }
        }
        poseStack.translate(0.0F, -1.5F, 0.0F);
    }

    private static void recordFailure(ResourceLocation gunId, @Nullable GunDisplayInstance display, long now,
                                      RuntimeException e) {
        Failure previous = FAILURES.put(gunId,
                new Failure(display == null ? null : new WeakReference<>(display), now + LOOKUP_RETRY_MILLIS));
        boolean repeated = previous != null
                && (previous.display() == null ? display == null : previous.display().get() == display);
        if (!repeated) {
            LOGGER.error("TaCZ failed to draw gun {} on the gunsmith assembly bench; it stays hidden until the gun "
                    + "packs reload", gunId, e);
        }
    }

    private static void disable(LinkageError e) {
        if (!disabled) {
            disabled = true;
            LOGGER.error("The installed TaCZ does not match the API the gunsmith assembly bench was built against; "
                    + "benches show no gun for the rest of this session", e);
        }
    }

    /**
     * 在 FIXED 定位系 (未缩放 px) 里量画出来的方块 (按 HIDDEN_BONES / ATTACHMENT_ADAPTER 的结构规则) 的包围盒、
     * 逐方块 AABB 与枪口参考点。走法与 BedrockPart.render 相同: 从根骨骼起逐层 translateAndRotateAndScale;
     * 不画的子树照样往下走, 只为找枪口节点。方块取 BedrockCubeBox / BedrockCubePerFace 的 minX..maxZ 字段
     * (部件局部 px, 不含 inflate), 与 JS 预览默认的 raw 包围盒一致。
     */
    private static final class FixedFrameBounds {
        private final Vector3f corner = new Vector3f();
        private final float[] boreZ = {Float.NaN, Float.NaN, Float.NaN};
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;
        /** 每个画出来的方块 6 个数 {minX, minY, minZ, maxX, maxY, maxZ}, 见 GunsmithBenchGunLayout.placeDrops。 */
        private float[] cubes = new float[6 * 256];
        private int cubeCount;

        private void walk(BedrockPart part, PoseStack poseStack, boolean hidden) {
            boolean skip = hidden || (part.name != null && HIDDEN_BONES.contains(part.name));
            poseStack.pushPose();
            part.translateAndRotateAndScale(poseStack);
            Matrix4f pose = poseStack.last().pose();
            if (part.name != null) {
                int bore = BORE_NODES.indexOf(part.name);
                if (bore >= 0) {
                    boreZ[bore] = pose.m32() * 16.0F;
                }
            }
            if (!skip) {
                for (BedrockCube cube : part.cubes) {
                    if (cube instanceof BedrockCubeBox box) {
                        addBox(pose, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                    } else if (cube instanceof BedrockCubePerFace face) {
                        addBox(pose, face.minX, face.minY, face.minZ, face.maxX, face.maxY, face.maxZ);
                    } else {
                        throw new IllegalStateException("unknown TaCZ cube type " + cube.getClass().getName()
                                + " in bone " + part.name);
                    }
                }
            }
            // 裸枪时 attachment_adapter 的子节点全不画 (具名骨骼与带 rotation 方块的无名包装都算), 它自己的方块照画
            boolean childrenHidden = skip || ATTACHMENT_ADAPTER.equals(part.name);
            for (BedrockPart child : part.children) {
                walk(child, poseStack, childrenHidden);
            }
            poseStack.popPose();
        }

        /** 与 BedrockCube.compile 相同: 顶点 px / 16 乘姿态矩阵; 结果再乘回 16 得定位系 px。 */
        private void addBox(Matrix4f pose, float x0, float y0, float z0, float x1, float y1, float z1) {
            float cubeMinX = Float.POSITIVE_INFINITY;
            float cubeMinY = Float.POSITIVE_INFINITY;
            float cubeMinZ = Float.POSITIVE_INFINITY;
            float cubeMaxX = Float.NEGATIVE_INFINITY;
            float cubeMaxY = Float.NEGATIVE_INFINITY;
            float cubeMaxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                corner.set(((i & 1) == 0 ? x0 : x1) / 16.0F, ((i & 2) == 0 ? y0 : y1) / 16.0F,
                        ((i & 4) == 0 ? z0 : z1) / 16.0F);
                pose.transformPosition(corner);
                float x = corner.x() * 16.0F;
                float y = corner.y() * 16.0F;
                float z = corner.z() * 16.0F;
                cubeMinX = Math.min(cubeMinX, x);
                cubeMinY = Math.min(cubeMinY, y);
                cubeMinZ = Math.min(cubeMinZ, z);
                cubeMaxX = Math.max(cubeMaxX, x);
                cubeMaxY = Math.max(cubeMaxY, y);
                cubeMaxZ = Math.max(cubeMaxZ, z);
            }
            minX = Math.min(minX, cubeMinX);
            minY = Math.min(minY, cubeMinY);
            minZ = Math.min(minZ, cubeMinZ);
            maxX = Math.max(maxX, cubeMaxX);
            maxY = Math.max(maxY, cubeMaxY);
            maxZ = Math.max(maxZ, cubeMaxZ);
            int o = 6 * cubeCount;
            if (o + 6 > cubes.length) {
                cubes = Arrays.copyOf(cubes, cubes.length * 2);
            }
            cubes[o] = cubeMinX;
            cubes[o + 1] = cubeMinY;
            cubes[o + 2] = cubeMinZ;
            cubes[o + 3] = cubeMaxX;
            cubes[o + 4] = cubeMaxY;
            cubes[o + 5] = cubeMaxZ;
            cubeCount++;
        }

        @Nullable
        private GunsmithBenchGunLayout.Placement place() {
            float bore = GunsmithBenchGunLayout.NO_BORE;
            for (float z : boreZ) {
                if (!Float.isNaN(z)) {
                    bore = z;
                    break;
                }
            }
            return GunsmithBenchGunLayout.place(minX, minY, minZ, maxX, maxY, maxZ, bore);
        }
    }
}
