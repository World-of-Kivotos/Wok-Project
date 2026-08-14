import type { ReactElement } from 'react'
import {
  Currency,
  DataTable,
  ErrorBlock,
  LoadingBlock,
  Panel,
  Stat,
  Tag,
} from '@/components/kit'
import { useItemNames } from '../../../lib/i18n'
import type { ChefEffectRow, ChefEffectUnit, ChefQualityRow } from '../../../lib/types'
import { useMockAction } from '../../../mock'

/**
 * 厨师面板 (`job.chef.state`, Java 落点 com.miningdim.job.chef.ChefWebUiActions)。
 * 回执形状见 lib/types.ts 的 ChefStateResult。
 *
 * 全部数值走 ForgeConfigSpec 运营可调, 服务端每次调用实时 ChefConfig.*.get() —— 本面板必须实时读这条
 * action, **严禁**抄一份静态副本进代码。
 *
 * 效果表是 (18 种效果 x 5 档品质) 的矩阵, 不是"一档一个值"的单列表: 各效果的 magnitude 语义还各不相同
 * (倍率 x100 / 千分比 / 秒 / 1-based 等级 / 个数), 故按效果成行、品质成列, 数值按行自带的 unit 格式化。
 *
 * 做菜火候小游戏判定不进 MCEF (QTE 的游标是每 tick 变化的服务端时序权威值, 网络延迟直接影响手感),
 * 走原生 Container GUI。本面板因此只做数值预览, 没有任何计时器/判定 UI —— 这是决策而非遗漏,
 * 与铸甲师面板对纳米校准 QTE 的处理同一条纪律。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

/** 1-based 等级的罗马数字 (效果等级最高 5 档, 与 MC 药水等级同一套写法)。 */
const LEVEL_NUMERALS: readonly string[] = ['I', 'II', 'III', 'IV', 'V']

/**
 * 按效果自身的量纲把 magnitude 显示出来。
 * 0 一律显示成 "—": 在这份矩阵里 0 的语义恒为"该档不掷出/不适用该效果"(战斗向在低/中两档、翻车负面在
 * 超凡/闪耀两档都是这种情况), 画一个 0 会被读成"有这个效果但数值是零"。
 */
function formatMagnitude(value: number, unit: ChefEffectUnit): string {
  if (unit === 'none') {
    return '固定语义'
  }
  if (value === 0) {
    return '—'
  }
  switch (unit) {
    case 'mul_x100':
      return `x${(value / 100).toFixed(2)}`
    case 'permille':
      return `${(value / 10).toFixed(1)}%`
    case 'level':
      return LEVEL_NUMERALS[value - 1] ?? String(value)
    case 'seconds':
      return `${String(value)}s`
    // 回甘的 99 是"全部清除"的哨兵值, 不是真的 99 个 (ChefEffectMagnitude.snapshot 的 RADIANT 分支)。
    case 'count':
      return value >= 99 ? '全部' : `${String(value)} 个`
    default:
      return String(value)
  }
}

function effectColumns(
  qualities: readonly ChefQualityRow[],
  names: Record<string, string>,
): { header: string; key: string; render: (row: ChefEffectRow) => ReactElement }[] {
  return qualities.map((quality) => ({
    header: names[quality.nameKey] ?? quality.nameKey,
    key: quality.qualityId,
    render: (row: ChefEffectRow) => {
      const magnitude = row.magnitudes[quality.tier]
      const duration = row.durationSeconds[quality.tier]
      return (
        <span className="flex flex-col">
          <span className="tabular-nums">
            {magnitude === undefined ? '—' : formatMagnitude(magnitude, row.unit)}
          </span>
          {duration === undefined || duration === 0 ? null : (
            <span className="text-muted-foreground text-xs tabular-nums">{duration}s</span>
          )}
        </span>
      )
    },
  }))
}

function EffectTable({
  title,
  rows,
  qualities,
  names,
}: {
  title: string
  rows: readonly ChefEffectRow[]
  qualities: readonly ChefQualityRow[]
  names: Record<string, string>
}): ReactElement {
  return (
    <Panel title={title}>
      <DataTable<ChefEffectRow>
        columns={[
          {
            header: '效果',
            key: 'label',
            render: (row) => (
              <span className="flex flex-wrap items-center gap-1">
                <span>{names[row.labelKey] ?? row.labelKey}</span>
                {row.combat ? (
                  <Tag size="sm" tone="info">
                    战斗向
                  </Tag>
                ) : null}
                {row.windowed ? (
                  <Tag size="sm" tone="neutral">
                    窗口型
                  </Tag>
                ) : null}
              </span>
            ),
          },
          ...effectColumns(qualities, names),
        ]}
        emptyHint="暂无效果数据"
        rowKey={(row) => row.effectId}
        rows={rows}
      />
    </Panel>
  )
}

export function ChefPanel(): ReactElement {
  const query = useMockAction('job.chef.state', EMPTY_PAYLOAD)
  const data = query.status === 'ready' ? query.data : null
  const names = useItemNames(
    data === null
      ? []
      : [...data.qualities.map((quality) => quality.nameKey), ...data.effects.map((row) => row.labelKey)],
  )

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取厨师档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.chef.state 回执为空" onRetry={query.reload} />
  }

  const capQuality = data.qualities.find((quality) => quality.tier === data.qualityCapTier)
  const positives = data.effects.filter((row) => !row.negative)
  const negatives = data.effects.filter((row) => row.negative)

  return (
    <div className="flex flex-col gap-4">
      <Panel title="厨师">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="可做出的最高品质"
              value={
                capQuality === undefined
                  ? String(data.qualityCapTier)
                  : (names[capQuality.nameKey] ?? capQuality.nameKey)
              }
            />
            <Stat
              label="调味台每道菜"
              value={<Currency amount={data.seasoningCostCredit} currency="credit" />}
            />
          </div>
          <p className="text-muted-foreground text-xs">
            下面所有数值都由服务端实时读配置回出, 运营改一次配置这里就跟着变, 不要照抄记忆里的旧数
          </p>
        </div>
      </Panel>

      <Panel title="品质档">
        <DataTable<ChefQualityRow>
          columns={[
            {
              header: '品质',
              key: 'name',
              render: (row) => (
                <span className="flex items-center gap-1">
                  <span
                    className={row.tier <= data.qualityCapTier ? 'text-foreground' : 'text-muted-foreground'}
                  >
                    {names[row.nameKey] ?? row.nameKey}
                  </span>
                  {row.tier === data.qualityCapTier ? (
                    <Tag size="sm" tone="brand">
                      当前上限
                    </Tag>
                  ) : null}
                </span>
              ),
            },
            {
              header: '一菜带几个效果',
              key: 'maxEffects',
              numeric: true,
              render: (row) => String(row.maxEffects),
              sortValue: (row) => row.maxEffects,
            },
            {
              header: '翻车',
              key: 'noFailure',
              render: (row) => (row.noFailure ? '零翻车' : '会翻车'),
            },
            {
              header: '战斗向效果',
              key: 'combatUnlocked',
              render: (row) => (row.combatUnlocked ? '已解锁' : '未解锁'),
            },
            {
              header: '单菜经验',
              key: 'rawXp',
              numeric: true,
              render: (row) => String(row.rawXp),
              sortValue: (row) => row.rawXp,
            },
          ]}
          rowKey={(row) => row.qualityId}
          rows={data.qualities}
        />
      </Panel>

      <EffectTable
        names={names}
        qualities={data.qualities}
        rows={positives}
        title="增益效果 (行 = 效果, 列 = 品质档)"
      />

      <EffectTable
        names={names}
        qualities={data.qualities}
        rows={negatives}
        title="翻车负面 (仅低/中/高会掷出, 超凡与闪耀零翻车)"
      />
    </div>
  )
}
