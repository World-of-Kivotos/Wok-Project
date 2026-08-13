import { LockIcon, PackageIcon, RefreshCwIcon } from 'lucide-react'
import type { ReactElement, TransitionEvent } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
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
import { WebUiCallError, isMockActive } from '../lib/bridge'
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
 *   2. 视觉体系: 那份自绘了一套深色拟真皮与逐帧减速滚动; 本页走平板统一的中性灰阶体系, 减速交给一次
 *      CSS 过渡 (只动 transform, 由合成器接管), 不做逐帧 JS 推进。两者的落点同为服务端下发的权威值 ——
 *      前端只把这个已经定死的结果演出来, 不抽也不算。
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

/**
 * 结果浮层的兜底开启时限。正常路径由条带落定回调触发, 这条只在落定信号没来时兜。
 * 取值 = 长扫 2200ms + 落定 160ms + 余量, 宁可晚开也不能不开。
 */
const RESULT_REVEAL_FALLBACK_MS = 3_000

type RarityFilter = CaseRarity | 'all'

interface PanelToast {
  tone: FeedbackTone
  message: string
}

interface FailureView {
  message: string
  /*
   * 业务失败才有的稳定机器码; 通用异常路径没有这一层。
   *
   * 本页展示的是服务端原文 + 括号里的码, 不走 lib/errorText.ts 那张表 —— 那张表因此**刻意不收**
   * case.* 那一组码 (收了也永远读不到, 留着就是"看着像收编了"的假象)。要改成走那张表, 得连
   * CASE_DISABLED 的一码两义 (运营关闭 / TaCZ 与资源包未就绪) 一起在服务端用 params 拆开,
   * 否则玩家会丢掉"是被关了还是没装资源包"的区分, 那属于开箱模块自己的改动, 不在 W1 范围。
   */
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
        {/* 错误码留着是为了让玩家报给管理员时有据可查; 没有码的通用异常就不必再占一行。 */}
        {failure.code === null ? null : (
          <p className="text-muted-foreground text-xs">{`错误代码 ${failure.code}`}</p>
        )}
        {failure.retrySameOpeningId ? (
          <p className="text-warning text-xs">这次的费用已经扣了, 直接点重试即可, 不会再扣一次</p>
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
    { header: '权重', key: 'weight', numeric: true, render: (row) => String(row.weight) },
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
            ? '掉率表校验通过'
            : '掉率表异常: 下方百分比只按当前数值折算, 可能与实际掉率不符'}
        </span>
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-muted-foreground text-xs">五档掉率占比</span>
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
 * 落定回弹的过冲幅度, 取落点格实测宽度的比例而非固定像素 —— 格子尺寸由 ItemSlot 的 scale 决定,
 * 写死像素会在改 scale 时错位。0.16 约等于一格的六分之一, 只够看出"顿了一下", 不至于变成弹簧玩具。
 */
const REEL_OVERSHOOT_RATIO = 0.16

type ReelPhase = 'idle' | 'armed' | 'reeling' | 'settling' | 'landed'

interface ReelMotion {
  phase: ReelPhase
  /** 条带相对最终落位的横向偏移 (px)。0 即已落位。 */
  shift: number
}

/**
 * 各阶段的过渡声明。idle/armed/landed 一律无过渡: 前两者是起跑前的瞬时定位 (带过渡就会把定位本身也演一遍),
 * landed 是已经停稳。motion-reduce 下再兜一层 transition-none —— 主判断在 JS 里 (整段不演), 这里是
 * 让"reduce 不做位移"这条规则在 CSS 层也成立, 不依赖 JS 那一处判断没被改坏。
 */
const REEL_TRACK_MOTION: Record<ReelPhase, string> = {
  idle: 'transition-none',
  armed: 'transition-none',
  reeling:
    'transition-transform duration-(--duration-reel) ease-(--ease-reel) motion-reduce:transition-none',
  settling:
    'transition-transform duration-(--duration-reel-settle) ease-out-soft motion-reduce:transition-none',
  landed: 'transition-none',
}

/**
 * 落点格的提亮。长扫途中压暗一档, 落定时提亮回来 —— 这是"开出的就是它"的那一下, 只动 opacity。
 * 压暗那一侧刻意不挂过渡: 挂上去连"压暗"本身也会演 180ms, 于是直接落位的那条路 (复播 / reduce)
 * 会先淡出再淡回, 变成一次没人要的闪烁。过渡只写在提亮这一侧, 按 CSS 过渡取变更后样式的规则生效。
 */
const REEL_WINNER_REVEAL: Record<ReelPhase, string> = {
  idle: '',
  armed: 'opacity-70',
  reeling: 'opacity-70',
  settling: 'opacity-100 transition-opacity duration-(--duration-enter) ease-out-soft',
  landed: 'opacity-100 transition-opacity duration-(--duration-enter) ease-out-soft',
}

/**
 * 服务端下发的 reel。
 *
 * 落点是服务端权威: `stopIndex` 指哪一格就是哪一格。本组件不抽、不算、也不允许动效结果与它有半点偏差 ——
 * 前端自己抽一格再演一遍是最容易与回执对不上的做法。动效只负责把这个已经定死的结果演出来。
 *
 * 位移的做法值得写清楚, 否则下一个人会"简化"成错的那种: 先把可滚动容器的 scrollLeft 一次性设到落位,
 * 再给条带一个等量的**正向** transform 把它拽回条带开头, 然后过渡回 0, 视觉上即一次向左的长扫。
 * 反过来做 (scrollLeft 不动, 条带一路负向位移到底) 看着一样, 但停下时条带的可滚动内容宽度会缩得比容器还窄:
 * 滚动条消失, 条带前半段被永久裁掉, 玩家再也翻不回去看完整奖池。
 */
function ReelStrip({ open, onLanded }: { open: CaseOpenResult; onLanded: () => void }): ReactElement {
  const viewportRef = useRef<HTMLDivElement>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const winnerRef = useRef<HTMLDivElement>(null)
  const [motion, setMotion] = useState<ReelMotion>({ phase: 'idle', shift: 0 })

  /*
   * 落定回调走"最新回调 ref": 父组件传的是内联箭头函数, 放进 effect 依赖数组会让每次无关重渲染
   * 都重跑一遍落定通知。与 kit/Feedback.tsx 的自动消失计时器同一手法。
   */
  const onLandedRef = useRef(onLanded)
  useEffect(() => {
    onLandedRef.current = onLanded
  })

  useEffect(() => {
    if (motion.phase === 'landed') {
      onLandedRef.current()
    }
  }, [motion.phase])

  /*
   * 用 layout effect 而不是 effect: 起跑姿态必须在这一帧绘制前就位。放到 effect 里会先绘出一帧"已落位"
   * 的画面再跳回开头, 玩家看到的是结果先漏了一眼、然后倒着重演。
   */
  useLayoutEffect(() => {
    const viewport = viewportRef.current
    const track = trackRef.current
    const winner = winnerRef.current
    // 取不到落点那一格 (stopIndex 越界) 就整段不演: 条带静止、高亮照常, 不猜一个落点糊过去。
    if (viewport === null || track === null || winner === null) {
      return
    }

    /*
     * 量之前先把条带按回 0: 上一次开箱的长扫可能还在跑 (结果回来得比 2200ms 快, 玩家可以马上再开一箱),
     * 而在途的位移会一起算进可滚动区 —— transform 后的子元素边框盒是要计进 scrollWidth 的, 据此算出的
     * 落位会偏大, 后面那道 maxScroll 夹取随之失效, 表现为第二次长扫的减速尾巴被浏览器逐帧夹掉。
     *
     * 只写 transform 不够: 此刻条带的 className 里还挂着 2200ms 的 transition-transform, 这行直写
     * 只会再起一条过渡而不是瞬时归零, 量到的仍是脏值。故先临时摘掉过渡、强制一次重排让归零真正落地,
     * 量完再把过渡交还给 className (React 下一次提交会重设 style.transform, 不留第二个真源)。
     */
    track.style.transition = 'none'
    track.style.transform = 'translate3d(0px, 0, 0)'
    /*
     * 上一次开箱补的尾部留白必须在量之前一并清掉, 与上面归零 transform 同一个理由 ——
     * 不清的话 `track.scrollWidth` 会含着上一轮的留白, 算出来的 trailingRoom 偏大、neededTail 变负,
     * 于是这一次不补; 下一次因为没补又算出要补。表现是**隔次居中、隔次不居中**, 极难归因。
     */
    track.style.width = ''
    track.style.paddingInlineEnd = ''
    void track.offsetWidth

    /*
     * 落点格中心相对条带左缘的距离, 用两个 rect 相减而不是 offsetLeft。
     *
     * 原先写的是 `winner.offsetLeft - track.offsetLeft + winner.offsetWidth / 2`, 实测恒少算 16px:
     * track 挂着 transform, 于是它成了后代的 containing block, Chrome 据此把 `winner.offsetParent`
     * 判成 track —— `winner.offsetLeft` **本来就已经是相对 track 的值**了, 再减一个 track.offsetLeft
     * (那是 track 相对外层面板的偏移, 完全另一个坐标系) 就凭空少了 16px。
     *
     * 更要紧的是它属于"碰巧算对一半": 这份代码之所以没错得更离谱, 全靠 track 恰好带着 transform;
     * 哪天有人把 transform 改成 none, offsetParent 会换人, 结果反过来错 16px, 而且没有任何报错。
     * 两个 rect 相减不依赖 offsetParent 是谁 —— transform 与滚动对二者的影响完全相同, 差值恒稳。
     */
    const trackRect = track.getBoundingClientRect()
    const winnerRect = winner.getBoundingClientRect()
    const winnerCenter = winnerRect.left - trackRect.left + winnerRect.width / 2
    const overshoot = winnerRect.width * REEL_OVERSHOOT_RATIO

    /*
     * 尾部留白: 让落点格真的能滚到可视区中央。
     *
     * 服务端恒把落点钉在 40 格里的第 35 格 (CaseRoller.STOP_INDEX), 离尾部只有 4 格 —— 条带本身的
     * 可滚动余量根本不够把它推到中央, 于是下面那道 maxScroll 夹取必然生效。真页实测三档视口全部被夹:
     * 1280 宽偏右 300px、1024 宽偏右 172px, 而 1920 宽最惨 —— 可滚动余量只剩 260px, 2200ms 里总共
     * 才走 254px, 那不是"长扫"是爬。**屏越宽越糟**, 而 MCEF 是按游戏窗口尺寸渲染的, 1920 是常态。
     *
     * 补一段纯空白把可滚动区撑够即可, 不伪造任何格子 —— 奖池内容是服务端权威, 前端多画一格都是撒谎。
     * 只补差额而不是无脑补一屏: 补多了落定后玩家能滚进一大片空白, 看着像条带断了。
     *
     * **必须连 width: max-content 一起设**, 只写 padding 是空转的 (实测): track 是 display:flex 且
     * width:auto, 计算宽度等于滚动容器的内容宽 (如 1002px), 40 个格子溢出到 1912px 之外;
     * padding-inline-end 铺在**内容盒**右缘, 整段落在溢出内容的后面, 对可滚动范围零贡献 ——
     * 受控实验: pad 0 与 pad 280px 的 scrollWidth 同为 1912, 直到 pad 单独超过 1912 (取 max 不是相加)
     * 才开始跟着走。改成 max-content 后 track 宽度变成内容宽, padding 才叠在溢出范围之外。
     * 另一条死路别试: 给最后一个 flex 子项挂 margin-inline-end 同样被吞 (滚动容器末端外边距的经典坑)。
     */
    const trailingRoom = track.scrollWidth - winnerCenter
    const neededTail = viewport.clientWidth / 2 + overshoot - trailingRoom
    if (neededTail > 0) {
      track.style.width = 'max-content'
      track.style.paddingInlineEnd = `${String(Math.ceil(neededTail))}px`
    }
    // 留白改的是布局, 必须先落地再量 maxScroll, 否则量到的还是补之前的可滚动上限。
    void track.offsetWidth

    const maxScroll = Math.max(viewport.scrollWidth - viewport.clientWidth, 0)
    /*
     * 落位处给回弹留出 overshoot 的余量: 回弹那一下条带会再往左多走一点, 若此刻已经贴着可滚动区右端,
     * 浏览器会按缩小后的滚动上限把 scrollLeft 夹回来, 正好把回弹抵消掉, 右缘还要闪出一条空白。
     */
    const landing = Math.min(
      Math.max(winnerCenter - viewport.clientWidth / 2, 0),
      Math.max(maxScroll - overshoot, 0),
    )
    viewport.scrollLeft = landing
    // 回读实际值: 浏览器会按自己的可滚动上限再夹一次, 拿假设值算位移会让条带停在差一点的地方。
    const travel = viewport.scrollLeft

    /*
     * 三种情况直接落位, 不演:
     * 1. replayed —— 断线复播是"恢复上一次的结果", 再演一遍会被当成又开了一箱;
     * 2. reduce —— 2200ms 横扫上千像素正是前庭敏感人群最受不了的那类动作, 减弱到 400ms 只会更暴烈;
     * 3. travel 不足 1px —— 落点本来就在视野里, 没有可扫的距离, 演一遍只剩一次几像素的抽搐。
     * 这三种情况下仍保留落点格那一下 180ms 的透明度提亮 (reduce 档由令牌自动收到 100ms):
     * reduce 的要求是减弱而不是归零, 位移可以没有, "结果落定了"这个时间提示不能没有。
     */
    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const settleNow = open.replayed || prefersReducedMotion || travel < 1

    /*
     * 把条带滚进视野。少了这一步, 前面那些让浮层等落定的功夫全是白做的。
     *
     * 真页实测 (1280x720 与 1920x1080 两种视口一致): 条带面板的 top 恒在 1006px, 而内容区滚动容器的
     * 可视带只到 830px 上下, scrollTop 全程是 0 —— 条带整块落在折叠线以下约 300px, 页面又不会自己滚过去。
     * 点击后 +900/+1500/+2100ms 三张全屏截图与点击前像素级一致 (只有顶栏钱包数字变了): 玩家看到的是
     * "什么都没发生 2.8 秒然后弹窗", 长扫从头到尾没进过视网膜。
     *
     * block: 'center' 而不是 'nearest': 条带只有 84px 高, nearest 会把它贴在可视区边缘, 落点格仍可能
     * 被顶栏压住。inline: 'nearest' 是必须的 —— 条带自身就是横向滚动容器, 让祖先在横轴上跟着动会把
     * 上面刚算好的 scrollLeft 落位搅乱。
     *
     * 平滑还是瞬时按 reduce 档分, 而不是按 settleNow 分: 平滑滚动本身就是一段几百像素的位移动效,
     * 对前庭敏感人群比条带横扫更难受, 那一档必须瞬时到位。其余情况用平滑 —— 它约 300-500ms 完成,
     * 远早于 2200ms 的长扫结束, 玩家不会追着一个移动的目标看。
     */
    viewport.scrollIntoView({
      behavior: prefersReducedMotion ? 'auto' : 'smooth',
      block: 'center',
      inline: 'nearest',
    })

    setMotion({ phase: 'armed', shift: settleNow ? 0 : travel })

    /*
     * 隔一帧再改目标值: armed 那一帧必须真的成为浏览器的 before-change style, 否则它只看到一次样式
     * 变化 (0 -> -overshoot), 长扫无从起步, 静默退化成几像素的抽搐。
     *
     * 但只隔一层 rAF 不够保底 —— rAF 回调跑在当帧样式计算**之前**。此处在 rAF 里显式强制一次重排,
     * 把 armed 的位移钉成既成事实, 之后再解除上面那道 transition: none, 让长扫真的从 travel 起跑。
     * 不这么做的话, 它能不能跑起来取决于同批提交里恰好有没有别的组件顺手刷了样式 —— 那是运气不是设计。
     */
    const frame = requestAnimationFrame(() => {
      void track.offsetWidth
      track.style.transition = ''
      setMotion(settleNow ? { phase: 'landed', shift: 0 } : { phase: 'reeling', shift: -overshoot })
    })
    return () => {
      cancelAnimationFrame(frame)
      // 打断时把临时摘掉的过渡还回去, 否则下一次开箱会带着 transition:none 起跑, 长扫整个不见。
      // 用 effect 开头捕获的 track 而不是重读 ref: 清理跑的时候 ref 可能已经指向别的节点了。
      track.style.transition = ''
    }
  }, [open])

  function handleTransitionEnd(event: TransitionEvent<HTMLDivElement>): void {
    // 格子自己带 transition-colors, 冒泡上来的不是条带的位移, 两个条件都要卡。
    if (event.target !== event.currentTarget || event.propertyName !== 'transform') {
      return
    }
    setMotion((current) => {
      if (current.phase === 'reeling') {
        return { phase: 'settling', shift: 0 }
      }
      if (current.phase === 'settling') {
        return { phase: 'landed', shift: 0 }
      }
      return current
    })
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-muted-foreground text-xs">
        {`共 ${String(open.reel.length)} 格, 高亮的一格就是本次开出的皮肤`}
      </p>
      <div aria-label="开箱滚动条" className="w-full overflow-x-auto" ref={viewportRef}>
        <div
          className={`flex gap-2 pb-2 ${REEL_TRACK_MOTION[motion.phase]}`}
          onTransitionEnd={handleTransitionEnd}
          ref={trackRef}
          // translate3d 而非 translateX: 强制走合成层, 长扫期间不与 Minecraft 抢主线程。
          style={{ transform: `translate3d(${String(motion.shift)}px, 0, 0)` }}
        >
          {open.reel.map((entry, index) => {
            const isWinner = index === open.stopIndex
            return (
              /*
                格子宽度钉死 w-10 (= ItemSlot scale=1 的 size-10), 不让标签把格子撑宽。

                条带在需要补留白时会被设成 width: max-content, 那会让 flex 按内容的 max-content 定宽 ——
                落点格比别人多一个"本次开出", 于是它单独变宽 8px、文字从两行收成一行, 整条高度跟着掉 16px。
                结果是同一个条带在窄屏 (不补留白) 与宽屏 (补留白) 下高度不一样, 且落点格比邻格宽一截。
                标签是注解, 不该反过来决定几何。
              */
              <div
                className={
                  isWinner
                    ? `flex w-10 shrink-0 flex-col items-center gap-1 ${REEL_WINNER_REVEAL[motion.phase]}`
                    : 'flex w-10 shrink-0 flex-col items-center gap-1'
                }
                key={`${String(index)}-${entry.skinId}`}
                ref={isWinner ? winnerRef : null}
              >
                <ItemSlot
                  itemId={entry.gunId}
                  label={entry.displayName}
                  scale={1}
                  selected={isWinner}
                />
                {/*
                  只有落点那一格有文字, 其余格子留空但仍占住这一行的高度 (min-h-4) ——
                  格子编号 0/1/2… 是开发味, 玩家不需要知道自己开出的是第几格; 而直接不渲染这个 span
                  会让非落点格矮一截, 整条 reel 参差不齐。

                  必须是**两个字**: 格子宽钉死在 40px, 而 text-xs 下一个汉字 12px, 一行只放得下三个。
                  四字词 (原先写的"本次开出") 会断成 3+1, 下面孤零零挂一个字, 一眼就能看出没排好。
                */}
                <span className="min-h-4 font-medium text-brand text-xs">
                  {isWinner ? '开出' : ''}
                </span>
              </div>
            )
          })}
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
                  content={`${skin.displayName} · 已持有 ${String(skin.ownedCount)}`}
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
  const [toast, setToastValue] = useState<PanelToast | null>(null)
  /*
   * 回执的实例序号, 只用来当 React key。理由见 kit/Feedback.tsx 的退场闸门:
   * 退场那 140ms 内被同一句文案顶替时, 组件分辨不出这是新的一条, 旧的退场定时器会把它吞掉。
   */
  const toastSeqRef = useRef(0)
  const setToast = (next: PanelToast | null): void => {
    toastSeqRef.current += 1
    setToastValue(next)
  }
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

  /*
   * 本次调用里必须用这个派生值, 不能直接用 snapshot。
   *
   * 上面那句渲染期 setState 是 React 认可的写法, 但它**不会中断当前这次函数调用** —— React 要等函数
   * 返回之后才重跑。于是在这一遍里 snapshot 仍然是 null, 执行会一路走到下面 "snapshot === null" 那句
   * throw, 直接把整页炸成白屏, 而 React 根本没机会重跑。
   *
   * 这不是理论风险: 首屏 case.state 一 resolve 就必现, 开箱面板自像素风换皮那轮起就是打不开的
   * (throw 与渲染期 setState 这对组合在 HEAD 里已经存在)。
   */
  const readySnapshot = stateQuery.status === 'ready' ? stateQuery.data : snapshot

  /**
   * 音效是旁路: 它失败不该把一次已经成功的开箱变成失败 (钱已经扣了)。但也不静默吞掉 ——
   * 完整错误留在控制台 (保留堆栈), 面板上另给一条 info 提示, 与 lib/i18n.ts 处理显示名解析失败同一纪律。
   */
  const playCue = (cue: CaseSoundCue): void => {
    callMock('client.playCaseSound', { cue }).catch((error: unknown) => {
      console.error('[case] 音效播放失败:', error)
      setToast({ tone: 'info', message: '音效播放失败, 不影响这次开箱' })
    })
  }

  /*
   * 结果浮层的开启时机: 正常路径由 ReelStrip 落定后回调触发, 这里再挂一道兜底定时器。
   *
   * 兜底不是防御性编程的洁癖 —— 落定信号来自 transitionend, 而它在"元素被切走/过渡被打断/浏览器
   * 干脆没跑这条过渡"时都不会到。少了兜底, 那些情况下玩家花了钱却永远看不到自己开出了什么,
   * 这是本页最不能接受的失败模式。时长取长扫 + 落定 + 余量。
   */
  const revealTimerRef = useRef<number | null>(null)

  const clearRevealTimer = (): void => {
    if (revealTimerRef.current !== null) {
      window.clearTimeout(revealTimerRef.current)
      revealTimerRef.current = null
    }
  }

  const revealResult = (): void => {
    clearRevealTimer()
    setResultOpen(true)
  }

  const armResultReveal = (): void => {
    clearRevealTimer()
    revealTimerRef.current = window.setTimeout(revealResult, RESULT_REVEAL_FALLBACK_MS)
  }

  // 卸载时清掉在途的兜底定时器, 免得对已卸载组件 setState。
  useEffect(() => clearRevealTimer, [])

  const runOpen = async (): Promise<void> => {
    const openingId = retryOpeningId ?? createOpeningId()
    setRetryOpeningId(openingId)
    setOpening(true)
    setFailure(null)
    /*
     * 起手先把浮层收起来, 让"这一次开箱的揭晓"重新变成一件没交付过的事。
     *
     * 不收的话有两条残留:
     * 1. 上一次长扫还没落定时又点了开箱 —— 上一次的落定回调把浮层开起来, 而本次结果一回来就直接换进
     *    那个已经开着的浮层, 零长扫剧透; 与此同时本次的条带在遮罩后面空跑一遍。这正是让浮层等落定
     *    想消灭的失败模式, 只是换了个入口回来。
     * 2. 兜底定时器先开了浮层, 玩家把它关掉, 随后落定回调再把它弹回来。
     * 两条同一个根因: revealResult 只认"开", 不认"这一次的揭晓是否已经交付过"。
     */
    setResultOpen(false)
    playCue('unlock')
    try {
      // caseId 显式省略而不是传 null: 服务端对显式 null 抛 INVALID_REQUEST, 缺省才落到 "founders"。
      const result = await callMock('case.open', { openingId })
      setLastOpen(result)
      /*
       * 结果浮层**不再**与长扫同时打开。
       *
       * 同时打开等于这段动效白做: 浮层的遮罩 (fixed inset-0 z-50 + backdrop-blur) 会把页内的条带
       * 全程盖住, 而浮层本身已经把开出的皮肤直接摆在脸上 —— 玩家既看不见长扫, 也早就知道结果了。
       * 改成等条带落定 (ReelStrip 的 onLanded) 再开, 长扫才真的是"揭晓"而不是装饰。
       *
       * 复播与 reduce 档不受影响: 那两种情况 ReelStrip 会直接进 landed, onLanded 当帧就到。
       */
      armResultReveal()
      setSelectedAssetId(result.result.assetId)
      setRetryOpeningId(null)
      playCue(RARITY_SOUND[result.result.rarity])
      setToast({
        tone: result.replayed ? 'info' : 'success',
        message: result.replayed
          ? `已恢复上一次的开箱结果: ${result.result.displayName} (未重复扣费)`
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
      setToast({ tone: 'success', message: '皮肤已应用到手持枪械' })
    } catch (error: unknown) {
      setFailure(describeFailure(error))
    } finally {
      setApplying(false)
    }
  }

  const toastNode =
    toast === null ? null : (
      <FeedbackAlert
        key={toastSeqRef.current}
        message={toast.message}
        onDismiss={() => {
          setToast(null)
        }}
        tone={toast.tone}
      />
    )

  if (readySnapshot === null && stateQuery.status === 'loading') {
    return (
      <section className="flex flex-col gap-4">
        <Panel>
          <LoadingBlock label="正在读取武器箱状态" size="lg" />
        </Panel>
      </section>
    )
  }

  // 首屏就失败时整页只剩错误态; 已经有快照时错误只作为一条横幅贴在页内 (见下方), 不推翻已经画出来的东西。
  if (readySnapshot === null && stateQuery.status === 'error') {
    return (
      <section className="flex flex-col gap-4">
        <ErrorBlock
          message={stateQuery.error.message}
          {...(isMockActive()
            ? {
                code:
                  stateQuery.error instanceof WebUiCallError
                    ? `case.state / code ${String(stateQuery.error.code)}`
                    : 'case.state',
              }
            : {})}
          onRetry={stateQuery.reload}
        />
      </section>
    )
  }

  if (readySnapshot === null) {
    // status 已不是 loading/error 却仍无快照, 只可能是 useMockAction 的契约破了, 不在这里造一个空箱子糊过去。
    throw new Error('case.state 既未加载中也未失败, 却没有可渲染的快照')
  }

  const state = readySnapshot

  if (!state.enabled) {
    return (
      <section className="flex flex-col gap-4">
        <EmptyBlock
          title="开箱当前不可用"
          hint={
            isMockActive()
              ? 'enabled = 配置开关 AND tacz 已加载 AND 资源包已注册, 三者任一为假即关闭'
              : '服务器暂时关闭了开箱功能'
          }
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
              aria-label="刷新武器箱状态"
              loading={stateQuery.status === 'loading'}
              onClick={stateQuery.reload}
              size="sm"
              variant="outline"
            >
              <RefreshCwIcon />
              刷新
            </Button>
          </>
        }
        {...(isMockActive()
          ? // caseId 对玩家没有意义, 只在假数据模式下露出来便于核对 (生产构建里恒为 false)。
            { description: `caseId = ${state.caseId}` }
          : {})}
        title={state.displayName}
      >
        <div className="flex flex-col gap-4">
          {stateQuery.status === 'error' ? (
            <FeedbackAlert
              message={stateQuery.error.message}
              title="刷新失败, 下面显示的仍是上一次的数据"
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
              label="单次开箱扣费 (两种货币各扣一份)"
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
                  {openable === null ? '价格异常' : `${String(openable)} 次`}
                </Tag>
              }
            />
            <Hint
              content={
                affordable
                  ? '开箱会同时扣除两种货币, 且无法撤销'
                  : '两种货币任一不足都开不了箱'
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
                {failure !== null && retryOpeningId !== null ? '重试 (不会重复扣费)' : '开箱'}
              </Button>
            </Hint>
          </div>

          {failure === null ? null : <FailurePanel failure={failure} />}
        </div>
      </Panel>

      <Panel
        description="各档掉率由服务器下发, 下方同时给出这张表的校验结果"
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
          description="结果由服务器判定, 高亮的一格就是你开出的那件"
          title="本次开箱结果"
        >
          <ReelStrip onLanded={revealResult} open={lastOpen} />
        </Panel>
      )}

      <Panel
        description={
          state.ownedTotal > ownedShown.length
            ? `共 ${String(state.ownedTotal)} 件, 这里只列出其中 ${String(ownedShown.length)} 件`
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
                <span className="text-muted-foreground text-xs">{RARITY_LABEL[selectedAsset.rarity]}</span>
                {/* assetId / displayId 只有排查问题时用得上, 生产构建里这两行不存在。 */}
                {isMockActive() ? (
                  <>
                    <span className="text-muted-foreground text-xs">{`assetId ${selectedAsset.assetId}`}</span>
                    <span className="text-muted-foreground text-xs">{`displayId ${selectedAsset.displayId}`}</span>
                  </>
                ) : null}
              </div>
              {appliedAssetId === selectedAsset.assetId ? <Tag tone="success">已应用</Tag> : null}
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
                <DialogDescription>以下是本次开出的皮肤</DialogDescription>
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
                    ? '当前没有交易限制'
                    : `交易锁定至 ${formatMoment(lastOpen.result.tradeLockedUntil)}`}
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
