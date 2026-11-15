"""Synthetic Chromium composer regression; no mailbox, model, uploads or Tauri.

python scripts/check-chat-composer-ui.py --browser /usr/bin/chromium
"""
import argparse
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

REPO = Path(__file__).resolve().parents[1]
UI = REPO / 'self-analyst-app/src/main/resources/desktop-ui'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', default=None)
    parser.add_argument('--output-dir', default='artifacts/chat-composer-ui')
    args = parser.parse_args()
    output = REPO / args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    source = (UI / 'index.html').read_text()
    markup = re.search(r'<div id="chat-composer".*?<div id="chat-composer-status"[^>]*></div>\s*</div>', source, re.S).group()
    with sync_playwright() as runtime:
        browser = runtime.chromium.launch(executable_path=args.browser, args=['--no-sandbox'])
        for language in ['zh', 'en']:
            page = browser.new_page()
            page.route('**/*', lambda route: route.abort())
            page.set_content('<style>' + (UI / 'styles.css').read_text() + '</style>' + markup)
            page.evaluate('locale => window.locale = locale', json.loads((UI / f'locales/{language}.json').read_text()))
            page.add_script_tag(content='''
              var API_BASE = '', api = {}, session = { messagesLoaded: true, messages: [] };
              var state = { tab: 'chat', activeChatSessionId: 'a', chatSending: false,
                status: { llm: { configured: true } }, dom: {
                  chatTabInput: document.getElementById('chat-tab-input'),
                  chatTabSendBtn: document.getElementById('chat-tab-send-btn') } };
              function t(key) { return locale[key] || key; }
              function escHtml(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
              function getActiveChatSession() { return session; }
            ''')
            page.add_script_tag(content=(UI / 'chat-composer.js').read_text())
            page.add_script_tag(content=(UI / 'chat-images.js').read_text())
            page.evaluate('renderChatImages()')
            textarea = page.locator('#chat-tab-input')
            previews = page.locator('#chat-image-previews')
            expect(previews).to_be_hidden()
            textarea.fill('Text remains while pictures are added')
            textarea.focus()
            assert textarea.evaluate('e => getComputedStyle(e).outlineStyle') == 'none'
            expect(page.locator('.chat-image-help, details')).to_have_count(0)
            # Exercise the production paste listener using a real decoded PNG File.
            page.evaluate('''async () => {
              const canvas = document.createElement('canvas'); canvas.width = 120; canvas.height = 80;
              const ctx = canvas.getContext('2d'); ctx.fillStyle = '#3567c4'; ctx.fillRect(0,0,120,80);
              const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
              const transfer = new DataTransfer(); transfer.items.add(new File([blob], 'pasted.png', {type:'image/png'}));
              document.getElementById('chat-tab-input').dispatchEvent(new ClipboardEvent('paste', {bubbles:true, clipboardData:transfer}));
              await chatImageQueues.a;
            }''')
            expect(previews.locator('img')).to_have_count(1)
            expect(textarea).to_have_value('Text remains while pictures are added')
            for scheme in ['light', 'dark']:
                page.emulate_media(color_scheme=scheme)
                for width, height in [(1200, 800), (390, 640)]:
                    page.set_viewport_size({'width': width, 'height': height})
                    assert previews.bounding_box()['y'] + previews.bounding_box()['height'] <= textarea.bounding_box()['y']
                    assert page.locator('#chat-composer').evaluate('e => e.scrollWidth <= e.clientWidth + 1')
                    page.screenshot(path=str(output / f'{language}-{scheme}-{width}.png'))
            remove = previews.locator('button')
            remove.focus()
            page.evaluate('() => { renderChatImages(); renderChatImages(); }')
            expect(remove).to_be_focused()
            page.keyboard.press('Enter')
            expect(previews).to_be_hidden()
            expect(page.locator('[data-add-image]')).to_be_focused()
            expect(textarea).to_have_value('Text remains while pictures are added')
            page.close()
        browser.close()
    (output / 'verification.txt').write_text('PASS: production composer HTML/CSS/JS; zh/en; light/dark media; wide/narrow; synthetic PNG paste; upper previews; empty collapse; keyboard removal/focus; unchanged redraw.\nNo real OS clipboard, Windows IME, Tauri or model validation. Shared palette may remain light in dark media.\n')
    print('COMPOSER_UI_OK')


if __name__ == '__main__':
    main()
