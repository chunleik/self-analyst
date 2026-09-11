import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
const root = new URL("../../main/resources/", import.meta.url);

function parseCatalog(text, source) {
  const keys = new Set();
  for (const match of text.matchAll(/"(?:\\.|[^"\\])*"/g)) {
    if (!/^\s*:/.test(text.slice(match.index + match[0].length))) continue;
    const key = JSON.parse(match[0]);
    assert.ok(!keys.has(key), `${source}: duplicate key ${key}`);
    keys.add(key);
  }
  return JSON.parse(text);
}
function parameters(text) {
  return [...text.matchAll(/\{\{?(\w+)\}?}|%[sd]/g)].map(match => match[0]).sort();
}
function validate(english, catalog, source) {
  for (const key of new Set([...Object.keys(english), ...Object.keys(catalog)])) {
    assert.equal(typeof catalog[key], "string", `${source}: missing key ${key}`);
    assert.equal(typeof english[key], "string", `${source}: unexpected key ${key}`);
    assert.deepEqual(parameters(catalog[key]), parameters(english[key]), `${source}: parameters ${key}`);
  }
}

test("all production catalogs have complete keys and matching parameters", () => {
  const languages = JSON.parse(fs.readFileSync(new URL("i18n/languages.json", root), "utf8"));
  assert.deepEqual(languages.map(lang => lang.code).sort(), ["en", "zh"]);
  for (const domain of ["desktop-ui/locales/", "i18n/messages/", "i18n/native/"]) {
    const english = parseCatalog(fs.readFileSync(new URL(domain + "en.json", root), "utf8"), domain + "en");
    for (const lang of languages) {
      const source = domain + lang.resource;
      validate(english, parseCatalog(fs.readFileSync(new URL(source + ".json", root), "utf8"), source), source);
    }
  }
  const prompts = new URL("prompts/agent/", root);
  for (const file of fs.readdirSync(prompts).filter(file => file.endsWith(".en.md"))) {
    const english = fs.readFileSync(new URL(file, prompts), "utf8");
    for (const lang of languages) {
      const path = file.replace(/\.en\.md$/, "." + lang.resource + ".md");
      assert.deepEqual(parameters(fs.readFileSync(new URL(path, prompts), "utf8")), parameters(english), path);
    }
  }
});

test("catalog validation reports duplicates, omissions and parameter mismatches", () => {
  assert.throws(() => parseCatalog('{"key":"one","key":"two"}', "fr"), /fr: duplicate key key/);
  assert.throws(() => validate({ key: "{name}" }, {}, "fr"), /fr: missing key key/);
  assert.throws(() => validate({ key: "{name}" }, { key: "{other}" }, "fr"), /fr: parameters key/);
});
