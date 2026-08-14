import type { ReactElement } from 'react'
import {
  DataTable,
  EmptyBlock,
  ErrorBlock,
  ItemIcon,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
} from '@/components/kit'
import { useItemNames } from '../../../lib/i18n'
import type { BrewerBrewEntry, BrewerRecipeRow } from '../../../lib/types'
import { useMockAction } from '../../../mock'

/**
 * 酿酒师面板 (`job.brewer.state`, Java 落点 com.miningdim.job.brewer.BrewerWebUiActions)。
 * 回执形状见 lib/types.ts 的 BrewerStateResult。
 *
 * 两处维度必须分清 (旧假定契约在这两处都是错的):
 *   1. 月光词条是**玩家全局一组** (满 5 层月光时一次性固化 8 选 5), 与具体是哪种酒无关, 故独立成一个
 *      顶层分区; 挂在每行酒上会渲染出"伏特加带着月光词条"这种不存在的东西。
 *   2. 陈酿没有 per-配方的天数: 陈酿是酒窖箱按现实挂钟持续累积年份, 9 种酒共用同一套时钟, 故配方表里
 *      没有"陈酿天数"这一列, 改成一句由 millisPerVintageYear 算出的速率说明。
 *
 * 本面板没有对应写操作, 全部只读: 酒窖陈酿/酿酒台进度挂在方块位置而非玩家 (状态无公开 getter, 远程
 * 查看需额外设计索引), 酿酒师卖酒也没有 NPC 收购 faucet (变现只能走市场卖给其他玩家)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}
const MILLIS_PER_DAY = 86_400_000

function BrewRow({
  brew,
  maxLayers,
  displayName,
}: {
  brew: BrewerBrewEntry
  maxLayers: number
  displayName: string
}): ReactElement {
  return (
    <Surface>
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <h3 className="flex items-center gap-2 font-medium text-foreground text-sm">
            <ItemIcon itemId={brew.itemId} label={displayName} />
            {displayName}
          </h3>
          <span className="text-muted-foreground text-xs tabular-nums">
            {brew.permanentStacks}/{maxLayers}
          </span>
        </div>
        <Meter
          bare
          max={maxLayers}
          tone={brew.permanentStacks >= maxLayers ? 'success' : 'brand'}
          value={brew.permanentStacks}
        />
      </div>
    </Surface>
  )
}

export function BrewerPanel(): ReactElement {
  const query = useMockAction('job.brewer.state', EMPTY_PAYLOAD)
  const data = query.status === 'ready' ? query.data : null
  const names = useItemNames(
    data === null
      ? []
      : [
          ...data.brews.map((brew) => brew.descriptionId),
          ...data.moonshinePerks.map((perk) => perk.labelKey),
          ...data.recipes.flatMap((recipe) => recipe.inputs.map((input) => input.descriptionId)),
        ],
  )

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取酿酒师档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.brewer.state 回执为空" onRetry={query.reload} />
  }

  const wineName = (wineId: string): string => {
    const brew = data.brews.find((candidate) => candidate.wineId === wineId)
    if (brew === undefined) {
      return wineId
    }
    return names[brew.descriptionId] ?? brew.descriptionId
  }
  const daysPerVintageYear = data.millisPerVintageYear / MILLIS_PER_DAY

  return (
    <div className="flex flex-col gap-4">
      <Panel title="酿酒师">
        <div className="grid grid-cols-3 gap-4">
          <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
          <Stat label="每类永久层数上限" value={String(data.maxLayersPerType)} />
        </div>
      </Panel>

      <Panel title="永久层数 (喝闪耀酒按年份加层, 死亡清零)">
        {data.brews.length === 0 ? (
          <EmptyBlock title="暂无酿酒记录" />
        ) : (
          <div className="flex flex-col gap-3">
            {data.brews.map((brew) => (
              <BrewRow
                brew={brew}
                displayName={names[brew.descriptionId] ?? brew.descriptionId}
                key={brew.wineId}
                maxLayers={data.maxLayersPerType}
              />
            ))}
          </div>
        )}
      </Panel>

      <Panel title="月光词条">
        {data.moonshinePerks.length === 0 ? (
          <EmptyBlock
            hint="月光酒攒满永久层数时会一次性固化 8 选 5, 在那之前这里是空的"
            title="月光尚未满层"
          />
        ) : (
          <div className="flex flex-col gap-2">
            <p className="text-muted-foreground text-xs">
              全玩家一组, 与具体喝的是哪种酒无关; 满层时一次性固化后不再变
            </p>
            <div className="flex flex-wrap items-center gap-2">
              {data.moonshinePerks.map((perk) => (
                <Tag key={perk.perkId} size="sm" tone="info">
                  {names[perk.labelKey] ?? perk.labelKey}
                </Tag>
              ))}
            </div>
          </div>
        )}
      </Panel>

      <Panel title="配方表">
        <div className="flex flex-col gap-3">
          <p className="text-muted-foreground text-xs">
            精确匹配: 投料的物品集合与计数必须与本表逐项相等, 多投或错投都不出酒。酿出的是基酒,
            年份要靠酒窖箱按现实挂钟慢慢陈 —— 每 {daysPerVintageYear.toFixed(1)} 个现实天累积 1 个年份,
            9 种酒共用同一套时钟, 没有一酒一档的陈酿天数
          </p>
          {data.recipes.length === 0 ? (
            <EmptyBlock title="暂无配方数据" />
          ) : (
            <DataTable<BrewerRecipeRow>
              columns={[
                { header: '成品', key: 'wine', render: (row) => wineName(row.wineId) },
                {
                  header: '原料 (精确)',
                  key: 'inputs',
                  render: (row) => (
                    <span className="flex flex-wrap items-center gap-2">
                      {row.inputs.map((input) => (
                        <span className="flex items-center gap-1" key={input.itemId}>
                          <ItemIcon
                            itemId={input.itemId}
                            label={names[input.descriptionId] ?? input.descriptionId}
                          />
                          <span className="text-muted-foreground text-xs">x{input.count}</span>
                        </span>
                      ))}
                    </span>
                  ),
                },
              ]}
              rowKey={(row) => row.wineId}
              rows={data.recipes}
            />
          )}
        </div>
      </Panel>
    </div>
  )
}
