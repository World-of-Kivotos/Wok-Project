package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;

/**
 * 组装台台面真枪的高模名额: 同时最多 {@link #MAX_HIGH_POLY_BENCHES} 台画高模, 其余画低模 (TaCZ 每帧都在 CPU 上重建顶点,
 * 高模上千个方块、低模几十个)。只管有低模可换、高模也加载出来了的枪; 别的枪不经过这里 (见 GunsmithBenchGunRenderer.choose)。
 * <p>
 * 名额只在主画面里记账、发放: 主画面的方块实体紧接在 RenderLevelStageEvent AFTER_ENTITIES 之后画, 到 AFTER_BLOCK_ENTITIES
 * 画完 (LevelRenderer.renderLevel 里的 "blockentities" 段), 这一段就是 "主画面趟"。下面的 "上一趟" 指上一帧里同一次序的那一趟
 * (见 "多趟")。趟内一台要画高模, 这几关都得过:
 * <ol>
 *     <li>距离, 按台带滞回: 上一趟不是高模的要进到 {@link #ENTER_DISTANCE_SQ 7.5 格} 以内, 上一趟已是高模的在
 *     {@link #LEAVE_DISTANCE_SQ 8.5 格} 以内都保持, 站在边界上不会来回换模型;</li>
 *     <li>名额按台 (方块坐标) 留: 上一趟过了距离关的台里最近的 MAX 台 (排名见下一条) 各留一个名额, 这一趟还没画到的也给它留着;
 *     不在其中的台只能用没人留的空名额 (上一趟不足 MAX 台时才有), 先到先得。所以相机怎么走、各台按什么顺序画, 原来那几台都不会
 *     丢名额, 只有别的台挤进最近的 MAX 台之后才换人, 晚一趟;</li>
 *     <li>排名带滞回: 排 "最近的 MAX 台" 时, 上一趟已在其中的台按近 {@link #RANK_MARGIN 0.5 格} 算, 别的台要比其中最远的那台
 *     近出 0.5 格以上才挤得掉它; 两台差不多远时, 左右挪步、视角在画面边上抖都不会让它们来回换;</li>
 *     <li>每趟 MAX 个的硬上限 (同一台在同一趟里再画一次不另占名额)。</li>
 * </ol>
 * 多趟: 一帧里 AFTER_ENTITIES 来了不止一次 (别的模组另画一个视角, 如监控画面、传送门; 或光影在阴影趟里也触发了它) 时,
 * 各趟按在本帧里的次序 (最多分开记 {@link #MAX_MAIN_PASSES} 趟, 再多的共用最后一份) 各记各的账, 只和上一帧同次序的那一趟比,
 * 互不抢名额、互不改资格; 第一次遇到时记一次日志。
 * <p>
 * 主画面趟之外 (光影的阴影趟等, 通常在 AFTER_ENTITIES 之前) 不记账也不发名额, 只照抄这台在一趟主画面里的结果: 本帧后面还有
 * 主画面趟 (按上一帧的趟数估) 就抄接下来那一趟在上一帧的结果, 本帧的趟都画完了就抄刚收尾的那一趟。阴影、以及每个游戏刻第一次
 * 渲染时放的焊花 (开光影时往往就是阴影趟) 都和看得见的那把枪用同一个模型、同一个下沉量。
 * <p>
 * 换世界 (帧开头发现 Minecraft.level 换了一个: 换维度、进出服务器) 时清掉全部记录, 新世界里同一个方块坐标上的台不沿用旧台的
 * 资格与滞回。
 * <p>
 * 兜底: 某一帧画了组装台却一次 AFTER_ENTITIES 也没来 (渲染器不触发 Forge 的渲染阶段事件), 名额就永远发不出去、全停在低模。
 * 所以每帧开头 (RenderTickEvent START) 查一次, 遇到这种帧就从下一帧起把整帧当作主画面趟 (退回按帧记账, 阴影趟也会占名额),
 * 并记一次日志; 等 AFTER_ENTITIES 恢复了自动回到正常做法。AFTER_BLOCK_ENTITIES 没来时, 这一趟在下一次 AFTER_ENTITIES 或
 * 帧开头收尾。
 * <p>
 * 本类不碰 TaCZ: 挂在 Forge 总线的 RenderLevelStageEvent / RenderTickEvent 上, 没装 TaCZ 也照常加载。只在渲染线程上用,
 * 不加锁; 全是定长数组, 每帧不分配对象。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunsmithBenchGunBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith-assembly");

    /** 同时最多这么多台画高模 (每趟主画面)。 */
    static final int MAX_HIGH_POLY_BENCHES = 3;
    /** 上一趟不是高模的台, 相机到台面枪的距离平方 (格²) 小于它才能画高模。 */
    static final double ENTER_DISTANCE_SQ = 7.5D * 7.5D;
    /** 上一趟已是高模的台, 距离平方 (格²) 不超过它就保持高模。 */
    static final double LEAVE_DISTANCE_SQ = 8.5D * 8.5D;
    /** 排名滞回 (格): 上一趟最近的几台排名时按近这么多算。 */
    static final double RANK_MARGIN = 0.5D;
    /** 一帧里最多分开记这么多趟主画面, 再多的趟共用最后一份记录。 */
    static final int MAX_MAIN_PASSES = 4;

    /** 正在主画面趟里: AFTER_ENTITIES 到 AFTER_BLOCK_ENTITIES 之间, 兜底时是整帧。 */
    private static boolean mainPass;
    /** 本趟读写哪一份记录 (本帧第几趟主画面, 从 0 起, 封顶 MAX_MAIN_PASSES - 1)。 */
    private static int passSlot;
    /** 本帧到目前为止来过几次 AFTER_ENTITIES (兜底的整帧趟不算)。 */
    private static int passesThisFrame;
    /** 上一帧来过几次 AFTER_ENTITIES: 趟外据此判断本帧后面还有没有主画面趟。 */
    private static int passesLastFrame;

    /** 本趟已拿到名额的台。 */
    private static final long[] GRANTED_POS = new long[MAX_HIGH_POLY_BENCHES];
    private static int grantedCount;
    /**
     * 本趟到目前为止过了距离关的台里排名最近的几台, 按排名距离 (上一趟的最近几台减去 RANK_MARGIN) 升序, 同一台只留最近的那次;
     * 收尾时成为这一份记录的 PREV。
     */
    private static final long[] NEAREST_POS = new long[MAX_HIGH_POLY_BENCHES];
    private static final double[] NEAREST_DISTANCE_SQ = new double[MAX_HIGH_POLY_BENCHES];
    private static int nearestCount;
    /**
     * 每份记录 (本帧第 s 趟) 最近一次收尾时的 NEAREST_POS, 放在 [s * MAX, s * MAX + PREV_COUNT[s]): 这几台各留一个名额,
     * 排名时按近 RANK_MARGIN 算。
     */
    private static final long[] PREV_POS = new long[MAX_MAIN_PASSES * MAX_HIGH_POLY_BENCHES];
    private static final int[] PREV_COUNT = new int[MAX_MAIN_PASSES];
    /** 每份记录最近一次收尾时的 GRANTED_POS (布局同 PREV_POS): 距离滞回按它判 "已是高模", 主画面趟之外照抄它。 */
    private static final long[] LAST_GRANTED_POS = new long[MAX_MAIN_PASSES * MAX_HIGH_POLY_BENCHES];
    private static final int[] LAST_GRANTED_COUNT = new int[MAX_MAIN_PASSES];

    /** 上一次帧开头时的 Minecraft.level (只比身份); 每帧都会更新, 回到标题画面时变回 null, 不会一直拽着旧世界。 */
    private static Object lastLevel;
    /** 上一次帧开头以来有组装台来要过名额。 */
    private static boolean claimedSinceFrameStart;
    private static boolean fallbackLogged;
    private static boolean multiPassLogged;

    private GunsmithBenchGunBudget() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            afterEntities();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            endMainPass();
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            frameStart(Minecraft.getInstance().level);
        }
    }

    /**
     * AFTER_ENTITIES: 主画面的方块实体接下来就画, 主画面趟从这里开始。还开着的上一趟 (没等到 AFTER_BLOCK_ENTITIES, 或兜底的
     * 整帧趟) 先收尾; 本帧第几次来就用第几份记录, 兜底的整帧趟与本帧第一趟共用第 0 份。
     */
    static void afterEntities() {
        if (passesThisFrame >= 1 && !multiPassLogged) {
            multiPassLogged = true;
            LOGGER.info("RenderLevelStageEvent AFTER_ENTITIES fired more than once in a frame (another mod renders the level "
                    + "again, or a shader pass fires stage events); the gunsmith high-poly gun budget is kept per pass");
        }
        beginMainPass(Math.min(passesThisFrame, MAX_MAIN_PASSES - 1));
        passesThisFrame++;
    }

    /** 主画面趟开始: 还开着的上一趟先收尾, 再清空本趟的账。 */
    private static void beginMainPass(int slot) {
        endMainPass();
        mainPass = true;
        passSlot = slot;
        grantedCount = 0;
        nearestCount = 0;
    }

    /** 主画面趟收尾: 这一趟的结果写进它那一份记录; 不在趟里时什么也不做。 */
    static void endMainPass() {
        if (!mainPass) {
            return;
        }
        mainPass = false;
        int base = passSlot * MAX_HIGH_POLY_BENCHES;
        System.arraycopy(NEAREST_POS, 0, PREV_POS, base, nearestCount);
        PREV_COUNT[passSlot] = nearestCount;
        System.arraycopy(GRANTED_POS, 0, LAST_GRANTED_POS, base, grantedCount);
        LAST_GRANTED_COUNT[passSlot] = grantedCount;
    }

    /**
     * 每帧开头: 收掉还开着的趟; 世界换了就清掉全部记录; 上一帧画了组装台却没来 AFTER_ENTITIES 时, 把这一整帧当作主画面趟。
     *
     * @param level 当前的 Minecraft.level, 只比身份
     */
    static void frameStart(Object level) {
        endMainPass();
        boolean fallback = claimedSinceFrameStart && passesThisFrame == 0;
        claimedSinceFrameStart = false;
        passesLastFrame = passesThisFrame;
        passesThisFrame = 0;
        if (level != lastLevel) {
            lastLevel = level;
            Arrays.fill(PREV_COUNT, 0);
            Arrays.fill(LAST_GRANTED_COUNT, 0);
        }
        if (fallback) {
            if (!fallbackLogged) {
                fallbackLogged = true;
                LOGGER.warn("Gunsmith assembly benches were drawn in a frame without RenderLevelStageEvent "
                        + "AFTER_ENTITIES; the high-poly gun budget now counts whole frames (shadow passes included)");
            }
            beginMainPass(0);
        }
    }

    /**
     * 这台 (有低模可换) 这一次能不能画高模。主画面趟里按距离、名额、排名、硬上限判定, 能就占掉一个名额;
     * 趟外只照抄这台在一趟主画面里的结果 (见类说明), 不记账。
     *
     * @param benchPos           组装台主格的 BlockPos.asLong(): 名额、资格与滞回都按它记
     * @param distanceSqToCamera 相机到台面枪的距离平方 (格²)
     */
    static boolean claimHighPoly(long benchPos, double distanceSqToCamera) {
        claimedSinceFrameStart = true;
        int slot = mainPass ? passSlot : outsideSlot();
        int base = slot * MAX_HIGH_POLY_BENCHES;
        boolean wasHighPoly = contains(LAST_GRANTED_POS, base, LAST_GRANTED_COUNT[slot], benchPos);
        if (!mainPass) {
            return wasHighPoly;
        }
        if (contains(GRANTED_POS, 0, grantedCount, benchPos)) {
            return true;
        }
        // 写成 !(<=) / !(<): NaN 距离也不给
        if (wasHighPoly ? !(distanceSqToCamera <= LEAVE_DISTANCE_SQ) : !(distanceSqToCamera < ENTER_DISTANCE_SQ)) {
            return false;
        }
        int prevCount = PREV_COUNT[slot];
        boolean reserved = contains(PREV_POS, base, prevCount, benchPos);
        // 排名滞回: 上一趟的最近几台排名时按近 RANK_MARGIN 格算, 别的台要明显更近才挤得掉它
        double rankDistanceSq = distanceSqToCamera;
        if (reserved) {
            double rankDistance = Math.max(0.0D, Math.sqrt(distanceSqToCamera) - RANK_MARGIN);
            rankDistanceSq = rankDistance * rankDistance;
        }
        recordCandidate(benchPos, rankDistanceSq);
        // 上一趟的最近几台各留一个名额 (这一趟还没画到的也留着), 别的台只能用剩下的: 先画到的新台抢不走老台的名额
        if (grantedCount >= MAX_HIGH_POLY_BENCHES
                || (!reserved && grantedCount + unclaimedReserved(base, prevCount) >= MAX_HIGH_POLY_BENCHES)) {
            return false;
        }
        GRANTED_POS[grantedCount++] = benchPos;
        return true;
    }

    /**
     * 趟外照抄哪一份记录: 本帧后面还有主画面趟 (本帧来过的 AFTER_ENTITIES 少于上一帧) 就抄接下来那一趟的 (如画在它前面的
     * 阴影趟), 否则抄本帧刚收尾的那一趟。
     */
    private static int outsideSlot() {
        int slot = passesThisFrame < passesLastFrame ? passesThisFrame : passesThisFrame - 1;
        return Math.max(0, Math.min(slot, MAX_MAIN_PASSES - 1));
    }

    /** 这份记录里留了名额、这一趟还没来要的台数。 */
    private static int unclaimedReserved(int base, int prevCount) {
        int unclaimed = 0;
        for (int i = base; i < base + prevCount; i++) {
            if (!contains(GRANTED_POS, 0, grantedCount, PREV_POS[i])) {
                unclaimed++;
            }
        }
        return unclaimed;
    }

    private static boolean contains(long[] positions, int from, int count, long benchPos) {
        for (int i = from; i < from + count; i++) {
            if (positions[i] == benchPos) {
                return true;
            }
        }
        return false;
    }

    /** 把这台放进 "排名最近的几台" (同一台只留最近的那次), 保持升序。 */
    private static void recordCandidate(long benchPos, double distanceSq) {
        for (int i = 0; i < nearestCount; i++) {
            if (NEAREST_POS[i] == benchPos) {
                if (distanceSq >= NEAREST_DISTANCE_SQ[i]) {
                    return;
                }
                System.arraycopy(NEAREST_POS, i + 1, NEAREST_POS, i, nearestCount - i - 1);
                System.arraycopy(NEAREST_DISTANCE_SQ, i + 1, NEAREST_DISTANCE_SQ, i, nearestCount - i - 1);
                nearestCount--;
                break;
            }
        }
        int slot;
        if (nearestCount < MAX_HIGH_POLY_BENCHES) {
            slot = nearestCount++;
        } else if (distanceSq < NEAREST_DISTANCE_SQ[MAX_HIGH_POLY_BENCHES - 1]) {
            slot = MAX_HIGH_POLY_BENCHES - 1;
        } else {
            return;
        }
        while (slot > 0 && NEAREST_DISTANCE_SQ[slot - 1] > distanceSq) {
            NEAREST_POS[slot] = NEAREST_POS[slot - 1];
            NEAREST_DISTANCE_SQ[slot] = NEAREST_DISTANCE_SQ[slot - 1];
            slot--;
        }
        NEAREST_POS[slot] = benchPos;
        NEAREST_DISTANCE_SQ[slot] = distanceSq;
    }
}
