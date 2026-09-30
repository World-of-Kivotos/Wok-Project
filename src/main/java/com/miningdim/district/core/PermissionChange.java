package com.miningdim.district.core;

/**
 * 一条改开关的记录内容: 哪一项、当时的名称、哪一列 (wire 值)、从什么改成什么。label 存改动那一刻的名称,
 * 目录日后改名不改历史。
 */
public record PermissionChange(String permissionId, String label, String audience, boolean from, boolean to) {
}
