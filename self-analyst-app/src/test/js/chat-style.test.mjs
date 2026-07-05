import assert from "node:assert/strict";
import fs from "node:fs";

const styles = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/styles.css", import.meta.url),
  "utf8",
);

const composerTextarea = styles.match(/\.chat-composer textarea\s*\{([\s\S]*?)\}/);
assert.ok(composerTextarea, "chat composer textarea CSS rule should exist");

const minHeight = composerTextarea[1].match(/min-height:\s*(\d+)px/);
assert.ok(minHeight, "chat composer textarea should define min-height");
assert.ok(
  Number(minHeight[1]) >= 72,
  "chat composer textarea min-height should leave room for multi-line input",
);

const maxHeight = composerTextarea[1].match(/max-height:\s*(\d+)px/);
assert.ok(maxHeight, "chat composer textarea should define max-height");
assert.ok(
  Number(maxHeight[1]) >= 180,
  "chat composer textarea max-height should allow comfortable expansion",
);
