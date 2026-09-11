import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const read = name => fs.readFileSync(new URL("../../main/resources/desktop-ui/" + name, import.meta.url), "utf8");
function sandbox() {
  const context = { state: { lang: "zh" }, setTimeout, clearTimeout, AbortController };
  vm.createContext(context);
  vm.runInContext(read("i18n.js"), context);
  context.MESSAGES = Object.fromEntries(["en", "zh"].map(code => [code, JSON.parse(read("locales/" + code + ".json"))]));
  return context;
}

test("resource fallback and parameters preserve user data", () => {
  const ctx = sandbox();
  ctx.MESSAGES.en.example = "Value: {value}";
  assert.equal(ctx.t("example", { value: "{other}<script>$1\\" }), "Value: {other}<script>$1\\");
  assert.equal(ctx.t("unknown"), "unknown");
  ctx.state.lang = "fr";
  ctx.MESSAGES.fr = { example: "Valeur : {value}" };
  assert.equal(ctx.t("example", { value: "原文" }), "Valeur : 原文");
  assert.equal(ctx.t("tab.chat"), "Chat");
});

test("API errors translate known codes and retain legacy diagnostics", () => {
  const ctx = sandbox();
  ctx.window = { location: { origin: "http://localhost" } };
  vm.runInContext(read("api.js"), ctx);
  ctx.state.lang = "en";
  assert.equal(ctx.apiErrorMessage({ errorCode: "error.file.pathMissing", errorParams: { path: "D:/中文" }, error: "旧文案" }, "fallback"),
    "The watched folder does not exist or is not a directory: D:/中文");
  assert.equal(ctx.apiErrorMessage({ errorCode: "unknown", error: "legacy" }, "fallback"), "Operation failed: legacy");
});

test("language resources load and unavailable language falls back to English", async () => {
  const ctx = sandbox();
  ctx.fetch = async url => ({ ok: url.includes("en.json"), json: async () => JSON.parse(read("locales/en.json")) });
  await ctx.loadI18n("fr");
  ctx.state.lang = "fr";
  assert.equal(ctx.t("tab.chat"), "Chat");
  await assert.rejects(ctx.loadLocale("../config"));
});

test("initial status failure can recover once without duplicate initialization", async () => {
  const ctx = sandbox();
  let failures = true, starts = 0, bindings = 0;
  ctx.document = { readyState: "loading", addEventListener() {}, documentElement: {}, querySelectorAll: () => [] };
  ctx.api = { getStatus: async () => { if (failures) throw Error("offline"); return { language: "zh", dateLocale: "zh-CN" }; } };
  ctx.state.dom = { errorRetryBtn: { removeEventListener() {} } };
  ctx.showError = () => {};
  ctx.hideError = () => {};
  ctx.setupEvents = () => { bindings++; };
  ctx.switchTab = () => {};
  ctx.loadChatSessions = async () => {};
  ctx.renderChatTab = () => {};
  ctx.loadAll = () => {};
  ctx.startAutoRefresh = () => { starts++; };
  ctx.loadI18n = async () => {};
  vm.runInContext(read("init.js"), ctx);
  await ctx.initializeLanguage();
  assert.equal(starts, 0);
  failures = false;
  await Promise.all([ctx.initializeLanguage(), ctx.initializeLanguage()]);
  await ctx.initializeLanguage();
  assert.equal(starts, 1);
  assert.equal(bindings, 1);
  assert.equal(ctx.state.lang, "zh");
  assert.equal(ctx.document.documentElement.lang, "zh");
});

test("language selection edits only its scalar and preserves pending TOML", () => {
  const ctx = sandbox();
  ctx.state.status = { languages: [{ code: "zh" }, { code: "en" }] };
  vm.runInContext(read("config.js"), ctx);
  const text = '# keep\r\n[app]\r\nlanguage = \'zh\' # keep\r\n[llm]\r\nmodel="pending"\r\n';
  assert.equal(ctx.editLanguageDraft(text, "en"), text.replace("'zh'", '"en"'));
  assert.equal(ctx.editLanguageDraft('[app]\nlanguage="zh"\nlanguage="en"', "en"), null);
  assert.equal(ctx.editLanguageDraft('[app]\nlanguage="unterminated', "en"), null);
  assert.equal(ctx.editLanguageDraft('app={language="zh"}', "en"), null);
  assert.equal(ctx.editLanguageDraft('app="occupied"', "en"), null);
  assert.equal(ctx.editLanguageDraft('[app.language]\nvalue="occupied"', "en"), null);
  assert.equal(ctx.editLanguageDraft('[app.language.child]', "en"), null);
  assert.equal(ctx.editLanguageDraft('app.language.value="occupied"', "en"), null);
  assert.match(ctx.editLanguageDraft('[llm]\nmodel="pending"', "en"), /\[app\]\nlanguage = "en"/);
  assert.equal(ctx.editLanguageDraft(text, "fr"), null);
});
