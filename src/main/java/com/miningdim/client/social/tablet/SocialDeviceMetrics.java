package com.miningdim.client.social.tablet;

/**
 * 社交平板设备在布局里的边距/凸包常量。步战对应 {@code TacticalShellLayout.DeviceMetrics}；
 * 社交平板是整屏设备，边距为 0，凸包 0（正面图的外轮廓自带圆角）。
 */
public record SocialDeviceMetrics(int marginX, int marginBottom, int bump) {
}
