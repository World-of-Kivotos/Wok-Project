import { CheckIcon, LockIcon, MinusIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { Button, Panel, Stat, Surface, Tag, type Tone } from '@/components/kit'
import { nowMs } from '@/mock'
import type {
  DistrictAbilities,
  DistrictFixedRule,
  DistrictInfo,
  DistrictPermissionItem,
  DistrictPermissionsResult,
  DistrictPlotsResult,
  DistrictResidency,
  DistrictRole,
  DistrictSyncStatus,
  PlotBuyBlock,
  PlotFriendship,
  PlotRef,
  PlotSummary,
} from '@/lib/types'
import {
  BUY_BLOCK_TEXT,
  ROLE_LABEL,
  ROLE_SUMMARY,
  boundsSize,
  dimensionLabel,
  formatArea,
  formatBounds,
  formatDate,
  formatRelative,
  onOffLabel,
} from './format'

/**
 * 自管区页的只读卡片: 身份条 / 我的居住权 / 我能做什么 / 仅管理员 / 本区信息。
 *
 * 能力的"能 / 不能 / 锁定"一律按服务端下发的 abilities、本区开关表 (district.permissions 的住户列) 与地块列表
 * (district.plots) 画, 不按 role 自己推 —— 前端再写一份"住户能做什么"就是第二份权限规则, 服务端一改两边就对不上。
 */

// ============================================================
// 身份条
// ============================================================

export function IdentityBar({
  role,
  playerName,
  context,
}: {
  role: DistrictRole
  playerName: string
  /** 身份后面的补充, 如"千年自管区"。 */
  context: string | null
}): ReactElement {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <Tag tone="brand">{ROLE_LABEL[role]}</Tag>
      <span className="font-medium text-foreground text-sm">{playerName}</span>
      {context === null ? null : <span className="text-muted-foreground text-sm">· {context}</span>}
      <span className="text-muted-foreground text-xs">{ROLE_SUMMARY[role]}</span>
    </div>
  )
}

// ============================================================
// 我的居住权
// ============================================================

/**
 * 居住权的生效档。名单上有你 ≠ 游戏里能建: Flan 写入没成功时你不在居民组里, 游戏里按外人那一列算,
 * 这张卡不许一律说"有效"; 也不许说成"什么都不能做" —— 外人能做的事 (开门、睡床…) 你照样能做,
 * 具体哪些由"我在本区能做什么"按本区外人开关列出。
 * 待生效在真服上很少会被本人看到 (首次登录即补写), 但服务端补写也可能慢一拍, 照样如实画。
 */
const RESIDENCY_STATUS: Record<DistrictSyncStatus, { tone: Tone; label: string; notice: string | null }> = {
  synced: { tone: 'success', label: '居住权已生效', notice: null },
  pending: {
    tone: 'warning',
    label: '居住权待生效',
    notice: '服务器还没把你写进本区领地，通常登录后很快会自动生效。在那之前你在本区按外人算，只能做外人能做的事。',
  },
  failed: {
    tone: 'danger',
    label: '领地权限未生效',
    notice: '服务器没能把你写进本区领地。处理好之前你在本区按外人算，只能做外人能做的事。请联系区务长或管理员。',
  },
}

/**
 * 还没有地块时的那一句: 能不能买、为什么不能买。按服务端下发的 market.viewerBlock 写 (与「本区地块」页签顶上
 * 那句同源), 不一律说"可以直接买" —— 原来的地块还冻结着的人 (被移出后又被加回来) 买不了 (HAS_FROZEN_PLOT)。
 * undefined = 地块列表还没读到或读失败: 只指个去处, 不猜。
 */
function noPlotText(block: PlotBuyBlock | null | undefined): string {
  if (block === undefined) {
    return '能不能买，看「本区地块」'
  }
  if (block === null) {
    return '本区开放购买中：在「本区地块」里直接买一块空置的，先到先得，一人一块'
  }
  if (block === 'PURCHASE_CLOSED') {
    return '本区开放购买时，可以在「本区地块」里直接买一块空置的，先到先得，一人一块'
  }
  return BUY_BLOCK_TEXT[block]
}

export function ResidencyCard({
  residency,
  buyBlock,
}: {
  residency: DistrictResidency
  /** 本区地块列表里的 market.viewerBlock; undefined = 还没读到。只在我还没有地块时用得上。 */
  buyBlock: PlotBuyBlock | null | undefined
}): ReactElement {
  const status = RESIDENCY_STATUS[residency.syncStatus]
  return (
    <Panel
      actions={<Tag tone={status.tone}>{status.label}</Tag>}
      description={`被加入${residency.academyFullName}，就获得了本学院自管区的居住权`}
      title="我的居住权"
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-0.5">
          <span className="font-medium text-base text-foreground">{residency.districtName}</span>
          <span className="text-muted-foreground text-xs">{residency.academyFullName}</span>
        </div>
        {status.notice === null ? null : (
          <Surface tone="warning">
            <p className="text-foreground text-sm">{status.notice}</p>
          </Surface>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Stat
            hint={formatRelative(residency.grantedAt, nowMs())}
            label="获得时间"
            value={formatDate(residency.grantedAt)}
          />
          <Stat label="由谁添加" value={residency.grantedBy} />
          <Stat
            hint={residency.plot === null ? noPlotText(buyBlock) : '在「我的地块」里管朋友和权限'}
            label="我的地块"
            value={residency.plot?.code ?? <span className="text-muted-foreground">还没有</span>}
          />
        </div>
        {residency.isWarden ? (
          <Surface tone="brand">
            <p className="text-foreground text-sm">
              你是本区区务长（由管理员任命，不是 OP）：可以添加、移出住户，开关公共区域的权限，在本区划新地块、调整或删除空置地块，查看地块列表和操作记录。有户主的地块由户主做主，你改不了；定价和冻结处置归管理员。
            </p>
          </Surface>
        ) : null}
      </div>
    </Panel>
  )
}

// ============================================================
// 我在本区能做什么
// ============================================================

/** 管理类能力 (与开关表无关, 按 abilities 画)。 */
interface ManageLine {
  label: string
  granted: (abilities: DistrictAbilities) => boolean
  /** 做不了时的去处提示。 */
  otherwise: string
  /** 仅管理员的能力: 区务长那侧改由"仅管理员"卡单独画成锁定态, 这里不重复列。 */
  adminOnly: boolean
}

const MANAGE_LINES: readonly ManageLine[] = [
  { label: '添加、移出住户', granted: (a) => a.manageResidents, otherwise: '找本区区务长', adminOnly: false },
  {
    label: '开关公共区域的权限',
    granted: (a) => a.managePermissions,
    otherwise: '区务长和管理员可改',
    adminOnly: false,
  },
  {
    label: '查看住户名单和操作记录',
    granted: (a) => a.viewRoster,
    otherwise: '区务长和管理员可看',
    adminOnly: false,
  },
  {
    label: '查看本区地块和户主',
    granted: (a) => a.viewPlotList,
    otherwise: '本区住户可看',
    adminOnly: false,
  },
  {
    label: '查看各地块的朋友数和生效状态',
    granted: (a) => a.viewPlotStatus,
    otherwise: '区务长和管理员可看',
    adminOnly: false,
  },
  {
    label: '划新地块，调整、删除空置地块',
    granted: (a) => a.managePlots,
    otherwise: '区务长和管理员可做',
    adminOnly: false,
  },
  { label: '圈地、改自管区范围', granted: (a) => a.editClaim, otherwise: '仅管理员', adminOnly: true },
  { label: '任命区务长', granted: (a) => a.appointWarden, otherwise: '仅管理员', adminOnly: true },
  { label: '改 PvP、爆炸等区域规则', granted: (a) => a.manageRegionRules, otherwise: '仅管理员', adminOnly: true },
  { label: '给地块定价、开放购买', granted: (a) => a.managePlotMarket, otherwise: '仅管理员', adminOnly: true },
  { label: '解除冻结、立即收回地块', granted: (a) => a.manageFrozenPlots, otherwise: '仅管理员', adminOnly: true },
  { label: '打开、代改别人的地块', granted: (a) => a.overridePlots, otherwise: '仅管理员', adminOnly: true },
]

/** 某份查询在这张卡里的三种状态 (调用方把查询状态折成它)。 */
export type AbilitySource<T> =
  | { status: 'loading' }
  | { status: 'error'; message: string; onRetry: () => void }
  | { status: 'ready'; data: T }

export type AbilityPermissions = AbilitySource<DistrictPermissionsResult>
export type AbilityPlots = AbilitySource<DistrictPlotsResult>

interface DeniedLine {
  key: string
  label: string
  otherwise: string
}

/**
 * 我在本区能做什么, 分四段:
 *   公共区域   按本区的开关实时生成 (现有逻辑): 开着的按分组列在上面, 关着的进"不能做的"并注明是谁关的;
 *              住户列默认就关着的几项 (护农田、护宠物) 不算"被关掉", 单独一行"默认保护";
 *   我的地块   户主在自己地块里什么都能做 (改地块范围这类管理权限除外); 没有地块的按 market.viewerBlock 写能不能买
 *              (原来的地块还冻结着的买不了, 见 noPlotText);
 *   别的地块   先列户主把我加成朋友的那几块 (按朋友那一列; 被暂停的如实说); 冻结中的几块并成一行 (除管理员外谁都进不去);
 *              其余看各户主的设置: 按默认的并成一行, 户主改过"其他住户"那一列的逐块写出多开了 / 关掉了什么;
 *   管理       按 abilities。
 * 勾号只给真能做的事: 做不了的 (居住权没生效、按默认什么都不能碰、只被关掉了几项) 一律画成横杠。
 *
 * 本人的领地权限还没生效 (待生效 / 同步失败) 时 abilities.build / interact 为 false: 那时本人不在 Flan 的居民组里,
 * 游戏里按的是**外人那一列** —— 所以公共区域按外人列列出"现在能做的", 住户才有的那几项进"不能做的"并注明
 * "居住权生效后才可以"; 地块里同样按外人算。
 */
export function AbilityCard({
  abilities,
  hideAdminOnly,
  permissions,
  plots,
  myPlot,
  friendOf,
  wardenName,
}: {
  abilities: DistrictAbilities
  /** 区务长视角置真: 仅管理员的几项另有一张锁定卡, 这里不再列。 */
  hideAdminOnly: boolean
  permissions: AbilityPermissions
  plots: AbilityPlots
  /** 我在本区的地块 (来自居住权); null = 还没有。 */
  myPlot: PlotRef | null
  /** 本区里户主把我加成朋友的地块 (来自 district.state, 调用方已按本区筛过)。 */
  friendOf: readonly PlotFriendship[]
  /** 本区区务长; null = 暂无, 那时开关只可能是管理员动的。 */
  wardenName: string | null
}): ReactElement {
  const effective = abilities.build || abilities.interact
  const data = permissions.status === 'ready' ? permissions.data : null
  const regionItems =
    data === null ? [] : data.groups.filter((group) => group.scope === 'region').flatMap((group) => group.items)

  const grantedManage = MANAGE_LINES.filter((line) => line.granted(abilities))
  const deniedManage: DeniedLine[] = MANAGE_LINES.filter(
    (line) => !line.granted(abilities) && !(hideAdminOnly && line.adminOnly),
  ).map((line) => ({ key: line.label, label: line.label, otherwise: line.otherwise }))

  return (
    <Panel description="领地保护由服务器执行，下面按本区当前的设置列出" title="我在本区能做什么">
      <div className="flex flex-col gap-5">
        <AbilitySection hint="地块以外的地方，按区务长的设置" title="公共区域">
          <PublicAreaAbilities
            abilities={abilities}
            effective={effective}
            permissions={permissions}
            wardenName={wardenName}
          />
        </AbilitySection>

        <AbilitySection hint="户主自己做主" title="我的地块">
          {myPlot === null ? (
            <p className="text-muted-foreground text-sm">
              {`你还没有地块：${noPlotText(plots.status === 'ready' ? plots.data.market.viewerBlock : undefined)}。`}
            </p>
          ) : (
            <ul className="flex flex-col gap-2">
              {effective ? (
                <GrantedLine>
                  <span className="font-medium">{myPlot.code}：</span>
                  全部都能做（改地块范围这类除外）。朋友、其他住户、外人在你的地块里能做什么，在「我的地块」里自己设置。管理员（OP）可以进入所有地块并拥有全部权限，不受这些设置限制。
                </GrantedLine>
              ) : (
                <MutedLine>
                  <span className="font-medium">{myPlot.code}：</span>
                  居住权生效后才可以在这里建造；生效前你在自己的地块里也按外人算。
                </MutedLine>
              )}
            </ul>
          )}
        </AbilitySection>

        <AbilitySection hint="看各户主的设置" title="别的地块">
          <OtherPlotsAbilities
            effective={effective}
            friendOf={friendOf}
            myPlotId={myPlot?.plotId ?? null}
            plots={plots}
          />
        </AbilitySection>

        {grantedManage.length + deniedManage.length === 0 ? null : (
          <AbilitySection hint={null} title="管理">
            <ul className="flex flex-col gap-2">
              {grantedManage.map((line) => (
                <GrantedLine key={line.label}>{line.label}</GrantedLine>
              ))}
              {deniedManage.map((line) => (
                <DeniedItem key={line.key} line={line} />
              ))}
            </ul>
          </AbilitySection>
        )}

        {regionItems.length === 0 ? null : <RegionRulesLine fixedRules={data?.fixedRules ?? []} items={regionItems} />}
      </div>
    </Panel>
  )
}

function AbilitySection({
  title,
  hint,
  children,
}: {
  title: string
  hint: string | null
  children: ReactNode
}): ReactElement {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="font-medium text-foreground text-sm">
        {title}
        {hint === null ? null : <span className="ml-2 font-normal text-muted-foreground text-xs">{hint}</span>}
      </h3>
      {children}
    </section>
  )
}

function GrantedLine({ children }: { children: ReactNode }): ReactElement {
  return (
    <li className="flex items-start gap-2 text-sm">
      <CheckIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-success" />
      <span className="min-w-0 text-foreground">{children}</span>
    </li>
  )
}

/** 做不了 (或只剩被关掉的) 的一行: 横杠 + 弱化文字。勾号只留给真能做的事。 */
function MutedLine({ children }: { children: ReactNode }): ReactElement {
  return (
    <li className="flex items-start gap-2 text-sm">
      <MinusIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <span className="min-w-0 text-muted-foreground">{children}</span>
    </li>
  )
}

function DeniedItem({ line }: { line: DeniedLine }): ReactElement {
  return (
    <MutedLine>
      {line.label}
      <span className="text-xs">（{line.otherwise}）</span>
    </MutedLine>
  )
}

function SourceStatus({ source, what }: { source: AbilitySource<unknown>; what: string }): ReactElement | null {
  if (source.status === 'loading') {
    return <p className="text-muted-foreground text-xs">正在读取{what}…</p>
  }
  if (source.status === 'error') {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-destructive text-xs">
          没读到{what}：{source.message}
        </p>
        <Button onClick={source.onRetry} size="xs" variant="outline">
          重试
        </Button>
      </div>
    )
  }
  return null
}

function PublicAreaAbilities({
  abilities,
  effective,
  permissions,
  wardenName,
}: {
  abilities: DistrictAbilities
  effective: boolean
  permissions: AbilityPermissions
  wardenName: string | null
}): ReactElement {
  // 生效前游戏里按外人那一列算 (见 AbilityCard 注释)。
  const column = effective ? 'resident' : 'outsider'
  const data = permissions.status === 'ready' ? permissions.data : null
  const memberGroups = data === null ? [] : data.groups.filter((group) => group.scope === 'member')

  // 区务长自己就是开关的人, "区务长已关闭"对他说不通, 改指到公共区域权限。没有区务长时只可能是管理员关的。
  const closedText = abilities.managePermissions
    ? '在公共区域权限里关着'
    : wardenName === null
      ? '管理员已关闭'
      : '区务长已关闭'

  const grantedGroups = memberGroups
    .map((group) => ({
      groupId: group.groupId,
      label: group.label,
      items: group.items.filter((item) => item.current[column] === true),
    }))
    .filter((group) => group.items.length > 0)

  // 开关表还没回来时 memberGroups 为空, 这里自然一项不列 —— 不先画一版再跳成另一版。
  const blocked = memberGroups.flatMap((group) => group.items).filter((item) => item.current[column] !== true)
  // 住户列默认关、现在也关: 是本区的默认保护, 不是谁关掉的。
  const isDefaultProtected = (item: DistrictPermissionItem): boolean =>
    item.defaults.resident === false && item.current.resident === false
  const protectedLabels = blocked.filter(isDefaultProtected).map((item) => item.label)
  // 生效前才拦着的 (住户列开着、外人列没开): 合成一行, 不然十几行"生效后才可以"会把真正被关掉的几项淹没。
  const awaitingLabels = effective
    ? []
    : blocked.filter((item) => item.current.resident === true).map((item) => item.label)
  const denied: DeniedLine[] = [
    ...(awaitingLabels.length === 0
      ? []
      : [{ key: 'awaiting-residency', label: awaitingLabels.join('、'), otherwise: '居住权生效后才可以' }]),
    ...blocked
      .filter((item) => item.current.resident !== true && !isDefaultProtected(item))
      .map((item) => ({ key: item.permissionId, label: item.label, otherwise: closedText })),
  ]

  return (
    <div className="flex flex-col gap-2">
      <SourceStatus source={permissions} what="本区公共区域的权限设置" />

      {!effective && data !== null ? (
        <p className="text-foreground text-sm">
          {grantedGroups.length === 0
            ? '生效前你在本区按外人算，公共区域里外人什么都不能碰。'
            : '生效前你在本区按外人算，可以：'}
        </p>
      ) : null}

      {grantedGroups.length === 0 ? null : (
        <ul className="flex flex-col gap-2">
          {grantedGroups.map((group) => (
            <GrantedLine key={group.groupId}>
              <span className="font-medium">{group.label}：</span>
              {group.items.map((item) => item.label).join('、')}
            </GrantedLine>
          ))}
        </ul>
      )}

      {denied.length === 0 ? null : (
        <ul className="flex flex-col gap-2">
          {denied.map((line) => (
            <DeniedItem key={line.key} line={line} />
          ))}
        </ul>
      )}

      {protectedLabels.length === 0 ? null : (
        <p className="text-muted-foreground text-xs">默认保护，不开放：{protectedLabels.join('、')}</p>
      )}
    </div>
  )
}

/** 一块改过"其他住户"那一列的地, 相对默认多开了 / 关掉了什么。条目名多以动词开头, 一律加「」免得和前面的动词粘在一起。 */
function residentDiff(
  plot: PlotSummary,
  defaults: readonly string[],
): { text: string; opensMore: boolean } {
  const extra = plot.openToResidents.filter((label) => !defaults.includes(label))
  const missing = defaults.filter((label) => !plot.openToResidents.includes(label))
  const parts = [
    extra.length === 0 ? null : `另外开了「${extra.join('」「')}」`,
    missing.length === 0 ? null : `关掉了「${missing.join('」「')}」`,
  ].filter((part): part is string => part !== null)
  return { text: parts.join('；'), opensMore: extra.length > 0 }
}

/** 户主把我加成朋友的一块地。朋友组没写进去 (待生效 / 未生效) 时如实说, 不打勾。 */
function FriendPlotLine({ friendship }: { friendship: PlotFriendship }): ReactElement {
  const head = (
    <span className="font-medium">
      {friendship.code}（户主 {friendship.ownerName}）：
    </span>
  )
  if (friendship.suspended) {
    return (
      <MutedLine>
        {head}
        你的朋友身份已暂停，户主恢复之前不按朋友算。
      </MutedLine>
    )
  }
  if (friendship.syncStatus === 'synced') {
    return (
      <GrantedLine>
        {head}
        你是这块地的朋友，按“朋友”那一列算。
      </GrantedLine>
    )
  }
  return (
    <MutedLine>
      {head}
      {friendship.syncStatus === 'pending'
        ? '你是这块地的朋友，你第一次登录后生效。'
        : '你是这块地的朋友，但服务器还没把你写进领地；下一次有人改这块地时会重写。'}
    </MutedLine>
  )
}

function OtherPlotsAbilities({
  plots,
  myPlotId,
  friendOf,
  effective,
}: {
  plots: AbilityPlots
  myPlotId: string | null
  friendOf: readonly PlotFriendship[]
  effective: boolean
}): ReactElement {
  if (plots.status !== 'ready') {
    return <SourceStatus source={plots} what="本区的地块" />
  }
  // 我是朋友的那几块按朋友那一列算, 不再按"其他住户"算: 单独列在最前, 不混进下面的统计。
  // 暂停了的不按朋友算 —— 我是本区住户, 那块地对我就和别的地块一样按其他住户算 (居住权没生效时按外人算):
  // 照样列在最前说明白, 同时也进下面的统计。
  const friendPlotIds = new Set(
    friendOf.filter((friendship) => !friendship.suspended).map((friendship) => friendship.plotId),
  )
  // 冻结中的地块除管理员 (OP 绕过, 不拦) 外谁都进不去: 单独并成一行, 不进"按默认的"统计。
  const frozen = plots.data.plots.filter((plot) => plot.status === 'frozen')
  const others = plots.data.plots.filter(
    (plot) => plot.plotId !== myPlotId && !friendPlotIds.has(plot.plotId) && plot.status !== 'frozen',
  )
  const friendLines =
    friendOf.length === 0 ? null : (
      <ul className="flex flex-col gap-2">
        {friendOf.map((friendship) => (
          <FriendPlotLine friendship={friendship} key={friendship.plotId} />
        ))}
      </ul>
    )
  const frozenLine =
    frozen.length === 0 ? null : (
      <ul className="flex flex-col gap-2">
        <MutedLine>
          <span className="font-medium">
            冻结中的 {frozen.length} 块（{frozen.map((plot) => plot.code).join('、')}）：
          </span>
          原户主已被移出本区，冻结期间除管理员外谁都不能进出和操作。
        </MutedLine>
      </ul>
    )

  if (others.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        {friendLines}
        {frozenLine}
        {friendOf.length === 0 && frozen.length === 0 ? (
          <p className="text-muted-foreground text-sm">本区还没有别人的地块。</p>
        ) : null}
      </div>
    )
  }
  if (!effective) {
    return (
      <div className="flex flex-col gap-2">
        {friendLines}
        {frozenLine}
        <p className="text-muted-foreground text-sm">
          居住权生效前，你在{friendOf.length === 0 ? '' : '其余'}别人的地块里按外人算，能做什么看各户主给外人开了什么。
        </p>
      </div>
    )
  }
  const defaults = plots.data.residentDefaults
  const byDefault = others.filter((plot) => plot.residentColumnIsDefault)
  const vacantByDefault = byDefault.filter((plot) => plot.ownerName === null).length
  const customized = others.filter((plot) => !plot.residentColumnIsDefault)
  const byDefaultHead = (
    <span className="font-medium">
      按默认的 {byDefault.length} 块{vacantByDefault > 0 ? `（含 ${String(vacantByDefault)} 块空置）` : ''}：
    </span>
  )

  return (
    <div className="flex flex-col gap-2">
      {friendLines}
      {frozenLine}
      <p className="text-muted-foreground text-xs">
        各户主自己决定其他住户在自己的地块里能做什么。
        {friendOf.length === 0 && frozen.length === 0 ? '' : '其余几块，'}你作为其他住户：
      </p>
      <ul className="flex flex-col gap-2">
        {byDefault.length === 0 ? null : defaults.length === 0 ? (
          <MutedLine>
            {byDefaultHead}
            什么都不能碰
          </MutedLine>
        ) : (
          <GrantedLine>
            {byDefaultHead}
            {defaults.join('、')}
          </GrantedLine>
        )}
        {customized.map((plot) => {
          const diff = residentDiff(plot, defaults)
          const head = (
            <span className="font-medium">
              {plot.code}（{plot.ownerName ?? '空置'}）：
            </span>
          )
          // 只关掉了几项、没多开任何一项: 这一行说的全是"不能做", 不配打勾。
          return diff.opensMore ? (
            <GrantedLine key={plot.plotId}>
              {head}
              {diff.text}
            </GrantedLine>
          ) : (
            <MutedLine key={plot.plotId}>
              {head}
              {diff.text}
            </MutedLine>
          )
        })}
      </ul>
      {friendOf.length === 0 ? (
        <p className="text-muted-foreground text-xs">有户主把你加成朋友的话，那块地按“朋友”那一列算。</p>
      ) : null}
    </div>
  )
}

/**
 * 区域规则的一行摘要 (PvP 关、怪物自然生成 开…)。整片统一, 住户、外人、各户地块都受它管。
 * 标题用"区域规则"而不是"本区规则": 下面"本区信息"里的"区规"是手写的文字守则, 两个名字挨着会被当成一回事。
 * 标签一律中性色: "开"不等于"好" —— PvP 开、爆炸开染成绿色就成了在夸它。
 * 这里不写"管理员除外": 区域规则是 Flan 的全局类权限, 没有 OP 绕过, 对 OP 一样生效 (见 Flan 对接说明 2.3 第 4 步)。
 * 带锁的机械动力那条例外: 服主拍板对 OP 例外, 由它自己的 valueText 写明。
 */
function RegionRulesLine({
  items,
  fixedRules,
}: {
  items: readonly DistrictPermissionItem[]
  /** 服务端写死、谁都改不了的 (机械动力的机器禁用等), 带锁标出。 */
  fixedRules: readonly DistrictFixedRule[]
}): ReactElement {
  return (
    <div className="flex flex-col gap-2">
      <h3 className="font-medium text-foreground text-sm">区域规则</h3>
      <div className="flex flex-wrap gap-1.5">
        {items.map((item) =>
          item.current.district === null ? null : (
            <Tag key={item.permissionId} size="sm" tone="neutral">
              {`${item.label}：${onOffLabel(item.current.district)}`}
            </Tag>
          ),
        )}
        {fixedRules.map((rule) => (
          <Tag key={rule.ruleId} size="sm" tone="neutral">
            <LockIcon aria-hidden="true" className="size-3" />
            {`${rule.label}：${rule.valueText}`}
          </Tag>
        ))}
      </div>
      <p className="text-muted-foreground text-xs">
        {'区域规则管整片自管区，公共区域和各户地块都一样，住户、外人也一样，只有管理员能改；带锁的是服务器固定的规则，谁都改不了。自管区内和外围 8 格内不能自己圈个人领地。'}
      </p>
    </div>
  )
}

// ============================================================
// 仅管理员 (区务长视角的锁定卡)
// ============================================================

interface AdminOnlyLine {
  label: string
  detail: string
  granted: (abilities: DistrictAbilities) => boolean
}

const ADMIN_ONLY_LINES: readonly AdminOnlyLine[] = [
  {
    label: '圈学院用地、新建自管区',
    detail: '在游戏里圈出一大片 Flan 管理员领地并登记给学院；里面的地块由区务长来划',
    granted: (a) => a.editClaim,
  },
  { label: '调整自管区边界', detail: '扩大、缩小或移动本区范围', granted: (a) => a.editClaim },
  {
    label: '删除自管区',
    detail: '只解除与学院的绑定：领地和学院成员名单保留，操作记录归档',
    granted: (a) => a.deleteDistrict,
  },
  { label: '任命、撤销区务长', detail: '包括换人；区务长由管理员任命，本身不是 OP', granted: (a) => a.appointWarden },
  {
    label: '改区域规则',
    detail: 'PvP、爆炸、刷怪这类整片统一的开关；公共区域住户和外人的开关区务长可以改',
    granted: (a) => a.manageRegionRules,
  },
  {
    label: '给地块定价、开放购买',
    detail: '每格单价、地块尺寸上下限；区务长不能定价',
    granted: (a) => a.managePlotMarket,
  },
  {
    label: '解除冻结、立即收回地块',
    detail: '移出户主会自动冻结 TA 的地块 7 天，到期由服务器收回',
    granted: (a) => a.manageFrozenPlots,
  },
  {
    label: '打开、代改别人的地块',
    detail: '看朋友和权限、改有户主的地块范围；每次都写进那块地的记录，户主看得到',
    granted: (a) => a.overridePlots,
  },
]

/**
 * 区务长做不了的事画成锁定态而不是直接藏掉: 藏掉会让区务长以为"没有这个功能", 于是要么去找不存在的入口,
 * 要么以为改边界这件事根本没人能做。锁着并写明"仅管理员", 下一步该找谁就一目了然。
 */
export function AdminOnlyCard({ abilities }: { abilities: DistrictAbilities }): ReactElement {
  return (
    <Panel description="这些操作区务长做不了，需要时请联系管理员" title="仅管理员">
      <ul className="flex flex-col divide-y">
        {ADMIN_ONLY_LINES.map((line) => {
          const locked = !line.granted(abilities)
          return (
            <li className="flex items-center gap-3 py-2 first:pt-0 last:pb-0" key={line.label}>
              <LockIcon
                aria-hidden="true"
                className={`size-4 shrink-0 ${locked ? 'text-muted-foreground' : 'text-success'}`}
              />
              <div className="flex min-w-0 flex-1 flex-col">
                <span className={`text-sm ${locked ? 'text-muted-foreground' : 'text-foreground'}`}>
                  {line.label}
                </span>
                <span className="text-muted-foreground text-xs">{line.detail}</span>
              </div>
              <Tag size="sm" tone="neutral">
                {locked ? '仅管理员 · 已锁定' : '可用'}
              </Tag>
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}

// ============================================================
// 本区信息
// ============================================================

export function DistrictInfoPanel({ district }: { district: DistrictInfo }): ReactElement {
  return (
    <Panel description={`${district.academyFullName} · ${district.displayName}`} title="本区信息">
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <Stat
            label="区务长"
            value={
              district.wardenName === null ? (
                <span className="text-muted-foreground">暂无</span>
              ) : (
                district.wardenName
              )
            }
          />
          <Stat label="住户" value={`${String(district.residentCount)} 人`} />
          <Stat hint={boundsSize(district.bounds)} label="面积" value={formatArea(district.area)} />
          <Stat
            className="col-span-2"
            hint={dimensionLabel(district.bounds.dimension)}
            label="范围坐标"
            value={formatBounds(district.bounds)}
          />
          <Stat label="建立于" value={formatDate(district.createdAt)} />
          <Stat
            hint={
              district.vacantPlotCount === 0 ? '没有空置' : `${String(district.vacantPlotCount)} 块空置`
            }
            label="地块"
            value={`${String(district.plotCount)} 块`}
          />
        </div>
        <div className="flex flex-col gap-2">
          <h3 className="font-medium text-foreground text-sm">区规</h3>
          {district.rules.length === 0 ? (
            <p className="text-muted-foreground text-sm">本区还没有写区规。</p>
          ) : (
            <ol className="flex list-decimal flex-col gap-1 pl-5 text-foreground text-sm">
              {district.rules.map((rule) => (
                <li key={rule}>{rule}</li>
              ))}
            </ol>
          )}
        </div>
      </div>
    </Panel>
  )
}
