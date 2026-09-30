package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.store.DistrictStoreException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.onlinePlayer;
import static com.miningdim.district.DistrictTestEnv.op;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.removePlayer;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 买地 (plot.buy, 设计文档 6.5): 检查顺序、先到先得、扣款与过户与两条记录同一个事务、Flan 写失败不退款。
 * 地块 (10,10)-(25,25) 面积 256, 默认单价 5, 价格 1,280 信用点。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlotMarketGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_plot_market";
    private static final long PRICE = 1280L;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyChecksInOrder(GameTestHelper helper) {
        ServerPlayer buyer = null;
        ServerPlayer outsider = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Owner_Two");
            String vacant = env.plot("abydos", 10, 10, 25, 25);
            String frozen = env.plot("abydos", 40, 10, 55, 25);
            String owned = env.plot("abydos", 70, 10, 85, 25);
            env.own(frozen, "Owner_One");
            env.own(owned, "Owner_Two");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            env.fund("Buyer_Bo", 10_000);
            buyer = onlinePlayer(helper, "Buyer_Bo");
            outsider = onlinePlayer(helper, "Outsider_Oz");
            ServerPlayer wallet = buyer;
            ServerPlayer outsiderWallet = outsider;
            PlotMarketService market = env.ctx.market();
            Actor bo = player("Buyer_Bo");

            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "不存在的区",
                    () -> market.buy(bo, wallet, "nowhere", vacant, env.area(vacant), PRICE));
            expect(helper, DistrictError.PLOT_NOT_FOUND, "不存在的地块",
                    () -> market.buy(bo, wallet, "abydos", "abydos-99", env.area(vacant), PRICE));
            DistrictRuleException notResident = expect(helper, DistrictError.NOT_RESIDENT, "外人",
                    () -> market.buy(player("Outsider_Oz"), outsiderWallet, "abydos", vacant, env.area(vacant), PRICE));
            helper.assertTrue(notResident.getMessage().equals("只有本区住户能买本区的地块"), "文案");
            DistrictRuleException closed = expect(helper, DistrictError.PURCHASE_CLOSED, "未开放购买 (排在地块状态之前)",
                    () -> market.buy(bo, wallet, "abydos", frozen, env.area(frozen), PRICE));
            helper.assertTrue(closed.getMessage().equals("本区暂未开放购买"), "文案");

            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            DistrictRuleException isFrozen = expect(helper, DistrictError.PLOT_FROZEN, "冻结中的地块",
                    () -> market.buy(bo, wallet, "abydos", frozen, env.area(frozen), PRICE));
            helper.assertTrue(isFrozen.getMessage().equals("阿拜多斯-02 冻结中，不能买"), "文案");
            DistrictRuleException occupied = expect(helper, DistrictError.PLOT_OCCUPIED, "已有户主",
                    () -> market.buy(bo, wallet, "abydos", owned, env.area(owned), PRICE));
            helper.assertTrue(occupied.getMessage().equals("阿拜多斯-03 已经被 Owner_Two 买下了（先到先得）"), "文案");
            DistrictRuleException changed = expect(helper, DistrictError.PRICE_CHANGED, "价格不符",
                    () -> market.buy(bo, wallet, "abydos", vacant, env.area(vacant), PRICE - 1));
            helper.assertTrue(changed.getMessage().equals("价格刚变了，现在是 1,280 信用点，请按新价格重新确认")
                            && "1280".equals(changed.params().get("price")),
                    "文案与 params, 实为 " + changed.getMessage());
            helper.assertTrue(env.balance("Buyer_Bo") == 10_000, "被拒的请求不扣钱");
        } finally {
            removePlayer(helper, buyer);
            removePlayer(helper, outsider);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void alreadyOwnsAndFrozenPlotBlocks(GameTestHelper helper) {
        ServerPlayer owner = null;
        ServerPlayer comeback = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Comeback_Cy");
            String first = env.plot("abydos", 10, 10, 25, 25);
            String second = env.plot("abydos", 40, 10, 55, 25);
            String third = env.plot("abydos", 70, 10, 85, 25);
            env.own(first, "Owner_One");
            env.own(second, "Comeback_Cy");
            env.ctx.residents().removeResident(env.admin, "abydos", "Comeback_Cy", "inactive", "不上线");
            env.ctx.residents().addResident(env.admin, "abydos", "Comeback_Cy", false);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Owner_One", 10_000);
            env.fund("Comeback_Cy", 10_000);
            owner = onlinePlayer(helper, "Owner_One");
            comeback = onlinePlayer(helper, "Comeback_Cy");
            ServerPlayer ownerWallet = owner;
            ServerPlayer comebackWallet = comeback;
            DistrictRuleException already = expect(helper, DistrictError.ALREADY_OWNS_PLOT, "已有地块",
                    () -> env.ctx.market().buy(player("Owner_One"), ownerWallet, "abydos", third,
                            env.area(third), PRICE));
            helper.assertTrue(already.getMessage().equals("你已经有一块地了，一人最多一块")
                    && first.equals(already.params().get("plotId")), "文案与 params, 实为 " + already.params());
            DistrictRuleException frozen = expect(helper, DistrictError.HAS_FROZEN_PLOT, "原来的地块还在冻结",
                    () -> env.ctx.market().buy(player("Comeback_Cy"), comebackWallet, "abydos", third,
                            env.area(third), PRICE));
            helper.assertTrue(frozen.getMessage().equals("你原来的地块还在冻结中，请先找管理员解除冻结或收回"), "文案");
        } finally {
            removePlayer(helper, owner);
            removePlayer(helper, comeback);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opIsAlwaysNotResident(GameTestHelper helper) {
        ServerPlayer olga = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Op_Olga");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Op_Olga", 10_000);
            olga = onlinePlayer(helper, "Op_Olga");
            ServerPlayer wallet = olga;
            expect(helper, DistrictError.NOT_RESIDENT, "OP 即使在名单上也不能买",
                    () -> env.ctx.market().buy(op("Op_Olga"), wallet, "abydos", plot, env.area(plot), PRICE));
            DistrictQueryService.MarketView market = env.ctx.queries().plots(op("Op_Olga"), olga, "abydos").market();
            helper.assertTrue(market.viewerBlock() == DistrictError.NOT_RESIDENT && market.viewerBalance() == null,
                    "district.plots viewerBlock 对 OP 恒为 NOT_RESIDENT, 余额不给, 实为 " + market);
        } finally {
            removePlayer(helper, olga);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void viewerBlockOrderMatchesBuyChecks(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.fund("Buyer_Bo", 4321);
            bo = onlinePlayer(helper, "Buyer_Bo");
            DistrictQueryService queries = env.ctx.queries();
            helper.assertTrue(queries.plots(player("Owner_One"), null, "abydos").market().viewerBlock()
                    == DistrictError.ALREADY_OWNS_PLOT, "户主: ALREADY_OWNS_PLOT");
            DistrictQueryService.MarketView closed = queries.plots(player("Buyer_Bo"), bo, "abydos").market();
            helper.assertTrue(closed.viewerBlock() == DistrictError.PURCHASE_CLOSED && closed.viewerBalance() == null
                            && !closed.open() && closed.unitPrice() == 5,
                    "未开放: PURCHASE_CLOSED, 不给余额, 实为 " + closed);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            DistrictQueryService.MarketView open = queries.plots(player("Buyer_Bo"), bo, "abydos").market();
            helper.assertTrue(open.viewerBlock() == null && open.viewerBalance() != null && open.viewerBalance() == 4321,
                    "能买时给出余额, 实为 " + open);
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            env.ctx.residents().addResident(env.admin, "abydos", "Owner_One", false);
            helper.assertTrue(queries.plots(player("Owner_One"), null, "abydos").market().viewerBlock()
                    == DistrictError.HAS_FROZEN_PLOT, "原户主回来、地块还冻结: HAS_FROZEN_PLOT");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void priceChangedWhenUnitPriceMoved(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 10_000);
            env.ctx.admin().setPlotPricing(env.admin, "abydos", 6L, 8L, 48L);
            bo = onlinePlayer(helper, "Buyer_Bo");
            ServerPlayer wallet = bo;
            DistrictRuleException changed = expect(helper, DistrictError.PRICE_CHANGED, "单价刚改过",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, env.area(plot), PRICE));
            helper.assertTrue("1536".equals(changed.params().get("price"))
                    && "1280".equals(changed.params().get("expectedPrice")), "params, 实为 " + changed.params());
            PlotMarketService.BuyResult bought = env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot,
                    env.area(plot), 1536L);
            helper.assertTrue(bought.price() == 1536L, "按新价格买成");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    /**
     * 买家确认期间区务长把这块空置地块挪走或改了形状 (面积不变, 价格也不变): 只钉价格的话, 买家会按同一个价格买到另一片地。
     * 确认时看到的范围与现在的不符一律 PLOT_CHANGED, 且排在 PRICE_CHANGED 之前; 被拒的请求一分钱不扣、一行不写。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotChangedWhenWardenMovesOrReshapesVacantPlot(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 10_000);
            bo = onlinePlayer(helper, "Buyer_Bo");
            ServerPlayer wallet = bo;
            PlotArea seen = env.area(plot);

            // 同样 16 × 16、同样 1,280 信用点, 只是挪到了 X 40 ~ 55。
            env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot,
                    PlotLayoutService.AreaInput.of(new PlotArea(40, 10, 55, 25)));
            int logRows = env.repo.districtLog("abydos", 100).size();
            DistrictRuleException moved = expect(helper, DistrictError.PLOT_CHANGED, "地块被挪走",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, seen, PRICE));
            helper.assertTrue(moved.getMessage().equals(
                            "阿拜多斯-01 的范围刚被调整过，现在是 16 × 16（X 40 ~ 55，Z 10 ~ 25），请看清新的范围和价格再确认")
                            && plot.equals(moved.params().get("plotId")) && "40".equals(moved.params().get("minX"))
                            && "55".equals(moved.params().get("maxX")) && "25".equals(moved.params().get("maxZ")),
                    "文案与 params 带现在的范围, 实为 " + moved.getMessage() + " / " + moved.params());
            helper.assertTrue(env.balance("Buyer_Bo") == 10_000 && env.plotRecord(plot).vacant()
                            && env.repo.districtLog("abydos", 100).size() == logRows,
                    "被拒的请求不扣钱、不过户、不写记录");

            // 面积不变的另一个形状 (32 × 8 = 256), 价格仍是 1,280: 照样拒。
            PlotArea movedArea = env.area(plot);
            env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot,
                    PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 41, 17)));
            expect(helper, DistrictError.PLOT_CHANGED, "同面积改形状",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, movedArea, PRICE));

            // 改大了 (价格也变了): 先报范围变了, 不报价格变了。
            PlotArea reshaped = env.area(plot);
            env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot,
                    PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 29, 25)));
            expect(helper, DistrictError.PLOT_CHANGED, "范围与价格都变了时先报范围",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, reshaped, PRICE));
            expect(helper, DistrictError.PRICE_CHANGED, "范围对上了再核价格",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, env.area(plot), PRICE));

            PlotMarketService.BuyResult bought = env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot,
                    new PlotArea(10, 10, 29, 25), 1600L);
            helper.assertTrue(bought.plot().owned() && bought.price() == 1600L && env.balance("Buyer_Bo") == 8400,
                    "按确认时看到的范围与价格买成, 实为 " + bought.price());
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void insufficientFundsWritesNothing(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 1000);
            int logRows = env.repo.districtLog("abydos", 100).size();
            bo = onlinePlayer(helper, "Buyer_Bo");
            ServerPlayer wallet = bo;
            DistrictRuleException poor = expect(helper, DistrictError.INSUFFICIENT_FUNDS, "余额不足",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, env.area(plot), PRICE));
            helper.assertTrue(poor.getMessage().equals("余额不足：还差 280 信用点")
                            && "1280".equals(poor.params().get("cost")) && "CREDIT".equals(poor.params().get("currency"))
                            && "1000".equals(poor.params().get("balance")),
                    "文案与 params, 实为 " + poor.getMessage() + " / " + poor.params());
            helper.assertTrue(env.balance("Buyer_Bo") == 1000 && env.plotRecord(plot).vacant()
                            && env.repo.districtLog("abydos", 100).size() == logRows,
                    "余额、地块、记录都不变");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void economyOfflineIsReported(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.openWithoutEconomy()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            bo = onlinePlayer(helper, "Buyer_Bo");
            ServerPlayer wallet = bo;
            expect(helper, DistrictError.ECONOMY_OFFLINE, "经济门面取不到",
                    () -> env.ctx.market().buy(player("Buyer_Bo"), wallet, "abydos", plot, env.area(plot), PRICE));
            helper.assertTrue(env.ctx.queries().plots(player("Buyer_Bo"), bo, "abydos").market().viewerBalance() == null,
                    "经济门面不在线时 viewerBalance 为 null");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void successDebitsAssignsAndLogsBoth(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 5000);
            bo = onlinePlayer(helper, "Buyer_Bo");
            PlotMarketService.BuyResult bought = env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot,
                    env.area(plot), PRICE);
            helper.assertTrue(bought.price() == PRICE && bought.balanceAfter() == 3720 && env.balance("Buyer_Bo") == 3720,
                    "扣款 1,280, 回执的余额与账本一致, 实为 " + bought.balanceAfter());
            PlotRecord record = bought.plot();
            helper.assertTrue(record.owned() && "Buyer_Bo".equals(record.ownerName())
                            && record.syncStatus() == PlotSyncStatus.SYNCED,
                    "过户完成, 推送后为已生效, 实为 " + record);
            helper.assertTrue(bought.logEntry().action() == DistrictLogAction.BUY_PLOT
                            && bought.logEntry().actorRole() == DistrictActorRole.RESIDENT
                            && "阿拜多斯-01".equals(bought.logEntry().targetName())
                            && "1,280 信用点".equals(bought.logEntry().reason()),
                    "本区记录 buyPlot, 实为 " + bought.logEntry());
            PlotLogEntry purchase = env.repo.plotLog(plot, 1, 1, 1).get(0);
            helper.assertTrue(purchase.action() == PlotLogAction.PURCHASE && purchase.actorRole() == PlotActorRole.OWNER
                            && "Buyer_Bo".equals(purchase.targetName()) && "1,280 信用点".equals(purchase.reason()),
                    "地块记录 purchase, 实为 " + purchase);
            helper.assertTrue(FlanGroupNames.plotOwner(plot).equals(
                            env.recording().membersOf(record.flanClaimId()).get(uuidOf("Buyer_Bo"))),
                    "买家进了户主组");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void debitAndAssignmentAreAtomic(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 5000);
            int logRows = env.repo.districtLog("abydos", 100).size();
            helper.assertTrue(env.repo.connection() == env.ledger.connection(), "账本与仓储是同一条连接");
            // 在扣款与过户之后、事务提交之前让写地块记录失败: 扣款必须连同过户一起回滚。
            env.exec("CREATE TEMP TRIGGER fail_plot_log BEFORE INSERT ON district_plot_log "
                    + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            bo = onlinePlayer(helper, "Buyer_Bo");
            boolean failed = false;
            try {
                env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot, env.area(plot), PRICE);
            } catch (DistrictStoreException expected) {
                failed = true;
            }
            env.exec("DROP TRIGGER fail_plot_log");
            helper.assertTrue(failed, "注入的写入失败必须冒出来");
            helper.assertTrue(env.balance("Buyer_Bo") == 5000, "扣款随事务回滚, 实为 " + env.balance("Buyer_Bo"));
            helper.assertTrue(env.plotRecord(plot).vacant(), "过户随事务回滚");
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == logRows, "本区记录也回滚");
            PlotMarketService.BuyResult retry = env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot,
                    env.area(plot), PRICE);
            helper.assertTrue(retry.balanceAfter() == 3720, "去掉故障后照常能买");
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void secondBuyerGetsOccupied(GameTestHelper helper) {
        ServerPlayer first = null;
        ServerPlayer second = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "First_Fi");
            env.resident("abydos", "Second_Se");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("First_Fi", 5000);
            env.fund("Second_Se", 5000);
            first = onlinePlayer(helper, "First_Fi");
            second = onlinePlayer(helper, "Second_Se");
            ServerPlayer secondWallet = second;
            env.ctx.market().buy(player("First_Fi"), first, "abydos", plot, env.area(plot), PRICE);
            DistrictRuleException late = expect(helper, DistrictError.PLOT_OCCUPIED, "后到的人",
                    () -> env.ctx.market().buy(player("Second_Se"), secondWallet, "abydos", plot,
                            env.area(plot), PRICE));
            helper.assertTrue(late.getMessage().equals("阿拜多斯-01 已经被 First_Fi 买下了（先到先得）"), "文案");
            helper.assertTrue(env.balance("Second_Se") == 5000, "后到的人不扣钱");
        } finally {
            removePlayer(helper, first);
            removePlayer(helper, second);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gatewayFailureKeepsPurchaseAndMarksPlotFailed(GameTestHelper helper) {
        ServerPlayer bo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Buyer_Bo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Buyer_Bo", 5000);
            bo = onlinePlayer(helper, "Buyer_Bo");
            env.recording().failWhen(call -> true, "区块未加载");
            PlotMarketService.BuyResult bought = env.ctx.market().buy(player("Buyer_Bo"), bo, "abydos", plot,
                    env.area(plot), PRICE);
            env.recording().clearFailures();
            helper.assertTrue(bought.plot().owned() && env.balance("Buyer_Bo") == 3720,
                    "Flan 写失败不回滚购买、不退款");
            helper.assertTrue(bought.plot().syncStatus() == PlotSyncStatus.FAILED
                            && "区块未加载".equals(bought.plot().syncError()),
                    "地块显示同步失败并带原因, 实为 " + bought.plot());
        } finally {
            removePlayer(helper, bo);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenBuyingIsLoggedAsResident(GameTestHelper helper) {
        ServerPlayer wu = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos", true);
            env.fund("Warden_Wu", 5000);
            wu = onlinePlayer(helper, "Warden_Wu");
            PlotMarketService.BuyResult bought = env.ctx.market().buy(player("Warden_Wu"), wu, "abydos", plot,
                    env.area(plot), PRICE);
            helper.assertTrue(bought.logEntry().actorRole() == DistrictActorRole.RESIDENT,
                    "区务长自己买地记 resident (P3), 实为 " + bought.logEntry().actorRole());
        } finally {
            removePlayer(helper, wu);
        }
        helper.succeed();
    }
}
