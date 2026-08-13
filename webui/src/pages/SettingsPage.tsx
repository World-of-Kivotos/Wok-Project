import type { CSSProperties, ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Currency,
  Dropdown,
  FeedbackAlert,
  type FeedbackTone,
  Meter,
  Panel,
  Surface,
  TabBar,
  Tag,
  Toggle,
} from '@/components/kit'
import { BRAND_CHROMA_MAX, BRAND_PRESETS, useBrand } from '@/lib/brand'
import type { Theme } from '@/lib/theme'
import { useTheme } from '@/lib/theme'

/*
 * 设置: UI 偏好。
 *
 * 契约缺口 (清单 A9 player.prefs, BACKEND 未落地): planned.ts 定义了 player.prefs.get/set 且
 * mock/handlers.ts 已实现 (走 world.prefs 内存态), 但架构决策 (清单第七章"我直接定的"第 2 条) 明确
 * 拍板不做这条 action —— 服务端 MiningPlayerData 没有 UI 偏好字段, 长期方案是 Chromium localStorage,
 * player.prefs 只是留个位置以防日后真出现跨机器诉求。因此本页刻意不调 callMock('player.prefs.*'),
 * 全部偏好直接读写 localStorage —— 这意味着**换一台机器/清一次浏览器缓存, 偏好就不跟随**,
 * 页面上必须标出这一点, 不能让玩家以为这是账号级设置。
 *
 * 本页不发起任何远端/mock 请求, 因此没有可触发的加载态/错误态 —— 偏好要么已在 localStorage 里、
 * 要么取内置默认值, 不存在"取不到"的中间状态。
 *
 * 原"界面缩放"一节已随像素风一并撤除: 它调的是 --pixel-scale (9-slice 边框的整数放大倍率),
 * 而 9-slice 那套已整体封存到 webui/_pixel-archive/。留着它就是一个拖了没有任何效果的滑块。
 */

const MUTE_STORAGE_KEY = 'wok-prefs-mute-toasts'
const LANGUAGE_STORAGE_KEY = 'wok-prefs-language'

/** 界面文案目前全部硬编码简体中文, 唯一能真实生效的语言只有这一档。 */
const UI_LANGUAGE_VALUE = 'zh_cn'
const LANGUAGE_OPTIONS = [{ label: '简体中文', value: UI_LANGUAGE_VALUE }] as const

function readStoredMuteToasts(): boolean {
  return localStorage.getItem(MUTE_STORAGE_KEY) === 'true'
}

const THEME_TABS: readonly { id: Theme; label: string }[] = [
  { id: 'dark', label: '暗色' },
  { id: 'light', label: '亮色' },
]

/** 通知预览的自动消失时长; 太短来不及看清, 太长又会挡住下面的控件。 */
const PREVIEW_DISMISS_MS = 4_000

type PreviewBanner = { tone: FeedbackTone; message: string } | null

/**
 * 色相带的渐变停靠点。13 个停靠点 (每 30 度一个) 是肉眼看不出分段的下限 ——
 * 再少会在青绿区出现可见的直线过渡, 而那一段恰好是人眼对色相变化最敏感的区间。
 */
function hueTrackImage(chroma: number): string {
  const stops = Array.from(
    { length: 13 },
    (_unused, index) => `oklch(0.64 ${String(chroma)} ${String(index * 30)})`,
  )
  return `linear-gradient(to right, ${stops.join(', ')})`
}

function chromaTrackImage(hue: number): string {
  return `linear-gradient(to right, oklch(0.64 0 ${String(hue)}), oklch(0.64 ${String(
    BRAND_CHROMA_MAX,
  )} ${String(hue)}))`
}

export function SettingsPage(): ReactElement {
  const { theme, toggle: toggleTheme } = useTheme()
  const { brand, setBrand, reset: resetBrand } = useBrand()

  const [muteToasts, setMuteToasts] = useState<boolean>(readStoredMuteToasts)
  const [language, setLanguage] = useState<string>(UI_LANGUAGE_VALUE)
  const [preview, setPreview] = useState<PreviewBanner>(null)

  const previewTimeoutRef = useRef<number | null>(null)

  useEffect(() => {
    localStorage.setItem(MUTE_STORAGE_KEY, String(muteToasts))
  }, [muteToasts])

  useEffect(() => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, language)
  }, [language])

  useEffect(
    () => () => {
      if (previewTimeoutRef.current !== null) {
        window.clearTimeout(previewTimeoutRef.current)
      }
    },
    [],
  )

  function handlePreviewToast(): void {
    if (previewTimeoutRef.current !== null) {
      window.clearTimeout(previewTimeoutRef.current)
    }
    setPreview(
      muteToasts
        ? {
            message: '已被免打扰设置拦截: 示例通知「你的挂单已成交, 到账 480 信用点」',
            tone: 'neutral',
          }
        : { message: '示例通知: 你的挂单已成交, 到账 480 信用点', tone: 'info' },
    )
    previewTimeoutRef.current = window.setTimeout(() => {
      setPreview(null)
      previewTimeoutRef.current = null
    }, PREVIEW_DISMISS_MS)
  }

  function handleThemeChange(id: string): void {
    if ((id === 'dark' || id === 'light') && id !== theme) {
      toggleTheme()
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Surface tone="warning">
        <p className="text-foreground text-sm">
          以下设置只保存在这台电脑上, 不跟随账号 —— 换一台电脑就会恢复默认值, 需要重新调整一次。
        </p>
      </Surface>

      {/*
        autoDismissMs={0} 关掉组件自带的倒计时, 由本页 previewTimeoutRef 那套接管。
        差别在重复触发: 组件的计时只在文案变化时重起, 而连点两次"预览"多半得到同一句文案 ——
        那样第二次点下去看着像没反应, 且横幅按第一次的时间线消失。本页的计时器每次点击都重置, 是对的。
      */}
      {preview === null ? null : (
        <FeedbackAlert
          autoDismissMs={0}
          message={preview.message}
          onDismiss={() => {
            setPreview(null)
          }}
          tone={preview.tone}
        />
      )}

      <Panel description="决定界面整体是深色还是浅色, 强调色不受影响。" title="主题">
        <TabBar
          activeId={theme}
          onChange={handleThemeChange}
          tabs={THEME_TABS.map((tab) => ({ id: tab.id, label: tab.label }))}
        />
      </Panel>

      <Panel
        actions={
          <Button onClick={resetBrand} size="sm" variant="outline">
            恢复默认
          </Button>
        }
        description="界面主体是固定的灰色, 这里调的是少量强调色 —— 当前导航项、进度条、选中行。"
        title="强调色"
      >
        <div className="flex flex-col gap-5">
          {/*
            只开放色相与彩度两个自由度, 亮度锁死在样式表的常量上 (见 lib/brand.ts 的文件注释)。
            强调色要在上面压白字, 放开亮度就意味着用户能调出读不出来的组合, 而那类问题只在真客户端的
            某几个页面上才暴露。锁死 L 之后, 对比度是结构性保证而不是用户自觉。
          */}
          <div className="flex flex-col gap-2">
            <div className="flex items-baseline justify-between">
              <label className="text-foreground text-sm" htmlFor="brand-hue">
                色相
              </label>
              <span className="text-muted-foreground text-xs tabular-nums">
                {String(Math.round(brand.hue))}°
              </span>
            </div>
            <input
              className="gradient-slider"
              id="brand-hue"
              max={360}
              min={0}
              onChange={(event) => {
                setBrand({ ...brand, hue: Number(event.target.value) })
              }}
              step={1}
              style={{ '--track-image': hueTrackImage(brand.chroma) } as CSSProperties}
              type="range"
              value={brand.hue}
            />
          </div>

          <div className="flex flex-col gap-2">
            <div className="flex items-baseline justify-between">
              <label className="text-foreground text-sm" htmlFor="brand-chroma">
                彩度
              </label>
              <span className="text-muted-foreground text-xs tabular-nums">
                {brand.chroma.toFixed(3)}
              </span>
            </div>
            <input
              className="gradient-slider"
              id="brand-chroma"
              max={BRAND_CHROMA_MAX}
              min={0}
              onChange={(event) => {
                setBrand({ ...brand, chroma: Number(event.target.value) })
              }}
              step={0.005}
              style={{ '--track-image': chromaTrackImage(brand.hue) } as CSSProperties}
              type="range"
              value={brand.chroma}
            />
            <p className="text-muted-foreground text-xs">
              调到 0 就是纯灰色界面, 这是正常效果, 不是坏了。
            </p>
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-foreground text-sm">预设色相</span>
            <div className="flex flex-wrap gap-2">
              {BRAND_PRESETS.map((preset) => (
                <button
                  className={`flex items-center gap-2 rounded-md border px-2.5 py-1.5 text-xs transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    Math.round(brand.hue) === preset.hue
                      ? 'border-ring bg-accent text-foreground'
                      : 'border-border text-muted-foreground hover:bg-accent hover:text-foreground'
                  }`}
                  key={preset.hue}
                  onClick={() => {
                    setBrand({ ...brand, hue: preset.hue })
                  }}
                  type="button"
                >
                  <span
                    aria-hidden="true"
                    className="size-3.5 rounded-full border border-border"
                    style={{
                      backgroundColor: `oklch(0.64 ${String(
                        brand.chroma === 0 ? BRAND_CHROMA_MAX * 0.75 : brand.chroma,
                      )} ${String(preset.hue)})`,
                    }}
                  />
                  {preset.label}
                </button>
              ))}
            </div>
            {/*
              彩度为 0 时预设点仍然显示各自的色相 (用一个演示彩度画色点), 否则八个点会全是同一块灰,
              用户没法知道点下去会变成什么 —— 而他多半是先拧了彩度到 0、又想换个色相回来。
            */}
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-foreground text-sm">效果预览</span>
            <Surface>
              <div className="flex flex-wrap items-center gap-3">
                <Button size="sm" variant="brand">
                  强调色按钮
                </Button>
                <Button size="sm" variant="default">
                  主按钮 (不受影响)
                </Button>
                <Tag tone="brand">强调徽标</Tag>
                <span className="rounded-md bg-brand-muted px-2 py-1 text-foreground text-xs">
                  当前导航项底色
                </span>
                <Currency amount={12_400} currency="credit" size="sm" />
              </div>
              <Meter className="mt-3" label="经验" max={100} tone="brand" value={62} />
            </Surface>
          </div>
        </div>
      </Panel>

      <Panel
        actions={
          <Button onClick={handlePreviewToast} size="sm" variant="outline">
            预览一条示例通知
          </Button>
        }
        title="免打扰"
      >
        <div className="flex flex-col gap-3">
          <Toggle
            checked={muteToasts}
            label="关闭成交 / 求婚 / 击杀结算的浮层提示"
            onChange={setMuteToasts}
          />
          <p className="text-muted-foreground text-xs">
            该功能尚未开放, 开关暂时不影响真实提示; 可用右上角的按钮预览开启后的效果。
          </p>
        </div>
      </Panel>

      <Panel title="语言">
        <div className="flex flex-col gap-3">
          <Dropdown
            className="max-w-64"
            onChange={setLanguage}
            options={LANGUAGE_OPTIONS}
            value={language}
          />
          <p className="text-muted-foreground text-xs">
            界面暂时只有简体中文。物品名称跟随你的 Minecraft 客户端语言设置, 不受这里影响。
          </p>
        </div>
      </Panel>
    </div>
  )
}
