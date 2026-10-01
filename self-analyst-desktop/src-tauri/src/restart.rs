//! Explicit desktop restart; no arguments from the WebView or previous autostart.
use std::{io, path::Path, process::Command, sync::Mutex};
use tauri::Manager;

#[derive(Default)]
pub struct Restart(Mutex<Option<Command>>);

impl Restart {
    fn request(&self, prepare: impl FnOnce() -> io::Result<Command>) -> Result<bool, String> {
        let mut pending = self.0.lock().map_err(|_| "config.restartFailed")?;
        if pending.is_some() {
            return Ok(false);
        }
        *pending = Some(prepare().map_err(|_| "config.restartFailed")?);
        Ok(true)
    }

    pub fn finish(
        &self,
        cleanup: impl FnOnce(),
        launch: impl FnOnce(&mut Command) -> io::Result<()>,
    ) -> io::Result<()> {
        if let Some(mut command) = self
            .0
            .lock()
            .map_err(|_| io::Error::other("restart lock"))?
            .take()
        {
            cleanup();
            launch(&mut command)?;
        }
        Ok(())
    }
}

fn command(executable: &Path, directory: &Path) -> Command {
    let mut command = Command::new(executable);
    command.current_dir(directory);
    // No inherited --autostart: an explicit restart must show the main window.
    command
}

fn allowed(label: &str, url: &tauri::Url, port: u16) -> bool {
    label == "main" && super::documents::trusted(url, port)
}

#[tauri::command]
pub fn restart_application(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
) -> Result<(), String> {
    let fail = || "config.restartFailed".to_owned();
    let backend = app.try_state::<super::JavaBackend>().ok_or_else(fail)?;
    let port = backend.port.lock().map_err(|_| fail())?.ok_or_else(fail)?;
    if !allowed(window.label(), &window.url().map_err(|_| fail())?, port) {
        return Err(fail());
    }
    let restart = app.state::<std::sync::Arc<Restart>>();
    if restart.request(|| {
        Ok(command(
            &std::env::current_exe()?,
            &std::env::current_dir()?,
        ))
    })? {
        app.exit(0);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn generated_acl_only_grants_managed_remote_main_window() {
        use tauri::utils::acl::{resolved::Resolved, ExecutionContext};
        let acl = Resolved::resolve(
            &serde_json::from_str(include_str!("../gen/schemas/acl-manifests.json")).unwrap(),
            serde_json::from_str(include_str!("../gen/schemas/capabilities.json")).unwrap(),
            tauri::utils::platform::Target::Windows,
        )
        .unwrap();
        let grants = acl
            .allowed_commands
            .get("restart_application")
            .expect("restart permission");
        let granted = |label: &str, address: &str| {
            let address = tauri::Url::parse(address).unwrap();
            grants.iter().any(|grant| grant.windows.iter().any(|pattern| pattern.matches(label))
                && matches!(&grant.context, ExecutionContext::Remote { url } if url.test(&address)))
        };
        assert!(granted("main", "http://localhost:5812/desktop-ui/"));
        assert!(!granted("other", "http://localhost:5812/desktop-ui/"));
        assert!(!granted("main", "https://example.com/desktop-ui/"));
        assert!(grants
            .iter()
            .all(|grant| !matches!(grant.context, ExecutionContext::Local)));
    }

    #[test]
    fn accepts_only_the_managed_main_page() {
        let good = tauri::Url::parse("http://localhost:5812/desktop-ui/index.html").unwrap();
        assert!(allowed("main", &good, 5812));
        assert!(!allowed("other", &good, 5812));
        assert!(!allowed("main", &good, 5813));
        for url in [
            "https://localhost:5812/desktop-ui/",
            "http://localhost:5812/other/",
            "http://localhost.evil.test:5812/desktop-ui/",
            "http://user@localhost:5812/desktop-ui/",
        ] {
            assert!(!allowed("main", &tauri::Url::parse(url).unwrap(), 5812));
        }
    }

    #[test]
    fn one_request_cleans_up_before_launch_and_preserves_location_without_arguments() {
        let restart = Restart::default();
        let path = Path::new(r"C:\Program Files\SelfAnalyst\SelfAnalyst.exe");
        let cwd = Path::new(r"C:\Users\test\runtime");
        assert!(restart.request(|| Ok(command(path, cwd))).unwrap());
        assert!(!restart.request(|| panic!("duplicate request")).unwrap());
        let order = RefCell::new(Vec::new());
        restart
            .finish(
                || order.borrow_mut().push("stop and release"),
                |cmd| {
                    order.borrow_mut().push("launch");
                    assert_eq!(cmd.get_program(), path.as_os_str());
                    assert_eq!(cmd.get_current_dir(), Some(cwd));
                    assert_eq!(cmd.get_args().count(), 0);
                    Ok(())
                },
            )
            .unwrap();
        assert_eq!(*order.borrow(), ["stop and release", "launch"]);
        restart
            .finish(
                || panic!("already consumed"),
                |_| panic!("already launched"),
            )
            .unwrap();
    }

    #[test]
    fn preparation_failure_allows_retry_and_launch_failure_is_reported() {
        let restart = Restart::default();
        assert!(restart
            .request(|| Err(io::Error::other("unavailable")))
            .is_err());
        assert!(restart.request(|| Ok(Command::new("test.exe"))).unwrap());
        assert!(restart
            .finish(|| {}, |_| Err(io::Error::other("launch failed")))
            .is_err());
    }
}
