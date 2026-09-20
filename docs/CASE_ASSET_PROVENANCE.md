# 开箱资源来源与替换说明

本文是 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 的配套专文（见其第一节末与第六节）；
两份文件必须同步更新。

本模块随 JAR 分发的开箱资产分两类，法务性质不同：

**（一）原创生成物**：17 张枪械皮肤纹理 PNG 和 8 个界面音效 OGG，均由
`tools/generate_case_assets.py` 确定性生成。它们没有复制、采样或提取
Counter-Strike 2、TaCZ 或其他游戏的图片与音频字节。

**（二）TaCZ 配置的派生拷贝**：17 份 `case_*_display.json`（在
`src/main/resources/assets/miningdim/custom/miningdim_cases/assets/miningdim/display/guns/`）。
生成脚本从
`libs/tacz-1.20.1-1.1.8-hotfix.jar` 内
`assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/<gun>_display.json`
把整个 JSON 对象读出，只替换 `texture` 字段、删掉 `lod`，其余（`transform` 缩放
数值、`muzzle_flash`、`hud`/`slot`/`animation`/`state_machine`/`sounds` 引用等）
原样写入并随 JAR 分发。这是 TaCZ 配置数据的逐字派生，不是单纯的"保留资源引用"；
TaCZ 对其资产自声明 `CC BY-NC-ND 4.0`，ND 条款下的再分发风险见
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 第五节。运行时仍要求客户端
安装与服务器一致的 TaCZ 版本。

同目录树顶层的 `gunpack.meta.json` 是我方自写的枪包声明（内容只有
`{"namespace": "miningdim"}`），按 TaCZ 的枪包格式填写，不含其数据。

音效事件槽如下，可由独立 Minecraft 资源包合法覆盖：

- `miningdim:case_unlock`
- `miningdim:case_open`
- `miningdim:case_tick`
- `miningdim:case_reveal_blue`
- `miningdim:case_reveal_purple`
- `miningdim:case_reveal_pink`
- `miningdim:case_reveal_red`
- `miningdim:case_reveal_gold`

重新生成命令：

```powershell
python -m pip install -r tools/requirements-case-assets.txt
python tools/generate_case_assets.py
```

不要把从 CS2 安装目录提取的 Valve 音频提交到仓库或打入发布 JAR；如未来取得
明确的再分发授权，可通过资源包覆盖上述事件，而无需修改开箱逻辑。
