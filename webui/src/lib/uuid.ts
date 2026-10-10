/**
 * 客户端生成的幂等键 (UUID v4 字符串)。开箱的 openingId 与系统采购的 purchaseId 共用这一个实现。
 *
 * 不直接用 crypto.randomUUID: 它只在**安全上下文**里存在, 而 MCEF 加载本页的来源不保证是 https。
 * jar 内置开箱页为此带了同一条回退链 (case-opening.html 的 createUuid), 这不是假想风险 —— 少了它,
 * 真客户端上点开箱会抛 "randomUUID is not a function", 而不是失败得体面。
 * getRandomValues 不受安全上下文限制, 故回退只补版本位与变体位, 不退化到 Math.random。
 */

function randomHex(byteCount: number): string {
  const bytes = new Uint8Array(byteCount)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}

export function createClientUuid(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const hex = randomHex(16)
  // 版本位钉 4、变体位钉 8: 服务端按标准 8-4-4-4-12 形状校验后 UUID.fromString 解析, 随机凑出的这两处不能省。
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}
