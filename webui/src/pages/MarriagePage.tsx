import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelConfirmDanger,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelInput,
  PixelLoading,
  PixelProgress,
  PixelSelect,
  PixelSlotGrid,
} from '../components/pixel'
import type { PixelFrameTone, PixelSelectOption, PixelSlotGridEntry } from '../components/pixel'
import { useItemNames } from '../lib/i18n'
import type { PlayerInventoryItem } from '../lib/types'
import { callMock, useMockAction, useMockWorld } from '../mock'
import type { PlannedMarriageStatus, PlannedProposal } from '../mock'

/*
 * 婚姻 (接线清单 E 组)。本页依赖的假定契约 (planned.ts, 均已在 mock/handlers.ts 落地为内存世界实现):
 *   marriage.state     E1  状态聚合 (配偶/婚龄/婚戒/里程碑/收发求婚)
 *   marriage.buyRing   E2  购买婚戒
 *   marriage.propose   E2  发起求婚
 *   marriage.respond   E2+E3  应答收到的求婚
 *   marriage.wed       E2  举行典礼 (wed 六态失败枚举, 服务端以字符串位 outcomeCode 回, 前端只判 ok 展示 message)
 *   marriage.divorce   E2  离婚 (divorce 四态同上)
 *   marriage.sharedInv E5  共享背包只读快照 (取放留在原生 Container 协议里, 本页不做)
 *
 * 缺口: 任务书要求的"传送蓄力与冷却"在 planned.ts 的契约表里完全没有对应 action (既非 E 组既有条目,
 * 也未见于接线清单)。本页把它做成纯前端本地计时器 (蓄力进度条 + 冷却倒计时), 明确标注不产生任何
 * 服务端/mock 世界状态变更, 待补 marriage.teleport 一类契约后需替换为真实调用 —— 不在此处臆造契约
 * 或改动 mock/ 目录 (八荣八耻: 以创造接口为耻, 以复用现有为荣)。
 */

const STATUS_LABEL: Record<PlannedMarriageStatus, string> = {
  single: '未婚',
  engaged: '已订婚',
  married: '已婚',
  cooldown: '再婚冷却中',
}

const STATUS_TONE: Record<PlannedMarriageStatus, PixelFrameTone> = {
  single: 'neutral',
  engaged: 'info',
  married: 'success',
  cooldown: 'warning',
}

type Banner = { tone: PixelFrameTone; message: string } | null

type TeleportPhase = 'idle' | 'channeling' | 'cooldown'

/** 纯前端本地模拟, 数值无平衡依据, 只为让蓄力/冷却两种视觉态可被触发 (见文件头缺口说明)。 */
const TELEPORT_CHANNEL_MS = 3_000
const TELEPORT_COOLDOWN_MS = 20_000
const TELEPORT_TICK_MS = 100

/** 分钟级粒度即可: 求婚过期/再婚冷却动辄以小时天计, 不需要秒级刷新。 */
function formatDuration(remainingMs: number): string {
  if (remainingMs <= 0) {
    return '已到期'
  }
  const totalMinutes = Math.ceil(remainingMs / 60_000)
  if (totalMinutes < 60) {
    return `${String(totalMinutes)} 分钟`
  }
  const totalHours = Math.floor(totalMinutes / 60)
  const remMinutes = totalMinutes % 60
  if (totalHours < 24) {
    return remMinutes === 0 ? `${String(totalHours)} 小时` : `${String(totalHours)} 小时 ${String(remMinutes)} 分钟`
  }
  const days = Math.floor(totalHours / 24)
  const remHours = totalHours % 24
  return remHours === 0 ? `${String(days)} 天` : `${String(days)} 天 ${String(remHours)} 小时`
}

/** 同一条回退链在网格标签 (buildSharedSlots) 与详情面板两处都要用到, 抽出来避免两处各写一遍且悄悄漂移。 */
function resolveSharedItemName(item: PlayerInventoryItem, names: Record<string, string>): string {
  if (item.displayName !== undefined) {
    return item.displayName
  }
  const translated = names[item.descriptionId]
  return translated === undefined ? item.descriptionId : translated
}

function buildSharedSlots(
  items: readonly PlayerInventoryItem[],
  totalSlots: number,
  names: Record<string, string>,
): PixelSlotGridEntry[] {
  return Array.from({ length: totalSlots }, (_unused, index) => {
    const item = items.find((entry) => entry.slot === index)
    if (item === undefined) {
      return { slot: index }
    }
    return {
      slot: index,
      itemId: item.itemId,
      count: item.count,
      label: resolveSharedItemName(item, names),
    }
  })
}

export function MarriagePage(): ReactElement {
  const world = useMockWorld()
  const stateQuery = useMockAction('marriage.state', {})
  const sharedQuery = useMockAction('marriage.sharedInv', {})

  const [banner, setBanner] = useState<Banner>(null)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [divorceConfirmOpen, setDivorceConfirmOpen] = useState(false)
  const [selectedSharedSlot, setSelectedSharedSlot] = useState<number | undefined>(undefined)

  const otherPlayers = world.otherPlayers.filter((candidate) => candidate.name !== world.player.name)
  const [proposeTarget, setProposeTarget] = useState<string>(() => {
    const first = otherPlayers[0]
    return first === undefined ? '' : first.name
  })

  const [nowTick, setNowTick] = useState<number>(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowTick(Date.now())
    }, 30_000)
    return () => {
      window.clearInterval(timer)
    }
  }, [])

  const [teleportPhase, setTeleportPhase] = useState<TeleportPhase>('idle')
  const [teleportStartedAt, setTeleportStartedAt] = useState(0)
  const [teleportCooldownUntil, setTeleportCooldownUntil] = useState(0)
  const [teleportTick, setTeleportTick] = useState<number>(() => Date.now())

  useEffect(() => {
    if (teleportPhase === 'idle') {
      return
    }
    const timer = window.setInterval(() => {
      setTeleportTick(Date.now())
    }, TELEPORT_TICK_MS)
    return () => {
      window.clearInterval(timer)
    }
  }, [teleportPhase])

  useEffect(() => {
    if (teleportPhase === 'channeling' && teleportTick - teleportStartedAt >= TELEPORT_CHANNEL_MS) {
      setTeleportPhase('cooldown')
      setTeleportCooldownUntil(Date.now() + TELEPORT_COOLDOWN_MS)
      setBanner({ tone: 'info', message: '传送完成 (本地模拟, 无服务端契约, 未产生任何实际效果)' })
    }
  }, [teleportPhase, teleportTick, teleportStartedAt])

  useEffect(() => {
    if (teleportPhase === 'cooldown' && teleportTick >= teleportCooldownUntil) {
      setTeleportPhase('idle')
    }
  }, [teleportPhase, teleportTick, teleportCooldownUntil])

  const sharedDescriptionIds =
    sharedQuery.status === 'ready' ? sharedQuery.data.items.map((item) => item.descriptionId) : []
  const sharedNames = useItemNames(sharedDescriptionIds)

  async function handleBuyRing(): Promise<void> {
    setBusyAction('ring')
    try {
      const result = await callMock('marriage.buyRing', {})
      setBanner({ tone: 'success', message: `购买婚戒成功, 花费 ${String(result.costCredit)} 信用点` })
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handlePropose(): Promise<void> {
    if (proposeTarget === '') {
      setBanner({ tone: 'danger', message: '没有可求婚的对象' })
      return
    }
    setBusyAction('propose')
    try {
      const result = await callMock('marriage.propose', { targetName: proposeTarget })
      setBanner({ tone: 'success', message: `已向 ${result.targetName} 发出求婚` })
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handleRespond(proposal: PlannedProposal, accept: boolean): Promise<void> {
    setBusyAction(`respond:${proposal.proposalId}`)
    try {
      const result = await callMock('marriage.respond', { proposalId: proposal.proposalId, accept })
      if (!accept) {
        setBanner({ tone: 'info', message: `已拒绝 ${proposal.playerName} 的求婚` })
      } else if (result.spouseName === null) {
        // 服务端回执理应带回配偶名; 缺席是契约破裂, 如实报出而不是拿 proposal.playerName 悄悄补上。
        setBanner({ tone: 'danger', message: '已接受求婚, 但回执缺少配偶姓名 (契约异常)' })
      } else {
        setBanner({ tone: 'success', message: `已与 ${result.spouseName} 订婚` })
      }
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handleWed(): Promise<void> {
    setBusyAction('wed')
    try {
      const result = await callMock('marriage.wed', {})
      setBanner({ tone: result.ok ? 'success' : 'danger', message: result.message })
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handleDivorceConfirm(): Promise<void> {
    setBusyAction('divorce')
    try {
      const result = await callMock('marriage.divorce', {})
      setBanner({ tone: result.ok ? 'warning' : 'danger', message: result.message })
      setDivorceConfirmOpen(false)
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusyAction(null)
    }
  }

  if (stateQuery.status === 'loading') {
    return (
      <div className="flex flex-col gap-4 p-4">
        <PixelLoading label="正在加载婚姻状态" size="lg" />
      </div>
    )
  }

  if (stateQuery.status === 'error') {
    return (
      <div className="flex flex-col gap-4 p-4">
        <PixelError message={stateQuery.error.message} onRetry={stateQuery.reload} />
      </div>
    )
  }

  const data = stateQuery.data
  const proposeOptions: PixelSelectOption[] = otherPlayers.map((candidate) => ({
    value: candidate.name,
    label: `${candidate.name}${candidate.online ? ' (在线)' : ' (离线)'}`,
  }))

  const teleportChannelElapsed =
    teleportPhase === 'channeling' ? Math.min(TELEPORT_CHANNEL_MS, teleportTick - teleportStartedAt) : 0
  const teleportCooldownRemaining =
    teleportPhase === 'cooldown' ? Math.max(0, teleportCooldownUntil - teleportTick) : 0
  const teleportCooldownElapsed = TELEPORT_COOLDOWN_MS - teleportCooldownRemaining
  const canTeleport = data.status === 'married' && data.spouseOnline

  const sharedSlots =
    sharedQuery.status === 'ready' ? buildSharedSlots(sharedQuery.data.items, sharedQuery.data.slots, sharedNames) : []
  const selectedSharedItem =
    sharedQuery.status === 'ready' && selectedSharedSlot !== undefined
      ? sharedQuery.data.items.find((item) => item.slot === selectedSharedSlot)
      : undefined

  // 已订婚/已婚状态下 spouseName 理应非空; 为空是契约异常, 如实标注而不是拿通用词悄悄补上。
  const spouseLabel = data.spouseName === null ? '(配偶姓名缺失, 契约异常)' : data.spouseName

  function handleStartTeleport(): void {
    const now = Date.now()
    setTeleportStartedAt(now)
    setTeleportTick(now)
    setTeleportPhase('channeling')
  }

  return (
    <div className="flex flex-col gap-4 p-4">
      {banner === null ? null : (
        <PixelFrame variant="panel" tone={banner.tone} className="w-full">
          <div className="flex items-center justify-between gap-4 p-3">
            <p className="text-1x text-fg">{banner.message}</p>
            <PixelButton size="sm" tone="neutral" icon="close" label="关闭" onClick={() => { setBanner(null) }} />
          </div>
        </PixelFrame>
      )}

      {/* 状态摘要 */}
      <PixelFrame variant="panel" className="w-full">
        <div className="flex flex-col gap-3 p-4">
          <div className="flex flex-wrap items-center gap-3">
            <PixelBadge tone={STATUS_TONE[data.status]} size="md">
              {STATUS_LABEL[data.status]}
            </PixelBadge>
            {data.status === 'married' || data.status === 'cooldown' ? (
              <span className="text-1x text-muted">离婚次数: {data.divorceCount}</span>
            ) : null}
          </div>

          {data.status === 'married' && data.spouseName !== null ? (
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-1x text-fg">
                配偶: {data.spouseName} ({data.spouseOnline ? '在线' : '离线'})
              </span>
              <span className="text-1x text-muted">婚龄: {data.marriageDays} 天</span>
            </div>
          ) : null}

          {data.status === 'cooldown' ? (
            <p className="text-1x text-warning">
              再婚冷却中, 还需 {formatDuration(data.remarryCooldownUntil - nowTick)}
            </p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            {data.ringOwned ? (
              <PixelBadge tone="success">已持有婚戒</PixelBadge>
            ) : (
              <PixelButton
                tone="accent"
                icon="coin-credit"
                loading={busyAction === 'ring'}
                disabled={busyAction !== null}
                onClick={() => { void handleBuyRing() }}
              >
                购买婚戒 (<PixelCurrency amount={data.ringPriceCredit} currency="credit" size="sm" />)
              </PixelButton>
            )}
          </div>

          {data.milestones.length === 0 ? null : (
            <div className="flex flex-wrap gap-2">
              {data.milestones.map((milestone) => (
                <PixelBadge key={milestone.milestoneId} tone={milestone.achievedAt === null ? 'neutral' : 'success'}>
                  {milestone.label}
                </PixelBadge>
              ))}
            </div>
          )}
        </div>
      </PixelFrame>

      {/* 求婚 (仅单身可发起) */}
      {data.status === 'single' ? (
        <PixelFrame variant="panel" className="w-full">
          <div className="flex flex-col gap-3 p-4">
            <h2 className="text-1x text-fg">求婚</h2>
            {data.outgoingProposal !== null ? (
              <p className="text-1x text-info">
                已向 {data.outgoingProposal.playerName} 求婚, 剩余 {formatDuration(data.outgoingProposal.expiresAt - nowTick)} 后过期
              </p>
            ) : proposeOptions.length === 0 ? (
              <PixelEmpty title="暂无可求婚对象" hint="mock 世界里没有其他玩家数据" />
            ) : (
              <>
                {data.ringOwned ? null : <p className="text-1x text-warning">需先购买婚戒才能求婚</p>}
                <div className="flex flex-wrap items-center gap-2">
                  <PixelSelect
                    value={proposeTarget}
                    options={proposeOptions}
                    onChange={setProposeTarget}
                    disabled={!data.ringOwned || busyAction !== null}
                  />
                  <PixelButton
                    tone="accent"
                    icon="heart"
                    loading={busyAction === 'propose'}
                    disabled={!data.ringOwned || busyAction !== null}
                    onClick={() => { void handlePropose() }}
                  >
                    求婚
                  </PixelButton>
                </div>
                <div className="flex flex-col gap-1">
                  <PixelInput
                    value=""
                    onChange={() => {
                      // onRequestEdit 模式下本回调不会被触发 (输入框为只读), 保留仅为满足受控 props。
                    }}
                    placeholder="搜索玩家 (暂不可输入中文)"
                    onRequestEdit={() => {
                      setBanner({
                        tone: 'info',
                        message: '宿主中文输入尚未接入 (接线清单 A14), 请改用上方下拉列表选择求婚对象',
                      })
                    }}
                  />
                  <p className="text-1x text-muted">
                    玩家名含中文, 当前无法通过界面直接输入; 上方下拉列表已列出全部已知玩家。
                  </p>
                </div>
              </>
            )}
          </div>
        </PixelFrame>
      ) : null}

      {/* 收到的求婚 */}
      {data.incomingProposals.length === 0 ? null : (
        <PixelFrame variant="panel" className="w-full">
          <div className="flex flex-col gap-3 p-4">
            <h2 className="text-1x text-fg">收到的求婚</h2>
            {data.incomingProposals.map((proposal) => (
              <div key={proposal.proposalId} className="flex flex-wrap items-center justify-between gap-3">
                <span className="text-1x text-fg">
                  {proposal.playerName} · 剩余 {formatDuration(proposal.expiresAt - nowTick)}
                </span>
                {data.status === 'single' ? (
                  <div className="flex gap-2">
                    <PixelButton
                      size="sm"
                      tone="success"
                      icon="check"
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      disabled={busyAction !== null}
                      onClick={() => { void handleRespond(proposal, true) }}
                    >
                      接受
                    </PixelButton>
                    <PixelButton
                      size="sm"
                      tone="danger"
                      icon="cross"
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      disabled={busyAction !== null}
                      onClick={() => { void handleRespond(proposal, false) }}
                    >
                      拒绝
                    </PixelButton>
                  </div>
                ) : (
                  <span className="text-1x text-muted">当前状态无法处理</span>
                )}
              </div>
            ))}
          </div>
        </PixelFrame>
      )}

      {/* 典礼 */}
      {data.status === 'engaged' ? (
        <PixelFrame variant="panel" className="w-full">
          <div className="flex items-center justify-between gap-4 p-4">
            <div>
              <h2 className="text-1x text-fg">婚礼典礼</h2>
              <p className="text-1x text-muted">已与 {spouseLabel} 订婚, 可举行典礼正式结为夫妻</p>
            </div>
            <PixelButton
              tone="accent"
              icon="heart"
              loading={busyAction === 'wed'}
              disabled={busyAction !== null}
              onClick={() => { void handleWed() }}
            >
              举行典礼
            </PixelButton>
          </div>
        </PixelFrame>
      ) : null}

      {/* 离婚 + 传送 + 共享背包 (仅已婚可用) */}
      {data.status === 'married' ? (
        <>
          <PixelFrame variant="panel" className="w-full">
            <div className="flex items-center justify-between gap-4 p-4">
              <h2 className="text-1x text-fg">离婚</h2>
              <PixelButton tone="danger" onClick={() => { setDivorceConfirmOpen(true) }}>
                申请离婚
              </PixelButton>
            </div>
          </PixelFrame>

          <PixelFrame variant="panel" className="w-full">
            <div className="flex flex-col gap-3 p-4">
              <h2 className="text-1x text-fg">传送至配偶</h2>
              <p className="text-1x text-muted">
                本区块尚无对应契约 (planned.ts 无 marriage.teleport), 以下蓄力/冷却为纯前端本地模拟,
                不产生任何服务端或 mock 世界状态变更。
              </p>
              {teleportPhase === 'idle' ? (
                <div className="flex items-center gap-2">
                  <PixelButton tone="accent" disabled={!canTeleport} onClick={handleStartTeleport}>
                    传送至配偶
                  </PixelButton>
                  {canTeleport ? null : <span className="text-1x text-muted">配偶当前不在线</span>}
                </div>
              ) : teleportPhase === 'channeling' ? (
                <div className="flex flex-col gap-2">
                  <PixelProgress value={teleportChannelElapsed} max={TELEPORT_CHANNEL_MS} tone="accent" label="蓄力中" />
                  <PixelButton tone="danger" size="sm" onClick={() => { setTeleportPhase('idle') }}>
                    取消
                  </PixelButton>
                </div>
              ) : (
                <PixelProgress
                  value={teleportCooldownElapsed}
                  max={TELEPORT_COOLDOWN_MS}
                  tone="warning"
                  label={`冷却中, 还需 ${String(Math.ceil(teleportCooldownRemaining / 1000))} 秒`}
                />
              )}
            </div>
          </PixelFrame>

          <PixelFrame variant="panel" className="w-full">
            <div className="flex flex-col gap-3 p-4">
              <h2 className="text-1x text-fg">
                共享背包 (等级 {data.sharedInvLevel}, {data.sharedInvSlots} 格)
              </h2>
              <p className="text-1x text-muted">只读快照; 取放物品请在游戏内使用共享背包容器界面。</p>
              {sharedQuery.status === 'loading' ? (
                <PixelLoading label="正在加载共享背包" />
              ) : sharedQuery.status === 'error' ? (
                <PixelError message={sharedQuery.error.message} />
              ) : (
                <div className="flex flex-col gap-3">
                  <PixelSlotGrid
                    slots={sharedSlots}
                    columns={sharedQuery.data.slots}
                    {...(selectedSharedSlot === undefined ? {} : { selectedSlot: selectedSharedSlot })}
                    onSelect={setSelectedSharedSlot}
                    label="共享背包"
                  />
                  {selectedSharedItem === undefined ? (
                    <p className="text-1x text-muted">点击一个格子查看物品详情</p>
                  ) : (
                    <div className="flex items-center gap-3">
                      <ItemIcon itemId={selectedSharedItem.itemId} scale={2} />
                      <div className="flex flex-col">
                        <span className="text-1x text-fg">{resolveSharedItemName(selectedSharedItem, sharedNames)}</span>
                        <span className="text-1x text-muted">数量: {selectedSharedItem.count}</span>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </PixelFrame>
        </>
      ) : null}

      <PixelConfirmDanger
        open={divorceConfirmOpen}
        title="确认离婚"
        message={`离婚后进入再婚冷却, 与 ${spouseLabel} 的婚姻关系将立即解除, 此操作不可撤销。`}
        confirmLabel="确认离婚"
        loading={busyAction === 'divorce'}
        onConfirm={() => { void handleDivorceConfirm() }}
        onCancel={() => { setDivorceConfirmOpen(false) }}
      />
    </div>
  )
}
