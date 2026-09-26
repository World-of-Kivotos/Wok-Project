# WOK-全服经验模块

模块键：`wok-experience`

公开入口：`com.miningdim.progression.ExperienceModule`

公开服务：`com.miningdim.progression.IExperienceService`

## 1. 全服定位

经验是 WOK 服务器基础能力，不属于某个职业或玩法。农夫收获、矿工挖矿、厨师制作、精英战斗、任务、活动和未来赛季系统都应从同一个服务提交经验。

统一入口采用两个稳定标识：

- **轨道 ID**：经验进入哪套进度，例如 `miningdim:job/farmer`。
- **来源 ID**：经验为什么产生，例如 `miningdim:farmer/harvest`。

模块只提交原始经验和来源；经验模块负责找到轨道处理器。具体轨道负责曲线、每日衰减、持久化、等级派生和同步，业务模块不得直接修改别人的经验数据。

## 2. 现有兼容接入

当前八个职业全部注册为全服经验轨道：

- `miningdim:job/miner`
- `miningdim:job/farmer`
- `miningdim:job/engineer`
- `miningdim:job/tarot`
- `miningdim:job/chef`
- `miningdim:job/agent`
- `miningdim:job/munitions`
- `miningdim:job/brewer`

这些轨道仍使用原有 `EnumMap<JobId, JobProgress>`、Capability、NBT 字段、等级和衰减曲线，因此旧世界和旧玩家数据无需迁移。旧代码调用 `IJobService.grantXp` 时会自动转入全服经验服务，并记录为 `miningdim:legacy/job/<job>` 来源。

农夫模块已完成来源细分：

- `miningdim:farmer/harvest`
- `miningdim:farmer/pick`

其余职业可以逐个把 `legacy` 来源细化，不要求一次性改动，也不改变最终经验数值。

## 3. 新模块接入规则

1. 在模块中声明稳定的轨道和来源 `ResourceLocation`。
2. 在模块装配期注册轨道处理器；若复用现有职业轨道则不重复注册。
3. 在模块装配期把每个来源注册并绑定到唯一轨道；拼错或跨轨道发放会立即失败。
4. 运行期只调用 `ExperienceServices.experienceService().award(...)`。
5. 来源 ID 一旦用于审计、任务或统计，不得随意改名。
6. 不允许直接写其他模块的 Capability、SavedData、每日经验或等级字段。
7. 全服总等级、赛季等级等新轨道必须先确定曲线和旧数据策略，再新增持久化处理器；不能把所有职业经验直接相加造成重复升级。

## 4. 当前代码边界

```text
progression/
  ExperienceModule.java        全服模块入口与路由器
  IExperienceService.java      业务模块调用契约
  ExperienceServices.java      服务定位器
  ExperienceGrant.java         轨道 + 来源 + 原始经验
  ExperienceAward.java         实际入账结果
  ExperienceSnapshot.java      当前总经验与等级
  ExperienceTrackHandler.java  轨道持久化适配契约
  ExperienceAwardListener.java 发放完成后的多播监听 (只读消费方, 如成就)
```

职业框架通过 `JobExperienceTracks` 和 `JobTrackHandler` 接入，不反向要求经验模块理解 `JobId` 或 `JobProgress`。

只读消费方经 `ExperienceServices.registerAwardListener` 在装配期注册：路由器在一笔经验落到轨道、读回发放后快照之后通知，带发放前的等级；被拒收的发放不通知。经验模块不吞监听器异常，捕获与记录是消费方自己的责任（成就模块的接法见 [成就设计文档](../../Achievement_System_DesignSpec.md) 9.10）。

## 5. 验证要求

- 全服经验模块必须先于职业框架和具体职业装配。
- 重复轨道注册必须启动失败，未登记轨道发放必须显式失败。
- 来源必须登记且只能绑定一个轨道，跨轨道发放必须显式失败。
- 原始经验和处理器返回的有效经验不得为负数。
- 旧 `IJobService.grantXp` 与新经验入口对同一轨道必须得到相同曲线结果。
- 每个具体模块至少有一个测试确认自己的轨道和来源 ID。
