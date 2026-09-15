//! The browser draws the titlebar; Windows handles non-client dragging and caption controls.
use std::sync::{Arc, Mutex};
use tauri::{Manager, WebviewWindow};
use windows_sys::Win32::{
    Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM},
    Graphics::Gdi::ScreenToClient,
    System::Threading::GetCurrentThreadId,
    UI::{
        Shell::{DefSubclassProc, GetWindowSubclass, RemoveWindowSubclass, SetWindowSubclass},
        WindowsAndMessaging::*,
    },
};

const SUBCLASS: usize = 0x53415442;

#[derive(Clone, Default, serde::Deserialize)]
pub struct Rect {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}
impl Rect {
    fn contains(&self, x: f64, y: f64) -> bool {
        x >= self.x && y >= self.y && x < self.x + self.width && y < self.y + self.height
    }
    fn valid(&self) -> bool {
        [self.x, self.y, self.width, self.height]
            .iter()
            .all(|v| v.is_finite() && *v >= 0.0 && *v < 100_000.0)
    }
}
#[derive(Clone, Default, serde::Deserialize)]
pub struct Layout {
    drag: Rect,
    maximize: Rect,
    minimize: Rect,
    close: Rect,
    scale: f64,
}
impl Layout {
    fn hit(&self, x: f64, y: f64) -> i32 {
        for (rect, result) in [
            (&self.maximize, HTMAXBUTTON),
            (&self.minimize, HTMINBUTTON),
            (&self.close, HTCLOSE),
            (&self.drag, HTCAPTION),
        ] {
            if rect.contains(x / self.scale, y / self.scale) {
                return result as i32;
            }
        }
        HTCLIENT as i32
    }
    fn valid(&self) -> bool {
        self.scale.is_finite()
            && (0.5..=8.0).contains(&self.scale)
            && [&self.drag, &self.maximize, &self.minimize, &self.close]
                .iter()
                .all(|r| r.valid())
    }
}
struct Hook {
    root: isize,
    layout: Arc<Mutex<Layout>>,
    window: WebviewWindow,
}
pub struct TitlebarState(Arc<Mutex<Layout>>);

pub fn install(window: &WebviewWindow) -> tauri::Result<()> {
    let root = window.hwnd()?.0 as isize;
    let layout = Arc::new(Mutex::new(Layout {
        scale: 1.0,
        ..Default::default()
    }));
    window.manage(TitlebarState(layout.clone()));
    let hook = Box::new(Hook {
        root,
        layout,
        window: window.clone(),
    });
    let raw = Box::into_raw(hook);
    unsafe {
        if SetWindowSubclass(root as HWND, Some(window_proc), SUBCLASS, raw as usize) == 0 {
            drop(Box::from_raw(raw));
            return Err(std::io::Error::last_os_error().into());
        }
    }
    Ok(())
}

unsafe extern "system" fn child_hook(hwnd: HWND, data: LPARAM) -> i32 {
    let source = &*(data as *const Hook);
    let mut class = [0u16; 128];
    let len = GetClassNameW(hwnd, class.as_mut_ptr(), class.len() as i32);
    // Tao owns this overlay's non-client resizing behavior.
    if len > 0 && String::from_utf16_lossy(&class[..len as usize]) == "TAURI_DRAG_RESIZE_WINDOW" {
        return 1;
    }
    let mut existing = 0;
    if GetWindowThreadProcessId(hwnd, std::ptr::null_mut()) == GetCurrentThreadId()
        && GetWindowSubclass(hwnd, Some(window_proc), SUBCLASS, &mut existing) == 0
    {
        let hook = Box::new(Hook {
            root: source.root,
            layout: source.layout.clone(),
            window: source.window.clone(),
        });
        let raw = Box::into_raw(hook);
        if SetWindowSubclass(hwnd, Some(window_proc), SUBCLASS, raw as usize) == 0 {
            drop(Box::from_raw(raw));
        }
    }
    1
}

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    msg: u32,
    wp: WPARAM,
    lp: LPARAM,
    _: usize,
    data: usize,
) -> LRESULT {
    let hook = &*(data as *const Hook);
    let root = hook.root as HWND;
    if msg == WM_NCDESTROY {
        RemoveWindowSubclass(hwnd, Some(window_proc), SUBCLASS);
        let result = DefSubclassProc(hwnd, msg, wp, lp);
        drop(Box::from_raw(data as *mut Hook));
        return result;
    }
    if msg == WM_NCHITTEST {
        if hwnd == root {
            let default_hit = DefSubclassProc(hwnd, msg, wp, lp);
            if (HTLEFT as isize..=HTBOTTOMRIGHT as isize).contains(&default_hit) {
                return default_hit;
            }
        }
        let mut point = POINT {
            x: (lp as i16) as i32,
            y: ((lp >> 16) as i16) as i32,
        };
        ScreenToClient(root, &mut point);
        let hit = hook
            .layout
            .lock()
            .map(|l| l.hit(point.x as f64, point.y as f64))
            .unwrap_or(HTCLIENT as i32);
        if hit != HTCLIENT as i32 {
            // WebView2's child HWND must yield these regions to the top-level window.
            return if hwnd == root {
                hit as LRESULT
            } else {
                HTTRANSPARENT as LRESULT
            };
        }
    }
    if hwnd == root && msg == WM_SIZE {
        let _ = hook
            .window
            .eval("window.SelfAnalystTitlebar && window.SelfAnalystTitlebar.refresh()");
    }
    DefSubclassProc(hwnd, msg, wp, lp)
}

fn authorized(label: &str, url: &tauri::Url, port: u16) -> bool {
    label == "main" && super::documents::trusted(url, port)
}
fn managed_port(app: &tauri::AppHandle, window: &WebviewWindow) -> Result<u16, String> {
    let backend = app.try_state::<super::JavaBackend>().ok_or("help.failed")?;
    let port = backend
        .port
        .lock()
        .map_err(|_| "help.failed")?
        .ok_or("help.failed")?;
    if !authorized(
        window.label(),
        &window.url().map_err(|_| "help.failed")?,
        port,
    ) {
        return Err("help.failed".into());
    }
    Ok(port)
}

#[tauri::command]
pub fn titlebar_layout(
    app: tauri::AppHandle,
    window: WebviewWindow,
    layout: Layout,
) -> Result<(), String> {
    managed_port(&app, &window)?;
    if !layout.valid() {
        return Err("help.failed".into());
    }
    *app.try_state::<TitlebarState>()
        .ok_or("help.failed")?
        .0
        .lock()
        .map_err(|_| "help.failed")? = layout;
    let clone = window.clone();
    window
        .run_on_main_thread(move || unsafe {
            if let Ok(hwnd) = clone.hwnd() {
                let source = Hook {
                    root: hwnd.0 as isize,
                    layout: clone.state::<TitlebarState>().0.clone(),
                    window: clone.clone(),
                };
                EnumChildWindows(
                    hwnd.0 as HWND,
                    Some(child_hook),
                    &source as *const Hook as LPARAM,
                );
            }
        })
        .map_err(|_| "help.failed".to_owned())
}

#[tauri::command]
pub fn titlebar_action(
    app: tauri::AppHandle,
    window: WebviewWindow,
    action: String,
) -> Result<bool, String> {
    managed_port(&app, &window)?;
    let result = match action.as_str() {
        "minimize" => window.minimize(),
        "maximize" => {
            if window.is_maximized().map_err(|_| "help.failed")? {
                window.unmaximize()
            } else {
                window.maximize()
            }
        }
        "close" => window.close(),
        "drag" => window.start_dragging(),
        "state" => Ok(()),
        _ => return Err("help.failed".into()),
    };
    result.map_err(|_| "help.failed")?;
    window.is_maximized().map_err(|_| "help.failed".into())
}

fn help_url(action: &str, language: &str) -> Option<&'static str> {
    match (action, language) {
        ("guide", "zh") => {
            Some("https://github.com/chunleik/self-analyst/blob/main/README.zh-CN.md")
        }
        ("guide", _) => Some("https://github.com/chunleik/self-analyst/blob/main/README.md"),
        ("feedback", _) => Some("https://github.com/chunleik/self-analyst/issues"),
        _ => None,
    }
}
#[tauri::command]
pub async fn help_action(
    app: tauri::AppHandle,
    window: WebviewWindow,
    action: String,
    tag: Option<String>,
) -> Result<Option<super::updates::UpdateInfo>, String> {
    let port = managed_port(&app, &window)?;
    if action == "check_update" {
        let permit = super::updates::CheckPermit::acquire()?;
        return tauri::async_runtime::spawn_blocking(move || {
            let _permit = permit;
            super::updates::check().map(Some)
        })
        .await
        .map_err(|_| "update.failed".to_owned())?;
    }
    if action == "download" || action == "releases" {
        let url = if action == "download" {
            super::updates::release_url(tag.as_deref().ok_or("update.invalid")?)?
        } else {
            super::updates::RELEASES_URL.to_owned()
        };
        open::that(url).map_err(|_| "help.failed")?;
        return Ok(None);
    }
    if action == "about" {
        super::show_about_message(port);
        return Ok(None);
    }
    let url = help_url(&action, &super::i18n::effective_language()).ok_or("help.failed")?;
    open::that(url)
        .map(|_| None)
        .map_err(|_| "help.failed".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_other_windows_origins_ports_and_paths() {
        for (label, url, expected) in [
            ("main", "http://localhost:5701/desktop-ui/", true),
            ("main", "http://127.0.0.1:5701/desktop-ui/index.html", true),
            ("other", "http://localhost:5701/desktop-ui/", false),
            ("main", "http://localhost:5702/desktop-ui/", false),
            ("main", "https://example.com/desktop-ui/", false),
            ("main", "http://localhost:5701/desktop/status", false),
        ] {
            assert_eq!(
                authorized(label, &tauri::Url::parse(url).unwrap(), 5701),
                expected
            );
        }
    }
    #[test]
    fn physical_hit_test_obeys_scale_and_leaves_help_in_client() {
        let layout = Layout {
            drag: Rect {
                x: 220.0,
                y: 0.0,
                width: 400.0,
                height: 34.0,
            },
            maximize: Rect {
                x: 700.0,
                y: 0.0,
                width: 46.0,
                height: 34.0,
            },
            scale: 1.5,
            ..Default::default()
        };
        assert_eq!(layout.hit(1080.0, 24.0), HTMAXBUTTON as i32);
        assert_eq!(layout.hit(450.0, 24.0), HTCAPTION as i32);
        assert_eq!(layout.hit(240.0, 24.0), HTCLIENT as i32);
        assert_eq!(layout.hit(1080.0, 60.0), HTCLIENT as i32);
        assert!(!Layout {
            scale: f64::NAN,
            ..layout
        }
        .valid());
    }
    #[test]
    fn help_destinations_are_fixed_and_language_specific() {
        assert!(help_url("guide", "zh")
            .unwrap()
            .ends_with("README.zh-CN.md"));
        assert!(help_url("guide", "en").unwrap().ends_with("README.md"));
        assert!(help_url("feedback", "en").unwrap().ends_with("/issues"));
        assert_eq!(help_url("https://example.com", "zh"), None);
    }

    #[test]
    fn generated_permissions_restrict_all_three_commands_to_managed_remote_window() {
        use tauri::utils::acl::{resolved::Resolved, ExecutionContext};
        let acl = Resolved::resolve(
            &serde_json::from_str(include_str!("../gen/schemas/acl-manifests.json")).unwrap(),
            serde_json::from_str(include_str!("../gen/schemas/capabilities.json")).unwrap(),
            tauri::utils::platform::Target::Windows,
        )
        .unwrap();
        for command in ["titlebar_action", "titlebar_layout", "help_action"] {
            let grants = acl
                .allowed_commands
                .get(command)
                .expect("explicit application grant");
            let allowed = |label: &str, address: &str| {
                let address = tauri::Url::parse(address).unwrap();
                grants.iter().any(|grant| grant.windows.iter().any(|pattern| pattern.matches(label))
                    && matches!(&grant.context, ExecutionContext::Remote { url } if url.test(&address)))
            };
            assert!(allowed("main", "http://localhost:5701/desktop-ui/"));
            assert!(!allowed("other", "http://localhost:5701/desktop-ui/"));
            assert!(!allowed("main", "https://example.com/desktop-ui/"));
            assert!(!allowed(
                "main",
                "http://localhost.evil.test:5701/desktop-ui/"
            ));
            assert!(grants
                .iter()
                .all(|grant| !matches!(grant.context, ExecutionContext::Local)));
        }
    }
}

#[cfg(feature = "titlebar-review")]
pub fn run_titlebar_review() {
    let port: u16 = std::env::var("SELF_ANALYST_TITLEBAR_REVIEW_PORT")
        .expect("fixture port")
        .parse()
        .unwrap();
    tauri::Builder::default()
        .any_thread()
        .invoke_handler(tauri::generate_handler![
            titlebar_action,
            titlebar_layout,
            help_action
        ])
        .setup(move |app| {
            app.manage(super::JavaBackend {
                child: Mutex::new(None),
                job: 0,
                token: "review-only".into(),
                port: Mutex::new(Some(port)),
                port_file: std::env::temp_dir().join("sa-titlebar-review-no-port-file"),
            });
            let window = tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::External(
                    format!("http://localhost:{port}/desktop-ui/")
                        .parse()
                        .unwrap(),
                ),
            )
            .title("SelfAnalyst Titlebar Review")
            .decorations(false)
            .inner_size(1200.0, 800.0)
            .min_inner_size(800.0, 600.0)
            .visible(false)
            .initialization_script(
                "Object.defineProperty(window, '__SELF_ANALYST_DESKTOP__', {value:true});",
            )
            .build()?;
            // Review-only sizing/zoom allows repeatable small-window and scaled-layout QA.
            if std::env::var("SELF_ANALYST_TITLEBAR_REVIEW_SMALL").is_ok() {
                window.set_size(tauri::LogicalSize::new(800.0, 600.0))?;
            }
            if let Ok(zoom) = std::env::var("SELF_ANALYST_TITLEBAR_REVIEW_ZOOM") {
                let zoom: f64 = zoom.parse().expect("review zoom");
                assert!((0.5..=2.0).contains(&zoom));
                window.set_zoom(zoom)?;
            }
            install(&window)?;
            window.show()?;
            if std::env::var("SELF_ANALYST_TITLEBAR_REVIEW_TRAY").is_ok() {
                let (menu, _) = super::create_tray_menu(app.handle())?;
                let ids: Vec<String> = menu
                    .items()?
                    .iter()
                    .map(|item| item.id().as_ref().to_owned())
                    .collect();
                assert_eq!(ids, ["show", "web_desktop", "autostart", "quit"]);
                let menu_window = window.clone();
                window.on_window_event(move |event| {
                    if matches!(event, tauri::WindowEvent::Focused(true)) {
                        let _ =
                            menu_window.popup_menu_at(&menu, tauri::PhysicalPosition::new(60, 80));
                    }
                });
            }
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(900));
                handle.exit(0);
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .unwrap()
        .run_return(|_, _| {});
}
