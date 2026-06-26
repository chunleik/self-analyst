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
  getSummary: function () {
    return fetch(API_BASE + "/desktop/summary").then(function (r) {
      if (!r.ok) throw new Error("Summary fetch failed: " + r.status);
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
  postChat: function (msg, ctx) {
    return fetch(API_BASE + "/desktop/chat", {
      method: "POST",
      body: JSON.stringify({ message: msg, context: ctx }),
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
