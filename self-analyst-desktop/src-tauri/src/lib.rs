use std::fs::OpenOptions;
use std::io;
use std::mem::size_of;
use std::os::windows::io::AsRawHandle;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE},
    Security::Cryptography::{BCryptGenRandom, BCRYPT_USE_SYSTEM_PREFERRED_RNG},
    System::{
        JobObjects::{
            AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
            SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
            JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        },
        Threading::CreateMutexW,
    },
    UI::WindowsAndMessaging::{MessageBoxW, MB_ICONINFORMATION, MB_OK, MB_SETFOREGROUND},
};

const STARTUP_PHASE_TIMEOUT: Duration = Duration::from_secs(30);
const BACKEND_JAR: &str = "self-analyst-app.jar";
const INSTALLED_LAYOUT_MARKER: &str = "installed-layout.marker";

struct JavaBackend {
    child: Mutex<Option<Child>>,
    job: isize,
    token: String,
    port: Mutex<Option<u16>>,
    port_file: std::path::PathBuf,
}

impl JavaBackend {
    fn shutdown_gracefully(&self) {
        let port = self.port.lock().ok().and_then(|guard| *guard);
        if let Some(port) = port {
            let url = backend_url(port, "/desktop/lifecycle/shutdown");
            let agent: ureq::Agent = ureq::Agent::config_builder()
                .timeout_global(Some(Duration::from_secs(2)))
                .build()
                .into();
            let _ = agent
                .post(&url)
                .header("X-SelfAnalyst-Token", &self.token)
                .send_empty();
        }

        if let Ok(mut guard) = self.child.lock() {
            if let Some(mut child) = guard.take() {
                for _ in 0..100 {
                    match child.try_wait() {
                        Ok(Some(_)) => return,
                        Ok(None) => thread::sleep(Duration::from_millis(100)),
                        Err(_) => break,
                    }
                }
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

impl Drop for JavaBackend {
    fn drop(&mut self) {
        if self.job != 0 {
            unsafe {
                let _ = CloseHandle(self.job as HANDLE);
            }
        }
        if let Ok(child) = self.child.get_mut() {
            if let Some(mut child) = child.take() {
                let _ = child.wait();
            }
        }
        let _ = std::fs::remove_file(&self.port_file);
    }
}

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

fn show_about_message(port: u16) {
    let message = format!(
        "SelfAnalyst v{}\n桌面端: Tauri\n后端服务: {}",
        env!("CARGO_PKG_VERSION"),
        backend_url(port, "")
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

fn find_java(distribution_root: Option<&Path>) -> Option<PathBuf> {
    // Portable and installed builds both carry a runtime under their resolved
    // distribution root. This keeps either form independent of system Java.
    if let Some(root) = distribution_root {
        let bundled = root.join("runtime").join("bin").join("java.exe");
        if bundled.exists() {
            return Some(bundled);
        }
    }
    // Check JAVA_HOME
    for env_key in &["JAVA_HOME", "JDK_HOME"] {
        if let Ok(home) = std::env::var(env_key) {
            let java = std::path::Path::new(&home).join("bin").join("java.exe");
            if java.exists() {
                return Some(java);
            }
        }
    }
    // Check common install paths — prefer highest version >= 21
    let mut candidates: Vec<(u32, std::path::PathBuf)> = Vec::new();
    let program_files =
        std::env::var("ProgramFiles").unwrap_or_else(|_| "C:\\Program Files".to_string());
    let roots = [
        format!("{}\\Java", program_files),
        format!(
            "{}\\Java",
            program_files.replace("Program Files", "Program Files (x86)")
        ),
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
    candidates.sort_by_key(|candidate| std::cmp::Reverse(candidate.0));
    for (ver, java) in &candidates {
        if *ver >= 21 {
            return Some(java.clone());
        }
    }
    // Any version if no 21+
    candidates.first().map(|(_, j)| j.clone())
    // Fallback to system PATH (handled by caller with "java")
}

fn select_distribution_root(
    exe_dir: &Path,
    resource_dir: Option<&Path>,
) -> Option<(PathBuf, bool)> {
    if let Some(resources) = resource_dir {
        if resources.join(INSTALLED_LAYOUT_MARKER).is_file() {
            return resources
                .join(BACKEND_JAR)
                .is_file()
                .then(|| (resources.to_path_buf(), true));
        }
    }
    if exe_dir.join(BACKEND_JAR).is_file() {
        return Some((exe_dir.to_path_buf(), false));
    }
    resource_dir.and_then(|resources| {
        resources
            .join(BACKEND_JAR)
            .is_file()
            .then(|| (resources.to_path_buf(), true))
    })
}

fn parse_java_version(path: &str) -> u32 {
    let lower = path.to_lowercase();
    for prefix in &["jdk-", "jdk", "java-", "jre-"] {
        if let Some(idx) = lower.find(prefix) {
            let rest = &lower[idx + prefix.len()..];
            if let Some(dot) = rest.find('.') {
                return rest[..dot].parse().unwrap_or(0);
            }
            return rest
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect::<String>()
                .parse()
                .unwrap_or(0);
        }
    }
    0
}

fn backend_url(port: u16, path: &str) -> String {
    format!("http://localhost:{}{}", port, path)
}

fn parse_backend_port(raw: &str) -> io::Result<u16> {
    raw.trim()
        .parse::<u16>()
        .ok()
        .filter(|port| *port > 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid backend port"))
}

fn lifecycle_token() -> io::Result<String> {
    let mut bytes = [0u8; 32];
    let status = unsafe {
        BCryptGenRandom(
            std::ptr::null_mut(),
            bytes.as_mut_ptr(),
            bytes.len() as u32,
            BCRYPT_USE_SYSTEM_PREFERRED_RNG,
        )
    };
    if status < 0 {
        return Err(io::Error::other(format!(
            "BCryptGenRandom failed: {status}"
        )));
    }
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

fn create_kill_on_close_job(child: &Child) -> io::Result<isize> {
    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return Err(io::Error::last_os_error());
        }
        let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &limits as *const _ as *const std::ffi::c_void,
            size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        ) == 0
        {
            let error = io::Error::last_os_error();
            let _ = CloseHandle(job);
            return Err(error);
        }
        if AssignProcessToJobObject(job, child.as_raw_handle() as HANDLE) == 0 {
            let error = io::Error::last_os_error();
            let _ = CloseHandle(job);
            return Err(error);
        }
        Ok(job as isize)
    }
}

fn start_java(app: AppHandle) {
    let exe_dir = std::env::current_exe()
        .unwrap_or_default()
        .parent()
        .unwrap_or(Path::new("."))
        .to_path_buf();
    let resource_dir = app.path().resource_dir().ok();
    let (distribution_root, installed) =
        select_distribution_root(&exe_dir, resource_dir.as_deref()).unwrap_or_else(|| {
            eprintln!("JAR not found in executable or resource directory");
            std::process::exit(1);
        });
    let working_dir = if installed {
        let directory = app.path().app_local_data_dir().unwrap_or_else(|error| {
            eprintln!("Failed to resolve application data directory: {error}");
            std::process::exit(1);
        });
        std::fs::create_dir_all(&directory).unwrap_or_else(|error| {
            eprintln!("Failed to create application data directory: {error}");
            std::process::exit(1);
        });
        directory
    } else {
        exe_dir
    };
    let jar = distribution_root.join(BACKEND_JAR);
    println!("[SelfAnalyst] Jar path: {}", jar.display());

    let java = find_java(Some(&distribution_root)).unwrap_or_else(|| PathBuf::from("java"));
    eprintln!("Using Java: {}", java.display());

    let token = lifecycle_token().expect("Failed to create desktop lifecycle token");
    let port_file_nonce = lifecycle_token().expect("Failed to create desktop port-file nonce");
    let port_file = std::env::temp_dir().join(format!("self-analyst-port-{port_file_nonce}.txt"));
    let _ = std::fs::remove_file(&port_file);
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(working_dir.join("self-analyst-backend.log"))
        .expect("Failed to open backend log");
    let error_log = log_file
        .try_clone()
        .expect("Failed to clone backend log handle");
    let mut child = Command::new(java)
        .creation_flags(0x08000000) // CREATE_NO_WINDOW
        // Portable mode keeps data beside the executable. Installed mode uses
        // the per-user application data directory so upgrades/uninstalls do not
        // overwrite the user's databases and configuration.
        .current_dir(&working_dir)
        .env("SELF_ANALYST_DESKTOP_TOKEN", &token)
        .env("SELF_ANALYST_DESKTOP_PORT_FILE", &port_file)
        .arg("-jar")
        .arg(jar.to_string_lossy().to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(error_log))
        .spawn()
        .expect("Failed to start Java backend");

    let job = create_kill_on_close_job(&child).unwrap_or_else(|error| {
        let _ = child.kill();
        let _ = child.wait();
        panic!("Failed to secure Java backend in a Windows Job Object: {error}");
    });
    app.manage(JavaBackend {
        child: Mutex::new(Some(child)),
        job,
        token: token.clone(),
        port: Mutex::new(None),
        port_file: port_file.clone(),
    });

    // Java owns config.toml parsing and publishes the effective port only after
    // its authenticated desktop lifecycle routes are ready.
    let handle = app.clone();
    let health_token = token.clone();
    thread::spawn(move || {
        let port_deadline = Instant::now() + STARTUP_PHASE_TIMEOUT;
        let port = loop {
            if Instant::now() >= port_deadline {
                eprintln!("Java backend did not publish its configured port");
                handle.exit(1);
                return;
            }
            thread::sleep(Duration::from_millis(100));
            match std::fs::read_to_string(&port_file) {
                Ok(raw) => match parse_backend_port(&raw) {
                    Ok(port) => break port,
                    Err(error) => {
                        eprintln!("Java backend published an invalid port: {error}");
                        let _ = std::fs::remove_file(&port_file);
                        handle.exit(1);
                        return;
                    }
                },
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    if backend_exited(&handle) {
                        handle.exit(1);
                        return;
                    }
                }
                Err(error) => {
                    eprintln!("Failed to read Java backend port: {error}");
                    handle.exit(1);
                    return;
                }
            }
        };
        let _ = std::fs::remove_file(&port_file);
        if let Some(state) = handle.try_state::<JavaBackend>() {
            if let Ok(mut guard) = state.port.lock() {
                *guard = Some(port);
            }
        }

        let health_url = backend_url(port, "/desktop/lifecycle/health");
        let health_deadline = Instant::now() + STARTUP_PHASE_TIMEOUT;
        while Instant::now() < health_deadline {
            let remaining = health_deadline.saturating_duration_since(Instant::now());
            let request_timeout = std::cmp::min(remaining, Duration::from_secs(2));
            let agent: ureq::Agent = ureq::Agent::config_builder()
                .timeout_global(Some(request_timeout))
                .build()
                .into();
            if agent
                .get(&health_url)
                .header("X-SelfAnalyst-Token", &health_token)
                .call()
                .is_ok()
            {
                println!("Java backend ready");
                let window_handle = handle.clone();
                let window_token = health_token.clone();
                if let Err(error) = handle.run_on_main_thread(move || {
                    if let Err(error) = create_tray(&window_handle, &window_token, port) {
                        eprintln!("Failed to create tray icon: {error}");
                        window_handle.exit(1);
                        return;
                    }
                    if let Err(error) = create_main_window(&window_handle, port, &window_token) {
                        eprintln!("Failed to create desktop window: {error}");
                        window_handle.exit(1);
                    }
                }) {
                    eprintln!("Failed to schedule desktop window creation: {error}");
                    handle.exit(1);
                }
                return;
            }
            if backend_exited(&handle) {
                handle.exit(1);
                return;
            }
            let remaining = health_deadline.saturating_duration_since(Instant::now());
            if !remaining.is_zero() {
                thread::sleep(std::cmp::min(remaining, Duration::from_millis(500)));
            }
        }
        eprintln!("Java backend failed to start");
        handle.exit(1);
    });
}

fn backend_exited(app: &AppHandle) -> bool {
    if let Some(state) = app.try_state::<JavaBackend>() {
        if let Ok(mut guard) = state.child.lock() {
            if let Some(child) = guard.as_mut() {
                if matches!(child.try_wait(), Ok(Some(_))) {
                    eprintln!("Java backend exited during startup; see self-analyst-backend.log");
                    return true;
                }
            }
        }
    }
    false
}

fn create_main_window(app: &AppHandle, port: u16, token: &str) -> tauri::Result<()> {
    let auth_script = desktop_auth_script(token);

    WebviewWindowBuilder::new(
        app,
        "main",
        WebviewUrl::External(backend_url(port, "/desktop-ui/").parse().unwrap()),
    )
    .title("SelfAnalyst")
    .inner_size(1200.0, 800.0)
    .min_inner_size(800.0, 600.0)
    .center()
    .initialization_script(auth_script)
    .build()?;
    Ok(())
}

fn desktop_auth_script(token: &str) -> String {
    format!(
        r#"
        (() => {{
            const originalFetch = window.fetch.bind(window);
            window.fetch = (input, init = {{}}) => {{
                const rawUrl = typeof input === 'string' ? input : input.url;
                const url = new URL(rawUrl, window.location.href);
                if (url.origin === window.location.origin && url.pathname.startsWith('/desktop/')) {{
                    const inherited = input instanceof Request ? input.headers : undefined;
                    const headers = new Headers(init.headers || inherited);
                    headers.set('X-SelfAnalyst-Token', '{}');
                    init = {{ ...init, headers }};
                }}
                return originalFetch(input, init);
            }};
        }})();
        "#,
        token
    )
}

fn create_tray(app: &AppHandle, token: &str, port: u16) -> tauri::Result<tauri::tray::TrayIcon> {
    let show_item = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
    let web_desktop_item = MenuItem::with_id(app, "web_desktop", "Web版桌面", true, None::<&str>)?;
    let about_item = MenuItem::with_id(app, "about", "关于", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[&show_item, &web_desktop_item, &about_item, &quit_item],
    )?;

    let desktop_session_url = format!("{}?token={}", backend_url(port, "/desktop/session"), token);
    TrayIconBuilder::new()
        .icon(
            app.default_window_icon()
                .cloned()
                .expect("default window icon missing"),
        )
        .menu(&menu)
        .on_menu_event(move |app, event| match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "web_desktop" => {
                let _ = open::that(&desktop_session_url);
            }
            "about" => {
                show_about_message(port);
            }
            "quit" => {
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
            start_java(app.handle().clone());

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error building app")
        .run(|app, event| {
            if let RunEvent::ExitRequested { .. } = event {
                if let Some(state) = app.try_state::<JavaBackend>() {
                    state.shutdown_gracefully();
                }
            } else if let RunEvent::WindowEvent {
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

#[cfg(test)]
mod tests {
    use super::{
        desktop_auth_script, parse_backend_port, select_distribution_root, BACKEND_JAR,
        INSTALLED_LAYOUT_MARKER,
    };
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temporary_layout(name: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "self-analyst-desktop-{name}-{}-{nonce}",
            std::process::id()
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn parses_published_backend_port() {
        assert_eq!(parse_backend_port(" 45731\r\n").unwrap(), 45731);
    }

    #[test]
    fn rejects_invalid_published_backend_port() {
        assert!(parse_backend_port("0").is_err());
        assert!(parse_backend_port("65536").is_err());
        assert!(parse_backend_port("not-a-port").is_err());
    }

    #[test]
    fn desktop_token_script_is_scoped_to_same_origin_desktop_paths() {
        let script = desktop_auth_script("launch-secret");
        assert!(script.contains("url.origin === window.location.origin"));
        assert!(script.contains("url.pathname.startsWith('/desktop/')"));
        assert!(script.contains("X-SelfAnalyst-Token"));
        assert!(script.contains("launch-secret"));
        assert!(!script.contains("url.searchParams.get('token')"));
    }

    #[test]
    fn portable_distribution_uses_executable_directory() {
        let root = temporary_layout("portable");
        let executable = root.join("bin");
        fs::create_dir_all(&executable).unwrap();
        fs::write(executable.join(BACKEND_JAR), b"jar").unwrap();

        let selected = select_distribution_root(&executable, None).unwrap();
        assert_eq!(selected, (executable, false));

        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn installed_distribution_uses_marked_resource_directory() {
        let root = temporary_layout("installed");
        let executable = root.join("bin");
        let resources = root.join("resources");
        fs::create_dir_all(&executable).unwrap();
        fs::create_dir_all(&resources).unwrap();
        fs::write(executable.join(BACKEND_JAR), b"stale portable jar").unwrap();
        fs::write(resources.join(BACKEND_JAR), b"installed jar").unwrap();
        fs::write(resources.join(INSTALLED_LAYOUT_MARKER), b"installed\n").unwrap();

        let selected = select_distribution_root(&executable, Some(&resources)).unwrap();
        assert_eq!(selected, (resources, true));

        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn corrupt_installed_distribution_fails_closed() {
        let root = temporary_layout("corrupt-installed");
        let executable = root.join("bin");
        let resources = root.join("resources");
        fs::create_dir_all(&executable).unwrap();
        fs::create_dir_all(&resources).unwrap();
        fs::write(executable.join(BACKEND_JAR), b"untrusted fallback").unwrap();
        fs::write(resources.join(INSTALLED_LAYOUT_MARKER), b"installed\n").unwrap();

        assert!(select_distribution_root(&executable, Some(&resources)).is_none());

        fs::remove_dir_all(root).unwrap();
    }
}
