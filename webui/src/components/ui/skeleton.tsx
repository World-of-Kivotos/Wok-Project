import type React from "react";
import { cn } from "@/lib/utils";

/**
 * 骨架屏占位。
 *
 * 刻意<b>没有</b>循环流光。原实现是 {@code animate-skeleton} 无限循环 {@code background-position}
 * (还带 {@code background-attachment: fixed}), 那是一条永不停止的<b>重绘</b>动画 —— 在 MCEF 的离屏渲染下,
 * 页面每重绘一帧就要往 MC 的渲染线程上传一次贴图 (MCEFRenderer.onPaint 走 glTexSubImage2D), 于是只要屏幕上
 * 还有一格骨架屏, 游戏就一直在为它掉帧。这是"打开面板就卡"的主要来源, 不是审美问题。
 *
 * 换成一次性的淡入 (skeleton-appear, 见 styles/index.css): 它在 180ms 后彻底静止, 之后这块像素零成本。
 * 代价是失去"正在加载"的持续暗示 —— 而那个暗示现在由 lib/query-cache 的缓存命中大幅削减了出现次数,
 * 剩下的场合由页面自己的 LoadingBlock/Spinner 承担。
 */
export function Skeleton({
  className,
  ...props
}: React.ComponentProps<"div">): React.ReactElement {
  return (
    <div
      className={cn("skeleton-appear rounded-sm bg-muted", className)}
      data-slot="skeleton"
      {...props}
    />
  );
}
