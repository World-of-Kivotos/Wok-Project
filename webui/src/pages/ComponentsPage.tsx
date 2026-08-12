import type { ReactElement, ReactNode } from 'react'
import { useState } from 'react'
import type {
  PixelFrameTone,
  PixelFrameVariant,
  PixelSlotScale,
  PixelTableDemoListing,
} from '../components/pixel'
import {
  ItemIcon,
  PIXEL_BADGE_DEMO_ITEMS,
  PIXEL_BUTTON_DEMO_CASES,
  PIXEL_CHECKBOX_DEMO_CASES,
  PIXEL_CONFIRM_DANGER_DEMO,
  PIXEL_CONTROL_SIZES,
  PIXEL_CURRENCY_DEMO_ITEMS,
  PIXEL_EMPTY_DEMO,
  PIXEL_ERROR_DEMO,
  PIXEL_FRAME_TONES,
  PIXEL_ICON_NAMES,
  PIXEL_INPUT_DEMO_CASES,
  PIXEL_LOADING_DEMO,
  PIXEL_MODAL_DEMO,
  PIXEL_PROGRESS_DEMO_ITEMS,
  PIXEL_SCROLL_AREA_DEMO_LINES,
  PIXEL_SELECT_DEMO_OPTIONS,
  PIXEL_SLOT_DEMO_ENTRIES,
  PIXEL_SLOT_GRID_DEMO_COLUMNS,
  PIXEL_SLOT_GRID_DEMO_SLOTS,
  PIXEL_STEPPER_DEMO_CASE,
  PIXEL_TABLE_DEMO_COLUMNS,
  PIXEL_TABLE_DEMO_ROWS,
  PIXEL_TABS_DEMO_PRIMARY,
  PIXEL_TABS_DEMO_SECONDARY,
  PIXEL_TOAST_DEMO,
  PIXEL_TOOLTIP_DEMO_ITEMS,
  PixelBadge,
  PixelButton,
  PixelCheckbox,
  PixelConfirmDanger,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelIcon,
  PixelInput,
  PixelLoading,
  PixelModal,
  PixelProgress,
  PixelScrollArea,
  PixelSelect,
  PixelSlot,
  PixelSlotGrid,
  PixelStepper,
  PixelTable,
  PixelTabs,
  PixelToast,
  PixelTooltip,
} from '../components/pixel'

/**
 * 组件预览页 —— 设计系统的活文档。
 *
 * 它解决的不是"看看组件长什么样", 而是**跨组件的风格一致性**: 二十个控件由多个并行批次交付,
 * 各自单看都说得通, 只有把它们按 variant x 状态穷举着并排放在同一屏, 才看得出同一档 size 的行高对不齐、
 * 同一个语义在两个控件上是两支色、按压位移一个有一个没有这类问题。这些问题在任何自动检查里都不报错。
 *
 * 两条排布规则:
 *   1. 同一组件的全部档位同排并列, 不许换行分屏 —— 对齐问题只有在并排时才看得见;
 *   2. 样例数据一律取自各控件自带的 DEMO 常量。那些常量本就是各批次为"预览页与面板复用"导出的,
 *      在这里另造一套等于让活文档与组件作者的意图分叉, 且组件改了之后本页不会跟着改。
 *
 * 交互态一律真接: 按钮点了要有回执、禁用与忙碌的按钮点了必须没有回执、浮层要能真开真关。
 * 一个点下去什么都不动的预览页, 验不出"回调被组件自己拦住了"这件唯一值得验的事。
 */

const TONE_TEXT_CLASS: Record<PixelFrameTone, string> = {
  neutral: 'text-fg',
  accent: 'text-accent',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  info: 'text-info',
}

const FRAME_VARIANTS: readonly PixelFrameVariant[] = ['window', 'panel', 'inset']

const VARIANT_LABEL: Record<PixelFrameVariant, string> = {
  window: 'window · 外凸窗口',
  panel: 'panel · 平面板',
  inset: 'inset · 内凹凹槽',
}

const SLOT_SCALES: readonly PixelSlotScale[] = [1, 2, 3]

/**
 * 取一条样例数据。取不到就抛 —— DEMO 常量是控件对外承诺的一部分, 变空了是控件侧的缺陷,
 * 在这里兜一个空串会让本页照常渲染, 于是那个缺陷永远没人发现。
 * 刻意在组件函数里调用而不是模块顶层: 顶层抛异常会连累整个应用起不来, 这一条只该拖垮本页。
 */
function requireDemo<T>(items: readonly T[], index: number, source: string): T {
  const item = items[index]
  if (item === undefined) {
    throw new Error(`${source} 缺少下标 ${String(index)} 的样例: 控件导出的 DEMO 常量与预览页脱节`)
  }
  return item
}

/** 一个组件一节。note 写的是"这一节要看什么", 不是组件说明 —— 组件说明在各自文件头。 */
function Section({
  title,
  note,
  children,
}: {
  title: string
  note: string
  children: ReactNode
}): ReactElement {
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-2x text-fg">{title}</h2>
      <p className="text-1x text-muted">{note}</p>
      <PixelFrame variant="panel" className="flex flex-col gap-6 p-4">
        {children}
      </PixelFrame>
    </section>
  )
}

/** 一档一排。items-end 而不是 items-center: 并排控件要对齐的是底边, 高度差才看得出来。 */
function Row({ label, children }: { label: string; children: ReactNode }): ReactElement {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-1x text-muted">{label}</p>
      <div className="flex flex-wrap items-end gap-4">{children}</div>
    </div>
  )
}

interface ToastEntry {
  readonly id: number
  readonly tone: PixelFrameTone
  readonly message: string
}

function seedToasts(): ToastEntry[] {
  return PIXEL_TOAST_DEMO.map((toast, index) => ({
    id: index,
    tone: toast.tone,
    message: toast.message,
  }))
}

export function ComponentsPage(): ReactElement {
  const searchDemo = requireDemo(PIXEL_INPUT_DEMO_CASES, 0, 'PIXEL_INPUT_DEMO_CASES')
  const priceDemo = requireDemo(PIXEL_INPUT_DEMO_CASES, 1, 'PIXEL_INPUT_DEMO_CASES')
  const sortDemo = requireDemo(PIXEL_SELECT_DEMO_OPTIONS, 0, 'PIXEL_SELECT_DEMO_OPTIONS')
  const primaryTabDemo = requireDemo(PIXEL_TABS_DEMO_PRIMARY, 0, 'PIXEL_TABS_DEMO_PRIMARY')
  const secondaryTabDemo = requireDemo(PIXEL_TABS_DEMO_SECONDARY, 0, 'PIXEL_TABS_DEMO_SECONDARY')

  const [clickLog, setClickLog] = useState('尚未点击')
  const [searchValue, setSearchValue] = useState(searchDemo.value)
  const [priceValue, setPriceValue] = useState(priceDemo.value)
  const [hostEditLog, setHostEditLog] = useState('宿主输入层尚未被请求。')
  const [sortValue, setSortValue] = useState(sortDemo.value)
  const [stepperValue, setStepperValue] = useState(PIXEL_STEPPER_DEMO_CASE.value)
  const [checked, setChecked] = useState<readonly boolean[]>(() =>
    PIXEL_CHECKBOX_DEMO_CASES.map((item) => item.checked),
  )
  const [primaryTab, setPrimaryTab] = useState(primaryTabDemo.id)
  const [secondaryTab, setSecondaryTab] = useState(secondaryTabDemo.id)
  const [selectedScale, setSelectedScale] = useState<PixelSlotScale | null>(null)
  const [selectedGridSlot, setSelectedGridSlot] = useState<number | null>(null)
  const [selectedRow, setSelectedRow] = useState<PixelTableDemoListing | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmLoading, setConfirmLoading] = useState(false)
  const [toasts, setToasts] = useState<readonly ToastEntry[]>(seedToasts)

  const logClick = (label: string): void => {
    setClickLog(`最近点击: ${label}`)
  }

  const toggleChecked = (index: number, next: boolean): void => {
    setChecked((current) => current.map((value, at) => (at === index ? next : value)))
  }

  return (
    <div className="flex flex-col gap-8">
      <p className="text-1x text-muted">
        全部控件按 variant x 状态穷举, 样例数据取自各控件导出的 DEMO 常量。
      </p>

      {/* ==================== L0 地基 ==================== */}

      <Section
        title="PixelFrame · 9-slice 容器 (L0)"
        note="三档层级 x 六档语义 = 18 种框体。层级必须分别出图 (斜面方向相反), 语义只换 CSS 变量。这一屏要看的是: 同一 tone 在三档层级下是否仍能看出凸/平/凹的差异。"
      >
        {FRAME_VARIANTS.map((variant) => (
          <Row key={variant} label={VARIANT_LABEL[variant]}>
            {PIXEL_FRAME_TONES.map((tone) => (
              <PixelFrame key={tone} variant={variant} tone={tone} className="w-32 p-4">
                <span className={`text-1x ${TONE_TEXT_CLASS[tone]}`}>{tone}</span>
              </PixelFrame>
            ))}
          </Row>
        ))}
      </Section>

      <Section
        title="PixelIcon · 功能图标 (L0)"
        note="24 张 16x16 单色蒙版, 上色靠 currentColor, 放大只走整数倍。缺哪张图标在这里一眼可数 —— 平板导航当前不给页签图标, 就是因为这份名单里没有首页/职业/矿洞/图鉴/开箱。"
      >
        <Row label="全部图标 (scale 1)">
          {PIXEL_ICON_NAMES.map((name) => (
            <span key={name} className="flex w-40 flex-col items-center gap-1">
              <PixelIcon name={name} label={name} />
              <span className="text-1x text-muted">{name}</span>
            </span>
          ))}
        </Row>
        <Row label="放大档 scale = 1 / 2 / 3">
          <PixelIcon name="star" scale={1} label="星标 1x" />
          <PixelIcon name="star" scale={2} label="星标 2x" />
          <PixelIcon name="star" scale={3} label="星标 3x" />
        </Row>
        <Row label="语义上色 (颜色由父级 text-* 给, 零新增资产)">
          {PIXEL_FRAME_TONES.map((tone) => (
            <span key={tone} className={TONE_TEXT_CLASS[tone]}>
              <PixelIcon name="warning" scale={2} label={`warning ${tone}`} />
            </span>
          ))}
        </Row>
      </Section>

      <Section
        title="ItemIcon · 物品贴图 (L0)"
        note="原版贴图 -> 本 mod 贴图 -> 像素占位块 三层回退。与 PixelIcon 同排时必须取同一个 scale, 否则两套图标的像素密度不同, 一眼可见。"
      >
        <Row label="scale = 1 / 2 / 3">
          <ItemIcon itemId="minecraft:diamond" label="钻石" scale={1} />
          <ItemIcon itemId="minecraft:diamond" label="钻石" scale={2} />
          <ItemIcon itemId="minecraft:diamond" label="钻石" scale={3} />
        </Row>
        <Row label="取图失败时的占位块 (故意给一个不存在的 id)">
          <ItemIcon itemId="wok:not_a_real_item" label="不存在的物品" scale={2} />
        </Row>
      </Section>

      {/* ==================== L1 控件 ==================== */}

      <Section
        title="PixelButton"
        note={`语义维度只有 tone, 没有 primary/ghost 那套第二词表。禁用与忙碌是两件事, 但两者都必须拦住回调 —— 点下面每一个按钮, 只有可用的那些会改这行回执。当前 ${clickLog}`}
      >
        <Row label="典型业务动作 (取自 PIXEL_BUTTON_DEMO_CASES)">
          {PIXEL_BUTTON_DEMO_CASES.map((demo) => (
            <PixelButton
              key={demo.tone}
              tone={demo.tone}
              onClick={() => {
                logClick(demo.label)
              }}
            >
              {demo.label}
            </PixelButton>
          ))}
        </Row>
        <Row label="六档 tone 全展开">
          {PIXEL_FRAME_TONES.map((tone) => (
            <PixelButton
              key={tone}
              tone={tone}
              onClick={() => {
                logClick(`tone ${tone}`)
              }}
            >
              {tone}
            </PixelButton>
          ))}
        </Row>
        <Row label="尺寸档 sm / md / lg">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelButton
              key={size}
              tone="accent"
              size={size}
              onClick={() => {
                logClick(`size ${size}`)
              }}
            >
              {size}
            </PixelButton>
          ))}
        </Row>
        <Row label="状态: 常规 / 禁用 / 忙碌 / 带图标 / 纯图标">
          <PixelButton
            onClick={() => {
              logClick('常规')
            }}
          >
            常规
          </PixelButton>
          <PixelButton
            disabled
            onClick={() => {
              logClick('禁用 (这行不该出现)')
            }}
          >
            禁用
          </PixelButton>
          <PixelButton
            loading
            onClick={() => {
              logClick('忙碌 (这行不该出现)')
            }}
          >
            忙碌
          </PixelButton>
          <PixelButton
            icon="refresh"
            onClick={() => {
              logClick('带图标')
            }}
          >
            刷新
          </PixelButton>
          <PixelButton
            icon="close"
            label="关闭"
            onClick={() => {
              logClick('纯图标')
            }}
          />
        </Row>
      </Section>

      <Section
        title="PixelInput"
        note={`受控单行输入。中文输入当前是 BLOCKED (接线清单 A14), 故第五个输入演示的是转交宿主 EditBox 的模式: 点它或按 Enter/Space 只把当前值喊出去, 不在浏览器里直接打字。${hostEditLog}`}
      >
        <Row label="常规 / 有值 / 禁用 / 非法">
          <PixelInput
            value={searchValue}
            placeholder={searchDemo.placeholder}
            onChange={setSearchValue}
            className="w-64"
          />
          <PixelInput
            value={priceValue}
            placeholder={priceDemo.placeholder}
            onChange={setPriceValue}
            className="w-32"
          />
          <PixelInput value={searchValue} onChange={setSearchValue} disabled className="w-64" />
          <PixelInput value={priceValue} onChange={setPriceValue} invalid className="w-32" />
        </Row>
        <Row label="转交宿主输入层 (onRequestEdit)">
          <PixelInput
            value={searchValue}
            onChange={setSearchValue}
            onRequestEdit={(current) => {
              setHostEditLog(`已向宿主请求编辑, 当时的值是 ${current === '' ? '(空)' : current}。`)
            }}
            className="w-64"
          />
        </Row>
        <Row label="尺寸档 sm / md / lg">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelInput
              key={size}
              value={searchValue}
              onChange={setSearchValue}
              size={size}
              placeholder={size}
              className="w-64"
            />
          ))}
        </Row>
      </Section>

      <Section
        title="PixelSelect"
        note="自绘下拉 (原生 select 的弹层由系统渲染, 与像素风互斥)。展开后用方向键/Home/End 移动、Enter 选中、Esc 收起 —— 焦点必须真的落在选项上, 而不是靠高亮假装。"
      >
        <Row label="常规 / 禁用 / 三档尺寸">
          <PixelSelect
            value={sortValue}
            options={PIXEL_SELECT_DEMO_OPTIONS}
            onChange={setSortValue}
            className="w-64"
          />
          <PixelSelect
            value={sortValue}
            options={PIXEL_SELECT_DEMO_OPTIONS}
            onChange={setSortValue}
            disabled
            className="w-64"
          />
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelSelect
              key={size}
              value={sortValue}
              options={PIXEL_SELECT_DEMO_OPTIONS}
              onChange={setSortValue}
              size={size}
              className="w-64"
            />
          ))}
        </Row>
      </Section>

      <Section
        title="PixelStepper"
        note={`数量与价格靠它绕开中文输入, 因此数值格是只读文本, 不叠可键入的框。按住加减键 400ms 后转连发。当前值 ${String(stepperValue)}, 区间 ${String(PIXEL_STEPPER_DEMO_CASE.min)}-${String(PIXEL_STEPPER_DEMO_CASE.max)}。`}
      >
        <Row label="三档尺寸 (共享同一个受控值)">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelStepper
              key={size}
              value={stepperValue}
              onChange={setStepperValue}
              min={PIXEL_STEPPER_DEMO_CASE.min}
              max={PIXEL_STEPPER_DEMO_CASE.max}
              step={PIXEL_STEPPER_DEMO_CASE.step}
              size={size}
            />
          ))}
        </Row>
        <Row label="整体禁用 / 已触底 (只有减号灰) / 已触顶 (只有加号灰)">
          <PixelStepper
            value={stepperValue}
            onChange={setStepperValue}
            min={PIXEL_STEPPER_DEMO_CASE.min}
            max={PIXEL_STEPPER_DEMO_CASE.max}
            disabled
          />
          <PixelStepper value={0} onChange={setStepperValue} min={0} max={64} />
          <PixelStepper value={64} onChange={setStepperValue} min={0} max={64} />
        </Row>
      </Section>

      <Section
        title="PixelCheckbox"
        note="原生 input 藏在 sr-only 里承担全部键盘与读屏语义, 方框只是视觉。label 必填, 且点文字也要能切换。"
      >
        <Row label="未勾选 / 已勾选 / 禁用">
          {PIXEL_CHECKBOX_DEMO_CASES.map((demo, index) => (
            <PixelCheckbox
              key={demo.label}
              checked={checked[index] ?? demo.checked}
              label={demo.label}
              onChange={(next) => {
                toggleChecked(index, next)
              }}
            />
          ))}
          <PixelCheckbox
            checked
            label="禁用且已勾选"
            disabled
            onChange={(next) => {
              // 接一个真实的写回: 原生 disabled 已在浏览器层拦住 change, 这行若真跑了就是控件缺陷。
              toggleChecked(0, next)
            }}
          />
        </Row>
        <Row label="三档尺寸 (方框边长锁定在 PixelIcon 的量化档位上)">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelCheckbox
              key={size}
              checked={checked[0] ?? false}
              label={size}
              size={size}
              onChange={(next) => {
                toggleChecked(0, next)
              }}
            />
          ))}
        </Row>
      </Section>

      <Section
        title="PixelSlot / PixelSlotGrid"
        note={`物品格与背包网格。网格里的 slot 号是服务端槽位索引而不是数组下标, 方向键在格间移动且行首行尾不环绕。当前选中槽位 ${selectedGridSlot === null ? '无' : String(selectedGridSlot)}。`}
      >
        <Row label="占用 / 满堆 / 单件 / 禁用 / 空槽 (取自 PIXEL_SLOT_DEMO_ENTRIES)">
          {PIXEL_SLOT_DEMO_ENTRIES.map((entry, index) => (
            <PixelSlot
              key={`${entry.itemId ?? 'empty'}-${String(index)}`}
              {...(entry.itemId === undefined ? {} : { itemId: entry.itemId })}
              {...(entry.count === undefined ? {} : { count: entry.count })}
              {...(entry.label === undefined ? {} : { label: entry.label })}
              selected={entry.selected ?? false}
              disabled={entry.disabled ?? false}
              scale={2}
            />
          ))}
        </Row>
        <Row label="尺寸档 scale = 1 / 2 / 3 (可点击, 带选中态)">
          {SLOT_SCALES.map((scale) => (
            <PixelSlot
              key={scale}
              itemId="minecraft:emerald"
              count={3}
              label="绿宝石"
              scale={scale}
              selected={selectedScale === scale}
              onClick={() => {
                setSelectedScale(scale)
              }}
            />
          ))}
        </Row>
        <Row label="27 格背包网格 (3 行 x 9 列, 与 MC 背包同宽)">
          <PixelSlotGrid
            slots={PIXEL_SLOT_GRID_DEMO_SLOTS}
            columns={PIXEL_SLOT_GRID_DEMO_COLUMNS}
            {...(selectedGridSlot === null ? {} : { selectedSlot: selectedGridSlot })}
            onSelect={setSelectedGridSlot}
            label="示例背包"
          />
        </Row>
      </Section>

      <Section
        title="PixelTable"
        note={`订单簿式数据表: 点表头三态循环排序 (升 -> 降 -> 取消), 行可选中。当前选中行 ${selectedRow === null ? '无' : selectedRow.itemName}。`}
      >
        <Row label="有数据 (高度经 className 给, 超出即自绘滚动)">
          <PixelTable
            columns={PIXEL_TABLE_DEMO_COLUMNS}
            rows={PIXEL_TABLE_DEMO_ROWS}
            rowKey={(row) => String(row.id)}
            onRowClick={setSelectedRow}
            {...(selectedRow === null ? {} : { selectedRowKey: String(selectedRow.id) })}
            className="h-64 w-96"
          />
        </Row>
        <Row label="空数据 (emptyHint)">
          <PixelTable
            columns={PIXEL_TABLE_DEMO_COLUMNS}
            rows={[]}
            rowKey={(row: PixelTableDemoListing) => String(row.id)}
            emptyHint="该分类下暂无挂单"
            className="h-32 w-96"
          />
        </Row>
      </Section>

      <Section
        title="PixelScrollArea"
        note="零原生滚动条: 内容区 overflow:hidden, 滚轮/方向键/翻页键/拖拽拇指四条通路全部自绘。原生滚动条是圆角矢量, 与硬红线冲突。"
      >
        <Row label="垂直滚动 (40 行)">
          <PixelScrollArea className="h-64 w-64" label="示例长列表">
            <ul className="flex flex-col gap-1 p-2">
              {PIXEL_SCROLL_AREA_DEMO_LINES.map((line) => (
                <li key={line} className="text-1x text-fg">
                  {line}
                </li>
              ))}
            </ul>
          </PixelScrollArea>
        </Row>
      </Section>

      <Section
        title="PixelTabs"
        note="一级挂 panel (平板 hub 的顶层分区), 二级挂 inset (面板内的子视图)。左右方向键在页签间移动焦点并直接切换。"
      >
        <Row label="一级 (带图标)">
          <PixelTabs tabs={PIXEL_TABS_DEMO_PRIMARY} activeId={primaryTab} onChange={setPrimaryTab} />
        </Row>
        <Row label="二级 (纯文字, 更紧凑)">
          <PixelTabs
            tabs={PIXEL_TABS_DEMO_SECONDARY}
            activeId={secondaryTab}
            onChange={setSecondaryTab}
            level="secondary"
          />
        </Row>
        <Row label="整条禁用">
          <PixelTabs
            tabs={PIXEL_TABS_DEMO_SECONDARY}
            activeId={secondaryTab}
            onChange={setSecondaryTab}
            level="secondary"
            disabled
          />
        </Row>
      </Section>

      <Section
        title="PixelProgress"
        note="经验条 / 耐久 (带警戒阈值) / 产能缓冲 (多段合成) 共用同一副身体。越界值按端点画, 但缺值不被抹平成 0。"
      >
        {PIXEL_PROGRESS_DEMO_ITEMS.map((demo) => (
          <Row key={demo.id} label={demo.id}>
            <PixelProgress
              value={demo.value}
              max={demo.max}
              tone={demo.tone}
              label={demo.label}
              {...(demo.segments === undefined ? {} : { segments: demo.segments })}
              {...(demo.thresholds === undefined ? {} : { thresholds: demo.thresholds })}
              className="w-96"
            />
          </Row>
        ))}
        <Row label="三档轨道高度 sm / md / lg">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelProgress
              key={size}
              value={60}
              max={100}
              tone="accent"
              size={size}
              className="w-64"
            />
          ))}
        </Row>
      </Section>

      <Section
        title="PixelBadge"
        note="星级 / 品质 / 状态三类用途共用一个容器, 变的只有 tone 与内容。默认档是 sm —— 徽标与正文并排时不该比正文高一截。"
      >
        <Row label="典型用法 (取自 PIXEL_BADGE_DEMO_ITEMS)">
          {PIXEL_BADGE_DEMO_ITEMS.map((demo) => (
            <PixelBadge key={demo.id} tone={demo.tone}>
              {demo.label}
            </PixelBadge>
          ))}
        </Row>
        <Row label="六档 tone x 三档 size">
          {PIXEL_FRAME_TONES.map((tone) =>
            PIXEL_CONTROL_SIZES.map((size) => (
              <PixelBadge key={`${tone}-${size}`} tone={tone} size={size}>
                {`${tone}/${size}`}
              </PixelBadge>
            )),
          )}
        </Row>
      </Section>

      <Section
        title="PixelTooltip"
        note="必须同时响应 hover 与 focus (原生 title 属性被禁: 系统渲染, 既不是像素风延迟也不可控)。按 Esc 关掉之后, 光标不动也不该弹回来。"
      >
        <Row label="四个方位">
          {PIXEL_TOOLTIP_DEMO_ITEMS.map((demo) => (
            <PixelTooltip key={demo.id} content={demo.content} placement={demo.placement}>
              <span className="text-1x text-fg">{demo.trigger}</span>
            </PixelTooltip>
          ))}
        </Row>
      </Section>

      <Section
        title="PixelCurrency"
        note="双货币行内展示。千分位分组只在本组件内实现, 面板不许自己拼字符串。它是行内文本件, 刻意不套 9-slice 边框。"
      >
        <Row label="信用点 / 青辉石 (含大额验分组)">
          {PIXEL_CURRENCY_DEMO_ITEMS.map((demo) => (
            <PixelCurrency key={demo.id} amount={demo.amount} currency={demo.currency} />
          ))}
        </Row>
        <Row label="三档尺寸 / 不带图标">
          {PIXEL_CONTROL_SIZES.map((size) => (
            <PixelCurrency key={size} amount={1284560} currency="credit" size={size} />
          ))}
          <PixelCurrency amount={1284560} currency="credit" showIcon={false} />
        </Row>
      </Section>

      {/* ==================== L2 状态件 ==================== */}

      <Section
        title="PixelLoading"
        note="全库唯一允许实现动效的地方, 且只能是整帧色块切换 —— 旋转圆环的中间帧必落非整数角度, 那是半像素抗锯齿的直接来源。"
      >
        <Row label="三档尺寸 (lg 档故意不给 label, 演示嵌进按钮的用法)">
          {PIXEL_LOADING_DEMO.map((demo) => (
            <PixelLoading
              key={demo.size}
              size={demo.size}
              {...(demo.label === undefined ? {} : { label: demo.label })}
            />
          ))}
        </Row>
      </Section>

      <Section
        title="PixelEmpty / PixelError"
        note="空态表达的是暂无内容 (图标压成 muted), 错误态是需要打断的异常 (tone 固定 danger + role=alert)。两者的分量差必须一眼可辨。"
      >
        <Row label="空态">
          <div className="w-96">
            <PixelEmpty
              title={PIXEL_EMPTY_DEMO.title}
              {...(PIXEL_EMPTY_DEMO.hint === undefined ? {} : { hint: PIXEL_EMPTY_DEMO.hint })}
              {...(PIXEL_EMPTY_DEMO.icon === undefined ? {} : { icon: PIXEL_EMPTY_DEMO.icon })}
            />
          </div>
        </Row>
        <Row label="错误态: 带重试 / 不带重试">
          <div className="w-96">
            <PixelError
              message={PIXEL_ERROR_DEMO.message}
              {...(PIXEL_ERROR_DEMO.code === undefined ? {} : { code: PIXEL_ERROR_DEMO.code })}
              onRetry={() => {
                logClick('错误态重试')
              }}
            />
          </div>
          <div className="w-96">
            <PixelError message="该物品已被下架, 无法查看详情" />
          </div>
        </Row>
      </Section>

      <Section
        title="PixelModal / PixelConfirmDanger"
        note="两者的差别不在外观而在语义: dialog 允许点遮罩关闭, alertdialog 不允许 (破坏性操作必须走显式按钮)。打开后 Tab 不得跑出浮层, Esc 关闭并把焦点还回触发按钮。"
      >
        <Row label="开关">
          <PixelButton
            tone="accent"
            onClick={() => {
              setModalOpen(true)
            }}
          >
            打开模态框
          </PixelButton>
          <PixelButton
            tone="danger"
            onClick={() => {
              setConfirmOpen(true)
            }}
          >
            打开危险确认
          </PixelButton>
          <PixelCheckbox
            checked={confirmLoading}
            label="危险确认处于处理中"
            onChange={setConfirmLoading}
          />
        </Row>
      </Section>

      <Section
        title="PixelToast"
        note="纯展示件: 它不管自己出现在屏幕哪里、也不管同时有几条, 挂载点与队列由消费页面决定。4 秒自动消失, 消失后用右边的按钮重放。"
      >
        <Row label="三档语义回执">
          <div className="flex flex-col gap-2">
            {toasts.map((toast) => (
              <PixelToast
                key={toast.id}
                tone={toast.tone}
                message={toast.message}
                onDismiss={() => {
                  setToasts((current) => current.filter((item) => item.id !== toast.id))
                }}
              />
            ))}
            {toasts.length === 0 ? <p className="text-1x text-muted">全部提示已消失</p> : null}
          </div>
          <PixelButton
            icon="refresh"
            onClick={() => {
              setToasts(seedToasts())
            }}
          >
            重放提示
          </PixelButton>
        </Row>
      </Section>

      <PixelModal
        open={modalOpen}
        title={PIXEL_MODAL_DEMO.title}
        {...(PIXEL_MODAL_DEMO.size === undefined ? {} : { size: PIXEL_MODAL_DEMO.size })}
        onClose={() => {
          setModalOpen(false)
        }}
      >
        <div className="flex flex-col gap-4">
          <p className="text-1x text-fg">浮层内部同样只用本库控件, 不另起一套排版。</p>
          <PixelStepper
            value={stepperValue}
            onChange={setStepperValue}
            min={PIXEL_STEPPER_DEMO_CASE.min}
            max={PIXEL_STEPPER_DEMO_CASE.max}
          />
          <PixelButton
            tone="accent"
            onClick={() => {
              logClick('模态框内确认')
              setModalOpen(false)
            }}
          >
            确认
          </PixelButton>
        </div>
      </PixelModal>

      <PixelConfirmDanger
        open={confirmOpen}
        title={PIXEL_CONFIRM_DANGER_DEMO.title}
        message={PIXEL_CONFIRM_DANGER_DEMO.message}
        confirmLabel={PIXEL_CONFIRM_DANGER_DEMO.confirmLabel}
        loading={confirmLoading}
        onConfirm={() => {
          logClick('危险确认')
          setConfirmOpen(false)
        }}
        onCancel={() => {
          setConfirmOpen(false)
        }}
      />
    </div>
  )
}
