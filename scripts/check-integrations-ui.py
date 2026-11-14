"""生产连接 UI 的合成浏览器回归；不访问真实 MCP/邮箱、不打包。

python scripts/check-integrations-ui.py --browser /usr/bin/chromium
输出截图与验证报告位于忽略的 artifacts/integrations-ui。
"""
import argparse
import copy
import functools
import json
from pathlib import Path
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parents[1]
UI = REPO / 'self-analyst-app/src/main/resources/desktop-ui'
BOOTSTRAP = (UI / 'init.js').read_text().split('var initializationPending')[0] + """
(async function () {
  cacheDom(); state.lang = new URLSearchParams(location.search).get('lang') || 'en';
  await loadI18n(state.lang); applyI18n(document); document.documentElement.lang = state.lang;
  setupEvents(); hideError(); state.configOpen = true; state.dom.configModal.classList.remove('hidden');
  await switchConfigView('integrations'); window.integrationsFixtureReady = true;
})();
"""


class FixtureHandler(SimpleHTTPRequestHandler):
    snapshot = None

    def respond_json(self, body, status=200):
        self.send_response(status); self.send_header('Content-Type', 'application/json'); self.end_headers()
        self.wfile.write(json.dumps(body).encode())

    def do_POST(self):
        # Serve-only mode supports the same synthetic operations for a cloud browser.
        path = urlsplit(self.path).path
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        value = type(self).snapshot
        if path == '/desktop/integrations/discover':
            value['statuses'][body['serverId']] = {'state': 'ready', 'catalogAvailable': True, 'credentialConfigured': True, 'tools': []}
        elif path == '/desktop/integrations/delete':
            if body['expectedRevision'] != value['config']['revision']:
                self.respond_json({'error': 'revision_conflict'}, 409); return
            collection = 'mailAccounts' if body['kind'] == 'mail' else 'mcpServers'
            value['config'][collection] = [item for item in value['config'][collection] if item['id'] != body['id']]
            value['config']['toolApprovals'] = [item for item in value['config']['toolApprovals'] if item['sourceId'] != body['id']]
            value['config']['revision'] += 1; value['statuses'].pop(body['id'], None)
        else:
            self.respond_json({'error': 'unsupported_synthetic_operation'}, 400); return
        self.respond_json(value)

    def do_GET(self):
        if urlsplit(self.path).path == '/init.js':
            type(self).snapshot = fixture()
            self.send_response(200); self.send_header('Content-Type', 'application/javascript; charset=utf-8'); self.end_headers()
            self.wfile.write(BOOTSTRAP.encode())
        elif urlsplit(self.path).path == '/desktop/integrations':
            self.respond_json(type(self).snapshot or fixture())
        elif urlsplit(self.path).path == '/desktop/integrations/approvals':
            self.respond_json({'approvals': []})
        elif urlsplit(self.path).path.startswith('/desktop/'):
            self.respond_json({})
        else:
            super().do_GET()

    def log_message(self, *_):
        pass


def fixture():
    def server(id, name):
        return {'id': id, 'name': name, 'enabled': False, 'transport': 'streamable-http',
                'endpoint': f'https://{id}.example.com/mcp', 'authType': 'anonymous',
                'credentialRef': None, 'storageMode': 'session',
                'budget': {'connectTimeoutSeconds': 10, 'requestTimeoutSeconds': 30, 'maxTools': 64,
                           'maxSchemaBytes': 32768, 'maxResultBytes': 262144}}
    tool = {'name': 'lookup_<img src=x onerror=alert(1)>', 'description': '<script>window.fixtureXss = true</script> Synthetic lookup.',
            'inputSchema': {'type': 'object', 'properties': {'query': {'type': 'string', 'description': 'Synthetic text ' * 15}}},
            'digest': 'a' * 64, 'approved': False}
    return {'config': {'schemaVersion': 1, 'revision': 4, 'mcpServers': [server('alpha', 'Alpha synthetic'), server('beta', 'Beta synthetic')],
                       'mailAccounts': [], 'toolApprovals': []},
            'statuses': {'alpha': {'state': 'ready', 'catalogAvailable': True, 'credentialConfigured': True, 'tools': [tool]},
                         'beta': {'state': 'not_connected', 'catalogAvailable': False, 'credentialConfigured': True, 'tools': []}},
            'persistentCredentialsSupported': False, 'credentialCleanupPending': False,
            'modelTarget': 'https://model.example.com/v1', 'legacyWebsearch': {}}


def check_browser(server, output, executable):
    from playwright.sync_api import sync_playwright, expect
    output.mkdir(parents=True, exist_ok=True)
    origin = f'http://127.0.0.1:{server.server_port}'
    errors, external, checks = [], [], []
    database, calls, mode = {}, [], {'discover': 'empty', 'delete': 'success'}
    with sync_playwright() as pw:
        options = {'headless': True}
        if executable:
            options['executable_path'] = executable
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={'width': 1280, 'height': 1000}, service_workers='block')

        def route_request(route):
            request = route.request; parsed = urlsplit(request.url); path = parsed.path
            if f'{parsed.scheme}://{parsed.netloc}' != origin:
                if parsed.netloc == 'fonts.googleapis.com':
                    route.fulfill(status=200, content_type='text/css', body='/* offline synthetic font stub */')
                else:
                    external.append(request.url); route.abort()
                return
            if not path.startswith('/desktop/'):
                route.continue_(); return
            if not path.startswith('/desktop/integrations'):
                route.fulfill(status=200, content_type='application/json', body='{}'); return
            suffix = path.removeprefix('/desktop/integrations')
            body = request.post_data_json if request.post_data else None
            calls.append({'path': suffix, 'method': request.method, 'body': body})
            status, result = 200, database
            if suffix == '/approvals':
                result = {'approvals': []}
            elif suffix == '/mail/folders':
                if mode.get('folders') == 'error':
                    status, result = 400, {'error': 'timeout'}
                else:
                    result = {'accountId': body['id'], 'revision': database['config']['revision'], 'complete': True,
                              'folders': [{'name': name, 'separator': '/', 'selectable': True} for name in
                                          ['INBOX', '工作/客户 A', '账单', '<script>window.mailXss = true</script>']]}
            elif suffix == '/mail/folders/cancel':
                result = {'cancelled': True}
            elif suffix == '' and request.method == 'PUT':
                assert body['expectedRevision'] == database['config']['revision']
                database['config'] = copy.deepcopy(body['config']); database['config']['revision'] += 1
            elif suffix == '/delete':
                if mode['delete'] == 'conflict':
                    status, result = 409, {'error': 'revision_conflict'}
                elif mode['delete'] == 'unknown':
                    status, result = 503, {'error': 'unavailable'}
                else:
                    assert set(body) == {'kind', 'id', 'expectedRevision'}, body
                    assert body['expectedRevision'] == database['config']['revision']
                    collection = 'mailAccounts' if body['kind'] == 'mail' else 'mcpServers'
                    database['config'][collection] = [item for item in database['config'][collection] if item['id'] != body['id']]
                    database['config']['toolApprovals'] = [item for item in database['config']['toolApprovals'] if item['sourceId'] != body['id']]
                    database['config']['revision'] += 1
                    database['statuses'].pop(body['id'], None)
                    database['credentialCleanupPending'] = mode['delete'] == 'cleanup'
            elif suffix == '/discover':
                if mode['discover'] == 'error':
                    status, result = 400, {'error': 'unavailable'}
                else:
                    database['statuses'][body['serverId']] = {'state': 'ready', 'catalogAvailable': True, 'credentialConfigured': True, 'tools': []}
            route.fulfill(status=status, content_type='application/json', body=json.dumps(result))

        context.route('**/*', route_request)
        page = context.new_page(); page.on('pageerror', lambda error: errors.append(str(error)))
        try:
            for lang in ['en', 'zh']:
                labels = json.loads((UI / f'locales/{lang}.json').read_text())
                def text(key): return labels[key]
                def button(key): return page.get_by_role('button', name=text(key), exact=True)
                def select(name): page.locator('.integrations-list').get_by_role('button', name=name, exact=True).click()
                def reset():
                    database.clear(); database.update(copy.deepcopy(fixture())); calls.clear(); mode.update(discover='empty', delete='success')
                    page.set_viewport_size({'width': 1280, 'height': 1000})
                    page.goto(f'{origin}/?lang={lang}'); page.wait_for_function('window.integrationsFixtureReady === true')
                    expect(page.locator('.integrations-list button')).to_have_count(3)
                def accepted_delete():
                    page.once('dialog', lambda dialog: dialog.accept()); button('integrations.action.delete').click()
                reset()
                # Empty drafts are independently removable without any persistence or network action.
                button('integrations.add.mcp').click(); button('integrations.add.mcp').click()
                expect(page.locator('.integrations-list button')).to_have_count(5)
                page.once('dialog', lambda dialog: dialog.dismiss()); button('integrations.action.delete').click()
                expect(page.locator('.integrations-list button')).to_have_count(5)
                accepted_delete(); expect(page.locator('.integrations-list button')).to_have_count(4)
                expect(page.locator('.config-scroll-area > [role="status"][tabindex="-1"]')).to_be_focused()
                assert not [c for c in calls if c['method'] != 'GET']
                # Deleting saved Alpha must not save Beta's name edit or the remaining incomplete draft.
                select('Beta synthetic'); page.locator('#integration-name').fill('Beta unsaved')
                select('Alpha synthetic'); accepted_delete()
                expect(page.locator('.integrations-list button')).to_have_count(3)
                expect(page.locator('.config-scroll-area > [role="status"][tabindex="-1"]')).to_be_focused()
                assert database['config']['mcpServers'][0]['name'] == 'Beta synthetic'
                select('Beta unsaved'); expect(page.locator('#integration-name')).to_have_value('Beta unsaved')
                assert len([c for c in calls if c['path'] == '/delete']) == 1
                assert not [c for c in calls if c['method'] == 'PUT']
                checks.append(f'{lang}: draft cancellation/isolated delete, saved delete preserves unrelated drafts')
                reset(); select('Alpha synthetic')
                trigger = button('integrations.catalog.open'); trigger.click()
                dialog = page.locator('dialog.integration-tools-dialog'); expect(dialog).to_be_visible()
                expect(dialog).to_contain_text(text('integrations.catalog.disclosure'))
                assert dialog.get_by_role('button', name=text('integrations.approveTool'), exact=True).count() == 0
                assert not [c for c in calls if c['path'] == '/discover']
                expect(dialog.locator('article')).to_have_count(1)
                expect(dialog.locator('h4')).to_contain_text('<img')
                assert dialog.locator('img, script, iframe').count() == 0
                assert page.evaluate('window.fixtureXss === undefined')
                dialog.locator('summary').click()
                expect(dialog.locator('pre')).to_contain_text('query')
                for key in ['Tab', 'Shift+Tab']:
                    for _ in range(12):
                        page.keyboard.press(key)
                        assert page.evaluate('document.querySelector("dialog.integration-tools-dialog").contains(document.activeElement)')
                for width, height in [(1280, 1000), (800, 600), (390, 700)]:
                    page.set_viewport_size({'width': width, 'height': height})
                    bounds = dialog.bounding_box()
                    assert bounds is not None and bounds['x'] >= 7 and bounds['y'] >= 7
                    assert abs(bounds['x'] + bounds['width'] / 2 - width / 2) <= 1
                    assert abs(bounds['y'] + bounds['height'] / 2 - height / 2) <= 1
                    page.screenshot(path=str(output / f'mcp-tools-{lang}-{width}x{height}.png'))
                    assert dialog.evaluate('e => e.scrollWidth <= e.clientWidth + 2'), f'{lang} {width} dialog overflows'
                page.keyboard.press('Escape'); expect(dialog).not_to_be_visible()
                expect(page.locator('.integrations-settings')).to_be_visible(); expect(trigger).to_be_focused()
                trigger.click(); expect(dialog).to_be_visible(); dialog.get_by_role('button', name=text('integrations.catalog.close'), exact=True).click()
                checks.append(f'{lang}: cached tools, safe text/schema, focus trap/restore, Escape/reopen, responsive screenshots')
                page.set_viewport_size({'width': 1280, 'height': 1000})
                # An edited unsaved endpoint must not inherit the saved endpoint's cached tools.
                page.locator('#integration-endpoint').fill('https://changed.example.com/mcp')
                button('integrations.catalog.open').click()
                expect(dialog.locator('article')).to_have_count(0)
                expect(dialog).to_contain_text(text('integrations.catalog.saveFirst'))
                expect(dialog.get_by_role('button', name=text('integrations.catalog.discover'), exact=True)).to_be_disabled()
                page.keyboard.press('Escape')
                page.once('dialog', lambda dialog: dialog.accept()); button('config.discard').click()
                select('Beta synthetic'); button('integrations.catalog.open').click()
                expect(dialog).to_contain_text(text('integrations.catalog.undiscovered'))
                dialog.get_by_role('button', name=text('integrations.catalog.discover'), exact=True).click()
                expect(dialog).to_contain_text(text('integrations.catalog.empty'))
                mode['discover'] = 'error'; dialog.get_by_role('button', name=text('integrations.catalog.refresh'), exact=True).click()
                expect(dialog.locator('[role="alert"]')).not_to_be_empty()
                assert page.evaluate('document.querySelector("dialog.integration-tools-dialog").contains(document.activeElement)')
                page.keyboard.press('Escape')
                expect(dialog).not_to_be_visible()
                expect(page.locator('.integrations-settings')).to_be_visible()
                expect(button('integrations.catalog.open')).to_be_focused()
                # A disabled connection can still have a cached, genuinely empty catalog.
                database['statuses']['beta']['state'] = 'disabled'
                button('config.discard').click(); select('Beta synthetic'); button('integrations.catalog.open').click()
                expect(dialog).to_contain_text(text('integrations.catalog.empty'))
                page.keyboard.press('Escape')
                # Hold a synthetic discovery promise to verify close suppresses a late response.
                page.evaluate("""() => { window.originalFixtureFetch = window.fetch; window.fetch = function(input, options) {
                  if (String(input).endsWith('/desktop/integrations/discover')) return new Promise(resolve => {
                    window.finishFixtureDiscovery = value => resolve(new Response(JSON.stringify(value), {status: 200, headers: {'Content-Type': 'application/json'}}));
                    window.fixtureSignal = options.signal;
                  });
                  return originalFixtureFetch(input, options);
                }; }""")
                button('integrations.catalog.open').click()
                dialog.get_by_role('button', name=text('integrations.catalog.refresh'), exact=True).click()
                page.wait_for_function('typeof window.finishFixtureDiscovery === "function"')
                page.keyboard.press('Escape'); assert page.evaluate('window.fixtureSignal.aborted')
                page.evaluate('(snapshot) => window.finishFixtureDiscovery(snapshot)', copy.deepcopy(database))
                expect(dialog).not_to_be_visible(); expect(page.locator('.integrations-settings')).to_be_visible()
                checks.append(f'{lang}: edited target cannot inherit cached tools; explicit discovery, undiscovered/empty/error/disabled cached, cancelled late response')
                reset(); select('Alpha synthetic'); mode['delete'] = 'conflict'; accepted_delete()
                expect(page.locator('.integrations-settings > .config-scroll-area > [role="alert"]')).to_contain_text(text('integrations.error.conflict'))
                expect(button('integrations.action.delete')).to_be_enabled()
                mode['delete'] = 'unknown'; accepted_delete()
                expect(page.locator('.integrations-settings')).to_contain_text(text('integrations.deleteUnknown'))
                expect(button('integrations.action.delete')).to_be_disabled(); expect(button('integrations.add.mcp')).to_be_disabled()
                page.once('dialog', lambda dialog: dialog.accept()); button('config.discard').click()
                expect(button('integrations.add.mcp')).to_be_enabled()
                select('Alpha synthetic'); mode['delete'] = 'cleanup'; accepted_delete()
                expect(page.locator('.integrations-settings')).to_contain_text(text('integrations.notice.cleanupPending'))
                expect(page.locator('.integrations-list button')).to_have_count(2)
                checks.append(f'{lang}: revision conflict, unknown outcome write lock/reload, credential cleanup warning')
                # Folder discovery is a local setup operation, not a mail tool or an activation.
                reset()
                database['config']['mailAccounts'] = [{'id': 'mail-fixture', 'provider': '163', 'email': 'synthetic@163.com',
                    'displayName': 'Synthetic mailbox', 'enabled': False, 'folderScope': 'all', 'allowedFolders': ['INBOX'],
                    'bodyAccess': False, 'disclosureVersion': 0, 'storageMode': 'windows',
                    'credentialRef': 'windows:00000000-0000-0000-0000-000000000001'}]
                database['statuses']['mail-fixture'] = {'state': 'not_connected', 'credentialConfigured': True}
                database['persistentCredentialsSupported'] = True
                button('config.discard').click(); select('Synthetic mailbox')
                choices = page.locator('.integration-folder-list input[type=checkbox]')
                expect(choices).to_have_count(4)
                assert len([c for c in calls if c['path'] == '/mail/folders']) == 1
                assert not [c for c in calls if c['path'] == '/mail/activate']
                expect(page.locator('#integration-enabled, #integration-persist, #integration-disclosureVersion, #integration-allowedFolders')).to_have_count(0)
                expect(page.locator('#integration-bodyAccess')).not_to_be_checked()
                assert page.locator('.integration-folder-list script, .integration-folder-list img').count() == 0
                assert page.evaluate('window.mailXss === undefined')
                target = page.locator('.integration-folder-list input[data-folder-name="工作/客户 A"]')
                target.focus(); page.keyboard.press('Space')
                expect(page.locator('#integration-folderScope')).to_have_value('selected')
                expect(target).not_to_be_checked(); expect(target).to_be_focused()
                page.locator('#integration-folder-search').fill('INBOX'); expect(choices).to_have_count(1)
                page.locator('#integration-folder-search').fill(''); expect(choices).to_have_count(4)
                button('config.saveChanges').click()
                expect(button('config.saveChanges')).to_be_disabled()
                assert database['config']['mailAccounts'][0]['allowedFolders'] == ['INBOX', '账单', '<script>window.mailXss = true</script>']
                expect(target).not_to_be_checked()
                mode['folders'] = 'error'; button('integrations.folders.refresh').click()
                expect(page.locator('.integration-folder-picker')).to_contain_text(text('integrations.error.timeout'))
                expect(target).not_to_be_checked(); expect(button('integrations.folders.selectCurrent')).to_be_disabled()
                mode['folders'] = 'success'; button('integrations.folders.refresh').click()
                expect(button('integrations.folders.selectCurrent')).to_be_enabled()
                for scheme in ['light', 'dark']:
                    page.emulate_media(color_scheme=scheme)
                    for width, height in [(1280, 1000), (390, 800)]:
                        page.set_viewport_size({'width': width, 'height': height})
                        page.locator('.integration-folder-picker').scroll_into_view_if_needed()
                        assert page.locator('.integration-folder-picker').evaluate('e => e.scrollWidth <= e.clientWidth + 2')
                        page.screenshot(path=str(output / f'mail-folders-{lang}-{scheme}-{width}x{height}.png'))
                checks.append(f'{lang}: mail auto-discovery, explicit selection, safe text, Space/focus, search preservation, save/refresh/error recovery, light/dark media and narrow screenshots')
                page.emulate_media(color_scheme='light')

            assert not errors, errors
            assert not external, external
            (output / 'validation.json').write_text(json.dumps({'syntheticData': True, 'productionAssets': str(UI.relative_to(REPO)),
                'checks': checks, 'pageErrors': errors, 'externalRequests': external,
                'limitations': ['No real backend, MCP service, mailbox, Windows or Tauri validation.', 'Dark media preference is exercised; production theme currently remains its shared light palette.']}, ensure_ascii=False, indent=2) + '\n')
        except Exception:
            page.screenshot(path=str(output / 'failure.png')); raise
        finally:
            context.close(); browser.close()
    print('PASS: synthetic connection deletion, tools dialog and mailbox folder picker, EN/ZH, keyboard, late responses, light/dark media and narrow screenshots. No real services or packaging.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=REPO / 'artifacts/integrations-ui')
    parser.add_argument('--browser', help='Optional existing Chromium path; default uses Playwright-managed Chromium')
    parser.add_argument('--serve-only', action='store_true')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(FixtureHandler, directory=str(UI)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        if args.serve_only:
            print(f'http://127.0.0.1:{server.server_port}/?lang=en', flush=True)
            threading.Event().wait()
        else:
            check_browser(server, args.output_dir, args.browser)
    finally:
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
