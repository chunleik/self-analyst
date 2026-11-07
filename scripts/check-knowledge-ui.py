"""用生产静态资源和合成 API 数据验证知识页；不启动后端、不打包、不访问外网。

KNOWLEDGE_BROWSER=/usr/bin/chromium python scripts/check-knowledge-ui.py
默认截图和报告位于 artifacts/knowledge-ui；--screenshots-dir 可指定 PR 图片目录。
复用 check-privacy-ui.py 所用的 Playwright；截图不是 Tauri 或真实数据集成证据。
"""
import argparse
import functools
import json
import os
from pathlib import Path
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

REPO = Path(__file__).resolve().parents[1]
UI = REPO / 'self-analyst-app/src/main/resources/desktop-ui'
BOOTSTRAP = (UI / 'init.js').read_text().split('var initializationPending')[0] + """
(async function () {
  cacheDom(); state.lang = new URLSearchParams(location.search).get('lang') || 'en';
  await loadI18n(state.lang); applyI18n(document); document.documentElement.lang = state.lang;
  setupEvents(); hideError(); switchTab('knowledge'); window.knowledgeFixtureReady = true;
})();
"""

def entity(id, name, type='project', source='manual'):
    return dict(id=id, name=name, type=type, source=source, aliases=['Atlas'] if id == 'p1' else [],
                description='Synthetic demonstration data' if id == 'p1' else '', evidence=[])

ENTITIES = [entity('p1', 'Atlas workspace'), entity('p2', 'Research notebook'),
            entity('g1', 'Build a sustainable learning practice', 'goal', 'wiki'),
            entity('p3', 'Archive notebook')]
ENTITIES += [entity(f'a{i}', f'Atlas activity {i:02d}', 'activity', 'capture') for i in range(1, 38)]
RELATIONS = [dict(other=ENTITIES[4+i], assertion=dict(subject=f'a{i+1}', object='p1', predicate='relatedTo',
    status='candidate' if i == 3 else 'accepted', claimType=['confirmed', 'inferred', 'observed', 'inferred'][i % 4],
    source='alias-rule' if i % 4 == 1 else 'capture', evidence=[])) for i in range(12)]
RELATIONS.insert(0, dict(other=ENTITIES[2], assertion=dict(subject='p1', object='g1', predicate='supports',
    status='accepted', claimType='confirmed', source='manual', evidence=[])))
REQUESTS = []
COVERAGE = {'wiki': 'current', 'memory': 'current'}

def paginated(items, params):
    offset, limit = int(params.get('offset', ['0'])[0]), int(params.get('limit', ['30'])[0])
    return dict(items=items[offset:offset+limit], offset=offset, limit=limit, total=len(items), hasMore=offset+limit < len(items), coverage=COVERAGE)

class FixtureHandler(SimpleHTTPRequestHandler):
    def respond(self, content_type, body):
        self.send_response(200); self.send_header('Content-Type', content_type); self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        url = urlsplit(self.path); params = parse_qs(url.query)
        if url.path == '/init.js':
            self.respond('application/javascript; charset=utf-8', BOOTSTRAP)
        elif url.path.startswith('/desktop/'):
            REQUESTS.append(dict(method='GET', path=url.path, params=params))
            if url.path == '/desktop/ontology/entities':
                items = [e for e in ENTITIES if params.get('q', [''])[0].lower() in e['name'].lower()
                         and (not params.get('type') or e['type'] == params['type'][0])
                         and (params.get('unclassified') != ['true'] or e['type'] == 'activity')]
                result = paginated(items, params)
            elif url.path.startswith('/desktop/ontology/entities/'):
                id = url.path.rsplit('/', 1)[1]
                result = dict(entity=next(e for e in ENTITIES if e['id'] == id), classification='ambiguous',
                              relations=paginated(RELATIONS if id == 'p1' else [], params))
            else:
                result = {}
            self.respond('application/json', json.dumps(result))
        else:
            super().do_GET()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}')
        REQUESTS.append(dict(method='POST', path=self.path, body=body))
        result = {}
        if self.path.endswith('/merge'):
            result = next(e for e in ENTITIES if e['id'] == body['to'])
            ENTITIES[:] = [e for e in ENTITIES if e['id'] != body['from']]
        elif self.path.endswith('/relations'):
            for relation in list(RELATIONS):
                a = relation['assertion']
                if all(a[k] == body[k] for k in ['subject', 'object', 'predicate']):
                    if body['action'] == 'reject': RELATIONS.remove(relation)
                    else: a.update(claimType='confirmed', status='accepted')
        self.respond('application/json', json.dumps(result))

    def do_DELETE(self):
        REQUESTS.append(dict(method='DELETE', path=self.path))
        ENTITIES[:] = [e for e in ENTITIES if e['id'] != self.path.rsplit('/', 1)[1]]
        self.respond('application/json', '{}')

    def log_message(self, *_):
        pass


def check_browser(server, output, screenshots):
    from playwright.sync_api import sync_playwright, expect
    output.mkdir(parents=True, exist_ok=True); screenshots.mkdir(parents=True, exist_ok=True)
    origin = f'http://127.0.0.1:{server.server_port}'
    errors, external, fonts, checks = [], [], [], []
    with sync_playwright() as pw:
        options = {'headless': True}
        if os.environ.get('KNOWLEDGE_BROWSER'): options['executable_path'] = os.environ['KNOWLEDGE_BROWSER']
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={'width': 1280, 'height': 1000}, service_workers='block', color_scheme='light')
        def offline(route):
            if route.request.url.startswith(origin + '/'):
                route.continue_()
            elif route.request.url.startswith('https://fonts.googleapis.com/'):
                fonts.append(route.request.url); route.fulfill(content_type='text/css', body='/* 离线系统字体 */')
            else:
                external.append(route.request.url); route.abort()
        context.route('**/*', offline)
        page = context.new_page(); page.on('pageerror', lambda e: errors.append(str(e)))
        def open_project():
            page.locator('.knowledge-entity').filter(has_text='Atlas workspace').click()
            expect(page.locator('.knowledge-detail h2')).to_have_text('Atlas workspace')
        def dialog(): return page.locator('dialog[open]')
        def mutate(path, action, method='POST'):
            # 等待对应写请求及详情重绘，不能把上一次的 Saved 当作本次完成。
            old_header = page.locator('.knowledge-detail-header').element_handle()
            with page.expect_response(lambda response: response.request.method == method
                                      and urlsplit(response.url).path == '/desktop/ontology' + path) as pending:
                action()
            assert pending.value.ok
            if old_header:
                page.wait_for_function('(node) => !node.isConnected', arg=old_header)

        try:
            page.goto(origin + '/?lang=en'); page.wait_for_function('window.knowledgeFixtureReady')
            open_project()
            expect(page.locator('.knowledge-warning')).to_be_hidden()
            for status in ['confirmed', 'inferred', 'observed', 'candidate']:
                assert page.locator(f'.knowledge-claim[data-status={status}]').count()
            assert page.locator('.knowledge-relation-group').count() == 2
            confirmed = page.locator('.knowledge-relation').filter(has=page.locator('[data-status=confirmed]')).first
            assert confirmed.get_by_role('button', name='Confirm', exact=True).count() == 0
            checks.append('状态文字与图标、关系分组、已确认无重复确认、正常来源无警告')
            page.screenshot(path=str(screenshots / 'knowledge-ux-light.png'), full_page=True)
            light = page.locator('body').evaluate('(e) => getComputedStyle(e).backgroundColor')
            page.emulate_media(color_scheme='dark')
            page.screenshot(path=str(screenshots / 'knowledge-ux-dark.png'), full_page=True)
            dark = page.locator('body').evaluate('(e) => getComputedStyle(e).backgroundColor')
            page.emulate_media(color_scheme='light')
            more = page.locator('.knowledge-heading button[aria-haspopup=menu]').last
            more.focus(); more.press('ArrowDown'); expect(page.get_by_role('menuitem', name='Rematch automatically')).to_be_focused()
            page.keyboard.press('Escape'); expect(more).to_be_focused(); expect(more).to_have_attribute('aria-expanded', 'false')
            more.click(); page.locator('.knowledge-heading h2').click(); expect(more).to_have_attribute('aria-expanded', 'false')
            for accept in [False, True]:
                more.click(); page.get_by_role('menuitem', name='Rematch automatically').click()
                expect(dialog()).to_contain_text('preserved')
                if accept:
                    mutate('/rebuild', lambda: dialog().get_by_role('button', name='Rematch automatically', exact=True).click())
                else:
                    dialog().get_by_role('button', name='Cancel', exact=True).click()
                expect(dialog()).to_have_count(0)
            assert len([r for r in REQUESTS if r['method']=='POST' and r['path'].endswith('/rebuild')]) == 1
            checks.append('菜单键盘、Escape 焦点恢复、外部关闭、重新匹配取消与确认')
            page.locator('.knowledge-detail').get_by_role('button', name='Add relationship').click()
            combo = dialog().locator('input[role=combobox]'); expect(dialog().locator('[role=listbox] [role=option]').first).to_be_visible()
            combo.fill('learning'); expect(dialog().locator('[role=listbox] [role=option]')).to_have_count(1)
            combo.press('ArrowDown'); assert combo.get_attribute('aria-activedescendant')
            page.screenshot(path=str(screenshots / 'knowledge-ux-combobox.png'))
            combo.press('Escape'); expect(combo).to_have_attribute('aria-expanded', 'false'); expect(dialog()).to_be_visible()
            combo.press('ArrowDown'); combo.press('Enter'); expect(combo).to_have_value('Build a sustainable learning practice')
            mutate('/relations', lambda: dialog().get_by_role('button', name='Save', exact=True).click())
            expect(dialog()).to_have_count(0)
            page.locator('.knowledge-detail').get_by_role('button', name='Add relationship').click()
            combo = dialog().locator('input[role=combobox]'); expect(dialog().locator('[role=listbox] [role=option]').first).to_be_visible()
            combo.press('Escape'); expect(dialog()).to_be_visible()
            combo.press('Escape'); expect(dialog()).to_have_count(0)
            checks.append('组合框防抖搜索、活动选项 ARIA、两层 Escape、方向键/Enter 选择与保存')
            # 用真实按钮完成修改；点击前已置于可见区，验证重绘后的滚动恢复。
            row = page.locator('.knowledge-relation').filter(has=page.locator('[data-status=inferred]')).first
            button = row.get_by_role('button', name='Confirm', exact=True); button.scroll_into_view_if_needed()
            page.locator('.knowledge-list').evaluate('(e) => e.scrollTop = 120')
            before = page.locator('.knowledge-detail').evaluate('(e) => e.scrollTop')
            inferred_count = page.locator('.knowledge-claim[data-status=inferred]').count()
            mutate('/relations', button.click)
            expect(page.locator('.knowledge-claim[data-status=inferred]')).to_have_count(inferred_count - 1)
            expect(page.locator('#knowledge-message')).to_contain_text('Saved')
            assert abs(page.locator('.knowledge-detail').evaluate('(e) => e.scrollTop') - before) < 3
            assert page.locator('.knowledge-list').evaluate('(e) => e.scrollTop') == 120
            relation_count = page.locator('.knowledge-relation').count()
            mutate('/relations', lambda: row.get_by_role('button', name='Remove association', exact=True).click())
            expect(page.locator('.knowledge-relation')).to_have_count(relation_count - 1)
            expect(page.locator('#knowledge-message')).to_contain_text('Saved')
            checks.append('确认/否定关系、列表和详情滚动位置恢复')
            query = page.locator('.knowledge-filters input[type=search]')
            query.fill('Research'); expect(page.locator('.knowledge-entity')).to_have_count(1)
            query.fill(''); expect(page.locator('.knowledge-entity')).to_have_count(30)
            times = page.locator('.knowledge-filters select').nth(1)
            times.select_option('week'); expect(page.locator('.knowledge-time-notice')).to_contain_text('7')
            assert any('start' in r.get('params', {}) for r in REQUESTS)
            page.locator('.knowledge-time-notice button').click(); expect(times).to_have_value('all')
            times.select_option('custom'); expect(page.locator('input[type=datetime-local]').first).to_be_visible()
            times.select_option('all')
            organize = page.locator('.knowledge-organize'); organize.click(); expect(organize).to_have_attribute('aria-pressed', 'true')
            expect(page.locator('.knowledge-list .knowledge-badge').first).to_have_attribute('data-type', 'activity')
            page.locator('.knowledge-filters select').first.select_option(''); expect(organize).to_have_attribute('aria-pressed', 'false')
            checks.append('即时搜索、时间预设与详情清除、自定义范围、待整理快捷筛选')
            page.locator('.knowledge-entity').filter(has_text='Build a sustainable').click()
            expect(page.locator('.knowledge-readonly')).to_have_attribute('aria-describedby', 'ontology-readonly-help')
            assert page.locator('.knowledge-detail button[aria-haspopup=menu]').count() == 0
            open_project()
            for width, height in [(800, 600), (390, 700)]:
                page.set_viewport_size({'width': width, 'height': height})
                page.locator('.knowledge-detail').evaluate('(e) => e.scrollTop=0')
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), f'{width} 页面横向溢出'
                assert page.locator('#knowledge-root').evaluate('(e) => e.scrollWidth <= e.clientWidth'), f'{width} 知识页横向溢出'
                if width == 390: page.screenshot(path=str(screenshots / 'knowledge-ux-narrow.png'), full_page=True)
            checks.append('只读来源说明、800×600 与 390×700 无横向溢出')
            page.set_viewport_size({'width': 1280, 'height': 1000})
            page.locator('.knowledge-entity').filter(has_text='Archive notebook').click()
            expect(page.locator('.knowledge-detail h2')).to_have_text('Archive notebook')
            menu = page.locator('.knowledge-detail button[aria-haspopup=menu]')
            menu.focus(); menu.press('ArrowDown'); page.keyboard.press('Enter')
            expect(dialog()).to_contain_text('Merge')
            combo = dialog().locator('input[role=combobox]'); combo.fill('Research')
            expect(dialog().locator('[role=listbox] [role=option]')).to_have_count(1)
            combo.press('ArrowDown'); combo.press('Enter'); expect(combo).to_have_value('Research notebook')
            mutate('/merge', lambda: dialog().get_by_role('button', name='Save', exact=True).click())
            expect(dialog()).to_have_count(0)
            expect(page.locator('.knowledge-detail h2')).to_have_text('Research notebook')
            expect(page.locator('.knowledge-entity').filter(has_text='Archive notebook')).to_have_count(0)
            assert [r['body'] for r in REQUESTS if r['method'] == 'POST' and r['path'].endswith('/merge')] == [{'from': 'p3', 'to': 'p2'}]
            checks.append('合并菜单键盘开启、组合框搜索/选择/保存、来源移除与目标详情刷新')
            for accept in [False, True]:
                menu = page.locator('.knowledge-detail button[aria-haspopup=menu]'); menu.focus(); menu.press('ArrowDown')
                page.keyboard.press('End'); expect(page.get_by_role('menuitem', name='Delete', exact=True)).to_be_focused()
                page.keyboard.press('Enter'); expect(dialog()).to_contain_text('Research notebook')
                if accept:
                    mutate('/entities/p2', lambda: dialog().get_by_role('button', name='Delete', exact=True).click(), method='DELETE')
                    expect(page.locator('.knowledge-entity').filter(has_text='Research notebook')).to_have_count(0)
                else:
                    dialog().get_by_role('button', name='Cancel', exact=True).click()
                expect(dialog()).to_have_count(0)
            assert len([r for r in REQUESTS if r['method']=='DELETE']) == 1
            checks.append('删除菜单 End/Enter、应用内取消与确认')
            COVERAGE['wiki'] = 'truncated'
            page.goto(origin + '/?lang=zh'); page.wait_for_function('window.knowledgeFixtureReady')
            expect(page.locator('.knowledge-warning')).to_be_visible()
            page.locator('.knowledge-entity').first.click()
            assert 'ontology.' not in page.locator('#knowledge-root').inner_text()
            checks.append('中文文案无缺失键、截断来源显示警告')
            assert not errors, errors
            assert not external, external
            (output / 'validation.json').write_text(json.dumps(dict(syntheticData=True, backendIntegration=False,
                productionAssets=str(UI.relative_to(REPO)), checks=checks, darkSchemeBackground=dark,
                lightSchemeBackground=light, darkThemeChangesBackground=dark != light,
                externalRequests=external, locallyStubbedOptionalFonts=fonts, pageErrors=errors,
                requests=REQUESTS), ensure_ascii=False, indent=2) + '\n')
        except Exception:
            page.screenshot(path=str(output / 'failure.png'), full_page=True)
            raise
        finally:
            context.close(); browser.close()
    print('PASS: 知识页真实 DOM、键盘、CRUD、过滤与窄屏验证；仅合成数据，非 Tauri 集成。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=REPO / 'artifacts/knowledge-ui')
    parser.add_argument('--screenshots-dir', type=Path, help='可选的 PR 图片目录；默认与报告同目录')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(FixtureHandler, directory=str(UI)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        check_browser(server, args.output_dir, args.screenshots_dir or args.output_dir)
    except Exception as error:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / 'failure.json').write_text(json.dumps({
            'status': 'failed', 'syntheticData': True, 'error': str(error),
            'note': '未通过；不能将脚本或截图作为已通过的集成验证证据。'
        }, ensure_ascii=False, indent=2) + '\n')
        raise
    finally:
        server.shutdown(); server.server_close()

if __name__ == '__main__': main()
