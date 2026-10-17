package com.miningdim.job.farmer.granary;

/**
 * 账台界面的页签 (规格「账台的多页签界面」)。
 *
 * 顺序就是界面上的顺序, **枚举名一旦下发不许改名** (页序号走网络包); 文案键在 {@link #translationKey()}。
 * 页内容是服务端算好的 (判定与命令面早已就绪), 客户端只负责画 —— 界面里不放任何判定逻辑, 免得"界面说能存、
 * 服务端说不行"。
 */
public enum GranaryCenterPage {

    /** 结构与验收: 与 {@code /granary status} 读同一批行。 */
    STRUCTURE("granary.center.page.structure"),

    /** 我的囤: 我在所有粮仓里的囤与状态。 */
    MY_BINS("granary.center.page.my_bins"),

    /** 成员: 这座粮仓的合建成员 (账台旁边那座)。 */
    MEMBERS("granary.center.page.members"),

    /** 别人开放给我的: 开放名单里我进得去的囤。 */
    SHARED("granary.center.page.shared"),

    /** 流水: 这座粮仓最近的记录。 */
    LEDGER("granary.center.page.ledger");

    private final String translationKey;

    GranaryCenterPage(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    /** 越界 (网络来的东西一律当不可信) 时退回第一页。 */
    public static GranaryCenterPage byIndex(int index) {
        GranaryCenterPage[] all = values();
        return index >= 0 && index < all.length ? all[index] : STRUCTURE;
    }
}
