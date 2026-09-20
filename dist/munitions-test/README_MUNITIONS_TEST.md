# 军火商测试包

## 文件

- `miningdim-1.20.1-1.0.0-all.jar`

这是完整 `miningdim` 测试 jar。项目是单 jar 架构，军火商不是独立 mod，所以测试军火商也需要放这个完整 jar。

## 放置位置

把 `miningdim-1.20.1-1.0.0-all.jar` 放进 Minecraft 1.20.1 Forge 47.x 实例的 `mods` 文件夹。

## 建议前置

- 必需: Minecraft 1.20.1 + Forge 47.x
- 必需: 一套供电手段。军火台是耗电机器，产线结算前先查内部 FE 缓冲，不足直接不产。`/job set @p munitions 10` 对应 L10（已解锁提炼），每批电费 = `REFINED_ROUNDS_PER_BATCH`（默认 70）× `FE_PER_RIFLE_EQUIVALENT_ROUND`（默认 100,000）= 7,000,000 FE（见 `MunitionsProduction.feCostPerBatch`）；军火台内部缓冲上限 `BENCH_ENERGY_CAPACITY` 默认 32,000,000 FE，满电约够连产 4 批。没电时开工帧与完工帧的 `energy.hasAtLeast` 两道闸门都会拒绝结算，表现为零产出。
- 军火商真产弹建议安装 TACZ 1.20.1。没有 TACZ 时，军火台逻辑能加载，但不会物化出真 TACZ 弹药。
- Champions 和 MCEF 对军火商基础测试不是必需项。

## 快速测试命令

```mcfunction
/give @p miningdim:munitions_bench
/give @p miningdim:primer 64
/give @p miningdim:casing 64
/give @p miningdim:bullet_head 64
/give @p miningdim:propellant 64
/give @p miningdim:coal_generator
/give @p miningdim:iron_energy_cable 16
/job set @p munitions 10
```

发电与线缆按需替换成更高档的型号（`miningdim:geothermal_generator`、更高等级的 `*_energy_cable`）。线缆额定吞吐按导体材料分级（铁是最低的一档），低级线缆会把充能速度压得很慢，急着看产出就用高等级线缆或多路并联。

## 测试路径

1. 放置军火台。
2. 放置发电机，并用线缆把发电机接到军火台上（军火台自身不发电，电只能由电网 push 进它的内部 FE 缓冲）。给发电机加燃料，确认军火台的 FE 缓冲开始上涨后再往下走。
3. 右键打开军火台。
4. 依次放入底火、弹壳、弹头、发射药。
5. 选择已解锁口径。
6. 等待产线累积，输出缓冲会生成 TACZ 弹药。

产不出来先看电：缓冲不够一批电费时，开工帧与完工帧都会直接拒绝结算，界面上没有任何报错，表现就是"料满了、口径也选了，就是不出弹"。

当前规则: 每批消耗 底火 1 + 弹壳 1 + 弹头 1 + 发射药 2（对齐 `docs/Munitions_Job_DesignSpec.md` 的"7 铜 + 16 火药"批次成本：底火 2 铜 + 弹壳 3 铜 + 弹头 2 铜 = 7 铜各 1 个，发射药 8 火药 × 2 = 16 火药；默认值见 `MunitionsConfig` 的 recipe 组）。备料时发射药按双倍带，否则它会先耗尽。产量仍按文档等级走，L1-L5 每批步枪弹 40 发，L6+ 每批步枪弹 70 发；高阶口径按缩产系数减少发数。
