# self-analyst-audio SDD 规格说明书

> 音频采集 + whisper.cpp 语音转文字模块。

---

## 1. 模块标识

| 属性 | 值 |
|------|-----|
| 模块名 | `self-analyst-audio` |
| 版本 | 1.0.0 |
| 类型 | Java 21 库模块 |


## 2. 架构契约

### SPEC-AU-001: 模块依赖

- 无外部 Java 依赖（仅 Jackson + JUnit 测试）
- 通过 HTTP 向 AW API 推送数据（与 WindowWatcher 同模式）
- whisper.cpp 作为独立子进程调用（同 PaddleOCR 模式）

### SPEC-AU-002: 数据流

```
麦克风 → AudioCapturer (PCM 16kHz)
         → VAD (RMS > 阈值)
           → 临时 .wav
             → whisper-cli.exe (ggml-small.bin)
               → 解析 stdout 文本
                 → POST /api/0/buckets/aw-watcher-audio/heartbeat
```


## 3. 组件规格

### SPEC-AU-003: AudioEngine 接口

```java
public interface AudioEngine {
    String transcribe(byte[] wavData);
    boolean isAvailable();
}
```

### SPEC-AU-004: AudioCapturer

| 参数 | 值 |
|------|-----|
| 采样率 | 16000 Hz |
| 位深 | 16 bit |
| 声道 | mono |
| 录音周期 | 5 秒 |
| VAD 阈值 | RMS < 0.01 → 跳过静音 |
| 输出格式 | WAV (PCM RIFF 头) |

- **SPEC-AU-004a**: 无麦克风设备时 `isAvailable()` 返回 false
- **SPEC-AU-004b**: 静音段直接返回 null，不触发转录

### SPEC-AU-005: WhisperEngine

- 使用 whisper.cpp v1.8.6 (`whisper-cli.exe`)
- 模型: `ggml-small.bin` (466MB, 中英文)
- 参数: `-l auto -nt --no-prints`
- 通过 ProcessBuilder one-shot 调用
- 输出: 过滤进度行后的纯文本转录
- **SPEC-AU-005a**: exe 或模型不存在时 `isAvailable()` 返回 false

### SPEC-AU-006: AudioWatcher

- 继承 `Thread`，daemon 线程
- 5 秒循环：capture → VAD → transcribe → POST heartbeat
- Bucket: `aw-watcher-audio_{hostname}`
- **SPEC-AU-006a**: 启动时调用 `ensureBucket()` 创建 bucket
- **SPEC-AU-006b**: 转录失败不终止循环
- **SPEC-AU-006c**: `shutdown()` 关闭 AudioCapturer 并中断线程


## 4. 配置

```properties
# 默认关闭
aw.audio.enabled=false     # 或 AW_AUDIO_ENABLED=true
```

AppSession 在启动时检查此配置，false 时跳过 AudioWatcher。桌面 UI 的状态接口同时返回
`aw.audioEnabled=false`，此时顶部“录音”页签和录音开关均隐藏；若界面正停留在录音页，
则切回 Agent 页。运行期间仅暂停录音不会隐藏入口。


## 5. 测试规格

| 测试 | 预期 |
|------|------|
| AudioCapturer 无设备 | `isAvailable()` = false |
| VAD 静音 | `capture()` 返回 null |
| WhisperEngine exe 缺失 | `isAvailable()` = false |

---

## 追溯矩阵

| 规格 ID | 文件 |
|---------|------|
| SPEC-AU-001..002 | pom.xml |
| SPEC-AU-003 | AudioEngine.java |
| SPEC-AU-004 | AudioCapturer.java |
| SPEC-AU-005 | WhisperEngine.java |
| SPEC-AU-006 | AudioWatcher.java + AppSession.java |
