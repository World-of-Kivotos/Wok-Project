import type { ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import {
  PixelButton,
  PixelCheckbox,
  PixelFrame,
  PixelSelect,
  PixelStepper,
  PixelTabs,
} from '../components/pixel'
import type { PixelFrameScale, PixelFrameTone, PixelSelectOption } from '../components/pixel'
import type { Theme } from '../lib/theme'
import { useTheme } from '../lib/theme'

/*
 * 设置: UI 偏好。
 *
 * 契约缺口 (清单 A9 player.prefs, BACKEND 未落地): planned.ts 定义了 player.prefs.get/set 且
 * mock/handlers.ts 已实现 (走 world.prefs 内存态), 但架构决策 (清单第七章"我直接定的"第 2 条) 明确
 * 拍板不做这条 action —— 服务端 MiningPlayerData 没有 UI 偏好字段, 长期方案是 Chromium localStorage,
 * player.prefs 只是留个位置以防日后真出现跨机器诉求。因此本页刻意不调 callMock('player.prefs.*'),
 * 全部四项偏好直接读写 localStorage —— 这意味着**换一台机器/清一次浏览器缓存, 偏好就不跟随**,
 * 页面上必须标出这一点, 不能让玩家以为这是账号级设置。
 *
 * 本页不发起任何远端/mock 请求, 因此没有可触发的加载态/错误态 —— 四项偏好要么已在 localStorage
 * 里、要么取内置默认值, 不存在"取不到"的中间状态。
 *
 * 已知限制 (首帧闪烁): --pixel-scale 的持久化应用挂在本页的 effect 里, 不像 lib/theme.ts 的
 * initTheme() 那样能在 main.tsx 渲染前预应用 —— 本任务不允许改 main.tsx/App.tsx。首次进入本页前,
 * 全局缩放会短暂落回 index.css 的默认值 2, 直到本组件挂载并把持久化值写回 --pixel-scale。
 * 若要做到零闪烁, 需要 hub agent 在入口补一个等效的 applyPersistedScale() 预调用。
 */

const SCALE_STORAGE_KEY = 'wok-prefs-scale'
const MUTE_STORAGE_KEY = 'wok-prefs-mute-toasts'
const LANGUAGE_STORAGE_KEY = 'wok-prefs-language'

const DEFAULT_SCALE: PixelFrameScale = 2
const MIN_SCALE = 1
const MAX_SCALE = 4

/** 界面文案目前全部硬编码简体中文, 唯一能真实生效的语言只有这一档。 */
const UI_LANGUAGE_VALUE = 'zh_cn'
const LANGUAGE_OPTIONS: readonly PixelSelectOption[] = [{ value: UI_LANGUAGE_VALUE, label: '简体中文' }]

function isValidScale(value: number): value is PixelFrameScale {
  return Number.isInteger(value) && value >= MIN_SCALE && value <= MAX_SCALE
}

function readStoredScale(): PixelFrameScale {
  const raw = localStorage.getItem(SCALE_STORAGE_KEY)
  const parsed = raw === null ? Number.NaN : Number.parseInt(raw, 10)
  return isValidScale(parsed) ? parsed : DEFAULT_SCALE
}

function readStoredMuteToasts(): boolean {
  return localStorage.getItem(MUTE_STORAGE_KEY) === 'true'
}

const THEME_TABS: readonly { id: Theme; label: string; icon: 'star' | 'lock' }[] = [
  { id: 'dark', label: '暗色', icon: 'lock' },
  { id: 'light', label: '亮色', icon: 'star' },
]

/** 通知预览的自动消失时长; 太短来不及看清, 太长又会挡住下面的控件。 */
const PREVIEW_DISMISS_MS = 4_000

type PreviewBanner = { tone: PixelFrameTone; message: string } | null

export function SettingsPage(): ReactElement {
  const { theme, toggle: toggleTheme } = useTheme()

  const [scale, setScale] = useState<PixelFrameScale>(readStoredScale)
  const [muteToasts, setMuteToasts] = useState<boolean>(readStoredMuteToasts)
  const [language, setLanguage] = useState<string>(UI_LANGUAGE_VALUE)
  const [preview, setPreview] = useState<PreviewBanner>(null)

  const previewTimeoutRef = useRef<number | null>(null)

  useEffect(() => {
    document.documentElement.style.setProperty('--pixel-scale', String(scale))
    localStorage.setItem(SCALE_STORAGE_KEY, String(scale))
  }, [scale])

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
        ? { tone: 'neutral', message: '已被免打扰设置拦截: 示例通知「你的挂单已成交, 到账 480 信用点」' }
        : { tone: 'info', message: '示例通知: 你的挂单已成交, 到账 480 信用点' },
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

  function handleScaleChange(next: number): void {
    if (isValidScale(next)) {
      setScale(next)
    }
  }

  return (
    <div className="flex flex-col gap-4 p-4">
      <PixelFrame variant="panel" tone="warning" className="w-full">
        <p className="p-3 text-1x text-fg">
          以下偏好仅保存在本机浏览器的 localStorage 里, 不随账号同步 —— 换一台电脑或清空浏览数据后,
          这里的设置会恢复默认值, 需要重新调整一次。
        </p>
      </PixelFrame>

      {preview === null ? null : (
        <PixelFrame variant="panel" tone={preview.tone} className="w-full">
          <div className="flex items-center justify-between gap-4 p-3">
            <p className="text-1x text-fg">{preview.message}</p>
            <PixelButton size="sm" tone="neutral" icon="close" label="关闭" onClick={() => { setPreview(null) }} />
          </div>
        </PixelFrame>
      )}

      <PixelFrame variant="panel" className="w-full">
        <div className="flex flex-col gap-3 p-4">
          <h2 className="text-1x text-fg">主题</h2>
          <PixelTabs
            tabs={THEME_TABS.map((tab) => ({ id: tab.id, label: tab.label, icon: tab.icon }))}
            activeId={theme}
            onChange={handleThemeChange}
          />
        </div>
      </PixelFrame>

      <PixelFrame variant="panel" className="w-full">
        <div className="flex flex-col gap-3 p-4">
          <h2 className="text-1x text-fg">界面缩放</h2>
          <p className="text-1x text-muted">
            控制全部 9-slice 边框与角标的整数放大倍率 (--pixel-scale); 像素规格禁非整数倍缩放, 因此只能取
            {String(MIN_SCALE)} 到 {String(MAX_SCALE)} 之间的整数档。
          </p>
          <PixelStepper value={scale} onChange={handleScaleChange} min={MIN_SCALE} max={MAX_SCALE} step={1} />
        </div>
      </PixelFrame>

      <PixelFrame variant="panel" className="w-full">
        <div className="flex flex-col gap-3 p-4">
          <h2 className="text-1x text-fg">免打扰</h2>
          <PixelCheckbox
            checked={muteToasts}
            onChange={setMuteToasts}
            label="关闭成交 / 求婚 / 击杀结算的浮层提示"
          />
          <p className="text-1x text-muted">
            全局提示队列的挂载点尚未接线 (L2 组件表标注"队列与挂载点本批不定"), 该开关暂不影响真实提示;
            下方按钮可预览开关生效后的效果。
          </p>
          <div>
            <PixelButton tone="neutral" onClick={handlePreviewToast}>
              预览一条示例通知
            </PixelButton>
          </div>
        </div>
      </PixelFrame>

      <PixelFrame variant="panel" className="w-full">
        <div className="flex flex-col gap-3 p-4">
          <h2 className="text-1x text-fg">语言</h2>
          <PixelSelect value={language} options={LANGUAGE_OPTIONS} onChange={setLanguage} />
          <p className="text-1x text-muted">
            界面文案当前全部硬编码简体中文, 暂无其它语言可选。物品/翻译键名称走玩家 Minecraft
            客户端自身的语言设置解析 (client.i18n), 与此处选择无关, 不受本设置控制。
          </p>
        </div>
      </PixelFrame>
    </div>
  )
}
