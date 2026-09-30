import { CheckIcon, MinusIcon, SlidersHorizontalIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { DataTable, type DataTableColumn, Panel } from '@/components/kit'
import type { DistrictRole } from '@/lib/types'
import { ROLE_LABEL } from './format'

/**
 * 权限说明: 能力 x 身份的对照表, 所有人都看得到, 让服主一眼看清"管理员管领地、区务长管人和公共区域的开关、
 * 户主管自己的地块、其余人按设置"这几层。
 *
 * 这是**说明文字**, 不是权限判定: 每个人实际能点什么由服务端下发的 abilities 与开关表决定 (见 DistrictCards /
 * PermissionSettings / PlotDetail)。故这张表写成静态文案而不从回执拼 —— 它描述的是已拍板的规则本身, 规则变了
 * 要改的是决策与服务端, 这里跟着改文案。
 *
 * 单元格一律 nowrap (DataTable 契约), 故格子里只放"可以 / 仅本区 / 仅自己的地块 / 按设置 / 不可以"这几个短词,
 * 附带条件 (如"区域规则除外") 另起一行小字, 不往同一行里塞 —— 否则 1366x768 档下整张表会横向滚动。
 */

/**
 * all = 所有自管区都可以; own = 只在自己那个自管区可以; ownPlot = 只在自己的地块可以;
 * setting = 按设置 (公共区域按区务长的开关, 地块按户主的开关); none = 不可以。
 */
type Grant = 'all' | 'own' | 'ownPlot' | 'setting' | 'none'

interface MatrixRow {
  capability: string
  note: string | null
  grants: Record<DistrictRole, Grant>
  /** 格子里第二行小字 (附带条件)。 */
  grantNotes: Partial<Record<DistrictRole, string>>
}

const ROWS: readonly MatrixRow[] = [
  {
    capability: '查看有哪些自管区、区务长是谁',
    note: null,
    grants: { admin: 'all', warden: 'all', resident: 'all', outsider: 'all' },
    grantNotes: {},
  },
  {
    capability: '放置、破坏方块',
    note: '公共区域按区务长的开关，地块按户主的开关',
    // 管理员 (OP) 在 Flan 管理员领地里自带绕过, 服主拍板不拦: 所有地块都进得去, 不受任何开关限制。
    grants: { admin: 'all', warden: 'setting', resident: 'setting', outsider: 'setting' },
    grantNotes: { admin: '不受开关限制', warden: '自己的地块里都能', resident: '自己的地块里都能' },
  },
  {
    capability: '开门、开箱子等日常交互',
    note: '每一项都能单独开关，比如外人只许开门',
    grants: { admin: 'all', warden: 'setting', resident: 'setting', outsider: 'setting' },
    grantNotes: { admin: '不受开关限制' },
  },
  {
    capability: '开关公共区域的权限',
    note: '地块以外的地方；住户、外人各一列',
    grants: { admin: 'all', warden: 'own', resident: 'none', outsider: 'none' },
    grantNotes: { warden: '区域规则除外' },
  },
  {
    capability: '开关自己地块的权限',
    note: '朋友、其他住户、外人各一列',
    grants: { admin: 'all', warden: 'ownPlot', resident: 'ownPlot', outsider: 'none' },
    grantNotes: { admin: '全部地块，记为代改' },
  },
  {
    capability: '管理自己地块的朋友',
    // 上限按地块档位定、还没拍板 (同 PlotDetail 的说法), 不写成定数。
    note: '朋友可以是任何玩家；上限还在定，先按 8 人',
    grants: { admin: 'all', warden: 'ownPlot', resident: 'ownPlot', outsider: 'none' },
    grantNotes: { admin: '全部地块，记为代改' },
  },
  {
    // 住户也有"本区地块"页签 (平面图、编号、户主、面积、状态); 朋友数与生效状态只给区务长和管理员。
    capability: '查看本区地块和户主',
    note: '朋友名单只有户主和管理员看得到',
    grants: { admin: 'all', warden: 'own', resident: 'own', outsider: 'none' },
    grantNotes: { resident: '不含朋友数', outsider: '只看块数' },
  },
  {
    capability: '划分、调整、删除空置地块',
    // 与 plot.delete / plot.resize 同口径: 有户主的地块谁都删不了; 管理员只能代调范围, 区务长连范围也改不了。
    note: '服务器代建；有户主的地块谁都不能删',
    grants: { admin: 'all', warden: 'own', resident: 'none', outsider: 'none' },
    grantNotes: { admin: '有户主的只能代调范围', warden: '有户主的改不了' },
  },
  {
    capability: '给地块定价',
    note: '每格单价、尺寸上下限，开不开放购买',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '购买空置地块',
    note: '直接买，先到先得；一人最多一块',
    grants: { admin: 'none', warden: 'own', resident: 'own', outsider: 'none' },
    grantNotes: { admin: '管理员身份不买', warden: '无地块时自己买', resident: '没有地块时' },
  },
  {
    capability: '冻结与收回地块',
    note: '移出户主自动冻结 7 天再收回',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: { admin: '解除冻结、立即收回', warden: '移出户主自动触发' },
  },
  {
    capability: '改区域规则',
    note: 'PvP、爆炸、刷怪这类整片统一的开关',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    // 服务器固定的拦截, 不是 Flan 开关 (见 district.permissions 的 fixedRules): 除管理员外谁都不可以。
    // 服主 2026-09-29 拍板对 OP 例外 (亲手放置); 装饰方块照常可放; 规则本身管理员也改不了。
    capability: '用机械动力',
    note: '机器禁用、装饰方块可放；外围 8 格内同样',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: { admin: '亲手放置例外' },
  },
  {
    capability: '查看住户名单和操作记录',
    note: null,
    grants: { admin: 'all', warden: 'own', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '添加、移出住户',
    note: '加住户 = 把玩家加入本学院',
    grants: { admin: 'all', warden: 'own', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '任命、撤销区务长',
    note: '区务长由管理员任命，本身不是 OP',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '圈学院用地、新建自管区',
    note: '在游戏里用金锄头圈管理员领地',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '调整自管区边界',
    note: null,
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '删除自管区',
    note: '只解绑：领地、成员名单保留，记录归档',
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
  {
    capability: '自管区外圈个人领地',
    note: '与本面板无关；区内和外围 8 格内不能圈',
    grants: { admin: 'all', warden: 'all', resident: 'all', outsider: 'all' },
    grantNotes: {},
  },
  {
    capability: '重试领地权限同步',
    note: null,
    grants: { admin: 'all', warden: 'none', resident: 'none', outsider: 'none' },
    grantNotes: {},
  },
]

const ROLE_ORDER: readonly DistrictRole[] = ['admin', 'warden', 'resident', 'outsider']

const GRANT_TEXT: Record<Exclude<Grant, 'none'>, string> = {
  all: '可以',
  own: '仅本区',
  ownPlot: '仅自己的地块',
  setting: '按设置',
}

function GrantCell({ grant, note }: { grant: Grant; note: string | undefined }): ReactElement {
  const noteLine = note === undefined ? null : <span className="text-muted-foreground text-xs">{note}</span>
  if (grant === 'none') {
    return (
      <span className="inline-flex flex-col gap-0.5">
        <span className="inline-flex items-center gap-1 text-muted-foreground">
          <MinusIcon aria-hidden="true" className="size-4" />
          <span className="text-xs">不可以</span>
        </span>
        {noteLine}
      </span>
    )
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex items-center gap-1 text-foreground">
        {grant === 'setting' ? (
          <SlidersHorizontalIcon aria-hidden="true" className="size-4 text-muted-foreground" />
        ) : (
          <CheckIcon aria-hidden="true" className="size-4 text-success" />
        )}
        <span className="text-xs">{GRANT_TEXT[grant]}</span>
      </span>
      {noteLine}
    </span>
  )
}

export function PermissionMatrix({ viewerRole }: { viewerRole: DistrictRole }): ReactElement {
  const columns: readonly DataTableColumn<MatrixRow>[] = [
    {
      key: 'capability',
      header: '能做什么',
      render: (row) => (
        <span className="flex flex-col">
          <span className="text-foreground">{row.capability}</span>
          {row.note === null ? null : <span className="text-muted-foreground text-xs">{row.note}</span>}
        </span>
      ),
    },
    ...ROLE_ORDER.map(
      (role): DataTableColumn<MatrixRow> => ({
        key: role,
        // 表头是纯字符串 (kit 契约), 当前身份用文字标出来, 不另造一套列高亮。
        header: role === viewerRole ? `${ROLE_LABEL[role]}（你）` : ROLE_LABEL[role],
        render: (row) => <GrantCell grant={row.grants[role]} note={row.grantNotes[role]} />,
      }),
    ),
  ]

  return (
    <Panel
      description="管理员管领地和定价，区务长管人、公共区域的开关和划地块，户主管自己的地块，其余人按设置行事"
      padded={false}
      title="权限说明"
    >
      <DataTable columns={columns} rowKey={(row) => row.capability} rows={ROWS} />
      <div className="flex flex-col gap-1 border-t px-4 py-3 text-muted-foreground text-xs">
        <p>· 住户名单就是学院成员名单：被加入某个学院，就获得这个学院自管区的居住权。</p>
        {/* 中文句子不在 JSX 里折行: 折行处会被渲染成一个空格。 */}
        <p>
          {'· 管理员圈一大片学院用地，就是自管区；自管区里的小块地块由区务长来划（服务器代建），区务长不能新建、改大小、删除自管区本身。地块以外的地方是公共区域：住户、外人在公共区域能做什么，由区务长逐项开关。'}
        </p>
        <p>· 主城 DU 由管理员直接管理，不设自管区。</p>
        <p>
          {'· 地块直接购买，先到先得；一块地一个户主，一个住户最多一块。价格 = 面积 × 每格单价，单价只有管理员能设。付款去向还在定，退地规则也还在定。'}
        </p>
        <p>
          {'· 户主在自己的地块里什么都能做（改地块范围这类除外），并自己决定朋友、其他住户、外人在地块里能做什么。管理员（OP）可以进入所有地块并拥有全部权限，不受这些开关限制。区务长只能看地块列表，不能改有户主的地块；管理员可以代改，每次都记进那块地的记录，户主看得到。'}
        </p>
        <p>
          {'· 户主被移出本区后，地块原地冻结 7 天再收回：东西不动，除管理员外谁都不能进出和操作；期间管理员可以解除冻结（原户主回到本区时）或立即收回。收回后原户主留下的东西怎么处理还在定。因违反区规被移出的人，在本区别人地块的朋友身份自动暂停，户主会收到通知，可以自己恢复；其他原因移出的保留朋友身份。'}
        </p>
        <p>
          {'· PvP、爆炸这类区域规则管整片自管区（地块也一样），只有管理员能改。机械动力的机器在自管区内和外围 8 格内都不能放，装饰方块可以，管理员（OP）亲手放置例外。外人指不是本区住户的任何玩家。'}
        </p>
        <p>
          {'· 个人圈地：自管区外，玩家可以自己用 Flan 圈个人领地、自己管权限，与本面板无关；自管区内和外围 8 格内不能圈个人领地。'}
        </p>
        <p>
          · 领地保护由服务器执行。区务长由管理员任命，本身不是 OP；区务长和户主本人都不直接持有领地的编辑权限，添加、移出住户、划地块和各种开关都是服务器代为办理。
          {viewerRole === 'admin'
            ? '（Flan 里：自管区是管理员领地，住户在它的居民组，外人对应领地的全域权限；每块地是一块子领地，地块内每项都写成明确值、组名独立，不沿用自管区设置。OP 在管理员领地里本来就绕过这些组权限，按拍板不拦。）'
            : ''}
        </p>
        <p>· 删除自管区只解除与学院的绑定：领地和学院成员名单都保留，操作记录归档，管理员仍可查。</p>
      </div>
    </Panel>
  )
}
