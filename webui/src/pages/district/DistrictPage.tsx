import type { ReactElement } from 'react'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { EmptyBlock, ErrorBlock, LoadingBlock, Panel, TabBar, type TabItem } from '@/components/kit'
import { invalidateAll } from '@/lib/refresh'
import { districtPreviewIdentity, getWorld, subscribeWorld, useMockAction } from '@/mock'
import type { DistrictResidency, DistrictSummary, PlotFriendship } from '@/lib/types'
import { AdminActionsPanel, ArchivedDistrictsPanel, DistrictOverviewPanel } from './AdminPanels'
import {
  AbilityCard,
  type AbilityPermissions,
  type AbilityPlots,
  AdminOnlyCard,
  DistrictInfoPanel,
  IdentityBar,
  ResidencyCard,
} from './DistrictCards'
import { DistrictLogPanel } from './DistrictLogPanel'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import { PermissionMatrix } from './PermissionMatrix'
import { PermissionSettings } from './PermissionSettings'
import { PlotDetail } from './PlotDetail'
import { DistrictPlotsTab } from './PlotList'
import { PreviewRoleSwitcher } from './PreviewRoleSwitcher'
import { PublicDirectory } from './PublicDirectory'
import { ResidentManager } from './ResidentManager'

/**
 * 自管区 (学院领地)。
 *
 * 依赖的真契约 (lib/types.ts 自管区一节, 服务端 com.miningdim.district.web.DistrictWebUiActions, 接线清单 K 组)。
 * 开发构建没有宿主时由 lib/bridge.mock 转给 mock/district-handlers.ts 的假世界回答, 页首的预览身份切换器只在那时出现;
 * 装进游戏时走真服。导航入口由服务端决定 (TabletShell 的 hubGated): 只在服务端经 hub.panels 报告自管区功能生效时出现,
 * 开发构建 (无宿主) 的假后端恒按生效下发 (docs/District_Backend_Design.md 20.9):
 *   district.state            页首数据: 服务端判定的身份 + 我的居住权 (含我的地块) + 各自管区公开摘要 (含地块块数)
 *                             + 我是哪几块地的朋友
 *   district.detail           某个自管区的详情; 住户名单与操作记录只下发给区务长与管理员
 *   district.addResident / district.removeResident      区务长与管理员 (移出有地块的住户, 地块冻结 7 天;
 *                             违反区规移出的, TA 在本区别人地块的朋友身份暂停)
 *   admin.district.retrySync / setWarden / delete       仅管理员 (删除 = 只解除与学院的绑定)
 *   admin.district.archive    已解绑自管区的归档记录, 仅管理员
 *   district.permissions      某区**公共区域**的权限开关表; 按身份裁剪 (外人拿不到住户列; 外人列与全区列对谁都公开)
 *   district.setPermission / district.resetPermissions  区务长 (区域规则除外) 与管理员
 *   district.plots            本区平面图与地块列表 (含划地块规则、单价与我能不能买); 按身份裁剪, 外人拒绝
 *   plot.create / resize / delete                       区务长 (本区空置地块) 与管理员 (有户主的记为代改)
 *   plot.buy                  没有地块的本区住户, 直接买, 先到先得
 *   admin.district.setPlotPricing / setPurchaseOpen, admin.plot.unfreeze / reclaimNow   仅管理员
 *   plot.detail / setPermission / resetPermissions / addFriend / removeFriend / restoreFriend
 *                             户主本人与管理员 (代改); 区务长改别人的地块一律拒绝
 *
 * 四种视角由**服务端下发的 viewer.role** 决定, 页面不自己判身份:
 *   外人    你还不是住户 + 各学院公开信息 (含"外人可以…"、地块块数、我是哪块地的朋友、个人圈地规则)
 *   住户    我的居住权 / 我能做什么 (公共区域 · 我的地块 · 别的地块) / 本区信息 + 本区地块 (平面图与列表; 没有地块的
 *           可以直接买); 有地块的另有"我的地块"页签
 *   区务长  以上 + 住户管理 + 本区地块 (另看朋友数与生效状态; 划新地块、调整或删除空置地块) + 公共区域权限
 *           + 仅管理员 (锁定) + 操作记录
 *   管理员  自管区总览 (+ 已解绑的归档) -> 点进某区: 区务长能看到的一切 + 区域规则 + 打开 / 代改任一地块
 *           + 冻结处置 + 管理员操作 (含地块定价与开放购买)
 * 四种视角底部都有一张"权限说明"对照表。
 *
 * 数据刷新: 每个写操作成功后由发起方自己调 invalidateAll (同 QuestsPage / AdminPage), 不靠假世界的变化 ——
 * 所以接线 (把 action 从 PLANNED_ACTIONS 摘掉、改走真契约) 时面板代码一行不动也照样刷新。
 *
 * 不做的: 税收 (待拍板); 退地、转让 (规则还在定); 地块的 Y 轴高度 (地块默认整列)。
 */

const MEMBER_TAB_IDS = ['overview', 'myPlot', 'residents', 'plots', 'permissions', 'log'] as const
const ADMIN_TAB_IDS = ['overview', 'residents', 'plots', 'permissions', 'admin', 'log'] as const
type MemberTabId = (typeof MEMBER_TAB_IDS)[number]
type AdminTabId = (typeof ADMIN_TAB_IDS)[number]

function isMemberTab(id: string): id is MemberTabId {
  return (MEMBER_TAB_IDS as readonly string[]).includes(id)
}

function isAdminTab(id: string): id is AdminTabId {
  return (ADMIN_TAB_IDS as readonly string[]).includes(id)
}

function readPreviewIdentity(): string {
  return districtPreviewIdentity(getWorld())
}

/**
 * 上一次本页看到的预览身份。放在模块级而不是 ref: 在别的页拨了顶栏的 OP 视图再切回来, 本页是重新挂载的,
 * ref 会从新身份起步而看不出"换过人", 缓存里那份 15 秒内的旧身份回执就会原样画出来。
 */
let lastSeenPreviewIdentity: string | null = null

/**
 * 预览身份一换就全量作废查询 (仅假数据模式下会发生; 真服身份永远是发请求的那个人, 签名恒不变)。
 *
 * 只盯身份, 不盯 world.revision: 写操作的刷新由各自的发起方负责, 这里若再跟着世界版本号作废, 就等于抹掉
 * useMockAction "不随世界自动重查" 那条刻意划的边界 (见 mock/useMockWorld.ts)。
 * 作废而不是逐个 reload: 缓存里还躺着别的身份取过的 district.detail, 只 reload 挂载中的那几条,
 * 切回去时会先闪出上一个身份的名单。
 */
function useInvalidateOnPreviewIdentityChange(): void {
  const identity = useSyncExternalStore(subscribeWorld, readPreviewIdentity, readPreviewIdentity)
  useEffect(() => {
    const previous = lastSeenPreviewIdentity
    lastSeenPreviewIdentity = identity
    if (previous !== null && previous !== identity) {
      invalidateAll()
    }
  }, [identity])
}

export function DistrictPage(): ReactElement {
  useInvalidateOnPreviewIdentityChange()
  const state = useMockAction('district.state', {})

  if (state.status === 'error') {
    return (
      <section className="flex flex-col gap-4">
        <ErrorBlock code="district.state" message={state.error.message} onRetry={state.reload} />
      </section>
    )
  }
  if (state.data === null) {
    return (
      <section className="flex flex-col gap-4">
        <LoadingBlock label="正在读取自管区" size="lg" />
      </section>
    )
  }

  const { viewer, residency, districts, friendOf } = state.data
  const context =
    viewer.role === 'admin' ? `共 ${String(districts.length)} 个自管区` : (residency?.districtName ?? null)

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复。 */}
      <PreviewRoleSwitcher switching={state.refreshing} viewerName={viewer.playerName} viewerRole={viewer.role} />
      <IdentityBar context={context} playerName={viewer.playerName} role={viewer.role} />

      {viewer.role === 'outsider' ? <PublicDirectory districts={districts} friendOf={friendOf} /> : null}
      {viewer.role === 'resident' || viewer.role === 'warden' ? (
        residency === null ? (
          // 服务端说你是住户/区务长却不给居住权: 契约自相矛盾, 如实报出来, 不编一张空卡。
          <ErrorBlock code="district.state" message="服务器没有返回你的居住权记录" onRetry={state.reload} />
        ) : (
          <MemberView
            friendOf={friendOf.filter((friendship) => friendship.districtId === residency.districtId)}
            key={residency.districtId}
            residency={residency}
            stateRefreshing={state.refreshing}
          />
        )
      ) : null}
      {viewer.role === 'admin' ? <AdminView districts={districts} stateRefreshing={state.refreshing} /> : null}

      <PermissionMatrix viewerRole={viewer.role} />
    </section>
  )
}

/**
 * 详情取数失败时画什么。
 *
 * 页首的 district.state 正在重拉时 (切了预览身份 / 写操作后的全量作废 / 面板重开), 挂着的旧视角会以**新身份**
 * 重查自己那份详情, 两条请求各自往返、谁先回来不一定。详情抢先回来的"无权查看 / 找不到这个自管区"只是过渡态 ——
 * state 一落定, 这个视角就会被换掉或卸载。故 state 在途时先画加载, 等它落定后仍然失败才报错, 免得每切一次身份
 * 都闪一下红色错误块。
 */
function DetailFailure({
  stateRefreshing,
  message,
  onRetry,
}: {
  stateRefreshing: boolean
  message: string
  onRetry?: (() => void) | undefined
}): ReactElement {
  if (stateRefreshing) {
    return <LoadingBlock label="正在刷新" />
  }
  return <ErrorBlock code="district.detail" message={message} onRetry={onRetry} />
}

// ============================================================
// 住户 / 区务长
// ============================================================

function MemberView({
  residency,
  friendOf,
  stateRefreshing,
}: {
  residency: DistrictResidency
  /** 本区里户主把我加成朋友的地块。 */
  friendOf: readonly PlotFriendship[]
  stateRefreshing: boolean
}): ReactElement {
  const detail = useMockAction('district.detail', { districtId: residency.districtId })
  // 与公共区域权限页签同一个缓存键: 区务长在页签里改了开关, 回到概况时"我能做什么"已是新的。
  const permissions = useMockAction('district.permissions', { districtId: residency.districtId })
  // 与"本区地块"页签同一个缓存键。住户也要它: "别的地块"按各户主给其他住户开了什么生成。
  const plots = useMockAction('district.plots', { districtId: residency.districtId })
  const [tab, setTab] = useState<MemberTabId>('overview')

  if (detail.status === 'error') {
    return <DetailFailure message={detail.error.message} onRetry={detail.reload} stateRefreshing={stateRefreshing} />
  }
  if (detail.data === null) {
    return <LoadingBlock label="正在读取本区信息" />
  }

  const abilityPermissions: AbilityPermissions =
    permissions.status === 'error'
      ? { status: 'error', message: permissions.error.message, onRetry: permissions.reload }
      : permissions.data === null
        ? { status: 'loading' }
        : { status: 'ready', data: permissions.data }
  const abilityPlots: AbilityPlots =
    plots.status === 'error'
      ? { status: 'error', message: plots.error.message, onRetry: plots.reload }
      : plots.data === null
        ? { status: 'loading' }
        : { status: 'ready', data: plots.data }

  const { district, abilities, residents, log } = detail.data
  const myPlot = residency.plot
  const overview = (
    <>
      <div className="grid gap-4 lg:grid-cols-2">
        <ResidencyCard
          buyBlock={abilityPlots.status === 'ready' ? abilityPlots.data.market.viewerBlock : undefined}
          residency={residency}
        />
        <AbilityCard
          abilities={abilities}
          friendOf={friendOf}
          hideAdminOnly={residents !== null}
          myPlot={myPlot}
          permissions={abilityPermissions}
          plots={abilityPlots}
          wardenName={district.wardenName}
        />
      </div>
      {residents === null ? null : <AdminOnlyCard abilities={abilities} />}
      <DistrictInfoPanel district={district} />
    </>
  )

  // 页签是否出现跟着服务端下发的数据与能力走, 不按 role 推。
  // "我的地块"只给户主本人 (区务长本人有地块同样有); 住户的名单与记录服务端不下发 (null), 那两个页签也就没有。
  // "本区地块"住户也有 (只看编号、户主、面积); 朋友数与生效状态跟着 abilities.viewPlotStatus 走。
  const tabs: readonly TabItem[] = [
    { id: 'overview', label: '本区概况' },
    ...(myPlot === null ? [] : [{ id: 'myPlot', label: '我的地块' }]),
    ...(residents === null
      ? []
      : [{ id: 'residents', label: '住户管理', badge: <TabCount value={residents.length} /> }]),
    ...(abilities.viewPlotList
      ? [{ id: 'plots', label: '本区地块', badge: <TabCount value={district.plotCount} /> }]
      : []),
    ...(abilities.managePermissions ? [{ id: 'permissions', label: '公共区域权限' }] : []),
    ...(log === null ? [] : [{ id: 'log', label: '操作记录', badge: <TabCount value={log.length} /> }]),
  ]

  // 只有概况一页 (服务端没给任何别的能力): 不需要页签。
  if (tabs.length === 1) {
    return <div className="flex flex-col gap-4">{overview}</div>
  }

  // 能力被收回 (如刚被撤了区务长) 时, 停在一个已不存在的页签上会整块空白, 落回概况。
  const activeTab: MemberTabId = tabs.some((item) => item.id === tab) ? tab : 'overview'

  return (
    <div className="flex flex-col gap-4">
      <TabBar
        activeId={activeTab}
        onChange={(id) => {
          if (isMemberTab(id)) {
            setTab(id)
          }
        }}
        tabs={tabs}
      />
      {activeTab === 'overview' ? overview : null}
      {activeTab === 'myPlot' && myPlot !== null ? (
        <PlotDetail districtId={district.districtId} key={myPlot.plotId} plotId={myPlot.plotId} />
      ) : null}
      {activeTab === 'residents' && residents !== null ? (
        <ResidentManager
          abilities={abilities}
          academyFullName={district.academyFullName}
          academyName={district.academyName}
          districtId={district.districtId}
          districtName={district.displayName}
          residents={residents}
        />
      ) : null}
      {activeTab === 'plots' ? (
        <DistrictPlotsTab abilities={abilities} districtId={district.districtId} districtName={district.displayName} />
      ) : null}
      {activeTab === 'permissions' ? (
        <PermissionSettings districtId={district.districtId} districtName={district.displayName} />
      ) : null}
      {activeTab === 'log' && log !== null ? <DistrictLogPanel log={log} /> : null}
    </div>
  )
}

function TabCount({ value }: { value: number }): ReactElement {
  return <span className="text-muted-foreground text-xs tabular-nums">{value}</span>
}

// ============================================================
// 管理员
// ============================================================

/**
 * 刚删掉、但页首那份摘要还没重拉回来的自管区。只对**删除时手上那份** districts 生效 (按引用比):
 * 下一份回执一到 (每次都是新数组), 这层遮挡自动失效, 以服务端为准 —— 包括"重置假数据"之后它又回来了的情形。
 */
interface HiddenAfterDelete {
  districtId: string
  from: readonly DistrictSummary[]
}

function AdminView({
  districts,
  stateRefreshing,
}: {
  districts: readonly DistrictSummary[]
  stateRefreshing: boolean
}): ReactElement {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [hidden, setHidden] = useState<HiddenAfterDelete | null>(null)
  const { toast, show, clear } = useDistrictToast()
  const detailRef = useRef<HTMLDivElement>(null)

  // 删掉的那个在重拉回来之前就从总览里拿掉: 否则选中项会落回它 (它若排第一), 详情区继续画一个已经不存在的区。
  const visible =
    hidden !== null && hidden.from === districts
      ? districts.filter((district) => district.districtId !== hidden.districtId)
      : districts
  // 选中项被删掉 (或还没选过) 时落到第一个自管区, 不留一个指向已不存在的区的选中态。
  const effectiveId = visible.some((district) => district.districtId === selectedId)
    ? selectedId
    : (visible[0]?.districtId ?? null)

  function select(districtId: string): void {
    setSelectedId(districtId)
    // 总览表在上、详情在下: 平板默认尺寸下详情整块落在折叠线以下, 点了一行却看不到任何变化,
    // 服主会以为没点上。点完把详情滚到可视区顶端 (同 CasePage 的条带落位, 减少动效档瞬时到位)。
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    detailRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }

  return (
    <div className="flex flex-col gap-4">
      <DistrictOverviewPanel districts={visible} onSelect={select} selectedId={effectiveId} />
      <ArchivedDistrictsPanel stateRefreshing={stateRefreshing} />
      <div className="flex scroll-mt-4 flex-col gap-4" ref={detailRef}>
        <DistrictToastSlot onClear={clear} toast={toast} />
        {effectiveId === null ? (
          <Panel>
            <EmptyBlock hint="先在游戏里圈出学院用地（Flan 管理员领地）并登记给学院" title="服务器上还没有自管区" />
          </Panel>
        ) : (
          <AdminDistrictDetail
            districtId={effectiveId}
            key={effectiveId}
            onDeleted={(message) => {
              setHidden({ districtId: effectiveId, from: districts })
              setSelectedId(null)
              show('success', message)
            }}
            stateRefreshing={stateRefreshing}
          />
        )}
      </div>
    </div>
  )
}

function AdminDistrictDetail({
  districtId,
  onDeleted,
  stateRefreshing,
}: {
  districtId: string
  onDeleted: (message: string) => void
  stateRefreshing: boolean
}): ReactElement {
  const detail = useMockAction('district.detail', { districtId })
  const [tab, setTab] = useState<AdminTabId>('overview')

  if (detail.status === 'error') {
    return <DetailFailure message={detail.error.message} onRetry={detail.reload} stateRefreshing={stateRefreshing} />
  }
  if (detail.data === null) {
    return <LoadingBlock label="正在读取自管区详情" />
  }

  const { district, abilities, residents, log } = detail.data
  if (residents === null || log === null) {
    // 管理员一定拿得到名单; 拿不到说明回执与身份对不上。state 在途时多半是 OP 视图刚被关掉而本块抢先以新身份
    // 重查回来 (过渡态, 同 DetailFailure); state 落定后仍然如此才如实报出。
    return (
      <DetailFailure
        message="服务器没有返回住户名单，当前身份可能已不是管理员"
        stateRefreshing={stateRefreshing}
      />
    )
  }

  const failedCount = residents.filter((resident) => resident.syncStatus === 'failed').length
  const tabs: readonly TabItem[] = [
    { id: 'overview', label: '本区概况' },
    { id: 'residents', label: '住户管理', badge: <TabCount value={residents.length} /> },
    ...(abilities.viewPlotList
      ? [{ id: 'plots', label: '本区地块', badge: <TabCount value={district.plotCount} /> }]
      : []),
    ...(abilities.managePermissions ? [{ id: 'permissions', label: '公共区域权限' }] : []),
    {
      id: 'admin',
      label: '管理员操作',
      badge: failedCount === 0 ? undefined : <span className="text-destructive text-xs tabular-nums">{failedCount}</span>,
    },
    { id: 'log', label: '操作记录', badge: <TabCount value={log.length} /> },
  ]
  const activeTab: AdminTabId = tabs.some((item) => item.id === tab) ? tab : 'overview'

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-medium text-foreground text-sm">
          {district.displayName}
          <span className="ml-2 font-normal text-muted-foreground text-xs">{district.academyFullName}</span>
        </h3>
        <TabBar
          activeId={activeTab}
          onChange={(id) => {
            if (isAdminTab(id)) {
              setTab(id)
            }
          }}
          tabs={tabs}
        />
      </div>
      {activeTab === 'overview' ? <DistrictInfoPanel district={district} /> : null}
      {activeTab === 'residents' ? (
        <ResidentManager
          abilities={abilities}
          academyFullName={district.academyFullName}
          academyName={district.academyName}
          districtId={district.districtId}
          districtName={district.displayName}
          residents={residents}
        />
      ) : null}
      {activeTab === 'plots' ? (
        <DistrictPlotsTab abilities={abilities} districtId={district.districtId} districtName={district.displayName} />
      ) : null}
      {activeTab === 'permissions' ? (
        <PermissionSettings districtId={district.districtId} districtName={district.displayName} />
      ) : null}
      {activeTab === 'admin' ? (
        <AdminActionsPanel abilities={abilities} district={district} onDeleted={onDeleted} residents={residents} />
      ) : null}
      {activeTab === 'log' ? <DistrictLogPanel log={log} /> : null}
    </div>
  )
}
