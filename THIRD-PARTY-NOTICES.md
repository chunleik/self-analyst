# Third-Party Notices

SelfAnalyst is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).

It depends on, and in its distribution (`dist/`) bundles, the third-party
components listed below. Each remains under its own license; the texts referenced
here govern those components, not SelfAnalyst's own code.

This list is provided in good faith for attribution and compliance. If you spot
an error or omission, please open an issue.

## Java dependencies (declared in `pom.xml`)

| Component | Purpose | License |
|-----------|---------|---------|
| [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) | LLM Agent framework | Apache-2.0 |
| [Javalin](https://javalin.io) | Embedded HTTP server | Apache-2.0 |
| [sqlite-jdbc (Xerial)](https://github.com/xerial/sqlite-jdbc) | SQLite JDBC driver | Apache-2.0 |
| [JNA / JNA Platform](https://github.com/java-native-access/jna) | Native window/AFK tracking, UIAutomation COM | Apache-2.0 / LGPL-2.1 (dual; used under Apache-2.0) |
| [Jackson](https://github.com/FasterXML/jackson) | JSON serialization | Apache-2.0 |
| [Tess4J](https://github.com/nguyenq/tess4j) | Tesseract OCR Java wrapper | Apache-2.0 |
| [Apache Lucene](https://lucene.apache.org) | Vector / KNN semantic index | Apache-2.0 |
| [Apache PDFBox](https://pdfbox.apache.org) | PDF text extraction | Apache-2.0 |
| [Apache POI](https://poi.apache.org) | Office document extraction | Apache-2.0 |
| [SLF4J](https://www.slf4j.org) | Logging facade | MIT |
| [Logback](https://logback.qos.ch) | Logging implementation | EPL-1.0 / LGPL-2.1 (dual) |
| [JUnit 5](https://junit.org/junit5/) | Testing (test scope only) | EPL-2.0 |

## Bundled / downloaded binaries and models

These are not in the Git repository; `scripts/download-tools.ps1` fetches them
into `tools/`, and `scripts/build-dist.ps1` packages them into `dist/`.

| Component | Purpose | License |
|-----------|---------|---------|
| [PaddleOCR-json](https://github.com/hiroi-sora/PaddleOCR-json) (PaddleOCR engine + models) | Chinese OCR | Apache-2.0 |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | Local speech-to-text inference | MIT |
| Whisper ggml models (from [OpenAI Whisper](https://github.com/openai/whisper)) | STT model weights | MIT |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) | OCR fallback engine | Apache-2.0 |

## Desktop shell (Tauri)

| Component | Purpose | License |
|-----------|---------|---------|
| [Tauri 2.x](https://tauri.app) | Desktop application shell | MIT / Apache-2.0 |
| Microsoft Edge WebView2 Runtime | Windows WebView (system component, not redistributed) | Proprietary (Microsoft redistributable terms) |

Rust crate dependencies of the Tauri shell are declared in
`self-analyst-desktop/src-tauri/Cargo.toml`; their licenses (predominantly
MIT / Apache-2.0) are resolved by Cargo and recorded in `Cargo.lock`.

## Desktop frontend

| Component | Purpose | License |
|-----------|---------|---------|
| [Deep Chat 2.5.0](https://github.com/OvidijusParsiunas/deep-chat) | Vendored web component for the chat message surface and composer | MIT |

The Deep Chat distribution bundle is stored at
`self-analyst-app/src/main/resources/desktop-ui/deep-chat.bundle.js`. Its
license text is bundled in the application resources at
`self-analyst-app/src/main/resources/third-party/deep-chat-LICENSE.txt`. The
bundle was extracted from the official `deep-chat@2.5.0` npm package and has
SHA-256 `12E0B5352E26E257C4D80BCA9FCFB75DC382608EE9B15CF920A469D51C3496EA`.

## Note on ActivityWatch compatibility

SelfAnalyst's embedded AW server is an independent Java implementation that is
**data-format and API compatible** with [ActivityWatch](https://activitywatch.net)
(MPL-2.0). It does not incorporate ActivityWatch source code.
