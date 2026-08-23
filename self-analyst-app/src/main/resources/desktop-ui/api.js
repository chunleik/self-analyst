/* ============================================================
   SelfAnalyst Desktop - API Client
   ============================================================ */
"use strict";

var API_BASE = window.location && window.location.origin ? window.location.origin : "http://localhost:5700";

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
    }).then(function (r) {
      if (!r.ok) {
        return r.json().then(function (body) {
          throw new Error(body.error || ("Chat failed: " + r.status));
        }, function () {
          throw new Error("Chat failed: " + r.status);
        });
      }
      return r.json();
    });
  },
  // ---- Chat sessions (SPEC-CSP-FE-001) ----
  listSessions: function () {
    return fetch(API_BASE + "/desktop/chat/sessions").then(function (r) {
      if (!r.ok) throw new Error("List sessions failed: " + r.status);
      return r.json();
    });
  },
  getSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id)).then(function (r) {
      if (!r.ok) throw new Error("Get session failed: " + r.status);
      return r.json();
    });
  },
  createSession: function (body) {
    return fetch(API_BASE + "/desktop/chat/sessions", {
      method: "POST",
      body: JSON.stringify(body || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Create session failed: " + r.status);
      return r.json();
    });
  },
  updateSession: function (id, patch) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id), {
      method: "PUT",
      body: JSON.stringify(patch || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Update session failed: " + r.status);
      return r.json();
    });
  },
  deleteSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id), {
      method: "DELETE",
    }).then(function (r) {
      if (!r.ok) throw new Error("Delete session failed: " + r.status);
      return r.json();
    });
  },
  appendMessages: function (id, payload) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) + "/messages", {
      method: "POST",
      body: JSON.stringify(payload || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Append messages failed: " + r.status);
      return r.json();
    });
  },
  updateMessage: function (id, msgId, patch) {
    return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(id) +
        "/messages/" + encodeURIComponent(msgId), {
      method: "PUT",
      body: JSON.stringify(patch || {}),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Update message failed: " + r.status);
      return r.json();
    });
  },
  setActiveSession: function (id) {
    return fetch(API_BASE + "/desktop/chat/active-session", {
      method: "PUT",
      body: JSON.stringify({ activeSessionId: id }),
      headers: { "Content-Type": "application/json" },
    }).then(function (r) {
      if (!r.ok) throw new Error("Set active session failed: " + r.status);
      return r.json();
    });
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
  getConfigHistory: function () {
    return fetch(API_BASE + "/desktop/config/history").then(function (r) {
      if (!r.ok) throw new Error("Config history fetch failed: " + r.status);
      return r.json();
    });
  },
  getConfigVersion: function (id) {
    return fetch(API_BASE + "/desktop/config/history/" + encodeURIComponent(id)).then(function (r) {
      if (!r.ok) throw new Error("Config version fetch failed: " + r.status);
      return r.json();
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
