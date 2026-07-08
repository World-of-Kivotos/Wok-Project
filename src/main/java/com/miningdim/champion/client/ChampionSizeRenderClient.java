package com.miningdim.champion.client;

import com.miningdim.core.MiningConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冠军体型客户端渲染 + 碰撞箱缩放 (ChampionStarAffix spec 9A.3 #17; 批4 波3, 精英怪首个客户端渲染件)。冠军
 * capability 不同步客户端, 客户端无从得知体型词条, 故服务端经 {@code ChampionSizeS2C} 下发【最终守卫后的尺寸系数】,
 * 本类缓存 {@code entityId -> scale} 并在两处消费:
 * <ul>
 *   <li>{@link #onRenderLivingPre}/{@link #onRenderLivingPost}: 渲染前 poseStack 缩放模型、渲染后还原 (纯原版
 *       客户端可见, 不改模型资源);</li>
 *   <li>{@link #onEntitySizeClient}: 客户端 {@code EntityEvent.Size} 同样按缓存缩碰撞箱, 与服务端 AABB 一致
 *       (两端碰撞箱一致防幽灵卡位 —— 服务端已放大而客户端未放大会出现"看得见打不到/卡进模型"的幽灵位)。</li>
 * </ul>
 *
 * <p>缓存生命周期: {@link #accept} (S2C handler 客户端主线程调) 存入并触发该实体 refreshDimensions 即时缩碰撞箱;
 * {@link #onEntityLeaveLevel} 实体卸载/维度切换 (客户端侧) 摘除。全部访问均在客户端主线程 (网络包经 enqueueWork
 * 切主线程, 渲染/Size/Leave 事件本就主线程), 用 ConcurrentHashMap 仅为对潜在异步读的防御, 非跨线程协作。
 *
 * <p>已知副作用 (首版接受, spec 9A.3 报备): 阴影半径由渲染器 {@code shadowRadius} 常量决定, 不随本缩放变化
 * (巨大化冠军脚下阴影偏小); 名牌高度按缩放后碰撞箱顶推高 (巨大化名牌抬高、缩小化压低), 属预期偏移。二者不影响
 * 命中/碰撞正确性 (碰撞箱两端一致), 后续如需精修另开工单。
 *
 * <p>仅客户端逻辑端加载 (Dist.CLIENT 静态订阅, 与 {@code MinerHighlightRenderer} 同范式); 不持任何服务端态,
 * 只渲染 S2C 携带的服务端权威结果。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChampionSizeRenderClient {

    /** 系数与 1.0 的判等容差: 服务端不发 1.0 包, 但浮点回读/退化档 (守卫降到极近 1) 用容差判"无需缩放"防无谓 push/pop。 */
    private static final float SCALE_EPSILON = 1.0e-4F;

    /** entityId -> 体型尺寸系数缓存 (客户端主线程读写; ConcurrentHashMap 仅防御性)。 */
    private static final Map<Integer, Float> SCALE_BY_ENTITY = new ConcurrentHashMap<>();

    private ChampionSizeRenderClient() {
    }

    /**
     * 由 {@code ChampionSizeS2C} 客户端 handler 在客户端主线程调用: 缓存体型系数并触发该实体 refreshDimensions,
     * 使客户端碰撞箱按新系数即时缩放 (否则要等下一次 pose 变化才缩)。实体尚未在客户端生成时只存缓存, 待其生成后
     * 自身的 Size 事件会读到本缓存。
     *
     * @param entityId 目标实体网络 id
     * @param scale    体型尺寸系数 (巨大化 &gt;1 / 缩小化 &lt;1)
     */
    public static void accept(int entityId, float scale) {
        SCALE_BY_ENTITY.put(entityId, scale);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity != null) {
                entity.refreshDimensions(); // 触发 onEntitySizeClient 读缓存缩碰撞箱 (即时生效)。
            }
        }
    }

    /** 缓存系数 (无缓存 = 1.0 = 不缩)。 */
    private static float scaleFor(int entityId) {
        Float f = SCALE_BY_ENTITY.get(entityId);
        return f == null ? 1.0F : f;
    }

    /** 系数是否需要缩放 (与 1.0 差异超容差)。 */
    private static boolean isScaled(float scale) {
        return Math.abs(scale - 1.0F) > SCALE_EPSILON;
    }

    /**
     * 渲染前: 若该实体有体型系数则 pushPose + 以脚位为原点整体缩放模型。与 {@link #onRenderLivingPost} 的 popPose
     * 严格配对 —— 二者对同一实体在同一渲染帧读同一缓存值 (客户端主线程单线程, 帧内缓存不变), 故 push 必有对应 pop;
     * 系数为 1.0 时两处都不动 pose (不缩放的实体零开销)。
     *
     * <p>LOWEST 优先级 (审查修复): Pre 是 @Cancelable 且被 cancel 时 vanilla 渲染器直接 return 【不 post Post】——
     * 若本类先 push 而后续监听器 cancel, popPose 永不执行, 本帧共享 PoseStack 失衡殃及之后所有实体渲染。挂 LOWEST
     * (不收 canceled) 让一切取消者先裁决: 事件被取消则本类根本不 push, 存活则 Post 必达成对。残余缝隙 (同 LOWEST
     * 桶内注册序在我之后的取消者) 属极端装载组合, 报备接受。
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        float scale = scaleFor(event.getEntity().getId());
        if (!isScaled(scale)) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.scale(scale, scale, scale);
    }

    /** 渲染后: 与 {@link #onRenderLivingPre} 配对还原 pose (同一缓存值判定, 保证 push/pop 平衡)。 */
    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        float scale = scaleFor(event.getEntity().getId());
        if (!isScaled(scale)) {
            return;
        }
        event.getPoseStack().popPose();
    }

    /**
     * 客户端碰撞箱缩放: 按缓存系数缩 {@code EntityEvent.Size} 的新尺寸 (含 eyeHeight), 使客户端 AABB 与服务端一致。
     * 仅客户端侧生效 (集成服务器同 JVM 时本 forge 事件对服务端实体亦触发, 用 isClientSide 闸掉, 服务端 AABB 由
     * {@code ChampionSizeHandler} 按 capability 权威缩放)。
     */
    @SubscribeEvent
    @SuppressWarnings("removal") // EntityEvent.Size 在 1.20.1 deprecated-for-removal, 但为该版本唯一体型钩子 (无替代)。
    public static void onEntitySizeClient(EntityEvent.Size event) {
        Level lvl = event.getEntity().level();
        // 构造期 Size 事件 (spec 报备: 也在 Entity 构造器触发) level 已置但实体未完全初始化; 服务端实体走
        // ChampionSizeHandler 的 capability 权威路径。二者均在此早退。
        if (lvl == null || !lvl.isClientSide()) {
            return;
        }
        float scale = scaleFor(event.getEntity().getId());
        if (!isScaled(scale)) {
            return;
        }
        EntityDimensions base = event.getNewSize();
        event.setNewSize(base.scale(scale), true); // updateEyeHeight=true: 眼位随体型同步缩放。
    }

    /** 实体卸载/维度切换 (客户端侧): 摘缓存防泄漏 (entityId 会随重进世界复用, 不清会张冠李戴)。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        SCALE_BY_ENTITY.remove(event.getEntity().getId());
    }

    /**
     * 客户端世界整体卸载 (断线/退出存档/换维度): 整表清空 (审查修复)。断线走 Minecraft.clearLevel 时旧 ClientLevel
     * 被整体丢弃【不逐实体触发 EntityLeaveLevelEvent】, 上面的逐实体清理对该路径失效 —— 残留条目会在下一个会话里
     * 与重新分配的 entityId 撞车, 把无关实体渲染成巨怪/错缩客户端碰撞箱 (张冠李戴)。换维度整清亦正确: 新维度可见的
     * 冠军由 StartTracking 补发重建缓存。
     */
    @SubscribeEvent
    public static void onClientLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() == null || !event.getLevel().isClientSide()) {
            return;
        }
        SCALE_BY_ENTITY.clear();
    }
}
