import assert from "node:assert/strict";
import fs from "node:fs";

const indexHtml = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/index.html", import.meta.url),
  "utf8",
);
const desktopServer = fs.readFileSync(
  new URL("../../main/java/com/selfanalyst/desktop/DesktopServer.java", import.meta.url),
  "utf8",
);

assert.match(
  indexHtml,
  /<link rel="icon" type="image\/svg\+xml" href="favicon\.svg">/,
  "desktop UI should declare an SVG favicon for browser tabs",
);

const favicon = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/favicon.svg", import.meta.url),
  "utf8",
);

assert.match(favicon, /^<svg\b/);
assert.match(favicon, /viewBox="0 0 64 64"/);
assert.match(favicon, />SA</);

assert.match(
  desktopServer,
  /\.svg"\)\)\s*ctx\.contentType\("image\/svg\+xml"\)/,
  "desktop static server should serve SVG assets with the right content type",
);
