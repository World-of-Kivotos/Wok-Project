// 只挂 tailwindcss。刻意不装 autoprefixer: 渲染目标单一 (MCEF 内嵌 Chromium),
// 前缀补丁只会产出永远走不到的旧内核分支。
export default {
  plugins: {
    tailwindcss: {},
  },
}
