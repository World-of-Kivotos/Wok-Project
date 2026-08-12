import { LockIcon, PackageIcon, RefreshCwIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  Currency,
  DataTable,
  type DataTableColumn,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  type FeedbackTone,
  Hint,
  ItemIcon,
  ItemSlot,
  LoadingBlock,
  Panel,
  Stat,
  Surface,
  TabBar,
  type TabItem,
  Tag,
  type Tone,
  TONE_FILL_CLASS,
} from '@/components/kit'
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogPopup,
  DialogTitle,
} from '@/components/ui/dialog'
import { WebUiCallError } from '../lib/bridge'
import type {
  CaseCatalogSkin,
  CaseOpenResult,
  CaseOwnedAsset,
  CaseRarity,
  CaseRarityWeight,
  CaseSoundCue,
  CaseStateResult,
} from '../lib/types'
import { callMock, useMockAction } from '../mock'

/**
 * 开箱面板 —— 平板 hub 内的那一版。
 *
 * === 与 jar 内置开箱页的关系 (必须先看这段, 否则会把两者当成重复实现) ===
 * mod 资源里已经有一份独立整页: `src/main/resources/assets/miningdim/web/case-opening.html`
 * (单文件 HTML, 深色拟真风, 自绘 reel 减速滚动 + tick 音效节拍), 由 MCEF 当作一整块屏幕直接加载。
 * 它与本页不是新旧替代, 而是**同一套服务端权威的两个外壳**: 三条 action 与皮肤资产、钱包全部共用同一份
 * 服务端数据, 本页一行业务规则都不另写。差别只有两处:
 *   1. 外壳: 那份是"独占全屏的开箱页", 本页是平板 hub 的一个面板, 与市场/职业等共用导航、钱包与返回路径;
 *   2. 视觉体系: 那份自绘了一套深色拟真皮与逐帧减速滚动; 本页走平板统一的中性灰阶体系, 且滚动动效仍不自绘
 *      —— 落点是服务端下发的权威值, 前端另抽一格演一遍是最容易与回执对不上的做法。
 * 两个外壳并存不会重复扣费: `openingId` 在服务端是幂等键, 同 id 复播回同一结果并置 `replayed=true`。
 *
 * === 契约依赖 ===
 * 本页 planned (前端假定契约) 依赖为 **0**, 全部走真契约:
 *   case.state / case.open / case.apply   —— lib/actions.ts SERVER_ACTIONS, 服务端 CaseWebUiActions.java
 *   client.playCaseSound                  —— 客户端本地 action, WebUiBridge.handleCaseSound
 * 一处需要在核销时一并修正的文档偏差: 接线清单第四章"完全没有后端的 10 块系统"仍把"开箱 (买箱 + 买钥匙 +
 * 掉率公示)"列为"全库零实现"。该行已过期 —— `com.miningdim.caseopening` 包已落地且三条 action 已注册,
 * 照那行去补一份 planned 契约会凭空造出与真契约打架的第二套形状。
 *
 * === 掉率公示 ===
 * 清单同一行写明"掉率公示是硬需求"。服务端下发的是**整数权重数组**而非小数概率, 且五档之和恒 100000,
 * 故本页公示三样东西: 每档权重原值、由它算出的百分比、以及总和是否仍等于 100000。总和对不上时不做任何
 * 归一化补救, 直接把"契约破裂"标出来 —— 归一化会让一个错误的权重表看起来完全正常。
 */

const RARITY_ORDER: readonly CaseRarity[] = ['blue', 'purple', 'pink', 'red', 'gold']

/**
 * 五档中文名逐字取自 jar 内置开箱页的 RARITY_META。两个外壳必须叫同一个名字 ——
 * 同一件皮肤在两处出现两种说法, 玩家第一反应是"这是两个箱子"。
 */
const RARITY_LABEL: Record<CaseRarity, string> = {
  blue: '军规级',
  purple: '受限级',
  pink: '保密级',
  red: '隐秘级',
  gold: '特殊物品',
}

/**
 * 稀有度 -> tone。tone 只有六个语义档且没有洋红档, 故 pink 与 purple 同落 brand ——
 * 两者的区分交给文字标签与掉率数字, 不靠颜色。为一个稀有度硬造第七档要同时动 index.css 与
 * kit/tokens.ts, 不开这个口子。
 */
const RARITY_TONE: Record<CaseRarity, Tone> = {
  blue: 'info',
  purple: 'brand',
  pink: 'brand',
  red: 'danger',
  gold: 'warning',
}

const RARITY_SOUND: Record<CaseRarity, CaseSoundCue> = {
  blue: 'reveal_blue',
  purple: 'reveal_purple',
  pink: 'reveal_pink',
  red: 'reveal_red',
  gold: 'reveal_gold',
}

/** CaseWeights 的契约恒等式: 五档整数权重之和恒 100000。 */
const CONTRACT_WEIGHT_TOTAL = 100_000

type RarityFilter = CaseRarity | 'all'

interface PanelToast {
  tone: FeedbackTone
  message: string
}

interface FailureView {
  message: string
  /** 业务失败才有的稳定机器码; 通用异常路径没有这一层 (缺口 A10 错误码中文化未做, 故原样展示英文码)。 */
  code: string | null
  /** case.open 专用: 服务端说这次失败可以拿同一个 openingId 原样重试, 不会重复扣费。 */
  retrySameOpeningId: boolean
}

function describeFailure(error: unknown): FailureView {
  if (error instanceof WebUiCallError) {
    return {
      message: error.message,
      code: error.business === null ? null : error.business.errorCode,
      retrySameOpeningId: error.business !== null && error.business.retrySameOpeningId,
    }
  }
  // 非 WebUiCallError 说明是前端自身的异常, 照样原样展示, 不压成一句"操作失败"。
  return {
    message: error instanceof Error ? error.message : String(error),
    code: null,
    retrySameOpeningId: false,
  }
}

/**
 * 失败横幅。抽成组件是因为它现在要出现在两处: 页内(开箱失败)与结果浮层内部(应用皮肤失败)。
 *
 * 浮层是 aria-modal 的对话框且带一层不透明遮罩, 页内那一份对浮层里的人既看不见也读不到 ——
 * "立即应用"失败时用户只看到按钮转完一圈然后毫无反应。故同一份内容必须在浮层里再画一遍。
 *
 * role="alert" 不可省: 失败是异步落进来的, 此刻焦点仍停在刚才那个按钮上, 没有 live region
 * 读屏就完全不会播报。
 */
function FailurePanel({ failure }: { failure: FailureView }): ReactElement {
  return (
    <div role="alert">
      <Surface className="flex flex-col gap-1" tone="danger">
        <p className="text-destructive text-sm">{failure.message}</p>
        <p className="text-muted-foreground text-xs">
          {failure.code === null
            ? '通用异常路径 (无 errorCode), 服务端未给稳定机器码'
            : `errorCode = ${failure.code}`}
        </p>
        {failure.retrySameOpeningId ? (
          <p className="text-warning text-xs">
            服务端标记为可原样重试: 扣费已发生, 必须沿用同一 openingId, 换新 id 会再扣一次
          </p>
        ) : null}
      </Surface>
    </div>
  )
}

function formatMoment(epochMs: number): string {
  return new Date(epochMs).toLocaleString('zh-CN', { hour12: false })
}

function randomHex(byteCount: number): string {
  const bytes = new Uint8Array(byteCount)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}

/**
 * 生成 openingId。
 *
 * 不直接用 crypto.randomUUID: 它只在**安全上下文**里存在, 而 MCEF 加载本页的来源不保证是 https。
 * jar 内置开箱页为此带了同一条回退链 (case-opening.html 的 createUuid), 这不是假想风险 —— 少了它,
 * 真客户端上点开箱会抛 "randomUUID is not a function", 而不是失败得体面。
 * getRandomValues 不受安全上下文限制, 故回退只补版本位与变体位, 不退化到 Math.random。
 */
function createOpeningId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const hex = randomHex(16)
  // 版本位钉 4、变体位钉 8: 服务端按 UUID.fromString 解析, 随机凑出的这两处不能省。
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}

function formatOdds(weight: number, total: number): string {
  if (total <= 0) {
    return '—'
  }
  // 三位小数: gold 档 400/100000 = 0.400%, 少一位就把最稀有的两档压成同一个数字。
  return `${((weight / total) * 100).toFixed(3)}%`
}

/**
 * 还能开几次。两种货币各自能开多少次取小者。
 * 单价非正时返回 null 而不是算出一个巨大的次数: 服务端配置的下界是 1 (CaseOpeningConfig defineInRange),
 * 出现 0 就是契约破裂, 此时任何计算结果都是假的。
 */
function affordableOpens(state: CaseStateResult): number | null {
  if (state.creditCost <= 0 || state.azureCost <= 0) {
    return null
  }
  return Math.min(
    Math.floor(state.wallet.credit / state.creditCost),
    Math.floor(state.wallet.azure / state.azureCost),
  )
}

function RarityChip({ rarity }: { rarity: CaseRarity }): ReactElement {
  return <Tag tone={RARITY_TONE[rarity]}>{RARITY_LABEL[rarity]}</Tag>
}

/** 掉率公示。表 + 分段条并存: 表给可核对的原始整数, 条给"gold 那一格窄到几乎看不见"这个直觉。 */
function OddsPanel({ weights }: { weights: readonly CaseRarityWeight[] }): ReactElement {
  const total = weights.reduce((sum, entry) => sum + entry.weight, 0)
  const contractHolds = total === CONTRACT_WEIGHT_TOTAL

  const columns: readonly DataTableColumn<CaseRarityWeight>[] = [
    { header: '稀有度', key: 'rarity', render: (row) => <RarityChip rarity={row.rarity} /> },
    { header: '权重 (整数)', key: 'weight', numeric: true, render: (row) => String(row.weight) },
    { header: '掉率', key: 'odds', numeric: true, render: (row) => formatOdds(row.weight, total) },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-muted-foreground text-sm">权重总和</span>
        <Tag tone={contractHolds ? 'success' : 'danger'}>
          {`${String(total)} / ${String(CONTRACT_WEIGHT_TOTAL)}`}
        </Tag>
        <span className="text-muted-foreground text-xs">
          {contractHolds
            ? '与 CaseWeights 契约恒等式一致'
            : '与契约恒等式不符: 权重表已破裂, 下方百分比按实际总和折算, 不代表服务端真实掉率'}
        </span>
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-muted-foreground text-xs">
          {'五档权重构成 (按声明序 blue -> gold)'}
        </span>
        <div className="flex h-2 w-full overflow-hidden rounded-full bg-muted">
          {weights.map((entry) => (
            <div
              className={TONE_FILL_CLASS[RARITY_TONE[entry.rarity]]}
              key={entry.rarity}
              style={{
                width: total <= 0 ? '0%' : `${String((entry.weight / total) * 100)}%`,
              }}
            />
          ))}
        </div>
      </div>

      <DataTable columns={columns} rowKey={(row) => row.rarity} rows={weights} />
    </div>
  )
}

/**
 * 服务端下发的 reel。
 *
 * 刻意不做减速滚动: `stopIndex` 是服务端权威落点, 前端自己抽一格再演一遍是最容易与回执对不上的做法。
 * 因此这里把落点直接标出来并把该格高亮, 让"动画该停在哪一格"这件事以数据而不是演出的形式呈现。
 */
function ReelStrip({ open }: { open: CaseOpenResult }): ReactElement {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-muted-foreground text-xs">
        {`服务端权威落点 stopIndex = ${String(open.stopIndex)} (共 ${String(open.reel.length)} 格)`}
      </p>
      <div aria-label="开箱滚动条" className="w-full overflow-x-auto">
        <div className="flex gap-2 pb-2">
          {open.reel.map((entry, index) => (
            <div
              className="flex flex-col items-center gap-1"
              key={`${String(index)}-${entry.skinId}`}
            >
              <ItemSlot
                itemId={entry.gunId}
                label={entry.displayName}
                scale={1}
                selected={index === open.stopIndex}
              />
              <span
                className={
                  index === open.stopIndex
                    ? 'font-medium text-brand text-xs'
                    : 'text-muted-foreground text-xs'
                }
              >
                {index === open.stopIndex ? '落点' : String(index)}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/** 箱内皮肤目录。ownedCount 直接决定这格上贴不贴数字, 于是"开出来一把"在目录上立刻可见。 */
function CatalogGrid({ skins }: { skins: readonly CaseCatalogSkin[] }): ReactElement {
  return (
    <div className="flex flex-wrap gap-3">
      {RARITY_ORDER.map((rarity) => {
        const group = skins.filter((skin) => skin.rarity === rarity)
        if (group.length === 0) {
          return null
        }
        return (
          <Surface className="flex flex-col gap-2" key={rarity} tone={RARITY_TONE[rarity]}>
            <div className="flex items-center gap-2">
              <RarityChip rarity={rarity} />
              <span className="text-muted-foreground text-xs">{`${String(group.length)} 款`}</span>
            </div>
            <div className="flex flex-wrap gap-2">
              {group.map((skin) => (
                <Hint
                  content={`${skin.displayName} · ${skin.gunId} · 已持有 ${String(skin.ownedCount)}`}
                  key={skin.skinId}
                >
                  <ItemSlot
                    itemId={skin.gunId}
                    label={skin.displayName}
                    {...(skin.ownedCount > 0 ? { count: skin.ownedCount } : {})}
                    scale={2}
                  />
                </Hint>
              ))}
            </div>
          </Surface>
        )
      })}
    </div>
  )
}

const OWNED_COLUMNS: readonly DataTableColumn<CaseOwnedAsset>[] = [
  {
    key: 'skin',
    header: '皮肤',
    sortValue: (row) => row.displayName,
    render: (row) => (
      <span className="flex items-center gap-2">
        <ItemIcon itemId={row.gunId} label={row.displayName} />
        <span className="text-foreground">{row.displayName}</span>
      </span>
    ),
  },
  {
    key: 'rarity',
    header: '稀有度',
    sortValue: (row) => RARITY_ORDER.indexOf(row.rarity),
    render: (row) => <RarityChip rarity={row.rarity} />,
  },
  {
    key: 'gun',
    header: '枪械',
    sortValue: (row) => row.gunId,
    render: (row) => <span className="text-muted-foreground">{row.gunId}</span>,
  },
  {
    key: 'acquired',
    header: '获得时间',
    sortValue: (row) => row.acquiredAt,
    render: (row) => <span className="text-muted-foreground">{formatMoment(row.acquiredAt)}</span>,
  },
  {
    key: 'lock',
    header: '交易锁',
    sortValue: (row) => row.tradeLockedUntil,
    render: (row) =>
      row.tradeLockedUntil === 0 ? (
        <span className="text-muted-foreground">无锁</span>
      ) : (
        <span className="text-warning">{formatMoment(row.tradeLockedUntil)}</span>
      ),
  },
]

export function CasePage(): ReactElement {
  const stateQuery = useMockAction('case.state', {})

  /**
   * 待重试的 openingId。失败且服务端说 retrySameOpeningId 时必须留着同一个 id 再发一次 ——
   * 那种失败发生在扣费之后, 换新 id 重试等于再扣一次; 反之扣费前的失败必须换新 id (服务端构造该异常时
   * 传的就是 false)。这一个字段就是这条规则的全部实现, 不要"顺手"在重试时无脑生成新 UUID。
   */
  const [retryOpeningId, setRetryOpeningId] = useState<string | null>(null)
  const [opening, setOpening] = useState(false)
  const [lastOpen, setLastOpen] = useState<CaseOpenResult | null>(null)
  const [resultOpen, setResultOpen] = useState(false)
  const [failure, setFailure] = useState<FailureView | null>(null)
  const [toast, setToast] = useState<PanelToast | null>(null)
  const [rarityFilter, setRarityFilter] = useState<RarityFilter>('all')
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const [appliedAssetId, setAppliedAssetId] = useState<string | null>(null)

  /**
   * 最近一次成功拿到的 case.state。
   *
   * useMockAction 在每次 reload 时把 data 置回 null 并转 loading, 若直接按它渲染, 开箱成功后的那次重查
   * 会把整页 (含刚弹出的结果窗与 reel) 换成一块加载骨架 —— 玩家看到的是"开完箱子界面闪没了"。
   * 于是留一份最近的成功快照: 首次加载仍是整页加载态, 之后的重查只在角上标一行"刷新中", 页面不塌。
   * 用渲染期 setState (React 官方认可的"按外部值调整 state"写法) 而不是 useEffect: 后者要多一帧才生效,
   * 那一帧渲染的正是被清空的旧值。
   */
  const [snapshot, setSnapshot] = useState<CaseStateResult | null>(null)
  if (stateQuery.status === 'ready' && stateQuery.data !== snapshot) {
    setSnapshot(stateQuery.data)
  }

  /**
   * 音效是旁路: 它失败不该把一次已经成功的开箱变成失败 (钱已经扣了)。但也不静默吞掉 ——
   * 完整错误留在控制台 (保留堆栈), 面板上另给一条 info 提示, 与 lib/i18n.ts 处理显示名解析失败同一纪律。
   */
  const playCue = (cue: CaseSoundCue): void => {
    callMock('client.playCaseSound', { cue }).catch((error: unknown) => {
      console.error('[case] 音效播放失败:', error)
      setToast({ tone: 'info', message: `音效 ${cue} 播放失败, 开箱流程不受影响` })
    })
  }

  const runOpen = async (): Promise<void> => {
    const openingId = retryOpeningId ?? createOpeningId()
    setRetryOpeningId(openingId)
    setOpening(true)
    setFailure(null)
    playCue('unlock')
    try {
      // caseId 显式省略而不是传 null: 服务端对显式 null 抛 INVALID_REQUEST, 缺省才落到 "founders"。
      const result = await callMock('case.open', { openingId })
      setLastOpen(result)
      setResultOpen(true)
      setSelectedAssetId(result.result.assetId)
      setRetryOpeningId(null)
      playCue(RARITY_SOUND[result.result.rarity])
      setToast({
        tone: result.replayed ? 'info' : 'success',
        message: result.replayed
          ? `断线复播: ${result.result.displayName} (未重复扣费)`
          : `开出 ${RARITY_LABEL[result.result.rarity]} · ${result.result.displayName}`,
      })
      // 钱包与持有列表的权威都在服务端, 开完必须重查一次, 不在前端自己减余额。
      stateQuery.reload()
    } catch (error: unknown) {
      const view = describeFailure(error)
      setFailure(view)
      if (!view.retrySameOpeningId) {
        setRetryOpeningId(null)
      }
    } finally {
      setOpening(false)
    }
  }

  const runApply = async (assetId: string): Promise<void> => {
    setApplying(true)
    setFailure(null)
    try {
      const result = await callMock('case.apply', { assetId })
      setAppliedAssetId(result.assetId)
      setToast({ tone: 'success', message: `已应用皮肤 ${result.skinId} 到 ${result.gunId}` })
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setApplying(false)
    }
  }

  const toastNode =
    toast === null ? null : (
      <FeedbackAlert
        message={toast.message}
        onDismiss={() => {
          setToast(null)
        }}
        tone={toast.tone}
      />
    )

  if (snapshot === null && stateQuery.status === 'loading') {
    return (
      <section className="flex flex-col gap-4">
        <Panel>
          <LoadingBlock label="正在读取武器箱状态" size="lg" />
        </Panel>
      </section>
    )
  }

  // 首屏就失败时整页只剩错误态; 已经有快照时错误只作为一条横幅贴在页内 (见下方), 不推翻已经画出来的东西。
  if (snapshot === null && stateQuery.status === 'error') {
    return (
      <section className="flex flex-col gap-4">
        <ErrorBlock
          message={stateQuery.error.message}
          code={
            stateQuery.error instanceof WebUiCallError
              ? `case.state / code ${String(stateQuery.error.code)}`
              : 'case.state'
          }
          onRetry={stateQuery.reload}
        />
      </section>
    )
  }

  if (snapshot === null) {
    // status 已不是 loading/error 却仍无快照, 只可能是 useMockAction 的契约破了, 不在这里造一个空箱子糊过去。
    throw new Error('case.state 既未加载中也未失败, 却没有可渲染的快照')
  }

  const state = snapshot

  if (!state.enabled) {
    return (
      <section className="flex flex-col gap-4">
        <EmptyBlock
          title="开箱当前不可用"
          hint="enabled = 配置开关 AND tacz 已加载 AND 资源包已注册, 三者任一为假即关闭"
          icon={<LockIcon aria-hidden="true" />}
        />
      </section>
    )
  }

  const openable = affordableOpens(state)
  const affordable = openable !== null && openable > 0
  const ownedShown = state.owned
  const filteredOwned =
    rarityFilter === 'all'
      ? ownedShown
      : ownedShown.filter((asset) => asset.rarity === rarityFilter)
  const selectedAsset =
    selectedAssetId === null
      ? undefined
      : ownedShown.find((asset) => asset.assetId === selectedAssetId)

  const filterTabs: readonly TabItem[] = [
    { id: 'all', label: `全部 (${String(ownedShown.length)})` },
    ...RARITY_ORDER.map((rarity) => ({
      id: rarity,
      label: `${RARITY_LABEL[rarity]} (${String(
        ownedShown.filter((asset) => asset.rarity === rarity).length,
      )})`,
    })),
  ]

  return (
    <section className="flex flex-col gap-4">
      <Panel
        actions={
          <>
            {stateQuery.status === 'loading' ? <LoadingBlock label="刷新中" size="sm" /> : null}
            <Button
              aria-label="重新拉取武器箱状态"
              loading={stateQuery.status === 'loading'}
              onClick={stateQuery.reload}
              size="sm"
              variant="outline"
            >
              <RefreshCwIcon />
              重新拉取
            </Button>
          </>
        }
        description={`caseId = ${state.caseId} · 平板内版本 (jar 内置整页 case-opening.html 共用同一套服务端权威)`}
        title={state.displayName}
      >
        <div className="flex flex-col gap-4">
          {stateQuery.status === 'error' ? (
            <FeedbackAlert
              message={`重查 case.state 失败: ${stateQuery.error.message} (下方数据是上一次成功的快照, 可能已过期)`}
              tone="danger"
              action={
                <Button onClick={stateQuery.reload} size="sm" variant="destructive-outline">
                  重试
                </Button>
              }
            />
          ) : null}

          <div className="flex flex-wrap items-end gap-6">
            <Stat
              label="单次开箱扣费 (双币同时扣, 缺一即拒)"
              value={
                <span className="flex items-center gap-3">
                  <Currency amount={state.creditCost} currency="credit" />
                  <Currency amount={state.azureCost} currency="azure" />
                </span>
              }
            />
            <Stat
              label="我的余额"
              value={
                <span className="flex items-center gap-3">
                  <Currency amount={state.wallet.credit} currency="credit" />
                  <Currency amount={state.wallet.azure} currency="azure" />
                </span>
              }
            />
            <Stat
              label="还能开"
              value={
                <Tag tone={affordable ? 'success' : 'danger'}>
                  {openable === null ? '单价非法 (契约破裂)' : `${String(openable)} 次`}
                </Tag>
              }
            />
            <Hint
              content={
                affordable
                  ? '开箱是不可撤销的双币扣费, 服务端以 openingId 幂等'
                  : '服务端在扣费前同时校验两种货币, 任一不足即 INSUFFICIENT_FUNDS'
              }
            >
              <Button
                disabled={!affordable}
                loading={opening}
                onClick={() => {
                  void runOpen()
                }}
                size="lg"
                variant="brand"
              >
                {/* 只有"上一次失败且服务端允许原样重试"才换文案: 请求进行中 retryOpeningId 也非空, 那时换字会让人以为已经失败过一次。 */}
                {failure !== null && retryOpeningId !== null ? '用同一 openingId 重试' : '开箱'}
              </Button>
            </Hint>
          </div>

          {failure === null ? null : <FailurePanel failure={failure} />}
        </div>
      </Panel>

      <Panel
        description="权重是服务端下发的整数数组, 百分比由前端按总和折算; 总和与契约恒等式的比对结果同屏给出"
        title="掉率公示"
      >
        <OddsPanel weights={state.weights} />
      </Panel>

      <Panel
        description={`共 ${String(state.skins.length)} 款, 格上的数字是我已持有的数量`}
        title="箱内皮肤"
      >
        <CatalogGrid skins={state.skins} />
      </Panel>

      {lastOpen === null ? null : (
        <Panel
          actions={
            <Button
              onClick={() => {
                setResultOpen(true)
              }}
              size="sm"
              variant="outline"
            >
              重看结果
            </Button>
          }
          description="reel 与落点均由服务端下发; 本页不自绘滚动动效, 直接标出权威落点"
          title="本次开箱回执"
        >
          <ReelStrip open={lastOpen} />
        </Panel>
      )}

      <Panel
        description={
          state.ownedTotal > ownedShown.length
            ? `服务端只回前 ${String(ownedShown.length)} 条 (OWNED_RESPONSE_LIMIT), 真实总数 ${String(state.ownedTotal)}`
            : `共 ${String(state.ownedTotal)} 件`
        }
        title="我的皮肤资产"
      >
        <div className="flex flex-col gap-3">
          <TabBar
            activeId={rarityFilter}
            onChange={(id) => {
              // id 来自本页自己构造的 tabs, 只可能是 'all' 或五档之一; 收窄靠查表而不是断言。
              const matched = RARITY_ORDER.find((rarity) => rarity === id)
              setRarityFilter(matched ?? 'all')
            }}
            tabs={filterTabs}
            variant="underline"
          />

          {filteredOwned.length === 0 ? (
            <EmptyBlock
              title="该稀有度下还没有皮肤"
              hint={
                ownedShown.length === 0
                  ? '开一次箱子就会出现在这里'
                  : '换一个稀有度页签, 或继续开箱'
              }
              icon={<PackageIcon aria-hidden="true" />}
            />
          ) : (
            <div className="max-h-96 overflow-y-auto">
              <DataTable
                columns={OWNED_COLUMNS}
                rows={filteredOwned}
                rowKey={(row) => row.assetId}
                {...(selectedAssetId === null ? {} : { selectedRowKey: selectedAssetId })}
                onRowClick={(row) => {
                  setSelectedAssetId(row.assetId)
                }}
              />
            </div>
          )}

          {selectedAsset === undefined ? null : (
            <Surface className="flex flex-wrap items-center gap-4">
              <ItemIcon itemId={selectedAsset.gunId} label={selectedAsset.displayName} scale={2} />
              <div className="flex flex-col gap-0.5">
                <span className="text-foreground text-sm">{selectedAsset.displayName}</span>
                <span className="text-muted-foreground text-xs">{`assetId ${selectedAsset.assetId}`}</span>
                <span className="text-muted-foreground text-xs">{`displayId ${selectedAsset.displayId}`}</span>
              </div>
              {appliedAssetId === selectedAsset.assetId ? (
                <Tag tone="success">本次会话已应用</Tag>
              ) : null}
              <Button
                loading={applying}
                onClick={() => {
                  void runApply(selectedAsset.assetId)
                }}
                variant="brand"
              >
                应用到手持枪械
              </Button>
            </Surface>
          )}
        </div>
      </Panel>

      <Dialog
        onOpenChange={(next) => {
          if (!next) {
            setResultOpen(false)
          }
        }}
        open={resultOpen && lastOpen !== null}
      >
        <DialogPopup className="max-w-2xl">
          {lastOpen === null ? null : (
            <>
              <DialogHeader>
                <DialogTitle>开箱结果</DialogTitle>
                <DialogDescription>{`${lastOpen.result.gunId} · ${lastOpen.result.skinId}`}</DialogDescription>
              </DialogHeader>
              <div className="flex flex-col items-center gap-3 px-6 pb-6">
                <RarityChip rarity={lastOpen.result.rarity} />
                <ItemIcon
                  itemId={lastOpen.result.gunId}
                  label={lastOpen.result.displayName}
                  scale={3}
                />
                <p className="font-medium text-base text-foreground">
                  {lastOpen.result.displayName}
                </p>
                <p className="text-muted-foreground text-xs">
                  {lastOpen.result.tradeLockedUntil === 0
                    ? '交易锁: 无 (服务端当前恒为 0, 7 天 trade hold 尚未实现)'
                    : `交易锁至 ${formatMoment(lastOpen.result.tradeLockedUntil)}`}
                </p>
                <div className="flex items-center gap-4">
                  <Currency amount={lastOpen.wallet.credit} currency="credit" />
                  <Currency amount={lastOpen.wallet.azure} currency="azure" />
                </div>
                <Button
                  loading={applying}
                  onClick={() => {
                    void runApply(lastOpen.result.assetId)
                  }}
                  variant="brand"
                >
                  立即应用
                </Button>
                {/* 浮层开着时 failure 只可能来自本浮层里的这次 apply —— 开箱按钮在遮罩之后, 点不到。 */}
                {failure === null ? null : <FailurePanel failure={failure} />}
              </div>
            </>
          )}
        </DialogPopup>
      </Dialog>

      {toastNode}
    </section>
  )
}
