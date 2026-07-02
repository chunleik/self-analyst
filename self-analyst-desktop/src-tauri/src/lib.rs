use std::os::windows::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE},
    System::Threading::CreateMutexW,
    UI::WindowsAndMessaging::{MessageBoxW, MB_ICONINFORMATION, MB_OK, MB_SETFOREGROUND},
};

struct JavaBackend(Mutex<Option<Child>>);

struct SingleInstanceGuard(HANDLE);

impl Drop for SingleInstanceGuard {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                let _ = CloseHandle(self.0);
            }
        }
    }
}

fn acquire_single_instance() -> Option<SingleInstanceGuard> {
    let name: Vec<u16> = "Local\\SelfAnalystDesktopSingleInstance"
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();

    unsafe {
        let handle = CreateMutexW(std::ptr::null(), 0, name.as_ptr());
        if handle.is_null() {
            eprintln!("Failed to create SelfAnalyst single-instance mutex");
            return None;
        }

        if GetLastError() == ERROR_ALREADY_EXISTS {
            let _ = CloseHandle(handle);
            show_already_running_message();
            return None;
        }

        Some(SingleInstanceGuard(handle))
    }
}

fn show_already_running_message() {
    show_message("SelfAnalyst", "SelfAnalyst 已在运行");
}

fn show_about_message() {
    let message = format!(
        "SelfAnalyst v{}\n桌面端: Tauri\n后端服务: {}",
        env!("CARGO_PKG_VERSION"),
        backend_url(backend_port(), "")
    );
    show_message("关于 SelfAnalyst", &message);
}

fn show_message(title: &str, message: &str) {
    let title: Vec<u16> = title.encode_utf16().chain(std::iter::once(0)).collect();
    let message: Vec<u16> = message.encode_utf16().chain(std::iter::once(0)).collect();

    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_OK | MB_ICONINFORMATION | MB_SETFOREGROUND,
        );
    }
}

fn find_java() -> Option<std::path::PathBuf> {
    // Portable build: prefer the JRE bundled next to the exe (dist/runtime/bin/java.exe).
    // This makes the app fully offline — no system Java required.
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("runtime").join("bin").join("java.exe");
            if bundled.exists() { return Some(bundled); }
        }
    }
    // Check JAVA_HOME
    for env_key in &["JAVA_HOME", "JDK_HOME"] {
        if let Ok(home) = std::env::var(env_key) {
            let java = std::path::Path::new(&home).join("bin").join("java.exe");
            if java.exists() { return Some(java); }
        }
    }
    // Check common install paths — prefer highest version >= 21
    let mut candidates: Vec<(u32, std::path::PathBuf)> = Vec::new();
    let program_files = std::env::var("ProgramFiles").unwrap_or_else(|_| "C:\\Program Files".to_string());
    let roots = [
        format!("{}\\Java", program_files),
        format!("{}\\Java", program_files.replace("Program Files", "Program Files (x86)")),
        format!("{}\\Eclipse Adoptium", program_files),
    ];
    for root in &roots {
        if let Ok(entries) = std::fs::read_dir(root) {
            for entry in entries.flatten() {
                let java = entry.path().join("bin").join("java.exe");
                if java.exists() {
                    let ver = parse_java_version(&entry.path().to_string_lossy());
                    candidates.push((ver, java));
                }
            }
        }
    }
    candidates.sort_by(|a, b| b.0.cmp(&a.0));
    for (ver, java) in &candidates {
        if *ver >= 21 { return Some(java.clone()); }
    }
    // Any version if no 21+
    candidates.first().map(|(_, j)| j.clone())
    // Fallback to system PATH (handled by caller with "java")
}

fn parse_java_version(path: &str) -> u32 {
    let lower = path.to_lowercase();
    for prefix in &["jdk-", "jdk", "java-", "jre-"] {
        if let Some(idx) = lower.find(prefix) {
            let rest = &lower[idx + prefix.len()..];
            if let Some(dot) = rest.find('.') {
                return rest[..dot].parse().unwrap_or(0);
            }
            return rest.chars().take_while(|c| c.is_ascii_digit())
                .collect::<String>().parse().unwrap_or(0);
        }
    }
    0
}

fn backend_port() -> u16 {
    std::env::var("AW_PORT")
        .ok()
        .and_then(|v| v.parse::<u16>().ok())
        .filter(|p| *p > 0)
        .unwrap_or(5700)
}

fn backend_url(port: u16, path: &str) -> String {
    format!("http://localhost:{}{}", port, path)
}

fn start_java(app: AppHandle, port: u16) {
    // jar is alongside the exe in dist/
    let exe_dir = std::env::current_exe()
        .unwrap_or_default()
        .parent().unwrap_or(std::path::Path::new("."))
        .to_path_buf();
    let jar = exe_dir.join("self-analyst-app.jar");
    println!("[SelfAnalyst] Jar path: {}", jar.display());

    if !jar.exists() {
        eprintln!("JAR not found: {}", jar.display());
        std::process::exit(1);
    }

    let java = find_java().unwrap_or_else(|| std::path::PathBuf::from("java"));
    eprintln!("Using Java: {}", java.display());

    let child = Command::new(java)
        .creation_flags(0x08000000) // CREATE_NO_WINDOW
        // Run with CWD = exe dir so the backend resolves its relative paths
        // (tools/PaddleOCR-json, tools/whisper, ./data, ./config) next to the exe.
        .current_dir(&exe_dir)
        .env("AW_PORT", port.to_string())
        .arg("-jar")
        .arg(jar.to_string_lossy().to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("Failed to start Java backend");

    app.manage(JavaBackend(Mutex::new(Some(child))));

    // Poll until backend is ready
    let handle = app.clone();
    let health_url = backend_url(port, "/0/info");
    thread::spawn(move || {
        for _ in 0..20 {
            thread::sleep(Duration::from_millis(500));
            if ureq::get(&health_url).call().is_ok() {
                println!("Java backend ready");
                return;
            }
        }
        eprintln!("Java backend failed to start");
        handle.exit(1);
    });
}

fn create_tray(app: &AppHandle) -> tauri::Result<tauri::tray::TrayIcon> {
    let show_item = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
    let web_desktop_item = MenuItem::with_id(app, "web_desktop", "Web版桌面", true, None::<&str>)?;
    let about_item = MenuItem::with_id(app, "about", "关于", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &show_item,
            &web_desktop_item,
            &about_item,
            &quit_item,
        ],
    )?;

    TrayIconBuilder::new()
        .icon(
            app.default_window_icon()
                .cloned()
                .expect("default window icon missing"),
        )
        .menu(&menu)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "web_desktop" => {
                let _ = open::that(backend_url(backend_port(), "/desktop-ui/"));
            }
            "about" => {
                show_about_message();
            }
            "quit" => {
                if let Some(state) = app.try_state::<JavaBackend>() {
                    if let Ok(mut guard) = state.0.lock() {
                        if let Some(ref mut child) = *guard {
                            let _ = child.kill();
                        }
                    }
                }
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
        })
        .build(app)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _single_instance = match acquire_single_instance() {
        Some(guard) => guard,
        None => return,
    };

    // Portable build relies on the system-provided (Evergreen) WebView2 runtime
    // — it is intentionally NOT bundled.

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let port = backend_port();
            start_java(app.handle().clone(), port);
            let _tray = create_tray(app.handle())?;

            let _window = WebviewWindowBuilder::new(
                app, "main",
                WebviewUrl::External(backend_url(port, "/desktop-ui/").parse().unwrap()),
            )
            .title("SelfAnalyst")
            .inner_size(1200.0, 800.0)
            .min_inner_size(800.0, 600.0)
            .center()
            .build()?;

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error building app")
        .run(|app, event| {
            if let RunEvent::WindowEvent {
                event: WindowEvent::CloseRequested { api, .. },
                ..
            } = event
            {
                api.prevent_close();
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.hide();
                }
            }
        });
}
