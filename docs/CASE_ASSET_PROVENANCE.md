# 开箱资源来源与替换说明

本模块随 JAR 分发的 17 张枪械皮肤纹理和 8 个界面音效，均由
`tools/generate_case_assets.py` 确定性生成。它们没有复制、采样或提取
Counter-Strike 2、TaCZ 或其他游戏的图片与音频字节。

枪械 display JSON 会读取本地 `libs/tacz-1.20.1-1.1.8-hotfix.jar` 的显示配置，
保留模型、动画、状态机和声音的资源引用，仅把枪身纹理指向本模组生成的原创
PNG。运行时仍要求客户端安装与服务器一致的 TaCZ 版本。

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
