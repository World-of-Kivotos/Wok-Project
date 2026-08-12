import type { ReactElement, ReactNode } from 'react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PixelFrameTone, PixelSelectOption } from '../components/pixel'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelIcon,
  PixelInput,
  PixelLoading,
  PixelProgress,
  PixelSelect,
  PixelToast,
  PixelTooltip,
} from '../components/pixel'
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

/** 整数千分位。经验值与人数不是货币, 不能借 PixelCurrency 渲染 (那会给它挂上一个币种图标)。 */
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
function decayTone(decayFactor: number): PixelFrameTone {
  if (decayFactor >= 1) {
    return 'success'
  }
  if (decayFactor >= 0.5) {
    return 'warning'
  }
  return 'danger'
}

/** 矿洞 danger 是 0..1 的实时值 (F6)。三档只决定颜色, 不参与任何业务判定。 */
function dangerTone(danger: number): PixelFrameTone {
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
  readonly tone: PixelFrameTone
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

const PANEL_SCOPE_OPTIONS: readonly PixelSelectOption[] = [
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
  if (matchRoute(panel.route).pattern === null) {
    return { usable: false, reason: `路由 ${panel.route} 未在前端路由表登记 (面板注册表与 router.ts 脱节)` }
  }
  if (!panel.enabled) {
    if (panel.lockReason === null) {
      return { usable: false, reason: '注册表把该面板标为不可用, 却没有给出原因 (数据缺陷)' }
    }
    return { usable: false, reason: panel.lockReason }
  }
  return { usable: true, reason: null }
}

// ============================================================
// 页面内的通用小件 (都是既有像素控件的组合, 不是新控件)
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
    return <PixelLoading label={loadingLabel} size="sm" />
  }
  if (query.status === 'error') {
    const code = errorCodeOf(query.error)
    return (
      <PixelError
        message={query.error.message}
        onRetry={query.reload}
        {...(code === null ? {} : { code })}
      />
    )
  }
  return <>{children(query.data)}</>
}

interface SectionCardProps {
  title: string
  /** 标题右侧的操作位 (刷新按钮、筛选器)。 */
  actions?: ReactNode
  children: ReactNode
}

/** 分区卡片。就是 PixelFrame panel + 一条标题行, 页面里出现六次, 抽出来只为不把同一段 JSX 抄六遍。 */
function SectionCard({ title, actions, children }: SectionCardProps): ReactElement {
  return (
    <PixelFrame variant="panel" className="flex flex-col gap-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-border pb-2">
        <h2 className="text-2x text-fg">{title}</h2>
        {actions === undefined ? null : <div className="flex flex-wrap items-center gap-4">{actions}</div>}
      </div>
      {children}
    </PixelFrame>
  )
}

interface CapBarProps {
  label: string
  value: number
  /** 上限。非正数一律不画条 —— PixelProgress 明确不为 max<=0 兜底, 画出来是 NaN 宽度的空槽。 */
  max: number
  tone: PixelFrameTone
}

/** 带上限的进度条 + 缺上限时的诚实回退。 */
function CapBar({ label, value, max, tone }: CapBarProps): ReactElement {
  if (max <= 0) {
    return (
      <p className="text-1x text-muted">
        {label}: {formatInteger(value)} (未给出上限, 无法画进度)
      </p>
    )
  }
  return <PixelProgress value={value} max={max} tone={tone} size="sm" label={label} />
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
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start gap-8">
        <div className="flex flex-col gap-1">
          <span className="text-1x text-muted">信用点余额</span>
          <PixelCurrency amount={profile.wallet.credit} currency="credit" size="lg" />
          <span className="text-1x text-muted">今日入账 {formatInteger(profile.todayCreditIn)}</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-1x text-muted">青辉石余额</span>
          <PixelCurrency amount={profile.wallet.azure} currency="azure" size="lg" />
          <span className="text-1x text-muted">今日入账 {formatInteger(profile.todayAzureIn)}</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-1x text-muted">今日净流入 (进 - 出)</span>
          <PixelCurrency amount={netCredit} currency="credit" size="lg" />
          <span className="text-1x text-muted">
            距翻日 {formatRemaining(today.resetsAt - now)}
          </span>
        </div>
      </div>

      {/*
        青辉石与信用点的上限机制不是一回事, 必须分开画: 青辉石是硬截断 (D4, 撞顶即一分不发),
        信用点各 faucet 是软上限后按 decayFactor 打折继续发 (D3)。用同一种条画两种规则, 玩家会
        以为撞了软上限就没得赚了。
      */}
      <CapBar
        label={`青辉石今日 ${formatInteger(today.azureIn)} / ${formatInteger(today.azureDailyCap)} (硬上限, 撞顶即停发)`}
        value={today.azureIn}
        max={today.azureDailyCap}
        tone={today.azureIn >= today.azureDailyCap ? 'danger' : 'info'}
      />

      <div className="flex flex-col gap-3">
        <h3 className="text-1x text-fg">信用点 faucet 当日额度</h3>
        {today.faucets.length === 0 ? (
          <PixelEmpty title="今日没有任何 faucet 记录" hint="打一次矿或卖一次菜后这里会出现进度" icon="info" />
        ) : (
          today.faucets.map((faucet) => (
            <div key={faucet.faucetKey} className="flex flex-col gap-1">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <span className="text-1x text-fg">{faucet.label}</span>
                <div className="flex items-center gap-4">
                  <PixelCurrency amount={faucet.earnedToday} currency="credit" size="sm" showIcon={false} />
                  <span className="text-1x text-muted">/ {formatInteger(faucet.softCap)}</span>
                  <PixelBadge tone={decayTone(faucet.decayFactor)}>
                    {faucet.decayFactor >= 1 ? '满额入账' : `衰减 x${String(faucet.decayFactor)}`}
                  </PixelBadge>
                </div>
              </div>
              <CapBar
                label={`${faucet.label} 软上限进度`}
                value={faucet.earnedToday}
                max={faucet.softCap}
                tone={decayTone(faucet.decayFactor)}
              />
            </div>
          ))
        )}
      </div>

      <div className="flex flex-col gap-2">
        <h3 className="text-1x text-fg">今日支出 (sink)</h3>
        {today.sinks.length === 0 ? (
          <p className="text-1x text-muted">今日还没有任何支出记录。</p>
        ) : (
          <div className="flex flex-wrap gap-6">
            {today.sinks.map((sink) => (
              <div key={sink.sinkKey} className="flex items-center gap-2">
                <span className="text-1x text-muted">{sink.label}</span>
                <PixelCurrency amount={sink.spentToday} currency="credit" size="sm" showIcon={false} />
              </div>
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
    return <PixelEmpty title="没有拿到任何职业进度" hint="player.profile 回执里的 jobs 是空数组" icon="warning" />
  }
  return (
    <div className="grid grid-cols-2 gap-4">
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
    <PixelFrame variant="inset" className="flex flex-col gap-2 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-1x text-fg">{job.displayName}</span>
          <PixelBadge tone={maxed ? 'success' : 'neutral'}>Lv.{String(job.level)}</PixelBadge>
          {maxed ? <PixelBadge tone="success">满级</PixelBadge> : null}
          {job.dailyRemaining === 0 ? <PixelBadge tone="warning">今日额度已满</PixelBadge> : null}
        </div>
        <PixelButton
          size="sm"
          onClick={() => {
            onOpen(job.jobId)
          }}
        >
          详情
        </PixelButton>
      </div>

      {maxed ? (
        // 满级没有"下一级所需", 拿 0 当分母画出来的是一条 NaN 宽度的空槽; 直接换成一句结论。
        <p className="text-1x text-success">已满级 · 累计经验 {formatInteger(job.totalXp)}</p>
      ) : (
        <PixelProgress
          value={job.levelXp}
          max={job.nextLevelXp}
          tone="accent"
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
    </PixelFrame>
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
  const options: readonly PixelSelectOption[] = instances.map((instance) => ({
    value: instance.difficulty,
    label: `${instance.displayName} (需矿工 ${String(instance.requiredMinerLevel)} 级)`,
  }))
  const selected = instances.find((instance) => instance.difficulty === difficulty)
  const current = instances.find((instance) => instance.difficulty === myStatus.difficulty)
  const freezeRemaining = myStatus.spawnFreezeUntil - now

  if (!myStatus.inside) {
    return (
      <div className="flex flex-col gap-4">
        <PixelEmpty
          title="当前不在任何矿洞内"
          hint="全服每个难度只有一个常驻实例 (R1 模型), 进入即与其他玩家共处同一份地形"
          icon="info"
        />
        <div className="flex flex-wrap items-center gap-4">
          {options.length === 0 ? (
            <p className="text-1x text-muted">没有拿到任何矿洞实例, 无法选择难度。</p>
          ) : (
            <PixelSelect
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
          <PixelButton tone="accent" size="sm" loading={busy} disabled={options.length === 0} onClick={onEnter}>
            进入矿洞
          </PixelButton>
        </div>
        {/*
          等级门只提示不代拒: 服务端的 EntryGateway.requestEnter 才是权威 (F3 记着那三条不一致的进入
          路径), 前端把按钮灰掉等于在客户端复制一份门槛规则, 两边一旦漂移就是"看着能进点了没反应"。
        */}
        {selected !== undefined && myStatus.minerLevel < selected.requiredMinerLevel ? (
          <p className="text-1x text-warning">
            当前矿工 {String(myStatus.minerLevel)} 级, 该难度要求 {String(selected.requiredMinerLevel)} 级;
            仍可发起请求, 由服务端裁决。
          </p>
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-4">
        <PixelBadge tone="info">
          {current === undefined ? String(myStatus.difficulty) : current.displayName}
        </PixelBadge>
        <span className="text-1x text-muted">
          区块 ({String(myStatus.regionX)}, {String(myStatus.regionZ)})
        </span>
        <span className="text-1x text-muted">矿工 {String(myStatus.minerLevel)} 级</span>
        {freezeRemaining > 0 ? (
          <PixelBadge tone="success">新手保护 {formatRemaining(freezeRemaining)}</PixelBadge>
        ) : null}
      </div>

      <PixelProgress
        value={myStatus.danger}
        max={1}
        tone={dangerTone(myStatus.danger)}
        size="sm"
        label={`危险度 ${String(Math.round(myStatus.danger * 100))}%`}
      />

      {current === undefined ? null : (
        <p className="text-1x text-muted">
          同区玩家 {String(current.playersInside)} 人 · 下次自动重置 {formatRemaining(current.nextResetAt - now)}后
        </p>
      )}

      <div className="flex flex-wrap items-center gap-4">
        <PixelButton tone="danger" size="sm" loading={busy} onClick={onLeave}>
          离开矿洞
        </PixelButton>
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
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-4">
        <PixelBadge tone={style.tone}>{style.label}</PixelBadge>
        {marriage.spouseName === null ? (
          <span className="text-1x text-muted">尚无配偶</span>
        ) : (
          <>
            <span className="text-1x text-fg">{marriage.spouseName}</span>
            <PixelBadge tone={marriage.spouseOnline ? 'success' : 'neutral'}>
              {marriage.spouseOnline ? '在线' : '离线'}
            </PixelBadge>
            <span className="text-1x text-muted">相伴 {String(marriage.marriageDays)} 天</span>
          </>
        )}
        {marriage.incomingProposals.length === 0 ? null : (
          <PixelBadge tone="warning">
            {String(marriage.incomingProposals.length)} 份求婚待答复
          </PixelBadge>
        )}
      </div>

      {summary === '' ? null : <p className="text-1x text-fg">{summary}</p>}

      <div className="flex flex-wrap gap-6">
        <span className="text-1x text-muted">
          共享背包 Lv.{String(marriage.sharedInvLevel)} · {String(marriage.sharedInvSlots)} 格
        </span>
        <span className="text-1x text-muted">离婚 {String(marriage.divorceCount)} 次</span>
        <span className="text-1x text-muted">
          里程碑 {String(achieved)} / {String(marriage.milestones.length)}
        </span>
        <span className="text-1x text-muted">
          婚戒 {marriage.ringOwned ? '已持有' : `未持有 (${formatInteger(marriage.ringPriceCredit)} 信用点)`}
        </span>
      </div>

      {cooldownRemaining > 0 ? (
        <p className="text-1x text-warning">再婚冷却剩余 {formatRemaining(cooldownRemaining)}</p>
      ) : null}

      {/*
        首页只做摘要: 求婚/应答/结婚/离婚全部是有金钱与状态后果的写操作, 它们的确认流程属于婚姻面板。
        这里给一个出口而不是把按钮搬过来 —— 首页放危险操作等于把误触成本抬到最高。
      */}
      <div className="flex flex-wrap items-center gap-4">
        <PixelButton size="sm" tone={marriage.incomingProposals.length > 0 ? 'accent' : 'neutral'} onClick={onOpenMarriage}>
          进入婚姻面板
        </PixelButton>
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
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-4">
        <PixelSelect
          value={scope}
          options={PANEL_SCOPE_OPTIONS}
          size="sm"
          onChange={(next) => {
            if (isPanelScope(next)) {
              onScopeChange(next)
            }
          }}
        />
        <PixelInput
          value={keyword}
          size="sm"
          placeholder="按名称筛选"
          onChange={onKeywordChange}
          onRequestEdit={onRequestKeywordEdit}
        />
        <PixelButton
          size="sm"
          disabled={keyword === ''}
          onClick={() => {
            onKeywordChange('')
          }}
        >
          清空
        </PixelButton>
      </div>

      {/*
        A14 提示位。面板名全是中文, 这个框因此只能走宿主 EditBox 叠加 (架构文档第七章的中文输入路线),
        而那层叠加尚未实现 —— 点击只会向宿主报一次"请开输入框", 回填通道接通前它拿不到任何值。
        照实说明, 不做成一个看起来能打字的框。
      */}
      <p className="text-1x text-warning">
        中文输入未接线 (清单 A14): 宿主 EditBox 叠加未实现, 点击输入框只会向宿主发起一次请求, 回填后本框才会生效。
      </p>

      {visible.length === 0 ? (
        <PixelEmpty title="没有符合条件的面板" hint="换一个筛选范围, 或清空关键字" icon="filter" />
      ) : (
        <div className="flex flex-wrap gap-4">
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

function PanelTile({ panel, current, onOpen }: PanelTileProps): ReactElement {
  const availability = panelAvailability(panel)

  const tile = (
    <PixelButton
      tone={current ? 'accent' : 'neutral'}
      size="sm"
      disabled={!availability.usable || current}
      onClick={() => {
        onOpen(panel.route)
      }}
    >
      <span className="flex flex-col items-center gap-2">
        <ItemIcon itemId={panel.iconItemId} label={panel.label} scale={2} />
        <span className="flex items-center gap-1 text-1x">
          {availability.usable ? null : <PixelIcon name="lock" scale={1} />}
          {panel.label}
        </span>
      </span>
    </PixelButton>
  )

  if (availability.reason === null) {
    return current ? (
      <PixelTooltip content="你正在这个面板上" placement="top">
        {tile}
      </PixelTooltip>
    ) : (
      tile
    )
  }

  /*
   * 禁用按钮本身不响应 hover/focus, 所以锁定原因必须挂在包在外面的 PixelTooltip 上 ——
   * 它自带一个可聚焦的 span, 键盘用户也能读到原因 (九-1/九-9: 原生 title 属性被禁)。
   */
  return (
    <PixelTooltip content={availability.reason} placement="top">
      {tile}
    </PixelTooltip>
  )
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
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-4">
        <span className="text-1x text-muted">
          错误通道自检 (仅假数据模式): 向 player.itemDetail 请求一个不存在的槽位, 走完整失败链路。
        </span>
        <PixelButton size="sm" tone="warning" loading={busy} onClick={run}>
          触发一次真实失败
        </PixelButton>
        {error === null ? null : (
          <PixelButton
            size="sm"
            onClick={() => {
              setError(null)
            }}
          >
            收起
          </PixelButton>
        )}
      </div>
      {error === null ? null : (
        <PixelError message={error.message} onRetry={run} {...(code === null ? {} : { code })} />
      )}
    </div>
  )
}

// ============================================================
// 页面
// ============================================================

interface ToastEntry {
  readonly id: number
  readonly tone: PixelFrameTone
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

  const pushToast = useCallback((tone: PixelFrameTone, message: string): void => {
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
      pushToast(
        'info',
        current === ''
          ? '已向宿主请求中文输入框; A14 未接线, 暂时不会有回填。'
          : `已向宿主请求中文输入框 (当前值 ${current}); A14 未接线, 暂时不会有回填。`,
      )
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
    <section className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-4">
          {profile.status === 'ready' ? (
            <>
              <span className="text-2x text-fg">{profile.data.playerName}</span>
              {profile.data.isOp ? <PixelBadge tone="accent">OP</PixelBadge> : null}
            </>
          ) : (
            <span className="text-2x text-muted">档案读取中</span>
          )}
          {economyStatus.status === 'ready' ? (
            <PixelBadge tone={economyStatus.data.afkFrozen ? 'danger' : 'success'}>
              {economyStatus.data.afkFrozen
                ? `挂机冻结中 (静止 ${String(economyStatus.data.idleSeconds)} 秒, faucet 不入账)`
                : `活跃 (静止 ${String(economyStatus.data.idleSeconds)} / ${String(economyStatus.data.freezeThresholdSeconds)} 秒)`}
            </PixelBadge>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-4">
          <span className="text-1x text-muted">{mirrorLabel}</span>
          {anyLoading ? <PixelLoading size="sm" label="同步中" /> : null}
          <PixelButton icon="refresh" size="sm" onClick={reloadAll}>
            全部重载
          </PixelButton>
        </div>
      </header>

      {toasts.length === 0 ? null : (
        <div className="flex flex-wrap gap-4">
          {toasts.map((entry) => (
            <PixelToast
              key={entry.id}
              tone={entry.tone}
              message={entry.message}
              onDismiss={() => {
                dismissToast(entry.id)
              }}
            />
          ))}
        </div>
      )}

      <SectionCard title="今日收支与额度">
        <QueryGate query={profile} loadingLabel="读取个人档案">
          {(profileData) => (
            <QueryGate query={today} loadingLabel="读取今日收支">
              {(todayData) => <WalletSection profile={profileData} today={todayData} now={now} />}
            </QueryGate>
          )}
        </QueryGate>
      </SectionCard>

      <SectionCard title="八职业进度 (被动恒生效, 无需转职)">
        <QueryGate query={profile} loadingLabel="读取职业进度">
          {(profileData) => <JobsSection jobs={profileData.jobs} onOpen={openJob} />}
        </QueryGate>
      </SectionCard>

      <div className="grid grid-cols-2 gap-6">
        <SectionCard
          title="当前所在矿洞"
          actions={
            <PixelButton
              size="sm"
              onClick={() => {
                navigate(ROUTE_MINING)
              }}
            >
              矿洞面板
            </PixelButton>
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
        </SectionCard>

        <SectionCard title="婚姻状态">
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
        </SectionCard>
      </div>

      <SectionCard title="快捷入口">
        <QueryGate query={hubPanels} loadingLabel="读取面板注册表">
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
      </SectionCard>

      {isMockActive() ? (
        <SectionCard title="接线自检">
          <BridgeErrorProbe onUnexpectedSuccess={probeUnexpectedSuccess} />
        </SectionCard>
      ) : null}
    </section>
  )
}
