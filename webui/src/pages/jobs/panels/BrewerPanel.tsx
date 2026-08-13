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
import { useMockAction } from '../../../mock'
import type { PlannedBrewEntry } from '../../../mock'

/**
 * 酿酒师面板 (接线清单 C11 job.brewer.state, PLANNED, 备注"9 酒永久层数 + 月光 8 选 5 词条 + 配方表
 * 全已持久化. 配方表是最容易接的一条")。
 *
 * 依赖的假定契约:
 *   - job.brewer.state -> PlannedBrewerStateResult (各酒永久层数/月光词条 + 配方表)
 *
 * 本面板没有对应写操作, 全部只读: 酒窖陈酿/酿酒台进度挂在方块位置而非玩家 (C12 BACKEND, 状态无公开
 * getter, 远程查看需额外设计索引), 酿酒师卖酒没有 NPC 收购 faucet (C13 NONE, 变现只能走市场卖给其他
 * 玩家); mock/seed.ts 当前只种了 4 条 brews 记录 (伏特加/金酒/朗姆/威士忌), 未覆盖真服全部 9 种酒 ——
 * 那是种子数据的缺口, 不是本文件的渲染缺陷, 列表按回执给多少渲染多少。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function BrewRow({ brew }: { brew: PlannedBrewEntry }): ReactElement {
  return (
    <Surface>
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <h3 className="font-medium text-foreground text-sm">{brew.displayName}</h3>
          <span className="text-muted-foreground text-xs tabular-nums">
            {brew.permanentStacks}/{brew.maxStacks}
          </span>
        </div>
        <Meter
          bare
          max={brew.maxStacks}
          tone={brew.permanentStacks >= brew.maxStacks ? 'success' : 'brand'}
          value={brew.permanentStacks}
        />
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground text-xs">月光词条:</span>
          {brew.moonshineAffixes.length === 0 ? (
            <span className="text-muted-foreground text-xs">无</span>
          ) : (
            brew.moonshineAffixes.map((affix) => (
              <Tag key={affix} size="sm" tone="info">
                {affix}
              </Tag>
            ))
          )}
        </div>
      </div>
    </Surface>
  )
}

export function BrewerPanel(): ReactElement {
  const query = useMockAction('job.brewer.state', EMPTY_PAYLOAD)
  const recipeInputIds =
    query.status === 'ready'
      ? query.data.recipes.flatMap((recipe) => recipe.inputs.map((input) => input.descriptionId))
      : []
  const names = useItemNames(recipeInputIds)

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取酿酒师档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data

  return (
    <div className="flex flex-col gap-4">
      <Panel title="酿酒师">
        <div className="grid grid-cols-3 gap-4">
          <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
        </div>
      </Panel>

      <Panel title="永久层数与月光词条">
        {data.brews.length === 0 ? (
          <EmptyBlock title="暂无酿酒记录" />
        ) : (
          <div className="flex flex-col gap-3">
            {data.brews.map((brew) => (
              <BrewRow brew={brew} key={brew.brewId} />
            ))}
          </div>
        )}
      </Panel>

      <Panel title="配方表">
        {data.recipes.length === 0 ? (
          <EmptyBlock title="暂无配方数据" />
        ) : (
          <DataTable
            columns={[
              { header: '配方', key: 'name', render: (row) => row.displayName },
              {
                header: '原料',
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
              {
                header: '陈酿天数',
                key: 'agingDays',
                numeric: true,
                render: (row) => `${String(row.agingDays)} 天`,
                sortValue: (row) => row.agingDays,
              },
            ]}
            rowKey={(row) => row.recipeId}
            rows={data.recipes}
          />
        )}
      </Panel>
    </div>
  )
}
