/* ============================================================
   SelfAnalyst Desktop - i18n message catalog + lookup (SPEC-I18N-UI-001/002)
   ------------------------------------------------------------
   Loaded after utils.js and before state.js so t()/applyI18n() exist
   before any module renders. The effective language lives in state.lang
   (resolved from /desktop/status, see init.js). The catalog stores one
   entry per stable key with a column per language; the structure can take
   a third language without touching call sites (SPEC-I18N-DEC-002).
   ============================================================ */
"use strict";

var MESSAGES = {
  // ── Top nav / status bar ──
  "tab.agent": { zh: "Agent", en: "Agent" },
  "tab.chat": { zh: "会话", en: "Chat" },
  "tab.audio": { zh: "录音", en: "Audio" },
  "status.service": { zh: "服务", en: "Service" },
  "status.capture": { zh: "采集", en: "Capture" },
  "status.llm": { zh: "LLM", en: "LLM" },
  "status.ok": { zh: "正常", en: "OK" },
  "status.notReady": { zh: "未就绪", en: "not ready" },
  "status.running": { zh: "运行中", en: "Running" },
  "status.degraded": { zh: "降级", en: "Degraded" },
  "status.disabled": { zh: "已禁用", en: "Disabled" },
  "headroom.status.disabled": { zh: "关闭", en: "off" },
  "headroom.status.available": { zh: "可用", en: "available" },
  "headroom.status.fallback": { zh: "回退", en: "fallback" },
  "headroom.status.unavailable": { zh: "不可用", en: "unavailable" },
  "headroom.status.unknown": { zh: "未知", en: "unknown" },
  "audio.start": { zh: "开始录音", en: "Start recording" },
  "audio.stop": { zh: "暂停录音", en: "Pause recording" },
  "audio.toggleFailed": { zh: "切换录音失败: {msg}", en: "Failed to toggle recording: {msg}" },
  "audio.title": { zh: "录音文本", en: "Audio transcripts" },
  "audio.subtitle": { zh: "实时显示最近转写内容", en: "Showing recent transcripts in real time" },
  "audio.updated": { zh: "{time}更新", en: "Updated {time}" },
  "audio.latest": { zh: "最后录音 {time}", en: "Last transcript {time}" },
  "audio.listening": { zh: "监听中，暂无新的可转写声音", en: "Listening, no new transcribable audio yet" },
  "audio.diagnostics.silent": { zh: "最近采样正常，但 {time} 未检测到可转写声音", en: "Sampling is active, but no transcribable voice was detected {time}" },
  "audio.diagnostics.emptyTranscript": { zh: "检测到声音，但识别引擎 {time} 未返回文本", en: "Voice was detected, but the recognition engine returned no text {time}" },
  "audio.diagnostics.error": { zh: "录音链路异常: {msg}", en: "Audio pipeline error: {msg}" },
  "audio.source.mic": { zh: "麦克风", en: "Microphone" },
  "audio.source.system": { zh: "系统声音", en: "System audio" },
  "audio.source.both": { zh: "混合", en: "Mixed" },
  "audio.empty": { zh: "暂无录音文本", en: "No audio transcripts yet" },
  "audio.loadFailed": { zh: "载入录音文本失败: {msg}", en: "Failed to load audio transcripts: {msg}" },

  // ── Common ──
  "common.loading": { zh: "加载中...", en: "Loading..." },
  "common.loadingEllipsis": { zh: "加载中…", en: "Loading…" },
  "common.noData": { zh: "暂无数据", en: "No data" },
  "common.unknownError": { zh: "未知错误", en: "Unknown error" },
  "action.settings": { zh: "配置", en: "Settings" },
  "action.close": { zh: "关闭", en: "Close" },

  // ── Behavior advice ──
  "advice.analyzing": { zh: "分析行为数据中...", en: "Analyzing behavior data..." },
  "advice.empty": {
    zh: "还没有足够行为数据生成建议。继续使用一段时间后，这里会出现基于过往行为的提醒或鼓励。",
    en: "Not enough behavior data yet to generate advice. Keep using SelfAnalyst for a while and reminders or encouragement based on your past behavior will appear here.",
  },
  "advice.type.encouragement": { zh: "鼓励", en: "Encouragement" },
  "advice.type.suggestion": { zh: "建议", en: "Suggestion" },
  "advice.type.reminder": { zh: "提醒", en: "Reminder" },
  "advice.basedOn": { zh: "基于{scope}", en: "Based on {scope}" },
  "advice.updated": { zh: "{time}更新", en: "Updated {time}" },
  "advice.basis.observationRange": { zh: "观察范围", en: "Observation range" },
  "advice.basis.trend": { zh: "趋势", en: "Trend" },
  "advice.basis.adviceKind": { zh: "建议类型", en: "Advice type" },
  "advice.basis.dataCompleteness": { zh: "数据完整度", en: "Data completeness" },

  // ── Timeline ──
  "timeline.title": { zh: "时间轴", en: "Timeline" },
  "timeline.insufficient": { zh: "数据不足", en: "Not enough data" },
  "timeline.period": { zh: "时间段", en: "Period" },
  "timeline.noSummary": { zh: "暂无总结", en: "No summary yet" },
  "timeline.topApps": { zh: "常用应用:", en: "Top apps:" },
  "timeline.activeTime": { zh: "活跃时长:", en: "Active time:" },
  "timeline.afkTime": { zh: "离开时长:", en: "Idle time:" },
  "timeline.llmNotConfigured": { zh: "配置 LLM 后可生成建议", en: "Configure the LLM to generate advice" },
  "timeline.evidence": { zh: "证据", en: "Evidence" },
  "timeline.insight": { zh: "洞察", en: "Insight" },
  "timeline.suggestion": { zh: "建议", en: "Suggestion" },
  "timeline.discuss": { zh: "追问", en: "Ask" },
  "timeline.discussSuffix": { zh: "{label}追问", en: "Ask about {label}" },

  // ── Tasks ──
  "tasks.title": { zh: "未来任务", en: "Upcoming tasks" },
  "tasks.empty": { zh: "暂无待办事项", en: "No to-dos yet" },
  "task.untitled": { zh: "未命名任务", en: "Untitled task" },
  "task.label.title": { zh: "标题", en: "Title" },
  "task.label.priority": { zh: "优先级", en: "Priority" },
  "task.label.due": { zh: "截止日期", en: "Due date" },
  "task.complete": { zh: "完成", en: "Complete" },
  "task.discuss": { zh: "讨论", en: "Discuss" },
  "task.archive": { zh: "归档", en: "Archive" },
  "task.delete": { zh: "删除", en: "Delete" },
  "task.save": { zh: "保存", en: "Save" },
  "task.added": { zh: "已添加", en: "Added" },
  "task.created": { zh: "已创建", en: "Created" },
  "task.confirmDelete": { zh: "确认删除此任务？", en: "Delete this task?" },
  "task.discussPrefix": { zh: "任务讨论：{title}", en: "Task discussion: {title}" },
  "task.completeFailed": { zh: "完成任务失败: {msg}", en: "Failed to complete task: {msg}" },
  "task.archiveFailed": { zh: "归档任务失败: {msg}", en: "Failed to archive task: {msg}" },
  "task.deleteFailed": { zh: "删除任务失败: {msg}", en: "Failed to delete task: {msg}" },
  "task.saveFailed": { zh: "保存任务失败: {msg}", en: "Failed to save task: {msg}" },
  "task.addFailed": { zh: "添加任务失败: {msg}", en: "Failed to add task: {msg}" },
  "task.createFailed": { zh: "创建任务失败: {msg}", en: "Failed to create task: {msg}" },

  // ── Priority ──
  "priority.high": { zh: "高", en: "High" },
  "priority.medium": { zh: "中", en: "Medium" },
  "priority.low": { zh: "低", en: "Low" },

  // ── Relative time ──
  "time.justNow": { zh: "刚刚", en: "just now" },
  "time.minutesAgo": { zh: "{n}分钟前", en: "{n} min ago" },
  "time.hoursAgo": { zh: "{n}小时前", en: "{n} h ago" },
  "time.daysAgo": { zh: "{n}天前", en: "{n} d ago" },

  // ── Chat tab ──
  "chat.sessionsTitle": { zh: "会话列表", en: "Sessions" },
  "chat.newSessionTitle": { zh: "新建会话", en: "New chat" },
  "chat.newBtn": { zh: "+ 新建", en: "+ New" },
  "chat.searchPlaceholder": { zh: "搜索会话...", en: "Search chats..." },
  "chat.emptyHint": { zh: '暂无会话，点击"+ 新建"开始', en: 'No chats yet — click "+ New" to start' },
  "chat.noMatch": { zh: "没有匹配的会话", en: "No matching chats" },
  "chat.searching": { zh: "正在搜索会话...", en: "Searching chats..." },
  "chat.loadMore": { zh: "加载更多", en: "Load more" },
  "chat.loadMoreFailed": { zh: "加载更多会话失败: {msg}", en: "Failed to load more chats: {msg}" },
  "chat.selectOrCreate": { zh: "选择或创建会话", en: "Select or create a chat" },
  "chat.selectOrCreateThread": { zh: "选择或创建一个会话", en: "Select or create a chat" },
  "chat.selectFromListHint": {
    zh: '从左侧列表选择会话，或点击"+ 新建"创建新会话',
    en: 'Select a chat from the list on the left, or click "+ New" to create one',
  },
  "chat.welcomeTitle": { zh: "欢迎使用会话模式", en: "Welcome to chat mode" },
  "chat.welcomeHint": {
    zh: "可以追问当前状态、记录想法、生成待办",
    en: "Ask about your current status, capture ideas, and generate to-dos",
  },
  "chat.composerPlaceholder": {
    zh: "输入消息... (Enter 发送, Shift+Enter 换行)",
    en: "Type a message... (Enter to send, Shift+Enter for newline)",
  },
  "chat.send": { zh: "发送", en: "Send" },
  "chat.ctxCurrentStatus": { zh: "当前状态", en: "Current status" },
  "chat.ctxRecentActivity": { zh: "最近活动", en: "Recent activity" },
  "chat.ctxTaskSuggestions": { zh: "可生成待办", en: "Suggested to-dos" },
  "chat.ctxContext": { zh: "上下文", en: "Context" },
  "chat.ctxFutureTasks": { zh: "未来任务", en: "Upcoming tasks" },
  "chat.ctxHistory": { zh: "历史消息", en: "History" },
  "chat.noActivitySummary": { zh: "暂无活动摘要", en: "No activity summary" },
  "chat.noSuggestedTasks": { zh: "暂无建议待办", en: "No suggested to-dos" },
  "chat.thinking": { zh: "思考中...", en: "Thinking..." },
  "chat.noReply": { zh: "无回复", en: "No reply" },
  "chat.retry": { zh: "重试", en: "Retry" },
  "chat.createTask": { zh: "创建任务", en: "Create task" },
  "chat.confidence": { zh: "置信度: {v}", en: "Confidence: {v}" },
  "chat.moreSummaries": { zh: "还有 {n} 条摘要未展开", en: "{n} more summaries collapsed" },
  "chat.deleteSessionTitle": { zh: "删除会话", en: "Delete chat" },
  "chat.confirmDeleteSession": { zh: "确定删除该会话及其所有消息？", en: "Delete this chat and all its messages?" },
  "chat.deleteSessionFailed": { zh: "删除会话失败: {msg}", en: "Failed to delete chat: {msg}" },
  "chat.sendFailed": { zh: "发送失败: {msg}", en: "Send failed: {msg}" },
  "chat.loadSessionFailed": { zh: "加载会话失败，可重新选择该会话重试：{msg}", en: "Failed to load chat. Select it again to retry: {msg}" },
  "chat.reconciliationPending": { zh: "回复保存状态待确认，请重新选择该会话重试加载：{msg}", en: "Reply persistence needs reconciliation. Select this chat again to reload: {msg}" },
  "chat.llmNotConfiguredPlaceholder": {
    zh: "LLM 未配置，请先在配置页设置 API Key",
    en: "LLM not configured. Please set the API key in Settings first.",
  },
  "chat.agentNoContent": { zh: "Agent 未返回可显示内容", en: "The agent returned no displayable content" },
  "chat.newSessionDefault": { zh: "新会话", en: "New chat" },
  "chat.currentSession": { zh: "当前会话", en: "Current chat" },
  "chat.newSessionFailed": { zh: "新建会话失败: {msg}", en: "Failed to create chat: {msg}" },
  "chat.createSessionFailed": { zh: "创建会话失败: {msg}", en: "Failed to create chat: {msg}" },
  "chat.contextFollowup": { zh: "上下文追问", en: "Context follow-up" },
  "chat.contextBrought": { zh: "已带入上下文：{title}", en: "Context added: {title}" },
  "chat.currentItem": { zh: "当前条目", en: "current item" },

  // ── Long-term memory ──
  "memory.chatTitle": { zh: "长期记忆", en: "Long-term memory" },
  "memory.loading": { zh: "加载中...", en: "Loading..." },
  "memory.loadFailed": { zh: "记忆加载失败: {msg}", en: "Failed to load memory: {msg}" },
  "memory.noSession": { zh: "选择会话后显示记忆设置", en: "Select a chat to show memory settings" },
  "memory.policy.smart": { zh: "智能", en: "Smart" },
  "memory.policy.confirmAll": { zh: "每次确认", en: "Confirm all" },
  "memory.policy.off": { zh: "关闭", en: "Off" },
  "memory.addPlaceholder": { zh: "手动添加一条记忆...", en: "Add a memory..." },
  "memory.add": { zh: "添加记忆", en: "Add memory" },
  "memory.empty": { zh: "暂无相关记忆", en: "No related memories" },
  "memory.approve": { zh: "批准", en: "Approve" },
  "memory.reject": { zh: "拒绝", en: "Reject" },
  "memory.edit": { zh: "编辑", en: "Edit" },
  "memory.editApprove": { zh: "编辑并批准", en: "Edit and approve" },
  "memory.editContent": { zh: "编辑记忆内容", en: "Edit memory content" },
  "memory.editEvidence": { zh: "编辑来源说明", en: "Edit evidence" },
  "memory.pendingCount": { zh: "待确认 {n}", en: "{n} pending" },
  "memory.manualEvidence": { zh: "用户从会话手动添加", en: "Manually added from chat" },
  "memory.saveFailed": { zh: "保存记忆失败: {msg}", en: "Failed to save memory: {msg}" },
  "memory.disable": { zh: "停用", en: "Disable" },

  // ── Chat drawer (legacy) ──
  "drawer.discuss": { zh: "讨论", en: "Discuss" },
  "drawer.discussWith": { zh: "讨论: {title}", en: "Discuss: {title}" },
  "drawer.item": { zh: "条目", en: "item" },
  "drawer.contextLabel": { zh: "上下文:", en: "Context:" },
  "drawer.currentSelection": { zh: "当前选中项", en: "current selection" },
  "drawer.inputPlaceholder": { zh: "输入消息...", en: "Type a message..." },
  "drawer.addAsTask": { zh: "+ 添加为任务: {title}", en: "+ Add as task: {title}" },

  // ── Config ──
  "config.modalTitle": { zh: "配置文件", en: "Configuration file" },
  "config.loading": { zh: "加载配置中...", en: "Loading configuration..." },
  "config.testLlm": { zh: "测试 LLM 连接", en: "Test LLM connection" },
  "config.testEmbedding": { zh: "测试 Embedding 连接", en: "Test Embedding connection" },
  "config.testing": { zh: "测试中...", en: "Testing..." },
  "config.connectOk": { zh: "{service} 连接成功", en: "{service} connection succeeded" },
  "config.connectFailed": { zh: "{service} 连接失败: {msg}", en: "{service} connection failed: {msg}" },
  "config.testFailed": { zh: "{service} 测试失败: {msg}", en: "{service} test failed: {msg}" },
  "config.loadErrorReadonly": {
    zh: "载入配置失败，编辑器为只读。请关闭后重试。",
    en: "Failed to load configuration; the editor is read-only. Please close and retry.",
  },
  "config.changesTitle": { zh: "配置更改", en: "Configuration changes" },
  "config.discard": { zh: "放弃更改", en: "Discard changes" },
  "config.saveChanges": { zh: "保存更改", en: "Save changes" },
  "config.saving": { zh: "正在保存...", en: "Saving..." },
  "config.savingShort": { zh: "保存中...", en: "Saving..." },
  "config.unsavedChanges": { zh: "有未保存更改", en: "Unsaved changes" },
  "config.noUnsavedChanges": { zh: "没有未保存更改", en: "No unsaved changes" },
  "config.saveSuccess": { zh: "保存成功", en: "Saved" },
  "config.restartSuffix": { zh: "（需重启后端: {keys}）", en: " (restart required: {keys})" },
  "config.unknownKeysSuffix": { zh: "（未知键: {keys}）", en: " (unknown keys: {keys})" },
  "config.saveFailed": { zh: "保存失败: {msg}", en: "Save failed: {msg}" },
  "config.loadConfigFailed": { zh: "载入配置失败: {msg}", en: "Failed to load configuration: {msg}" },
  "config.confirmDiscardClose": { zh: "有未保存的更改，确定丢弃并关闭？", en: "You have unsaved changes. Discard and close?" },

  // ── Error overlay ──
  "error.notReady": { zh: "本地服务未就绪，正在重试...", en: "Local service not ready, retrying..." },
  "error.retry": { zh: "重试连接", en: "Retry connection" },
};

/**
 * Look up a localized message by stable key for the effective language
 * (state.lang). Falls back to the other language, then to the key itself —
 * never throws (SPEC-I18N-UI-002). Replaces {name} placeholders from params.
 */
function t(key, params) {
  var lang = (typeof state !== "undefined" && state && state.lang) ? state.lang : "zh";
  var entry = MESSAGES[key];
  var s = null;
  if (entry) {
    s = entry[lang];
    if (s == null) s = entry.zh != null ? entry.zh : entry.en;
  }
  if (s == null) s = key;
  if (params) {
    s = s.replace(/\{(\w+)\}/g, function (m, k) {
      return params[k] != null ? params[k] : m;
    });
  }
  return s;
}

/**
 * Apply the catalog to static markup under root: [data-i18n] sets textContent,
 * [data-i18n-placeholder] sets placeholder, [data-i18n-title] sets title
 * (SPEC-I18N-UI-003a).
 */
function applyI18n(root) {
  root = root || document;
  var nodes = root.querySelectorAll("[data-i18n]");
  for (var i = 0; i < nodes.length; i++) {
    nodes[i].textContent = t(nodes[i].getAttribute("data-i18n"));
  }
  var ph = root.querySelectorAll("[data-i18n-placeholder]");
  for (var j = 0; j < ph.length; j++) {
    ph[j].setAttribute("placeholder", t(ph[j].getAttribute("data-i18n-placeholder")));
  }
  var titles = root.querySelectorAll("[data-i18n-title]");
  for (var k = 0; k < titles.length; k++) {
    titles[k].setAttribute("title", t(titles[k].getAttribute("data-i18n-title")));
  }
}
