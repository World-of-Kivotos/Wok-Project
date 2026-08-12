import { SearchIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  CONTROL_SIZES,
  Currency,
  DataTable,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  Hint,
  ItemSlot,
  ItemSlotGrid,
  LoadingBlock,
  Meter,
  NumberInput,
  Panel,
  Stat,
  Surface,
  TabBar,
  Tag,
  TextInput,
  Toggle,
  TONES,
  type Tone,
} from '@/components/kit'

/**
 * 组件与配色预览页。
 *
 * 用途有两个, 缺一不可:
 *   1. 设计评审 —— 一屏看全 kit 的所有档位, 判断中性灰阶与强调色的关系是否成立;
 *   2. 换皮回归 —— 像素风将来重启时 (规格标 DEFERRED), 改完 kit 内部实现后先看这一页,
 *      任何一个档位画崩了都在这里当场显形, 不必逐个业务页翻。
 *
 * 因此本页必须**穷举**而不是挑几个好看的摆出来: 档位表直接遍历 TONES 与 CONTROL_SIZES,
 * 加一档就自动多一列, 不需要有人记得回来补。
 */

interface DemoRow {
  id: string
  item: string
  price: number
  seller: string
}

const DEMO_ROWS: readonly DemoRow[] = [
  { id: 'a', item: '钻石镐', price: 12_400, seller: 'Shinoyuki' },
  { id: 'b', item: '下界合金锭', price: 86_000, seller: 'Kivotos' },
  { id: 'c', item: '烈酒·冬麦', price: 940, seller: 'Aris' },
]

const DEMO_SLOTS = [
  { count: 64, itemId: 'minecraft:diamond', label: '钻石' },
  { count: 1, itemId: 'minecraft:iron_pickaxe', label: '铁镐' },
  {},
  { count: 12, disabled: true, itemId: 'minecraft:redstone', label: '红石 (锁定)' },
  {},
  {},
]

function Section({ title, children }: { title: string; children: ReactElement }): ReactElement {
  return (
    <Panel title={title}>
      <div className="flex flex-col gap-4">{children}</div>
    </Panel>
  )
}

export function ComponentsPage(): ReactElement {
  const [text, setText] = useState('')
  const [amount, setAmount] = useState(8)
  const [choice, setChoice] = useState<'all' | 'tools' | 'food'>('all')
  const [checked, setChecked] = useState(true)
  const [tab, setTab] = useState('one')
  const [selectedSlot, setSelectedSlot] = useState<number | undefined>(0)
  const [confirmOpen, setConfirmOpen] = useState(false)

  return (
    <div className="flex flex-col gap-4">
      <Section title="中性灰阶">
        <>
          <p className="text-muted-foreground text-sm">
            全部中性色彩度为零。灰阶层次是唯一的层级手段, 强调色只出现在下一节那几个小面积位置。
          </p>
          <div className="grid grid-cols-6 gap-2">
            {[
              { name: 'background', swatch: 'bg-background' },
              { name: 'card', swatch: 'bg-card' },
              { name: 'popover', swatch: 'bg-popover' },
              { name: 'muted', swatch: 'bg-muted' },
              { name: 'accent', swatch: 'bg-accent' },
              { name: 'secondary', swatch: 'bg-secondary' },
              { name: 'border', swatch: 'bg-border' },
              { name: 'input', swatch: 'bg-input' },
              { name: 'muted-fg', swatch: 'bg-muted-foreground' },
              { name: 'foreground', swatch: 'bg-foreground' },
              { name: 'primary', swatch: 'bg-primary' },
              { name: 'brand', swatch: 'bg-brand' },
            ].map((entry) => (
              <div className="flex flex-col gap-1" key={entry.name}>
                <div className={`h-10 rounded-md border ${entry.swatch}`} />
                <span className="text-muted-foreground text-xs">{entry.name}</span>
              </div>
            ))}
          </div>
        </>
      </Section>

      <Section title="语义档 (Tone) 穷举">
        <>
          <div className="flex flex-wrap gap-2">
            {TONES.map((tone: Tone) => (
              <Tag key={tone} tone={tone}>
                {tone}
              </Tag>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-2">
            {TONES.map((tone: Tone) => (
              <Surface key={tone} tone={tone}>
                <span className="text-sm">Surface tone={tone}</span>
              </Surface>
            ))}
          </div>
          <div className="flex flex-col gap-3">
            {TONES.map((tone: Tone, index) => (
              <Meter key={tone} label={`Meter tone=${tone}`} max={100} tone={tone} value={(index + 1) * 15} />
            ))}
          </div>
        </>
      </Section>

      <Section title="按钮">
        <>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="default">默认</Button>
            <Button variant="brand">强调色</Button>
            <Button variant="secondary">次级</Button>
            <Button variant="outline">描边</Button>
            <Button variant="ghost">幽灵</Button>
            <Button variant="destructive">危险</Button>
            <Button variant="destructive-outline">危险描边</Button>
            <Button variant="link">链接</Button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="xs">xs</Button>
            <Button size="sm">sm</Button>
            <Button size="default">default</Button>
            <Button size="lg">lg</Button>
            <Button size="xl">xl</Button>
            <Button loading>加载中</Button>
            <Button disabled>禁用</Button>
          </div>
        </>
      </Section>

      <Section title="表单控件">
        <>
          <div className="grid grid-cols-3 gap-4">
            {CONTROL_SIZES.map((size) => (
              <div className="flex flex-col gap-2" key={size}>
                <span className="text-muted-foreground text-xs">size={size}</span>
                <TextInput onChange={setText} placeholder="搜索物品" size={size} value={text} />
                <NumberInput max={64} min={1} onChange={setAmount} size={size} value={amount} />
                <Dropdown
                  onChange={setChoice}
                  options={[
                    { label: '全部分类', value: 'all' },
                    { label: '工具', value: 'tools' },
                    { label: '食物', value: 'food' },
                  ]}
                  size={size}
                  value={choice}
                />
                <Toggle checked={checked} label="只看有货" onChange={setChecked} size={size} />
              </div>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <TextInput invalid onChange={setText} placeholder="校验未通过" value={text} />
            <TextInput disabled onChange={setText} placeholder="禁用态" value="" />
            <Hint content="悬停提示的内容在这里">
              <Button size="sm" variant="outline">
                <SearchIcon />
                悬停我
              </Button>
            </Hint>
          </div>
        </>
      </Section>

      <Section title="页签">
        <>
          <TabBar
            activeId={tab}
            onChange={setTab}
            tabs={[
              { id: 'one', label: '浏览' },
              { id: 'two', label: '挂单' },
              { id: 'three', label: '我的' },
              { disabled: true, id: 'four', label: '禁用' },
            ]}
          />
          <TabBar
            activeId={tab}
            onChange={setTab}
            tabs={[
              { id: 'one', label: '浏览' },
              { id: 'two', label: '挂单' },
              { id: 'three', label: '我的' },
            ]}
            variant="underline"
          />
        </>
      </Section>

      <Section title="货币与指标">
        <>
          <div className="flex flex-wrap items-center gap-4">
            {CONTROL_SIZES.map((size) => (
              <Currency amount={1_234_567} currency="credit" key={size} size={size} />
            ))}
            <Currency amount={840} currency="azure" />
            <Currency amount={2400} currency="credit" signed />
            <Currency amount={-320} currency="credit" signed />
          </div>
          <div className="grid grid-cols-4 gap-4">
            <Stat hint="距下一级 800" label="矿工等级" value="12" />
            <Stat label="今日成交" value={<Currency amount={48_200} currency="credit" />} />
            <Stat label="在线人数" layout="inline" value="34 / 80" />
            <Stat label="手续费率" layout="inline" value="4%" />
          </div>
        </>
      </Section>

      <Section title="物品格">
        <>
          <div className="flex items-end gap-4">
            <ItemSlot count={64} itemId="minecraft:diamond" label="钻石" scale={1} />
            <ItemSlot count={64} itemId="minecraft:diamond" label="钻石" scale={2} />
            <ItemSlot count={64} itemId="minecraft:diamond" label="钻石" scale={3} />
            <ItemSlot label="空格子" />
            <ItemSlot disabled itemId="minecraft:redstone" label="锁定" />
            <ItemSlot itemId="miningdim:this_does_not_exist" label="无贴图占位块" />
          </div>
          <ItemSlotGrid
            columns={6}
            label="演示背包"
            onSelect={setSelectedSlot}
            selectedSlot={selectedSlot}
            slots={DEMO_SLOTS}
          />
        </>
      </Section>

      <Section title="数据表">
        <DataTable
          columns={[
            { header: '物品', key: 'item', render: (row) => row.item, sortValue: (row) => row.item },
            {
              header: '价格',
              key: 'price',
              numeric: true,
              render: (row) => <Currency amount={row.price} currency="credit" size="sm" />,
              sortValue: (row) => row.price,
            },
            { header: '卖家', key: 'seller', render: (row) => row.seller },
          ]}
          rowKey={(row) => row.id}
          rows={DEMO_ROWS}
        />
      </Section>

      <Section title="状态与回执">
        <>
          <LoadingBlock label="正在读取挂单" />
          <ErrorBlock code="NOT_WIRED" message="服务端尚未接线" onRetry={() => undefined} />
          <EmptyBlock hint="换个关键词试试" title="没有匹配的挂单" />
          <FeedbackAlert message="已挂单, 手续费 496 信用点。" tone="success" />
          <FeedbackAlert message="余额不足, 还差 1,200 信用点。" tone="danger" />
          <FeedbackAlert message="该挂单已被其他玩家买走。" title="操作未生效" tone="warning" />
          <FeedbackAlert message="矿洞将在 12 分钟后重置。" tone="info" />
          <div>
            <Button
              onClick={() => {
                setConfirmOpen(true)
              }}
              variant="destructive"
            >
              打开二次确认
            </Button>
          </div>
          <ConfirmDangerDialog
            confirmLabel="确认修改"
            confirmWord="Shinoyuki"
            message="将 Shinoyuki 的信用点由 1,200 改为 99,999。此操作直接改动经济数据, 无法撤销。"
            onConfirm={() => {
              setConfirmOpen(false)
            }}
            onOpenChange={setConfirmOpen}
            open={confirmOpen}
            title="修改玩家余额"
          />
        </>
      </Section>
    </div>
  )
}
