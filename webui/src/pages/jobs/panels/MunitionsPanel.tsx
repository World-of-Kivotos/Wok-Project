import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  EmptyBlock,
  ErrorBlock,
  ItemIcon,
  ItemSlot,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import { useMockAction } from '../../../mock'
import { useItemNames } from '../../../lib/i18n'

/**
 * 军火商面板 (接线清单 C19 job.munitions.state / C20 job.blueprints, 均为 PLANNED 但标注 WRAP ——
 * 军械三台是 ContainerData 驱动的成熟 menu, 数据权威完整; 图纸表是 GunsmithBlueprint 枚举 dump,
 * 两条在清单里都是"薄封装即可"的低风险项, 只是尚未真正落地为 action)。
 *
 * 依赖的假定契约:
 *   - job.munitions.state -> PlannedMunitionsStateResult (军械台/冲压机/装配台三台的只读镜像)
 *   - job.blueprints        -> PlannedBlueprintsResult (图纸百科静态表)
 *
 * 契约缺口: 两条 action 目前都只有 state 没有对应的 mutate action (无开始/停止生产、无按图纸执行装配),
 * 与清单原文"数据权威完整, 按 blockPos + 按钮 id 薄封装"里的"按钮"部分尚未过契约层, 故本面板对三台
 * 机器是纯只读遥测展示, 交互面收在图纸百科的筛选与详情展开上 —— 这不是偷懒简化, 是当前契约能诚实
 * 支撑的全部操作面。
 *
 * 图纸名称含中文 (如"M4A1 图纸"), 但筛选框只按 blueprintId/gunId 做子串匹配 —— 两者都是英文/数字
 * 资源定位符, 不依赖当前 BLOCKED 的宿主中文输入通道 (接线清单 A14), 因此用普通 TextInput 而非
 * onRequestEdit 占位, 符合九-10 "数字与英文输入必须不依赖宿主能力可用"。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function stationToneBadge(running: boolean): 'success' | 'neutral' {
  return running ? 'success' : 'neutral'
}

export function MunitionsPanel(): ReactElement {
  const stationQuery = useMockAction('job.munitions.state', EMPTY_PAYLOAD)
  const blueprintQuery = useMockAction('job.blueprints', EMPTY_PAYLOAD)

  const [filterText, setFilterText] = useState('')
  const [selectedBlueprintId, setSelectedBlueprintId] = useState<string | null>(null)

  const blueprints = blueprintQuery.status === 'ready' ? blueprintQuery.data.blueprints : []
  const partDescriptionIds = blueprints.flatMap((blueprint) =>
    blueprint.requiredParts.map((part) => part.descriptionId),
  )
  const partNames = useItemNames(partDescriptionIds)

  if (stationQuery.status === 'loading') {
    return <LoadingBlock label="正在读取军械台状态" />
  }
  if (stationQuery.status === 'error') {
    return <ErrorBlock message={stationQuery.error.message} onRetry={stationQuery.reload} />
  }

  const stationData = stationQuery.data
  const needle = filterText.trim().toLowerCase()
  const filteredBlueprints = blueprints.filter(
    (blueprint) =>
      needle === '' ||
      blueprint.blueprintId.toLowerCase().includes(needle) ||
      blueprint.gunId.toLowerCase().includes(needle),
  )
  const foundSelectedBlueprint = blueprints.find((blueprint) => blueprint.blueprintId === selectedBlueprintId)
  const selectedBlueprint = foundSelectedBlueprint === undefined ? null : foundSelectedBlueprint

  return (
    <div className="flex flex-col gap-4">
      <Panel title="军火商">
        <div className="grid grid-cols-3 gap-4">
          <Stat label="职业等级" value={`Lv.${String(stationData.level)}`} />
        </div>
      </Panel>

      <Panel title="生产状态">
        <div className="flex flex-col gap-3">
          {stationData.stations.map((station) => (
            <Surface key={station.stationId}>
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <h3 className="font-medium text-foreground text-sm">{station.displayName}</h3>
                  <div className="flex items-center gap-3">
                    {station.pos === null ? (
                      <Tag tone="warning">尚未建造</Tag>
                    ) : (
                      <>
                        <span className="text-muted-foreground text-xs">
                          坐标 ({station.pos.x}, {station.pos.y}, {station.pos.z})
                        </span>
                        <Tag tone={stationToneBadge(station.running)}>
                          {station.running ? '运行中' : '空闲'}
                        </Tag>
                      </>
                    )}
                  </div>
                </div>
                {station.pos === null ? null : (
                  <div className="flex items-center gap-4">
                    <Meter
                      className="flex-1"
                      label="加工进度"
                      max={station.maxProgress}
                      tone={station.running ? 'brand' : 'neutral'}
                      value={station.progress}
                      valueText={`${String(station.progress)} / ${String(station.maxProgress)}`}
                    />
                    {station.outputItemId === null ? (
                      <span className="text-muted-foreground text-xs">当前无产出</span>
                    ) : (
                      <div className="flex items-center gap-2">
                        <ItemIcon itemId={station.outputItemId} label={station.outputItemId} />
                        <span className="text-muted-foreground text-xs">{station.outputItemId}</span>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </Surface>
          ))}
        </div>
      </Panel>

      <Panel title="图纸百科">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <TextInput
              onChange={setFilterText}
              placeholder="按图纸/枪械 ID 筛选 (英文/数字)"
              size="sm"
              value={filterText}
            />
            <span className="text-muted-foreground text-xs">
              中文输入暂未开放, 检索请用英文或数字 ID
            </span>
          </div>

          {blueprintQuery.status === 'loading' ? <LoadingBlock label="正在读取图纸百科" /> : null}
          {blueprintQuery.status === 'error' ? (
            <ErrorBlock message={blueprintQuery.error.message} onRetry={blueprintQuery.reload} />
          ) : null}
          {blueprintQuery.status === 'ready' && filteredBlueprints.length === 0 ? (
            <EmptyBlock hint="换一个关键词试试" title="没有匹配的图纸" />
          ) : null}

          {blueprintQuery.status === 'ready' && filteredBlueprints.length > 0 ? (
            <div className="flex flex-col gap-2">
              {filteredBlueprints.map((blueprint) => {
                const selected = blueprint.blueprintId === selectedBlueprintId
                return (
                  <button
                    className={`flex w-full items-center justify-between gap-4 rounded-lg border p-3 text-left transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                      selected ? 'border-brand bg-brand/12' : 'border-border bg-muted/40 hover:bg-accent'
                    }`}
                    key={blueprint.blueprintId}
                    onClick={() => {
                      setSelectedBlueprintId(selected ? null : blueprint.blueprintId)
                    }}
                    type="button"
                  >
                    <span className="flex flex-col">
                      <span className="font-medium text-foreground text-sm">{blueprint.displayName}</span>
                      <span className="text-muted-foreground text-xs">{blueprint.gunId}</span>
                    </span>
                    <span className="text-muted-foreground text-xs">
                      {blueprint.requiredParts.length} 种部件
                    </span>
                  </button>
                )
              })}
            </div>
          ) : null}
        </div>
      </Panel>

      {selectedBlueprint === null ? null : (
        <Panel title={`${selectedBlueprint.displayName} 所需部件`}>
          <div className="flex flex-wrap gap-3">
            {selectedBlueprint.requiredParts.map((part) => {
              const resolvedName = partNames[part.descriptionId]
              return (
                <ItemSlot
                  count={part.count}
                  itemId={part.itemId}
                  key={part.itemId}
                  label={resolvedName === undefined ? part.descriptionId : resolvedName}
                  scale={2}
                />
              )
            })}
          </div>
        </Panel>
      )}
    </div>
  )
}
