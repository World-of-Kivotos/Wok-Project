import type { CSSProperties, ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Currency,
  Dropdown,
  ErrorBlock,
  FeedbackAlert,
  type FeedbackTone,
  LoadingBlock,
  Meter,
  Panel,
  Surface,
  TabBar,
  Tag,
  Toggle,
} from '@/components/kit'
import { BRAND_CHROMA_MAX, BRAND_PRESETS, DEFAULT_BRAND, useBrand } from '@/lib/brand'
import { callErrorText } from '@/lib/errorText'
import type { Theme } from '@/lib/theme'
import { useTheme } from '@/lib/theme'
import type { PlayerPrefs } from '@/lib/types'
import { callMock, useMockAction } from '@/mock'

/*
 * 设置: UI 偏好。
 *
 * 偏好落在**账号**上 (player.prefs.get/set -> IMiningPlayerData -> player.dat), 不是这台电脑上 ——
 * 换机器、清浏览器缓存都不丢。本页因此是全前端唯一同时持有两份状态的地方, 那套配合必须写清楚:
 *
 *   1. 启动时 theme.ts/brand.ts 的 initTheme/initBrand 跑在 React 渲染之前 (防首帧闪色), 那一刻远端
 *      偏好还没到, 只能读 localStorage;
 *   2. player.prefs.get 到达后, 以服务端的四项为准覆盖本地 (主题/色相当场生效), 并回写 localStorage ——
 *      回写是给下一次启动的第 1 步用的, 让首帧就是账号里的那一档;
 *   3. 用户改动时先本地立即生效 (拖滑块要跟手), 再 player.prefs.set 落账号; 被拒时把服务端的实际落盘值
 *      重新对齐回来。
 *
 * 本批刻意不动 theme.ts / brand.ts 的对外 API: 那两个模块跑在全局初始化路径上, 让它们感知远端偏好等于
 * 把一次网络往返塞进首帧渲染前。对齐只发生在本页。
 *
 * 只有四项跟随账号 (muteToasts / language / theme / brandHue)。**强调色彩度 (brand.chroma) 不在其中**,
 * 它仍只存在这台电脑上 —— 页面上必须如实说明, 不能笼统写成"偏好已跟随账号"。
 *
 * 原"界面缩放"一节已随像素风一并撤除: 它调的是 --pixel-scale (9-slice 边框的整数放大倍率),
 * 而 9-slice 那套已整体封存到 webui/_pixel-archive/。留着它就是一个拖了没有任何效果的滑块。
 */

/** 空入参; 提到模块级只是让"本页发几种请求"一眼可数。 */
const EMPTY_PAYLOAD: Record<string, never> = {}

/** 界面文案目前全部硬编码简体中文, 唯一能真实生效的语言只有这一档。 */
const UI_LANGUAGE_VALUE = 'zh_cn'
const LANGUAGE_OPTIONS = [{ label: '简体中文', value: UI_LANGUAGE_VALUE }] as const

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

/** 落账号的四项写完的等待时长。存在的唯一理由是色相滑块: 不合并的话拖一次会打出上百发 prefs.set。 */
const SAVE_DEBOUNCE_MS = 400

/**
 * 账号偏好先读到手再渲染表单。
 *
 * 分成两层组件而不是在一个组件里判空: 表单的初值 (免打扰/语言) 必须来自服务端那一份, 而 useState 的
 * 初值只在首次挂载时取一次 —— 若在同一个组件里"先渲染空表单、拿到回执再 setState 同步", 就要额外写一条
 * 同步 effect, 而那条 effect 与用户正在拖的滑块必然打架。等数据到齐再挂载表单, 这类竞态根本不存在。
 */
export function SettingsPage(): ReactElement {
  const prefs = useMockAction('player.prefs.get', EMPTY_PAYLOAD)

  if (prefs.status === 'loading') {
    return <LoadingBlock label="读取账号偏好" />
  }
  if (prefs.status === 'error') {
    return (
      <ErrorBlock
        message={callErrorText(prefs.error)}
        code="player.prefs.get"
        onRetry={prefs.reload}
      />
    )
  }
  return <SettingsForm stored={prefs.data} />
}

interface SettingsFormProps {
  /** 服务端当前落盘的四项。挂载即以它为准覆盖本地 localStorage 那一份。 */
  stored: PlayerPrefs
}

type SaveState = { status: 'idle' } | { status: 'saving' } | { status: 'failed'; message: string }

function SettingsForm({ stored }: SettingsFormProps): ReactElement {
  const { theme, toggle: toggleTheme } = useTheme()
  const { brand, setBrand, reset: resetBrand } = useBrand()

  const [muteToasts, setMuteToasts] = useState<boolean>(stored.muteToasts)
  const [language, setLanguage] = useState<string>(stored.language)
  const [saveState, setSaveState] = useState<SaveState>({ status: 'idle' })
  const [preview, setPreviewValue] = useState<PreviewBanner>(null)
  /*
   * 回执的实例序号, 只用来当 React key。与 AdminPage / CasePage / MarriagePage 三处同一处理。
   *
   * autoDismissMs={0} 只关掉了组件自带的倒计时, **没有**关掉手动点关闭那条退场路径: 点了 X 之后
   * 组件仍会进 leaving 态并排一个约 140ms 的退场定时器。若在那 140ms 内再点一次"预览"且免打扰开关
   * 没变 (文案字节完全相同), 组件从 props 上看不出这是新的一条, 旧定时器照常把它关掉 ——
   * 玩家点了预览却什么都没出现。序号一变 React 就重建实例, 旧定时器随卸载一起清掉。
   */
  const previewSeqRef = useRef(0)
  const setPreview = (next: PreviewBanner): void => {
    previewSeqRef.current += 1
    setPreviewValue(next)
  }

  const previewTimeoutRef = useRef<number | null>(null)
  const saveTimeoutRef = useRef<number | null>(null)
  /** 防抖窗口里等着发的那一份完整偏好; 离场补发要靠它 (见下面的 cleanup)。发出后置空。 */
  const pendingPrefsRef = useRef<PlayerPrefs | null>(null)
  /*
   * 主题与强调色的最新值。写请求跨越了防抖 400ms 加一次往返, 回执到达时函数闭包里的那两份可能已经过期
   * (玩家在这期间又改了一次)。两处都要拿最新值:
   *   - 对齐色相时要连彩度一起写回去 (setBrand 收的是整个对象), 用过期的那份会把刚拧好的彩度弹回旧值;
   *   - 对齐主题靠"当前档与目标档比对"决定要不要翻转, 比错了就是翻反。
   */
  const brandRef = useRef(brand)
  const themeRef = useRef(theme)
  useEffect(() => {
    brandRef.current = brand
    themeRef.current = theme
  }, [brand, theme])

  /*
   * 挂载时以账号那份为准覆盖本地: 主题与色相在 React 渲染前就已按 localStorage 生效 (initTheme/initBrand
   * 防首帧闪色), 这里是那两个模块与账号偏好唯一的对齐点。useTheme/useBrand 各自会把新值写回 localStorage,
   * 于是下一次启动的首帧直接就是账号里的那一档, 不再闪一下再改。
   *
   * 依赖表只放 stored 的两个字段: theme/brand 是被写入的目标, 放进依赖表会让"用户刚改完 -> effect 又按
   * 账号值改回去"每次都发生一遍。stored 在本组件生命周期内不变 (变了就是重新挂载), 故本 effect 恰好只跑一次。
   */
  useEffect(() => {
    if (stored.theme !== theme) {
      toggleTheme()
    }
    if (stored.brandHue !== Math.round(brand.hue)) {
      setBrand({ ...brand, hue: stored.brandHue })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 见上: theme/brand 是写入目标而非触发源
  }, [stored.theme, stored.brandHue])

  useEffect(
    () => () => {
      // 纯 UI 计时器, 没有要保住的副作用, 取消即可。
      if (previewTimeoutRef.current !== null) {
        window.clearTimeout(previewTimeoutRef.current)
      }
      /*
       * 防抖窗口里离场要**补发**而不是丢弃。改完主题立刻点导航离开是极常见的操作序列, 而窗口有 400ms:
       * 单纯取消等于"本地已生效、账号没存上" —— 正是本页要根治的那个症状被原地复现, 且这台机器上一切正常,
       * 换台机器才发现设置没跟过来。
       *
       * 直接 callMock 而不走 savePrefs: 组件已在卸载, savePrefs 里的 setSaveState 全是 no-op。请求本身
       * 不依赖组件存活, 照样能走完。
       */
      if (saveTimeoutRef.current !== null) {
        window.clearTimeout(saveTimeoutRef.current)
        const pending = pendingPrefsRef.current
        pendingPrefsRef.current = null
        if (pending !== null) {
          callMock('player.prefs.set', pending).catch((thrown: unknown) => {
            // 卸载后无处呈现失败, 但不能静默: 下次进设置页会以账号那份为准对齐, 玩家只会看到"我改的没生效"
            // 而不知道为什么, 控制台这条是唯一线索。
            console.warn('[settings] 离场补发 player.prefs.set 失败', thrown)
          })
        }
      }
    },
    [],
  )

  /**
   * 把一份完整偏好落到账号。
   *
   * 整份提交而不是改哪项发哪项: 契约就是整份覆盖 (部分更新会把"清空某项"与"不动某项"混在一起),
   * 而本页本来就持有完整的四项, 组一份是零成本。
   *
   * 失败不回滚本地: 玩家刚拧的色相被服务端一句拒绝弹回去, 比"改动生效了但没存上"更让人摸不着头脑。
   * 这里只把失败明说出来 (下面那条 danger 横幅), 由玩家决定是改回去还是重试。
   */
  function savePrefs(next: PlayerPrefs): void {
    setSaveState({ status: 'saving' })
    callMock('player.prefs.set', next)
      .then((persisted) => {
        setSaveState({ status: 'idle' })
        /*
         * 回执是**落盘后**的值。与提交值不同即说明服务端收窄了取值域 (或钳了某一项), 此时以服务端为准
         * 当场改回来 —— 让"你以为设成了 A, 实际存的是 B"这种事在界面上立刻可见, 而不是下次开平板才发现。
         */
        if (persisted.theme !== next.theme) {
          setTheme(persisted.theme)
        }
        if (persisted.brandHue !== next.brandHue) {
          setBrandHue(persisted.brandHue)
        }
        if (persisted.muteToasts !== next.muteToasts) {
          setMuteToasts(persisted.muteToasts)
        }
        if (persisted.language !== next.language) {
          setLanguage(persisted.language)
        }
      })
      .catch((thrown: unknown) => {
        const error = thrown instanceof Error ? thrown : new Error(String(thrown))
        setSaveState({ status: 'failed', message: callErrorText(error) })
      })
  }

  /**
   * 防抖: 色相滑块一次拖动会触发上百次 onChange, 不合并就是上百发写请求。
   *
   * 刻意不用 useCallback 包 savePrefs/scheduleSave: 它们要读当前的 theme/brand, 一旦被 [] 记住,
   * 拿到的就是首帧的那份闭包 —— 症状是"改完主题再拖色相, 主题被写回旧值"。这两个函数不进任何依赖数组,
   * 每次渲染重建的开销为零。
   */
  function scheduleSave(next: PlayerPrefs): void {
    if (saveTimeoutRef.current !== null) {
      window.clearTimeout(saveTimeoutRef.current)
    }
    pendingPrefsRef.current = next
    saveTimeoutRef.current = window.setTimeout(() => {
      saveTimeoutRef.current = null
      pendingPrefsRef.current = null
      savePrefs(next)
    }, SAVE_DEBOUNCE_MS)
  }

  /** 当前四项的快照。每次提交都从它出发, 免得四个控件各自拼一份、漏掉刚改的那个。 */
  function currentPrefs(): PlayerPrefs {
    // brand.hue 允许是小数 (localStorage 里的历史值), 而契约要求 int, 故这里取整后再提交。
    return { muteToasts, language, theme, brandHue: Math.round(brand.hue) }
  }

  function setTheme(next: Theme): void {
    if (next !== themeRef.current) {
      toggleTheme()
    }
  }

  function setBrandHue(next: number): void {
    setBrand({ ...brandRef.current, hue: next })
  }

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
    if ((id !== 'dark' && id !== 'light') || id === theme) {
      return
    }
    toggleTheme()
    scheduleSave({ ...currentPrefs(), theme: id })
  }

  function handleMuteChange(next: boolean): void {
    setMuteToasts(next)
    scheduleSave({ ...currentPrefs(), muteToasts: next })
  }

  function handleLanguageChange(next: string): void {
    setLanguage(next)
    scheduleSave({ ...currentPrefs(), language: next })
  }

  function handleHueChange(next: number): void {
    setBrandHue(next)
    scheduleSave({ ...currentPrefs(), brandHue: Math.round(next) })
  }

  /** 恢复默认会同时改色相与彩度; 只有色相跟随账号, 故落账号的也只有它。 */
  function handleResetBrand(): void {
    resetBrand()
    scheduleSave({ ...currentPrefs(), brandHue: DEFAULT_BRAND.hue })
  }

  return (
    <div className="flex flex-col gap-4">
      <Surface>
        <p className="text-foreground text-sm">
          主题、强调色的色相、语言、免打扰跟随你的账号 —— 换一台电脑登录同一个账号, 这四项还在。
          <br />
          强调色的彩度 (颜色浓淡) 只保存在这台电脑上, 换机器会回到默认值。
        </p>
        {saveState.status === 'saving' ? (
          <p className="mt-1 text-muted-foreground text-xs">正在保存到账号…</p>
        ) : null}
      </Surface>

      {saveState.status === 'failed' ? (
        <Surface tone="danger">
          <p className="text-foreground text-sm">
            改动没能存进账号: {saveState.message}
            <br />
            当前界面已按你的选择变了, 但换台电脑不会跟随; 再改一次即可重试。
          </p>
        </Surface>
      ) : null}

      {/*
        autoDismissMs={0} 关掉组件自带的倒计时, 由本页 previewTimeoutRef 那套接管。
        差别在重复触发: 组件的计时只在文案变化时重起, 而连点两次"预览"多半得到同一句文案 ——
        那样第二次点下去看着像没反应, 且横幅按第一次的时间线消失。本页的计时器每次点击都重置, 是对的。
      */}
      {preview === null ? null : (
        <FeedbackAlert
          autoDismissMs={0}
          key={previewSeqRef.current}
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
          <Button onClick={handleResetBrand} size="sm" variant="outline">
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
                handleHueChange(Number(event.target.value))
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
                    handleHueChange(preset.hue)
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
            onChange={handleMuteChange}
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
            onChange={handleLanguageChange}
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
