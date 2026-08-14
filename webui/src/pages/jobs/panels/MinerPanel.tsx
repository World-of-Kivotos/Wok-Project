import type { ReactElement } from 'react'
import { useMemo, useState } from 'react'
import {
  Button,
  DataTable,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  ItemIcon,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
} from '@/components/kit'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type { MinerScanResult, MinerStateResult, WebUiBlockPos } from '../../../lib/types'
import { callMock, nowMs, useMockAction } from '../../../mock'
import { formatCountdown, formatStatValue, toError, useLiveNow } from './shared'

/**
 * 矿工面板 (`job.miner.state` / `job.miner.scan`, Java 落点 com.miningdim.job.miner.MinerWebUiActions)。
 * 回执形状见 lib/types.ts 的 MinerStateResult / MinerScanResult。
 *
 * 防 X 光: 等级门 / CD / 半径 / 单矿种 / 64 条硬顶全部由服务端裁决链保证, 本面板只负责把回执画出来 ——
 * 因此这里既没有矿种选择器 (服务端按固定优先序自选, 没有入参能影响它), 也没有任何能放大半径的入口,
 * 探测按钮发的是空 payload。
 *
 * 时间口径: 服务端只发剩余/存活 **tick**, 不发 epoch millis (服务端手里只有 game tick, 折成服务端墙钟
 * 再让客户端拿 Date.now() 去减, 既吃时钟偏移又在掉刻时失真)。故本面板在**收到回执那一刻**把 tick 折成
 * 本地时刻, 之后的倒计时与脉冲熄灭全在本地算。
 *
 * 契约缺口 (报告给核销清单, 不在此处自造接口凑齐):
 *   - 三个开关只读: 没有对应的写 action, 面板只展示当前开合;
 *   - 当日矿物软上限进度已裁出本批: IEconomyService 门面上没有任何当日矿物计数/衰减系数的只读方法,
 *     且该状态无 save/load 重启即清零, 接它要先在门面上开只读方法 (BACKEND 级新增)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}
const MS_PER_TICK = 50

/** 收到回执那一刻把剩余 tick 折成本地到期时刻; 0 tick 即已就绪 (返回 0 表示"不在冷却")。 */
function tickDeadline(remainingTicks: number, receivedAt: number): number {
  return remainingTicks <= 0 ? 0 : receivedAt + remainingTicks * MS_PER_TICK
}

/** 一次探测脉冲的本地快照: 回执本身 + 收到它的时刻 (脉冲与冷却都从这一刻起算)。 */
interface ScanSnapshot {
  result: MinerScanResult
  receivedAt: number
}

function ToggleTag({
  label,
  unlocked,
  enabled,
}: {
  label: string
  unlocked: boolean
  enabled: boolean
}): ReactElement {
  if (!unlocked) {
    return (
      <Tag size="sm" tone="neutral">
        {label} · 未解锁
      </Tag>
    )
  }
  return (
    <Tag size="sm" tone={enabled ? 'success' : 'neutral'}>
      {label} · {enabled ? '已开启' : '已关闭'}
    </Tag>
  )
}

function ScanResultView({
  snapshot,
  oreName,
  now,
}: {
  snapshot: ScanSnapshot
  oreName: string | null
  now: number
}): ReactElement {
  const expiresAt = snapshot.receivedAt + snapshot.result.pulseTicks * MS_PER_TICK
  const expired = now >= expiresAt
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        {snapshot.result.oreItemId === null ? (
          <span className="text-muted-foreground">本次脉冲未命中任何可探测矿脉</span>
        ) : (
          <span className="flex items-center gap-1 text-muted-foreground">
            命中矿种
            <ItemIcon itemId={snapshot.result.oreItemId} label={oreName ?? snapshot.result.oreItemId} />
            <span className="text-foreground">{oreName ?? snapshot.result.oreItemId}</span>
          </span>
        )}
        <span className="text-muted-foreground">
          {expired ? '本次脉冲已熄灭' : `脉冲还剩 ${formatCountdown(expiresAt, now)} 熄灭`} · 命中{' '}
          {snapshot.result.hits.length} 处
        </span>
      </div>
      {expired ? null : (
        <DataTable<WebUiBlockPos>
          columns={[
            { header: 'X', key: 'x', numeric: true, render: (row) => String(row.x) },
            { header: 'Y', key: 'y', numeric: true, render: (row) => String(row.y) },
            { header: 'Z', key: 'z', numeric: true, render: (row) => String(row.z) },
          ]}
          emptyHint="本次脉冲未命中矿脉"
          rowKey={(row) => `${String(row.x)}_${String(row.y)}_${String(row.z)}`}
          rows={snapshot.result.hits}
        />
      )}
    </div>
  )
}

export function MinerPanel(): ReactElement {
  const query = useMockAction('job.miner.state', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<Error | null>(null)
  const [scan, setScan] = useState<ScanSnapshot | null>(null)

  const data: MinerStateResult | null = query.status === 'ready' ? query.data : null

  /*
   * state 里那个剩余 tick 只在收到回执那一刻有意义, 故在 data 这个引用刚换新时折一次本地时刻。
   * data 只在一次新的回执到达时才换引用, 因此这份折算恰好每条回执做一次。
   */
  const stateScanReadyAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.scanCooldownRemainingTicks, nowMs())),
    [data],
  )

  const toggleLabels = useItemNames(
    data === null ? [] : data.toggles.map((toggle) => `skill.miningdim.miner.${toggle.skillId}`),
  )
  const passiveLabels = useItemNames(data === null ? [] : data.passives.map((line) => line.labelKey))
  const scanOreNames = useItemNames(
    scan === null || scan.result.oreDescriptionId === null ? [] : [scan.result.oreDescriptionId],
  )

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取矿工档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.miner.state 回执为空" onRetry={query.reload} />
  }

  /*
   * 冷却取"state 折出来的"与"刚探测那次折出来的"两者较大值: 冷却只会因探测而变长, 这样就不必为刷新
   * 一个字段而重查整个 job.miner.state (重查会让本页闪一次骨架屏, 把刚展示出来的命中结果盖掉)。
   */
  const scanReadyAt =
    scan === null
      ? stateScanReadyAt
      : Math.max(stateScanReadyAt, tickDeadline(scan.result.scanCooldownRemainingTicks, scan.receivedAt))
  const scanReady = now >= scanReadyAt

  async function handleScan(): Promise<void> {
    setScanning(true)
    setScanError(null)
    try {
      const result = await callMock('job.miner.scan', {})
      // 收到的那一刻就是脉冲与冷却的起点, 之后一律用它算, 不再问服务端。
      setScan({ result, receivedAt: nowMs() })
    } catch (error) {
      setScanError(toError(error))
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Panel title="矿工">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="探测半径"
              value={data.scanUnlocked ? `${String(data.scanRadius)} 格` : '未解锁'}
            />
            <Stat label="挖掘疲劳" value={data.miningFatigueImmune ? '已免疫' : '未免疫'} />
          </div>
          <p className="text-muted-foreground text-xs">三个开关暂不可在此调整, 这里只显示当前状态</p>
        </div>
      </Panel>

      <Panel title="被动数值">
        {data.passives.length === 0 ? (
          <EmptyBlock title="暂无被动加成" />
        ) : (
          <DataTable
            columns={[
              {
                header: '属性',
                key: 'label',
                render: (row) => passiveLabels[row.labelKey] ?? row.labelKey,
              },
              {
                header: '数值',
                key: 'value',
                numeric: true,
                render: (row) => formatStatValue(row.value, row.unit),
                sortValue: (row) => row.value,
              },
            ]}
            rowKey={(row) => row.key}
            rows={data.passives}
          />
        )}
      </Panel>

      <Panel title="连锁充能与开关">
        <div className="flex flex-col gap-3">
          <Meter
            label="充能"
            max={data.chargeMax === 0 ? 1 : data.chargeMax}
            tone="brand"
            value={data.charge}
            valueText={`${String(data.charge)} / ${String(data.chargeMax)}`}
          />
          <div className="flex flex-wrap items-center gap-2">
            {data.toggles.map((toggle) => (
              <ToggleTag
                enabled={toggle.enabled}
                key={toggle.skillId}
                label={
                  toggleLabels[`skill.miningdim.miner.${toggle.skillId}`] ??
                  `skill.miningdim.miner.${toggle.skillId}`
                }
                unlocked={toggle.unlocked}
              />
            ))}
          </div>
        </div>
      </Panel>

      <Panel title="探测脉冲">
        <div className="flex flex-col gap-3">
          {data.scanUnlocked ? (
            <>
              <div className="flex flex-wrap items-center gap-3">
                <Button
                  disabled={!scanReady}
                  loading={scanning}
                  onClick={() => {
                    void handleScan()
                  }}
                  variant="brand"
                >
                  发起探测脉冲
                </Button>
                <span className="text-muted-foreground text-sm">
                  {scanReady ? '就绪, 可立即探测' : `冷却中, 剩余 ${formatCountdown(scanReadyAt, now)}`}
                </span>
              </div>
              <p className="text-muted-foreground text-xs">
                一次只探一种矿, 矿种由服务端按固定优先序自选; 半径与命中数上限同样由服务端裁决
              </p>
              {scanError === null ? null : (
                <FeedbackAlert message={callErrorText(scanError)} tone="danger" />
              )}
              {scan === null ? (
                <EmptyBlock hint="点击上方按钮发起一次探测脉冲, 结果会在此列出" title="尚未进行有效的探测" />
              ) : (
                <ScanResultView
                  now={now}
                  oreName={
                    scan.result.oreDescriptionId === null
                      ? null
                      : (scanOreNames[scan.result.oreDescriptionId] ?? scan.result.oreDescriptionId)
                  }
                  snapshot={scan}
                />
              )}
            </>
          ) : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                探测需要矿工 {data.scanUnlockLevel} 级 (当前 {data.level} 级)
              </p>
            </Surface>
          )}
        </div>
      </Panel>
    </div>
  )
}
