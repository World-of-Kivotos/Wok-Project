import { CheckIcon, CoinsIcon, HeartIcon, UsersIcon, XIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  formatAmount,
  ItemIcon,
  ItemSlotGrid,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Tag,
  TextInput,
} from '@/components/kit'
import type { DropdownOption, FeedbackTone, ItemSlotGridEntry, Tone } from '@/components/kit'
import { useItemDisplayNames } from '../lib/i18n'
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

const STATUS_TONE: Record<PlannedMarriageStatus, Tone> = {
  single: 'neutral',
  engaged: 'info',
  married: 'success',
  cooldown: 'warning',
}

type Banner = { tone: FeedbackTone; message: string } | null

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
function resolveSharedItemName(
  item: PlayerInventoryItem,
  nameOf: (item: PlayerInventoryItem) => string,
): string {
  if (item.displayName !== undefined) {
    return item.displayName
  }
  return nameOf(item)
}

/** 格子在网格里的身份就是它的下标, 故这里按 slot 号逐位填充, 空位留空对象而不是跳过。 */
function buildSharedSlots(
  items: readonly PlayerInventoryItem[],
  totalSlots: number,
  nameOf: (item: PlayerInventoryItem) => string,
): ItemSlotGridEntry[] {
  return Array.from({ length: totalSlots }, (_unused, index) => {
    const item = items.find((entry) => entry.slot === index)
    if (item === undefined) {
      return {}
    }
    return {
      itemId: item.itemId,
      // 不带这个键的话, 195 种枪匠零件在共享背包网格里是同一张图。
      customModelData: item.customModelData,
      count: item.count,
      label: resolveSharedItemName(item, nameOf),
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
      setBanner({ tone: 'info', message: '传送功能尚未开放, 刚才只是演示效果, 位置没有改变' })
    }
  }, [teleportPhase, teleportTick, teleportStartedAt])

  useEffect(() => {
    if (teleportPhase === 'cooldown' && teleportTick >= teleportCooldownUntil) {
      setTeleportPhase('idle')
    }
  }, [teleportPhase, teleportTick, teleportCooldownUntil])

  const sharedNameOf = useItemDisplayNames(
    sharedQuery.status === 'ready' ? sharedQuery.data.items : [],
  )

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
        setBanner({ tone: 'danger', message: '已接受求婚, 但没能读到配偶姓名, 请刷新后确认' })
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
      <div className="flex flex-col gap-4">
        <LoadingBlock label="正在加载婚姻状态" size="lg" />
      </div>
    )
  }

  if (stateQuery.status === 'error') {
    return (
      <div className="flex flex-col gap-4">
        <ErrorBlock message={stateQuery.error.message} onRetry={stateQuery.reload} />
      </div>
    )
  }

  const data = stateQuery.data
  const proposeOptions: DropdownOption<string>[] = otherPlayers.map((candidate) => ({
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
    sharedQuery.status === 'ready' ? buildSharedSlots(sharedQuery.data.items, sharedQuery.data.slots, sharedNameOf) : []
  const selectedSharedItem =
    sharedQuery.status === 'ready' && selectedSharedSlot !== undefined
      ? sharedQuery.data.items.find((item) => item.slot === selectedSharedSlot)
      : undefined

  /*
   * 已订婚/已婚状态下 spouseName 理应非空; 为空是数据异常, 如实说出来而不是拿"对方"这类通用词补上。
   *
   * 但**不能**把这句提示塞进句子的姓名位 —— 那会得到"已与 (姓名读取失败) 订婚"这种读不通的句子。
   * 下面两处消费点各自整句分叉: 有姓名说一句, 没姓名说另一句。
   */

  function handleStartTeleport(): void {
    const now = Date.now()
    setTeleportStartedAt(now)
    setTeleportTick(now)
    setTeleportPhase('channeling')
  }

  return (
    <div className="flex flex-col gap-4">
      {banner === null ? null : (
        <FeedbackAlert
          message={banner.message}
          onDismiss={() => {
            setBanner(null)
          }}
          tone={banner.tone}
        />
      )}

      {/* 状态摘要 */}
      <Panel actions={<Tag tone={STATUS_TONE[data.status]}>{STATUS_LABEL[data.status]}</Tag>} title="婚姻状态">
        <div className="flex flex-col gap-3">
          {data.status === 'married' && data.spouseName !== null ? (
            <>
              <Stat
                label="配偶"
                layout="inline"
                value={`${data.spouseName} (${data.spouseOnline ? '在线' : '离线'})`}
              />
              <Stat label="婚龄" layout="inline" value={`${String(data.marriageDays)} 天`} />
            </>
          ) : null}

          {data.status === 'married' || data.status === 'cooldown' ? (
            <Stat label="离婚次数" layout="inline" value={data.divorceCount} />
          ) : null}

          {data.status === 'cooldown' ? (
            <p className="text-warning text-sm">
              再婚冷却中, 还需 {formatDuration(data.remarryCooldownUntil - nowTick)}
            </p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            {data.ringOwned ? (
              <Tag tone="success">已持有婚戒</Tag>
            ) : (
              <Button
                disabled={busyAction !== null}
                loading={busyAction === 'ring'}
                onClick={() => {
                  void handleBuyRing()
                }}
                variant="brand"
              >
                <CoinsIcon />
                购买婚戒 ({formatAmount(data.ringPriceCredit)} 信用点)
              </Button>
            )}
          </div>

          {data.milestones.length === 0 ? null : (
            <div className="flex flex-wrap gap-2">
              {data.milestones.map((milestone) => (
                <Tag key={milestone.milestoneId} tone={milestone.achievedAt === null ? 'neutral' : 'success'}>
                  {milestone.label}
                </Tag>
              ))}
            </div>
          )}
        </div>
      </Panel>

      {/* 求婚 (仅单身可发起) */}
      {data.status === 'single' ? (
        <Panel title="求婚">
          <div className="flex flex-col gap-3">
            {data.outgoingProposal !== null ? (
              <p className="text-info text-sm">
                已向 {data.outgoingProposal.playerName} 求婚, 剩余 {formatDuration(data.outgoingProposal.expiresAt - nowTick)} 后过期
              </p>
            ) : proposeOptions.length === 0 ? (
              <EmptyBlock
                hint="等其他玩家上线后再来试试"
                icon={<UsersIcon aria-hidden="true" />}
                title="暂无可求婚对象"
              />
            ) : (
              <>
                {data.ringOwned ? null : <p className="text-warning text-sm">需先购买婚戒才能求婚</p>}
                <div className="flex flex-wrap items-center gap-2">
                  <Dropdown
                    disabled={!data.ringOwned || busyAction !== null}
                    onChange={setProposeTarget}
                    options={proposeOptions}
                    value={proposeTarget}
                  />
                  <TextInput
                    className="w-48"
                    onChange={() => {
                      // onRequestEdit 模式下本回调不会被触发 (输入框为只读), 保留仅为满足受控 props。
                    }}
                    onRequestEdit={() => {
                      setBanner({
                        tone: 'info',
                        message: '中文输入暂未开放, 请用左侧的下拉列表选择求婚对象',
                      })
                    }}
                    placeholder="搜索玩家"
                    value=""
                  />
                  <Button
                    disabled={!data.ringOwned || busyAction !== null}
                    loading={busyAction === 'propose'}
                    onClick={() => {
                      void handlePropose()
                    }}
                    variant="brand"
                  >
                    <HeartIcon />
                    求婚
                  </Button>
                </div>
              </>
            )}
          </div>
        </Panel>
      ) : null}

      {/* 收到的求婚 */}
      {data.incomingProposals.length === 0 ? null : (
        <Panel title="收到的求婚">
          <div className="flex flex-col gap-3">
            {data.incomingProposals.map((proposal) => (
              <div className="flex flex-wrap items-center justify-between gap-3" key={proposal.proposalId}>
                <span className="text-foreground text-sm">
                  {proposal.playerName} · 剩余 {formatDuration(proposal.expiresAt - nowTick)}
                </span>
                {data.status === 'single' ? (
                  <div className="flex gap-2">
                    <Button
                      disabled={busyAction !== null}
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      onClick={() => {
                        void handleRespond(proposal, true)
                      }}
                      size="sm"
                      variant="brand"
                    >
                      <CheckIcon />
                      接受
                    </Button>
                    <Button
                      disabled={busyAction !== null}
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      onClick={() => {
                        void handleRespond(proposal, false)
                      }}
                      size="sm"
                      variant="destructive"
                    >
                      <XIcon />
                      拒绝
                    </Button>
                  </div>
                ) : (
                  <span className="text-muted-foreground text-sm">当前状态无法处理</span>
                )}
              </div>
            ))}
          </div>
        </Panel>
      )}

      {/* 典礼 */}
      {data.status === 'engaged' ? (
        <Panel
          actions={
            <Button
              disabled={busyAction !== null}
              loading={busyAction === 'wed'}
              onClick={() => {
                void handleWed()
              }}
              variant="brand"
            >
              <HeartIcon />
              举行典礼
            </Button>
          }
          title="婚礼典礼"
        >
          <p className="text-muted-foreground text-sm">
            {data.spouseName === null
              ? '配偶姓名读取失败, 请刷新后再举行典礼'
              : `已与 ${data.spouseName} 订婚, 可举行典礼正式结为夫妻`}
          </p>
        </Panel>
      ) : null}

      {/* 离婚 + 传送 + 共享背包 (仅已婚可用) */}
      {data.status === 'married' ? (
        <>
          <Panel
            actions={
              <Button
                onClick={() => {
                  setDivorceConfirmOpen(true)
                }}
                size="sm"
                variant="destructive"
              >
                申请离婚
              </Button>
            }
            title="离婚"
          >
            <p className="text-muted-foreground text-sm">
              离婚后需要等待一段冷却时间才能再次结婚, 且无法撤销。
            </p>
          </Panel>

          <Panel
            description="传送功能尚未开放, 下面的蓄力与冷却只是效果演示, 不会真的改变你的位置。"
            title="传送至配偶"
          >
            <div className="flex flex-col gap-3">
              {teleportPhase === 'idle' ? (
                <div className="flex items-center gap-2">
                  <Button disabled={!canTeleport} onClick={handleStartTeleport} variant="brand">
                    传送至配偶
                  </Button>
                  {canTeleport ? null : <span className="text-muted-foreground text-sm">配偶当前不在线</span>}
                </div>
              ) : teleportPhase === 'channeling' ? (
                <div className="flex flex-col gap-2">
                  <Meter label="蓄力中" max={TELEPORT_CHANNEL_MS} tone="brand" value={teleportChannelElapsed} />
                  <div>
                    <Button
                      onClick={() => {
                        setTeleportPhase('idle')
                      }}
                      size="sm"
                      variant="destructive"
                    >
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <Meter
                  label={`冷却中, 还需 ${String(Math.ceil(teleportCooldownRemaining / 1000))} 秒`}
                  max={TELEPORT_COOLDOWN_MS}
                  tone="warning"
                  value={teleportCooldownElapsed}
                />
              )}
            </div>
          </Panel>

          <Panel
            description="这里只能查看, 存取物品请在游戏里打开共享背包。"
            title={`共享背包 (等级 ${String(data.sharedInvLevel)}, ${String(data.sharedInvSlots)} 格)`}
          >
            {sharedQuery.status === 'loading' ? (
              <LoadingBlock label="正在加载共享背包" />
            ) : sharedQuery.status === 'error' ? (
              <ErrorBlock message={sharedQuery.error.message} />
            ) : (
              <div className="flex flex-col gap-3">
                <ItemSlotGrid
                  // 按箱子的 9 列排, 而不是把全部格子摊成一行 —— 升级后格数变多时那一行会横着溢出面板。
                  columns={Math.min(9, sharedQuery.data.slots)}
                  label="共享背包"
                  onSelect={setSelectedSharedSlot}
                  selectedSlot={selectedSharedSlot}
                  slots={sharedSlots}
                />
                {selectedSharedItem === undefined ? (
                  <p className="text-muted-foreground text-sm">点击一个格子查看物品详情</p>
                ) : (
                  <div className="flex items-center gap-3">
                    <ItemIcon
                      customModelData={selectedSharedItem.customModelData}
                      itemId={selectedSharedItem.itemId}
                      scale={2}
                    />
                    <div className="flex flex-col">
                      <span className="text-foreground text-sm">
                        {resolveSharedItemName(selectedSharedItem, sharedNameOf)}
                      </span>
                      <span className="text-muted-foreground text-xs">数量: {selectedSharedItem.count}</span>
                    </div>
                  </div>
                )}
              </div>
            )}
          </Panel>
        </>
      ) : null}

      <ConfirmDangerDialog
        confirmLabel="确认离婚"
        loading={busyAction === 'divorce'}
        message={
          data.spouseName === null
            ? '离婚后进入再婚冷却, 当前的婚姻关系将立即解除, 此操作不可撤销。'
            : `离婚后进入再婚冷却, 与 ${data.spouseName} 的婚姻关系将立即解除, 此操作不可撤销。`
        }
        onConfirm={() => {
          void handleDivorceConfirm()
        }}
        onOpenChange={setDivorceConfirmOpen}
        open={divorceConfirmOpen}
        title="确认离婚"
      />
    </div>
  )
}
