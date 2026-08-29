# self-analyst-audio SDD 规格说明书

> 本模块负责麦克风/系统回放采集、VAD、可选本地或云端语音转写，以及 AW heartbeat 写入。

## 1. 模块标识

| 属性 | 值 |
|------|-----|
| 模块名 | `self-analyst-audio` |
| 版本 | 1.0.0 |
| 类型 | Java 21 库模块 |
| 默认状态 | 关闭 |

## 2. 架构契约

### SPEC-AU-001：模块依赖

- 模块通过 HTTP 向 AW API 写入音频转写事件。
- 本地转写通过独立 whisper.cpp 子进程执行。
- 云端转写通过 OpenAI-compatible `/audio/transcriptions` 接口执行。
- Windows 系统回放采集通过 WASAPI loopback；麦克风使用 Java Sound。

### SPEC-AU-002：数据流

```text
mic / system / both
  → AudioCapturer（PCM → 16kHz、16-bit、mono）
  → VAD（低于阈值跳过）
  → AudioEngine
      ├─ CloudAudioEngine → llm.base-url/audio/transcriptions
      ├─ WhisperEngine → 本地 whisper-cli
      └─ FallbackAudioEngine → 云端不可用/失败时回退本地
  → POST AW heartbeat
```

## 3. 组件规格

### SPEC-AU-003：AudioEngine

`AudioEngine` 提供 `transcribe(byte[])`、`isAvailable()` 和可诊断的引擎名称。任何引擎不可用或
转写失败时返回空文本，不得终止采集线程。

### SPEC-AU-004：AudioCapturer 与输入源

- **SPEC-AU-004a**：`mic` 使用 Java Sound；无可用输入设备时进入 degraded，不得导致应用退出。
- **SPEC-AU-004b**：`system` 只在 Windows 使用默认渲染设备的 WASAPI loopback；不支持的平台进入 degraded。
- **SPEC-AU-004c**：`both` 分别启动 mic 与 system watcher；任一可用即可报告 running。
- **SPEC-AU-004d**：采集结果统一为 16kHz、16-bit、mono WAV；VAD 在转写前执行，静音片段不发送给任何引擎。
- **SPEC-AU-004e**：采样、静音、有效语音、空转写、成功转写和最近错误必须进入运行时诊断。

### SPEC-AU-005：转写引擎

- **SPEC-AU-005a**：`local-whisper` 调用 `whisper-cli` 与本地模型，临时 WAV 在完成后删除。
- **SPEC-AU-005b**：`cloud-asr` 把 WAV 作为 multipart 请求发送到
  `{llm.base-url}/audio/transcriptions`，使用 `llm.api-key` 与 `aw.audio.model`。
- **SPEC-AU-005c**：`auto` 优先使用可用的云端引擎，再回退本地 whisper；不能把 `auto` 描述为“仅本地”。
- **SPEC-AU-005d**：本地 exe/模型或云端 URL/key/model 缺失时，对应引擎 `isAvailable()` 为 false。

### SPEC-AU-006：AudioWatcher 与运行时控制

- watcher 以 daemon 线程运行，循环执行 capture → VAD → transcribe → heartbeat。
- Bucket 为 `aw-watcher-audio_{source}_{hostname}`；事件必须标记输入源和转写文本。`both` 模式
  分别产生 `mic` 与 `system` 两个来源 bucket。
- `AudioCaptureManager` 支持运行时启停；关闭时停止并释放全部 watcher。
- 单次失败只更新 degraded/诊断状态，后续循环可继续尝试。
- 配置总开关为 false 时，桌面端隐藏录音入口；运行时暂停不改变配置文件。

## 4. 配置

用户覆盖写入 `config.toml`：

```toml
[aw.audio]
enabled = false
source = "mic"              # mic | system | both
engine = "auto"             # auto | local-whisper | cloud-asr
whisperPath = "tools/whisper"
vadThreshold = 0.0001
model = "gpt-4o-transcribe"
chunkSeconds = 10
```

云端 ASR 复用 `llm.base-url` 和 `llm.api-key`。如果要求音频不得离开本机，必须显式设置
`engine = "local-whisper"`，并确认本地 whisper 可用。隐私行为见 [`../../PRIVACY.md`](../../PRIVACY.md)。

## 5. 测试规格

| 测试 | 预期 |
|------|------|
| 麦克风/系统输入不可用 | 状态为 degraded，应用继续运行 |
| VAD 判定静音 | 不调用本地或云端转写 |
| `local-whisper` 缺少 exe/模型 | 引擎不可用，不遗留临时 WAV |
| `cloud-asr` | WAV 发送到 `/audio/transcriptions`，错误响应不终止 watcher |
| `auto` | 云端优先，失败或不可用时回退本地 |
| `both` | mic/system 独立运行并聚合诊断 |
| 运行时关闭 | watcher 停止，输入设备和子进程资源释放 |

## 追溯矩阵

| 规格 ID | 文件/组件 |
|---------|-----------|
| SPEC-AU-001..002 | `pom.xml`、`AudioWatcher.java` |
| SPEC-AU-003 | `AudioEngine.java`、`FallbackAudioEngine.java` |
| SPEC-AU-004 | `AudioCapturer.java`、`JavaSoundAudioInput.java`、`WasapiLoopbackAudioInput.java` |
| SPEC-AU-005 | `WhisperEngine.java`、`CloudAudioEngine.java`、`AudioCaptureOptions.java` |
| SPEC-AU-006 | `AudioWatcher.java`、`AudioCaptureManager.java`、`AppSession.java` |
