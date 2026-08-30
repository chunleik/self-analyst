import assert from "node:assert/strict";
import fs from "node:fs";

const apiJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/api.js", import.meta.url),
  "utf8",
);
const appProps = fs.readFileSync(
  new URL("../../main/resources/application.properties", import.meta.url),
  "utf8",
);
const readme = fs.readFileSync(new URL("../../../../README.md", import.meta.url), "utf8");
const appPom = fs.readFileSync(new URL("../../../pom.xml", import.meta.url), "utf8");
const awServer = fs.readFileSync(
  new URL("../../../../self-analyst-aw/src/main/java/com/selfanalyst/aw/AwServer.java", import.meta.url),
  "utf8",
);
const portableBuild = fs.readFileSync(
  new URL("../../../../scripts/build-portable.ps1", import.meta.url),
  "utf8",
);

assert.doesNotMatch(apiJs, /var API_BASE = "http:\/\/localhost:5700"/);
assert.match(apiJs, /window\.location\.origin/);

assert.match(appProps, /^llm\.base-url=https:\/\/api\.openai\.com\/v1$/m);
assert.match(appProps, /^llm\.model=gpt-4o$/m);
assert.match(appProps, /^agent\.compaction\.enabled=true$/m);
assert.match(appProps, /^agent\.compaction\.triggerTokens=60000$/m);
assert.match(readme, /\| `llm\.base-url` \| `LLM_BASE_URL` \| `https:\/\/api\.openai\.com\/v1` \|/);
assert.match(readme, /\| `llm\.model` \| `LLM_MODEL` \| `gpt-4o` \|/);
assert.match(readme, /Node\.js 20\+/);

assert.match(appPom, /<id>desktop-ui-js-tests<\/id>/);
assert.match(appPom, /<phase>test<\/phase>/);
assert.match(appPom, /<executable>node<\/executable>/);
assert.match(appPom, /<skip>\$\{skipTests\}<\/skip>/);

assert.match(awServer, /\.start\("127\.0\.0\.1", port\)/);
assert.match(portableBuild, /data\/config\/config\.toml/);
assert.match(portableBuild, /\[Text\.UTF8Encoding\]::new\(\$false\)/);
