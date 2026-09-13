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
    use tauri_plugin_shell::ShellExt;
    #[allow(deprecated)]
    app.shell()
        .open(directory.to_string_lossy().as_ref(), None)
        .map_err(|_| fail())
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
