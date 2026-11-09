package com.miningdim.job.tarot.codex;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 塔罗图鉴门面：发牌入口记录、登录补扫、进度摘要。只读/写 {@link TarotCodexSavedData}。
 *
 * <p>本包不依赖社交模块（职业代码不得反向依赖展示层）。需要“图鉴里程碑 → 平板主题解锁”的一方，
 * 用 {@link #addListener(MilestoneListener)} 注册回调，由本类在点亮新形态后统一通知；回调异常
 * 向调用方传播，避免图鉴联动失败被静默掩盖。</p>
 */
public final class TarotCodex {
    /** 图鉴变化回调（服务端线程；player 一定在线）。 */
    public interface MilestoneListener {
        void onCodexChanged(ServerPlayer player);
    }

    private static final List<MilestoneListener> LISTENERS = new CopyOnWriteArrayList<>();

    private TarotCodex() {
    }

    public static void addListener(MilestoneListener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    /** 发牌/合成/兑换之后调用；返回是否点亮了新形态（重复牌转碎片时不会重复点亮）。 */
    public static boolean record(ServerPlayer player, int cardId, TarotQuality quality, boolean upright) {
        // 所有发牌入口共用此处，创造模式开包和合成也不能计入收集成就。
        if (player.isCreative()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        boolean fresh = TarotCodexSavedData.get(server).record(player.getUUID(), cardId, quality, upright);
        if (fresh) {
            notifyChanged(player);
        }
        return fresh;
    }

    /** 记录一张已构造的塔罗牌（身份不可读的裸牌跳过）。 */
    public static boolean record(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof TarotCardItem)
                || !TarotCardItem.hasReadableCardIdentity(stack)) {
            return false;
        }
        return record(player, TarotCardItem.cardId(stack), TarotCardItem.quality(stack),
                TarotCardItem.upright(stack));
    }

    /** 登录补扫：背包 + 副手（只能补“当前还看得到”的牌，已合成消耗掉的历史追不回）。 */
    public static void scan(ServerPlayer player) {
        // 创造模式给物会污染图鉴（规格 v0.1 §2.1）：创造玩家不补扫，回生存后自然按新获得记录。
        if (player.isCreative()) {
            return;
        }
        for (ItemStack stack : player.getInventory().items) {
            record(player, stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            record(player, stack);
        }
        // 补扫完统一再判一次里程碑：老玩家点亮了图鉴却没解锁主题时，登录即可补上。
        notifyChanged(player);
    }

    /** 手动触发一次回调（客户端请求开机数据、配置开关变更后重算等）。 */
    public static void notifyChanged(ServerPlayer player) {
        for (MilestoneListener listener : LISTENERS) {
            listener.onCodexChanged(player);
        }
    }

    /** 进度摘要；无数据时返回全零。 */
    public static Summary summary(ServerPlayer player) {
        TarotCodexSavedData data = TarotCodexSavedData.get(player.getServer());
        return new Summary(data.arcana(player.getUUID()), data.shinyForms(player.getUUID()),
                data.count(player.getUUID()), TarotCodexSavedData.FORMS);
    }

    /**
     * @param arcana     已点亮的 cardId 数（0..22）
     * @param shinyForms 已点亮的闪耀形态数（0..44）；&gt;0 即“拥有闪耀”
     * @param count      已点亮的形态数（0..220）
     * @param total      形态总数（220）
     */
    public record Summary(int arcana, int shinyForms, int count, int total) {
        public int arcanaTotal() {
            return TarotArcana.COUNT;
        }

        public boolean shiny() {
            return shinyForms > 0;
        }
    }
}
