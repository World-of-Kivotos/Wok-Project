import type { ReactElement, ReactNode } from 'react'
import { useState } from 'react'
import type {
  PixelFrameTone,
  PixelSelectOption,
  PixelTab,
  PixelTableColumn,
} from '../../components/pixel'
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
  PixelStepper,
  PixelTable,
  PixelTabs,
  PixelToast,
} from '../../components/pixel'
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
 * 管理后台 (OP)。五块: 经济调账 / 基准价 curate / 职业调级 / 副本重置 / 服务器状态。
 *
 * === 权限边界 ===
 * 页签的显隐由平板外壳按 isOp 决定, 本页不重复做一次门控; 服务端每个 admin.* action 内部仍会各自校验
 * (MarketAdminActions.requireOp)。前端这层只是"不给非 OP 看见入口", 不是权限本身 —— 因此非 OP 状态下
 * 本页照常渲染, 只在顶部标出"所有提交都会被服务端拒绝", 让失败可预期而不是变成一串看不懂的异常。
 *
 * === 契约依赖 ===
 * 真契约 (已接线, 直接就是最终形状):
 *   admin.listItems / admin.setBaseValue    接线清单 I1 (READY x2, 已接线)
 * planned 假定契约 (后端尚无, 走 mock/planned.ts; 接线时按此表逐条核销):
 *   admin.economy.balance / admin.economy.set   I2 (WRAP x2; /economy set 已有 ledgerOf+balance 范式)
 *   admin.job.setLevel                          I3 (WRAP; 权限校验/setLevel/改级后 syncTo 全就绪)
 *   admin.mining.reset                          I4 (WRAP; 活跃版 /mining reset 无二次确认, 弹窗必须前端加)
 *   mining.overview                             F1 (只读, 用来给三个重置目标提供当前人数与倒计时)
 *   system.serverStatus                         A4 (WRAP, MinecraftServer 公开 API)
 * 另有两条已知缺口在本页直接可见, 不做任何遮掩:
 *   I2 无流水表 (D7): 历史调账查不到, 故 admin.economy.set 的回执带 before, 至少让操作者当场看见改前改后;
 *   A14 中文输入 BLOCKED: 玩家名输入框只能走 onRequestEdit 向宿主喊话, 见下方 PlayerPicker 注释。
 *
 * === 权限之外的一条硬约束 ===
 * I4 那条弹窗是**前端责任**: 活跃版 /mining reset 直接执行, 有二次确认的那套在 com.miningdim.command
 * 死代码里。接线之后服务端仍然不会拦, 所以 PixelConfirmDanger 这一步永远不能"为了顺手"去掉。
 */

type AdminTabId = 'economy' | 'baseValue' | 'job' | 'mining' | 'server'

/** 把 PixelTab 的 id 收窄到本页的五个字面量, 于是切页签时 find 回来的 id 直接就是 AdminTabId, 不必断言。 */
interface AdminTab extends PixelTab {
  id: AdminTabId
}

const ADMIN_TABS: readonly AdminTab[] = [
  { id: 'economy', label: '经济调账', icon: 'coin-credit' },
  { id: 'baseValue', label: '基准价 curate', icon: 'filter' },
  { id: 'job', label: '职业调级', icon: 'star' },
  { id: 'mining', label: '副本重置', icon: 'warning' },
  { id: 'server', label: '服务器状态', icon: 'info' },
]

/**
 * 各处下拉的候选表都带着**收窄后的 value 类型**, 而不是裸 string。
 * PixelSelect 的 onChange 只能回 string, 于是面板要么写一次断言, 要么在自己的候选表里 find 回来 ——
 * 后者不引入任何断言, 且"下拉里没有的值"这一情况会自然落到 undefined 分支而不是被强行当成合法值。
 */
const CURRENCY_OPTIONS: readonly { value: PlannedCurrency; label: string }[] = [
  { value: 'CREDIT', label: '信用点 CREDIT' },
  { value: 'AZURE', label: '青辉石 AZURE' },
]

const PAGE_SIZE_OPTIONS: readonly { value: string; label: string; size: number }[] = [
  { value: '25', label: '每页 25', size: 25 },
  { value: '50', label: '每页 50', size: 50 },
  { value: '100', label: '每页 100', size: 100 },
]

const BASE_VALUE_SOURCE_LABEL: Record<BaseValueSource, string> = {
  override: 'override (OP 手工锚)',
  preset: 'preset (预设锚)',
  none: 'none (无锚)',
}

const BASE_VALUE_SOURCE_TONE: Record<BaseValueSource, PixelFrameTone> = {
  override: 'accent',
  preset: 'info',
  none: 'neutral',
}

/** 职业等级的合法区间。与 handlers 里 admin.job.setLevel 的校验同口径 (1..10 的整数)。 */
const JOB_LEVEL_MIN = 1
const JOB_LEVEL_MAX = 10

const INTEGER_PATTERN = /^\d+$/

interface PanelToast {
  tone: PixelFrameTone
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
    return code === null ? error.message : `${error.message} (errorCode ${code})`
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
    return '已到期 (等待调度器执行)'
  }
  const hours = Math.floor(remain / 3_600_000)
  const minutes = Math.floor((remain % 3_600_000) / 60_000)
  return `${String(hours)} 小时 ${String(minutes)} 分后`
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
    <PixelFrame variant="panel" className="flex flex-col gap-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h2 className="text-2x text-fg">{title}</h2>
          {hint === undefined ? null : <p className="text-1x text-muted">{hint}</p>}
        </div>
        {actions}
      </header>
      {children}
    </PixelFrame>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }): ReactElement {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-1x text-muted">{label}</span>
      {children}
    </label>
  )
}

/**
 * 目标玩家选择。
 *
 * 为什么是"下拉 + 一个点不动的输入框"这种看着别扭的组合: 玩家名可能含中文, 而中文输入当前是 BLOCKED
 * (接线清单 A14, MC EditBox 叠加未实现)。下拉是**当前唯一真能用**的选人通路 (候选来自世界里已知的玩家);
 * 输入框保留的是接口位 —— PixelInput 传了 onRequestEdit 就转只读, 点击/Enter 只负责把当前值报给宿主,
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
  options: readonly PixelSelectOption[]
  onChange: (next: string) => void
  onRequestEdit: (current: string) => void
}): ReactElement {
  return (
    <div className="flex flex-wrap items-end gap-4">
      <Field label="目标玩家 (世界内已知)">
        <PixelSelect value={value} options={options} onChange={onChange} />
      </Field>
      <Field label="手工输入玩家名 (需宿主输入层)">
        <PixelInput value={value} onChange={onChange} onRequestEdit={onRequestEdit} />
      </Field>
      <p className="text-1x text-warning">当前不可输入中文 (A14 未实现), 点击输入框只会向宿主发起编辑请求</p>
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
  playerOptions: readonly PixelSelectOption[]
  target: string
  onTargetChange: (next: string) => void
  onToast: (toast: PanelToast) => void
}): ReactElement {
  const [currency, setCurrency] = useState<PlannedCurrency>('CREDIT')
  const [amountText, setAmountText] = useState('0')
  const [querying, setQuerying] = useState(false)
  const [submitting, setSubmitting] = useState(false)
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
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section
        title="余额调账"
        hint="I2 无流水表 (缺口 D7): 历史调账查不到, 故回执带 before, 改前改后必须当场核对"
      >
        <PlayerPicker
          value={target}
          options={playerOptions}
          onChange={onTargetChange}
          onRequestEdit={(current) => {
            onToast({
              tone: 'info',
              message: `已向宿主请求编辑玩家名 (当前值 ${current}); 宿主输入层未实现 (A14), 值不会回填`,
            })
          }}
        />

        <div className="flex flex-wrap items-end gap-4">
          <Field label="货币">
            <PixelSelect
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
            <PixelInput
              value={amountText}
              onChange={setAmountText}
              invalid={amount === null}
              maxLength={16}
            />
          </Field>
          <PixelButton tone="neutral" loading={querying} onClick={() => { void runQuery() }}>
            查询余额
          </PixelButton>
          <PixelButton
            tone="accent"
            disabled={amount === null}
            loading={submitting}
            onClick={() => {
              void runSet()
            }}
          >
            提交调账
          </PixelButton>
        </div>

        {amount === null ? (
          <p className="text-1x text-danger">金额必须是非负整数, 且不超过安全整数上界</p>
        ) : null}

        {failure === null ? null : <p className="text-1x text-danger">{failure}</p>}

        {queried === null ? null : (
          <PixelFrame variant="inset" className="flex flex-wrap items-center gap-4 p-3">
            <span className="text-1x text-fg">{queried.playerName}</span>
            <span className="text-1x text-muted">{queried.uuid}</span>
            <PixelCurrency amount={queried.credit} currency="credit" />
            <PixelCurrency amount={queried.azure} currency="azure" />
          </PixelFrame>
        )}

        {applied === null ? null : (
          <PixelFrame variant="inset" tone="success" className="flex flex-col gap-2 p-3">
            <span className="text-1x text-fg">{`${applied.playerName} 调账完成`}</span>
            <div className="flex flex-wrap items-center gap-4">
              <span className="text-1x text-muted">改前</span>
              <PixelCurrency amount={applied.beforeCredit} currency="credit" />
              <PixelCurrency amount={applied.beforeAzure} currency="azure" />
            </div>
            <div className="flex flex-wrap items-center gap-4">
              <span className="text-1x text-muted">改后</span>
              <PixelCurrency amount={applied.afterCredit} currency="credit" />
              <PixelCurrency amount={applied.afterAzure} currency="azure" />
            </div>
          </PixelFrame>
        )}
      </Section>

      <Section
        title="世界内其他玩家余额"
        hint="直接读 mock 世界状态: 上面提交成功后这张表当场变, 不必刷新 —— 接线后这块换成服务端查询"
      >
        <table className="w-full border-collapse text-1x">
          <thead>
            <tr>
              <th scope="col" className="border-b border-border px-2 py-1 text-left text-muted">
                玩家
              </th>
              <th scope="col" className="border-b border-border px-2 py-1 text-left text-muted">
                在线
              </th>
              <th scope="col" className="border-b border-border px-2 py-1 text-left text-muted">
                信用点
              </th>
              <th scope="col" className="border-b border-border px-2 py-1 text-left text-muted">
                青辉石
              </th>
            </tr>
          </thead>
          <tbody>
            {players.map((player) => (
              <tr key={player.uuid}>
                <td className="px-2 py-1 text-fg">{player.name}</td>
                <td className="px-2 py-1">
                  <PixelBadge tone={player.online ? 'success' : 'neutral'}>
                    {player.online ? '在线' : '离线'}
                  </PixelBadge>
                </td>
                <td className="px-2 py-1">
                  <PixelCurrency amount={player.wallet.credit} currency="credit" />
                </td>
                <td className="px-2 py-1">
                  <PixelCurrency amount={player.wallet.azure} currency="azure" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
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

  const columns: readonly PixelTableColumn<AdminItemEntry>[] = [
    {
      key: 'item',
      header: '物品',
      sortValue: (row) => row.itemId,
      render: (row) => (
        <span className="flex items-center gap-2">
          <ItemIcon itemId={row.itemId} label={displayNameOf(row, names)} />
          <span className="flex flex-col">
            <span className="text-fg">{displayNameOf(row, names)}</span>
            <span className="text-muted">{row.itemId}</span>
          </span>
        </span>
      ),
    },
    {
      key: 'v0',
      header: '基准价 v0',
      // 缺席键排到最前而不是当 0: 0 是一个合法价格, 用它占位会让"没锚"与"锚成 0"在排序里混成一类。
      sortValue: (row) => (row.v0 === undefined ? -1 : row.v0),
      render: (row) =>
        row.v0 === undefined ? (
          <span className="text-muted">未设定 (键缺席)</span>
        ) : (
          <PixelCurrency amount={row.v0} currency="credit" />
        ),
    },
    {
      key: 'source',
      header: '锚来源',
      sortValue: (row) => row.source,
      render: (row) => (
        <PixelBadge tone={BASE_VALUE_SOURCE_TONE[row.source]}>
          {BASE_VALUE_SOURCE_LABEL[row.source]}
        </PixelBadge>
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
    }
  }

  const lastPage = knownTotal === 0 ? 0 : Math.ceil(knownTotal / pageSize) - 1

  return (
    <div className="flex flex-col gap-4">
      <Section
        title="物品检索"
        hint="按小写子串匹配完整 itemId。itemId 是纯英文可直接输入; 按中文名过滤要等 A14 宿主输入层"
        actions={
          <PixelButton tone="neutral" icon="refresh" onClick={listQuery.reload} label="重新拉取列表">
            重新拉取
          </PixelButton>
        }
      >
        <div className="flex flex-wrap items-end gap-4">
          <Field label="itemId 过滤 (英文子串)">
            <PixelInput
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
            <PixelSelect
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
            <PixelStepper value={page} onChange={setPage} min={0} max={lastPage} />
          </Field>
        </div>

        {listQuery.status === 'loading' ? (
          <PixelLoading label="正在拉取物品列表" />
        ) : listQuery.status === 'error' ? (
          <PixelError
            message={listQuery.error.message}
            code="admin.listItems"
            onRetry={listQuery.reload}
          />
        ) : items.length === 0 ? (
          <PixelEmpty title="没有匹配的物品" hint="换一个 itemId 子串, 或把过滤清空" icon="search" />
        ) : (
          <PixelTable
            columns={columns}
            rows={items}
            rowKey={(row) => row.itemId}
            className="h-96"
            {...(selectedItemId === null ? {} : { selectedRowKey: selectedItemId })}
            onRowClick={(row) => {
              setSelectedItemId(row.itemId)
              // 选中即把现有锚填进输入框: 改价的常态是微调, 从当前值起步比从 1 起步少一次手输。
              setV0Text(row.v0 === undefined ? '1' : String(row.v0))
            }}
          />
        )}
      </Section>

      <Section
        title="设定基准价"
        hint="admin.setBaseValue 写的是 override 锚, 下界与合法性由引擎 setBaseValueOverride 校验"
      >
        {selectedItemId === null ? (
          <PixelEmpty title="先在上表里选一件物品" hint="行可点击, 也可用 Tab 聚焦后按 Enter" icon="arrow-up" />
        ) : (
          <div className="flex flex-wrap items-end gap-4">
            <Field label="物品">
              <span className="text-1x text-fg">{selectedItemId}</span>
            </Field>
            <Field label="新 v0 (正整数)">
              <PixelInput
                value={v0Text}
                onChange={setV0Text}
                invalid={!v0Valid}
                maxLength={16}
              />
            </Field>
            <PixelButton
              tone="accent"
              disabled={!v0Valid}
              loading={submitting}
              onClick={() => {
                void runSetBaseValue()
              }}
            >
              写入 override 锚
            </PixelButton>
          </div>
        )}
        {v0Valid ? null : <p className="text-1x text-danger">v0 必须是大于 0 的整数</p>}
        {failure === null ? null : <p className="text-1x text-danger">{failure}</p>}
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
  playerOptions: readonly PixelSelectOption[]
  target: string
  onTargetChange: (next: string) => void
  currentLevel: (jobId: PlannedJobId) => number | null
  onToast: (toast: PanelToast) => void
}): ReactElement {
  const firstJob = jobOptions[0]
  const [jobId, setJobId] = useState<string>(firstJob === undefined ? '' : firstJob.value)
  const [level, setLevel] = useState(JOB_LEVEL_MIN)
  const [submitting, setSubmitting] = useState(false)
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
        onRequestEdit={(current) => {
          onToast({
            tone: 'info',
            message: `已向宿主请求编辑玩家名 (当前值 ${current}); 宿主输入层未实现 (A14), 值不会回填`,
          })
        }}
      />

      <div className="flex flex-wrap items-end gap-4">
        <Field label="职业">
          <PixelSelect value={jobId} options={jobOptions} onChange={setJobId} />
        </Field>
        <Field label={`目标等级 (当前 ${now === null ? '未知' : String(now)})`}>
          <PixelStepper
            value={level}
            onChange={setLevel}
            min={JOB_LEVEL_MIN}
            max={JOB_LEVEL_MAX}
          />
        </Field>
        <PixelButton
          tone="accent"
          disabled={typedJobId === null}
          loading={submitting}
          onClick={() => {
            void runSetLevel()
          }}
        >
          提交调级
        </PixelButton>
      </div>

      {now === null ? null : (
        <PixelProgress
          value={now}
          max={JOB_LEVEL_MAX}
          tone="accent"
          label={`${target} 当前等级 ${String(now)} / ${String(JOB_LEVEL_MAX)}`}
        />
      )}

      {failure === null ? null : <p className="text-1x text-danger">{failure}</p>}
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
        <PixelLoading label="正在拉取矿洞实例" />
      </Section>
    )
  }

  if (overview.status === 'error') {
    return (
      <Section title="副本重置" hint="全服只有 3 个常驻共享固定实例, 每难度一个, 不存在私有副本">
        <PixelError message={overview.error.message} code="mining.overview" onRetry={overview.reload} />
      </Section>
    )
  }

  const instances = overview.data.instances
  const now = Date.now()

  return (
    <Section
      title="副本重置"
      hint="活跃版 /mining reset 无二次确认, 下面这道确认弹窗是前端责任, 接线后也不能去掉"
      actions={
        <PixelButton tone="neutral" icon="refresh" onClick={overview.reload} label="重新拉取矿洞实例">
          重新拉取
        </PixelButton>
      }
    >
      {instances.length === 0 ? (
        <PixelEmpty title="没有可重置的矿洞实例" hint="三个常驻实例一个都没回来, 属服务端异常" icon="warning" />
      ) : (
        <div className="flex flex-wrap gap-4">
          {instances.map((instance) => (
            <PixelFrame
              key={instance.difficulty}
              variant="inset"
              className="flex w-96 flex-col gap-2 p-3"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-2x text-fg">{instance.displayName}</span>
                <PixelBadge tone={overview.data.myDifficulty === instance.difficulty ? 'accent' : 'neutral'}>
                  {instance.difficulty}
                </PixelBadge>
              </div>
              <span className="text-1x text-muted">{`需要矿工 ${String(instance.requiredMinerLevel)} 级`}</span>
              <span className="text-1x text-fg">{`当前在内 ${String(instance.playersInside)} 人`}</span>
              <PixelProgress
                value={instance.danger}
                max={1}
                tone={instance.danger >= 0.7 ? 'danger' : instance.danger >= 0.4 ? 'warning' : 'success'}
                size="sm"
                label={`danger ${(instance.danger * 100).toFixed(0)}%`}
              />
              <span className="text-1x text-muted">{`上次重置 ${formatMoment(instance.lastResetAt)}`}</span>
              <span className="text-1x text-muted">{`下次自动重置 ${formatCountdown(instance.nextResetAt, now)}`}</span>
              <PixelButton
                tone="danger"
                onClick={() => {
                  setPending(instance)
                }}
              >
                立即重置
              </PixelButton>
            </PixelFrame>
          ))}
        </div>
      )}

      {failure === null ? null : <p className="text-1x text-danger">{failure}</p>}

      <PixelConfirmDanger
        open={pending !== null}
        title={pending === null ? '重置矿洞实例' : `重置 ${pending.displayName}`}
        message={
          pending === null
            ? ''
            : `该实例内当前有 ${String(
                pending.playersInside,
              )} 名玩家, 重置会把他们全部踢出并清空进度。此操作不可撤销, 服务端不会再问第二遍。`
        }
        confirmLabel="确认重置"
        loading={resetting}
        onConfirm={() => {
          if (pending !== null) {
            void runReset(pending)
          }
        }}
        onCancel={() => {
          setPending(null)
        }}
      />
    </Section>
  )
}

function ServerTab(): ReactElement {
  const status = useMockAction('system.serverStatus', {})

  if (status.status === 'loading') {
    return (
      <Section title="服务器状态" hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用">
        <PixelLoading label="正在拉取服务器状态" />
      </Section>
    )
  }

  if (status.status === 'error') {
    return (
      <Section title="服务器状态" hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用">
        <PixelError message={status.error.message} code="system.serverStatus" onRetry={status.reload} />
      </Section>
    )
  }

  const data = status.data

  return (
    <Section
      title="服务器状态"
      hint="A4: MinecraftServer 公开 API 的包装, hub 首页与本页共用"
      actions={
        <PixelButton tone="neutral" icon="refresh" onClick={status.reload} label="重新拉取服务器状态">
          重新拉取
        </PixelButton>
      }
    >
      <div className="flex flex-wrap gap-8">
        <div className="flex w-96 flex-col gap-2">
          <PixelProgress
            value={data.online}
            max={data.maxPlayers}
            tone="accent"
            label={`在线 ${String(data.online)} / ${String(data.maxPlayers)}`}
          />
          <PixelProgress
            value={data.tps}
            max={20}
            tone={data.tps >= 19 ? 'success' : data.tps >= 15 ? 'warning' : 'danger'}
            label={`TPS ${data.tps.toFixed(1)} / 20 (低于 20 即在掉刻)`}
            thresholds={[{ at: 15, tone: 'danger' }]}
          />
          <span className="text-1x text-muted">{`MSPT ${data.mspt.toFixed(1)} ms`}</span>
          <span className="text-1x text-muted">{`已运行 ${formatUptime(data.uptimeSeconds)}`}</span>
        </div>

        <div className="flex w-96 flex-col gap-2">
          <span className="text-1x text-muted">公告</span>
          {data.announcement === '' ? (
            <PixelEmpty title="当前没有公告" hint="运营常态是没设置, 服务端给的是空串而不是 null" icon="info" />
          ) : (
            <PixelFrame variant="inset" className="p-3">
              <p className="text-1x text-fg">{data.announcement}</p>
            </PixelFrame>
          )}
        </div>
      </div>
    </Section>
  )
}

export function AdminPage(): ReactElement {
  const world = useMockWorld()
  const [tab, setTab] = useState<AdminTabId>('economy')
  const [target, setTarget] = useState(world.player.name)
  const [toast, setToast] = useState<PanelToast | null>(null)

  const playerOptions: readonly PixelSelectOption[] = [
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
      <PixelFrame variant="window" className="flex flex-col gap-4 p-4">
        <header className="flex flex-wrap items-center justify-between gap-4">
          {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
          <div className="flex flex-col gap-1">
            <p className="text-1x text-muted">
              页签显隐由平板外壳按 isOp 决定; 服务端每个 admin.* 动作内部仍会各自校验权限
            </p>
          </div>
          <PixelBadge tone={world.player.isOp ? 'success' : 'danger'}>
            {world.player.isOp ? 'OP' : '非 OP'}
          </PixelBadge>
        </header>

        {world.player.isOp ? null : (
          <PixelFrame variant="inset" tone="danger" className="p-3">
            <p className="text-1x text-danger">
              当前身份不是 OP: 本页所有提交都会被服务端以"该操作需要 OP 权限"拒绝, 界面仍可浏览
            </p>
          </PixelFrame>
        )}

        <PixelTabs
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
      </PixelFrame>

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
        <PixelToast
          tone={toast.tone}
          message={toast.message}
          onDismiss={() => {
            setToast(null)
          }}
        />
      )}
    </section>
  )
}
