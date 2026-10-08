import { LockIcon, PencilIcon, PlusIcon, ShoppingCartIcon, SnowflakeIcon, Trash2Icon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useRef, useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  Currency,
  DataTable,
  type DataTableColumn,
  ErrorBlock,
  Hint,
  LoadingBlock,
  Panel,
  Stat,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import { isMockActive } from '@/lib/bridge'
import { invalidateAll } from '@/lib/refresh'
import { callMock, nowMs, useMockAction } from '@/mock'
import type {
  DeletedPlot,
  DistrictAbilities,
  DistrictBounds,
  DistrictPlotsResult,
  PlotArea,
  PlotBuyBlock,
  PlotSummary,
} from '@/lib/types'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import {
  BUY_BLOCK_TEXT,
  PLOTS_TRUNCATED_ELSEWHERE,
  PLOT_STATUS_LABEL,
  PLOT_STATUS_TONE,
  RECLAIM_LEFTOVER_NOTE,
  boundsSize,
  countText,
  describeFailure,
  formatArea,
  formatBounds,
  formatCredit,
  formatDateTime,
  formatRelative,
  freezeLeftText,
  freezeShortText,
} from './format'
import {
  type CornerInputs,
  EMPTY_CORNERS,
  areaBlocks,
  areaDepth,
  areaFromCorners,
  areaProblem,
  areaWidth,
  cornersOf,
  emptyCornerCount,
  hasMalformedCorner,
  isIntegerText,
  sameArea,
} from './plotArea'
import { PlotDetail, PlotLogPanel } from './PlotDetail'
import { PLOT_LEGEND, PlotMap } from './PlotMap'
import { TruncatedNotice } from './TruncatedNotice'

/**
 * "本区地块"页签: 俯视平面图 + 选中地块的操作卡 + 地块列表。本区住户、区务长与管理员可见 (abilities.viewPlotList)。
 *
 *   住户    只能看: 编号、户主、面积、状态 (谁是邻居)。朋友数与生效状态是户主的私事, 服务端不给
 *           (abilities.viewPlotStatus 为假), 那两列整列不画。没有地块的住户可以直接买一块空置的 (先到先得,
 *           一人一块), 能不能买由服务端 market.viewerBlock 说了算。
 *   区务长  另看朋友数、领地权限生没生效; 可以在本区划新地块、调整或删除**空置**地块 (abilities.managePlots)。
 *           有户主的地块 (包括自己的) 改不了范围、删不了 —— 那是别人的家; 冻结中的只能看。
 *   管理员  另外看得到 Flan 字样与写入失败原因; 可以代改有户主的地块范围 (记为"管理员代改"并通知户主),
 *           解除冻结或立即收回; 选中一块地在最下面打开它的朋友和三列权限; 再往下是已删除地块的留档记录
 *           (区务长删得了空置地块, 删不了它以前的记录)。
 *
 * 平面图与列表共用一个选中项: 点图上的地块或列表的一行, 两边一起高亮。
 * 布局按容器宽度 (不是视口) 切换: 内容区够宽 (>= 42rem) 时图在左、操作卡在右, 否则上下排 ——
 * 平板 765 / 867 两档内容区都走左右排, 右栏定宽 17rem, 图自己缩放, 不会横向溢出。
 * 列表最多六列、坐标并成编号下的一行小字、价格并进状态列 (同上一版的口径: 单元格一律 nowrap)。
 *
 * 游戏内打不了中文: 本页唯一的输入是坐标数字 (可带负号)。
 */

type EditorState =
  | { mode: 'create'; corners: CornerInputs }
  | { mode: 'resize'; plotId: string; corners: CornerInputs }

type FreezeAction = { kind: 'unfreeze' | 'reclaim'; plot: PlotSummary }

/** 购买是从哪里点的: 回执画在那一块旁边 (右边的操作卡在图上方, 列表在下面, 平板上两处隔着一屏)。 */
type BuyOrigin = 'side' | 'list'

/** 预览里买地用的是假世界的演示余额, 与页头的信用点不是同一份; 真服上是同一份, 不写这句。 */
function previewBalanceNote(): string {
  return isMockActive() ? '（预览：演示余额，与页头不同）' : ''
}

/**
 * 能买 (market.viewerBlock 为 null) 却没有余额 (market.viewerBalance 为 null): 服务端的经济子系统这会儿没就绪,
 * 余额取不到, 这时下单也只会被 ECONOMY_OFFLINE 拒掉。照实说读不到并把购买键置灰 —— 当成 0 来画的话,
 * 玩家看到的是"你有 0 信用点"。
 */
const BALANCE_UNREADABLE_TEXT = '余额暂时读不到'

export function DistrictPlotsTab({
  districtId,
  districtName,
  abilities,
}: {
  districtId: string
  districtName: string
  abilities: DistrictAbilities
}): ReactElement {
  const query = useMockAction('district.plots', { districtId })
  if (query.status === 'error') {
    return <ErrorBlock code="district.plots" message={query.error.message} onRetry={query.reload} />
  }
  if (query.data === null) {
    return <LoadingBlock label="正在读取本区地块" />
  }
  return <PlotsView abilities={abilities} data={query.data} districtId={districtId} districtName={districtName} />
}

function PlotsView({
  data,
  districtId,
  districtName,
  abilities,
}: {
  data: DistrictPlotsResult
  districtId: string
  districtName: string
  abilities: DistrictAbilities
}): ReactElement {
  const { toast, show, clear } = useDistrictToast()
  // 地块列表自己的回执条: 在列表里点"购买"的结果画在列表上方, 不画到一屏之外的平面图那边 (见 DistrictToast)。
  const listToast = useDistrictToast()
  const [buyOrigin, setBuyOrigin] = useState<BuyOrigin>('side')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [saving, setSaving] = useState(false)
  /** 各确认框的对象与开合分开存: 关窗时若一并清空, 收起动画那几帧标题会闪成空白 (同 PlotDetail)。 */
  const [buyTarget, setBuyTarget] = useState<PlotSummary | null>(null)
  const [buyOpen, setBuyOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<PlotSummary | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [freezeAction, setFreezeAction] = useState<FreezeAction | null>(null)
  const [freezeOpen, setFreezeOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const detailRef = useRef<HTMLDivElement>(null)

  const { plots, myPlotId, market, rules, bounds, plotsTruncated } = data
  const now = nowMs()
  const adminView = abilities.inspectPlots
  const vacant = plots.filter((plot) => plot.status === 'vacant').length
  const frozenCount = plots.filter((plot) => plot.status === 'frozen').length
  // 选中的地块不在列表里了 (刚被删掉、被别人改了), 就当没选。
  const selected = plots.find((plot) => plot.plotId === selectedId) ?? null
  const canBuy = market.viewerBlock === null
  const balance = market.viewerBalance
  // 地块列表被截断时, 划地块的人要多知道一句: 没下发的那些地块平板拿不到, 图上那里是空白, 下面本地的重叠检查
  // (areaProblem) 也查不到它们 —— 看着能划, 提交时仍可能被服务端以"与别的地块重叠"拒掉。
  const truncatedDrawNote = abilities.managePlots
    ? '没显示的地块在图上是空白，划新地块时会不会和它们重叠，以服务器的校验为准。'
    : ''

  // 调整中的那块地没了 (被别人删了), 编辑器随之作废。
  const editingPlot = editor?.mode === 'resize' ? (plots.find((plot) => plot.plotId === editor.plotId) ?? null) : null
  const activeEditor = editor?.mode === 'resize' && editingPlot === null ? null : editor
  const draft = activeEditor === null ? null : areaFromCorners(activeEditor.corners)
  // 还空着的格子不算错 (正在一格一格填): 只数一下还差几个; 填了但不是整数才报红。
  const missingCorners = activeEditor === null ? 0 : emptyCornerCount(activeEditor.corners)
  const problem =
    activeEditor === null
      ? null
      : hasMalformedCorner(activeEditor.corners)
        ? '坐标只能填整数（可以带负号）'
        : draft === null
          ? null
          : areaProblem(bounds, draft, rules, plots, editingPlot?.plotId ?? null)
  const unchanged = draft !== null && editingPlot !== null && sameArea(draft, editingPlot.bounds)

  function select(plotId: string): void {
    setSelectedId(plotId)
  }

  function openDetail(): void {
    // 平面图在上、地块详情在最下: 点完滚过去 (同自管区总览的选中), 减少动效档瞬时到位。
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    detailRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }

  function setCorners(corners: CornerInputs): void {
    setEditor((current) => (current === null ? current : { ...current, corners }))
  }

  async function submitEditor(): Promise<void> {
    if (activeEditor === null || draft === null) {
      return
    }
    setSaving(true)
    try {
      if (activeEditor.mode === 'create') {
        const result = await callMock('plot.create', { districtId, area: draft })
        invalidateAll()
        setEditor(null)
        setSelectedId(result.plot.plotId)
        show(
          'success',
          `已划出地块 ${result.plot.code}（${boundsSize(draft)}，${formatArea(areaBlocks(draft))}），现在空置${
            result.plot.price === null ? '' : `，按现价 ${formatCredit(result.plot.price)}`
          }。已记进本区操作记录。`,
        )
      } else {
        const result = await callMock('plot.resize', { districtId, plotId: activeEditor.plotId, area: draft })
        invalidateAll()
        setEditor(null)
        show(
          'success',
          result.ownerNotified
            ? `已把 ${result.plot.code} 调成 ${boundsSize(draft)}。这次记为“管理员代改”，已写进地块记录通知户主。`
            : `已把 ${result.plot.code} 调成 ${boundsSize(draft)}，已记进本区操作记录。`,
        )
      }
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      // 失败也重拉: 被拒多半是手上那份已经过期 (别人刚划了一块、刚被买走…), 同 PermissionRows 的 commit。
      invalidateAll()
    } finally {
      setSaving(false)
    }
  }

  async function confirmBuy(): Promise<void> {
    if (buyTarget === null || buyTarget.price === null) {
      return
    }
    // 回执画在点"购买"的那一块旁边; 另一处若还挂着上一条回执, 一并收起, 免得两处各说各的。
    const notify = buyOrigin === 'list' ? listToast.show : show
    if (buyOrigin === 'list') {
      clear()
    } else {
      listToast.clear()
    }
    setBusy(true)
    try {
      // 范围与价格一起钉住: 确认期间区务长挪了这块地或改了形状 (价格可能不变) 时服务端报 PLOT_CHANGED, 不会按同一个
      // 价格卖出另一片地。失败后下面的 invalidateAll 重拉, 确认框里就是新的范围和价格。
      const { minX, minZ, maxX, maxZ } = buyTarget.bounds
      const result = await callMock('plot.buy', {
        districtId,
        plotId: buyTarget.plotId,
        expectedBounds: { minX, minZ, maxX, maxZ },
        expectedPrice: buyTarget.price,
      })
      invalidateAll()
      notify(
        'success',
        `花了 ${formatCredit(result.price)}，还剩 ${formatCredit(result.balanceAfter)}。到「我的地块」里管朋友和权限。`,
        `你买下了 ${result.plot.code}，现在是户主了`,
      )
    } catch (error: unknown) {
      notify('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setBusy(false)
      // 成功失败都关: 失败回执画在弹窗背后, 不关就把服务端那句话藏起来了 (同住户管理)。
      setBuyOpen(false)
    }
  }

  async function confirmDelete(): Promise<void> {
    if (deleteTarget === null) {
      return
    }
    setBusy(true)
    try {
      await callMock('plot.delete', { districtId, plotId: deleteTarget.plotId })
      invalidateAll()
      setSelectedId(null)
      show(
        'success',
        `已删除 ${deleteTarget.code}，那片地回到公共区域。已记进本区操作记录；这块地以前的地块记录留档，管理员仍可查。`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setBusy(false)
      setDeleteOpen(false)
    }
  }

  async function confirmFreezeAction(): Promise<void> {
    if (freezeAction === null) {
      return
    }
    const { kind, plot } = freezeAction
    setBusy(true)
    try {
      if (kind === 'unfreeze') {
        const result = await callMock('admin.plot.unfreeze', { districtId, plotId: plot.plotId })
        show('success', `${result.plot.code} 已还给 ${result.ownerName}，朋友和三列设置照原样恢复。`)
      } else {
        await callMock('admin.plot.reclaimNow', { districtId, plotId: plot.plotId })
        show('success', `${plot.code} 已收回，现在空置。之前的地块记录已归档，只有管理员看得到。`)
      }
      invalidateAll()
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setBusy(false)
      setFreezeOpen(false)
    }
  }

  function requestBuy(plot: PlotSummary, origin: BuyOrigin): void {
    setSelectedId(plot.plotId)
    setBuyOrigin(origin)
    setBuyTarget(plot)
    setBuyOpen(true)
  }

  const side: ReactNode =
    activeEditor !== null ? (
      <PlotEditorCard
        bounds={bounds}
        corners={activeEditor.corners}
        draft={draft}
        editingPlot={editingPlot}
        missingCorners={missingCorners}
        onCancel={() => {
          setEditor(null)
        }}
        onChange={setCorners}
        onSubmit={() => {
          void submitEditor()
        }}
        problem={problem}
        rules={rules}
        saving={saving}
        unchanged={unchanged}
        unitPrice={market.unitPrice}
      />
    ) : selected === null ? (
      <Surface className="border-dashed">
        <p className="text-muted-foreground text-sm">
          点图上或下面列表里的任意一块地，在这里查看{canBuy ? '和购买' : ''}。
          {abilities.managePlots ? '要划新地块，点右上角“划新地块”。' : ''}
        </p>
      </Surface>
    ) : (
      <PlotSideCard
        abilities={abilities}
        balance={balance}
        buyBlock={market.viewerBlock}
        canBuy={canBuy}
        isMine={selected.plotId === myPlotId}
        marketOpen={market.open}
        now={now}
        onBuy={() => {
          requestBuy(selected, 'side')
        }}
        onDelete={() => {
          setDeleteTarget(selected)
          setDeleteOpen(true)
        }}
        onFreezeAction={(kind) => {
          setFreezeAction({ kind, plot: selected })
          setFreezeOpen(true)
        }}
        onOpenDetail={adminView ? openDetail : null}
        onResize={() => {
          setEditor({ mode: 'resize', plotId: selected.plotId, corners: cornersOf(selected.bounds) })
        }}
        plot={selected}
        unitPrice={market.unitPrice}
      />
    )

  return (
    <div className="flex flex-col gap-4">
      <Panel
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Tag tone="neutral">
              {`${countText(plots.length, plotsTruncated)} 块 · ${countText(vacant, plotsTruncated)} 块空置${
                frozenCount > 0 ? ` · ${countText(frozenCount, plotsTruncated)} 块冻结中` : ''
              }`}
            </Tag>
            {abilities.managePlots ? (
              <Button
                disabled={activeEditor !== null}
                onClick={() => {
                  setEditor({ mode: 'create', corners: EMPTY_CORNERS })
                }}
                size="sm"
              >
                <PlusIcon aria-hidden="true" />
                划新地块
              </Button>
            ) : null}
          </div>
        }
        description={
          abilities.managePlots
            ? '自管区由管理员圈定；里面的地块由区务长来划，服务器代建'
            : '俯视图，上北下南；点一块地查看'
        }
        title="本区地块"
      >
        <div className="flex flex-col gap-3">
          {plotsTruncated ? (
            <TruncatedNotice>
              {`地块太多，平面图和下面的列表只显示了前 ${String(plots.length)} 块；${PLOTS_TRUNCATED_ELSEWHERE}。${truncatedDrawNote}`}
            </TruncatedNotice>
          ) : null}
          <MarketBanner
            balance={balance}
            block={market.viewerBlock}
            plotsTruncated={plotsTruncated}
            vacantCount={vacant}
          />
          <DistrictToastSlot onClear={clear} toast={toast} />
          <div className="@container">
            <div className="grid gap-4 @2xl:grid-cols-[minmax(0,1fr)_17rem]">
              <div className="flex min-w-0 flex-col gap-2">
                <PlotMap
                  bounds={bounds}
                  drawing={
                    activeEditor === null
                      ? null
                      : {
                          draft,
                          valid: draft !== null && problem === null,
                          editingPlotId: editingPlot?.plotId ?? null,
                          onDraw: (area: PlotArea) => {
                            setCorners(cornersOf(area))
                          },
                        }
                  }
                  edgeGap={rules.edgeGap}
                  label={`${districtName}俯视平面图`}
                  myPlotId={myPlotId}
                  now={now}
                  onSelect={select}
                  plots={plots}
                  selectedId={selectedId}
                />
                <MapLegend canDraw={abilities.managePlots} drawing={activeEditor !== null} edgeGap={rules.edgeGap} />
              </div>
              <div className="flex min-w-0 flex-col gap-3">{side}</div>
            </div>
          </div>
          <p className="text-muted-foreground text-xs">
            {`每格 ${formatCredit(market.unitPrice)}，只有管理员能定价，区务长不能改。购买：${market.open ? '开放中' : '未开放'}。付款去向还在定。`}
          </p>
        </div>
      </Panel>

      <Panel
        description={
          adminView
            ? '点一行在图上选中，并在最下面查看这块地的朋友和三列权限；默认只读，可以代改，每次都记为“管理员代改”'
            : abilities.managePlots
              ? '点一行在图上选中。有户主的地块由户主做主，区务长只能看'
              : '点一行在图上选中。各户地块的朋友和权限由户主自己设置'
        }
        padded={false}
        title="地块列表"
      >
        {listToast.toast === null ? null : (
          <div className="px-4 pt-3 pb-1">
            <DistrictToastSlot onClear={listToast.clear} toast={listToast.toast} />
          </div>
        )}
        {/* 列表在平面图下面, 平板上隔着一屏: 图那边的提示在这里看不见, 列表上方再说一次。 */}
        {plotsTruncated ? (
          <div className="px-4 pt-3 pb-1">
            <TruncatedNotice>
              {`地块太多，列表只显示了前 ${String(plots.length)} 块；${PLOTS_TRUNCATED_ELSEWHERE}。`}
            </TruncatedNotice>
          </div>
        ) : null}
        <DataTable
          columns={columnsFor({
            adminView,
            showStatus: abilities.viewPlotStatus,
            myPlotId,
            now,
            canBuy,
            balance,
            onBuy: (plot) => {
              requestBuy(plot, 'list')
            },
          })}
          emptyHint="本区还没有划地块"
          onRowClick={(row) => {
            select(row.plotId)
          }}
          rowKey={(row) => row.plotId}
          rows={plots}
          selectedRowKey={selected?.plotId}
        />
        <div className="flex flex-col gap-1 border-t px-4 py-3 text-muted-foreground text-xs">
          <p>
            {'地块由区务长在自管区里划，住户直接购买，先到先得，一人最多一块；价格 = 面积 × 每格单价。退地规则还在定。户主被移出本区后，地块原地冻结 7 天再收回，期间除管理员外谁都不能进出和操作。'}
          </p>
          <p>
            {'自管区里除去地块的部分是公共区域，由区务长逐项开关；每块地由户主自己决定朋友、其他住户、外人能做什么。管理员（OP）可以进入所有地块并拥有全部权限。地块不分高度，默认是从地底到天空的整列。'}
          </p>
          {adminView ? (
            <p>
              每块地是本区 Flan 领地下的一块子领地，由服务器代建：建好后每项权限都写成明确值，地块的组用独立组名，不沿用自管区设置；住户增删时服务器会同步每块地的“居民”组。
            </p>
          ) : (
            <p>
              {abilities.managePlots
                ? '别人地块的朋友和权限由户主做主，你改不了；空置地块你可以调整范围或删除。'
                : '别人地块的朋友和权限由户主做主，你只能看。'}
              {myPlotId === null ? '' : '你自己的地块在「我的地块」里照常自己管。'}
            </p>
          )}
        </div>
      </Panel>

      {adminView ? (
        <div className="flex scroll-mt-4 flex-col gap-4" ref={detailRef}>
          {selected === null ? (
            <Surface className="border-dashed">
              <p className="text-muted-foreground text-sm">选中一块地，在这里查看它的朋友名单和三列权限。</p>
            </Surface>
          ) : (
            <PlotDetail districtId={districtId} key={selected.plotId} plotId={selected.plotId} />
          )}
        </div>
      ) : null}

      {data.deletedPlots === null ? null : (
        <DeletedPlotsPanel plots={data.deletedPlots} truncated={data.deletedPlotsTruncated} />
      )}

      <ConfirmDangerDialog
        confirmDisabled={buyTarget === null || buyTarget.price === null || balance === null || balance < buyTarget.price}
        confirmLabel="确认购买"
        // 买地不是破坏性操作: 确认键用常规样式, 红色读起来像删除 (同市场的购买确认)。
        confirmVariant="default"
        loading={busy}
        message={
          buyTarget === null || buyTarget.price === null
            ? ''
            : `一人最多一块地；买下后你立刻成为 ${buyTarget.code} 的户主，可以在「我的地块」里管朋友和权限。付款后这块地归你；退地规则还在定。以前有户主的地块里可能还留着东西，怎么处理还在定。`
        }
        onConfirm={() => {
          void confirmBuy()
        }}
        onOpenChange={setBuyOpen}
        open={buyOpen && buyTarget !== null}
        title={buyTarget === null ? '买下地块？' : `买下 ${buyTarget.code}？`}
      >
        {buyTarget === null || buyTarget.price === null ? null : (
          <BuyBreakdown balance={balance} plot={buyTarget} price={buyTarget.price} unitPrice={market.unitPrice} />
        )}
      </ConfirmDangerDialog>

      <ConfirmDangerDialog
        confirmLabel="删除地块"
        loading={busy}
        message={
          deleteTarget === null
            ? ''
            : `${deleteTarget.code}（${boundsSize(deleteTarget.bounds)}）是空置的，删掉后那片地回到公共区域，服务器会删掉这块子领地。编号不会再用。这块地以前的地块记录不会跟着删，留档给管理员查。`
        }
        onConfirm={() => {
          void confirmDelete()
        }}
        onOpenChange={setDeleteOpen}
        open={deleteOpen && deleteTarget !== null}
        title={deleteTarget === null ? '删除地块？' : `删除 ${deleteTarget.code}？`}
      />

      <ConfirmDangerDialog
        confirmLabel={freezeAction?.kind === 'unfreeze' ? '解除冻结' : '立即收回'}
        // 解除冻结是把地还给原户主, 不是破坏: 只有"立即收回"用红色确认键。
        confirmVariant={freezeAction?.kind === 'unfreeze' ? 'default' : 'destructive'}
        loading={busy}
        message={
          freezeAction === null || freezeAction.plot.frozen === null
            ? ''
            : freezeAction.kind === 'unfreeze'
              ? `把 ${freezeAction.plot.code} 照原样还给原户主 ${freezeAction.plot.frozen.formerOwnerName}：朋友名单和三列设置都恢复，马上生效。`
              : `不等冻结期满，马上收回 ${freezeAction.plot.code}：变成空置，原户主 ${freezeAction.plot.frozen.formerOwnerName} 存下的朋友名单清空、三列回到默认，之前的地块记录归档（只有管理员看得到）。不能撤销。${RECLAIM_LEFTOVER_NOTE}`
        }
        onConfirm={() => {
          void confirmFreezeAction()
        }}
        onOpenChange={setFreezeOpen}
        open={freezeOpen && freezeAction !== null}
        title={
          freezeAction === null
            ? '处理冻结中的地块'
            : freezeAction.kind === 'unfreeze'
              ? `解除 ${freezeAction.plot.code} 的冻结？`
              : `立即收回 ${freezeAction.plot.code}？`
        }
      />
    </div>
  )
}

// ============================================================
// 图例与购买提示
// ============================================================

function MapLegend({
  edgeGap,
  drawing,
  canDraw,
}: {
  edgeGap: number
  drawing: boolean
  /** 能划地块的 (区务长、管理员) 才需要知道"虚线内能划"; 其他人只需知道虚线外一圈是公共区域。 */
  canDraw: boolean
}): ReactElement {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground text-xs">
      {PLOT_LEGEND.map((entry) => (
        <span className="inline-flex items-center gap-1.5" key={entry.kind}>
          <span aria-hidden="true" className={`inline-block size-3 rounded-[2px] border ${entry.swatch}`} />
          {entry.label}
        </span>
      ))}
      <span className="inline-flex items-center gap-1.5">
        <span aria-hidden="true" className="inline-block size-3 rounded-[2px] border-2 border-foreground" />
        选中
      </span>
      {drawing ? (
        <span className="inline-flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className="inline-block size-3 rounded-[2px] border border-dashed border-foreground"
          />
          新范围（红框 = 不合规）
        </span>
      ) : null}
      <span>
        {canDraw
          ? `上北下南 · 虚线内是可以划地块的地方（离边界 ${String(edgeGap)} 格）`
          : `上北下南 · 虚线外一圈（${String(edgeGap)} 格）是公共区域`}
      </span>
    </div>
  )
}

/** 住户视角的一句购买提示: 能买、本区还没开放、原来的地块还冻结着。已有地块、不是住户的不画。 */
function MarketBanner({
  block,
  balance,
  vacantCount,
  plotsTruncated,
}: {
  block: DistrictPlotsResult['market']['viewerBlock']
  balance: number | null
  vacantCount: number
  /** 地块列表被服务端截断: vacantCount 只数了显示出来的那些, 为 0 不等于本区没有空置地块。 */
  plotsTruncated: boolean
}): ReactElement | null {
  if (block === null) {
    const balanceText =
      balance === null
        ? `${BALANCE_UNREADABLE_TEXT}，现在还买不了，稍后再来看。`
        : `你现在有 ${formatCredit(balance)}${previewBalanceNote()}。`
    // 被截掉的是列表末尾, 也就是最新划出的那些 —— 多半正是还没卖出去的空置地块, 不能照常说"没有空置地块"。
    const noVacantText = plotsTruncated
      ? '显示出来的地块里没有空置的；地块没有显示全，没显示的里面可能还有，请联系管理员确认。'
      : '本区现在没有空置地块，等区务长划出新地块后再来买。'
    return (
      <Surface tone="brand">
        <p className="text-foreground text-sm">
          {vacantCount === 0
            ? `你还没有地块。${noVacantText}${balanceText}`
            : `你还没有地块：在图上或列表里选一块空置的（绿色虚线框），直接买下，先到先得，一人最多一块。${balanceText}`}
        </p>
      </Surface>
    )
  }
  if (block === 'PURCHASE_CLOSED') {
    return (
      <Surface>
        <p className="text-foreground text-sm">本区暂未开放购买。开放后，没有地块的住户可以直接买一块空置的，先到先得。</p>
      </Surface>
    )
  }
  if (block === 'HAS_FROZEN_PLOT') {
    return (
      <Surface tone="warning">
        <p className="text-foreground text-sm">{BUY_BLOCK_TEXT.HAS_FROZEN_PLOT}。</p>
      </Surface>
    )
  }
  return null
}

function BuyBreakdown({
  plot,
  price,
  unitPrice,
  balance,
}: {
  plot: PlotSummary
  price: number
  unitPrice: number
  balance: number | null
}): ReactElement {
  const after = balance === null ? null : balance - price
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col gap-1 text-sm">
        <li className="flex items-center justify-between gap-3">
          <span className="text-muted-foreground">
            {`价格（${formatArea(plot.area)} × 每格 ${formatCredit(unitPrice)}）`}
          </span>
          <Currency amount={price} currency="credit" />
        </li>
        <li className="flex items-center justify-between gap-3">
          <span className="text-muted-foreground">{`当前余额${previewBalanceNote()}`}</span>
          {balance === null ? <span className="text-muted-foreground">未知</span> : <Currency amount={balance} currency="credit" />}
        </li>
        <li className="flex items-center justify-between gap-3 border-t pt-1">
          <span className="text-muted-foreground">购买后余额</span>
          {after === null ? (
            <span className="text-muted-foreground">未知</span>
          ) : (
            <Currency amount={after} currency="credit" signed={after < 0} />
          )}
        </li>
      </ul>
      {after !== null && after < 0 ? (
        <p className="text-destructive text-xs">{`余额不足：还差 ${formatCredit(-after)}，买不了。`}</p>
      ) : null}
      <p className="text-muted-foreground text-xs">付款去向还在定。</p>
    </div>
  )
}

// ============================================================
// 选中地块的操作卡
// ============================================================

function PlotSideCard({
  plot,
  abilities,
  isMine,
  canBuy,
  buyBlock,
  balance,
  marketOpen,
  unitPrice,
  now,
  onBuy,
  onResize,
  onDelete,
  onFreezeAction,
  onOpenDetail,
}: {
  plot: PlotSummary
  abilities: DistrictAbilities
  isMine: boolean
  canBuy: boolean
  buyBlock: PlotBuyBlock | null
  balance: number | null
  marketOpen: boolean
  unitPrice: number
  now: number
  onBuy: () => void
  onResize: () => void
  onDelete: () => void
  onFreezeAction: (kind: 'unfreeze' | 'reclaim') => void
  /** 管理员: 滚到最下面的朋友与权限; 其余身份 null。 */
  onOpenDetail: (() => void) | null
}): ReactElement {
  const frozen = plot.frozen
  return (
    <Surface>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-foreground text-sm">{plot.code}</span>
          <Tag size="sm" tone={PLOT_STATUS_TONE[plot.status]}>
            {PLOT_STATUS_LABEL[plot.status]}
          </Tag>
          {isMine ? (
            <Tag size="sm" tone="brand">
              我的
            </Tag>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5">
          {plot.status === 'owned' ? <Stat label="户主" layout="inline" value={plot.ownerName ?? '—'} /> : null}
          {frozen === null ? null : <Stat label="原户主" layout="inline" value={frozen.formerOwnerName} />}
          <Stat label="大小" layout="inline" value={`${boundsSize(plot.bounds)}，${formatArea(plot.area)}`} />
          <span className="text-muted-foreground text-xs">{formatBounds(plot.bounds)}</span>
          {plot.price === null ? null : (
            <Stat label="价格" layout="inline" value={<Currency amount={plot.price} currency="credit" />} />
          )}
        </div>

        {plot.status === 'vacant' ? (
          <VacantActions
            abilities={abilities}
            balance={balance}
            buyBlock={buyBlock}
            canBuy={canBuy}
            marketOpen={marketOpen}
            onBuy={onBuy}
            onDelete={onDelete}
            onResize={onResize}
            plot={plot}
            unitPrice={unitPrice}
          />
        ) : null}

        {plot.status === 'owned' ? (
          <OwnedActions abilities={abilities} isMine={isMine} onOpenDetail={onOpenDetail} onResize={onResize} plot={plot} />
        ) : null}

        {frozen === null ? null : (
          <div className="flex flex-col gap-2">
            <Surface tone="warning">
              <div className="flex flex-col gap-1">
                <span className="flex items-center gap-1.5 font-medium text-foreground text-sm">
                  <SnowflakeIcon aria-hidden="true" className="size-4 shrink-0" />
                  {freezeLeftText(frozen.reclaimAt, now)}
                </span>
                <span className="text-muted-foreground text-xs">
                  {`原户主 ${frozen.formerOwnerName} 已被移出本区。地块里的东西不动，除管理员外所有人（包括原来的朋友）都不能进出和操作；${formatDateTime(frozen.reclaimAt)} 到期由服务器收回，变成空置。${RECLAIM_LEFTOVER_NOTE}`}
                </span>
              </div>
            </Surface>
            {abilities.manageFrozenPlots ? (
              <div className="flex flex-col gap-1.5">
                <div className="flex flex-wrap gap-2">
                  {frozen.canRestore === true ? (
                    <Button
                      onClick={() => {
                        onFreezeAction('unfreeze')
                      }}
                      size="sm"
                      variant="outline"
                    >
                      解除冻结
                    </Button>
                  ) : (
                    <Hint content={`原户主 ${frozen.formerOwnerName} 现在不是本区住户（或已另有地块），先把 TA 加回本区才能还给 TA`}>
                      <Button disabled size="sm" variant="outline">
                        解除冻结
                      </Button>
                    </Hint>
                  )}
                  <Button
                    onClick={() => {
                      onFreezeAction('reclaim')
                    }}
                    size="sm"
                    variant="destructive-outline"
                  >
                    立即收回
                  </Button>
                </div>
                {frozen.canRestore === true ? null : (
                  <p className="text-muted-foreground text-xs">
                    {`解除冻结要原户主 ${frozen.formerOwnerName} 重新成为本区住户。`}
                  </p>
                )}
              </div>
            ) : (
              <LockedLine>
                {`只能看：解除冻结、立即收回由管理员处理。${abilities.managePlots ? '冻结中的地块也不能改范围或删。' : ''}`}
              </LockedLine>
            )}
            {onOpenDetail === null ? null : (
              <Button onClick={onOpenDetail} size="sm" variant="ghost">
                查看原户主存下的朋友和权限
              </Button>
            )}
          </div>
        )}
      </div>
    </Surface>
  )
}

function LockedLine({ children }: { children: ReactNode }): ReactElement {
  return (
    <p className="flex items-start gap-1.5 text-muted-foreground text-xs">
      <LockIcon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
      <span className="min-w-0">{children}</span>
    </p>
  )
}

function VacantActions({
  plot,
  abilities,
  canBuy,
  buyBlock,
  balance,
  marketOpen,
  unitPrice,
  onBuy,
  onResize,
  onDelete,
}: {
  plot: PlotSummary
  abilities: DistrictAbilities
  canBuy: boolean
  /** 服务端说的"为什么不能买"; 住户视角据此写一句 (本区暂未开放购买 / 已有地块…)。 */
  buyBlock: PlotBuyBlock | null
  balance: number | null
  marketOpen: boolean
  unitPrice: number
  onBuy: () => void
  onResize: () => void
  onDelete: () => void
}): ReactElement {
  const price = plot.price
  return (
    <div className="flex flex-col gap-2">
      {canBuy && price !== null ? (
        <div className="flex flex-col gap-1.5">
          <Button disabled={balance === null || balance < price} onClick={onBuy} size="sm">
            <ShoppingCartIcon aria-hidden="true" />
            购买
          </Button>
          {balance === null ? (
            <p className="text-destructive text-xs">{`${BALANCE_UNREADABLE_TEXT}，现在还买不了，稍后再来看。`}</p>
          ) : balance < price ? (
            <p className="text-destructive text-xs">
              {`余额不足：你有 ${formatCredit(balance)}${previewBalanceNote()}，还差 ${formatCredit(price - balance)}。`}
            </p>
          ) : (
            <p className="text-muted-foreground text-xs">
              {`${formatArea(plot.area)} × 每格 ${formatCredit(unitPrice)}；你有 ${formatCredit(balance)}${previewBalanceNote()}，先到先得。`}
            </p>
          )}
        </div>
      ) : null}
      {buyBlock !== null && buyBlock !== 'NOT_RESIDENT' ? (
        <p className="text-muted-foreground text-xs">{`${BUY_BLOCK_TEXT[buyBlock]}。`}</p>
      ) : null}
      {abilities.managePlotMarket ? (
        <p className="text-muted-foreground text-xs">
          {marketOpen ? '本区开放购买中，住户可以直接买。' : '本区购买未开放（在「管理员操作」里开）。'}
        </p>
      ) : null}
      {abilities.managePlots ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={onResize} size="sm" variant="outline">
            <PencilIcon aria-hidden="true" />
            调整范围
          </Button>
          <Button onClick={onDelete} size="sm" variant="destructive-outline">
            <Trash2Icon aria-hidden="true" />
            删除
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function OwnedActions({
  plot,
  abilities,
  isMine,
  onResize,
  onOpenDetail,
}: {
  plot: PlotSummary
  abilities: DistrictAbilities
  isMine: boolean
  onResize: () => void
  onOpenDetail: (() => void) | null
}): ReactElement | null {
  if (abilities.overridePlots) {
    return (
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap gap-2">
          <Button onClick={onResize} size="sm" variant="outline">
            <PencilIcon aria-hidden="true" />
            调整范围（代改）
          </Button>
          <Hint content="有户主的地块不能删；户主被移出本区后会先冻结 7 天再收回">
            <Button disabled size="sm" variant="destructive-outline">
              <Trash2Icon aria-hidden="true" />
              删除
            </Button>
          </Hint>
        </div>
        <p className="text-muted-foreground text-xs">
          {`这是 ${plot.ownerName ?? '户主'} 的家：改范围会记成“管理员代改”，写进地块记录通知户主。`}
        </p>
        {onOpenDetail === null ? null : (
          <Button onClick={onOpenDetail} size="sm" variant="ghost">
            查看朋友和权限
          </Button>
        )}
      </div>
    )
  }
  if (abilities.managePlots) {
    return (
      <LockedLine>
        {isMine
          ? '这是你自己的地块。区务长也不能改有户主的地块范围（包括自己的），要改请找管理员。'
          : '有户主：区务长不能改范围、不能删，那是别人的家。要改请找管理员。'}
      </LockedLine>
    )
  }
  return isMine ? <p className="text-muted-foreground text-xs">这是你的地块，在「我的地块」里管朋友和权限。</p> : null
}

// ============================================================
// 划 / 调地块
// ============================================================

const CORNER_FIELDS: readonly { key: keyof CornerInputs; axis: 'X' | 'Z'; corner: 1 | 2 }[] = [
  { key: 'x1', axis: 'X', corner: 1 },
  { key: 'z1', axis: 'Z', corner: 1 },
  { key: 'x2', axis: 'X', corner: 2 },
  { key: 'z2', axis: 'Z', corner: 2 },
]

function PlotEditorCard({
  editingPlot,
  bounds,
  corners,
  missingCorners,
  draft,
  problem,
  unchanged,
  rules,
  unitPrice,
  saving,
  onChange,
  onSubmit,
  onCancel,
}: {
  /** 调整时是那块地; 新划为 null。 */
  editingPlot: PlotSummary | null
  /** 自管区范围: 写出"可划范围"给填坐标的人看 (平面图上一格才一两个像素, 拖完多半要在这里微调)。 */
  bounds: DistrictBounds
  corners: CornerInputs
  /** 四个坐标框里还空着几个。 */
  missingCorners: number
  draft: PlotArea | null
  problem: string | null
  unchanged: boolean
  rules: DistrictPlotsResult['rules']
  unitPrice: number
  saving: boolean
  onChange: (next: CornerInputs) => void
  onSubmit: () => void
  onCancel: () => void
}): ReactElement {
  const occupiedOwner = editingPlot?.status === 'owned' ? editingPlot.ownerName : null
  const ready = draft !== null && problem === null && !unchanged
  const gap = rules.edgeGap
  const drawable =
    bounds.maxX - bounds.minX + 1 > gap * 2 && bounds.maxZ - bounds.minZ + 1 > gap * 2
      ? `可划范围：X ${String(bounds.minX + gap)} ~ ${String(bounds.maxX - gap)}，Z ${String(bounds.minZ + gap)} ~ ${String(bounds.maxZ - gap)}（游戏里按 F3 看坐标）`
      : '这个自管区太小，扣掉四周的公共区域后划不下地块'
  return (
    <Surface tone={occupiedOwner === null ? 'neutral' : 'warning'}>
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-0.5">
          <span className="font-medium text-foreground text-sm">
            {editingPlot === null ? '划新地块' : `调整 ${editingPlot.code} 的范围`}
          </span>
          <span className="text-muted-foreground text-xs">
            {editingPlot === null
              ? '在平面图上按住拖出一个矩形（拖完可以在下面微调坐标），或者填两个对角的坐标（顺序随意，整数，含两端的方块）。'
              : '在平面图上重新拖出整块新范围（不能只拖一条边），或直接改下面的坐标。'}
          </span>
          <span className="text-foreground text-xs">{drawable}</span>
        </div>

        <div className="grid grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)] items-center gap-x-2 gap-y-1.5">
          <span />
          <span className="text-center text-muted-foreground text-xs">X</span>
          <span className="text-center text-muted-foreground text-xs">Z</span>
          {([1, 2] as const).map((corner) => (
            <CornerRow corner={corner} corners={corners} disabled={saving} key={corner} onChange={onChange} />
          ))}
        </div>

        {/* 边拖边变的几行: 用礼貌播报而不是 role=alert, 拖动时每变一次都打断读屏太吵。 */}
        <div aria-live="polite" className="flex flex-col gap-0.5 text-xs">
          {draft === null ? (
            missingCorners === 0 ? null : (
              <span className="text-muted-foreground">
                {missingCorners === 4 ? '还没有范围' : `还差 ${String(missingCorners)} 个坐标`}
              </span>
            )
          ) : (
            <span className="text-foreground">
              {`${String(areaWidth(draft))} × ${String(areaDepth(draft))}，${formatArea(areaBlocks(draft))}`}
              {/* 已买下的地块改范围怎么结算还没定: 不写"按现价", 免得户主以为要补钱或退钱。 */}
              {occupiedOwner === null ? (
                <span className="text-muted-foreground">{` · 按现价 ${formatCredit(areaBlocks(draft) * unitPrice)}`}</span>
              ) : null}
            </span>
          )}
          {problem !== null ? (
            <span className="text-destructive">{problem}</span>
          ) : unchanged ? (
            <span className="text-muted-foreground">和原来的范围一样</span>
          ) : draft !== null ? (
            <span className="text-success">范围可以</span>
          ) : null}
        </div>

        {occupiedOwner === null ? null : (
          <p className="text-foreground text-xs">
            {`这块地有户主 ${occupiedOwner}：改了会记成“管理员代改”，写进地块记录通知户主。已买下的地块改范围暂不补差价、不退款（规则还在定）。`}
          </p>
        )}

        {/* 按钮放在规则清单上面: 平板矮屏上一边在图上拖、一边还要够得着"划出地块"。 */}
        <div className="flex flex-wrap gap-2">
          <Button disabled={!ready} loading={saving} onClick={onSubmit} size="sm">
            {editingPlot === null ? '划出地块' : '保存新范围'}
          </Button>
          <Button disabled={saving} onClick={onCancel} size="sm" variant="ghost">
            取消
          </Button>
        </div>

        <ul className="flex list-disc flex-col gap-0.5 pl-4 text-muted-foreground text-xs">
          <li>{`每边 ${String(rules.minSide)} ~ ${String(rules.maxSide)} 格（管理员可改）`}</li>
          <li>{`离自管区边界至少留 ${String(rules.edgeGap)} 格公共区域（数值待定）`}</li>
          <li>不能和别的地块重叠，贴边可以</li>
          <li>不分高度：地块默认是整列</li>
        </ul>
      </div>
    </Surface>
  )
}

function CornerRow({
  corner,
  corners,
  disabled,
  onChange,
}: {
  corner: 1 | 2
  corners: CornerInputs
  disabled: boolean
  onChange: (next: CornerInputs) => void
}): ReactElement {
  const fields = CORNER_FIELDS.filter((field) => field.corner === corner)
  return (
    <>
      <span className="text-muted-foreground text-xs">角 {corner}</span>
      {fields.map((field) => {
        const value = corners[field.key]
        return (
          <TextInput
            ariaLabel={`角 ${String(corner)} ${field.axis}`}
            className="w-full"
            disabled={disabled}
            invalid={value.trim() !== '' && !isIntegerText(value)}
            key={field.key}
            maxLength={9}
            onChange={(next) => {
              onChange({ ...corners, [field.key]: next })
            }}
            placeholder={field.axis}
            size="sm"
            value={value}
          />
        )
      })}
    </>
  )
}

// ============================================================
// 已删除的地块 (仅管理员)
// ============================================================

const DELETED_PLOT_COLUMNS: readonly DataTableColumn<DeletedPlot>[] = [
  {
    key: 'code',
    header: '地块',
    render: (row) => (
      <span className="flex flex-col">
        <span className="font-medium text-foreground">{row.code}</span>
        <span className="text-muted-foreground text-xs">{`${boundsSize(row.bounds)} · ${formatBounds(row.bounds)}`}</span>
      </span>
    ),
    sortValue: (row) => row.code,
  },
  {
    key: 'deleted',
    header: '删除',
    render: (row) => (
      <span className="flex flex-col">
        <span className="tabular-nums">{formatDateTime(row.deletedAt)}</span>
        <span className="text-muted-foreground text-xs">
          {row.deletedBy} · {formatRelative(row.deletedAt, nowMs())}
        </span>
      </span>
    ),
    sortValue: (row) => row.deletedAt,
  },
  {
    key: 'log',
    header: '留档记录',
    numeric: true,
    render: (row) => `${countText(row.log.length, row.logTruncated)} 条`,
  },
]

const DELETED_PLOTS_DESCRIPTION =
  '区务长能删空置地块，但删不掉它以前的记录：每块删掉的地块记录都留档在这里，只有管理员看得到'

/**
 * 删掉的空置地块的墓碑 (lib/types.ts K19): 地块没了, 它自己的地块记录 (含历任户主期间的归档) 留在这里。
 * 服务端只给管理员下发 (其余身份 deletedPlots 为 null, 调用方根本不渲染本组件); 一块都没有时整块不画
 * (同已解绑自管区的归档)。点一行在下面展开那块地删除前的记录。
 *
 * truncated = 回执的 deletedPlotsTruncated。墓碑排在地块列表之后装进回执, 地块多到把预算用完时可能一块都装不下:
 * 那时 plots 是空的而 truncated 为真, 不能照"一块都没有"整块不画 —— 留档明明在, 只是这次没下发。
 */
function DeletedPlotsPanel({
  plots,
  truncated,
}: {
  plots: readonly DeletedPlot[]
  truncated: boolean
}): ReactElement | null {
  const [openId, setOpenId] = useState<string | null>(null)
  if (plots.length === 0) {
    // 只有管理员看得到这一块, 所以下面两句提示都不写"请联系管理员"; 留档也没有对应的 /district 子命令, 不往那边指。
    return truncated ? (
      <Panel description={DELETED_PLOTS_DESCRIPTION} title="已删除的地块">
        <TruncatedNotice>
          本区有已删除地块的留档，但这次一块都没能显示：本区地块太多，回执里装不下了。留档仍保存在服务器上，平板上暂时看不到。
        </TruncatedNotice>
      </Panel>
    ) : null
  }
  const opened = plots.find((plot) => plot.plotId === openId) ?? null
  return (
    <>
      <Panel
        actions={<Tag tone="neutral">{`${countText(plots.length, truncated)} 块`}</Tag>}
        description={DELETED_PLOTS_DESCRIPTION}
        padded={false}
        title="已删除的地块"
      >
        {truncated ? (
          <div className="px-4 pt-3 pb-1">
            <TruncatedNotice>
              {`已删除的地块太多（或留档记录太长），这里只显示了最近删除的 ${String(plots.length)} 块；其余的留档仍保存在服务器上，平板上暂时看不到。`}
            </TruncatedNotice>
          </div>
        ) : null}
        <DataTable
          columns={DELETED_PLOT_COLUMNS}
          onRowClick={(row) => {
            setOpenId((current) => (current === row.plotId ? null : row.plotId))
          }}
          rowKey={(row) => row.plotId}
          rows={plots}
          selectedRowKey={opened?.plotId}
        />
        <p className="border-t px-4 py-3 text-muted-foreground text-xs">点一行查看它删除前的地块记录，再点一次收起。</p>
      </Panel>
      {opened === null ? null : (
        <PlotLogPanel
          description={`${opened.code} 删除之前的${opened.logTruncated ? '' : '全部'}地块记录（含历任户主期间的，留档，只读）`}
          log={opened.log}
          title={`留档记录 · ${opened.code}`}
          truncated={opened.logTruncated}
        />
      )}
    </>
  )
}

// ============================================================
// 列表
// ============================================================

function PlotSyncCell({ plot, adminView }: { plot: PlotSummary; adminView: boolean }): ReactElement {
  if (plot.syncStatus === null) {
    // 区务长与管理员一定拿得到; 走到这里说明回执与身份对不上, 如实显示未知。
    return <span className="text-muted-foreground">未知</span>
  }
  if (plot.syncStatus === 'synced') {
    return (
      <Tag size="sm" tone="success">
        已生效
      </Tag>
    )
  }
  const tag = (
    <Tag size="sm" tone="danger">
      {adminView ? '写入失败' : '未生效'}
    </Tag>
  )
  return (
    <Hint
      content={
        <span className="block max-w-64 whitespace-normal break-words">
          {adminView
            ? (plot.syncError ?? '服务端没有给出失败原因')
            : '服务器没能把这块地的设置写进领地；下一次有人改这块地时会整块重写，一直不行请找管理员'}
        </span>
      }
    >
      {tag}
    </Hint>
  )
}

function StatusCell({
  plot,
  now,
  canBuy,
  balance,
  onBuy,
}: {
  plot: PlotSummary
  now: number
  canBuy: boolean
  balance: number | null
  onBuy: (plot: PlotSummary) => void
}): ReactElement {
  // 与右边操作卡的"购买"同一口径: 钱不够就直接置灰并说差多少, 余额读不到也置灰并照实说, 不先弹一个确认不了的框。
  const buyDisabledReason =
    plot.price === null
      ? null
      : balance === null
        ? BALANCE_UNREADABLE_TEXT
        : balance < plot.price
          ? `余额不足，还差 ${formatCredit(plot.price - balance)}`
          : null
  const second =
    plot.status === 'vacant' && plot.price !== null
      ? formatCredit(plot.price)
      : plot.status === 'frozen' && plot.frozen !== null
        ? freezeShortText(plot.frozen.reclaimAt, now)
        : null
  return (
    <span className="flex items-center gap-2">
      <span className="flex flex-col items-start gap-0.5">
        <Tag size="sm" tone={PLOT_STATUS_TONE[plot.status]}>
          {PLOT_STATUS_LABEL[plot.status]}
        </Tag>
        {second === null ? null : <span className="text-muted-foreground text-xs">{second}</span>}
      </span>
      {canBuy && plot.status === 'vacant' ? (
        buyDisabledReason !== null ? (
          <Hint content={buyDisabledReason}>
            <Button disabled size="xs" variant="outline">
              购买
            </Button>
          </Hint>
        ) : (
          <Button
            onClick={(event) => {
              // 按钮在可点的行里: 不拦的话点购买也会触发行选中, 两件事叠在一起。
              event.stopPropagation()
              onBuy(plot)
            }}
            onKeyDown={(event) => {
              // 键盘同理, 而且更糟: 行自己接 Enter / 空格来选中 (DataTable), 还会取消按键的默认动作 ——
              // 不拦的话焦点在这个按钮上按 Enter 只选中了行, 购买确认框根本弹不出来。
              event.stopPropagation()
            }}
            size="xs"
            variant="outline"
          >
            购买
          </Button>
        )
      ) : null}
    </span>
  )
}

function columnsFor({
  adminView,
  showStatus,
  myPlotId,
  now,
  canBuy,
  balance,
  onBuy,
}: {
  adminView: boolean
  showStatus: boolean
  myPlotId: string | null
  now: number
  canBuy: boolean
  balance: number | null
  onBuy: (plot: PlotSummary) => void
}): readonly DataTableColumn<PlotSummary>[] {
  const columns: DataTableColumn<PlotSummary>[] = [
    {
      key: 'code',
      header: '地块',
      render: (row) => (
        <span className="flex flex-col">
          <span className="flex items-center gap-1.5">
            <span className="font-medium text-foreground">{row.code}</span>
            {row.plotId === myPlotId ? (
              <Tag size="sm" tone="brand">
                我的
              </Tag>
            ) : null}
          </span>
          <span className="text-muted-foreground text-xs">{formatBounds(row.bounds)}</span>
        </span>
      ),
      sortValue: (row) => row.code,
    },
    {
      key: 'owner',
      header: '户主',
      render: (row) =>
        row.ownerName !== null ? (
          row.ownerName
        ) : row.frozen !== null ? (
          <span className="text-muted-foreground">原户主 {row.frozen.formerOwnerName}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
      sortValue: (row) => (row.ownerName ?? row.frozen?.formerOwnerName ?? '').toLowerCase(),
    },
    {
      key: 'area',
      header: '面积',
      numeric: true,
      render: (row) => (
        <span className="flex flex-col items-end">
          <span>{formatArea(row.area)}</span>
          <span className="text-muted-foreground text-xs">{boundsSize(row.bounds)}</span>
        </span>
      ),
      sortValue: (row) => row.area,
    },
    {
      key: 'friends',
      header: '朋友',
      numeric: true,
      render: (row) =>
        row.status === 'vacant' || row.friendCount === null ? (
          <span className="text-muted-foreground">—</span>
        ) : row.status === 'frozen' ? (
          // 冻结中谁都进不去, 存着的朋友此刻不生效: 照实写, 不和正常地块的"N 人"混成一样。
          <span className="flex flex-col items-end text-muted-foreground">
            <span>{`${String(row.friendCount)} 人`}</span>
            <span className="text-xs">冻结中不生效</span>
          </span>
        ) : (
          `${String(row.friendCount)} 人`
        ),
      sortValue: (row) => row.friendCount ?? -1,
    },
    {
      key: 'status',
      header: '状态',
      render: (row) => <StatusCell balance={balance} canBuy={canBuy} now={now} onBuy={onBuy} plot={row} />,
      sortValue: (row) => ['vacant', 'frozen', 'owned'].indexOf(row.status),
    },
    {
      key: 'sync',
      header: adminView ? 'Flan 子领地' : '领地权限',
      render: (row) => <PlotSyncCell adminView={adminView} plot={row} />,
      sortValue: (row) => (row.syncStatus === 'failed' ? 0 : 1),
    },
  ]
  // 住户拿不到朋友数与生效状态 (回执里是 null): 整列不画, 而不是画一列"—"。
  return showStatus ? columns : columns.filter((column) => column.key !== 'friends' && column.key !== 'sync')
}
