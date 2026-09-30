package com.miningdim.district.core;

import com.miningdim.district.DistrictLimits;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 固定文案 (最初逐字取自 webui/src/mock/district-seed.ts 与 district-handlers.ts; 接线之后以这里为准, 假后端跟着改)
 * 与几个格式化函数。
 * 地块记录里的缘由一律固定一句, 不抄移出原因: 移出原因只进本区记录, 地块记录日后下一任户主也看得到。
 */
public final class DistrictTexts {

    private DistrictTexts() {
    }

    /** 事务里先写的"临时失败"原因: 服务器在提交与推送之间崩溃时, 留下的是如实的失败, 不是虚假的已生效。 */
    public static final String SYNC_INTERRUPTED = "服务器写入领地前中断，请管理员重试";

    /** 降级原因 (设计文档 20.2): Flan 没有安装。 */
    public static final String FLAN_REASON_MISSING = "Flan 没有安装";
    /** 降级原因: Flan 自检未通过。 */
    public static final String FLAN_REASON_SELF_CHECK = "Flan 自检未通过，见服务器日志";
    /** 降级原因: 运行中出了 LinkageError, 网关熔断。 */
    public static final String FLAN_REASON_FUSED = "Flan 调用出错，已停用到重启";
    /** 降级原因: 库里缺 district_notice 表 (用阶段 1–2 的开发构建开过这个存档, 设计文档 22.19)。 */
    public static final String NOTICE_TABLE_MISSING = "数据库缺少 district_notice 表，按服务器日志补建后重启";
    /** 写领地前的备份没写成: 本次不写 (20.5)。 */
    public static final String FLAN_BACKUP_FAILED = "领地备份失败，本次没有写入领地";
    /** 库里记着父领地的 id, Flan 里却找不到了 (领地对接正常、维度也加载着): 等管理员核对。 */
    public static final String FLAN_CLAIM_MISSING = "自管区的领地在 Flan 里找不到了，等待管理员核对";
    /** 新建的子领地没能记进库 (已删掉, 下一次写入重建)。 */
    public static final String PLOT_CLAIM_NOT_RECORDED = "地块的领地没能记进数据库，已撤销，稍后自动重试";

    /** 自管区所在的维度没有加载 (查询一律为空, 不能当成"领地被删了")。 */
    public static String dimensionNotLoaded(String dimension) {
        return "维度 " + dimension + " 没有加载";
    }

    /** 平板写动作在领地对接降级时的拒绝文案 (DISTRICT_DISABLED, 20.9): 只能查看、不能修改。 */
    public static String districtReadOnly(@Nullable String reason) {
        return "自管区的领地对接暂停" + (reason == null ? "" : "（" + reason + "）") + "，现在只能查看、不能修改";
    }

    /** 降级原因: Flan 版本不是核对过的那一个。 */
    public static String flanReasonVersion(String version) {
        return "Flan 版本 " + version + " 未经核对";
    }

    /** 降级网关 (DisabledFlanGateway) 对一切写入的回复: "领地对接未启用：{原因}"。 */
    public static String flanDisabled(String reason) {
        return "领地对接未启用：" + reason;
    }

    /** Flan 领地名: 管理员领地与地块的 getClaimName() 会做一次 String.format, 名字里不能有 %。 */
    public static String claimName(String name) {
        return name.replace("%", "");
    }

    public static final String PLOT_NOTE_FREEZE = "户主已移出本区，地块冻结 " + DistrictLimits.FREEZE_DAYS + " 天";
    public static final String PLOT_NOTE_RECLAIM_EXPIRED = "冻结期满，地块收回";
    public static final String PLOT_NOTE_RECLAIM_NOW = "管理员立即收回冻结中的地块";
    public static final String PLOT_NOTE_UNFREEZE = "原户主回到本区，解除冻结";
    public static final String PLOT_NOTE_SUSPEND = "TA 因违反区规被移出本区，朋友身份自动暂停；户主可以自己恢复";
    public static final String PLOT_NOTE_KICK_ARCHIVED = "管理员把原户主移出已解绑的学院";
    public static final String PLOT_NOTE_RELEASE_ARCHIVED = "原户主已被移出学院名单，已解绑自管区里的这块地清空户主";

    public static final String DISTRICT_NOTE_RECLAIM_EXPIRED = "冻结期满，自动收回";
    public static final String DISTRICT_NOTE_DELETE_PLOT = "删掉后这片地回到公共区域";
    public static final String RESTORE_DEFAULT = "恢复默认";
    public static final String PURCHASE_OPENED = "开放购买";
    public static final String PURCHASE_PAUSED = "暂停购买";

    /** 名字的比较口径: 全服不分大小写, 一律 Locale.ROOT 小写 (不用 SQLite 的 lower(), 它只处理 ASCII)。 */
    public static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** 回显客户端输入的截断: 超过 64 字符截断并加省略号, 与 WebUiPayloads.illegalValue 同口径。 */
    public static String echo(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= DistrictLimits.ECHO_MAX_CHARS
                ? value
                : value.substring(0, DistrictLimits.ECHO_MAX_CHARS) + "...";
    }

    /** "2,304 信用点"。 */
    public static String formatCredit(long amount) {
        return String.format(Locale.ROOT, "%,d 信用点", amount);
    }

    /** 两位编号: 1 -> "01", 100 -> "100"。 */
    public static String pad2(int number) {
        return String.format(Locale.ROOT, "%02d", number);
    }

    public static String freezeDistrictReason(String formerOwner) {
        return "原户主 " + formerOwner + " 被移出本区";
    }

    public static String suspendDistrictReason(int plots) {
        return "本区 " + plots + " 块地的朋友身份已暂停";
    }

    public static String unfreezeDistrictReason(String owner) {
        return "还给原户主 " + owner;
    }

    public static String reclaimNowDistrictReason(String formerOwner) {
        return "管理员立即收回（原户主 " + formerOwner + "）";
    }

    public static String createPlotReason(PlotArea area) {
        return area.sideText() + "，" + area.area() + " 格";
    }

    public static String resizeOwnedReason(String owner) {
        return "管理员代改，已通知户主 " + owner;
    }

    public static String resizeVacantReason(PlotArea from, PlotArea to) {
        return from.sideText() + " → " + to.sideText();
    }

    /** 管理员代你恢复默认时, 通知里的"恢复范围" (plot_admin.reset, 22.10): 恢复默认一律是三列全部。 */
    public static final String NOTICE_RESET_SCOPE = "朋友、其他住户、外人三列";

    /** 地块三列在界面上的叫法 (与平板 format.ts 的列名相同)。 */
    public static String plotAudienceLabel(PlotAudience audience) {
        return switch (audience) {
            case FRIEND -> "朋友";
            case RESIDENT -> "其他住户";
            case OUTSIDER -> "外人";
        };
    }

    /** 通知里的开关改动文字 (plot_admin.permission): "外人·破坏方块 → 关"。 */
    public static String permissionChangeText(PlotAudience audience, String itemLabel, boolean to) {
        return plotAudienceLabel(audience) + "·" + itemLabel + " → " + (to ? "开" : "关");
    }
}
