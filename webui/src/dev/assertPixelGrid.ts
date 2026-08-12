/** --px 必须是整数 px 字面量。小数会让整套派生尺寸落在半像素上, 是像素风的致命伤。 */
const INTEGER_PX = /^(\d+)px$/

/** --pixel-scale 是无单位整数 (border-width = slice x scale x 1px)。 */
const INTEGER_UNITLESS = /^\d+$/

/**
 * 像素网格整数守卫, 应用入口执行一次。
 *
 * 两个变量都必须查, 因为它们各管一条互不相交的派生链, 只守其中一条等于没守:
 *   --px          管布局尺寸 (间距 / 字号 / 普通边框), 由 tailwind 的 spacing/fontSize 派生;
 *   --pixel-scale 管 9-slice 边角放大倍率, 由 PixelFrame 的 border-width 派生。
 * 后者取 1.5 时 slice=8 算出的 border-width 是 12px —— 一个漂漂亮亮的整数, 任何"边框宽是不是整数"
 * 的检查都会放行, 而边角位图实际按 1.5 倍重采样, 症状只是边缘糊一圈, 不报错。故必须直接校验倍率本身。
 *
 * 两种非整数的处理刻意不同:
 *   - 两个 CSS 变量非整数直接抛错。它们是我们自己写死的值, 出错即工程失误, 静默兜底只会让糊掉的界面看着像"就这样"。
 *   - devicePixelRatio 非整数只告警。它由 MCEF 宿主与 MC GUI Scale 叠加决定, 前端无权修正;
 *     但必须把这个数打进控制台 —— 这是唯一能在运行期读到真实缩放的位置, 批 1 在真客户端标定 --px 靠它。
 */
export function assertPixelGrid(): void {
  const rootStyle = getComputedStyle(document.documentElement)

  const raw = rootStyle.getPropertyValue('--px').trim()
  if (!INTEGER_PX.test(raw)) {
    throw new Error(`--px 必须是整数 px 字面量 (当前为 "${raw}"): 半像素会毁掉整套像素网格`)
  }
  const px = Number.parseInt(raw, 10)
  if (px <= 0) {
    throw new Error(`--px 必须为正整数 (当前为 ${String(px)})`)
  }

  const rawScale = rootStyle.getPropertyValue('--pixel-scale').trim()
  // 空串既可能是变量没声明, 也可能是被覆盖成了空值; 两种都会让 border-width 的 calc 整条失效, 一并挡下。
  if (!INTEGER_UNITLESS.test(rawScale)) {
    throw new Error(
      `--pixel-scale 必须是无单位正整数 (当前为 "${rawScale}"): 非整数倍率会把 9-slice 边角重采样成糊边`,
    )
  }
  const pixelScale = Number.parseInt(rawScale, 10)
  if (pixelScale <= 0) {
    throw new Error(`--pixel-scale 必须为正整数 (当前为 ${String(pixelScale)}): 0 倍率直接让边框消失`)
  }

  const ratio = window.devicePixelRatio
  if (!Number.isInteger(ratio)) {
    console.warn(
      `[pixel-grid] devicePixelRatio=${String(ratio)} 非整数: 位图会被非整数倍重采样。` +
        '该值由 MCEF 宿主与 MC GUI Scale 叠加决定, 需在游戏内调 GUI Scale 到整数档 (规格第十二章)。',
    )
  }
  console.info(
    `[pixel-grid] --px=${String(px)}px --pixel-scale=${String(pixelScale)} ` +
      `devicePixelRatio=${String(ratio)} ` +
      `viewport=${String(window.innerWidth)}x${String(window.innerHeight)}`,
  )
}
