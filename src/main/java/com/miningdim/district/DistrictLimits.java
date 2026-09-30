package com.miningdim.district;

import java.util.regex.Pattern;

/**
 * 自管区模块的全部常量 (docs/District_Backend_Design.md)。阶段 1 不加配置文件; 第十九章 P4 / P8 列出了哪些值日后
 * 改成配置 (朋友上限、离边界格数、新区默认值与业务上限)。
 */
public final class DistrictLimits {

    private DistrictLimits() {
    }

    /** 户主被移出后地块冻结的天数 (已拍板)。 */
    public static final int FREEZE_DAYS = 7;

    /** 冻结时长的毫秒数: 到期时刻 = frozenAt + FREEZE_MS。 */
    public static final long FREEZE_MS = FREEZE_DAYS * 24L * 3_600_000L;

    /** 每块地的朋友上限, 已暂停的也占名额 (P8, 先按 8 人)。 */
    public static final int FRIEND_LIMIT = 8;

    /** 地块四边离自管区边界至少留几格公共区域 (P8, 先按 2 格)。 */
    public static final int EDGE_GAP = 2;

    /**
     * 自管区外围多少格内不能个人圈地、不能放机械动力的机器 (已拍板 8 格)。机械动力禁令 (设计文档 22.2: 方形外扩、两端都含、
     * 全高、只在该维度) 与个人圈地限制 (22.20) 用同一个禁放区。
     */
    public static final int BUFFER_BLOCKS = 8;

    /** 新区默认单价 (信用点/格, P4)。 */
    public static final int DEFAULT_UNIT_PRICE = 5;

    /** 新区默认的地块边长下限 (P4)。 */
    public static final int DEFAULT_MIN_SIDE = 8;

    /** 新区默认的地块边长上限 (P4)。 */
    public static final int DEFAULT_MAX_SIDE = 48;

    /** 单价的业务上限: 价格最大约 1024² × 10⁶ ≈ 1.05×10¹², 远小于 2⁵³−1, 前端的 number 不会失真。 */
    public static final int MAX_UNIT_PRICE = 1_000_000;

    /** 边长上限的业务上限。 */
    public static final int MAX_SIDE_LIMIT = 1024;

    /** 定时收回的间隔 (tick): 每 60 秒一次。 */
    public static final int SWEEP_INTERVAL_TICKS = 1200;

    /** 拒绝文案与 params 里回显客户端输入的字符上限, 与 WebUiPayloads.illegalValue 相同。 */
    public static final int ECHO_MAX_CHARS = 64;

    /** 玩家 ID 的格式 (加住户、加朋友去掉首尾空白后校验)。 */
    public static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /** 本区记录 (district.detail) 的条数上限。 */
    public static final int DISTRICT_LOG_LIMIT = 100;

    /** 地块记录 (plot.detail, 本任期与历任合计) 的条数上限。 */
    public static final int PLOT_LOG_LIMIT = 100;

    /** 已删地块的墓碑 (district.plots) 最多给几个。 */
    public static final int TOMBSTONE_LIMIT = 20;

    /** 每个墓碑的记录条数上限。 */
    public static final int TOMBSTONE_LOG_LIMIT = 20;

    /** 已解绑自管区 (admin.district.archive) 最多给几个。 */
    public static final int ARCHIVE_LIMIT = 20;

    /** 每个已解绑自管区的记录条数上限。 */
    public static final int ARCHIVE_LOG_LIMIT = 50;

    /** "我是哪几块地的朋友" (district.state friendOf) 的条数上限。 */
    public static final int FRIEND_OF_LIMIT = 50;

    /** 一个区最多几条区规 (/district rules add): 区规随 district.detail 整张下发, 条数与长度都要有上限才能守住回执体积。 */
    public static final int MAX_RULES = 20;

    /** 一条区规最多几个字。 */
    public static final int MAX_RULE_CHARS = 200;

    /**
     * 移出原因 (district.removeResident 的 reason, 去掉首尾空白后) 最多几个字符, 按 UTF-16 码元计, 与前端 maxLength
     * 同口径。界面把补充说明限在 60 字, 拼上预设原因也不到 70 字, 正常操作碰不到这个上限; 服务端仍要自己设: 原因原文
     * 写进本区记录, 随 district.detail 与 admin.district.archive 下发, 一条超长的记录会占满回执预算, 把更早的记录全挤掉。
     */
    public static final int MAX_REMOVE_REASON_CHARS = 200;

    /** /district residents add 一次最多几个名字。 */
    public static final int MAX_BULK_NAMES = 50;

    /** 聊天通知队列每人最多留几条 (设计文档 22.11, P29): 入队后超出就在同一事务里删掉最旧的。 */
    public static final int NOTICE_KEEP_PER_PLAYER = 30;

    /** 聊天通知在队列里最多留多久 (P29): 30 天。 */
    public static final long NOTICE_RETENTION_MS = 30L * 24L * 3_600_000L;

    /** 定时节拍里清过期通知的最小间隔: 每小时至多一次。 */
    public static final long NOTICE_PRUNE_INTERVAL_MS = 3_600_000L;

    /**
     * "开放购买"的在线广播 (purchase_opened, 只发在线的) 同一个区多久内至多一次: 管理员开了又关、关了又开时不重复刷屏
     * (阶段 3 B 步定的节流, 22.18)。
     */
    public static final long PURCHASE_NOTICE_COOLDOWN_MS = 10L * 60_000L;

    /**
     * 给朋友的通知 (friend_added / friend_removed / friend_restored, 任何户主都能对任何人触发) 提交后立即送达的最小间隔:
     * 同一个收件人 60 秒内至多即时送一次, 其余留在队列里 (同一块地的会合并成一条), 由定时节拍 (每分钟) 或下次上线补发
     * (22.19)。防户主反复加、移朋友刷别人的聊天栏。
     */
    public static final long FRIEND_NOTICE_INTERVAL_MS = 60_000L;

    /** 服务器自己做的事 (冻结期满自动收回) 在记录里的操作人名。 */
    public static final String SYSTEM_ACTOR_NAME = "服务器";

    /** 控制台 / RCON 执行命令时在记录里的操作人名。 */
    public static final String CONSOLE_ACTOR_NAME = "控制台";

    /**
     * 开服时改用记录型假网关的 JVM 系统属性 (取值 recording)。只在 GameTest 服务端上生效, 专用服务器与单人存档里忽略并记
     * WARN 后照常往下选 (GatewaySelector)。
     */
    public static final String GATEWAY_PROPERTY = "miningdim.district.flanGateway";
}
