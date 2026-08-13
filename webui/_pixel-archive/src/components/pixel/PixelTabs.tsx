import type { KeyboardEvent, ReactElement } from 'react'
import { useRef } from 'react'
import type { PixelFrameVariant } from './PixelFrame'
import { PixelFrame } from './PixelFrame'
import type { PixelIconName } from './PixelIcon'
import { PixelIcon } from './PixelIcon'

/**
 * 一级/二级两档导航切换控件。真源: conventions.md 十 · L1 · PixelTabs (冻结 props: tabs/activeId/onChange)。
 *
 * 两档样式靠 `level` 决定, 不是给组件新开一个 variant 维度——它只是在内部选用哪一档既有 PixelFrame
 * variant 承载, 没有新增资产诉求(冻结表明确禁止组件自造 variant, 见同文件二-2.4)。一级挂在
 * panel(平面板)上, 带图标, 用于平板 hub 的顶层分区切换; 二级挂在 inset(内凹凹槽)上, 纯文字更紧凑,
 * 用于面板内部的子视图切换。两档共用同一套受控值与键盘逻辑, 分叉只落在渲染层的几张 className 表里。
 *
 * 键盘走 WAI-ARIA tablist 的 roving tabindex: 只有当前选中(或无匹配时的首个)tab 留在 Tab 序列里,
 * 左右方向键在 tabs 之间移动焦点并直接触发 onChange —— MCEF 内鼠标可能不精准, 这条键盘通路不是可选项。
 */

export type PixelTabsLevel = 'primary' | 'secondary'

export interface PixelTab {
  id: string
  label: string
  icon?: PixelIconName
}

export interface PixelTabsProps {
  tabs: readonly PixelTab[]
  activeId: string
  onChange: (id: string) => void
  /** 默认 primary。 */
  level?: PixelTabsLevel
  /** 默认 false; 置真时整条切换栏换色到静默档且不响应点击与方向键。 */
  disabled?: boolean
  className?: string
}

const LEVEL_FRAME_VARIANT: Record<PixelTabsLevel, PixelFrameVariant> = {
  primary: 'panel',
  secondary: 'inset',
}

const LEVEL_PADDING_CLASS: Record<PixelTabsLevel, string> = {
  primary: 'px-4 py-2',
  secondary: 'px-3 py-1',
}

function tabButtonClass(level: PixelTabsLevel, active: boolean, disabled: boolean): string {
  const padding = LEVEL_PADDING_CLASS[level]
  if (disabled) {
    return `${padding} text-1x text-muted border-2 border-transparent`
  }
  const paint = active ? 'bg-accent text-on-accent' : 'text-muted hover:bg-raised hover:text-fg'
  // 常驻透明边框占住焦点态要用的空间, 使 focus-visible 换色时不改变按钮盒尺寸 (七-4 禁止布局跳动)。
  return `${padding} text-1x ${paint} border-2 border-transparent outline-none focus-visible:border-border-strong`
}

export function PixelTabs({
  tabs,
  activeId,
  onChange,
  level = 'primary',
  disabled = false,
  className,
}: PixelTabsProps): ReactElement {
  // 按 tab.id 存节点而非下标数组: 键盘切换要在 onChange 生效的同一帧把焦点移到新按钮,
  // id 与受控 activeId 天然对齐, 不必操心渲染间隙里下标是否还指向同一个 tab。
  const buttonsRef = useRef(new Map<string, HTMLButtonElement>())

  const activeIndex = tabs.findIndex((tab) => tab.id === activeId)
  // activeId 未命中任何 tab 时 (初始态或上游传错), 把首个 tab 视为焦点落点, 保证 tablist 里恒有且仅有
  // 一个 tabIndex=0 的按钮——一个都没有会让键盘用户完全够不到这条控件。
  const rovingId = activeIndex >= 0 ? activeId : tabs[0]?.id

  const moveTo = (index: number): void => {
    const target = tabs[index]
    if (target === undefined) {
      return
    }
    onChange(target.id)
    buttonsRef.current.get(target.id)?.focus()
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number): void => {
    if (disabled || tabs.length === 0) {
      return
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      moveTo((index + 1) % tabs.length)
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault()
      moveTo((index - 1 + tabs.length) % tabs.length)
    }
  }

  return (
    <div role="tablist" className={className}>
      <PixelFrame variant={LEVEL_FRAME_VARIANT[level]} className="inline-flex gap-1 p-1">
        {tabs.map((tab, index) => {
          const active = tab.id === activeId
          return (
            <button
              key={tab.id}
              type="button"
              ref={(node) => {
                if (node === null) {
                  buttonsRef.current.delete(tab.id)
                } else {
                  buttonsRef.current.set(tab.id, node)
                }
              }}
              role="tab"
              aria-selected={active}
              disabled={disabled}
              tabIndex={tab.id === rovingId ? 0 : -1}
              className={`flex items-center gap-2 ${tabButtonClass(level, active, disabled)}`}
              onClick={() => {
                onChange(tab.id)
              }}
              onKeyDown={(event) => {
                handleKeyDown(event, index)
              }}
            >
              {tab.icon === undefined ? null : <PixelIcon name={tab.icon} scale={1} />}
              <span>{tab.label}</span>
            </button>
          )
        })}
      </PixelFrame>
    </div>
  )
}

/** 一级 (平板 hub 顶层分区) 与二级 (面板内子视图) 各一份示例, 供预览页与面板 agent 直接复用。 */
export const PIXEL_TABS_DEMO_PRIMARY: readonly PixelTab[] = [
  { id: 'market', label: '集市', icon: 'bag' },
  { id: 'inventory', label: '背包', icon: 'star' },
  { id: 'case', label: '开箱', icon: 'coin-azure' },
  { id: 'admin', label: '管理', icon: 'settings' },
]

export const PIXEL_TABS_DEMO_SECONDARY: readonly PixelTab[] = [
  { id: 'active', label: '在售中' },
  { id: 'sold', label: '已售出' },
  { id: 'expired', label: '已过期' },
]
