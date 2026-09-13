import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, readdirSync, lstatSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = fileURLToPath(new URL('../website/', import.meta.url));
const script = readFileSync(join(root, 'assets/language.js'), 'utf8');
function visit({ explicit, saved, language = 'en', blocked = false, href = 'https://example.com/self-analyst/' } = {}) {
  const result = {};
  vm.runInNewContext(script, {
    document: { documentElement: { dataset: { language: explicit } } },
    navigator: { languages: [language], language }, URL,
    location: { href, replace(value) { result.redirect = value; } },
    localStorage: {
      getItem() { if (blocked) throw Error('denied'); return saved; },
      setItem(key, value) { if (blocked) throw Error('denied'); result.saved = value; }
    }
  });
  return result;
}
test('首次访问选择中文，其他语言回退英文，保留仓库子路径', () => {
  for (const language of ['zh-CN', 'zh-TW', 'zh']) assert.equal(visit({ language }).redirect, 'https://example.com/self-analyst/zh-CN/');
  for (const language of ['en', 'fr', 'ja']) assert.equal(visit({ language }).redirect, 'https://example.com/self-analyst/en/');
});
test('偏好优先且显式页面不被偏好覆盖', () => {
  assert.match(visit({ saved: 'en', language: 'zh-CN' }).redirect, /\/en\/$/);
  assert.match(visit({ saved: 'invalid', language: 'zh-CN' }).redirect, /\/zh-CN\/$/);
  assert.deepEqual(visit({ explicit: 'zh-CN', saved: 'en' }), { saved: 'zh-CN' });
  assert.deepEqual(visit({ explicit: 'en', saved: 'zh-CN' }), { saved: 'en' });
});
test('存储失败仍可显示显式页面和选择浏览器语言', () => {
  assert.deepEqual(visit({ explicit: 'en', blocked: true }), {});
  assert.match(visit({ language: 'zh-CN', blocked: true }).redirect, /\/zh-CN\/$/);
  assert.match(visit({ href: 'https://example.com/self-analyst/index.html' }).redirect, /\/self-analyst\/en\/$/);
});
test('双语页面无需脚本即可提供完整正文和入口', () => {
  for (const language of ['en', 'zh-CN']) {
    const html = readFileSync(join(root, language, 'index.html'), 'utf8');
    assert.ok(html.includes(`lang="${language}" data-language="${language}"`));
    for (const id of ['main', 'features', 'privacy', 'start', 'download']) assert.ok(html.includes(`id="${id}"`));
    for (const target of ['releases/latest', 'releases', 'issues', 'blob/main/PRIVACY.md', 'blob/main/docs/llm-settings.md']) assert.ok(html.includes(`https://github.com/chunleik/self-analyst/${target}"`));
    assert.match(html, /<img[^>]+alt="[^"]+"/);
    assert.match(html, /<meta name="description" content="[^"]+"/);
    assert.equal((html.match(/<h1>/g) || []).length, 1);
  }
});
test('静态资源、语言链接及锚点均存在，不发布链接文件或内部数据', () => {
  function walk(directory) {
    for (const name of readdirSync(directory)) {
      const file = join(directory, name);
      assert.equal(lstatSync(file).isSymbolicLink(), false);
      if (lstatSync(file).isDirectory()) { walk(file); continue; }
      assert.match(name, /\.(html|css|js|png)$/);
      if (!name.endsWith('.html')) continue;
      const html = readFileSync(file, 'utf8');
      for (const [, attribute] of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
        if (attribute.startsWith('https://')) continue;
        if (attribute.startsWith('#')) { assert.ok(html.includes(`id="${attribute.slice(1)}"`)); continue; }
        assert.ok(!attribute.startsWith('/'), `绝对根路径会破坏仓库子路径: ${attribute}`);
        assert.ok(existsSync(resolve(dirname(file), attribute)), `${file}: ${attribute}`);
      }
    }
  }
  walk(root);
});
