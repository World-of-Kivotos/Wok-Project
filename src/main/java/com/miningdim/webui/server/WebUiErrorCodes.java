package com.miningdim.webui.server;

/**
 * Web UI 业务错误码全集 (WebUI 接线契约 W1 / 决策 D4)。
 *
 * 收编范围: 只收真正出现在 {@link WebUiBusinessException} 里的码 —— 它们会经
 * {@link WebUiServerDispatcher#businessErrorJson} 下发给页面, 是前端本地化字典的键, 属对外契约。
 * 走通用异常兜底 (Gateway 的 {@code catch (Exception)} 分支) 的失败没有 errorCode, 不在本表内;
 * 把它们回填成业务异常是另一个题目, 不许顺手往本表加没有抛出点的码。
 *
 * 与面板锁定码分表: hub.panels 的 lockCode 是"这个面板此刻能不能进"的原因码, 与本表是两个命名空间,
 * 各有各的常量类与各自的前端文案字典。合表会让"锁定原因"与"调用失败"静默串号。
 *
 * 码本身即前后端的稳定契约: 值一旦下发就不许改名 (改名 = 前端文案字典整条失配, 玩家看到英文原码)。
 */
public final class WebUiErrorCodes {

    private WebUiErrorCodes() {
    }

    /**
     * 入参形状或取值非法 (缺必填字段 / 类型不符 / 取值域外)。
     * 抛出点: {@code CaseWebUiActions.invalidRequest}; W1 起 player.itemDetail 与 player.prefs.set 复用,
     * 后者带 params {@code field} 与 {@code value} 指出是哪个字段的哪个值被拒。
     */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /**
     * player.itemDetail 的 slot 不在 {@code [0, sender.getInventory().items.size())} 内。
     * params: {@code slot} 与 {@code size}。
     */
    public static final String SLOT_OUT_OF_RANGE = "SLOT_OUT_OF_RANGE";

    /** player.itemDetail 的 slot 合法但该格 {@code ItemStack.isEmpty()}。params: {@code slot}。 */
    public static final String SLOT_EMPTY = "SLOT_EMPTY";

    /** 开箱系统已关闭, 或 TaCZ / 武器箱资源包未就绪。抛出点: {@code CaseOpeningService.open}。 */
    public static final String CASE_DISABLED = "CASE_DISABLED";

    /** 余额不足 (信用点或青辉石)。抛出点: {@code CaseOpeningService.open}。 */
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    /** TaCZ 或武器箱资源包未就绪, 无法应用枪械皮肤。抛出点: {@code CaseOpeningService.apply}。 */
    public static final String TACZ_UNAVAILABLE = "TACZ_UNAVAILABLE";

    /** 玩家不拥有该皮肤资产。抛出点: {@code CaseOpeningService.apply}。 */
    public static final String ASSET_NOT_OWNED = "ASSET_NOT_OWNED";

    /** 该开箱事务已退款, 必须换新 openingId 重开。抛出点: {@code CaseOpeningService} 的两处退款对账。 */
    public static final String OPENING_REFUNDED = "OPENING_REFUNDED";

    /** 开箱请求过快 (新开箱冷却)。抛出点: {@code CaseOpeningService.enforceNewOpenRateLimit}。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** openingId 已属于其它玩家或其它箱子。抛出点: {@code CaseOpeningService.validateIdentity}。 */
    public static final String OPENING_ID_CONFLICT = "OPENING_ID_CONFLICT";
}
