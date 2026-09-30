package com.miningdim.district.core;

/**
 * 26 条平板动作的名字。Java 的注释与断言文案里一律写 action 名, 不写编号: 设计文档里的 D1–D26 (= 接线清单 K 组的
 * K1–K26, 顺序同本类常量) 会与别的模块注释里已有的"决策 D7""K1"之类撞名, 见设计文档 17.4。
 * 服务层用它们填 PERMISSION_DENIED 的 params.action, 平板层用它们登记派发器。
 */
public final class DistrictActionNames {

    private DistrictActionNames() {
    }

    public static final String STATE = "district.state";
    public static final String DETAIL = "district.detail";
    public static final String ADD_RESIDENT = "district.addResident";
    public static final String REMOVE_RESIDENT = "district.removeResident";
    public static final String RETRY_SYNC = "admin.district.retrySync";
    public static final String SET_WARDEN = "admin.district.setWarden";
    public static final String DELETE = "admin.district.delete";
    public static final String PERMISSIONS = "district.permissions";
    public static final String SET_PERMISSION = "district.setPermission";
    public static final String RESET_PERMISSIONS = "district.resetPermissions";
    public static final String PLOTS = "district.plots";
    public static final String PLOT_DETAIL = "plot.detail";
    public static final String PLOT_SET_PERMISSION = "plot.setPermission";
    public static final String PLOT_RESET_PERMISSIONS = "plot.resetPermissions";
    public static final String PLOT_ADD_FRIEND = "plot.addFriend";
    public static final String PLOT_REMOVE_FRIEND = "plot.removeFriend";
    public static final String PLOT_CREATE = "plot.create";
    public static final String PLOT_RESIZE = "plot.resize";
    public static final String PLOT_DELETE = "plot.delete";
    public static final String PLOT_BUY = "plot.buy";
    public static final String SET_PLOT_PRICING = "admin.district.setPlotPricing";
    public static final String SET_PURCHASE_OPEN = "admin.district.setPurchaseOpen";
    public static final String PLOT_UNFREEZE = "admin.plot.unfreeze";
    public static final String PLOT_RECLAIM_NOW = "admin.plot.reclaimNow";
    public static final String PLOT_RESTORE_FRIEND = "plot.restoreFriend";
    public static final String ARCHIVE = "admin.district.archive";
}
