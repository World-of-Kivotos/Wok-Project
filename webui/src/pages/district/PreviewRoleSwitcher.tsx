import type { ReactElement } from 'react'
import { Button, Surface, Tag } from '@/components/kit'
import { isMockActive } from '@/lib/bridge'
import { invalidateAll } from '@/lib/refresh'
import { DISTRICT_PREVIEW_OPTIONS, resetWorld, setDistrictPreviewPersona } from '@/mock'
import type { DistrictPreviewPersona } from '@/mock'
import type { DistrictRole } from '@/lib/types'
import { ROLE_LABEL } from './format'

/**
 * 预览身份切换器 —— 只在假数据模式下存在 (isMockActive 在生产构建里恒为 false, 同外壳顶栏的 OP 视图开关)。
 *
 * 真服没有也不该有这个东西: 身份永远是发请求的那个人, 由服务端判定。这里切的是 mock 世界里"以谁的名义看",
 * 页面随后照常经 district.state 取回服务端判定的身份 —— 所以切完显示的是"服务端判定: X", 而不是切换器
 * 自己宣称的身份。两者不一致时 (比如刚在管理员视角撤掉了 circuit_owl 的区务长, 再切"区务长"),
 * 以回执为准, 这正是接线后的真实行为。
 *
 * 选"管理员"会同时勾上外壳顶栏的 OP 视图, 选其余各项会把它关掉 (同一个 mock 身份位, 见 district-handlers)。
 * 切完不用在这里作废查询: 页面盯着预览身份签名, 一变就自己全量作废 (DistrictPage 的 useInvalidateOnPreviewIdentityChange)。
 */
export function PreviewRoleSwitcher({
  viewerRole,
  viewerName,
  switching,
}: {
  viewerRole: DistrictRole
  viewerName: string
  /** 切换后回执还没回来。 */
  switching: boolean
}): ReactElement | null {
  if (!isMockActive()) {
    return null
  }

  const active: DistrictPreviewPersona | null =
    viewerRole === 'admin'
      ? 'admin'
      : (DISTRICT_PREVIEW_OPTIONS.find(
          (option) => option.playerName !== null && option.playerName.toLowerCase() === viewerName.toLowerCase(),
        )?.persona ?? null)

  return (
    <Surface className="border-dashed">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="flex flex-col">
          <span className="font-medium text-foreground text-xs">预览身份</span>
          <span className="text-muted-foreground text-xs">仅假数据模式可见，真服按登录的人判定</span>
        </div>
        <div aria-label="预览身份" className="flex flex-wrap gap-1.5" role="group">
          {DISTRICT_PREVIEW_OPTIONS.map((option) => (
            <Button
              aria-pressed={active === option.persona}
              key={option.persona}
              onClick={() => {
                setDistrictPreviewPersona(option.persona)
              }}
              size="sm"
              variant={active === option.persona ? 'default' : 'outline'}
            >
              {option.label}
              <span className="font-normal text-xs opacity-64">{option.playerName ?? 'OP'}</span>
            </Button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground text-xs">服务端判定</span>
          <Tag size="sm" tone="neutral">
            {switching ? '切换中…' : ROLE_LABEL[viewerRole]}
          </Tag>
        </div>
        <Button
          className="ml-auto"
          onClick={() => {
            resetWorld()
            // 身份没变时页面不会自己作废 (它只盯身份, 见 DistrictPage), 重置后的名单要显式叫它重拉。
            invalidateAll()
          }}
          size="sm"
          variant="ghost"
        >
          重置假数据
        </Button>
      </div>
    </Surface>
  )
}
