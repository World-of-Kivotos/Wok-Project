package com.miningdim.district.service;

import com.miningdim.district.access.Actor;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.notice.DistrictNoticeKind;
import com.miningdim.economy.Currency;
import com.miningdim.economy.IEconomyService;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * 买地 (plot.buy, 设计文档 6.5): 直接购买, 先到先得; 扣款、过户、两条记录在同一个数据库事务里。
 *
 * 同一条连接: 生产环境里经济门面的 tryCharge 走 SqliteEconomyLedger.tryDebit, 用的正是 MiningStore.connection(),
 * 与本模块的仓储是同一条连接 —— 它在我们的事务里只执行不提交, 外层回滚时扣款一起撤销。钱暂时直接销毁 (sink, P9):
 * 没有出款方, 也没有流水表; 审计靠本区记录 buyPlot、地块记录 purchase 与审计日志的一行 INFO。
 * Flan 写失败不退款 (P6): 地块显示 failed, 之后任何一次改动或 /district resync 时整块重写。
 */
public final class PlotMarketService extends ServiceSupport {

    /** plot.buy 的结果。 */
    public record BuyResult(PlotRecord plot, long price, long balanceAfter, DistrictLogEntry logEntry) {
    }

    PlotMarketService(DistrictContext ctx) {
        super(ctx);
    }

    /**
     * 买家确认时看到的东西 (范围与价格) 两样都钉住: 只钉价格的话, 区务长在买家确认期间把这块空置地块挪到别处或改成
     * 同面积的另一个形状, 买家会按同一个价格买到另一片地。
     *
     * @param buyer          买家永远是调用者本人 (契约里没有"替谁买"的参数)
     * @param wallet         买家的在线玩家对象 (经济门面按它扣款), UUID 必须与 buyer 相同
     * @param expectedBounds 玩家确认时看到的地块范围 (与现在的范围不同 -&gt; PLOT_CHANGED)
     * @param expectedPrice  玩家确认时看到的价格
     */
    public BuyResult buy(Actor buyer, ServerPlayer wallet, String districtId, String plotId, PlotArea expectedBounds,
                         long expectedPrice) {
        if (buyer.uuid() == null || !buyer.uuid().equals(wallet.getUUID())) {
            throw new IllegalArgumentException("the wallet must belong to the buyer");
        }
        requireNoOpenTransaction("plot.buy");
        sweep(districtId);
        IEconomyService economy = ctx.economyOrNull();
        long at = ctx.now();
        record Bought(BuyResult result, long balanceBefore) {
        }
        Bought bought = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            PlotRecord plot = requirePlot(district, plotId);
            DistrictError block = buyBlock(district, buyer);
            if (block != null) {
                throw blocked(block, district, buyer);
            }
            if (plot.frozen()) {
                throw new DistrictRuleException(DistrictError.PLOT_FROZEN, plot.code() + " 冻结中，不能买",
                        DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
            }
            if (plot.owned()) {
                throw occupied(plot);
            }
            // 范围排在价格之前: 改了形状的地块价格多半也变了, 这时"范围变了"才是买家该先知道的那件事。
            if (!plot.area().equals(expectedBounds)) {
                PlotArea now = plot.area();
                throw new DistrictRuleException(DistrictError.PLOT_CHANGED,
                        plot.code() + " 的范围刚被调整过，现在是 " + now.sideText() + "（X " + now.minX() + " ~ "
                                + now.maxX() + "，Z " + now.minZ() + " ~ " + now.maxZ() + "），请看清新的范围和价格再确认",
                        DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code(),
                                "minX", String.valueOf(now.minX()), "minZ", String.valueOf(now.minZ()),
                                "maxX", String.valueOf(now.maxX()), "maxZ", String.valueOf(now.maxZ())));
            }
            long price = district.priceOf(plot.area());
            if (expectedPrice != price) {
                throw new DistrictRuleException(DistrictError.PRICE_CHANGED,
                        "价格刚变了，现在是 " + DistrictTexts.formatCredit(price) + "，请按新价格重新确认",
                        DistrictRuleException.params("expectedPrice", String.valueOf(expectedPrice),
                                "price", String.valueOf(price)));
            }
            if (economy == null) {
                throw new DistrictRuleException(DistrictError.ECONOMY_OFFLINE, "经济系统未就绪");
            }
            long balance = economy.creditBalance(wallet);
            if (!economy.tryCharge(wallet, Currency.CREDIT, price)) {
                throw new DistrictRuleException(DistrictError.INSUFFICIENT_FUNDS,
                        "余额不足：还差 " + DistrictTexts.formatCredit(price - balance),
                        DistrictRuleException.params("cost", String.valueOf(price), "currency", "CREDIT",
                                "balance", String.valueOf(balance)));
            }
            MemberRecord member = memberOfDistrict(district, buyer.uuid()).orElseThrow();
            // 条件 UPDATE: 0 行说明刚被别人买走, 连同扣款一起回滚。
            if (!repo.assignOwner(plot.plotId(), member.uuid(), member.name())) {
                throw occupied(repo.plot(plot.plotId()).orElse(plot));
            }
            repo.deleteFriends(plot.plotId());
            repo.resetPlotCells(plot.plotId());
            repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
            // 区务长自己买地也记 resident (P3): 买地是以住户身份做的事, 不是管理动作。
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, buyer.uuid(),
                    member.name(), DistrictActorRole.RESIDENT, DistrictLogAction.BUY_PLOT, plot.code(),
                    DistrictTexts.formatCredit(price), null, null));
            writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure(), at, buyer.uuid(),
                    member.name(), PlotActorRole.OWNER, PlotLogAction.PURCHASE, member.name(),
                    DistrictTexts.formatCredit(price), null, null, false));
            repo.afterCommit(() -> ctx.flanSync().writePlotState(plot.plotId()));
            // 买家就是操作人, 仍然发 (22.10: 服主点名要给买家); 买家通常在线, 提交后立即送达。
            ctx.notices().enqueue(member.uuid(), DistrictNoticeKind.PLOT_BOUGHT,
                    List.of(plot.code(), DistrictTexts.formatCredit(price)), district.districtId(), plot.plotId());
            return new Bought(new BuyResult(plot, price, economy.creditBalance(wallet), log), balance);
        });
        AUDIT.info("[miningdim] {} ({}) bought plot {} in {} for {} credit (balance {} -> {})", buyer.name(),
                buyer.uuid(), plotId, districtId, bought.result().price(), bought.balanceBefore(),
                bought.result().balanceAfter());
        return new BuyResult(repo.plot(plotId).orElse(bought.result().plot()), bought.result().price(),
                bought.result().balanceAfter(), bought.result().logEntry());
    }

    /**
     * 查看者现在为什么不能买地 (与 {@link #buy} 的拒绝同一顺序, district.plots market.viewerBlock); null = 能买。
     * OP 恒为 NOT_RESIDENT, 即使 TA 在名单上 (OP 只能经解冻拿到地)。一人一块地对全部地块生效, 所以"已有地块"按全库判。
     */
    @Nullable
    public DistrictError buyBlock(DistrictRecord district, Actor viewer) {
        if (viewer.op() || memberOfDistrict(district, viewer.uuid()).isEmpty()) {
            return DistrictError.NOT_RESIDENT;
        }
        if (repo.plotOwnedBy(viewer.uuid()).isPresent()) {
            return DistrictError.ALREADY_OWNS_PLOT;
        }
        if (frozenPlotOf(district, viewer).isPresent()) {
            return DistrictError.HAS_FROZEN_PLOT;
        }
        return district.purchaseOpen() ? null : DistrictError.PURCHASE_CLOSED;
    }

    private Optional<PlotRecord> frozenPlotOf(DistrictRecord district, Actor viewer) {
        List<PlotRecord> frozen = repo.frozenPlotsOf(viewer.uuid());
        return frozen.stream().filter(plot -> plot.districtId().equals(district.districtId())).findFirst();
    }

    private DistrictRuleException blocked(DistrictError block, DistrictRecord district, Actor buyer) {
        return switch (block) {
            case NOT_RESIDENT -> new DistrictRuleException(block, "只有本区住户能买本区的地块",
                    DistrictRuleException.params("districtId", district.districtId()));
            case ALREADY_OWNS_PLOT -> {
                PlotRecord owned = repo.plotOwnedBy(buyer.uuid()).orElseThrow();
                yield new DistrictRuleException(block, "你已经有一块地了，一人最多一块",
                        DistrictRuleException.params("plotId", owned.plotId(), "code", owned.code()));
            }
            case HAS_FROZEN_PLOT -> {
                PlotRecord frozen = frozenPlotOf(district, buyer).orElseThrow();
                yield new DistrictRuleException(block, "你原来的地块还在冻结中，请先找管理员解除冻结或收回",
                        DistrictRuleException.params("plotId", frozen.plotId(), "code", frozen.code()));
            }
            case PURCHASE_CLOSED -> new DistrictRuleException(block, "本区暂未开放购买",
                    DistrictRuleException.params("districtId", district.districtId()));
            default -> throw new IllegalStateException("not a buy block: " + block);
        };
    }

    private static DistrictRuleException occupied(PlotRecord plot) {
        return new DistrictRuleException(DistrictError.PLOT_OCCUPIED,
                plot.code() + " 已经被 " + plot.ownerName() + " 买下了（先到先得）",
                DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code(),
                        "ownerName", String.valueOf(plot.ownerName())));
    }
}
