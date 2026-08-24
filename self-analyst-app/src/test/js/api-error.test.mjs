import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const apiJs = fs.readFileSync(
  path.resolve(here, "../../main/resources/desktop-ui/api.js"), "utf8");

function sandboxWith(response, capture) {
  const sandbox = {
    window: { location: { origin: "http://localhost:5700" } },
    URLSearchParams,
    TextDecoder,
    fetch(url, init) {
      if (capture) { capture.url = url; capture.init = init; }
      return Promise.resolve(response);
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(apiJs, sandbox);
  return sandbox;
}

test("chat API errors preserve HTTP status and server JSON message", async () => {
  const sandbox = sandboxWith({
    ok: false,
    status: 413,
    text() { return Promise.resolve('{"error":"payload too large"}'); },
  });

  await assert.rejects(sandbox.api.postChat("q", {}, null, null), (error) => {
    assert.equal(error.status, 413);
    assert.equal(error.message, "payload too large");
    assert.equal(error.body.error, "payload too large");
    return true;
  });
});

test("session list encodes optional pagination and search parameters", async () => {
  const capture = {};
  const sandbox = sandboxWith({
    ok: true,
    status: 200,
    json() { return Promise.resolve({ sessions: [] }); },
  }, capture);

  await sandbox.api.listSessions({ limit: 50, cursor: "a+b/c", q: "focus work" });

  const url = new URL(capture.url);
  assert.equal(url.searchParams.get("limit"), "50");
  assert.equal(url.searchParams.get("cursor"), "a+b/c");
  assert.equal(url.searchParams.get("q"), "focus work");
});

test("session API errors retain status when the body is plain text", async () => {
  const sandbox = sandboxWith({
    ok: false,
    status: 500,
    text() { return Promise.resolve("backend unavailable"); },
  });

  await assert.rejects(sandbox.api.getSession("abc"), (error) => {
    assert.equal(error.status, 500);
    assert.equal(error.message, "Get session failed: 500");
    assert.equal(error.body, "backend unavailable");
    return true;
  });
});

test("chat stream parses split SSE deltas and returns the canonical result", async () => {
  const encoder = new TextEncoder();
  const chunks = [
    'event: delta\ndata: {"text":"Hel',
    'lo"}\n\nevent: delta\ndata: {"text":" world"}\n\n',
    'event: result\ndata: {"message":"Hello world","suggestedTasks":[]}\n\n',
  ].map((value) => encoder.encode(value));
  let index = 0;
  const capture = {};
  const events = [];
  const signal = new AbortController().signal;
  const sandbox = sandboxWith({
    ok: true,
    status: 200,
    body: {
      getReader() {
        return { read() {
          return Promise.resolve(index < chunks.length
            ? { done: false, value: chunks[index++] }
            : { done: true });
        } };
      },
    },
  }, capture);

  const result = await sandbox.api.postChatStream(
    "q", { type: "global" }, "a".repeat(32), "1".repeat(12), {
      signal,
      onEvent(event, payload) { events.push([event, payload.text || payload.message]); },
    });

  assert.equal(capture.url, "http://localhost:5700/desktop/chat/stream");
  assert.equal(capture.init.signal, signal);
  assert.equal(capture.init.headers.Accept, "text/event-stream");
  assert.deepEqual(events, [
    ["delta", "Hello"],
    ["delta", " world"],
    ["result", "Hello world"],
  ]);
  assert.equal(result.message, "Hello world");
});

test("chat stream surfaces an SSE error with its status", async () => {
  const encoder = new TextEncoder();
  let read = false;
  const sandbox = sandboxWith({
    ok: true,
    status: 200,
    body: {
      getReader() {
        return { read() {
          if (read) return Promise.resolve({ done: true });
          read = true;
          return Promise.resolve({
            done: false,
            value: encoder.encode(
              'event: error\ndata: {"status":409,"error":"still running"}\n\n'),
          });
        } };
      },
    },
  });

  await assert.rejects(
    sandbox.api.postChatStream("q", {}, "a".repeat(32), "1".repeat(12), {}),
    (error) => error.status === 409 && error.message === "still running");
});

test("chat stream preserves CRLF split across reader chunks", async () => {
  const encoder = new TextEncoder();
  const chunks = [
    'event: delta\r\ndata: {"text":"Hi"}\r',
    '\n\r\nevent: result\r\ndata: {"message":"Hi","suggestedTasks":[]}\r\n\r\n',
  ].map((value) => encoder.encode(value));
  let index = 0;
  const events = [];
  const sandbox = sandboxWith({
    ok: true,
    status: 200,
    body: { getReader() { return {
      read() { return Promise.resolve(index < chunks.length
        ? { done: false, value: chunks[index++] } : { done: true }); },
      cancel() { return Promise.resolve(); },
    }; } },
  });

  const result = await sandbox.api.postChatStream(
    "q", {}, "a".repeat(32), "1".repeat(12), {
      onEvent(event, payload) { events.push([event, payload.text || payload.message]); },
    });
  assert.deepEqual(events, [["delta", "Hi"], ["result", "Hi"]]);
  assert.equal(result.message, "Hi");
});

test("chat stream cancels its reader when event handling fails", async () => {
  const encoder = new TextEncoder();
  let cancelled = 0;
  let read = false;
  const sandbox = sandboxWith({
    ok: true,
    status: 200,
    body: { getReader() { return {
      read() {
        if (read) return Promise.resolve({ done: true });
        read = true;
        return Promise.resolve({ done: false,
          value: encoder.encode('event: delta\ndata: {"text":"Hi"}\n\n') });
      },
      cancel() { cancelled += 1; return Promise.resolve(); },
    }; } },
  });

  await assert.rejects(sandbox.api.postChatStream(
    "q", {}, "a".repeat(32), "1".repeat(12), {
      onEvent() { throw new Error("render failed"); },
    }), /render failed/);
  assert.equal(cancelled, 1);
});

test("chat cancellation targets the persisted session endpoint", async () => {
  const capture = {};
  const sandbox = sandboxWith({
    ok: true,
    status: 202,
    json() { return Promise.resolve({ cancelRequested: true }); },
  }, capture);

  await sandbox.api.cancelChat("a".repeat(32), "1".repeat(12));

  assert.equal(capture.url,
    "http://localhost:5700/desktop/chat/sessions/" + "a".repeat(32) + "/cancel");
  assert.equal(capture.init.method, "POST");
  assert.deepEqual(JSON.parse(capture.init.body), { userMessageId: "1".repeat(12) });
});
