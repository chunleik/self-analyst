/* ============================================================
   SelfAnalyst Desktop - API Client
   ============================================================ */
"use strict";

var API_BASE = window.location && window.location.origin ? window.location.origin : "http://localhost:5700";

function chatJsonResponse(response, fallbackMessage) {
  if (response.ok) return response.json();
  return response.text().then(function (text) {
    var body = null;
    try { body = text ? JSON.parse(text) : null; } catch (ignored) {}
    var error = new Error((body && (body.error || body.title)) ||
      fallbackMessage + ": " + response.status);
    error.status = response.status;
    error.body = body || text;
    throw error;
  });
}

function parseChatSseFrame(frame) {
  var eventName = "message";
  var data = [];
  String(frame || "").split("\n").forEach(function (line) {
    if (line.indexOf("event:") === 0) eventName = line.substring(6).trim();
    else if (line.indexOf("data:") === 0) data.push(line.substring(5).trimStart());
  });
  if (data.length === 0) return null;
  return { event: eventName, payload: JSON.parse(data.join("\n")) };
}

function consumeChatSseResponse(response, onEvent) {
  if (!response.ok) return chatJsonResponse(response, "Chat stream failed");
  if (!response.body || typeof response.body.getReader !== "function") {
    return Promise.reject(new Error("Chat stream response body is unavailable"));
  }
  var reader = response.body.getReader();
  var decoder = new TextDecoder();
  var buffer = "";
  var finalResult = null;

  function handleFrame(frame) {
    var parsed = parseChatSseFrame(frame);
    if (!parsed) return;
    if (onEvent) onEvent(parsed.event, parsed.payload);
    if (parsed.event === "error") {
      var error = new Error(parsed.payload && parsed.payload.error
        ? parsed.payload.error : "Chat stream failed");
      error.status = parsed.payload && parsed.payload.status;
      error.body = parsed.payload;
      throw error;
    }
    if (parsed.event === "result") finalResult = parsed.payload;
  }

  function drain(final) {
    var retainedCarriageReturn = !final && buffer.endsWith("\r");
    var parseable = retainedCarriageReturn ? buffer.slice(0, -1) : buffer;
    buffer = parseable.replace(/\r\n/g, "\n").replace(/\r/g, "\n") +
      (retainedCarriageReturn ? "\r" : "");
    var boundary;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      var frame = buffer.substring(0, boundary);
      buffer = buffer.substring(boundary + 2);
      if (frame.trim()) handleFrame(frame);
    }
    if (final && buffer.trim()) {
      handleFrame(buffer);
      buffer = "";
    }
  }

  function readNext() {
    return reader.read().then(function (part) {
      if (part.done) {
        buffer += decoder.decode();
        drain(true);
        if (!finalResult) throw new Error("Chat stream ended without a final result");
        return finalResult;
      }
      buffer += decoder.decode(part.value, { stream: true });
      drain(false);
      return readNext();
    });
  }
  return readNext().catch(function (error) {
    var cancellation;
    try {
      cancellation = typeof reader.cancel === "function" ? reader.cancel() : null;
    } catch (ignored) {}
    return Promise.resolve(cancellation).catch(function () {}).then(function () {
      throw error;
    });
  });
}

var api = {
  getStatus: function () {
    return fetch(API_BASE + "/desktop/status").then(function (r) {
      if (!r.ok) throw new Error("Status fetch failed: " + r.status);
      return r.json();
    });
  },
  setAudioCapture: function (enabled) {
    return fetch(API_BASE + "/desktop/audio", {
      method: "POST",
      body: JSON.stringify({ enabled: enabled }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Audio capture toggle failed: " + r.status);
      return r.json();
    });
  },
  getAudioEvents: function (limit) {
    var n = limit || 50;
    return fetch(API_BASE + "/desktop/audio/events?limit=" + encodeURIComponent(n)).then(function (r) {
      if (!r.ok) throw new Error("Audio events fetch failed: " + r.status);
      return r.json();
    });
  },
  getSummary: function () {
    return fetch(API_BASE + "/desktop/summary").then(function (r) {
      if (!r.ok) throw new Error("Summary fetch failed: " + r.status);
      return r.json();
    });
  },
  getUsage: function () {
    return fetch(API_BASE + "/desktop/usage").then(function (r) {
      if (!r.ok) throw new Error("Usage fetch failed: " + r.status);
      return r.json();
    });
  },
  getTasks: function () {
    return fetch(API_BASE + "/desktop/tasks").then(function (r) {
      if (!r.ok) throw new Error("Tasks fetch failed: " + r.status);
      return r.json();
    });
  },
  createTask: function (t) {
    return fetch(API_BASE + "/desktop/tasks", {
      method: "POST",
      body: JSON.stringify(t),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Create task failed: " + r.status);
      return r.json();
    });
  },
  updateTask: function (id, t) {
    return fetch(API_BASE + "/desktop/tasks/" + id, {
      method: "PUT",
      body: JSON.stringify(t),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Update task failed: " + r.status);
      return r.json();
    });
  },
  completeTask: function (id) {
    return fetch(API_BASE + "/desktop/tasks/" + id + "/complete", {
      method: "POST",
    }).then(function (r) {
      if (!r.ok) throw new Error("Complete task failed: " + r.status);
    });
  },
  archiveTask: function (id) {
    return fetch(API_BASE + "/desktop/tasks/" + id + "/archive", {
      method: "POST",
    }).then(function (r) {
      if (!r.ok) throw new Error("Archive task failed: " + r.status);
    });
  },
  deleteTask: function (id) {
    return fetch(API_BASE + "/desktop/tasks/" + id, {
      method: "DELETE",
    }).then(function (r) {
      if (!r.ok) throw new Error("Delete task failed: " + r.status);
    });
  },
  postChat: function (msg, ctx, sessionId, userMessageId) {
    return fetch(API_BASE + "/desktop/chat", {
      method: "POST",
      body: JSON.stringify({
        message: msg,
        context: ctx,
        sessionId: sessionId,
        userMessageId: userMessageId,
      }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Chat failed"); });
  },
  postChatStream: function (msg, ctx, sessionId, userMessageId, options) {
    var init = {
      method: "POST",
      body: JSON.stringify({
        message: msg,
        context: ctx,
        sessionId: sessionId,
        userMessageId: userMessageId,
      }),
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
    };
    if (options && options.signal) init.signal = options.signal;
    return fetch(API_BASE + "/desktop/chat/stream", init).then(function (response) {
      return consumeChatSseResponse(response, options && options.onEvent);
    });
  },
  cancelChat: function (sessionId, userMessageId) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(sessionId) + "/cancel", {
      method: "POST",
      body: JSON.stringify({ userMessageId: userMessageId }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Cancel chat failed"); });
  },
  // ---- Chat sessions (SPEC-CSP-FE-001) ----
  listSessions: function (options) {
    var params = new URLSearchParams();
    if (options && options.limit != null) params.set("limit", options.limit);
    if (options && options.cursor) params.set("cursor", options.cursor);
    if (options && options.q) params.set("q", options.q);
    var query = params.toString();
    return fetch(API_BASE + "/desktop/chat/sessions" + (query ? "?" + query : "")).then(function (r) {
      return chatJsonResponse(r, "List sessions failed");
    });
  },
  getSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id)).then(function (r) {
      return chatJsonResponse(r, "Get session failed");
    });
  },
  createSession: function (body) {
    return fetch(API_BASE + "/desktop/chat/sessions", {
      method: "POST",
      body: JSON.stringify(body || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Create session failed"); });
  },
  updateSession: function (id, patch) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id), {
      method: "PUT",
      body: JSON.stringify(patch || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Update session failed"); });
  },
  deleteSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id), {
      method: "DELETE",
    }).then(function (r) { return chatJsonResponse(r, "Delete session failed"); });
  },
  appendMessages: function (id, payload) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) + "/messages", {
      method: "POST",
      body: JSON.stringify(payload || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Append messages failed"); });
  },
  updateMessage: function (id, msgId, patch) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) +
        "/messages/" + encodeURIComponent(msgId), {
      method: "PUT",
      body: JSON.stringify(patch || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Update message failed"); });
  },
  setActiveSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/active-session", {
      method: "PUT",
      body: JSON.stringify({ activeSessionId: id }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) { return chatJsonResponse(r, "Set active session failed"); });
  },
  listMemory: function (params) {
    var query = params ? new URLSearchParams(params).toString() : "";
    var qs = query ? "?" + query : "";
    return fetch(API_BASE + "/desktop/memory" + qs).then(function (r) {
      if (!r.ok) throw new Error("List memory failed: " + r.status);
      return r.json();
    });
  },
  createMemory: function (payload) {
    return fetch(API_BASE + "/desktop/memory", {
      method: "POST",
      body: JSON.stringify(payload || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Create memory failed: " + r.status);
      return r.json();
    });
  },
  updateMemory: function (id, patch) {
    return fetch(API_BASE + "/desktop/memory/" + encodeURIComponent(id), {
      method: "PUT",
      body: JSON.stringify(patch || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Update memory failed: " + r.status);
      return r.json();
    });
  },
  deleteMemory: function (id) {
    return fetch(API_BASE + "/desktop/memory/" + encodeURIComponent(id), {
      method: "DELETE",
    }).then(function (r) {
      if (!r.ok) throw new Error("Delete memory failed: " + r.status);
      return r.json();
    });
  },
  setSessionMemoryPolicy: function (id, policy) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) + "/memory-policy", {
      method: "PUT",
      body: JSON.stringify({ memoryPolicy: policy }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Set memory policy failed: " + r.status);
      return r.json();
    });
  },
  createSessionMemory: function (id, payload) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) + "/memory", {
      method: "POST",
      body: JSON.stringify(payload || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Create session memory failed: " + r.status);
      return r.json();
    });
  },
  getConfig: function () {
    return fetch(API_BASE + "/desktop/config").then(function (r) {
      if (!r.ok) throw new Error("Config fetch failed: " + r.status);
      return r.json();
    });
  },
  saveConfig: function (c) {
    return fetch(API_BASE + "/desktop/config", {
      method: "PUT",
      body: JSON.stringify(c),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      return r.text().then(function (text) {
        var payload = {};
        if (text) {
          try {
            payload = JSON.parse(text);
          } catch (e) {
            payload = { error: text };
          }
        }
        if (!r.ok) {
          throw new Error(payload.error || text || "Save config failed: " + r.status);
        }
        return payload;
      });
    });
  },
  getRawConfig: function () {
    return fetch(API_BASE + "/desktop/config/raw").then(function (r) {
      if (!r.ok) throw new Error("Raw config fetch failed: " + r.status);
      return r.json();
    });
  },
  saveRawConfig: function (text) {
    return fetch(API_BASE + "/desktop/config/raw", {
      method: "PUT",
      body: JSON.stringify({ text: text }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      return r.text().then(function (body) {
        var payload = {};
        if (body) {
          try {
            payload = JSON.parse(body);
          } catch (e) {
            payload = { error: body };
          }
        }
        if (!r.ok) {
          throw new Error(payload.error || body || "Save raw config failed: " + r.status);
        }
        return payload;
      });
    });
  },
  testLlm: function (c) {
    return fetch(API_BASE + "/desktop/config/test-llm", {
      method: "POST",
      body: JSON.stringify(c),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("LLM test failed: " + r.status);
      return r.json();
    });
  },
  testEmbedding: function (c) {
    return fetch(API_BASE + "/desktop/config/test-embedding", {
      method: "POST",
      body: JSON.stringify(c),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Embedding test failed: " + r.status);
      return r.json();
    });
  },
};
