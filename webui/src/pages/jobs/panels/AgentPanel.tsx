import type { ReactElement } from 'react'
import { useState } from 'react'
import { PixelBadge, PixelButton, PixelCurrency, PixelEmpty, PixelError, PixelFrame, PixelLoading, PixelProgress } from '../../../components/pixel'
import { callMock, useMockAction } from '../../../mock'
import type { PlannedAgentScanResult, PlannedChampionAffix } from '../../../mock'
import { formatCountdown, toError, useLiveNow } from './shared'

/**
 * 特勤干员面板 (接线清单 C16 job.agent.scan / C17 job.agent.seal / C18 悬赏板, 均为 PLANNED)。
 *
 * 依赖的假定契约:
 *   - job.agent.state -> PlannedAgentStateResult (等级/扫描 CD/半径/上次扫描快照/悬赏列表)
 *   - job.agent.scan   -> PlannedAgentScanResult (新一轮探测快照, 带 expiresAt —— 过期后必须由前端
 *     自行熄灭, 不等服务端二次推送, 见 planned.ts 该类型的文件头注释), 本面板因此把扫描结果存在本地
 *     state 而不是长期信任 job.agent.state.seals
 *   - job.agent.seal   -> PlannedAgentSealResult (九态裁决只暴露 ok + 文案, 不在前端猜测 outcomeCode
 *     具体枚举名, 详见 planned.ts)
 *   - champion.codex   -> 仅借用 affixes 表把词条 id 解成中文名, 不重复发明一份词条名字典
 *
 * 契约缺口 (报告给核销清单, 不在此处自造接口凑齐):
 *   - 任务描述里的"五支线等级数值"在 PlannedAgentStateResult 里没有对应字段 (只有单一 level),
 *     design_mindmap.md 提到"五支线"这个设计概念但尚未落到 planned 契约, 本面板无法展示分支数值;
 *   - 悬赏 (C18) 只有展示字段, 没有 claimable 对应的领取 action, claimable=true 的条目本页不放按钮
 *     (放一个点了没有任何后果的按钮比不放更糟)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function affixLabel(affixMap: Record<string, string>, affixId: string): string {
  const label = affixMap[affixId]
  return label === undefined ? affixId : label
}

function buildAffixMap(affixes: readonly PlannedChampionAffix[]): Record<string, string> {
  const map: Record<string, string> = {}
  for (const affix of affixes) {
    map[affix.affixId] = affix.displayName
  }
  return map
}

interface SealFeedback {
  readonly ok: boolean
  readonly message: string
}

export function AgentPanel(): ReactElement {
  const stateQuery = useMockAction('job.agent.state', EMPTY_PAYLOAD)
  const codexQuery = useMockAction('champion.codex', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [scanResult, setScanResult] = useState<PlannedAgentScanResult | null>(null)
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<Error | null>(null)
  const [sealingKey, setSealingKey] = useState<string | null>(null)
  const [sealFeedback, setSealFeedback] = useState<Record<string, SealFeedback>>({})

  if (stateQuery.status === 'loading') {
    return <PixelLoading label="正在读取特勤档案" />
  }
  if (stateQuery.status === 'error') {
    return <PixelError message={stateQuery.error.message} onRetry={stateQuery.reload} />
  }

  const data = stateQuery.data
  const affixMap = codexQuery.status === 'ready' ? buildAffixMap(codexQuery.data.affixes) : {}
  const scanReady = now >= data.scanReadyAt
  // scanResult 非空且未过期时才算"活体快照" —— 过期后前端必须自行熄灭 (planned.ts 对该 action 的注释),
  // 不等世界状态被下一次扫描覆盖。narrow 出一个独立变量, 避免下方渲染分支里再对 scanResult 判空。
  const activeScan = scanResult !== null && scanResult.expiresAt > now ? scanResult : null

  async function handleScan(): Promise<void> {
    setScanning(true)
    setScanError(null)
    try {
      const result = await callMock('job.agent.scan', {})
      setScanResult(result)
      setSealFeedback({})
      stateQuery.reload()
    } catch (error) {
      setScanError(toError(error))
    } finally {
      setScanning(false)
    }
  }

  async function handleSeal(targetNetworkId: number, affixId: string): Promise<void> {
    const key = `${String(targetNetworkId)}:${affixId}`
    setSealingKey(key)
    try {
      const result = await callMock('job.agent.seal', { targetNetworkId, affixId })
      setSealFeedback((previous) => ({ ...previous, [key]: { ok: result.ok, message: result.message } }))
      if (result.ok) {
        setScanResult((previous) =>
          previous === null
            ? previous
            : {
                ...previous,
                seals: previous.seals.map((seal) =>
                  seal.targetNetworkId === targetNetworkId
                    ? { ...seal, affixIds: seal.affixIds.filter((id) => id !== affixId) }
                    : seal,
                ),
              },
        )
      }
    } catch (error) {
      setSealFeedback((previous) => ({
        ...previous,
        [key]: { ok: false, message: toError(error).message },
      }))
    } finally {
      setSealingKey(null)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="flex flex-wrap items-center justify-between gap-4 p-4">
        <div className="flex items-center gap-6">
          <span className="text-2x text-fg">特勤干员 Lv.{data.level}</span>
          <span className="text-1x text-muted">探测半径 {data.scanRadius} 格</span>
        </div>
        <span className="text-1x text-muted">五支线等级数值缺失: 契约 (planned.ts) 只给单一 level 字段</span>
      </PixelFrame>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">战术扫描</h2>
        <PixelFrame variant="panel" className="flex flex-col gap-4 p-4">
          <div className="flex flex-wrap items-center gap-4">
            <PixelButton
              tone="accent"
              loading={scanning}
              disabled={!scanReady}
              onClick={() => {
                void handleScan()
              }}
            >
              发起探测脉冲
            </PixelButton>
            <span className="text-1x text-muted">
              {scanReady ? '就绪, 可立即扫描' : `冷却中, 剩余 ${formatCountdown(data.scanReadyAt, now)}`}
            </span>
          </div>
          {scanError === null ? null : (
            <p role="alert" className="text-1x text-danger">
              {scanError.message}
            </p>
          )}

          {activeScan === null ? (
            <PixelEmpty title="尚未进行有效的战术扫描" hint="点击上方按钮发起一次探测脉冲, 结果会在此列出" />
          ) : activeScan.seals.length === 0 ? (
            <PixelEmpty title="本轮扫描没有发现可封印目标" hint="探测范围内暂无带词条的精英怪" />
          ) : (
            <div className="flex flex-col gap-3">
              <span className="text-1x text-muted">本轮快照将于 {formatCountdown(activeScan.expiresAt, now)} 后失效</span>
              {activeScan.seals.map((seal) => (
                <PixelFrame key={seal.targetNetworkId} variant="inset" className="flex flex-col gap-2 p-3">
                  <div className="flex items-center gap-3">
                    <span className="text-1x text-fg">{seal.entityLabel}</span>
                    <PixelBadge tone="warning">{seal.star} 星</PixelBadge>
                    <span className="text-1x text-muted">
                      坐标 ({seal.pos.x}, {seal.pos.y}, {seal.pos.z})
                    </span>
                  </div>
                  {seal.affixIds.length === 0 ? (
                    <span className="text-1x text-muted">词条已全部封印</span>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {seal.affixIds.map((affixId) => {
                        const key = `${String(seal.targetNetworkId)}:${affixId}`
                        const feedback = sealFeedback[key]
                        return (
                          <div key={key} className="flex items-center gap-2">
                            <PixelButton
                              size="sm"
                              loading={sealingKey === key}
                              onClick={() => {
                                void handleSeal(seal.targetNetworkId, affixId)
                              }}
                            >
                              封印 {affixLabel(affixMap, affixId)}
                            </PixelButton>
                            {feedback === undefined ? null : (
                              <span className={`text-1x ${feedback.ok ? 'text-success' : 'text-danger'}`}>
                                {feedback.message}
                              </span>
                            )}
                          </div>
                        )
                      })}
                    </div>
                  )}
                </PixelFrame>
              ))}
            </div>
          )}
        </PixelFrame>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">悬赏板</h2>
        {data.bounties.length === 0 ? (
          <PixelEmpty title="当前没有可用悬赏" />
        ) : (
          <div className="flex flex-col gap-3">
            {data.bounties.map((bounty) => (
              <PixelFrame key={bounty.bountyId} variant="panel" className="flex flex-col gap-2 p-4">
                <div className="flex flex-wrap items-center justify-between gap-4">
                  <span className="text-1x text-fg">{bounty.title}</span>
                  <div className="flex items-center gap-3">
                    <PixelCurrency amount={bounty.rewardCredit} currency="credit" size="sm" />
                    {bounty.claimable ? (
                      <PixelBadge tone="success">可领取 (暂无领取接口)</PixelBadge>
                    ) : (
                      <span className="text-1x text-muted">
                        {bounty.expiresAt > now ? `剩余 ${formatCountdown(bounty.expiresAt, now)}` : '已过期'}
                      </span>
                    )}
                  </div>
                </div>
                <PixelProgress
                  value={bounty.progress}
                  max={bounty.goal}
                  tone={bounty.claimable ? 'success' : 'accent'}
                  label={`${String(bounty.progress)} / ${String(bounty.goal)}`}
                />
              </PixelFrame>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
