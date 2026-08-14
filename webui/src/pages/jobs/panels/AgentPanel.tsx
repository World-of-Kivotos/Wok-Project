import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  Currency,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
} from '@/components/kit'
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
    return <LoadingBlock label="正在读取特勤档案" />
  }
  if (stateQuery.status === 'error') {
    return <ErrorBlock message={stateQuery.error.message} onRetry={stateQuery.reload} />
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
    <div className="flex flex-col gap-4">
      <Panel title="特勤干员">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat label="探测半径" value={`${String(data.scanRadius)} 格`} />
          </div>
          <p className="text-muted-foreground text-xs">
            五条支线的单独等级暂不可见, 这里只显示总等级
          </p>
        </div>
      </Panel>

      <Panel title="战术扫描">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <Button
              disabled={!scanReady}
              loading={scanning}
              onClick={() => {
                void handleScan()
              }}
              variant="brand"
            >
              发起探测脉冲
            </Button>
            <span className="text-muted-foreground text-sm">
              {scanReady ? '就绪, 可立即扫描' : `冷却中, 剩余 ${formatCountdown(data.scanReadyAt, now)}`}
            </span>
          </div>
          {scanError === null ? null : <FeedbackAlert message={scanError.message} tone="danger" />}

          {activeScan === null ? (
            <EmptyBlock hint="点击上方按钮发起一次探测脉冲, 结果会在此列出" title="尚未进行有效的战术扫描" />
          ) : activeScan.seals.length === 0 ? (
            <EmptyBlock hint="探测范围内暂无带词条的精英怪" title="本轮扫描没有发现可封印目标" />
          ) : (
            <div className="flex flex-col gap-3">
              <span className="text-muted-foreground text-xs">
                本轮快照将于 {formatCountdown(activeScan.expiresAt, now)} 后失效
              </span>
              {activeScan.seals.map((seal) => (
                <Surface key={seal.targetNetworkId}>
                  <div className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-3">
                      <h3 className="font-medium text-foreground text-sm">{seal.entityLabel}</h3>
                      <Tag tone="warning">{seal.star} 星</Tag>
                      <span className="text-muted-foreground text-xs">
                        坐标 ({seal.pos.x}, {seal.pos.y}, {seal.pos.z})
                      </span>
                    </div>
                    {seal.affixIds.length === 0 ? (
                      <span className="text-muted-foreground text-xs">词条已全部封印</span>
                    ) : (
                      <div className="flex flex-wrap gap-2">
                        {seal.affixIds.map((affixId) => {
                          const key = `${String(seal.targetNetworkId)}:${affixId}`
                          const feedback = sealFeedback[key]
                          return (
                            <div className="flex items-center gap-2" key={key}>
                              <Button
                                loading={sealingKey === key}
                                onClick={() => {
                                  void handleSeal(seal.targetNetworkId, affixId)
                                }}
                                size="sm"
                                variant="outline"
                              >
                                封印 {affixLabel(affixMap, affixId)}
                              </Button>
                              {feedback === undefined ? null : (
                                <span
                                  className={`text-xs ${feedback.ok ? 'text-success' : 'text-destructive'}`}
                                >
                                  {feedback.message}
                                </span>
                              )}
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                </Surface>
              ))}
            </div>
          )}
        </div>
      </Panel>

      <Panel title="悬赏板">
        {data.bounties.length === 0 ? (
          <EmptyBlock title="当前没有可用悬赏" />
        ) : (
          <div className="flex flex-col gap-3">
            {data.bounties.map((bounty) => (
              <Surface key={bounty.bountyId}>
                <div className="flex flex-col gap-2">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h3 className="font-medium text-foreground text-sm">{bounty.title}</h3>
                    <div className="flex items-center gap-3">
                      <Currency amount={bounty.rewardCredit} currency="credit" size="sm" />
                      {bounty.claimable ? (
                        <Tag tone="success">可领取 (暂无领取接口)</Tag>
                      ) : (
                        <span className="text-muted-foreground text-xs">
                          {bounty.expiresAt > now
                            ? `剩余 ${formatCountdown(bounty.expiresAt, now)}`
                            : '已过期'}
                        </span>
                      )}
                    </div>
                  </div>
                  <Meter
                    label="进度"
                    max={bounty.goal}
                    tone={bounty.claimable ? 'success' : 'brand'}
                    value={bounty.progress}
                    valueText={`${String(bounty.progress)} / ${String(bounty.goal)}`}
                  />
                </div>
              </Surface>
            ))}
          </div>
        )}
      </Panel>
    </div>
  )
}
