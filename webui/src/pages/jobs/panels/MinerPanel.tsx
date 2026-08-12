import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelLoading,
  PixelProgress,
  PixelSelect,
  PixelTable,
} from '../../../components/pixel'
import { useItemNames } from '../../../lib/i18n'
import { callMock, useMockAction } from '../../../mock'
import type { PlannedDailyOreLine, PlannedMinerScanResult } from '../../../mock'
import { formatCountdown, formatStatValue, toError, useLiveNow } from './shared'

/**
 * 矿工面板 (接线清单 C5 job.miner.state / C6 job.miner.scan / C7 当日矿物软上限进度, 均为 PLANNED)。
 *
 * 依赖的假定契约:
 *   - job.miner.state -> PlannedMinerStateResult (被动数值/连锁充能/探测 CD 与半径/当日矿物软上限)
 *   - job.miner.scan   -> PlannedMinerScanResult (一次性探测快照, 带 expiresAt —— 过期后前端必须自行
 *     熄灭, 不等下一次 state 覆盖, 与 AgentPanel 的战术扫描同一条纪律, 故本面板把扫描结果存在本地
 *     state 而不是长期信任 job.miner.state)
 *
 * C6 的硬约束: 服务端已裁决好等级门/CD/半径, webui 版只是把命中坐标 JSON 化, **必须保留同等防 X 光
 * 限制**(单矿种一次 + 有限半径 + 脉冲熄灭) —— 本面板因此只暴露"选一种矿 + 发起一次脉冲", 不提供放大
 * 半径或同时多矿种探测的入口。
 *
 * 契约缺口 (报告给核销清单, 不在此处自造接口凑齐):
 *   - 连锁开关 (chainEnabled) 没有对应的写 action, 只能只读展示当前状态;
 *   - C7 标注 BACKEND: PlayerAbuseState 有 public getter 但 IEconomyService 无对应查询方法, 且该态
 *     save/load 零调用方、重启即清零 —— 当日矿物软上限进度是本切片持久化最弱的一段, 接线时需要先补
 *     这条查询方法, 不是简单薄封装。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}
// 模块级稳定引用: query 未就绪时的兜底值若每次渲染都新建一个 [] 字面量, 会让下方依赖它的 useEffect
// 判定"依赖变了"而在 loading 期间每帧重跑 (react-hooks/exhaustive-deps 的提示)。
const EMPTY_DAILY_ORES: readonly PlannedDailyOreLine[] = []

function DailyOreRow({ ore, name }: { ore: PlannedDailyOreLine; name: string }): ReactElement {
  const decayed = ore.decayFactor < 1
  return (
    <div className="flex items-center gap-3">
      <ItemIcon itemId={ore.itemId} label={name} />
      <div className="flex flex-1 flex-col gap-1">
        <div className="flex items-center justify-between text-1x">
          <span className="text-fg">{name}</span>
          <span className={decayed ? 'text-warning' : 'text-muted'}>系数 {ore.decayFactor}</span>
        </div>
        <PixelProgress
          value={ore.minedToday}
          max={ore.softCap}
          tone={decayed ? 'warning' : 'accent'}
          label={`${String(ore.minedToday)}/${String(ore.softCap)}`}
        />
      </div>
    </div>
  )
}

function ScanResultView({ result, now }: { result: PlannedMinerScanResult; now: number }): ReactElement {
  const expired = now >= result.expiresAt
  return (
    <div className="flex flex-col gap-2">
      <span className="text-1x text-muted">
        {expired ? '本次脉冲已熄灭' : `脉冲还剩 ${formatCountdown(result.expiresAt, now)} 熄灭`} · 命中{' '}
        {result.hits.length} 处
      </span>
      {expired ? null : (
        <PixelTable
          columns={[
            { key: 'x', header: 'X', render: (row) => String(row.x) },
            { key: 'y', header: 'Y', render: (row) => String(row.y) },
            { key: 'z', header: 'Z', render: (row) => String(row.z) },
          ]}
          rows={result.hits}
          rowKey={(row) => `${String(row.x)}_${String(row.y)}_${String(row.z)}`}
          emptyHint="本次脉冲未命中矿脉"
        />
      )}
    </div>
  )
}

export function MinerPanel(): ReactElement {
  const query = useMockAction('job.miner.state', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [selectedOreId, setSelectedOreId] = useState<string | null>(null)
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<Error | null>(null)
  const [scanResult, setScanResult] = useState<PlannedMinerScanResult | null>(null)

  const dailyOres = query.status === 'ready' ? query.data.dailyOres : EMPTY_DAILY_ORES
  const oreNames = useItemNames(dailyOres.map((ore) => ore.descriptionId))

  // 首次拿到数据后默认选中第一种矿; selectedOreId 一旦非空就不再被这条效果覆盖, 不会打断玩家的手动切换。
  useEffect(() => {
    if (selectedOreId === null && dailyOres.length > 0) {
      const first = dailyOres[0]
      if (first !== undefined) {
        setSelectedOreId(first.itemId)
      }
    }
  }, [dailyOres, selectedOreId])

  if (query.status === 'loading') {
    return <PixelLoading label="正在读取矿工档案" />
  }
  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data
  const levelGated = data.level < data.scanUnlockLevel
  // 冷却优先取"刚提交那次拿回的" scanReadyAt: 冷却只会因探测而变长, 取两者较大值免去为刷新一个字段
  // 而重查整个 job.miner.state (重查会让本页闪一次骨架屏, 掩盖掉刚展示出来的命中结果)。
  const effectiveScanReadyAt =
    scanResult === null ? data.scanReadyAt : Math.max(data.scanReadyAt, scanResult.scanReadyAt)
  const scanReady = now >= effectiveScanReadyAt

  async function handleScan(): Promise<void> {
    if (selectedOreId === null) {
      return
    }
    setScanning(true)
    setScanError(null)
    try {
      const result = await callMock('job.miner.scan', { oreItemId: selectedOreId })
      setScanResult(result)
    } catch (error) {
      setScanError(toError(error))
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="flex flex-wrap items-center justify-between gap-4 p-4">
        <span className="text-2x text-fg">矿工 Lv.{data.level}</span>
        <span className="text-1x text-muted">连锁开关无对应写 action, 仅只读展示当前状态</span>
      </PixelFrame>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">被动数值</h2>
        {data.passives.length === 0 ? (
          <PixelEmpty title="暂无被动加成" />
        ) : (
          <PixelTable
            columns={[
              { key: 'label', header: '属性', render: (row) => row.label },
              {
                key: 'value',
                header: '数值',
                render: (row) => formatStatValue(row.value, row.unit),
                sortValue: (row) => row.value,
              },
            ]}
            rows={data.passives}
            rowKey={(row) => row.key}
          />
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">连锁充能</h2>
        <PixelFrame variant="panel" className="flex items-center gap-4 p-4">
          <PixelProgress
            value={data.charge}
            max={data.chargeMax}
            tone="accent"
            label={`${String(data.charge)}/${String(data.chargeMax)}`}
            className="flex-1"
          />
          <PixelBadge tone={data.chainEnabled ? 'success' : 'neutral'}>
            {data.chainEnabled ? '连锁已开启' : '连锁已关闭'}
          </PixelBadge>
        </PixelFrame>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">探测脉冲</h2>
        <PixelFrame variant="panel" className="flex flex-col gap-4 p-4">
          {levelGated ? (
            <PixelBadge tone="warning">
              探测需要矿工 {data.scanUnlockLevel} 级 (当前 {data.level} 级)
            </PixelBadge>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <PixelSelect
                  value={selectedOreId ?? ''}
                  options={dailyOres.map((ore) => ({
                    value: ore.itemId,
                    label: oreNames[ore.descriptionId] ?? ore.descriptionId,
                  }))}
                  onChange={(next) => {
                    setSelectedOreId(next)
                  }}
                  disabled={dailyOres.length === 0}
                />
                <PixelButton
                  tone="accent"
                  loading={scanning}
                  disabled={!scanReady || selectedOreId === null}
                  onClick={() => {
                    void handleScan()
                  }}
                >
                  发起探测脉冲
                </PixelButton>
                <span className="text-1x text-muted">
                  半径 {data.scanRadius} 格 ·{' '}
                  {scanReady ? '就绪, 可立即探测' : `冷却中, 剩余 ${formatCountdown(effectiveScanReadyAt, now)}`}
                </span>
              </div>
              {scanError === null ? null : (
                <p role="alert" className="text-1x text-danger">
                  {scanError.message}
                </p>
              )}
              {scanResult === null ? (
                <PixelEmpty title="尚未进行有效的探测" hint="点击上方按钮发起一次探测脉冲, 结果会在此列出" />
              ) : (
                <ScanResultView result={scanResult} now={now} />
              )}
            </>
          )}
        </PixelFrame>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">当日矿物软上限进度</h2>
        {dailyOres.length === 0 ? (
          <PixelEmpty title="今日暂无产出记录" />
        ) : (
          <div className="flex flex-col gap-3">
            {dailyOres.map((ore) => (
              <DailyOreRow key={ore.itemId} ore={ore} name={oreNames[ore.descriptionId] ?? ore.descriptionId} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
