import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const ui = new URL("../../main/resources/desktop-ui/", import.meta.url);
const read = name => fs.readFileSync(new URL(name, ui), "utf8");
const turn = () => new Promise(resolve => setImmediate(resolve));

function setup() {
  const elements = new Map();
  const doc = { activeElement: null, getElementById: id => elements.get(id) };
  function element(id) {
    const handlers = new Map();
    const el = {
      isConnected: true, open: false, disabled: false, textContent: "",
      focus() { doc.activeElement = el; },
      addEventListener(type, fn) { if (!handlers.has(type)) handlers.set(type, new Set()); handlers.get(type).add(fn); },
      removeEventListener(type, fn) { handlers.get(type)?.delete(fn); },
      emit(type, props = {}) {
        const event = { preventDefault() { this.defaultPrevented = true; }, ...props };
        for (const fn of [...(handlers.get(type) || [])]) fn(event);
        return event;
      },
      showModal() { el.open = true; },
      close() { if (!el.open) return; el.open = false; setImmediate(() => el.emit("close")); },
      listenerCount() { return [...handlers.values()].reduce((sum, set) => sum + set.size, 0); },
    };
    elements.set(id, el);
    return el;
  }
  for (const id of ["session-delete-dialog", "session-delete-title", "session-delete-description",
    "session-delete-cancel", "session-delete-confirm", "new-chat-session-btn"]) element(id);
  const sandbox = { document: doc, t: key => key };
  vm.createContext(sandbox);
  vm.runInContext(read("session-delete-dialog.js"), sandbox);
  return { sandbox, doc, element, elements, dialog: elements.get("session-delete-dialog"),
    cancel: elements.get("session-delete-cancel"), confirm: elements.get("session-delete-confirm") };
}

test("cancel, Escape and ordinary close resolve false and clean up listeners", async () => {
  const h = setup();
  let restored = 0;
  for (const action of [() => h.cancel.emit("click"), () => h.dialog.emit("cancel"), () => h.dialog.close()]) {
    const result = h.sandbox.confirmSessionDeletion(() => restored++);
    assert.equal(h.dialog.open, true);
    assert.equal(h.doc.activeElement, h.cancel);
    action();
    assert.equal(await result, false);
    assert.equal(h.dialog.listenerCount() + h.cancel.listenerCount() + h.confirm.listenerCount(), 0);
  }
  assert.equal(restored, 3);
});

test("only explicit confirmation accepts; repeated clicks and reopening do not leak results", async () => {
  const h = setup();
  const first = h.sandbox.confirmSessionDeletion(() => {});
  h.dialog.emit("click", { target: h.dialog });
  assert.equal(h.dialog.open, true);
  assert.equal(await h.sandbox.confirmSessionDeletion(() => {}), false);
  h.confirm.emit("click"); h.confirm.emit("click"); h.cancel.emit("click");
  assert.equal(await first, true);
  const second = h.sandbox.confirmSessionDeletion(() => {});
  assert.equal(h.confirm.disabled, false);
  h.cancel.emit("click");
  assert.equal(await second, false);
});

test("Tab and Shift+Tab wrap between the two dialog buttons", async () => {
  const h = setup();
  const result = h.sandbox.confirmSessionDeletion(() => {});
  h.dialog.emit("keydown", { key: "Tab", shiftKey: true });
  assert.equal(h.doc.activeElement, h.confirm);
  h.dialog.emit("keydown", { key: "Tab", shiftKey: false });
  assert.equal(h.doc.activeElement, h.cancel);
  h.cancel.emit("click"); await result;
});

function chatSetup({ fail = false } = {}) {
  const h = setup();
  const session = { id: "a", title: "A", messages: [], messagesLoaded: true };
  const other = { id: "b", title: "B", messages: [], messagesLoaded: true };
  const calls = [], alerts = [];
  let finishDelete;
  const request = new Promise((resolve, reject) => { finishDelete = () => fail ? reject(new Error("busy")) : resolve({ activeSessionId: "b" }); });
  const buttons = { a: h.element("delete-a"), b: h.element("delete-b") };
  const list = {
    innerHTML: "",
    querySelectorAll() { return h.sandbox.state.chatSessions.map(s => ({ dataset: { sid: s.id }, querySelector: () => buttons[s.id] })); },
  };
  Object.assign(h.sandbox, {
    state: { chatSessions: [session, other], activeChatSessionId: "a", chatSessionMutationGeneration: 0,
      chatSessionSearchRequestId: 0, dom: { chatSessionList: list } },
    api: { deleteSession(id) { calls.push(id); return request; } },
    alert: msg => alerts.push(msg), escHtml: s => s || "", formatRelativeTime: () => "now",
  });
  vm.runInContext(read("chat.js"), h.sandbox);
  h.sandbox.renderChatTab = () => {
    buttons.a.isConnected = false;
    buttons.b = h.element("replacement-b");
  };
  return { ...h, calls, alerts, buttons, session, finishDelete, list };
}

test("list delete entry cancels without sending requests or selecting another session", async () => {
  const h = chatSetup();
  h.sandbox.renderChatSessionList();
  h.buttons.b.closest = () => ({ dataset: { sid: "b" } });
  h.list.onclick({ stopPropagation() {}, target: { closest: selector => selector.includes("delete-session") ? h.buttons.b : null } });
  assert.equal(h.dialog.open, true);
  h.cancel.emit("click"); await turn();
  assert.deepEqual(h.calls, []);
  assert.equal(h.sandbox.state.activeChatSessionId, "a");
  assert.equal(h.doc.activeElement, h.buttons.b);
});

test("confirmation binds original target and suppresses duplicate requests until deletion finishes", async () => {
  const h = chatSetup();
  const result = h.sandbox.requestChatSessionDeletion("a", h.buttons.a);
  h.sandbox.state.activeChatSessionId = "b";
  h.confirm.emit("click"); h.confirm.emit("click"); await turn();
  await h.sandbox.requestChatSessionDeletion("b", h.buttons.b);
  assert.deepEqual(h.calls, ["a"]);
  h.finishDelete(); await result;
  assert.deepEqual(Array.from(h.sandbox.state.chatSessions, s => s.id), ["b"]);
  assert.equal(h.doc.activeElement, h.buttons.b);
});

test("failed deletion preserves cached sessions and restores the trigger", async () => {
  const h = chatSetup({ fail: true });
  const result = h.sandbox.requestChatSessionDeletion("a", h.buttons.a);
  h.confirm.emit("click"); await turn(); h.finishDelete(); await result;
  assert.equal(h.sandbox.state.chatSessions.length, 2);
  assert.equal(h.alerts.length, 1);
  assert.equal(h.doc.activeElement, h.buttons.a);
});

test("cancel restores a replaced trigger, or falls back to new chat when no row remains", async () => {
  const h = chatSetup();
  const result = h.sandbox.requestChatSessionDeletion("a", h.buttons.a);
  h.buttons.a.isConnected = false;
  h.buttons.a = h.element("replacement-a");
  h.cancel.emit("click"); await result;
  assert.equal(h.doc.activeElement, h.buttons.a);
  const next = h.sandbox.requestChatSessionDeletion("a", h.buttons.a);
  h.buttons.a.isConnected = false; h.sandbox.state.chatSessions = [];
  h.cancel.emit("click"); await next;
  assert.equal(h.doc.activeElement, h.elements.get("new-chat-session-btn"));
});
