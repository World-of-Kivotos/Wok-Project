import type { ReactElement } from 'react'
import {
  ItemIcon,
  PixelBadge,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelLoading,
  PixelProgress,
  PixelTable,
} from '../../../components/pixel'
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
    <PixelFrame variant="panel" className="flex flex-col gap-2 p-4">
      <div className="flex items-center justify-between gap-2">
        <span className="text-1x text-fg">{brew.displayName}</span>
        <span className="text-1x text-muted">
          {brew.permanentStacks}/{brew.maxStacks}
        </span>
      </div>
      <PixelProgress
        value={brew.permanentStacks}
        max={brew.maxStacks}
        tone={brew.permanentStacks >= brew.maxStacks ? 'success' : 'accent'}
      />
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-1x text-muted">月光词条:</span>
        {brew.moonshineAffixes.length === 0 ? (
          <span className="text-1x text-muted">无</span>
        ) : (
          brew.moonshineAffixes.map((affix) => (
            <PixelBadge key={affix} tone="info">
              {affix}
            </PixelBadge>
          ))
        )}
      </div>
    </PixelFrame>
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
    return <PixelLoading label="正在读取酿酒师档案" />
  }
  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="p-4">
        <span className="text-2x text-fg">酿酒师 Lv.{data.level}</span>
      </PixelFrame>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">永久层数与月光词条</h2>
        {data.brews.length === 0 ? (
          <PixelEmpty title="暂无酿酒记录" />
        ) : (
          <div className="flex flex-col gap-3">
            {data.brews.map((brew) => (
              <BrewRow key={brew.brewId} brew={brew} />
            ))}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">配方表</h2>
        {data.recipes.length === 0 ? (
          <PixelEmpty title="暂无配方数据" />
        ) : (
          <PixelTable
            columns={[
              { key: 'name', header: '配方', render: (row) => row.displayName },
              {
                key: 'inputs',
                header: '原料',
                render: (row) => (
                  <span className="flex flex-wrap items-center gap-2">
                    {row.inputs.map((input) => (
                      <span key={input.itemId} className="flex items-center gap-1">
                        <ItemIcon itemId={input.itemId} label={names[input.descriptionId] ?? input.descriptionId} />
                        <span className="text-1x text-muted">x{input.count}</span>
                      </span>
                    ))}
                  </span>
                ),
              },
              {
                key: 'agingDays',
                header: '陈酿天数',
                render: (row) => `${row.agingDays} 天`,
                sortValue: (row) => row.agingDays,
              },
            ]}
            rows={data.recipes}
            rowKey={(row) => row.recipeId}
          />
        )}
      </section>
    </div>
  )
}
