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
    fetch(url) {
      if (capture) capture.url = url;
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
