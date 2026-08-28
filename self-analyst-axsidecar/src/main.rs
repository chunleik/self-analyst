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
    use uiautomation::patterns::UIValuePattern;
    use uiautomation::types::{ControlType, Handle};
    use uiautomation::{UIAutomation, UIElement, UITreeWalker};

    const MAX_DEPTH: i32 = 60;

    pub struct Backend {
        automation: Option<UIAutomation>,
        walker: Option<UITreeWalker>,
    }

    impl Backend {
        pub fn new() -> Self {
            // UIAutomation::new() initializes COM with COINIT_MULTITHREADED.
            let automation = UIAutomation::new().ok();
            let walker = automation
                .as_ref()
                .and_then(|a| a.get_control_view_walker().ok());
            Backend { automation, walker }
        }

        pub fn query(&self, id: u64, handle: isize) -> Response {
            let (automation, walker) = match (&self.automation, &self.walker) {
                (Some(a), Some(w)) => (a, w),
                _ => return Response::error(id, "UIAutomation unavailable".into()),
            };
            let element = match automation.element_from_handle(Handle::from(handle)) {
                Ok(e) => e,
                Err(_) => return Response::null(id),
            };
            let root = walk(walker, &element, 0);
            Response::ok(id, root)
        }
    }

    fn walk(walker: &UITreeWalker, el: &UIElement, depth: i32) -> AxNode {
        let role = el
            .get_control_type()
            .map(control_type_to_role)
            .unwrap_or("Unknown")
            .to_string();
        let name = el.get_name().unwrap_or_default();
        let secure = el.is_password().unwrap_or(false);
        // SPEC-AXS-014b: never emit a real secret value.
        let value = if secure { "***".to_string() } else { value_of(el) };
        let bounds = el
            .get_bounding_rectangle()
            .map(|r| {
                [
                    r.get_left() as f64,
                    r.get_top() as f64,
                    r.get_width() as f64,
                    r.get_height() as f64,
                ]
            })
            .unwrap_or([0.0; 4]);

        let mut children = Vec::new();
        if depth < MAX_DEPTH {
            let mut child = walker.get_first_child(el).ok();
            while let Some(c) = child {
                children.push(walk(walker, &c, depth + 1));
                child = walker.get_next_sibling(&c).ok();
            }
        }

        AxNode { role, name, value, secure, bounds, children }
    }

    fn value_of(el: &UIElement) -> String {
        el.get_pattern::<UIValuePattern>()
            .and_then(|p| p.get_value())
            .unwrap_or_default()
    }

    /// Map UIA control type -> neutral role (SPEC-AXS-016).
    fn control_type_to_role(ct: ControlType) -> &'static str {
        match ct {
            ControlType::Button => "Button",
            ControlType::Calendar => "Calendar",
            ControlType::CheckBox => "CheckBox",
            ControlType::ComboBox => "ComboBox",
            ControlType::Edit => "Edit",
            ControlType::Hyperlink => "Hyperlink",
            ControlType::Image => "Image",
            ControlType::ListItem => "ListItem",
            ControlType::List => "List",
            ControlType::Menu => "Menu",
            ControlType::MenuBar => "MenuBar",
            ControlType::MenuItem => "MenuItem",
            ControlType::ProgressBar => "ProgressBar",
            ControlType::RadioButton => "RadioButton",
            ControlType::ScrollBar => "ScrollBar",
            ControlType::Slider => "Slider",
            ControlType::Spinner => "Spinner",
            ControlType::StatusBar => "StatusBar",
            ControlType::Tab => "Tab",
            ControlType::TabItem => "TabItem",
            ControlType::Text => "Text",
            ControlType::ToolBar => "ToolBar",
            ControlType::ToolTip => "ToolTip",
            ControlType::Tree => "Tree",
            ControlType::TreeItem => "TreeItem",
            ControlType::Custom => "Custom",
            ControlType::Group => "Group",
            ControlType::Thumb => "Thumb",
            ControlType::DataGrid => "DataGrid",
            ControlType::DataItem => "DataItem",
            ControlType::Document => "Document",
            ControlType::SplitButton => "SplitButton",
            ControlType::Window => "Window",
            ControlType::Pane => "Pane",
            ControlType::Header => "Header",
            ControlType::HeaderItem => "HeaderItem",
            ControlType::Table => "Table",
            ControlType::TitleBar => "TitleBar",
            ControlType::Separator => "Separator",
            ControlType::SemanticZoom => "SemanticZoom",
            ControlType::AppBar => "AppBar",
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
