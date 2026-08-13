import { FilterIcon, InfoIcon, LockIcon, RefreshCwIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { DropdownOption, FeedbackTone, Tone } from '@/components/kit'
import {
  Button,
  Currency,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  Hint,
  ItemIcon,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import { WebUiCallError, isMockActive } from '../lib/bridge'
import type {
  MockActionQuery,
  PlannedDifficulty,
  PlannedEconomyTodayResult,
  PlannedHubPanel,
  PlannedJobProgressEntry,
  PlannedMarriageStateResult,
  PlannedMarriageStatus,
  PlannedMiningInstance,
  PlannedMiningMyStatusResult,
  PlannedProfileResult,
} from '../mock'
import { callMock, nowMs, useMockAction, useMockWorld } from '../mock'
import {
  ROUTE_HOME,
  ROUTE_MARRIAGE,
  ROUTE_MINING,
  buildJobDetailPath,
  matchRoute,
  useNavigate,
} from '../router'

/**
 * 首页 · 个人档案 —— 平板打开后的第一屏。
 *
 * 信息架构取自接线清单第一章: 这一屏要在不翻页的前提下回答四个问题 —— 我今天赚了多少 (还能赚多少)、
 * 八个职业各卡在哪、我现在在不在矿洞、我的婚姻处于什么状态; 快捷入口排在最后, 因为它是"去别处"的出口
 * 而不是本页要承载的信息。
 *
 * 一条定死的设计: 八职业**并列**渲染, 不做单选器 (清单 C3, 决策已定 —— 全职业被动恒生效, 不存在
 * "当前激活职业")。任何把它改成"选一个职业看"的改动都是设计倒退, 不是布局优化。
 *
 * === 本页依赖的假定契约 (mock/planned.ts, 逐条对应接线清单第三章总表的行) ===
 *   A5  player.profile    首屏聚合: 玩家名/OP/钱包/8 职业进度/今日双币收入/婚姻一句话/所在矿洞难度
 *   A17 hub.panels        快捷入口的面板注册表 (含 enabled + lockReason)
 *   D2  economy.status    挂机冻结 (冻结期间 faucet 不入账, 是"今天赚了多少"的前置条件)
 *   D3  economy.today     各 faucet 当日进度与衰减档 (faucets[].decayFactor)
 *   D4  economy.today     青辉石每日硬上限 (azureIn / azureDailyCap)
 *   D6  economy.today     今日全口径收支合计 (totalCreditIn / totalCreditOut / sinks)
 *   E1  marriage.state    婚姻摘要 (状态/配偶/婚龄/共享背包/婚戒/待答复求婚)
 *   F1  mining.overview   三个常驻实例的名字与等级门 (R1 模型: 全服每难度只有一个, 不是私有副本)
 *   F2  mining.myStatus   我在不在矿洞 / 所在区块 / 实时 danger
 *   F8  mining.myStatus   新手保护倒计时 (spawnFreezeUntil)
 *   F3  mining.enter      快捷进入 (服务端裁决等级门, 前端不代拒)
 *   F4  mining.leave      快捷离开
 *   A8  player.itemDetail 仅用于错误通道自检, 不参与业务展示 (见文件末 BridgeErrorProbe)
 *   A14 中文输入 BLOCKED  快捷入口搜索框只能留 onRequestEdit 接口位 (见 PanelsSection)
 *
 * 接线核销: 上面每一行落地后, 按 Java 实现重写 planned 类型即可, 本页的读取点不需要改名 ——
 * 页面全程只经 callMock/useMockAction 调用, 而 handlers 会自动把已核销的 action 甩回真桥。
 *
 * 为什么本页要自己再发一次 player.profile (外壳顶栏已经发过一次):
 * 顶栏只需要名字与余额, 本页需要 jobs / todayCreditIn / marriageSummary / miningDifficulty 这几块;
 * 前端至今没有跨组件的请求缓存层, 与其为了省一次往返在两个组件之间搭一条隐式依赖, 不如各查各的 ——
 * 接线后这条重复往返若成为真实开销, 该补的是一层通用请求缓存, 不是让首页去读外壳的私有状态。
 */

/** planned 域的空入参。提到模块级只是让"本页一共发几种请求"一眼可数。 */
const EMPTY_PAYLOAD: Record<string, never> = {}

/**
 * 倒计时刷新周期。取 1 秒是因为本页最短的那个倒计时 (F8 新手保护) 只有 30 秒量级 ——
 * 周期比它长会让玩家看到一个卡住不动的数字, 那比不显示更糟。
 */
const TICK_INTERVAL_MS = 1_000

/**
 * 错误通道自检用的槽位号。背包槽位从 0 起, -1 恒不可能存在, 因此这一发请求必然被服务端拒绝 ——
 * 这正是自检要的: 用一条真实的失败往返验证 WebUiCallError 一路能画到界面上 (见 BridgeErrorProbe)。
 */
const PROBE_EMPTY_SLOT = -1

// ============================================================
// 纯函数与档位表
// ============================================================

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/**
 * 错误码展示串。A10 (错误码中文化) 未做, 服务端回的是 Java 异常原文, 故这里只把"哪个 action、哪个
 * 失败码"补在文案下方 —— 那是排障唯一能用的线索, 而 message 本身不做任何加工。
 */
function errorCodeOf(error: Error): string | null {
  if (!(error instanceof WebUiCallError)) {
    return null
  }
  return `${error.action} / code ${String(error.code)}`
}

/** 整数千分位。经验值与人数不是货币, 不能借 Currency 渲染 (那会给它挂上一个币种图标)。 */
function formatInteger(value: number): string {
  return value.toLocaleString('zh-CN')
}

/** 剩余时长。只到"时/分"或"分/秒"两档, 首页不需要秒级精度以外的第三档。 */
function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) {
    return `${String(hours)} 小时 ${String(minutes)} 分`
  }
  if (minutes > 0) {
    return `${String(minutes)} 分 ${String(seconds)} 秒`
  }
  return `${String(seconds)} 秒`
}

function formatClock(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString('zh-CN', { hour12: false })
}

/**
 * faucet 衰减档的颜色。1 = 满额入账, 低于 1 即已过软上限按比例打折 (D3 玩家最想看的那个数),
 * 0.5 以下按危险色 —— 那一档继续刷同一个 faucet 的收益已经不划算, 该换个赚钱路子。
 */
function decayTone(decayFactor: number): Tone {
  if (decayFactor >= 1) {
    return 'success'
  }
  if (decayFactor >= 0.5) {
    return 'warning'
  }
  return 'danger'
}

/** 矿洞 danger 是 0..1 的实时值 (F6)。三档只决定颜色, 不参与任何业务判定。 */
function dangerTone(danger: number): Tone {
  if (danger < 0.3) {
    return 'success'
  }
  if (danger < 0.7) {
    return 'warning'
  }
  return 'danger'
}

interface MarriageStatusStyle {
  readonly label: string
  readonly tone: Tone
}

/** 写成 Record<union, T>: 婚姻状态加一档 (planned E1 的 PlannedMarriageStatus) 时 tsc 直接报缺键。 */
const MARRIAGE_STATUS_STYLE: Record<PlannedMarriageStatus, MarriageStatusStyle> = {
  single: { label: '未婚', tone: 'neutral' },
  engaged: { label: '已订婚', tone: 'info' },
  married: { label: '已婚', tone: 'success' },
  cooldown: { label: '再婚冷却中', tone: 'warning' },
}

/** 快捷入口的可见范围。'locked' 一档存在的理由是让"我为什么进不去"可被单独筛出来看。 */
type PanelScope = 'all' | 'open' | 'locked'

const PANEL_SCOPE_OPTIONS: readonly DropdownOption<PanelScope>[] = [
  { value: 'all', label: '全部面板' },
  { value: 'open', label: '仅可进入' },
  { value: 'locked', label: '仅已锁定' },
]

function isPanelScope(value: string): value is PanelScope {
  return value === 'all' || value === 'open' || value === 'locked'
}

function isDifficulty(value: string): value is PlannedDifficulty {
  return value === 'easy' || value === 'medium' || value === 'hard'
}

/**
 * 一个面板当前能不能进。
 *
 * 两个来源都要判, 缺一不可: 注册表自己的 enabled/lockReason 是服务端的门控 (等级门/OP/婚姻),
 * 而 route 能不能在前端路由表里找到是**接线正确性**问题 —— mock 的注册表里就有 /champion 与 /quests
 * 两条对不上 router.ts 的路由 (前者真实路由是 /codex, 后者整个任务系统零实现)。
 * 不判后者的话, 点下去会进"未知路由"页, 那看起来像前端坏了, 而实际是注册表与路由表脱节。
 */
interface PanelAvailability {
  readonly usable: boolean
  /** usable 为真时是 null。 */
  readonly reason: string | null
}

function panelAvailability(panel: PlannedHubPanel): PanelAvailability {
  /*
   * 两处数据缺陷对玩家一律只说"尚未开放", 技术细节只在假数据模式下补出来。
   *
   * 玩家看到"路由 /quests 未在前端路由表登记 (面板注册表与 router.ts 脱节)"既看不懂也无从处理,
   * 而这条信息对开发是有用的 —— 故不是删掉, 是收进 isMockActive() 里 (生产构建恒为 false, 装进游戏
   * 后整段不存在)。
   */
  if (matchRoute(panel.route).pattern === null) {
    return {
      usable: false,
      reason: isMockActive()
        ? `尚未开放 (路由 ${panel.route} 未在前端路由表登记, 面板注册表与 router.ts 脱节)`
        : '该功能尚未开放',
    }
  }
  if (!panel.enabled) {
    if (panel.lockReason === null) {
      return {
        usable: false,
        reason: isMockActive() ? '尚未开放 (注册表标为不可用却未给出原因, 数据缺陷)' : '该功能尚未开放',
      }
    }
    return { usable: false, reason: panel.lockReason }
  }
  return { usable: true, reason: null }
}

// ============================================================
// 页面内的通用小件 (都是既有 kit 控件的组合, 不是新控件)
// ============================================================

/**
 * 自走的"当前时刻"。倒计时若只在渲染那一刻算一次, 玩家看到的是一个静止的假数字 ——
 * 冷却、新手保护、翻日剩余全是随时间走的量, 必须自己推。
 */
function useNowTick(intervalMs: number): number {
  const [now, setNow] = useState(() => nowMs())
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(nowMs())
    }, intervalMs)
    return () => {
      clearInterval(timer)
    }
  }, [intervalMs])
  return now
}

interface QueryGateProps<T> {
  query: MockActionQuery<T>
  /** 加载态给读屏与肉眼的名字, 形如"读取今日收支"。 */
  loadingLabel: string
  children: (data: T) => ReactNode
}

/**
 * 三态收口: 加载 / 失败 / 就绪。
 *
 * 每张卡各自过一遍而不是整页一个大 gate: 七条请求各自往返, 整页 gate 会让最慢的一条把已经到达的
 * 六块内容一起按住不画 —— 在 MCEF 那条往返上这不是理论问题。失败也同理只废掉自己那一块, 且带
 * onRetry 让人当场重试, 不必整页刷新。
 */
function QueryGate<T>({ query, loadingLabel, children }: QueryGateProps<T>): ReactElement {
  if (query.status === 'loading') {
    return <LoadingBlock label={loadingLabel} size="sm" />
  }
  if (query.status === 'error') {
    const code = errorCodeOf(query.error)
    return (
      <ErrorBlock
        message={query.error.message}
        onRetry={query.reload}
        {...(code === null ? {} : { code })}
      />
    )
  }
  return <>{children(query.data)}</>
}

interface CapBarProps {
  label: string
  value: number
  /** 上限。非正数一律不画条 —— Meter 明确不为 max<=0 兜底, 画出来是 NaN 宽度的空槽。 */
  max: number
  tone: Tone
}

/** 带上限的进度条 + 缺上限时的诚实回退。 */
function CapBar({ label, value, max, tone }: CapBarProps): ReactElement {
  if (max <= 0) {
    return (
      <p className="text-muted-foreground text-sm">
        {label}: {formatInteger(value)} · 暂无上限数据
      </p>
    )
  }
  return <Meter value={value} max={max} tone={tone} size="sm" label={label} />
}

// ============================================================
// 分区: 双货币与今日额度
// ============================================================

interface WalletSectionProps {
  profile: PlannedProfileResult
  today: PlannedEconomyTodayResult
  now: number
}

function WalletSection({ profile, today, now }: WalletSectionProps): ReactElement {
  const netCredit = today.totalCreditIn - today.totalCreditOut

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-3 gap-4">
        <Stat
          label="信用点余额"
          value={<Currency amount={profile.wallet.credit} currency="credit" size="lg" />}
          hint={`今日入账 ${formatInteger(profile.todayCreditIn)}`}
        />
        <Stat
          label="青辉石余额"
          value={<Currency amount={profile.wallet.azure} currency="azure" size="lg" />}
          hint={`今日入账 ${formatInteger(profile.todayAzureIn)}`}
        />
        <Stat
          label="今日净收入"
          value={<Currency amount={netCredit} currency="credit" size="lg" signed />}
          hint={`距翻日 ${formatRemaining(today.resetsAt - now)}`}
        />
      </div>

      {/*
        青辉石与信用点的上限机制不是一回事, 必须分开画: 青辉石是硬截断 (D4, 撞顶即一分不发),
        信用点各 faucet 是软上限后按 decayFactor 打折继续发 (D3)。用同一种条画两种规则, 玩家会
        以为撞了软上限就没得赚了。
      */}
      <CapBar
        label={`青辉石今日 ${formatInteger(today.azureIn)} / ${formatInteger(today.azureDailyCap)} · 到顶后今天不再产出`}
        value={today.azureIn}
        max={today.azureDailyCap}
        tone={today.azureIn >= today.azureDailyCap ? 'danger' : 'info'}
      />

      <div className="flex flex-col gap-3">
        <h3 className="font-medium text-sm text-foreground">今日收入额度</h3>
        {today.faucets.length === 0 ? (
          <EmptyBlock
            title="今日还没有收入"
            hint="打一次矿或卖一次菜后这里会出现进度"
            icon={<InfoIcon aria-hidden="true" />}
          />
        ) : (
          today.faucets.map((faucet) => (
            <div key={faucet.faucetKey} className="flex flex-col gap-1.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm text-foreground">{faucet.label}</span>
                <div className="flex items-center gap-2">
                  <Currency amount={faucet.earnedToday} currency="credit" size="sm" showIcon={false} />
                  <span className="text-muted-foreground text-xs">/ {formatInteger(faucet.softCap)}</span>
                  <Tag size="sm" tone={decayTone(faucet.decayFactor)}>
                    {faucet.decayFactor >= 1 ? '全额' : `收益 ${String(Math.round(faucet.decayFactor * 100))}%`}
                  </Tag>
                </div>
              </div>
              <CapBar
                label={`${faucet.label} 今日进度`}
                value={faucet.earnedToday}
                max={faucet.softCap}
                tone={decayTone(faucet.decayFactor)}
              />
            </div>
          ))
        )}
      </div>

      <div className="flex flex-col gap-2">
        <h3 className="font-medium text-sm text-foreground">今日支出</h3>
        {today.sinks.length === 0 ? (
          <p className="text-muted-foreground text-sm">今日还没有任何支出记录。</p>
        ) : (
          <div className="grid grid-cols-2 gap-x-4 gap-y-1">
            {today.sinks.map((sink) => (
              <Stat
                key={sink.sinkKey}
                label={sink.label}
                layout="inline"
                value={<Currency amount={sink.spentToday} currency="credit" size="sm" showIcon={false} />}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ============================================================
// 分区: 八职业进度 (并列, 非单选器)
// ============================================================

interface JobsSectionProps {
  jobs: readonly PlannedJobProgressEntry[]
  onOpen: (jobId: string) => void
}

function JobsSection({ jobs, onOpen }: JobsSectionProps): ReactElement {
  if (jobs.length === 0) {
    return (
      <EmptyBlock
        title="没有拿到任何职业进度"
        hint="player.profile 回执里的 jobs 是空数组"
        icon={<TriangleAlertIcon aria-hidden="true" />}
      />
    )
  }
  return (
    <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
      {jobs.map((job) => (
        <JobRow key={job.jobId} job={job} onOpen={onOpen} />
      ))}
    </div>
  )
}

interface JobRowProps {
  job: PlannedJobProgressEntry
  onOpen: (jobId: string) => void
}

function JobRow({ job, onOpen }: JobRowProps): ReactElement {
  // nextLevelXp 为 0 即满级 (planned A5 字段注释明确要求据此判, 不许硬编码 level === 10)。
  const maxed = job.nextLevelXp === 0
  const dailyTotal = job.dailyXp + job.dailyRemaining

  return (
    <Surface className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="font-medium text-sm text-foreground">{job.displayName}</span>
          <Tag size="sm" tone={maxed ? 'success' : 'neutral'}>
            Lv.{String(job.level)}
          </Tag>
          {maxed ? (
            <Tag size="sm" tone="success">
              满级
            </Tag>
          ) : null}
          {job.dailyRemaining === 0 ? (
            <Tag size="sm" tone="warning">
              今日额度已满
            </Tag>
          ) : null}
        </div>
        <Button
          size="xs"
          variant="outline"
          onClick={() => {
            onOpen(job.jobId)
          }}
        >
          详情
        </Button>
      </div>

      {maxed ? (
        // 满级没有"下一级所需", 拿 0 当分母画出来的是一条 NaN 宽度的空槽; 直接换成一句结论。
        <p className="text-sm text-success">已满级 · 累计经验 {formatInteger(job.totalXp)}</p>
      ) : (
        <Meter
          value={job.levelXp}
          max={job.nextLevelXp}
          tone="brand"
          size="sm"
          label={`本级经验 ${formatInteger(job.levelXp)} / ${formatInteger(job.nextLevelXp)}`}
        />
      )}

      <CapBar
        label={`今日经验 ${formatInteger(job.dailyXp)} / ${formatInteger(dailyTotal)} (超出后按衰减入账)`}
        value={job.dailyXp}
        max={dailyTotal}
        tone={job.dailyRemaining === 0 ? 'warning' : 'info'}
      />
    </Surface>
  )
}

// ============================================================
// 分区: 当前所在矿洞
// ============================================================

interface MiningSectionProps {
  myStatus: PlannedMiningMyStatusResult
  instances: readonly PlannedMiningInstance[]
  /** 难度选择是本页唯一的"待提交入参", 由页面持有以便进入失败后保留选择。 */
  difficulty: PlannedDifficulty
  onDifficultyChange: (next: PlannedDifficulty) => void
  onEnter: () => void
  onLeave: () => void
  busy: boolean
  now: number
}

function MiningSection({
  myStatus,
  instances,
  difficulty,
  onDifficultyChange,
  onEnter,
  onLeave,
  busy,
  now,
}: MiningSectionProps): ReactElement {
  const options: readonly DropdownOption<PlannedDifficulty>[] = instances.map((instance) => ({
    value: instance.difficulty,
    label: `${instance.displayName} (需矿工 ${String(instance.requiredMinerLevel)} 级)`,
  }))
  const selected = instances.find((instance) => instance.difficulty === difficulty)
  const current = instances.find((instance) => instance.difficulty === myStatus.difficulty)
  const freezeRemaining = myStatus.spawnFreezeUntil - now

  if (!myStatus.inside) {
    return (
      <div className="flex flex-col gap-3">
        <EmptyBlock
          title="当前不在任何矿洞内"
          hint="全服每个难度只有一个常驻实例 (R1 模型), 进入即与其他玩家共处同一份地形"
          icon={<InfoIcon aria-hidden="true" />}
        />
        <div className="flex flex-wrap items-center gap-2">
          {options.length === 0 ? (
            <p className="text-muted-foreground text-sm">没有拿到任何矿洞实例, 无法选择难度。</p>
          ) : (
            <Dropdown
              className="w-60"
              value={difficulty}
              options={options}
              size="sm"
              onChange={(next) => {
                if (isDifficulty(next)) {
                  onDifficultyChange(next)
                }
              }}
            />
          )}
          <Button variant="brand" size="sm" loading={busy} disabled={options.length === 0} onClick={onEnter}>
            进入矿洞
          </Button>
        </div>
        {/*
          等级门只提示不代拒: 服务端的 EntryGateway.requestEnter 才是权威 (F3 记着那三条不一致的进入
          路径), 前端把按钮灰掉等于在客户端复制一份门槛规则, 两边一旦漂移就是"看着能进点了没反应"。
        */}
        {selected !== undefined && myStatus.minerLevel < selected.requiredMinerLevel ? (
          <Surface tone="warning">
            <p className="text-sm text-foreground">
              当前矿工 {String(myStatus.minerLevel)} 级, 该难度要求 {String(selected.requiredMinerLevel)} 级;
              仍可发起请求, 由服务端裁决。
            </p>
          </Surface>
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone="info">{current === undefined ? String(myStatus.difficulty) : current.displayName}</Tag>
        <span className="text-muted-foreground text-sm">
          区块 ({String(myStatus.regionX)}, {String(myStatus.regionZ)})
        </span>
        <span className="text-muted-foreground text-sm">矿工 {String(myStatus.minerLevel)} 级</span>
        {freezeRemaining > 0 ? (
          <Tag tone="success">新手保护 {formatRemaining(freezeRemaining)}</Tag>
        ) : null}
      </div>

      <Meter
        value={myStatus.danger}
        max={1}
        tone={dangerTone(myStatus.danger)}
        size="sm"
        label={`危险度 ${String(Math.round(myStatus.danger * 100))}%`}
      />

      {current === undefined ? null : (
        <p className="text-muted-foreground text-xs">
          同区玩家 {String(current.playersInside)} 人 · 下次自动重置 {formatRemaining(current.nextResetAt - now)}后
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="destructive" size="sm" loading={busy} onClick={onLeave}>
          离开矿洞
        </Button>
      </div>
    </div>
  )
}

// ============================================================
// 分区: 婚姻摘要
// ============================================================

interface MarriageSectionProps {
  marriage: PlannedMarriageStateResult
  /** A5 聚合里那句现成的摘要; 未婚时是空串。 */
  summary: string
  onOpenMarriage: () => void
  now: number
}

function MarriageSection({ marriage, summary, onOpenMarriage, now }: MarriageSectionProps): ReactElement {
  const style = MARRIAGE_STATUS_STYLE[marriage.status]
  const achieved = marriage.milestones.filter((milestone) => milestone.achievedAt !== null).length
  const cooldownRemaining = marriage.remarryCooldownUntil - now

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={style.tone}>{style.label}</Tag>
        {marriage.spouseName === null ? (
          <span className="text-muted-foreground text-sm">尚无配偶</span>
        ) : (
          <>
            <span className="text-sm text-foreground">{marriage.spouseName}</span>
            <Tag size="sm" tone={marriage.spouseOnline ? 'success' : 'neutral'}>
              {marriage.spouseOnline ? '在线' : '离线'}
            </Tag>
            <span className="text-muted-foreground text-sm">相伴 {String(marriage.marriageDays)} 天</span>
          </>
        )}
        {marriage.incomingProposals.length === 0 ? null : (
          <Tag tone="warning">{String(marriage.incomingProposals.length)} 份求婚待答复</Tag>
        )}
      </div>

      {summary === '' ? null : <p className="text-sm text-foreground">{summary}</p>}

      <div className="grid grid-cols-2 gap-x-4 gap-y-1">
        <Stat
          label="共享背包"
          layout="inline"
          value={`Lv.${String(marriage.sharedInvLevel)} · ${String(marriage.sharedInvSlots)} 格`}
        />
        <Stat label="离婚次数" layout="inline" value={`${String(marriage.divorceCount)} 次`} />
        <Stat
          label="里程碑"
          layout="inline"
          value={`${String(achieved)} / ${String(marriage.milestones.length)}`}
        />
        <Stat
          label="婚戒"
          layout="inline"
          value={
            marriage.ringOwned ? '已持有' : `未持有 (${formatInteger(marriage.ringPriceCredit)} 信用点)`
          }
        />
      </div>

      {cooldownRemaining > 0 ? (
        <Surface tone="warning">
          <p className="text-sm text-foreground">再婚冷却剩余 {formatRemaining(cooldownRemaining)}</p>
        </Surface>
      ) : null}

      {/*
        首页只做摘要: 求婚/应答/结婚/离婚全部是有金钱与状态后果的写操作, 它们的确认流程属于婚姻面板。
        这里给一个出口而不是把按钮搬过来 —— 首页放危险操作等于把误触成本抬到最高。
      */}
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          variant={marriage.incomingProposals.length > 0 ? 'brand' : 'outline'}
          onClick={onOpenMarriage}
        >
          进入婚姻面板
        </Button>
      </div>
    </div>
  )
}

// ============================================================
// 分区: 快捷入口
// ============================================================

interface PanelsSectionProps {
  panels: readonly PlannedHubPanel[]
  scope: PanelScope
  onScopeChange: (next: PanelScope) => void
  keyword: string
  onKeywordChange: (next: string) => void
  onRequestKeywordEdit: (current: string) => void
  currentRoute: string
  onOpen: (route: string) => void
}

function PanelsSection({
  panels,
  scope,
  onScopeChange,
  keyword,
  onKeywordChange,
  onRequestKeywordEdit,
  currentRoute,
  onOpen,
}: PanelsSectionProps): ReactElement {
  const visible = panels.filter((panel) => {
    const availability = panelAvailability(panel)
    if (scope === 'open' && !availability.usable) {
      return false
    }
    if (scope === 'locked' && availability.usable) {
      return false
    }
    return keyword === '' || panel.label.includes(keyword)
  })

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Dropdown
          className="w-40"
          value={scope}
          options={PANEL_SCOPE_OPTIONS}
          size="sm"
          onChange={(next) => {
            if (isPanelScope(next)) {
              onScopeChange(next)
            }
          }}
        />
        <TextInput
          className="w-48"
          value={keyword}
          size="sm"
          placeholder="按名称筛选"
          onChange={onKeywordChange}
          onRequestEdit={onRequestKeywordEdit}
        />
        <Button
          size="sm"
          variant="outline"
          disabled={keyword === ''}
          onClick={() => {
            onKeywordChange('')
          }}
        >
          清空
        </Button>
      </div>

      {/*
        原先这里有一整条黄色警告横幅, 写着"中文输入未接线 (清单 A14): 宿主 EditBox 叠加未实现…"。
        撤掉的理由有两条: 玩家读不懂也用不上那串实现细节; 而它占掉一整行, 把本就不宽的磁贴区又压矮一截。
        这个约束真正需要被说出来的时机是玩家点了输入框的那一刻, 故改由 onRequestEdit 的回执承担 (见首页
        requestKeywordEdit), 一句话说完。
      */}
      {visible.length === 0 ? (
        <EmptyBlock
          title="没有符合条件的面板"
          hint="换一个筛选范围, 或清空关键字"
          icon={<FilterIcon aria-hidden="true" />}
        />
      ) : (
        /*
         * 定宽自适应网格而不是 flex-wrap: 后者每个磁贴按自身内容定宽, 于是"婚姻"比"精英怪图鉴"窄一大截,
         * 十个磁贴排下来宽窄参差。auto-fill + minmax 让每列等宽且随容器变化自动换行。
         */
        <div className="grid grid-cols-[repeat(auto-fill,minmax(4.5rem,1fr))] gap-2">
          {visible.map((panel) => (
            <PanelTile
              key={panel.panelId}
              panel={panel}
              current={panel.route === currentRoute}
              onOpen={onOpen}
            />
          ))}
        </div>
      )}
    </div>
  )
}

interface PanelTileProps {
  panel: PlannedHubPanel
  current: boolean
  onOpen: (route: string) => void
}

/**
 * 磁贴刻意不复用 Button。
 *
 * Button 的基类带着 whitespace-nowrap 与固定行高 (它是为"一行字的控件"设计的), 而磁贴是
 * "图标在上、名字在下、名字可能折行"的两行结构 —— 硬套的结果就是长名字 (精英怪图鉴) 横向溢出到
 * 边框外面, 且首个磁贴的名字被整行裁掉。用 Button 再逐条覆盖那些基类样式, 只会得到一个
 * "看起来是按钮实际处处例外"的东西。
 */
function PanelTile({ panel, current, onOpen }: PanelTileProps): ReactElement {
  const availability = panelAvailability(panel)
  const disabled = !availability.usable || current

  const stateClass = current
    ? 'border-brand bg-brand-muted text-foreground'
    : availability.usable
      ? 'border-border bg-card text-foreground hover:border-ring hover:bg-accent'
      : 'border-border bg-muted/40 text-muted-foreground opacity-64'

  const tile = (
    <button
      aria-current={current ? 'page' : undefined}
      className={`flex w-full flex-col items-center gap-1.5 rounded-lg border px-1.5 py-2.5 transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${stateClass}`}
      disabled={disabled}
      onClick={() => {
        onOpen(panel.route)
      }}
      type="button"
    >
      <span className="relative">
        <ItemIcon itemId={panel.iconItemId} label={panel.label} scale={2} />
        {availability.usable ? null : (
          // 锁标记压在图标右下角而不是排在名字前面: 排进名字会把本就要折行的两行挤成三行,
          // 且十个磁贴里只有两个带锁, 名字起始位置会参差不齐。
          <LockIcon
            aria-hidden="true"
            className="absolute -right-1 -bottom-1 size-3.5 rounded-full bg-card p-px text-muted-foreground"
          />
        )}
      </span>
      {/* 名字允许折到两行: 面板名是中文, 四字与五字并存, 强行一行必然溢出或被裁。 */}
      <span className="line-clamp-2 text-center text-xs leading-tight">{panel.label}</span>
    </button>
  )

  if (availability.reason === null) {
    return current ? <Hint content="你正在这个面板上">{tile}</Hint> : tile
  }

  /*
   * 禁用按钮本身不响应 hover/focus, 所以锁定原因必须挂在包在外面的 Hint 上 ——
   * 它把触发元素包进一条独立的 span, 悬停区域不受按钮禁用态影响 (九-1/九-9: 原生 title 属性被禁)。
   */
  return <Hint content={availability.reason}>{tile}</Hint>
}

// ============================================================
// 错误通道自检 (仅假数据模式)
// ============================================================

interface BridgeErrorProbeProps {
  onUnexpectedSuccess: () => void
}

/**
 * 用一次必然失败的真实往返, 把错误态画出来。
 *
 * 存在的理由不是"演示": MCEF 里的失败通道 (WebUiCallError 的 code -1/-2/-3/-100) 与浏览器里不一样,
 * 而首页的六块内容各自都有失败分支 —— 那些分支若从没在真客户端被看见过一次, 上线才发现错误卡片
 * 撑破布局/文案溢出就太晚了。这里打的是 player.itemDetail 的空槽位 (A8), 服务端必然拒绝, 拒绝路径
 * 与真实业务失败完全同一条。
 *
 * 只在假数据模式下渲染: isMockActive() 在生产构建里恒为 false, 装进游戏后这一块不存在。
 */
function BridgeErrorProbe({ onUnexpectedSuccess }: BridgeErrorProbeProps): ReactElement {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const code = error === null ? null : errorCodeOf(error)

  const run = useCallback((): void => {
    setBusy(true)
    setError(null)
    void callMock('player.itemDetail', { slot: PROBE_EMPTY_SLOT })
      .then(() => {
        // 空槽位竟然回了物品: 那说明 mock 的槽位语义变了, 自检本身失效, 必须喊出来而不是静默通过。
        onUnexpectedSuccess()
      })
      .catch((thrown: unknown) => {
        setError(toError(thrown))
      })
      .finally(() => {
        setBusy(false)
      })
  }, [onUnexpectedSuccess])

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground text-sm">
          错误通道自检 (仅假数据模式): 向 player.itemDetail 请求一个不存在的槽位, 走完整失败链路。
        </span>
        <Button size="sm" variant="destructive-outline" loading={busy} onClick={run}>
          触发一次真实失败
        </Button>
        {error === null ? null : (
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              setError(null)
            }}
          >
            收起
          </Button>
        )}
      </div>
      {error === null ? null : (
        <ErrorBlock message={error.message} onRetry={run} {...(code === null ? {} : { code })} />
      )}
    </div>
  )
}

// ============================================================
// 页面
// ============================================================

interface ToastEntry {
  readonly id: number
  readonly tone: FeedbackTone
  readonly message: string
}

export function HomePage(): ReactElement {
  const navigate = useNavigate()
  const world = useMockWorld()
  const now = useNowTick(TICK_INTERVAL_MS)

  const profile = useMockAction('player.profile', EMPTY_PAYLOAD)
  const today = useMockAction('economy.today', EMPTY_PAYLOAD)
  const economyStatus = useMockAction('economy.status', EMPTY_PAYLOAD)
  const marriage = useMockAction('marriage.state', EMPTY_PAYLOAD)
  const miningStatus = useMockAction('mining.myStatus', EMPTY_PAYLOAD)
  const miningOverview = useMockAction('mining.overview', EMPTY_PAYLOAD)
  const hubPanels = useMockAction('hub.panels', EMPTY_PAYLOAD)

  const [difficulty, setDifficulty] = useState<PlannedDifficulty>('easy')
  const [miningBusy, setMiningBusy] = useState(false)
  const [panelScope, setPanelScope] = useState<PanelScope>('all')
  const [panelKeyword, setPanelKeyword] = useState('')
  const [toasts, setToasts] = useState<readonly ToastEntry[]>([])
  const toastSeq = useRef(0)

  const pushToast = useCallback((tone: FeedbackTone, message: string): void => {
    toastSeq.current += 1
    const entry: ToastEntry = { id: toastSeq.current, tone, message }
    setToasts((previous) => [...previous, entry])
  }, [])

  const dismissToast = useCallback((id: number): void => {
    setToasts((previous) => previous.filter((entry) => entry.id !== id))
  }, [])

  const reloadProfile = profile.reload
  const reloadToday = today.reload
  const reloadEconomyStatus = economyStatus.reload
  const reloadMarriage = marriage.reload
  const reloadMiningStatus = miningStatus.reload
  const reloadMiningOverview = miningOverview.reload
  const reloadHubPanels = hubPanels.reload

  const reloadAll = useCallback((): void => {
    reloadProfile()
    reloadToday()
    reloadEconomyStatus()
    reloadMarriage()
    reloadMiningStatus()
    reloadMiningOverview()
    reloadHubPanels()
  }, [
    reloadProfile,
    reloadToday,
    reloadEconomyStatus,
    reloadMarriage,
    reloadMiningStatus,
    reloadMiningOverview,
    reloadHubPanels,
  ])

  /*
   * useMockAction 刻意不订阅世界版本 (会改世界的 action 一旦自动重查就自锁, 见 useMockWorld.ts),
   * 但首页恰恰是最需要跨面板联动的一屏: 别处卖菜进账、外壳切 OP 视图 (hub.panels 的 admin 锁态跟着变)、
   * 本页进出矿洞, 都会改世界。故在页面这一层显式补一条"世界变了就重查", 与 TabletShell 同一手法。
   * 首次挂载不触发 —— ref 初值即当前版本, 否则刚发出的首查会立刻被一次重查顶掉。
   */
  const lastRevisionRef = useRef(world.revision)
  useEffect(() => {
    if (lastRevisionRef.current === world.revision) {
      return
    }
    lastRevisionRef.current = world.revision
    reloadAll()
  }, [world.revision, reloadAll])

  /*
   * 进入/离开矿洞。两条都是写操作, 成功后由 mutateWorld 冒出的 revision 变化触发上面那条重查,
   * 因此这里不再手动 reload —— 手动再刷一次等于同一份数据发两轮请求。
   * 被拒 (等级门/已在内) 时服务端回的是 entered:false + message, 那不是异常, 走 danger 提示而不是抛。
   */
  const enterMining = useCallback((): void => {
    setMiningBusy(true)
    void callMock('mining.enter', { difficulty })
      .then((result) => {
        pushToast(result.entered ? 'success' : 'danger', result.message)
      })
      .catch((thrown: unknown) => {
        pushToast('danger', toError(thrown).message)
      })
      .finally(() => {
        setMiningBusy(false)
      })
  }, [difficulty, pushToast])

  const leaveMining = useCallback((): void => {
    setMiningBusy(true)
    void callMock('mining.leave', EMPTY_PAYLOAD)
      .then((result) => {
        pushToast(result.left ? 'success' : 'warning', result.message)
      })
      .catch((thrown: unknown) => {
        pushToast('danger', toError(thrown).message)
      })
      .finally(() => {
        setMiningBusy(false)
      })
  }, [pushToast])

  const openJob = useCallback(
    (jobId: string): void => {
      navigate(buildJobDetailPath(jobId))
    },
    [navigate],
  )

  const requestKeywordEdit = useCallback(
    (current: string): void => {
      // 玩家只需要知道两件事: 现在打不了中文, 以及有什么替代办法。为什么打不了是实现细节, 不上界面。
      // current 参数保留但不进文案 —— 宿主输入层接通后要拿它做初值回填。
      void current
      pushToast('info', '中文输入暂未开放, 可先用左侧的范围筛选')
    },
    [pushToast],
  )

  const probeUnexpectedSuccess = useCallback((): void => {
    pushToast('warning', `自检失效: 槽位 ${String(PROBE_EMPTY_SLOT)} 竟然回了物品, 错误通道没有被触发。`)
  }, [pushToast])

  const anyLoading =
    profile.status === 'loading' ||
    today.status === 'loading' ||
    economyStatus.status === 'loading' ||
    marriage.status === 'loading' ||
    miningStatus.status === 'loading' ||
    miningOverview.status === 'loading' ||
    hubPanels.status === 'loading'

  /** 真域镜像的刷新时刻由 mock 层写入 (0 = 本会话还没拉过), 是判断"页面上的钱是什么时候的钱"的唯一线索。 */
  const mirrorLabel = useMemo(
    () =>
      world.mirror.refreshedAt === 0
        ? '真域数据尚未拉取'
        : `真域数据刷新于 ${formatClock(world.mirror.refreshedAt)}`,
    [world.mirror.refreshedAt],
  )

  return (
    <section className="flex flex-col gap-4">
      {toasts.length === 0 ? null : (
        <div className="flex flex-col gap-2">
          {/*
            entry.id 当 key: 每条回执因此各自挂载一次, FeedbackAlert 的 4 秒倒计时也就各算各的。
            用下标当 key 的话, 关掉第一条会让后面每条都"继承"前一条的计时器状态。
          */}
          {toasts.map((entry) => (
            <FeedbackAlert
              key={entry.id}
              message={entry.message}
              onDismiss={() => {
                dismissToast(entry.id)
              }}
              tone={entry.tone}
            />
          ))}
        </div>
      )}

      <div className="grid grid-cols-3 gap-4">
        <Panel
          title="个人档案"
          actions={
            <Button size="sm" variant="outline" onClick={reloadAll}>
              <RefreshCwIcon />
              全部重载
            </Button>
          }
        >
          <div className="flex flex-1 flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              {profile.status === 'ready' ? (
                <>
                  <span className="font-medium text-base text-foreground">{profile.data.playerName}</span>
                  {profile.data.isOp ? <Tag tone="brand">OP</Tag> : null}
                </>
              ) : (
                <span className="text-base text-muted-foreground">档案读取中</span>
              )}
            </div>

            {economyStatus.status === 'ready' ? (
              <Surface tone={economyStatus.data.afkFrozen ? 'danger' : 'success'}>
                <div className="flex flex-col gap-1">
                  <Tag size="sm" tone={economyStatus.data.afkFrozen ? 'danger' : 'success'}>
                    {economyStatus.data.afkFrozen ? '挂机冻结中' : '活跃'}
                  </Tag>
                  <span className="text-muted-foreground text-xs">
                    {economyStatus.data.afkFrozen
                      ? `静止 ${String(economyStatus.data.idleSeconds)} 秒, 期间收入不入账`
                      : `静止 ${String(economyStatus.data.idleSeconds)} / ${String(economyStatus.data.freezeThresholdSeconds)} 秒`}
                  </span>
                </div>
              </Surface>
            ) : null}

            <div className="mt-auto flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">{mirrorLabel}</span>
              {anyLoading ? <LoadingBlock label="同步中" size="sm" /> : null}
            </div>
          </div>
        </Panel>

        <Panel className="col-span-2" title="今日收支与额度">
          <QueryGate query={profile} loadingLabel="读取个人档案">
            {(profileData) => (
              <QueryGate query={today} loadingLabel="读取今日收支">
                {(todayData) => <WalletSection profile={profileData} today={todayData} now={now} />}
              </QueryGate>
            )}
          </QueryGate>
        </Panel>
      </div>

      <Panel title="八职业进度 (被动恒生效, 无需转职)">
        <QueryGate query={profile} loadingLabel="读取职业进度">
          {(profileData) => <JobsSection jobs={profileData.jobs} onOpen={openJob} />}
        </QueryGate>
      </Panel>

      <div className="grid grid-cols-2 gap-4">
        <Panel
          title="当前所在矿洞"
          actions={
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                navigate(ROUTE_MINING)
              }}
            >
              矿洞面板
            </Button>
          }
        >
          <QueryGate query={miningStatus} loadingLabel="读取矿洞状态">
            {(statusData) => (
              <QueryGate query={miningOverview} loadingLabel="读取矿洞实例">
                {(overviewData) => (
                  <MiningSection
                    myStatus={statusData}
                    instances={overviewData.instances}
                    difficulty={difficulty}
                    onDifficultyChange={setDifficulty}
                    onEnter={enterMining}
                    onLeave={leaveMining}
                    busy={miningBusy}
                    now={now}
                  />
                )}
              </QueryGate>
            )}
          </QueryGate>
        </Panel>

        <Panel title="婚姻状态">
          <QueryGate query={marriage} loadingLabel="读取婚姻状态">
            {(marriageData) => (
              <MarriageSection
                marriage={marriageData}
                summary={profile.status === 'ready' ? profile.data.marriageSummary : ''}
                now={now}
                onOpenMarriage={() => {
                  navigate(ROUTE_MARRIAGE)
                }}
              />
            )}
          </QueryGate>
        </Panel>
      </div>

      <Panel title="快捷入口">
        <QueryGate query={hubPanels} loadingLabel="读取快捷入口">
          {(panelsData) => (
            <PanelsSection
              panels={panelsData.panels}
              scope={panelScope}
              onScopeChange={setPanelScope}
              keyword={panelKeyword}
              onKeywordChange={setPanelKeyword}
              onRequestKeywordEdit={requestKeywordEdit}
              currentRoute={ROUTE_HOME}
              onOpen={(route) => {
                navigate(route)
              }}
            />
          )}
        </QueryGate>
      </Panel>

      {isMockActive() ? (
        <Panel title="接线自检">
          <BridgeErrorProbe onUnexpectedSuccess={probeUnexpectedSuccess} />
        </Panel>
      ) : null}
    </section>
  )
}
