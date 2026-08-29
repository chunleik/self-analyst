import assert from "node:assert/strict";
import fs from "node:fs";

const styles = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/styles.css", import.meta.url),
  "utf8",
);

function cssRule(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = styles.match(new RegExp(escaped + "\\s*\\{([\\s\\S]*?)\\}"));
  assert.ok(match, `${selector} CSS rule should exist`);
  return match[1];
}

const chatTab = cssRule("#tab-chat");
assert.match(chatTab, /overflow:\s*hidden/, "chat tab must not become the scroll container");

const chatLayout = cssRule(".chat-tab-layout");
assert.match(
  chatLayout,
  /grid-template-rows:\s*minmax\(0,\s*1fr\)/,
  "chat grid row should stay within the available viewport height",
);
assert.match(chatLayout, /overflow:\s*hidden/, "chat grid must contain its independent scrollers");

const chatWorkspace = cssRule(".chat-workspace");
assert.match(chatWorkspace, /min-height:\s*0/, "chat workspace must be allowed to shrink");
assert.match(
  chatWorkspace,
  /overflow:\s*hidden/,
  "chat workspace should keep the composer inside the viewport",
);

const chatThread = cssRule(".chat-thread");
assert.match(chatThread, /overflow-y:\s*auto/, "fallback messages should scroll independently");

const composerTextarea = cssRule(".chat-composer textarea");

const minHeight = composerTextarea.match(/min-height:\s*(\d+)px/);
assert.ok(minHeight, "chat composer textarea should define min-height");
assert.ok(
  Number(minHeight[1]) >= 72,
  "chat composer textarea min-height should leave room for multi-line input",
);

const maxHeight = composerTextarea.match(/max-height:\s*(\d+)px/);
assert.ok(maxHeight, "chat composer textarea should define max-height");
assert.ok(
  Number(maxHeight[1]) >= 180,
  "chat composer textarea max-height should allow comfortable expansion",
);
