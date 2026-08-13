import { ArrowUpIcon, RefreshCwIcon, SearchIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useRef, useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  Currency,
  DataTable,
  type DataTableColumn,
  Dropdown,
  type DropdownOption,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  type FeedbackTone,
  formatAmount,
  ItemIcon,
  LoadingBlock,
  Meter,
  NumberInput,
  Panel,
  Stat,
  Surface,
  TabBar,
  type TabItem,
  Tag,
  TextInput,
  type Tone,
} from '@/components/kit'
import { WebUiCallError } from '../../lib/bridge'
import { useItemNames } from '../../lib/i18n'
import type { AdminItemEntry, BaseValueSource } from '../../lib/types'
import type {
  MockOtherPlayer,
  PlannedCurrency,
  PlannedJobId,
  PlannedMiningInstance,
} from '../../mock'
import { callMock, useMockAction, useMockWorld } from '../../mock'

/**
 * 管理后台 (OP)。五块: 经济调账 / 基准价 / 职业调级 / 副本重置 / 服务器状态。
 *
 * === 权限边界 ===
 * 页签的显隐由平板外壳按 isOp 决定, 本页不重复做一次门控; 服务端每个 admin.* action 内部仍会各自校验
 * (MarketAdminActions.requireOp)。前端这层只是"不给非 OP 看见入口", 不是权限本身 —— 因此非 OP 状态下
 * 本页照常渲染, 只在顶部标出"所有提交都会被服务端拒绝", 让失败可预期而不是变成一串看不懂的异常。
 *
 * === 契约依赖 ===
 * 真契约 (已接线, 直接就是最终形状):
 *   admin.listItems / admin.setBaseValue    接线清单 I1 (READY x2, 已接线)
 *   system.serverStatus                     A4 (MinecraftServer 公开 API 的包装; 无 announcement 字段,
 *                                           全库零"公告"业务概念, 恒回空串等于立一个永远为空的死约定)
 * planned 假定契约 (后端尚无, 走 mock/planned.ts; 接线时按此表逐条核销):
 *   admin.economy.balance / admin.economy.set   I2 (WRAP x2; /economy set 已有 ledgerOf+balance 范式)
 *   admin.job.setLevel                          I3 (WRAP; 权限校验/setLevel/改级后 syncTo 全就绪)
 *   admin.mining.reset                          I4 (WRAP; 活跃版 /mining reset 无二次确认, 弹窗必须前端加)
 *   mining.overview                             F1 (只读, 用来给三个重置目标提供当前人数与倒计时)
 * 另有两条已知缺口在本页直接可见, 不做任何遮掩:
 *   I2 无流水表 (D7): 历史调账查不到, 故 admin.economy.set 的回执带 before, 至少让操作者当场看见改前改后;
 *   A14 中文输入 BLOCKED: 玩家名输入框只能走 onRequestEdit 向宿主喊话, 见下方 PlayerPicker 注释。
 *
 * === 权限之外的一条硬约束 ===
 * 本页四处破坏性写操作 (改余额 / 改基准价 / 改职业等级 / 重置副本) 一律走 ConfirmDangerDialog, 这是
 * **前端责任**: 服务端那四条 action 全部一到就执行, 没有任何一条会再问第二遍 (I4 那套带确认的
 * /mining reset 躺在 com.miningdim.command 的死代码里)。接线之后服务端仍然不会拦, 所以这几道确认
 * 永远不能"为了顺手"去掉。改余额那条还额外要求逐字敲对**新余额数字** —— 它直接改经济数据且无流水可追。
 * (二道锁刻意不用玩家名: 玩家名可能含中文, 而 A14 中文输入 BLOCKED, 那样会把这个操作彻底封死。)
 */

type AdminTabId = 'economy' | 'baseValue' | 'job' | 'mining' | 'server'

/** 把 TabItem 的 id 收窄到本页的五个字面量, 于是切页签时 find 回来的 id 直接就是 AdminTabId, 不必断言。 */
interface AdminTab extends TabItem {
  id: AdminTabId
}

const ADMIN_TABS: readonly AdminTab[] = [
  { id: 'economy', label: '经济调账' },
  { id: 'baseValue', label: '基准价' },
  { id: 'job', label: '职业调级' },
  { id: 'mining', label: '副本重置' },
  { id: 'server', label: '服务器状态' },
]

/**
 * 各处下拉的候选表都带着**收窄后的 value 类型**, 而不是裸 string。
 * Dropdown 的 onChange 只能回 string, 于是面板要么写一次断言, 要么在自己的候选表里 find 回来 ——
 * 后者不引入任何断言, 且"下拉里没有的值"这一情况会自然落到 undefined 分支而不是被强行当成合法值。
 */
const CURRENCY_OPTIONS: readonly { value: PlannedCurrency; label: string }[] = [
  { value: 'CREDIT', label: '信用点' },
  { value: 'AZURE', label: '青辉石' },
]

const PAGE_SIZE_OPTIONS: readonly { value: string; label: string; size: number }[] = [
  { value: '25', label: '每页 25', size: 25 },
  { value: '50', label: '每页 50', size: 50 },
  { value: '100', label: '每页 100', size: 100 },
]

const BASE_VALUE_SOURCE_LABEL: Record<BaseValueSource, string> = {
  override: '手工设定',
  preset: '系统预设',
  none: '未设定',
}

const BASE_VALUE_SOURCE_TONE: Record<BaseValueSource, Tone> = {
  override: 'brand',
  preset: 'info',
  none: 'neutral',
}

/** 职业等级的合法区间。与 handlers 里 admin.job.setLevel 的校验同口径 (1..10 的整数)。 */
const JOB_LEVEL_MIN = 1
const JOB_LEVEL_MAX = 10

const INTEGER_PATTERN = /^\d+$/

interface PanelToast {
  tone: FeedbackTone
  message: string
}

/**
 * 非负整数解析。返回 null 表示"这串东西不是一个能提交的金额", 由调用方把输入框标红 ——
 * 刻意不做 Number(raw) || 0 那种兜底: 把 "12a" 悄悄当成 0 提交上去是最坏的一种"成功"。
 */
function parseNonNegativeInteger(raw: string): number | null {
  if (!INTEGER_PATTERN.test(raw)) {
    return null
  }
  const value = Number(raw)
  return Number.isSafeInteger(value) ? value : null
}

/**
 * 一件物品该显示的名字。
 * 两条回退各有各的来源, 不能合并成一个 `??`:
 *   - descriptionId 为空串是 admin.listItems 自己的回退 (物品从注册表取不到), 此时根本没有翻译键可解;
 *   - names 里取不到键只可能是本页送去解析的键与这里查的键不一致 (即本页的 bug), 退回键本身让它显形。
 */
function displayNameOf(entry: AdminItemEntry, names: Record<string, string>): string {
  if (entry.descriptionId === '') {
    return entry.itemId
  }
  const resolved = names[entry.descriptionId]
  return resolved === undefined ? entry.descriptionId : resolved
}

function describeFailure(error: unknown): string {
  if (error instanceof WebUiCallError) {
    const code = error.business === null ? null : error.business.errorCode
    return code === null ? error.message : `${error.message} (错误代码 ${code})`
  }
  return error instanceof Error ? error.message : String(error)
}

function formatMoment(epochMs: number): string {
  return new Date(epochMs).toLocaleString('zh-CN', { hour12: false })
}

function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  return `${String(days)} 天 ${String(hours)} 小时 ${String(minutes)} 分`
}

/** 距离某个未来时刻还有多久; 已过期时直接说过期, 不显示负数。 */
function formatCountdown(targetMs: number, nowMs: number): string {
  const remain = targetMs - nowMs
  if (remain <= 0) {
    return '已到期, 等待自动重置'
  }
  const hours = Math.floor(remain / 3_600_000)
  const minutes = Math.floor((remain % 3_600_000) / 60_000)
  return `${String(hours)} 小时 ${String(minutes)} 分后`
}

/** 货币在确认弹窗正文里的中文名。与下拉 label 同名, 单列一处只为让弹窗文案不依赖候选表的顺序。 */
function currencyLabelOf(currency: PlannedCurrency): string {
  return currency === 'CREDIT' ? '信用点' : '青辉石'
}

function Section({
  title,
  hint,
  actions,
  children,
}: {
  title: string
  hint?: string
  actions?: ReactNode
  children: ReactNode
}): ReactElement {
  return (
    <Panel actions={actions} description={hint} title={title}>
      <div className="flex flex-col gap-3">{children}</div>
    </Panel>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }): ReactElement {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-muted-foreground text-xs">{label}</span>
      {children}
    </label>
  )
}

/**
 * 目标玩家选择。
 *
 * 为什么是"下拉 + 一个点不动的输入框"这种看着别扭的组合: 玩家名可能含中文, 而中文输入当前是 BLOCKED
 * (接线清单 A14, MC EditBox 叠加未实现)。下拉是**当前唯一真能用**的选人通路 (候选来自世界里已知的玩家);
 * 输入框保留的是接口位 —— TextInput 传了 onRequestEdit 就转只读, 点击/Enter 只负责把当前值报给宿主,
 * 真正的回填要等宿主输入层落地后由它调 onChange。
 * 不把输入框直接做成可打字的普通输入: 那样在真机上只能敲出 ASCII, 玩家名一含中文就成了一个看着能用、
 * 实际永远搜不到人的框, 比明说"现在还不能输"更糟。
 */
function PlayerPicker({
  value,
  options,
  onChange,
  onRequestEdit,
}: {
  value: string
  options: readonly DropdownOption<string>[]
  onChange: (next: string) => void
  onRequestEdit: (current: string) => void
}): ReactElement {
  return (
    <div className="flex flex-wrap items-end gap-3">
      <Field label="目标玩家">
        <Dropdown onChange={onChange} options={options} value={value} />
      </Field>
      <Field label="手工输入玩家名">
        <TextInput onChange={onChange} onRequestEdit={onRequestEdit} value={value} />
      </Field>
      <p className="text-warning text-xs">
        中文输入暂未开放, 请用左侧下拉选择玩家
      </p>
    </div>
  )
}

function EconomyTab({
  players,
  playerOptions,
  target,
  onTargetChange,
  onToast,
}: {
  players: readonly MockOtherPlayer[]
  playerOptions: readonly DropdownOption<string>[]
  target: string
  onTargetChange: (next: string) => void
  onToast: (toast: PanelToast) => void
}): ReactElement {
  const [currency, setCurrency] = useState<PlannedCurrency>('CREDIT')
  const [amountText, setAmountText] = useState('0')
  const [querying, setQuerying] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [queried, setQueried] = useState<{ playerName: string; uuid: string; credit: number; azure: number } | null>(
    null,
  )
  const [applied, setApplied] = useState<{
    playerName: string
    beforeCredit: number
    beforeAzure: number
    afterCredit: number
    afterAzure: number
  } | null>(null)

  const amount = parseNonNegativeInteger(amountText)

  const runQuery = async (): Promise<void> => {
    setQuerying(true)
    setFailure(null)
    try {
      const result = await callMock('admin.economy.balance', { playerName: target })
      setQueried({
        playerName: result.playerName,
        uuid: result.playerUuid,
        credit: result.wallet.credit,
        azure: result.wallet.azure,
      })
    } catch (error: unknown) {
      setQueried(null)
      setFailure(describeFailure(error))
    } finally {
      setQuerying(false)
    }
  }

  const runSet = async (): Promise<void> => {
    if (amount === null) {
      return
    }
    setSubmitting(true)
    setFailure(null)
    try {
      const result = await callMock('admin.economy.set', {
        playerName: target,
        currency,
        amount,
      })
      setApplied({
        playerName: result.playerName,
        beforeCredit: result.before.credit,
        beforeAzure: result.before.azure,
        afterCredit: result.wallet.credit,
        afterAzure: result.wallet.azure,
      })
      onToast({
        tone: 'success',
        message: `${result.playerName} 的 ${currency} 已设为 ${String(amount)}`,
      })
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setSubmitting(false)
      // 成功与失败都关弹窗: 失败回执渲染在弹窗背后的面板里, 不关就等于把服务端那句话藏起来。
      setConfirmOpen(false)
    }
  }

  /**
   * 确认弹窗里的"改前"值。只认刚查回来的那个玩家 —— 换了目标玩家却还拿上一个人的余额去填这句话,
   * 比直接说"未知"危险得多。
   */
  const queriedBefore =
    queried === null || queried.playerName !== target
      ? null
      : currency === 'CREDIT'
        ? queried.credit
        : queried.azure

  const playerColumns: readonly DataTableColumn<MockOtherPlayer>[] = [
    { key: 'name', header: '玩家', sortValue: (row) => row.name, render: (row) => row.name },
    {
      key: 'online',
      header: '在线',
      sortValue: (row) => (row.online ? 1 : 0),
      render: (row) => (
        <Tag tone={row.online ? 'success' : 'neutral'}>{row.online ? '在线' : '离线'}</Tag>
      ),
    },
    {
      key: 'credit',
      header: '信用点',
      numeric: true,
      sortValue: (row) => row.wallet.credit,
      render: (row) => <Currency amount={row.wallet.credit} currency="credit" size="sm" />,
    },
    {
      key: 'azure',
      header: '青辉石',
      numeric: true,
      sortValue: (row) => row.wallet.azure,
      render: (row) => <Currency amount={row.wallet.azure} currency="azure" size="sm" />,
    },
  ]

  return (
    <div className="flex flex-col gap-4">
      <Section
        title="余额调账"
        hint="调账没有历史流水可查, 回执里的改前改后请当场核对"
      >
        <PlayerPicker
          value={target}
          options={playerOptions}
          onChange={onTargetChange}
          onRequestEdit={() => {
            onToast({
              tone: 'info',
              message: '中文输入暂未开放, 请用左侧下拉选择玩家',
            })
          }}
        />

        <div className="flex flex-wrap items-end gap-3">
          <Field label="货币">
            <Dropdown
              value={currency}
              options={CURRENCY_OPTIONS}
              onChange={(next) => {
                const matched = CURRENCY_OPTIONS.find((option) => option.value === next)
                if (matched !== undefined) {
                  setCurrency(matched.value)
                }
              }}
            />
          </Field>
          <Field label="设为 (非负整数, 是 set 不是 add)">
            <TextInput
              value={amountText}
              onChange={setAmountText}
              invalid={amount === null}
              maxLength={16}
            />
          </Field>
          <Button loading={querying} onClick={() => { void runQuery() }} variant="outline">
            查询余额
          </Button>
          <Button
            disabled={amount === null}
            onClick={() => {
              setConfirmOpen(true)
            }}
            variant="brand"
          >
            提交调账
          </Button>
        </div>

        {amount === null ? (
          <p className="text-destructive text-xs">金额必须是非负整数, 且不超过安全整数上界</p>
        ) : null}

        {failure === null ? null : <FeedbackAlert message={failure} tone="danger" />}

        {queried === null ? null : (
          <Surface>
            <div className="flex flex-wrap items-center gap-4">
              <span className="text-foreground text-sm">{queried.playerName}</span>
              <span className="font-mono text-muted-foreground text-xs">{queried.uuid}</span>
              <Currency amount={queried.credit} currency="credit" />
              <Currency amount={queried.azure} currency="azure" />
            </div>
          </Surface>
        )}

        {applied === null ? null : (
          <Surface tone="success">
            <div className="flex flex-col gap-2">
              <span className="text-foreground text-sm">{`${applied.playerName} 调账完成`}</span>
              <div className="flex flex-wrap items-center gap-4">
                <span className="text-muted-foreground text-xs">改前</span>
                <Currency amount={applied.beforeCredit} currency="credit" />
                <Currency amount={applied.beforeAzure} currency="azure" />
              </div>
              <div className="flex flex-wrap items-center gap-4">
                <span className="text-muted-foreground text-xs">改后</span>
                <Currency amount={applied.afterCredit} currency="credit" />
                <Currency amount={applied.afterAzure} currency="azure" />
              </div>
            </div>
          </Surface>
        )}

        {/*
          二道锁要求逐字敲的是**新余额数字**, 不是玩家名。
          玩家名会因为 A14 (MCEF 拿不到 MC 的 IME 焦点, 中文输入 BLOCKED) 而把整个操作封死 ——
          目标玩家名一含中文, OP 在游戏内根本敲不出那几个字。数字是纯 ASCII, 必定敲得出,
          而且逼 OP 把要写进去的那个数再读一遍, 比重抄一遍玩家名更贴近这道锁真正要防的事故。

          amount 为 null (输入尚未通过校验) 时不给 confirmWord: 此时没有一个确定的数可抄,
          给一个空串会让确认按钮永远解不开。校验未过本来就到不了这一步。
        */}
        <ConfirmDangerDialog
          confirmLabel="确认修改"
          confirmWord={amount === null ? undefined : String(amount)}
          loading={submitting}
          message={`把 ${target} 的${currencyLabelOf(currency)}由 ${
            queriedBefore === null ? '未知 (尚未查询该玩家余额)' : formatAmount(queriedBefore)
          } 改为 ${amount === null ? amountText : formatAmount(amount)}。这是直接覆盖余额而不是增减, 无法撤销, 且没有调账历史可供事后追溯。`}
          onConfirm={() => {
            void runSet()
          }}
          onOpenChange={setConfirmOpen}
          open={confirmOpen}
          title="修改玩家余额"
        />
      </Section>

      <Section
        title="世界内其他玩家余额"
        hint="提交成功后这张表当场刷新"
      >
        <DataTable
          columns={playerColumns}
          rowKey={(row) => row.uuid}
          rows={players}
        />
      </Section>
    </div>
  )
}

/**
 * 基准价 curate。本块是全页唯一走真契约的一块 (I1 已接线), 因此它的两个陷阱必须原样承受, 不许抹平:
 *   1. admin.listItems 的 v0 在无锚时是**缺席键**而不是 null (MarketAdminActions 用默认 Gson,
 *      写进去的 JsonNull 在写出阶段连键一起被丢掉), 故判存在只能用 `entry.v0 === undefined`;
 *      market.baseValue 那条恰好相反 (显式 null), 两者不可套同一套写法。
 *   2. 它是全库唯一带 total 的 action, 页数只有这里算得出来。
 */
function BaseValueTab({ onToast }: { onToast: (toast: PanelToast) => void }): ReactElement {
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [selectedItemId, setSelectedItemId] = useState<string | null>(null)
  const [v0Text, setV0Text] = useState('1')
  const [submitting, setSubmitting] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const listQuery = useMockAction('admin.listItems', { query, page, pageSize })
  const items = listQuery.data === null ? [] : listQuery.data.items

  /**
   * 最近一次拿到的匹配总条数。
   * 重查期间 data 是 null, 若直接按它算页数, 翻页器会在每次请求途中把上限塌成 0 页 ——
   * 于是当前页码看起来越界、上一页按钮短暂变灰。记住上一次的 total 只影响这一个显示量, 不影响提交。
   */
  const [knownTotal, setKnownTotal] = useState(0)
  if (listQuery.status === 'ready' && listQuery.data.total !== knownTotal) {
    setKnownTotal(listQuery.data.total)
  }
  const names = useItemNames(items.map((entry) => entry.descriptionId))
  const v0 = parseNonNegativeInteger(v0Text)
  const v0Valid = v0 !== null && v0 > 0

  const columns: readonly DataTableColumn<AdminItemEntry>[] = [
    {
      key: 'item',
      header: '物品',
      sortValue: (row) => row.itemId,
      render: (row) => (
        <span className="flex items-center gap-2">
          <ItemIcon itemId={row.itemId} label={displayNameOf(row, names)} />
          <span className="flex flex-col">
            <span className="text-foreground">{displayNameOf(row, names)}</span>
            <span className="text-muted-foreground text-xs">{row.itemId}</span>
          </span>
        </span>
      ),
    },
    {
      key: 'v0',
      header: '基准价 v0',
      numeric: true,
      // 缺席键排到最前而不是当 0: 0 是一个合法价格, 用它占位会让"没锚"与"锚成 0"在排序里混成一类。
      sortValue: (row) => (row.v0 === undefined ? -1 : row.v0),
      render: (row) =>
        row.v0 === undefined ? (
          <span className="text-muted-foreground">未设定 (键缺席)</span>
        ) : (
          <Currency amount={row.v0} currency="credit" size="sm" />
        ),
    },
    {
      key: 'source',
      header: '锚来源',
      sortValue: (row) => row.source,
      render: (row) => (
        <Tag tone={BASE_VALUE_SOURCE_TONE[row.source]}>{BASE_VALUE_SOURCE_LABEL[row.source]}</Tag>
      ),
    },
  ]

  const runSetBaseValue = async (): Promise<void> => {
    if (selectedItemId === null || v0 === null || !v0Valid) {
      return
    }
    setSubmitting(true)
    setFailure(null)
    try {
      const result = await callMock('admin.setBaseValue', { itemId: selectedItemId, v0 })
      onToast({ tone: 'success', message: `${result.itemId} 的 v0 已设为 ${String(result.v0)}` })
      // 列表里的 v0 与 source 都会跟着变 (override), 必须重查而不是在本地改一份。
      listQuery.reload()
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setSubmitting(false)
      setConfirmOpen(false)
    }
  }

  const lastPage = knownTotal === 0 ? 0 : Math.ceil(knownTotal / pageSize) - 1

  // 选中行的现价, 只用来填确认弹窗里的"由 X 改为 Y"。列表重查期间 items 为空, 此时诚实地说查不到。
  const selectedEntry = items.find((entry) => entry.itemId === selectedItemId)
  const currentV0Text =
    selectedEntry === undefined
      ? '未知 (列表正在重查)'
      : selectedEntry.v0 === undefined
        ? '未设定 (键缺席)'
        : formatAmount(selectedEntry.v0)

  return (
    <div className="flex flex-col gap-4">
      <Section
        title="物品检索"
        hint="按物品 id 匹配 (纯英文)。中文名过滤暂未开放"
        actions={
          <Button aria-label="重新拉取列表" onClick={listQuery.reload} size="sm" variant="outline">
            <RefreshCwIcon />
            重新拉取
          </Button>
        }
      >
        <div className="flex flex-wrap items-end gap-3">
          <Field label="itemId 过滤 (英文子串)">
            <TextInput
              value={query}
              onChange={(next) => {
                // 换了过滤条件还停在第 7 页, 得到的是一屏"无数据", 那不是空结果而是页码越界。
                setQuery(next)
                setPage(0)
              }}
              placeholder="minecraft:diamond"
              maxLength={64}
            />
          </Field>
          <Field label="每页条数">
            <Dropdown
              value={String(pageSize)}
              options={PAGE_SIZE_OPTIONS}
              onChange={(next) => {
                const matched = PAGE_SIZE_OPTIONS.find((option) => option.value === next)
                if (matched === undefined) {
                  return
                }
                setPageSize(matched.size)
                // 每页条数变了, 原页码指向的区间已经不存在, 回第 0 页而不是保留一个越界页码。
                setPage(0)
              }}
            />
          </Field>
          <Field label={`页码 (0 起, 共 ${String(lastPage + 1)} 页 / ${String(knownTotal)} 条)`}>
            <NumberInput value={page} onChange={setPage} min={0} max={lastPage} />
          </Field>
        </div>

        {listQuery.status === 'loading' ? (
          <LoadingBlock label="正在拉取物品列表" />
        ) : listQuery.status === 'error' ? (
          <ErrorBlock
            message={listQuery.error.message}
            code="admin.listItems"
            onRetry={listQuery.reload}
          />
        ) : items.length === 0 ? (
          <EmptyBlock
            title="没有匹配的物品"
            hint="换一个 itemId 子串, 或把过滤清空"
            icon={<SearchIcon aria-hidden="true" />}
          />
        ) : (
          <div className="max-h-96 overflow-y-auto">
            <DataTable
              columns={columns}
              rows={items}
              rowKey={(row) => row.itemId}
              selectedRowKey={selectedItemId === null ? undefined : selectedItemId}
              onRowClick={(row) => {
                setSelectedItemId(row.itemId)
                // 选中即把现有锚填进输入框: 改价的常态是微调, 从当前值起步比从 1 起步少一次手输。
                setV0Text(row.v0 === undefined ? '1' : String(row.v0))
              }}
            />
          </div>
        )}
      </Section>

      <Section
        title="设定基准价"
        hint="admin.setBaseValue 写的是 override 锚, 下界与合法性由引擎 setBaseValueOverride 校验"
      >
        {selectedItemId === null ? (
          <EmptyBlock
            title="先在上表里选一件物品"
            hint="行可点击, 也可用 Tab 聚焦后按 Enter"
            icon={<ArrowUpIcon aria-hidden="true" />}
          />
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            <Field label="物品">
              <span className="text-foreground text-sm">{selectedItemId}</span>
            </Field>
            <Field label="新 v0 (正整数)">
              <TextInput value={v0Text} onChange={setV0Text} invalid={!v0Valid} maxLength={16} />
            </Field>
            <Button
              disabled={!v0Valid}
              onClick={() => {
                setConfirmOpen(true)
              }}
              variant="brand"
            >
              写入 override 锚
            </Button>
          </div>
        )}
        {v0Valid ? null : <p className="text-destructive text-xs">v0 必须是大于 0 的整数</p>}
        {failure === null ? null : <FeedbackAlert message={failure} tone="danger" />}

        <ConfirmDangerDialog
          confirmLabel="确认修改"
          loading={submitting}
          message={`把 ${selectedItemId === null ? '(未选中物品)' : selectedItemId} 的基准价 v0 由 ${currentV0Text} 改为 ${
            v0 === null ? v0Text : formatAmount(v0)
          }。基准价是全服定价的锚, 写入后市场估价与收购曲线立刻跟着变, 且旧锚不会被保留, 无法撤销。`}
          onConfirm={() => {
            void runSetBaseValue()
          }}
          onOpenChange={setConfirmOpen}
          open={confirmOpen}
          title="修改物品基准价"
        />
      </Section>
    </div>
  )
}

function JobTab({
  jobOptions,
  playerOptions,
  target,
  onTargetChange,
  currentLevel,
  onToast,
}: {
  jobOptions: readonly { value: PlannedJobId; label: string }[]
  playerOptions: readonly DropdownOption<string>[]
  target: string
  onTargetChange: (next: string) => void
  currentLevel: (jobId: PlannedJobId) => number | null
  onToast: (toast: PanelToast) => void
}): ReactElement {
  const firstJob = jobOptions[0]
  const [jobId, setJobId] = useState<string>(firstJob === undefined ? '' : firstJob.value)
  const [level, setLevel] = useState(JOB_LEVEL_MIN)
  const [submitting, setSubmitting] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  // 候选表本身带着 PlannedJobId, 从它 find 回来即完成收窄; 没命中说明世界里没有这个职业, 按未知处理。
  const matchedJob = jobOptions.find((option) => option.value === jobId)
  const typedJobId = matchedJob === undefined ? null : matchedJob.value
  const now = typedJobId === null ? null : currentLevel(typedJobId)

  const runSetLevel = async (): Promise<void> => {
    if (typedJobId === null) {
      return
    }
    setSubmitting(true)
    setFailure(null)
    try {
      const result = await callMock('admin.job.setLevel', {
        playerName: target,
        jobId: typedJobId,
        level,
      })
      onToast({
        tone: 'success',
        message: `${result.playerName} 的 ${result.jobId} 已调至 ${String(result.level)} 级`,
      })
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setSubmitting(false)
      setConfirmOpen(false)
    }
  }

  return (
    <Section
      title="职业调级"
      hint="真服改级后会自行 syncTo 客户端, 前端不必再触发一次同步; 等级区间 1-10"
    >
      <PlayerPicker
        value={target}
        options={playerOptions}
        onChange={onTargetChange}
        onRequestEdit={() => {
          onToast({
            tone: 'info',
            message: '中文输入暂未开放, 请用左侧下拉选择玩家',
          })
        }}
      />

      <div className="flex flex-wrap items-end gap-3">
        <Field label="职业">
          <Dropdown value={jobId} options={jobOptions} onChange={setJobId} />
        </Field>
        <Field label={`目标等级 (当前 ${now === null ? '未知' : String(now)})`}>
          <NumberInput value={level} onChange={setLevel} min={JOB_LEVEL_MIN} max={JOB_LEVEL_MAX} />
        </Field>
        <Button
          disabled={typedJobId === null}
          onClick={() => {
            setConfirmOpen(true)
          }}
          variant="brand"
        >
          提交调级
        </Button>
      </div>

      {now === null ? null : (
        <Meter
          value={now}
          max={JOB_LEVEL_MAX}
          tone="brand"
          label={`${target} 当前等级 ${String(now)} / ${String(JOB_LEVEL_MAX)}`}
        />
      )}

      {failure === null ? null : <FeedbackAlert message={failure} tone="danger" />}

      <ConfirmDangerDialog
        confirmLabel="确认修改"
        loading={submitting}
        message={`把 ${target} 的${matchedJob === undefined ? '(未知职业)' : matchedJob.label}等级由 ${
          now === null ? '未知' : String(now)
        } 级改为 ${String(level)} 级。等级直接决定该职业的产出上限与解锁门槛, 服务端改完即刻 syncTo 客户端, 无法撤销。`}
        onConfirm={() => {
          void runSetLevel()
        }}
        onOpenChange={setConfirmOpen}
        open={confirmOpen}
        title="修改玩家职业等级"
      />
    </Section>
  )
}

function MiningTab({ onToast }: { onToast: (toast: PanelToast) => void }): ReactElement {
  const overview = useMockAction('mining.overview', {})
  const [pending, setPending] = useState<PlannedMiningInstance | null>(null)
  const [resetting, setResetting] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const runReset = async (instance: PlannedMiningInstance): Promise<void> => {
    setResetting(true)
    setFailure(null)
    try {
      const result = await callMock('admin.mining.reset', { difficulty: instance.difficulty })
      onToast({
        tone: 'warning',
        message: `${instance.displayName} 已于 ${formatMoment(result.resetAt)} 重置, 踢出 ${String(
          result.evictedPlayers,
        )} 名玩家`,
      })
      setPending(null)
      overview.reload()
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setResetting(false)
    }
  }

  if (overview.status === 'loading') {
    return (
      <Section title="副本重置" hint="全服只有 3 个常驻共享固定实例, 每难度一个, 不存在私有副本">
        <LoadingBlock label="正在拉取矿洞实例" />
      </Section>
    )
  }

  if (overview.status === 'error') {
    return (
      <Section title="副本重置" hint="全服只有 3 个常驻共享固定实例, 每难度一个, 不存在私有副本">
        <ErrorBlock message={overview.error.message} code="mining.overview" onRetry={overview.reload} />
      </Section>
    )
  }

  const instances = overview.data.instances
  const now = Date.now()

  return (
    <Section
      title="副本重置"
      hint="重置会清空该副本的当前进度, 不可撤销"
      actions={
        <Button aria-label="重新拉取矿洞实例" onClick={overview.reload} size="sm" variant="outline">
          <RefreshCwIcon />
          重新拉取
        </Button>
      }
    >
      {instances.length === 0 ? (
        <EmptyBlock
          title="没有可重置的矿洞实例"
          hint="三个常驻实例一个都没回来, 属服务端异常"
          icon={<TriangleAlertIcon aria-hidden="true" />}
        />
      ) : (
        <div className="flex flex-wrap gap-3">
          {instances.map((instance) => (
            <Surface className="flex w-96 flex-col gap-2" key={instance.difficulty}>
              <div className="flex items-center justify-between gap-2">
                <h3 className="font-medium text-foreground text-sm">{instance.displayName}</h3>
                <Tag tone={overview.data.myDifficulty === instance.difficulty ? 'brand' : 'neutral'}>
                  {instance.difficulty}
                </Tag>
              </div>
              <div className="flex flex-wrap gap-6">
                <Stat label="矿工等级门槛" value={String(instance.requiredMinerLevel)} />
                <Stat label="当前在内" value={`${String(instance.playersInside)} 人`} />
              </div>
              <Meter
                value={instance.danger}
                max={1}
                tone={instance.danger >= 0.7 ? 'danger' : instance.danger >= 0.4 ? 'warning' : 'success'}
                size="sm"
                label={`danger ${(instance.danger * 100).toFixed(0)}%`}
              />
              <span className="text-muted-foreground text-xs">{`上次重置 ${formatMoment(instance.lastResetAt)}`}</span>
              <span className="text-muted-foreground text-xs">{`下次自动重置 ${formatCountdown(instance.nextResetAt, now)}`}</span>
              <Button
                onClick={() => {
                  setPending(instance)
                }}
                variant="destructive"
              >
                立即重置
              </Button>
            </Surface>
          ))}
        </div>
      )}

      {failure === null ? null : <FeedbackAlert message={failure} tone="danger" />}

      <ConfirmDangerDialog
        confirmLabel="确认重置"
        loading={resetting}
        message={
          pending === null
            ? ''
            : `该实例内当前有 ${String(
                pending.playersInside,
              )} 名玩家, 重置会把他们全部踢出并清空进度。此操作不可撤销, 服务端不会再问第二遍。`
        }
        onConfirm={() => {
          if (pending !== null) {
            void runReset(pending)
          }
        }}
        onOpenChange={(next) => {
          // 弹窗只由 pending 驱动开合: 关闭即撤销这次选中的重置目标。
          if (!next) {
            setPending(null)
          }
        }}
        open={pending !== null}
        title={pending === null ? '重置矿洞实例' : `重置 ${pending.displayName}`}
      />
    </Section>
  )
}

function ServerTab(): ReactElement {
  const status = useMockAction('system.serverStatus', {})

  if (status.status === 'loading') {
    return (
      <Section title="服务器状态" hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用">
        <LoadingBlock label="正在拉取服务器状态" />
      </Section>
    )
  }

  if (status.status === 'error') {
    return (
      <Section title="服务器状态" hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用">
        <ErrorBlock message={status.error.message} code="system.serverStatus" onRetry={status.reload} />
      </Section>
    )
  }

  const data = status.data

  return (
    <Section
      title="服务器状态"
      hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用"
      actions={
        <Button aria-label="重新拉取服务器状态" onClick={status.reload} size="sm" variant="outline">
          <RefreshCwIcon />
          重新拉取
        </Button>
      }
    >
      <div className="flex flex-wrap gap-8">
        <div className="flex w-96 flex-col gap-3">
          <Meter
            value={data.online}
            max={data.maxPlayers}
            tone="brand"
            label="在线人数"
            valueText={`${String(data.online)} / ${String(data.maxPlayers)}`}
          />
          <Meter
            value={data.tps}
            max={20}
            tone={data.tps >= 19 ? 'success' : data.tps >= 15 ? 'warning' : 'danger'}
            label="TPS (低于 20 即在掉刻)"
            valueText={`${data.tps.toFixed(1)} / 20`}
          />
          <div className="flex flex-wrap gap-6">
            <Stat label="MSPT" value={`${data.mspt.toFixed(1)} ms`} />
            {/*
              口径写死在文案里: uptimeSeconds = getTickCount()/20, 是"已运行的游戏刻数折算秒", 服务器掉刻时
              它会慢于挂钟时间。原版没有"开机挂钟时刻"的公开 getter, 别把这个数当成开服到现在的真实时长。
            */}
            <Stat label="已运行 (按游戏刻折算)" value={formatUptime(data.uptimeSeconds)} />
          </div>
        </div>
      </div>
    </Section>
  )
}

export function AdminPage(): ReactElement {
  const world = useMockWorld()
  const [tab, setTab] = useState<AdminTabId>('economy')
  const [target, setTarget] = useState(world.player.name)
  const [toast, setToastValue] = useState<PanelToast | null>(null)
  /*
   * 回执的实例序号, 只用来当 React key。
   *
   * 退场动画那 140ms 里若被一条文案完全相同的新回执顶替, 组件从 props 上看不出这是新的一条,
   * 上一次排期的退场定时器会照常把它关掉 —— 玩家做了操作却什么都没看到。序号一变 React 就重建实例,
   * 旧实例的定时器随卸载一起清掉。
   */
  const toastSeqRef = useRef(0)
  const setToast = (next: PanelToast | null): void => {
    toastSeqRef.current += 1
    setToastValue(next)
  }

  const playerOptions: readonly DropdownOption<string>[] = [
    { value: world.player.name, label: `${world.player.name} (我自己)` },
    ...world.otherPlayers.map((player) => ({
      value: player.name,
      label: `${player.name}${player.online ? '' : ' (离线)'}`,
    })),
  ]

  const jobOptions: readonly { value: PlannedJobId; label: string }[] = world.jobs.progress.map((entry) => ({
    value: entry.jobId,
    label: entry.displayName,
  }))

  /**
   * 目标玩家在某职业的当前等级。自己与他人取自两处不同的世界字段 (自己有完整进度, 他人只有等级表),
   * 取不到时返回 null 而不是 1 —— "查不到"和"真的是 1 级"在调级面板上是两件事。
   */
  const currentLevel = (jobId: PlannedJobId): number | null => {
    if (target === world.player.name) {
      const entry = world.jobs.progress.find((candidate) => candidate.jobId === jobId)
      return entry === undefined ? null : entry.level
    }
    const other = world.otherPlayers.find((candidate) => candidate.name === target)
    return other === undefined ? null : other.jobLevels[jobId]
  }

  const pushToast = (next: PanelToast): void => {
    setToast(next)
  }

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <Panel
        actions={
          <Tag tone={world.player.isOp ? 'success' : 'danger'}>
            {world.player.isOp ? 'OP' : '非 OP'}
          </Tag>
        }
        description="页签显隐由平板外壳按 isOp 决定; 服务端每个 admin.* 动作内部仍会各自校验权限"
      >
        <div className="flex flex-col gap-3">
          {world.player.isOp ? null : (
            <Surface tone="danger">
              <p className="text-destructive text-sm">
                当前身份不是 OP: 本页所有提交都会被服务端以"该操作需要 OP 权限"拒绝, 界面仍可浏览
              </p>
            </Surface>
          )}

          <TabBar
            tabs={ADMIN_TABS}
            activeId={tab}
            onChange={(id) => {
              const matched = ADMIN_TABS.find((candidate) => candidate.id === id)
              // 页签 id 只可能来自上面那张表; 万一没命中就维持当前页, 不去猜一个默认页。
              if (matched !== undefined) {
                setTab(matched.id)
              }
            }}
          />
        </div>
      </Panel>

      {tab === 'economy' ? (
        <EconomyTab
          players={world.otherPlayers}
          playerOptions={playerOptions}
          target={target}
          onTargetChange={setTarget}
          onToast={pushToast}
        />
      ) : null}
      {tab === 'baseValue' ? <BaseValueTab onToast={pushToast} /> : null}
      {tab === 'job' ? (
        <JobTab
          jobOptions={jobOptions}
          playerOptions={playerOptions}
          target={target}
          onTargetChange={setTarget}
          currentLevel={currentLevel}
          onToast={pushToast}
        />
      ) : null}
      {tab === 'mining' ? <MiningTab onToast={pushToast} /> : null}
      {tab === 'server' ? <ServerTab /> : null}

      {toast === null ? null : (
        <FeedbackAlert
          key={toastSeqRef.current}
          onDismiss={() => {
            setToast(null)
          }}
          message={toast.message}
          tone={toast.tone}
        />
      )}
    </section>
  )
}
