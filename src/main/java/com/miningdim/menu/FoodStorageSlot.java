package com.miningdim.menu;

/** 本模组的权威储物槽，可在观察食物时结算；合并还须经过当前使用者的权限判断。 */
public interface FoodStorageSlot {
    boolean mayMergeForViewer();
}
