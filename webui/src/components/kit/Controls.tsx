import type { ReactElement, ReactNode } from 'react'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import {
  NumberField,
  NumberFieldDecrement,
  NumberFieldGroup,
  NumberFieldIncrement,
  NumberFieldInput,
} from '@/components/ui/number-field'
import { Progress, ProgressIndicator, ProgressTrack } from '@/components/ui/progress'
import { Select, SelectItem, SelectPopup, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsList, TabsTab } from '@/components/ui/tabs'
import { Tooltip, TooltipPopup, TooltipTrigger } from '@/components/ui/tooltip'
import type { ControlSize, Tone } from './tokens'
import { COSS_SIZE, TEXT_SIZE_CLASS, TONE_FILL_CLASS } from './tokens'

/**
 * 表单与展示控件。全部收敛成"受控 + 值/onChange"的朴素签名, 不向业务页暴露 Base UI 的
 * eventDetails 第二参数与 render prop 组合模式 —— 那套 API 的表达力业务页用不上, 而它一旦渗进
 * 15 个页面, 上游 Base UI 的任何签名调整都要全库改。
 */

// ============================================================
// 文本输入
// ============================================================

export interface TextInputProps {
  value: string
  onChange: (next: string) => void
  placeholder?: string | undefined
  disabled?: boolean | undefined
  invalid?: boolean | undefined
  size?: ControlSize | undefined
  maxLength?: number | undefined
  type?: 'text' | 'search' | 'password' | undefined
  /**
   * 交给宿主编辑。给了这个回调, 输入框变成只读并在点击/聚焦时回调宿主。
   *
   * 存在的理由是中文输入: MCEF 内嵌的 Chromium 拿不到 MC 客户端的 IME 焦点, 页面内的 <input>
   * 敲中文只会掉字符。真正能打中文的是 MC 自己的原生输入框, 故涉及自由文本 (挂单搜索、改名)
   * 的位置必须走"页面请求 -> 宿主弹原生输入 -> 回填"这条路。
   *
   * 宿主侧通道尚未接线 (接线清单第四章), 当前调用方一律不传此 prop, 输入框按普通 input 工作 ——
   * 在浏览器里的设计预览下这是对的, 装进游戏后再统一接。
   */
  onRequestEdit?: ((current: string) => void) | undefined
  className?: string | undefined
}

export function TextInput({
  value,
  onChange,
  placeholder,
  disabled = false,
  invalid = false,
  size = 'md',
  maxLength,
  type = 'text',
  onRequestEdit,
  className,
}: TextInputProps): ReactElement {
  const hostEdit = onRequestEdit !== undefined
  return (
    <Input
      aria-invalid={invalid || undefined}
      className={className}
      disabled={disabled}
      maxLength={maxLength}
      onChange={(event) => {
        onChange(event.target.value)
      }}
      onClick={
        hostEdit
          ? () => {
              onRequestEdit(value)
            }
          : undefined
      }
      placeholder={placeholder}
      readOnly={hostEdit}
      size={COSS_SIZE[size]}
      type={type}
      value={value}
    />
  )
}

// ============================================================
// 数字步进
// ============================================================

export interface NumberInputProps {
  value: number
  onChange: (next: number) => void
  min: number
  max: number
  step?: number | undefined
  disabled?: boolean | undefined
  size?: ControlSize | undefined
  className?: string | undefined
}

/**
 * Base UI 的 onValueChange 在输入框被清空时给 null。这里刻意不把 null 折成 min 或 0 ——
 * 那会让"用户正在删数字准备重输"这个中间态被强行改写成一个真实数值, 表现为光标乱跳。
 * 空值期间不向上游发值, 上游持有的仍是上一个合法值。
 */
export function NumberInput({
  value,
  onChange,
  min,
  max,
  step = 1,
  disabled = false,
  size = 'md',
  className,
}: NumberInputProps): ReactElement {
  return (
    <NumberField
      className={className}
      disabled={disabled}
      max={max}
      min={min}
      onValueChange={(next) => {
        if (next !== null) {
          onChange(next)
        }
      }}
      size={COSS_SIZE[size]}
      step={step}
      value={value}
    >
      <NumberFieldGroup>
        <NumberFieldDecrement />
        <NumberFieldInput />
        <NumberFieldIncrement />
      </NumberFieldGroup>
    </NumberField>
  )
}

// ============================================================
// 下拉选择
// ============================================================

export interface DropdownOption<TValue extends string> {
  value: TValue
  label: string
  disabled?: boolean | undefined
}

export interface DropdownProps<TValue extends string> {
  value: TValue
  onChange: (next: TValue) => void
  options: readonly DropdownOption<TValue>[]
  placeholder?: string | undefined
  disabled?: boolean | undefined
  size?: ControlSize | undefined
  className?: string | undefined
}

export function Dropdown<TValue extends string>({
  value,
  onChange,
  options,
  placeholder,
  disabled = false,
  size = 'md',
  className,
}: DropdownProps<TValue>): ReactElement {
  return (
    <Select
      disabled={disabled}
      // items 让 Select 在弹层未挂载时也能把 value 反查成显示文本 —— 缺了它, 触发器在首帧
      // 显示的是原始 value ("all") 而不是 label ("全部分类")。
      items={options}
      onValueChange={(next) => {
        // Base UI 在多选关闭时给的就是单值; 泛型收窄由调用方的 options 保证。
        onChange(next as TValue)
      }}
      value={value}
    >
      <SelectTrigger className={className} size={COSS_SIZE[size]}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectPopup>
        {options.map((option) => (
          <SelectItem disabled={option.disabled} key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectPopup>
    </Select>
  )
}

// ============================================================
// 开关
// ============================================================

export interface ToggleProps {
  checked: boolean
  onChange: (next: boolean) => void
  label: string
  disabled?: boolean | undefined
  size?: ControlSize | undefined
  className?: string | undefined
}

export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
  size = 'md',
  className,
}: ToggleProps): ReactElement {
  return (
    <label
      className={`inline-flex cursor-pointer items-center gap-2 ${TEXT_SIZE_CLASS[size]} text-foreground${
        className === undefined ? '' : ` ${className}`
      }`}
    >
      <Checkbox
        checked={checked}
        disabled={disabled}
        onCheckedChange={(next) => {
          onChange(next)
        }}
      />
      <span>{label}</span>
    </label>
  )
}

// ============================================================
// 进度条
// ============================================================

export interface MeterProps {
  value: number
  /** 必须为正数。本组件不为 0 或负值兜底 —— 那会把上游传错的值悄悄描成看似正常的进度条。 */
  max: number
  tone?: Tone | undefined
  size?: ControlSize | undefined
  /** 左上角说明文字。 */
  label?: string | undefined
  /** 右上角补充文字 (如 "3200 / 5000")。不给则显示百分比。 */
  valueText?: string | undefined
  /** 置真则整条只有轨道, 不渲染上方那一行文字。默认假。 */
  bare?: boolean | undefined
  /**
   * 轨道上的参考刻度 (如 TPS 的 15 那条线、精通度的升段坎)。
   *
   * 与 value 的区别: value 是"现在到哪了", threshold 是"到哪算及格"。没有刻度的话, 一条填了
   * 七成的进度条到底是好是坏, 只能靠旁边的文字解释 —— 而那正是刻度存在的意义。
   */
  thresholds?: readonly MeterThreshold[] | undefined
  className?: string | undefined
}

export interface MeterThreshold {
  /** 与 value 同一量纲。超出 [0, max] 的刻度不渲染 —— 画在轨道外面只会误导。 */
  value: number
  /** 无障碍名与悬停提示, 如 "及格线 15"。 */
  label: string
  tone?: Tone | undefined
}

const TRACK_HEIGHT_CLASS: Record<ControlSize, string> = {
  sm: 'h-1',
  md: 'h-1.5',
  lg: 'h-2.5',
}

export function Meter({
  value,
  max,
  tone = 'brand',
  size = 'md',
  label,
  valueText,
  bare = false,
  thresholds,
  className,
}: MeterProps): ReactElement {
  const percent = Math.round((value / max) * 100)
  return (
    <Progress className={className} max={max} value={value}>
      {bare ? null : (
        <div className="flex items-baseline justify-between gap-2 text-xs">
          <span className="text-muted-foreground">{label}</span>
          <span className="tabular-nums text-foreground">{valueText ?? `${String(percent)}%`}</span>
        </div>
      )}
      <ProgressTrack className={`relative ${TRACK_HEIGHT_CLASS[size]}`}>
        <ProgressIndicator className={TONE_FILL_CLASS[tone]} />
        {thresholds === undefined
          ? null
          : thresholds
              .filter((mark) => mark.value >= 0 && mark.value <= max)
              .map((mark) => (
                <span
                  // 刻度画在填充块之上 (z-10): 填充块盖过刻度的话, 一旦进度超过刻度线,
                  // 那条线就消失了 —— 而"已经越过及格线"恰恰是最需要看到刻度的时刻。
                  className={`absolute inset-y-0 z-10 w-0.5 ${TONE_FILL_CLASS[mark.tone ?? 'neutral']}`}
                  key={mark.label}
                  style={{ left: `${String((mark.value / max) * 100)}%` }}
                  title={mark.label}
                />
              ))}
      </ProgressTrack>
    </Progress>
  )
}

// ============================================================
// 页签栏
// ============================================================

export interface TabItem {
  id: string
  label: string
  disabled?: boolean | undefined
  /** 页签右侧的小标记 (如未读数)。 */
  badge?: ReactNode | undefined
}

export interface TabBarProps {
  tabs: readonly TabItem[]
  activeId: string
  onChange: (id: string) => void
  /** default 是灰底分段控件 (一级导航); underline 是下划线 (页内次级切换)。 */
  variant?: 'default' | 'underline' | undefined
  className?: string | undefined
}

export function TabBar({
  tabs,
  activeId,
  onChange,
  variant = 'default',
  className,
}: TabBarProps): ReactElement {
  return (
    <Tabs
      onValueChange={(next) => {
        onChange(String(next))
      }}
      value={activeId}
    >
      <TabsList className={className} variant={variant}>
        {tabs.map((tab) => (
          <TabsTab disabled={tab.disabled} key={tab.id} value={tab.id}>
            {tab.label}
            {tab.badge}
          </TabsTab>
        ))}
      </TabsList>
    </Tabs>
  )
}

// ============================================================
// 提示气泡
// ============================================================

export interface HintProps {
  /** 气泡内容。 */
  content: ReactNode
  /** 触发元素。必须能接住 ref 与事件 (原生元素或 Coss 组件均可)。 */
  children: ReactNode
}

export function Hint({ content, children }: HintProps): ReactElement {
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="inline-flex" />}>{children}</TooltipTrigger>
      <TooltipPopup>{content}</TooltipPopup>
    </Tooltip>
  )
}
