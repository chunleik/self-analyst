import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const apiJs = fs.readFileSync(
  path.resolve(here, "../../main/resources/desktop-ui/api.js"), "utf8");

function sandboxWith(response) {
  const sandbox = {
    window: { location: { origin: "http://localhost:5700" } },
    URLSearchParams,
    fetch() { return Promise.resolve(response); },
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
