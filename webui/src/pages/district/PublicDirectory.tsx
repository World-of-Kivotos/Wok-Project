import { LandPlotIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { Button, EmptyBlock, Panel, Stat, Surface, Tag } from '@/components/kit'
import { useMockAction } from '@/mock'
import type { DistrictSummary, PlotFriendship } from '@/lib/types'
import { formatArea } from './format'

/**
 * 外人视角: 你还不是任何自管区的住户 + 各学院自管区的公开信息 (有哪些、区务长是谁、划了几块地、外人在公共区域
 * 能做什么、怎么申请)。谁家在哪块地不公开 (district.plots 对外人直接拒绝), 这里只写块数; 唯一的例外是户主把我
 * 加成朋友的那几块 —— 那是我自己的数据 (district.state 的 friendOf)。
 *
 * 申请入口刻意不做成按钮: 入住 = 被加入学院, 而学院名单由区务长手工维护, 平板上没有"申请"这条流程可走。
 * 画一个按钮只会让人以为点了就有人处理。
 */
export function PublicDirectory({
  districts,
  friendOf,
}: {
  districts: readonly DistrictSummary[]
  /** 户主把我加成朋友的地块 (跨全部自管区, 我自己的数据)。朋友可以是任何玩家, 外人也可能是某块地的朋友。 */
  friendOf: readonly PlotFriendship[]
}): ReactElement {
  return (
    <div className="flex flex-col gap-4">
      <Panel>
        <EmptyBlock
          hint="加入某个学院后，就能在这个学院的自管区里建造，也可以在该区开放购买时直接买一块空置地块（先到先得）。想入住，请在游戏里私聊对应学院的区务长。"
          icon={<LandPlotIcon aria-hidden="true" />}
          title="你还不是任何自管区的住户"
        />
        <p className="mt-3 border-t pt-3 text-muted-foreground text-xs">
          {'不想加入学院也可以：自管区外，你可以自己用 Flan 圈个人领地、自己管权限（与本页无关）。自管区内和外围 8 格内不能圈个人领地，外围 8 格内也不能放机械动力的机器（装饰方块可以）。'}
        </p>
      </Panel>

      <Panel description="公开信息，所有人都能看；主城 DU 由管理员直接管理，不设自管区" title="各学院自管区">
        {districts.length === 0 ? (
          <p className="text-muted-foreground text-sm">服务器上还没有自管区。</p>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {districts.map((district) => (
              <Surface key={district.districtId}>
                <div className="flex flex-col gap-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex flex-col">
                      <h3 className="font-medium text-foreground text-sm">{district.academyFullName}</h3>
                      <span className="text-muted-foreground text-xs">{district.displayName}</span>
                    </div>
                    <Tag size="sm" tone="neutral">
                      {`${String(district.residentCount)} 人`}
                    </Tag>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Stat
                      label="区务长"
                      layout="inline"
                      value={
                        district.wardenName === null ? (
                          <span className="text-muted-foreground">暂无</span>
                        ) : (
                          district.wardenName
                        )
                      }
                    />
                    <Stat label="面积" layout="inline" value={formatArea(district.area)} />
                    <Stat
                      label="地块"
                      layout="inline"
                      value={
                        district.plotCount === 0
                          ? '还没划地块'
                          : `共 ${String(district.plotCount)} 块地，${
                              district.vacantPlotCount === 0
                                ? '没有空置'
                                : `${String(district.vacantPlotCount)} 块空置`
                            }`
                      }
                    />
                  </div>
                  <OutsiderAllowance districtId={district.districtId} />
                  <FriendPlotsNote
                    friendships={friendOf.filter((friendship) => friendship.districtId === district.districtId)}
                  />
                  <p className="text-muted-foreground text-xs">
                    {district.wardenName === null
                      ? '想入住：这个学院暂时没有区务长，请联系管理员。'
                      : `想入住：在游戏里私聊区务长 ${district.wardenName}，请 TA 把你加入${district.academyName}。`}
                  </p>
                </div>
              </Surface>
            ))}
          </div>
        )}
      </Panel>
    </div>
  )
}

/**
 * 外人最先想知道"能不能进门", 其次是睡床、交易这类生活项, 再是箱子机器、捡东西。只是展示顺序, 不参与任何判定;
 * 不认识的分组 (服务端日后加的) 按回执原顺序排在后面。
 */
const OUTSIDER_GROUP_ORDER: readonly string[] = ['doors', 'living', 'storage', 'items']

function outsiderGroupRank(groupId: string): number {
  const index = OUTSIDER_GROUP_ORDER.indexOf(groupId)
  return index === -1 ? OUTSIDER_GROUP_ORDER.length : index
}

/**
 * "外人可以: 门与红石: 开关门、按按钮和拉杆…" —— 按该区外人那一列的开关生成 (district.permissions 对外人只回
 * 外人列与全区列), 按分组一行一组, 不铺成一长串。默认外人能做、本区却关掉的另起一行写明: 门推不开的外人
 * 要的正是这一句, 光看"能做什么"得拿几张卡片来回对比才发现。
 * 每张卡片各取各的: 一个区读失败只影响那一张, 不拖累整页。
 */
function OutsiderAllowance({ districtId }: { districtId: string }): ReactElement {
  const query = useMockAction('district.permissions', { districtId })
  if (query.status === 'error') {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-destructive text-xs">没读到外人在这里能做什么：{query.error.message}</p>
        <Button onClick={query.reload} size="xs" variant="outline">
          重试
        </Button>
      </div>
    )
  }
  if (query.data === null) {
    return <p className="text-muted-foreground text-xs">正在读取外人在这里能做什么…</p>
  }
  const memberGroups = query.data.groups.filter((group) => group.scope === 'member')
  const allowedGroups = [...memberGroups]
    .sort((left, right) => outsiderGroupRank(left.groupId) - outsiderGroupRank(right.groupId))
    .map((group) => ({
      groupId: group.groupId,
      label: group.label,
      items: group.items.filter((item) => item.current.outsider === true).map((item) => item.label),
    }))
    .filter((group) => group.items.length > 0)
  const closedHere = memberGroups
    .flatMap((group) => group.items)
    .filter((item) => item.defaults.outsider === true && item.current.outsider === false)
    .map((item) => item.label)
  return (
    <div className="flex flex-col gap-0.5 text-xs">
      <span className="text-muted-foreground">外人在公共区域可以</span>
      {allowedGroups.length === 0 ? (
        <p className="text-foreground">什么都不能碰，只能路过</p>
      ) : (
        <ul className="flex flex-col gap-0.5">
          {allowedGroups.map((group) => (
            <li key={group.groupId}>
              <span className="text-muted-foreground">{group.label}：</span>
              <span className="text-foreground">{group.items.join('、')}</span>
            </li>
          ))}
        </ul>
      )}
      {closedHere.length === 0 ? null : (
        <p className="text-muted-foreground">公共区域关掉了：{closedHere.join('、')}</p>
      )}
      <p className="text-muted-foreground">各户地块另算，由户主自己设置。</p>
    </div>
  )
}

/**
 * "你是 格赫娜-06 (户主 BlazeBunny) 的朋友"。外人被户主加成朋友后, 平板上只有这里告诉 TA 在那块地里按朋友算 ——
 * 不写的话, TA 只看得到"外人在公共区域可以…", 会以为那块地里也只能照外人的来。
 */
function FriendPlotsNote({ friendships }: { friendships: readonly PlotFriendship[] }): ReactElement | null {
  if (friendships.length === 0) {
    return null
  }
  return (
    <Surface tone="brand">
      <ul className="flex flex-col gap-1 text-xs">
        {friendships.map((friendship) =>
          friendship.suspended ? (
            <li className="text-foreground" key={friendship.plotId}>
              {`你在 ${friendship.code}（户主 ${friendship.ownerName}）的朋友身份已暂停，在那块地里按外人算；户主可以恢复。`}
            </li>
          ) : (
            <li className="text-foreground" key={friendship.plotId}>
              {`你是 ${friendship.code}（户主 ${friendship.ownerName}）的朋友，在那块地里按户主给朋友的设置算。`}
              {friendship.syncStatus === 'synced' ? null : (
                <span className="text-muted-foreground">
                  {friendship.syncStatus === 'pending' ? '你第一次登录后生效。' : '服务器还没把你写进领地。'}
                </span>
              )}
            </li>
          ),
        )}
      </ul>
    </Surface>
  )
}
