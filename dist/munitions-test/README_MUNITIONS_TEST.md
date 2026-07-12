# 军火商测试包

## 文件

- `miningdim-1.20.1-1.0.0-all.jar`

这是完整 `miningdim` 测试 jar。项目是单 jar 架构，军火商不是独立 mod，所以测试军火商也需要放这个完整 jar。

## 放置位置

把 `miningdim-1.20.1-1.0.0-all.jar` 放进 Minecraft 1.20.1 Forge 47.x 实例的 `mods` 文件夹。

## 建议前置

- 必需: Minecraft 1.20.1 + Forge 47.x
- 军火商真产弹建议安装 TACZ 1.20.1。没有 TACZ 时，军火台逻辑能加载，但不会物化出真 TACZ 弹药。
- Champions 和 MCEF 对军火商基础测试不是必需项。

## 快速测试命令

```mcfunction
/give @p miningdim:munitions_bench
/give @p miningdim:primer 64
/give @p miningdim:casing 64
/give @p miningdim:bullet_head 64
/give @p miningdim:propellant 64
/job set @p munitions 10
```

## 测试路径

1. 放置军火台。
2. 右键打开军火台。
3. 依次放入底火、弹壳、弹头、发射药。
4. 选择已解锁口径。
5. 等待产线累积，输出缓冲会生成 TACZ 弹药。

当前规则: 四件套每批各消耗 1 个。产量仍按文档等级走，L1-L5 每批步枪弹 40 发，L6+ 每批步枪弹 70 发；高阶口径按缩产系数减少发数。
