import { CrownIcon, RotateCwIcon, UnlinkIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  DataTable,
  type DataTableColumn,
  Dropdown,
  type DropdownOption,
  ErrorBlock,
  type FeedbackTone,
  LoadingBlock,
  NumberInput,
  Panel,
  Stat,
  Surface,
  Tag,
  Toggle,
} from '@/components/kit'
import { invalidateAll } from '@/lib/refresh'
import { callMock, nowMs, useMockAction } from '@/mock'
import type {
  ArchivedDistrict,
  DistrictAbilities,
  DistrictInfo,
  DistrictPlotsResult,
  DistrictResident,
  DistrictSummary,
} from '@/lib/types'
import { DistrictLogPanel } from './DistrictLogPanel'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import {
  boundsSize,
  countText,
  describeFailure,
  dimensionLabel,
  formatArea,
  formatCredit,
  formatDate,
  formatRelative,
} from './format'
import { TruncatedNotice } from './TruncatedNotice'

/**
 * 管理员专属的几块: 自管区总览表、已解绑自管区的归档, 以及选中某个自管区后的"管理员操作"
 * (任免区务长 / 边界 / 地块价格与购买 / Flan 同步 / 删除即解绑)。
 *
 * 管理员视角可以说"Flan": 管理员要排障, 需要知道背后是哪个模组、失败原文是什么。
 *
 * 写操作成功后一律 invalidateAll (同 ResidentManager): 区务长、名单、记录、总览人数分属不同查询。
 */

// ============================================================
// 总览
// ============================================================

function ClaimStatusCell({ summary }: { summary: DistrictSummary }): ReactElement {
  const issues = summary.syncIssues
  if (issues === null) {
    // 服务端只给管理员下发 syncIssues; 走到这里说明回执与身份对不上, 如实显示未知而不是画一个"正常"。
    return <span className="text-muted-foreground">未知</span>
  }
  if (issues.pending === 0 && issues.failed === 0) {
    return (
      <Tag size="sm" tone="success">
        正常
      </Tag>
    )
  }
  return (
    <span className="flex flex-wrap gap-1">
      {issues.failed > 0 ? (
        <Tag size="sm" tone="danger">
          {`${String(issues.failed)} 人同步失败`}
        </Tag>
      ) : null}
      {issues.pending > 0 ? (
        <Tag size="sm" tone="warning">
          {`${String(issues.pending)} 人待生效`}
        </Tag>
      ) : null}
    </span>
  )
}

const OVERVIEW_COLUMNS: readonly DataTableColumn<DistrictSummary>[] = [
  {
    key: 'name',
    header: '自管区',
    render: (row) => (
      <span className="flex flex-col">
        <span className="font-medium text-foreground">{row.displayName}</span>
        <span className="font-mono text-muted-foreground text-xs">{row.districtId}</span>
      </span>
    ),
    sortValue: (row) => row.displayName,
  },
  {
    key: 'academy',
    header: '学院',
    render: (row) => row.academyFullName,
    sortValue: (row) => row.academyFullName,
  },
  {
    key: 'warden',
    header: '区务长',
    render: (row) =>
      row.wardenName === null ? <span className="text-muted-foreground">暂无</span> : row.wardenName,
    sortValue: (row) => row.wardenName ?? '',
  },
  {
    key: 'residents',
    header: '住户',
    numeric: true,
    render: (row) => `${String(row.residentCount)} 人`,
    sortValue: (row) => row.residentCount,
  },
  {
    key: 'area',
    header: '面积',
    numeric: true,
    render: (row) => formatArea(row.area),
    sortValue: (row) => row.area,
  },
  {
    key: 'claim',
    header: 'Flan 领地状态',
    render: (row) => <ClaimStatusCell summary={row} />,
    sortValue: (row) => (row.syncIssues === null ? 0 : row.syncIssues.failed * 1000 + row.syncIssues.pending),
  },
]

export function DistrictOverviewPanel({
  districts,
  selectedId,
  onSelect,
}: {
  districts: readonly DistrictSummary[]
  selectedId: string | null
  onSelect: (districtId: string) => void
}): ReactElement {
  return (
    <Panel
      actions={<Tag tone="neutral">{`${String(districts.length)} 个`}</Tag>}
      description="点一行查看并管理这个自管区"
      padded={false}
      title="自管区总览"
    >
      <DataTable
        columns={OVERVIEW_COLUMNS}
        emptyHint="还没有任何自管区"
        onRowClick={(row) => {
          onSelect(row.districtId)
        }}
        rowKey={(row) => row.districtId}
        rows={districts}
        selectedRowKey={selectedId ?? undefined}
      />
      <div className="border-t p-4">
        <p className="text-muted-foreground text-xs">
          {'新建自管区要在游戏里完成：管理员手持金锄头圈出一大片学院用地（Flan 的管理员领地），再用管理指令把它登记给某个学院（指令随后端一起定）。自管区里面的小块地块由区务长在平板上划，服务器代建。平板上只能查看和管理已登记的自管区。'}
        </p>
      </div>
    </Panel>
  )
}

// ============================================================
// 已解除绑定的自管区 (归档)
// ============================================================

const ARCHIVE_COLUMNS: readonly DataTableColumn<ArchivedDistrict>[] = [
  {
    key: 'name',
    header: '自管区',
    render: (row) => (
      <span className="flex flex-col">
        <span className="font-medium text-foreground">{row.displayName}</span>
        <span className="font-mono text-muted-foreground text-xs">{row.districtId}</span>
      </span>
    ),
    sortValue: (row) => row.displayName,
  },
  {
    key: 'academy',
    header: '学院',
    render: (row) => `${row.academyFullName}（成员 ${String(row.memberCount)} 人，名单保留）`,
    sortValue: (row) => row.academyFullName,
  },
  {
    key: 'unbound',
    header: '解绑',
    render: (row) => (
      <span className="flex flex-col">
        <span>{formatDate(row.unboundAt)}</span>
        <span className="text-muted-foreground text-xs">
          {row.unboundBy} · {formatRelative(row.unboundAt, nowMs())}
        </span>
      </span>
    ),
    sortValue: (row) => row.unboundAt,
  },
  {
    key: 'log',
    header: '归档记录',
    numeric: true,
    render: (row) => `${countText(row.log.length, row.logTruncated)} 条`,
  },
]

/**
 * 删除自管区只解除与学院的绑定 (lib/types.ts K7): 学院成员名单保留, 本区操作记录归档在这里, 管理员仍可查。
 * 一个都没有时整块不画 (不是一个空表): 绝大多数时候服主不需要看到它。
 */
export function ArchivedDistrictsPanel({ stateRefreshing }: { stateRefreshing: boolean }): ReactElement | null {
  const query = useMockAction('admin.district.archive', {})
  const [openId, setOpenId] = useState<string | null>(null)

  if (query.status === 'error') {
    // 切走管理员身份时这条会抢先以新身份重查而被拒 (过渡态, 同 DistrictPage 的 DetailFailure), 先不报。
    return stateRefreshing ? null : (
      <ErrorBlock code="admin.district.archive" message={query.error.message} onRetry={query.reload} />
    )
  }
  if (query.data === null || query.data.districts.length === 0) {
    return null
  }
  const opened = query.data.districts.find((district) => district.districtId === openId) ?? null
  // 回执的 truncated: 已解绑的区超过条数上限或回执体积预算, 只下发了最近解绑的一段。
  const { truncated } = query.data
  return (
    <>
      <Panel
        actions={<Tag tone="neutral">{`${countText(query.data.districts.length, truncated)} 个`}</Tag>}
        description="删除自管区只解除与学院的绑定：领地和学院成员名单都在，操作记录归档在这里，只有管理员看得到"
        padded={false}
        title="已解除绑定的自管区"
      >
        {truncated ? (
          <div className="px-4 pt-3 pb-1">
            {/* 只有管理员看得到这一块, 所以不写"请联系管理员"; 归档没有对应的 /district 子命令, 也不往那边指。 */}
            <TruncatedNotice>
              {`已解绑的自管区太多（或归档记录太长），这里只显示了最近解绑的 ${String(query.data.districts.length)} 个；其余的归档仍保存在服务器上，平板上暂时看不到。`}
            </TruncatedNotice>
          </div>
        ) : null}
        <DataTable
          columns={ARCHIVE_COLUMNS}
          onRowClick={(row) => {
            setOpenId((current) => (current === row.districtId ? null : row.districtId))
          }}
          rowKey={(row) => row.districtId}
          rows={query.data.districts}
          selectedRowKey={opened?.districtId}
        />
        <p className="border-t px-4 py-3 text-muted-foreground text-xs">点一行查看它解绑前的操作记录，再点一次收起。</p>
      </Panel>
      {opened === null ? null : (
        <DistrictLogPanel
          description={`${opened.displayName}解除绑定之前的操作记录（归档，只读）`}
          log={opened.log}
          title={`归档记录 · ${opened.displayName}`}
          truncated={opened.logTruncated}
        />
      )}
    </>
  )
}

// ============================================================
// 管理员操作
// ============================================================

export interface AdminActionsPanelProps {
  district: DistrictInfo
  residents: readonly DistrictResident[]
  /**
   * district.detail 的 residentsTruncated: residents 只是名单的前面一段。这时区务长候选和同步失败名单都只出自这一段,
   * "没有同步失败的住户"这类全称的话不能再说。
   */
  residentsTruncated: boolean
  abilities: DistrictAbilities
  /** 删除成功后由上层清掉选中项并出回执 (本组件随之卸载, 自己的回执条来不及显示)。 */
  onDeleted: (message: string) => void
}

export function AdminActionsPanel({
  district,
  residents,
  residentsTruncated,
  abilities,
  onDeleted,
}: AdminActionsPanelProps): ReactElement {
  const { toast, show, clear } = useDistrictToast()

  const candidates = residents.filter((resident) => !resident.isWarden)
  const [candidate, setCandidate] = useState('')
  const effectiveCandidate = candidates.some((resident) => resident.playerName === candidate) ? candidate : ''
  const candidateOptions: readonly DropdownOption<string>[] = [
    { value: '', label: '选择一名本区住户', disabled: true },
    ...candidates.map((resident) => ({
      value: resident.playerName,
      label: resident.syncStatus === 'pending' ? `${resident.playerName}（待生效）` : resident.playerName,
    })),
  ]

  const [wardenBusy, setWardenBusy] = useState(false)
  const [replaceOpen, setReplaceOpen] = useState(false)
  const [revokeOpen, setRevokeOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [retryingAll, setRetryingAll] = useState(false)

  const failed = residents.filter((resident) => resident.syncStatus === 'failed')
  const pendingCount = residents.filter((resident) => resident.syncStatus === 'pending').length

  async function setWarden(playerName: string | null): Promise<void> {
    setWardenBusy(true)
    try {
      const result = await callMock('admin.district.setWarden', { districtId: district.districtId, playerName })
      invalidateAll()
      setCandidate('')
      show(
        'success',
        result.wardenName === null
          ? `已撤销 ${district.wardenName ?? ''} 的区务长，TA 仍是本区住户。`
          : `${result.wardenName} 现在是${district.displayName}的区务长。`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      // 失败也重拉: 被拒多半是手上那份已经过期 (别的管理员先改了), 同 PermissionRows 的 commit。
      invalidateAll()
    } finally {
      setWardenBusy(false)
      setReplaceOpen(false)
      setRevokeOpen(false)
    }
  }

  async function retryAll(): Promise<void> {
    setRetryingAll(true)
    const stillFailed: string[] = []
    // 逐个串行重试: 每条都是一次服务端往返, 一条失败不该让其余的不试。
    for (const resident of failed) {
      try {
        await callMock('admin.district.retrySync', {
          districtId: district.districtId,
          playerName: resident.playerName,
        })
      } catch (error: unknown) {
        stillFailed.push(`${resident.playerName}：${describeFailure(error)}`)
      }
    }
    // 整轮跑完再作废一次, 不每条作废一次: 逐条作废会让名单在重试途中反复重拉。全失败也要刷新 ——
    // 被拒多半是名单已经过期 (人被移走、别人先重试过)。
    invalidateAll()
    setRetryingAll(false)
    if (stillFailed.length === 0) {
      show('success', `已重新写入 ${String(failed.length)} 人的 Flan 领地权限。`)
    } else {
      show('danger', `仍有 ${String(stillFailed.length)} 人失败：${stillFailed.join('；')}`)
    }
  }

  async function deleteDistrict(): Promise<void> {
    setDeleting(true)
    try {
      const result = await callMock('admin.district.delete', { districtId: district.districtId })
      setDeleteOpen(false)
      // 先交给上层把它从总览里拿掉, 再作废重拉: 顺序反过来, 重拉途中详情区会以"找不到这个自管区"重查它。
      onDeleted(
        `已解除${district.displayName}与${district.academyFullName}的绑定：Flan 领地和 ${String(result.keptPlots)} 块地块原样保留，学院成员名单（${String(result.keptMembers)} 人）保留，操作记录已归档（在总览下面查看）。`,
      )
      invalidateAll()
    } catch (error: unknown) {
      setDeleteOpen(false)
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setDeleting(false)
    }
  }

  function requestAppoint(): void {
    if (effectiveCandidate === '') {
      return
    }
    // 已有区务长时换人要确认一次 (现任会被撤掉); 没有时直接任命, 任命本身不是破坏性操作。
    if (district.wardenName === null) {
      void setWarden(effectiveCandidate)
    } else {
      setReplaceOpen(true)
    }
  }

  const { bounds } = district

  return (
    <div className="flex flex-col gap-4">
      <DistrictToastSlot onClear={clear} toast={toast} />

      {residentsTruncated ? (
        <TruncatedNotice>
          {`住户太多，这一页只拿到了名单的前 ${String(residents.length)} 人：任命区务长的下拉和下面的同步失败名单都只出自这些人。其余的请在游戏里用 /district academy members 命令查看。`}
        </TruncatedNotice>
      ) : null}

      {/* ==================== 区务长 ==================== */}
      <Panel
        description="由管理员（OP）任命，区务长本身不是 OP：管本区住户、公共区域开关，在本区划地块"
        title="区务长"
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <CrownIcon aria-hidden="true" className="size-4 text-muted-foreground" />
            <span className="text-muted-foreground text-sm">现任</span>
            {district.wardenName === null ? (
              <span className="text-muted-foreground text-sm">暂无区务长</span>
            ) : (
              <span className="font-medium text-foreground text-sm">{district.wardenName}</span>
            )}
            <Button
              disabled={!abilities.appointWarden || district.wardenName === null || wardenBusy}
              onClick={() => {
                setRevokeOpen(true)
              }}
              size="xs"
              variant="destructive-outline"
            >
              撤销区务长
            </Button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Dropdown
              className="w-56"
              disabled={!abilities.appointWarden || candidates.length === 0 || wardenBusy}
              onChange={setCandidate}
              options={candidateOptions}
              size="sm"
              value={effectiveCandidate}
            />
            <Button
              disabled={!abilities.appointWarden || effectiveCandidate === ''}
              loading={wardenBusy && !revokeOpen && !replaceOpen}
              onClick={requestAppoint}
              size="sm"
            >
              {district.wardenName === null ? '任命为区务长' : '改任为区务长'}
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            只能从本区住户里选。换人时现任区务长会被撤销，但仍是本区住户。区务长在 Flan 里没有任何编辑权限，一切由服务器代办。
          </p>
        </div>
      </Panel>

      {/* ==================== 地块价格与购买 ==================== */}
      <PlotMarketPanel abilities={abilities} districtId={district.districtId} onResult={show} />

      {/* ==================== 边界 ==================== */}
      <Panel
        description="自管区是一块 Flan 管理员领地，只有管理员能圈、能改；区务长不能新建、改大小或删除它"
        title="领地边界"
      >
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="维度" value={dimensionLabel(bounds.dimension)} />
            <Stat label="西北角" value={`X ${String(bounds.minX)}，Z ${String(bounds.minZ)}`} />
            <Stat label="东南角" value={`X ${String(bounds.maxX)}，Z ${String(bounds.maxZ)}`} />
            <Stat hint={boundsSize(bounds)} label="面积" value={formatArea(district.area)} />
          </div>
          <Surface>
            <ol className="flex list-decimal flex-col gap-1 pl-5 text-foreground text-sm">
              <li>到自管区现场，手持金锄头（Flan 的圈地工具）。</li>
              <li>右键领地的一个角，再右键新的位置，这个角就会被拖过去。</li>
              <li>也可以站在领地里用 Flan 的管理指令调整。改完回到这里刷新，就能看到新坐标。</li>
            </ol>
          </Surface>
          <BoundaryShrinkNote districtId={district.districtId} />
        </div>
      </Panel>

      {/* ==================== Flan 同步 ==================== */}
      <Panel
        actions={
          failed.length === 0 ? null : (
            <Button
              disabled={!abilities.retrySync}
              loading={retryingAll}
              onClick={() => {
                void retryAll()
              }}
              size="sm"
              variant="outline"
            >
              <RotateCwIcon aria-hidden="true" />
              全部重试
            </Button>
          )
        }
        description="服务器把住户写进 Flan 领地的居民组；写入失败的需要管理员重试"
        title="Flan 同步"
      >
        {failed.length === 0 ? (
          <p className="text-foreground text-sm">
            {residentsTruncated
              ? `拿到的这 ${String(residents.length)} 人里没有同步失败的；名单没有显示全，其余的人这里看不到。`
              : '没有同步失败的住户。'}
          </p>
        ) : (
          <ul className="flex flex-col gap-2">
            {failed.map((resident) => (
              <li className="flex flex-col" key={resident.playerName}>
                <span className="font-medium text-foreground text-sm">{resident.playerName}</span>
                <span className="text-muted-foreground text-xs">{resident.syncError ?? '服务端没有给出失败原因'}</span>
              </li>
            ))}
          </ul>
        )}
        {pendingCount === 0 ? null : (
          <p className="mt-3 text-muted-foreground text-xs">
            另有 {countText(pendingCount, residentsTruncated)} 人待生效：他们还没进过服务器，首次登录时会自动写入，不需要处理。
          </p>
        )}
      </Panel>

      {/* ==================== 危险操作 ==================== */}
      <Surface tone="danger">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 flex-col gap-0.5">
            <span className="font-medium text-foreground text-sm">删除自管区（只解除绑定）</span>
            <span className="text-muted-foreground text-xs">
              {`只解除${district.academyFullName}与这块领地的绑定：Flan 领地和 ${String(district.plotCount)} 块地块原样保留，只是不再由面板管理；学院成员名单（${String(district.residentCount)} 人）保留；本区操作记录归档，管理员仍可在总览下面查看。`}
            </span>
          </div>
          <Button
            disabled={!abilities.deleteDistrict}
            onClick={() => {
              setDeleteOpen(true)
            }}
            size="sm"
            variant="destructive"
          >
            <UnlinkIcon aria-hidden="true" />
            删除自管区
          </Button>
        </div>
      </Surface>

      <ConfirmDangerDialog
        confirmLabel="改任"
        loading={wardenBusy}
        message={`${effectiveCandidate} 将成为${district.displayName}的区务长，现任 ${district.wardenName ?? ''} 会被撤销区务长（仍是本区住户）。`}
        onConfirm={() => {
          void setWarden(effectiveCandidate)
        }}
        onOpenChange={setReplaceOpen}
        open={replaceOpen}
        title="更换区务长？"
      />

      <ConfirmDangerDialog
        confirmLabel="撤销区务长"
        loading={wardenBusy}
        message={`${district.wardenName ?? ''} 仍是本区住户，但不能再添加、移出住户。撤销后本区暂时没有区务长，住户管理只能由管理员来做。`}
        onConfirm={() => {
          void setWarden(null)
        }}
        onOpenChange={setRevokeOpen}
        open={revokeOpen}
        title={`撤销 ${district.wardenName ?? ''} 的区务长？`}
      />

      {/*
        二道锁用自管区的英文代号而不是中文名: 游戏内还打不了组字中文 (WebUI_ChineseIME_DesignSpec, DEFERRED),
        拿中文名当确认词等于把删除整个封死 —— 与 AdminPage 调账不用玩家名做确认词同一个理由。

        已拍板删除 = 只解除绑定 (lib/types.ts K7), 后果照实写: 领地、成员名单都还在, 但住户在面板上不再是"住户",
        住户管理、地块代办、权限开关这些都停了。仍留二道锁: 解绑之后要重新登记才能再管。
      */}
      <ConfirmDangerDialog
        confirmLabel="解除绑定"
        confirmWord={district.districtId}
        loading={deleting}
        message={`只解除${district.academyFullName}与这块领地的绑定：Flan 领地和各户地块的子领地原样保留，不再由面板管理（住户管理、地块代办、权限开关都停）；学院成员名单（${String(district.residentCount)} 人）保留，一个人仍只能属于一个学院；本区操作记录归档，管理员仍可在总览下面查看。要再管得重新登记。为防误操作，请输入这个自管区的英文代号确认。`}
        onConfirm={() => {
          void deleteDistrict()
        }}
        onOpenChange={setDeleteOpen}
        open={deleteOpen}
        title={`删除${district.displayName}（解除绑定）？`}
      />
    </div>
  )
}

/**
 * 缩小边界前的提醒。离边界留几格是服务端下发的 rules.edgeGap (数值待定), 读"本区地块"同一份 district.plots
 * (同一个缓存键, 不多发请求), 不在这里另写一个数 —— 数值一改, 这句话就会和平面图、划地块的校验对不上。
 * 还没读到 (或读失败) 时只说"要留出公共区域", 不编一个数。
 */
function BoundaryShrinkNote({ districtId }: { districtId: string }): ReactElement {
  const query = useMockAction('district.plots', { districtId })
  const gap = query.data === null ? null : query.data.rules.edgeGap
  return (
    <p className="text-muted-foreground text-xs">
      {`缩小边界前先看「本区地块」的平面图：地块离边界${
        gap === null ? '要留出一圈' : `至少要留 ${String(gap)} 格`
      }公共区域。自管区外围 8 格内不能个人圈地，除管理员外也不能放机械动力的机器（装饰方块可以）。`}
    </p>
  )
}

// ============================================================
// 地块价格与购买 (K21 / K22)
// ============================================================

/**
 * 本区地块的单价、尺寸上下限与"开放购买"开关。读的是"本区地块"页签同一份 district.plots (同一个缓存键):
 * 这里改完作废重拉, 那边的价格与"暂未开放购买"跟着变。只有管理员能改 (abilities.managePlotMarket) ——
 * 区务长不能定价, 看都不在这里看 (这一整页是管理员操作)。
 */
function PlotMarketPanel({
  districtId,
  abilities,
  onResult,
}: {
  districtId: string
  abilities: DistrictAbilities
  onResult: (tone: FeedbackTone, message: string) => void
}): ReactElement {
  const query = useMockAction('district.plots', { districtId })
  if (query.status === 'error') {
    return <ErrorBlock code="district.plots" message={query.error.message} onRetry={query.reload} />
  }
  if (query.data === null) {
    return <LoadingBlock label="正在读取本区地块价格" />
  }
  const { market, rules } = query.data
  return (
    <PlotMarketForm
      abilities={abilities}
      data={query.data}
      districtId={districtId}
      // 服务端的值一变 (别的管理员改了、刚保存完重拉回来) 就以新值重建表单, 不留一份过期的草稿。
      key={`${String(market.unitPrice)}|${String(rules.minSide)}|${String(rules.maxSide)}`}
      onResult={onResult}
    />
  )
}

/** 预览算价用的样例: 一块 24 x 24 的地 (种子里最常见的尺寸)。只是举例, 不参与任何判定。 */
const SAMPLE_SIDE = 24

function PlotMarketForm({
  districtId,
  data,
  abilities,
  onResult,
}: {
  districtId: string
  data: DistrictPlotsResult
  abilities: DistrictAbilities
  onResult: (tone: FeedbackTone, message: string) => void
}): ReactElement {
  const { market, rules } = data
  const [unitPrice, setUnitPrice] = useState(market.unitPrice)
  const [minSide, setMinSide] = useState(rules.minSide)
  const [maxSide, setMaxSide] = useState(rules.maxSide)
  const [saving, setSaving] = useState(false)
  const [toggling, setToggling] = useState(false)
  const [openConfirm, setOpenConfirm] = useState(false)

  const editable = abilities.managePlotMarket
  const dirty = unitPrice !== market.unitPrice || minSide !== rules.minSide || maxSide !== rules.maxSide
  const limitsValid = minSide >= 1 && minSide <= maxSide
  // 从地块列表数出来的: 列表被服务端截断 (plotsTruncated) 时只是下限 —— 被截掉的恰好多是最新划出、还空置的那些。
  const vacantText = countText(
    data.plots.filter((plot) => plot.status === 'vacant').length,
    data.plotsTruncated,
  )

  async function savePricing(): Promise<void> {
    setSaving(true)
    try {
      const result = await callMock('admin.district.setPlotPricing', { districtId, unitPrice, minSide, maxSide })
      invalidateAll()
      onResult(
        'success',
        result.logEntry === null
          ? '和原来一样，没有改动。'
          : `已保存：每格 ${formatCredit(result.unitPrice)}，每边 ${String(result.rules.minSide)} ~ ${String(result.rules.maxSide)} 格。空置地块的价格跟着变，已记进操作记录。`,
      )
    } catch (error: unknown) {
      onResult('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setSaving(false)
    }
  }

  async function setOpen(open: boolean): Promise<void> {
    setToggling(true)
    try {
      await callMock('admin.district.setPurchaseOpen', { districtId, open })
      invalidateAll()
      onResult(
        open ? 'warning' : 'success',
        open
          ? `本区已开放购买：没有地块的住户现在可以直接买空置地块（${vacantText} 块），先到先得。`
          : '本区已暂停购买，住户会看到“本区暂未开放购买”。已经买下的地块不受影响。',
      )
    } catch (error: unknown) {
      onResult('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setToggling(false)
      setOpenConfirm(false)
    }
  }

  return (
    <Panel description="只有管理员能定价、开关购买；区务长不能定价" title="地块价格与购买">
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Toggle
              checked={market.open}
              disabled={!editable || toggling}
              label={market.open ? '开放购买：开' : '开放购买：关'}
              onChange={(next) => {
                // 打开要先确认 (正式服的登录门设成 REQUIRED 之后才该开, 见下面那条提示); 关掉不拦。
                if (next) {
                  setOpenConfirm(true)
                } else {
                  void setOpen(false)
                }
              }}
            />
            <Tag tone={market.open ? 'warning' : 'neutral'}>{market.open ? '住户现在能直接买' : '住户看到“本区暂未开放购买”'}</Tag>
          </div>
          {/*
            登录门已随本模块合入: 服务端派发器入口对没登录的平板请求一律拒绝。但只有 REQUIRED 档在登录插件 (AccessHub)
            没装上时也关门; 默认的 AUTO 档没装就放行, OFF 档完全不查。买地是扣钱的写操作, 所以把条件写明白, 提示常驻。
          */}
          <Surface tone="warning">
            <p className="text-foreground text-xs">
              正式服请先把登录门（服务端配置 security.loginGate）设为 REQUIRED，再开放购买：登录门会拒绝没登录的平板请求，买地的人才确定是本人。没设成 REQUIRED 时登录校验可能没在生效，先别开。默认关。
            </p>
          </Surface>
        </div>

        <div className="flex flex-col gap-2">
          <h3 className="font-medium text-foreground text-sm">价格和地块尺寸</h3>
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">每格单价（信用点）</span>
              <NumberInput disabled={!editable || saving} max={1_000_000} min={1} onChange={setUnitPrice} size="sm" value={unitPrice} />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">每边最少（格）</span>
              <NumberInput disabled={!editable || saving} max={1_000} min={1} onChange={setMinSide} size="sm" value={minSide} />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">每边最多（格）</span>
              <NumberInput disabled={!editable || saving} max={1_000} min={1} onChange={setMaxSide} size="sm" value={maxSide} />
            </label>
          </div>
          <p className={`text-xs ${limitsValid ? 'text-muted-foreground' : 'text-destructive'}`}>
            {limitsValid
              ? `价格 = 面积 × 单价。比如一块 ${String(SAMPLE_SIDE)} × ${String(SAMPLE_SIDE)} 的地是 ${formatArea(SAMPLE_SIDE * SAMPLE_SIDE)} × ${String(unitPrice)} = ${formatCredit(SAMPLE_SIDE * SAMPLE_SIDE * unitPrice)}。尺寸上下限只管以后划、调的地块，已有的不动。`
              : '每边最少不能大于每边最多。'}
          </p>
          <p className="text-muted-foreground text-xs">
            {`地块离自管区边界至少留 ${String(rules.edgeGap)} 格公共区域（全服统一，数值待定）。付款去向还在定。`}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              disabled={!editable || !dirty || !limitsValid}
              loading={saving}
              onClick={() => {
                void savePricing()
              }}
              size="sm"
            >
              保存价格和尺寸
            </Button>
            {dirty ? (
              <Button
                disabled={saving}
                onClick={() => {
                  setUnitPrice(market.unitPrice)
                  setMinSide(rules.minSide)
                  setMaxSide(rules.maxSide)
                }}
                size="sm"
                variant="ghost"
              >
                撤回修改
              </Button>
            ) : null}
          </div>
        </div>
      </div>

      <ConfirmDangerDialog
        confirmLabel="仍然开放"
        loading={toggling}
        message={`开放后，本区没有地块的住户可以直接买空置地块（现在 ${vacantText} 块），先到先得，马上扣钱。正式服要先把登录门（服务端配置 security.loginGate）设为 REQUIRED 再开：没设成 REQUIRED 时登录校验可能没在生效，不能确认买地的请求是本人发的。`}
        onConfirm={() => {
          void setOpen(true)
        }}
        onOpenChange={setOpenConfirm}
        open={openConfirm}
        title="开放本区购买？"
      />
    </Panel>
  )
}
