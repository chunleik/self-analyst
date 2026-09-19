use std::{
    fs, io,
    path::{Path, PathBuf},
};
use tauri::Manager;

#[tauri::command]
pub fn open_data_directory(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
) -> Result<(), String> {
    let fail = || "storage.openFailed".to_owned();
    if window.label() != "main" {
        return Err(fail());
    }
    let backend = app.state::<super::JavaBackend>();
    let port = backend.port.lock().map_err(|_| fail())?.ok_or_else(fail)?;
    if !super::documents::trusted(&window.url().map_err(|_| fail())?, port) {
        return Err(fail());
    }
    let storage = app.state::<RuntimeStorage>();
    let directory = storage.root.join("data");
    if !directory.is_dir() {
        return Err(fail());
    }
    open_directory(&directory).map_err(|_| fail())
}

fn open_directory(directory: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::UI::{Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL};

    // Use the OS directory verb directly: a detached launcher only acknowledges
    // creation of its intermediary process, not whether the shell accepts the path.
    let path: Vec<u16> = directory.as_os_str().encode_wide().chain(Some(0)).collect();
    let operation: Vec<u16> = "open".encode_utf16().chain(Some(0)).collect();
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            operation.as_ptr(),
            path.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    } as isize;
    shell_open_result(result)
}

fn shell_open_result(result: isize) -> io::Result<()> {
    if result > 32 {
        Ok(())
    } else {
        Err(io::Error::other("directory open failed"))
    }
}

pub struct RuntimeStorage {
    pub root: PathBuf,
    pub mode: &'static str,
}

pub fn select(
    executable: &Path,
    installed: bool,
    user_root: impl FnOnce() -> io::Result<PathBuf>,
) -> io::Result<RuntimeStorage> {
    let portable = if installed {
        false
    } else {
        match fs::symlink_metadata(executable.join("portable.marker")) {
            Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => true,
            Ok(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "portable.marker must be a regular file",
                ))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => false,
            Err(error) => return Err(error),
        }
    };
    let root = if portable {
        executable.to_path_buf()
    } else {
        user_root()?
    };
    fs::create_dir_all(&root)?;
    Ok(RuntimeStorage {
        root,
        mode: if portable { "portable" } else { "user" },
    })
}

pub fn failure_key(code: i32) -> Option<&'static str> {
    match code {
        20 => Some("dataInUse"),
        21 => Some("dataLockUnavailable"),
        22 => Some("dataFormatUnsupported"),
        23 => Some("dataFormatInvalid"),
        24 => Some("dataRootUnavailable"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_shell_result_rejects_all_error_codes() {
        for code in 0..=32 {
            assert!(shell_open_result(code).is_err());
        }
        assert!(shell_open_result(33).is_ok());
        assert!(shell_open_result(isize::MAX).is_ok());
    }

    #[test]
    fn generated_acl_allows_data_directory_only_from_managed_main_window() {
        use tauri::utils::acl::{resolved::Resolved, ExecutionContext};
        let manifests = serde_json::from_str(include_str!("../gen/schemas/acl-manifests.json"))
            .expect("generated permission manifests");
        let capabilities = serde_json::from_str(include_str!("../gen/schemas/capabilities.json"))
            .expect("generated capabilities");
        let acl = Resolved::resolve(
            &manifests,
            capabilities,
            tauri::utils::platform::Target::Windows,
        )
        .expect("resolve desktop ACL");
        let grants = acl
            .allowed_commands
            .get("open_data_directory")
            .expect("open_data_directory must have an explicit application permission");
        let allowed = |window: &str, address: &str| {
            let address = tauri::Url::parse(address).unwrap();
            let config: tauri::Config =
                serde_json::from_str(include_str!("../tauri.conf.json")).expect("desktop config");
            // Tauri classifies a page relative to devUrl as Local, even when
            // WebviewUrl::External was used to create the window.
            let local_in_dev = config
                .build
                .dev_url
                .as_ref()
                .is_some_and(|base| base.make_relative(&address).is_some());
            grants.iter().any(|grant| {
                grant.windows.iter().any(|pattern| pattern.matches(window))
                    && match &grant.context {
                        ExecutionContext::Local => local_in_dev,
                        ExecutionContext::Remote { url } => !local_in_dev && url.test(&address),
                    }
            })
        };
        for host in ["localhost", "127.0.0.1"] {
            for port in [5700, 5701] {
                let address = format!("http://{host}:{port}/desktop-ui/index.html");
                assert!(
                    allowed("main", &address),
                    "managed development page denied: {address}"
                );
                assert!(!allowed("other", &address));
            }
        }
        for address in [
            "https://example.com/desktop-ui/",
            "http://localhost.evil.test:5701/desktop-ui/",
        ] {
            assert!(!allowed("main", address));
        }
        assert!(grants
            .iter()
            .all(|grant| !matches!(grant.context, ExecutionContext::Local)));
    }

    #[test]
    fn layout_selection_preserves_user_root_and_installer_priority() {
        let base = std::env::temp_dir().join(format!(
            "sa-runtime-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let exe = base.join("exe");
        let user = base.join("user");
        fs::create_dir_all(exe.join("data")).unwrap();
        let selected = select(&exe, false, || Ok(user.clone())).unwrap();
        assert_eq!(selected.mode, "user");
        assert_eq!(selected.root, user);
        fs::write(exe.join("portable.marker"), "").unwrap();
        assert_eq!(
            select(&exe, false, || panic!("portable must not need user root"))
                .unwrap()
                .mode,
            "portable"
        );
        assert_eq!(
            select(&exe, true, || Ok(user.clone())).unwrap().mode,
            "user"
        );
        assert_eq!(
            select(&base.join("other-version"), false, || Ok(user.clone()))
                .unwrap()
                .root,
            selected.root
        );
        fs::remove_file(exe.join("portable.marker")).unwrap();
        fs::create_dir(exe.join("portable.marker")).unwrap();
        assert!(select(&exe, false, || Ok(user.clone())).is_err());
        assert!(select(&base.join("missing"), false, || Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "denied"
        )))
        .is_err());
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn maps_only_known_failure_codes() {
        assert_eq!(failure_key(20), Some("dataInUse"));
        assert_eq!(failure_key(22), Some("dataFormatUnsupported"));
        assert_eq!(failure_key(0), None);
        assert_eq!(failure_key(1), None);
    }
}
