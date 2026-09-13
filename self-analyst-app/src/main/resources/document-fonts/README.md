# 文档中文字体

`NotoSansSC-Regular.ttf` 用于本地 PDF 嵌入和 PPT 布局测量。字体来自 [Noto CJK 官方仓库](https://github.com/notofonts/noto-cjk/tree/main/Sans)，按随附 `OFL.txt` 的 SIL Open Font License 1.1 分发。

构建来源：`Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`，2026-09-13 下载；使用 FontTools 的 `varLib.instancer` 固定 `wght=400`，生成静态常规字重，未裁剪字符集。用户运行应用时无需 FontTools 或 Python。

- 文件大小：10,596,232 字节。
- SHA-256：`7ec1731f6770faeae2e4f9c65e425faa7c74392a2c81354193ee6142a61d2b64`。
- 构建命令：`fonttools varLib.instancer NotoSansSC-VF.ttf wght=400 --output NotoSansSC-Regular.ttf`。
- Java 渲染依赖：Apache POI 5.5.1、Apache PDFBox 3.0.8；许可由 Maven shaded JAR 的许可证合并器收集。
- 字体与布局使用 `java.desktop`；当前便携包的 `java.se` 模块集已包含该模块，无需追加外部运行时。

Office 文档保留原生文字与表格，查看器可能替换字体；PDF 将字体字形嵌入文件以固定中文显示。
