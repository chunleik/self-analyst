//! SelfAnalyst accessibility-tree sidecar.
//!
//! OS-neutral protocol (SPEC-AXS-010..016): newline-delimited JSON over
//! stdin/stdout, one request -> one response, UTF-8 only.
//!
//!   request:  {"id":42,"handle":"0x001A0C3E"}
//!   response: {"id":42,"status":"ok","root":{ AxNode }}
//!             {"id":42,"status":"null"}
//!             {"id":42,"status":"error","error":"..."}
//!
//! The native accessibility API differs per OS and is fully contained in the
//! platform module (Windows: UI Automation). Everything above the protocol is
//! OS-neutral.

use std::io::{self, BufRead, Write};

use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
struct Request {
    id: u64,
    handle: String,
}

/// OS-neutral accessibility node (SPEC-AXS-013).
#[derive(Serialize, Default)]
pub struct AxNode {
    pub role: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub name: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub value: String,
    #[serde(skip_serializing_if = "is_false")]
    pub secure: bool,
    pub bounds: [f64; 4],
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub children: Vec<AxNode>,
}

fn is_false(b: &bool) -> bool {
    !*b
}

#[derive(Serialize)]
struct Response {
    id: u64,
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    root: Option<AxNode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

impl Response {
    fn ok(id: u64, root: AxNode) -> Self {
        Response { id, status: "ok", root: Some(root), error: None }
    }
    fn null(id: u64) -> Self {
        Response { id, status: "null", root: None, error: None }
    }
    fn error(id: u64, msg: String) -> Self {
        Response { id, status: "error", root: None, error: Some(msg) }
    }
}

/// Parse a neutral window handle ("0x1a2b" or "1a2b") into a raw pointer value.
fn parse_handle(s: &str) -> Option<isize> {
    let t = s.trim();
    let hex = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X"));
    let v = match hex {
        Some(h) => u64::from_str_radix(h, 16),
        None => t.parse::<u64>(),
    };
    v.ok().map(|n| n as isize)
}

fn main() {
    let backend = platform::Backend::new();

    let stdin = io::stdin();
    let mut out = io::stdout();

    for line in stdin.lock().lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        let line = line.trim();
        if line.is_empty() {
            continue;
        }

        let resp = match serde_json::from_str::<Request>(line) {
            Ok(req) => match parse_handle(&req.handle) {
                Some(h) => backend.query(req.id, h),
                None => Response::error(req.id, "bad handle".into()),
            },
            Err(e) => Response::error(0, format!("bad request: {e}")),
        };

        match serde_json::to_string(&resp) {
            Ok(json) => {
                if writeln!(out, "{json}").is_err() {
                    break;
                }
            }
            Err(_) => {
                let _ = writeln!(out, "{{\"id\":{},\"status\":\"error\"}}", resp.id);
            }
        }
        if out.flush().is_err() {
            break;
        }
    }
}

// ── Windows backend ────────────────────────────────────────────────

#[cfg(windows)]
mod platform {
    use super::{AxNode, Response};
    use windows::core::Interface;
    use windows::Win32::Foundation::{HWND, RECT};
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
    };
    use windows::Win32::UI::Accessibility::{
        CUIAutomation, IUIAutomation, IUIAutomationElement, IUIAutomationTreeWalker,
        IUIAutomationValuePattern, UIA_ValuePatternId,
    };

    const MAX_DEPTH: i32 = 60;

    pub struct Backend {
        automation: Option<IUIAutomation>,
        walker: Option<IUIAutomationTreeWalker>,
    }

    impl Backend {
        pub fn new() -> Self {
            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            }
            let automation: Option<IUIAutomation> =
                unsafe { CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER) }.ok();
            let walker = automation
                .as_ref()
                .and_then(|a| unsafe { a.ControlViewWalker() }.ok());
            Backend { automation, walker }
        }

        pub fn query(&self, id: u64, handle: isize) -> Response {
            let (automation, walker) = match (&self.automation, &self.walker) {
                (Some(a), Some(w)) => (a, w),
                _ => return Response::error(id, "UIAutomation unavailable".into()),
            };
            let hwnd = HWND(handle as *mut core::ffi::c_void);
            let element = match unsafe { automation.ElementFromHandle(hwnd) } {
                Ok(e) => e,
                Err(_) => return Response::null(id),
            };
            let root = unsafe { walk(walker, &element, 0) };
            Response::ok(id, root)
        }
    }

    unsafe fn walk(
        walker: &IUIAutomationTreeWalker,
        el: &IUIAutomationElement,
        depth: i32,
    ) -> AxNode {
        let control_type = el.CurrentControlType().map(|c| c.0).unwrap_or(0);
        let role = control_type_to_role(control_type).to_string();
        let name = bstr(el.CurrentName());
        let secure = el.CurrentIsPassword().map(|b| b.as_bool()).unwrap_or(false);
        // SPEC-AXS-014b: never emit a real secret value.
        let value = if secure { "***".to_string() } else { value_of(el) };
        let bounds = el
            .CurrentBoundingRectangle()
            .map(rect_to_bounds)
            .unwrap_or([0.0; 4]);

        let mut children = Vec::new();
        if depth < MAX_DEPTH {
            let mut child = walker.GetFirstChildElement(el).ok();
            while let Some(c) = child {
                children.push(walk(walker, &c, depth + 1));
                child = walker.GetNextSiblingElement(&c).ok();
            }
        }

        AxNode { role, name, value, secure, bounds, children }
    }

    unsafe fn value_of(el: &IUIAutomationElement) -> String {
        match el.GetCurrentPattern(UIA_ValuePatternId) {
            Ok(unk) => match unk.cast::<IUIAutomationValuePattern>() {
                Ok(vp) => bstr(vp.CurrentValue()),
                Err(_) => String::new(),
            },
            Err(_) => String::new(),
        }
    }

    fn bstr(r: windows::core::Result<windows::core::BSTR>) -> String {
        r.map(|b| b.to_string()).unwrap_or_default()
    }

    fn rect_to_bounds(r: RECT) -> [f64; 4] {
        [
            r.left as f64,
            r.top as f64,
            (r.right - r.left) as f64,
            (r.bottom - r.top) as f64,
        ]
    }

    /// Map UIA control-type id -> neutral role (SPEC-AXS-016).
    fn control_type_to_role(id: i32) -> &'static str {
        match id {
            50000 => "Button",
            50001 => "Calendar",
            50002 => "CheckBox",
            50003 => "ComboBox",
            50004 => "Edit",
            50005 => "Hyperlink",
            50006 => "Image",
            50007 => "ListItem",
            50008 => "List",
            50009 => "Menu",
            50010 => "MenuBar",
            50011 => "MenuItem",
            50012 => "ProgressBar",
            50013 => "RadioButton",
            50014 => "ScrollBar",
            50015 => "Slider",
            50016 => "Spinner",
            50017 => "StatusBar",
            50018 => "Tab",
            50019 => "TabItem",
            50020 => "Text",
            50021 => "ToolBar",
            50022 => "ToolTip",
            50023 => "Tree",
            50024 => "TreeItem",
            50025 => "Custom",
            50026 => "Group",
            50027 => "Thumb",
            50028 => "DataGrid",
            50029 => "DataItem",
            50030 => "Document",
            50031 => "SplitButton",
            50032 => "Window",
            50033 => "Pane",
            50034 => "Header",
            50035 => "HeaderItem",
            50036 => "Table",
            50037 => "TitleBar",
            50038 => "Separator",
            50039 => "SemanticZoom",
            50040 => "AppBar",
            _ => "Unknown",
        }
    }
}

// ── Non-Windows stub (macOS backend lands in a later phase, SPEC-AXS-050) ──

#[cfg(not(windows))]
mod platform {
    use super::Response;

    pub struct Backend;

    impl Backend {
        pub fn new() -> Self {
            Backend
        }
        pub fn query(&self, id: u64, _handle: isize) -> Response {
            Response::error(id, "accessibility backend not implemented on this OS".into())
        }
    }
}
