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
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type { Blueprint, MunitionsStation } from '../../../lib/types'
import { useMockAction } from '../../../mock'

/**
 * 军火商面板 (`job.munitions.state` / `job.blueprints`, Java 落点
 * com.miningdim.job.munitions.MunitionsWebUiActions)。回执形状见 lib/types.ts。
 *
 * 三条与旧版假定相反、必须照做的契约事实:
 *   1. **pos 是"附近扫到的最近一台", 不是"我的台"**: 全工程没有"玩家 -> 台位坐标"注册表, 冲压机与
 *      装配台连归属字段都没有。pos=null 只能写"附近未找到", 绝不能写"尚未建造" —— 想说造了几台请看
 *      benchesPlaced (那才是跨维度权威计数)。
 *   2. **装配台读不出已进行 tick**: 它没有 ContainerData, animationEndTick 是私有字段, 故 progressTicks
 *      恒 null。running=true 且 progressTicks=null 时只能说"加工中", 不能当 0% 画一条空条。
 *   3. **枪匠链默认关闭** (MunitionsConfig.gunsmithEnabled=false): 关着时装配台点开工只会被拒。面板必须
 *      先把这件事讲清楚, 否则玩家会当成 bug。
 *
 * 三台仍是纯只读遥测: 服务端这两条 action 都没有配套的写入口 (开工/选口径/开始装配), 面板不放点了
 * 没有后果的按钮。
 *
 * 图纸名是**两层拼的**: 套壳键 item.miningdim.gunsmith_blueprint.name 带一个 %s, 实参是枪名键
 * tacz.gun.<id>.name —— 后者属 TACZ 的 lang, 未装 TACZ 的客户端解不出, 那时退回显示 gunId。
 * 筛选仍只按 blueprintId/gunId 子串匹配 (英文/数字资源定位符), 不依赖当前 BLOCKED 的宿主中文输入通道。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

/** 三台的稳定 id (= 方块注册名), 用于按台种类取 detail 里的键。 */
const STATION_MUNITIONS_BENCH = 'munitions_bench'
const STATION_GUNSMITH_PRESS = 'gunsmith_press'
const STATION_GUNSMITH_ASSEMBLY = 'gunsmith_assembly_bench'

/**
 * 翻译键解不出来时 useItemNames 原样退回键本身 (见 lib/i18n 的降级纪律), 故"解出来了没有"只能
 * 拿结果与键比对。本页需要区分这两种情况: 枪名解不出要退回 gunId, 而不是把 tacz.gun.xxx.name 顶给玩家看。
 */
function resolvedOrNull(names: Record<string, string>, key: string): string | null {
  const value = names[key]
  return value === undefined || value === key ? null : value
}

/** 图纸标题: 套壳键 %s 位填枪名; 枪名解不出退回 gunId, 套壳键解不出就只显示枪名。 */
function blueprintTitle(blueprint: Blueprint, names: Record<string, string>): string {
  const gunName = resolvedOrNull(names, blueprint.gunNameKey) ?? blueprint.gunId
  const wrapper = resolvedOrNull(names, blueprint.nameKey)
  return wrapper === null ? gunName : wrapper.replace('%s', gunName)
}

/** tick -> 秒的纯展示折算 (20 tick = 1 秒)。 */
function ticksToSecondsText(ticks: number): string {
  return `${(ticks / 20).toFixed(1)}s`
}

/** 一台机器的特有状态。键随 stationId 变 (契约: detail 是按台种类条件写入的), 故按 id 分支取。 */
function StationDetail({ station }: { station: MunitionsStation }): ReactElement | null {
  const detail = station.detail

  if (station.stationId === STATION_MUNITIONS_BENCH) {
    return (
      <div className="flex flex-wrap items-center gap-4">
        <Stat
          label="口径"
          layout="inline"
          value={detail.caliberId === undefined || detail.caliberId === null ? '未选' : detail.caliberId}
        />
        <Stat
          label="缓冲发数"
          layout="inline"
          value={`${String(detail.bufferedRounds ?? 0)} / ${String(detail.bufferCap ?? 0)}`}
        />
        {detail.effectiveLevel === undefined ? null : (
          <Stat label="按几级算产能" layout="inline" value={String(detail.effectiveLevel)} />
        )}
        {detail.locked === true ? <Tag tone="warning">已锁定</Tag> : null}
        {detail.refineUnlocked === true ? <Tag tone="success">精炼已解锁</Tag> : null}
        {detail.continuousCrafting === true ? <Tag tone="info">连续生产</Tag> : null}
      </div>
    )
  }

  if (station.stationId === STATION_GUNSMITH_PRESS) {
    return (
      <div className="flex flex-wrap items-center gap-4">
        <Stat label="平台" layout="inline" value={detail.platformId ?? '未选'} />
        <Stat label="部位" layout="inline" value={detail.partId ?? '未选'} />
        <Stat label="品质" layout="inline" value={detail.qualityId ?? '未选'} />
        <Stat label="变体" layout="inline" value={detail.variantId ?? '未选'} />
      </div>
    )
  }

  if (station.stationId === STATION_GUNSMITH_ASSEMBLY) {
    return (
      <Stat
        label="图纸槽"
        layout="inline"
        value={detail.blueprintId === undefined || detail.blueprintId === null ? '未放图纸' : detail.blueprintId}
      />
    )
  }

  return null
}

export function MunitionsPanel(): ReactElement {
  const stationQuery = useMockAction('job.munitions.state', EMPTY_PAYLOAD)
  const blueprintQuery = useMockAction('job.blueprints', EMPTY_PAYLOAD)

  const [filterText, setFilterText] = useState('')
  const [selectedBlueprintId, setSelectedBlueprintId] = useState<string | null>(null)

  const blueprintData = blueprintQuery.status === 'ready' ? blueprintQuery.data : null
  const blueprints = blueprintData === null ? [] : blueprintData.blueprints
  const stationData = stationQuery.status === 'ready' ? stationQuery.data : null

  /*
   * 四类键一次批量解: 台名 / 图纸套壳名 / 枪名 / 部位标签。零件的 itemId 与 descriptionId 由服务端提到
   * 顶层只发一份 (195 种枪匠零件全注册在同一个 id 之下靠 NBT 区分), 逐行重复是纯浪费, 故这里也只解一次。
   */
  const names = useItemNames([
    ...(stationData === null ? [] : stationData.stations.map((station) => station.nameKey)),
    ...blueprints.map((blueprint) => blueprint.nameKey),
    ...blueprints.map((blueprint) => blueprint.gunNameKey),
    ...blueprints.flatMap((blueprint) => blueprint.requiredParts.map((part) => part.labelKey)),
    ...(blueprintData === null ? [] : [blueprintData.partDescriptionId]),
  ])
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  if (stationQuery.status === 'loading') {
    return <LoadingBlock label="正在读取军械台状态" />
  }
  if (stationQuery.status === 'error') {
    return <ErrorBlock message={callErrorText(stationQuery.error)} onRetry={stationQuery.reload} />
  }
  if (stationData === null) {
    return <ErrorBlock message="job.munitions.state 回执为空" onRetry={stationQuery.reload} />
  }

  const needle = filterText.trim().toLowerCase()
  const filteredBlueprints = blueprints.filter(
    (blueprint) =>
      needle === '' ||
      blueprint.blueprintId.toLowerCase().includes(needle) ||
      blueprint.gunId.toLowerCase().includes(needle),
  )
  const foundSelectedBlueprint = blueprints.find(
    (blueprint) => blueprint.blueprintId === selectedBlueprintId,
  )
  const selectedBlueprint = foundSelectedBlueprint === undefined ? null : foundSelectedBlueprint

  return (
    <div className="flex flex-col gap-4">
      <Panel title="军火商">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(stationData.level)}`} />
            <Stat
              label="军火台"
              value={`${String(stationData.benchesPlaced)} / ${String(stationData.benchCap)} 台`}
              hint="跨维度权威计数, 与下方扫到几台无关"
            />
            <Stat
              label="就近搜索半径"
              value={`${String(stationData.searchRadiusBlocks)} 格`}
              hint="没扫到只代表这个半径内没有"
            />
          </div>
          {stationData.gunsmithEnabled ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                枪匠冲压与装配整条链当前是关闭的 (服务端配置), 装配台点开工只会被拒绝 —— 下面的冲压机与
                装配台仅供查看
              </p>
            </Surface>
          )}
        </div>
      </Panel>

      <Panel title="生产状态">
        <div className="flex flex-col gap-3">
          {stationData.stations.map((station) => {
            /*
             * 三种取值都要分开处理 (契约): null = 没扫到或装配台读不出; 0 = 军火台未选口径 (此时
             * requiredTicks 同为 0, 直接相除是 NaN); 正数才是真进度。收成一个对象是为了让下面的
             * Meter 不必写 `?? 0` 这类会把"读不出"悄悄画成 0% 的兜底。
             */
            const progress =
              station.progressTicks !== null && station.requiredTicks !== null && station.requiredTicks > 0
                ? { value: station.progressTicks, max: station.requiredTicks }
                : null
            return (
              <Surface key={station.stationId}>
                <div className="flex flex-col gap-2">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h3 className="font-medium text-foreground text-sm">{nameOf(station.nameKey)}</h3>
                    <div className="flex items-center gap-3">
                      {station.pos === null ? (
                        <Tag tone="neutral">
                          附近未找到 (半径 {stationData.searchRadiusBlocks} 格)
                        </Tag>
                      ) : (
                        <>
                          <span className="text-muted-foreground text-xs">
                            坐标 ({station.pos.x}, {station.pos.y}, {station.pos.z})
                          </span>
                          <Tag tone={station.running ? 'success' : 'neutral'}>
                            {station.running ? '运行中' : '空闲'}
                          </Tag>
                        </>
                      )}
                    </div>
                  </div>

                  {station.pos === null ? null : (
                    <>
                      <div className="flex items-center gap-4">
                        {progress !== null ? (
                          <Meter
                            className="flex-1"
                            label="加工进度"
                            max={progress.max}
                            tone={station.running ? 'brand' : 'neutral'}
                            value={progress.value}
                            valueText={`${ticksToSecondsText(progress.value)} / ${ticksToSecondsText(
                              progress.max,
                            )}`}
                          />
                        ) : (
                          <span className="flex-1 text-muted-foreground text-xs">
                            {station.running
                              ? '加工中 · 这台机器的已进行时间服务端读不出, 只能确认它在转'
                              : station.requiredTicks === 0
                                ? '未选口径, 尚未开始加工'
                                : '当前空闲'}
                          </span>
                        )}
                        {station.outputItemId === null ? (
                          <span className="text-muted-foreground text-xs">输出槽为空</span>
                        ) : (
                          <div className="flex items-center gap-2">
                            <ItemIcon itemId={station.outputItemId} label={station.outputItemId} />
                            <span className="text-muted-foreground text-xs">
                              {station.outputItemId} x{station.outputCount}
                            </span>
                          </div>
                        )}
                      </div>
                      <StationDetail station={station} />
                    </>
                  )}
                </div>
              </Surface>
            )
          })}
          <p className="text-muted-foreground text-xs">
            军火台的产出以缓冲发数为准: 未装 TACZ 时输出槽恒空而缓冲照常累积
          </p>
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
              {blueprintData === null ? '' : ` · 共 ${String(blueprintData.blueprintCount)} 款图纸`}
            </span>
          </div>

          {blueprintQuery.status === 'loading' ? <LoadingBlock label="正在读取图纸百科" /> : null}
          {blueprintQuery.status === 'error' ? (
            <ErrorBlock
              message={callErrorText(blueprintQuery.error)}
              onRetry={blueprintQuery.reload}
            />
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
                      <span className="font-medium text-foreground text-sm">
                        {blueprintTitle(blueprint, names)}
                      </span>
                      <span className="text-muted-foreground text-xs">
                        {blueprint.gunId} · {nameOf(blueprint.platformLabelKey)}
                      </span>
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

      {selectedBlueprint === null || blueprintData === null ? null : (
        <Panel title={`${blueprintTitle(selectedBlueprint, names)} 所需部件`}>
          <div className="flex flex-wrap gap-3">
            {selectedBlueprint.requiredParts.map((part) => (
              <ItemSlot
                count={part.count}
                itemId={blueprintData.partItemId}
                key={part.partId}
                label={nameOf(part.labelKey)}
                scale={2}
              />
            ))}
          </div>
          <p className="text-muted-foreground text-xs">
            195 种枪匠零件共用同一个物品 id, 平台/部位/品质由 NBT 区分, 故这里的图标全是同一张
          </p>
        </Panel>
      )}
    </div>
  )
}
