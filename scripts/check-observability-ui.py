"""Production developer-observability UI with synthetic API snapshots only.

python scripts/check-observability-ui.py --output-dir artifacts/observability-ui
Uses Playwright-managed Chromium unless --browser names an existing executable.
Never connects to Langfuse, an MCP server, a model or a mailbox; all APIs are mocked.
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
  state.activeChatSessionId = 'synthetic-session'; setupEvents(); hideError();
  state.configOpen = true; state.dom.configModal.classList.remove('hidden');
  await switchConfigView('developer'); window.observabilityFixtureReady = true;
})();
"""


class FixtureHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if urlsplit(self.path).path == '/init.js':
            self.send_response(200); self.send_header('Content-Type', 'application/javascript; charset=utf-8'); self.end_headers()
            self.wfile.write(BOOTSTRAP.encode())
        else:
            super().do_GET()

    def log_message(self, *_):
        pass


def fixture():
    return {'config': {'schemaVersion': 1, 'revision': 3, 'endpoint': 'https://synthetic.example.com/api/public/otel/v1/traces',
                       'publicKey': 'pk-synthetic', 'sampleRate': 1, 'captureMode': 'metadata', 'categories': [],
                       'toolSources': [], 'mailAccounts': [], 'scope': 'all-runtime', 'sessionId': '', 'caCertificatePem': ''},
            'enabled': False, 'credentialConfigured': False, 'persistentCredentialsSupported': True,
            'credentialCleanupPending': False,
            'status': {'state': 'disabled', 'accepted': 0, 'dropped': 0, 'failed': 0, 'lastError': ''}}


def check_browser(server, output, executable):
    from playwright.sync_api import sync_playwright, expect
    output.mkdir(parents=True, exist_ok=True)
    origin = f'http://127.0.0.1:{server.server_port}'
    errors, external, checks = [], [], []
    database, calls, mode = {}, [], {'mutation': 'success', 'sources': 'success', 'preview': 'success', 'previewToken': None}
    synthetic_secret = 'SYNTHETIC-NOT-A-REAL-SECRET'
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
            if path == '/desktop/integrations':
                status = 503 if mode['sources'] == 'error' else 200
                result = {'config': {'mcpServers': [{'id': 'mcp-synthetic', 'name': '<img src=x onerror=alert(1)> Synthetic MCP'}],
                                     'mailAccounts': [{'id': 'mail-synthetic', 'displayName': 'Synthetic mailbox'}]}}
                route.fulfill(status=status, content_type='application/json', body=json.dumps(result)); return
            if not path.startswith('/desktop/observability'):
                route.fulfill(status=200, content_type='application/json', body='{}'); return
            suffix = path.removeprefix('/desktop/observability')
            body = request.post_data_json if request.post_data else None
            calls.append({'path': suffix, 'method': request.method, 'body': copy.deepcopy(body)})
            status, result = 200, database
            if suffix == '/preview':
                assert request.method == 'POST' and set(body) == {'expectedRevision'}
                assert body['expectedRevision'] == database['config']['revision']
                if mode['preview'] == 'error':
                    status, result = 400, {'error': 'unsafe_destination'}
                else:
                    mode['previewToken'] = 'synthetic-preview-' + str(len(calls))
                    result = {'destination': {'endpoint': database['config']['endpoint'], 'publicKey': database['config']['publicKey'],
                                             'category': 'public', 'addresses': ['203.0.113.17'], 'caFingerprint': 'system-trust'},
                              'confirmationToken': mode['previewToken']}
            elif request.method != 'GET':
                if mode['mutation'] == 'conflict':
                    status, result = 409, {'error': 'revision_conflict'}
                elif mode['mutation'] == 'unknown' and suffix != '/disable':
                    status, result = 503, {'error': 'unavailable'}
                else:
                    assert body['expectedRevision'] == database['config']['revision']
                    if request.method == 'PUT':
                        assert set(body) == {'expectedRevision', 'config'}
                        assert synthetic_secret not in json.dumps(body)
                        database['config'] = copy.deepcopy(body['config']); database['config']['revision'] += 1
                        database['enabled'] = False
                    elif suffix == '/credential':
                        assert set(body) == {'expectedRevision', 'secret'} and body['secret'] == synthetic_secret
                        database['credentialConfigured'] = True; database['config']['revision'] += 1
                    elif suffix == '/enable':
                        assert set(body) == {'expectedRevision', 'disclosureVersion', 'confirmationToken'} and body['disclosureVersion'] == 1
                        assert body['confirmationToken'] == mode['previewToken'] and mode['previewToken']
                        mode['previewToken'] = None; database['enabled'] = True
                    elif suffix == '/disable':
                        database['enabled'] = False
                    elif suffix == '/test':
                        assert set(body) == {'expectedRevision', 'confirmationToken'}
                        assert body['confirmationToken'] == mode['previewToken'] and mode['previewToken']
                        mode['previewToken'] = None; database['testAccepted'] = True
                    elif suffix == '/disconnect':
                        database['enabled'] = False; database['credentialConfigured'] = False; database['config']['revision'] += 1
                        database['credentialCleanupPending'] = mode['mutation'] == 'cleanup'
                    else:
                        raise AssertionError(f'Unexpected synthetic action {suffix}')
                    database['status']['state'] = 'enabled' if database['enabled'] else 'disabled'
            assert synthetic_secret not in json.dumps(result)
            route.fulfill(status=status, content_type='application/json', body=json.dumps(result))

        context.route('**/*', route_request)
        page = context.new_page(); page.on('pageerror', lambda error: errors.append(str(error)))
        try:
            for lang in ['en', 'zh']:
                labels = json.loads((UI / f'locales/{lang}.json').read_text())
                def text(key): return labels[key]
                def button(key): return page.get_by_role('button', name=text(key), exact=True)
                def reset(vault=True):
                    database.clear(); database.update(copy.deepcopy(fixture())); database['persistentCredentialsSupported'] = vault
                    calls.clear(); mode.update(mutation='success', sources='success', preview='success', previewToken=None)
                    page.set_viewport_size({'width': 1280, 'height': 1000})
                    page.goto(f'{origin}/?lang={lang}'); page.wait_for_function('window.observabilityFixtureReady === true')
                    expect(page.locator('#observability-endpoint')).to_be_enabled()
                def confirm_action(action, accept=True):
                    messages = []
                    def reply(dialog):
                        messages.append(dialog.message)
                        dialog.accept() if accept else dialog.dismiss()
                    page.once('dialog', reply)
                    with page.expect_event('dialog'):
                        button('observability.action.' + action).click()
                    assert messages and database['config']['endpoint'] in messages[0] and database['config']['publicKey'] in messages[0]
                    assert synthetic_secret not in messages[0]
                    if action in ['enable', 'test']:
                        assert '203.0.113.17' in messages[0] and 'system-trust' in messages[0] and 'public' in messages[0]
                    return messages[0]
                reset()
                section = page.locator('.observability-settings')
                expect(page.locator('#observability-captureMode')).to_have_value('metadata')
                assert not database['enabled'] and not [c for c in calls if c['method'] != 'GET']
                for checkbox in section.get_by_role('checkbox').all():
                    expect(checkbox).not_to_be_checked(); expect(checkbox).to_be_disabled()
                assert section.locator('img, script, iframe').count() == 0
                expect(section).to_contain_text('<img src=x onerror=alert(1)>')
                expect(section).to_contain_text(text('observability.images'))
                page.locator('#observability-sampleRate').fill('0.5')
                page.locator('#observability-sampleRate').press('Tab'); expect(page.locator('#observability-captureMode')).to_be_focused()
                button('config.saveChanges').click()
                expect(button('config.saveChanges')).to_be_disabled()
                assert database['config']['sampleRate'] == 0.5 and not database['enabled']
                assert [c['method'] for c in calls if c['method'] != 'GET'] == ['PUT']
                page.locator('#observability-secret').fill(synthetic_secret)
                expect(page.locator('#observability-secret')).to_have_attribute('type', 'password')
                button('observability.action.credential').click(); expect(page.locator('#observability-secret')).to_have_value('')
                expect(button('observability.action.enable')).to_be_enabled()
                assert synthetic_secret not in section.inner_text()
                count = len(calls); confirm_action('test', False)
                assert [call['path'] for call in calls[count:]] == ['/preview']
                confirm_action('test'); expect(section).to_contain_text(text('observability.notice.test'))
                assert not database['enabled']
                count = len(calls); confirm_action('enable', False)
                assert [call['path'] for call in calls[count:]] == ['/preview']
                # Failed preview must never fall back to confirmation or enable/test.
                mode['preview'] = 'error'; count = len(calls); unexpected_confirmations = []
                def reject_unexpected(dialog):
                    unexpected_confirmations.append(dialog.message); dialog.dismiss()
                page.on('dialog', reject_unexpected)
                button('observability.action.enable').click()
                expect(section).to_contain_text(text('observability.error.unsafe_destination'))
                page.remove_listener('dialog', reject_unexpected)
                assert not unexpected_confirmations and not database['enabled']
                assert [call['path'] for call in calls[count:]] == ['/preview']
                mode['preview'] = 'success'
                confirm_action('enable'); expect(section).to_contain_text(text('observability.notice.enable'))
                assert database['enabled']
                confirm_action('disable'); expect(section).to_contain_text(text('observability.notice.disable'))
                checks.append(f'{lang}: default off, metadata save/test/enable separated, recipient IP/CA preview consent, cancellation/failed preview never enables, secret write-only, safe source labels')
                # Content categories, source IDs and session scope must be shown in the consent, not inferred.
                page.locator('#observability-captureMode').select_option('content')
                section.get_by_role('checkbox', name=text('observability.category.modelText'), exact=True).check()
                section.get_by_role('checkbox', name=text('observability.category.toolText'), exact=True).check()
                section.get_by_role('checkbox', name=text('observability.category.mailBody'), exact=True).check()
                section.get_by_role('checkbox', name='<img src=x onerror=alert(1)> Synthetic MCP (mcp-synthetic)', exact=True).check()
                section.get_by_role('checkbox', name='Synthetic mailbox (mail-synthetic)', exact=True).check()
                page.locator('#observability-scope').select_option('session'); button('observability.useCurrentSession').click()
                expect(page.locator('#observability-sessionId')).to_have_value('synthetic-session')
                button('config.saveChanges').click(); expect(button('config.saveChanges')).to_be_disabled()
                assert not database['enabled']
                consent = confirm_action('enable')
                assert all(value in consent for value in ['mcp-synthetic', 'mail-synthetic', 'synthetic-session', text('observability.category.mailBody')])
                expect(section).to_contain_text(text('observability.notice.enable'))
                for width, height in [(1280, 1000), (800, 600), (390, 700)]:
                    page.set_viewport_size({'width': width, 'height': height})
                    scroll = section.locator('.config-scroll-area')
                    scroll.evaluate('e => {e.scrollTop = 0;}')
                    page.screenshot(path=str(output / f'observability-{lang}-{width}x{height}.png'))
                    scroll.evaluate('e => {e.scrollTop = e.scrollHeight;}')
                    page.screenshot(path=str(output / f'observability-{lang}-{width}x{height}-bottom.png'))
                    assert scroll.evaluate('e => e.scrollWidth <= e.clientWidth + 2'), f'{lang} {width} scroll overflow'
                page.set_viewport_size({'width': 1280, 'height': 1000})
                mode['mutation'] = 'cleanup'; confirm_action('disconnect')
                expect(section).to_contain_text(text('observability.notice.cleanupPending'))
                assert not database['enabled'] and not database['credentialConfigured']
                checks.append(f'{lang}: scoped content consent, images limitation, disconnect cleanup warning, responsive screenshots')
                reset(vault=False)
                expect(page.locator('#observability-secret')).to_be_disabled()
                expect(button('observability.action.credential')).to_be_disabled()
                expect(section).to_contain_text(text('observability.vaultUnavailable'))
                reset(); page.locator('#observability-publicKey').fill('pk-conflict'); mode['mutation'] = 'conflict'
                button('config.saveChanges').click(); expect(section).to_contain_text(text('observability.error.revision_conflict'))
                expect(page.locator('#observability-publicKey')).to_have_value('pk-conflict')
                mode['mutation'] = 'unknown'; button('config.saveChanges').click()
                expect(section).to_contain_text(text('observability.unknown')); expect(button('config.saveChanges')).to_be_disabled()
                expect(button('observability.action.disable')).to_be_enabled()
                confirm_action('disable'); expect(button('config.saveChanges')).to_be_disabled()
                page.once('dialog', lambda dialog: dialog.accept()); button('config.discard').click()
                expect(page.locator('#observability-publicKey')).to_have_value('pk-synthetic')
                expect(page.locator('#observability-publicKey')).to_be_enabled()
                checks.append(f'{lang}: unavailable secure vault, conflict preserves draft, unknown write lock, safety disable, explicit reload')
                mode['sources'] = 'error'; button('config.discard').click()
                expect(section).to_contain_text(text('observability.sourcesError'))
                assert not [box for box in section.get_by_role('checkbox').all() if box.is_checked()]
                checks.append(f'{lang}: source listing failure is explicit and grants no scope')
                # Resolve a held GET after closing to verify the destroyed view cannot be repopulated.
                page.evaluate("""() => { const original = window.fetch; window.fetch = function(input, options) {
                  if (String(input).endsWith('/desktop/observability') && (!options || options.method === 'GET')) return new Promise(resolve => {
                    window.fixtureReadSignal = options.signal;
                    window.finishFixtureRead = value => resolve(new Response(JSON.stringify(value), {status:200, headers:{'Content-Type':'application/json'}}));
                  });
                  return original(input, options);
                }; }""")
                button('config.discard').click(); page.wait_for_function('typeof window.finishFixtureRead === "function"')
                page.locator('#config-close-btn').click(); assert page.evaluate('window.fixtureReadSignal.aborted')
                page.evaluate('(value) => window.finishFixtureRead(value)', copy.deepcopy(database))
                expect(section).to_have_count(0)
                checks.append(f'{lang}: closed view cancels reads and ignores late results')
            assert not errors, errors
            assert not external, external
            (output / 'validation.json').write_text(json.dumps({'syntheticData': True, 'checks': checks,
                'pageErrors': errors, 'externalRequests': external,
                'limitations': ['No real Langfuse, native secure store, Windows/Tauri or backend integration validation.']}, ensure_ascii=False, indent=2) + '\n')
        except Exception:
            page.screenshot(path=str(output / 'failure.png')); raise
        finally:
            context.close(); browser.close()
    print('PASS: synthetic developer observability UI, EN/ZH consent, secrets, conflicts, cancellation and narrow layouts.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=REPO / 'artifacts/observability-ui')
    parser.add_argument('--browser', help='Optional existing Chromium path')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(FixtureHandler, directory=str(UI)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        check_browser(server, args.output_dir, args.browser)
    finally:
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
