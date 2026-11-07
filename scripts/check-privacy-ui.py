"""使用生产 UI 与合成 API 数据验证隐私页签；不打包，不连接模型或外部服务。

python -m pip install playwright==1.58.0
python -m playwright install --with-deps chromium
python scripts/check-privacy-ui.py --output-dir artifacts/privacy-ui

默认使用 Playwright 安装的 Chromium；PRIVACY_BROWSER 可指定已有浏览器。
截图是真实浏览器渲染的生产 UI，但不是实际后端或真实用户数据的集成证据。
"""
import argparse
import functools
import json
import os
from pathlib import Path
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parents[1]
UI = REPO / "self-analyst-app/src/main/resources/desktop-ui"
FIXTURE = {
    "apps": [
        {"app": "PotPlayerMini64.exe", "mode": "capture"},
        {"app": "PrivateNotes.exe", "mode": "model"},
    ],
    "sites": ["mail.example.com", "private.example.org"],
    "candidates": [
        {"app": "Code.exe", "seconds": 7200},
        {"app": "Browser.exe", "seconds": 3600},
    ],
}
BOOTSTRAP = (UI / "init.js").read_text(encoding="utf-8").split("var initializationPending")[0] + """
(async function () {
  cacheDom();
  state.lang = new URLSearchParams(location.search).get('lang') || 'en';
  await loadI18n(state.lang); applyI18n(document); document.documentElement.lang = state.lang;
  setupEvents(); hideError();
  state.configOpen = true; state.dom.configModal.classList.remove('hidden');
  await switchConfigView('privacy'); window.privacyFixtureReady = true;
})();
"""


class FixtureHandler(SimpleHTTPRequestHandler):
    def respond(self, content_type, body):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/init.js":
            self.respond("application/javascript; charset=utf-8", BOOTSTRAP)
        elif path.startswith("/desktop/"):
            self.respond("application/json", json.dumps(FIXTURE if path == "/desktop/privacy" else {}))
        else:
            super().do_GET()

    def do_PUT(self):
        if urlsplit(self.path).path != "/desktop/privacy":
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        self.respond("application/json", json.dumps({**body, "candidates": FIXTURE["candidates"], "applied": True}))

    def log_message(self, *_):
        pass


def check_browser(server, output):
    from playwright.sync_api import sync_playwright

    origin = f"http://127.0.0.1:{server.server_port}"
    output.mkdir(parents=True, exist_ok=True)
    errors, external_requests, local_font_stubs = [], [], []
    with sync_playwright() as playwright:
        options = {"headless": True}
        if os.environ.get("PRIVACY_BROWSER"):
            options["executable_path"] = os.environ["PRIVACY_BROWSER"]
        browser = playwright.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1280, "height": 1000}, device_scale_factor=1, service_workers="block")

        # 测试页只能访问本地 fixture；外部请求在发送前拦截，并令验证失败。
        def local_only(route):
            parsed = urlsplit(route.request.url)
            if f"{parsed.scheme}://{parsed.netloc}" == origin:
                route.continue_()
            elif route.request.url == "https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap":
                # 已有 deep-chat 的可选字体依赖在 fixture 中本地替代，绝不发送请求。
                local_font_stubs.append(route.request.url)
                route.fulfill(status=200, content_type="text/css", body="/* Offline fixture: use installed system fonts. */")
            else:
                external_requests.append(route.request.url)
                route.abort()

        context.route("**/*", local_only)
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        try:
            for lang in ["zh", "en"]:
                page.set_viewport_size({"width": 1280, "height": 1000})
                page.goto(f"{origin}/?lang={lang}")
                page.wait_for_function("window.privacyFixtureReady === true")
                page.evaluate("document.fonts.ready")
                page.locator("#config-modal").screenshot(path=str(output / f"privacy-settings-{lang}.png"))

                # 使用生产事件处理器验证键盘选择、模式、网站 chip 与放弃确认。
                app = page.locator("#privacy-app-input")
                app.fill("code")
                app.press("ArrowDown")
                app.press("Enter")
                assert page.locator(".privacy-app-row").count() == 3
                mode = page.locator(".privacy-app-row").last.locator("select")
                assert mode.input_value() == "model"
                mode.select_option("capture")
                assert mode.input_value() == "capture"
                site = page.locator("#privacy-site-input")
                site.fill("synthetic.example.net")
                site.press("Enter")
                assert page.locator(".privacy-chip").count() == 3
                page.once("dialog", lambda dialog: dialog.dismiss())
                page.locator('[data-config-view="raw"]').click()
                assert page.locator("#config-modal").get_attribute("data-view") == "privacy"
                page.once("dialog", lambda dialog: dialog.dismiss())
                page.locator("#config-close-btn").click()
                assert page.locator(".privacy-settings").is_visible()
                app.fill("browser")
                app.press("ArrowDown")
                app.press("Escape")
                assert page.locator("#privacy-candidates").is_hidden()
                assert page.locator(".privacy-settings").is_visible()
                app.fill("")
                page.locator(".privacy-actions .btn-primary").click()
                page.wait_for_function("!state.configDirty && !state.configSaving")
                assert page.locator(".privacy-status").inner_text() == (
                    "已生效，无需重启。" if lang == "zh" else "Applied. No restart needed."
                )
                for width, height in [(800, 600), (390, 700)]:
                    page.set_viewport_size({"width": width, "height": height})
                    scroll = page.locator(".privacy-settings .config-scroll-area")
                    scroll.evaluate("element => { element.scrollTop = 0; }")
                    page.screenshot(path=str(output / f"privacy-settings-{lang}-{width}x{height}.png"))
                    scroll.evaluate("element => { element.scrollTop = element.scrollHeight; }")
                    page.screenshot(path=str(output / f"privacy-settings-{lang}-{width}x{height}-bottom.png"))
                    assert page.locator(".privacy-chip").last.is_visible()
                    overflow = page.evaluate("""() => {
                      const selectors = ['.config-modal-panel', '.privacy-settings', '.privacy-settings .config-scroll-area'];
                      return selectors.filter(selector => {
                        const e = document.querySelector(selector);
                        return e.scrollWidth > e.clientWidth + 2;
                      });
                    }""")
                    assert not overflow, f"{lang} {width} overflow: {overflow}"
                    save = page.locator(".privacy-actions .btn-primary")
                    assert save.is_visible()
                    box = save.bounding_box()
                    assert box and box["x"] >= 0 and box["y"] >= 0
                    assert box["x"] + box["width"] <= width and box["y"] + box["height"] <= height
            assert not errors, errors
            assert not external_requests, external_requests
            (output / "validation.json").write_text(json.dumps({
                "syntheticData": True, "productionAssets": str(UI.relative_to(REPO)),
                "languages": ["zh", "en"], "viewports": [[1280, 1000], [800, 600], [390, 700]],
                "keyboardSelection": True, "dirtyCancellation": True, "save": True,
                "externalRequests": external_requests, "pageErrors": errors,
                "locallyStubbedOptionalFonts": local_font_stubs,
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        except Exception:
            page.screenshot(path=str(output / "failure.png"))
            raise
        finally:
            context.close()
            browser.close()
    print("PASS: EN/ZH screenshots, keyboard, dirty cancellation, save, 800x600 / 390x700 layouts. Synthetic fixtures; no backend or packaging.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=REPO / "artifacts/privacy-ui")
    parser.add_argument("--serve-only", action="store_true")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(FixtureHandler, directory=str(UI)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        if args.serve_only:
            print(f"http://127.0.0.1:{server.server_port}/?lang=en", flush=True)
            threading.Event().wait()
        else:
            check_browser(server, args.output_dir)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
