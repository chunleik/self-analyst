use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tauri::Manager;
use windows_sys::Win32::Storage::FileSystem::{MoveFileExW, ReplaceFileW, MOVEFILE_WRITE_THROUGH};
use windows_sys::Win32::UI::Controls::Dialogs::{
    CommDlgExtendedError, GetSaveFileNameW, OFN_EXPLORER, OFN_NOCHANGEDIR, OFN_OVERWRITEPROMPT,
    OFN_PATHMUSTEXIST, OPENFILENAMEW,
};

const MAX_BYTES: u64 = 50 * 1024 * 1024;
fn valid_id(id: &str) -> bool {
    id.len() == 32
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn wide(path: &Path) -> Vec<u16> {
    path.as_os_str().encode_wide().chain(Some(0)).collect()
}

pub(super) fn trusted(url: &tauri::Url, port: u16) -> bool {
    url.scheme() == "http"
        && matches!(url.host_str(), Some("localhost" | "127.0.0.1"))
        && url.port_or_known_default() == Some(port)
        && url.path().starts_with("/desktop-ui/")
        && url.username().is_empty()
        && url.password().is_none()
}

#[tauri::command]
pub async fn save_document(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
    session_id: String,
    artifact_id: String,
) -> Result<String, String> {
    if window.label() != "main" || !valid_id(&session_id) || !valid_id(&artifact_id) {
        return Err("document.saveFailed".into());
    }
    let backend = app.state::<super::JavaBackend>();
    let port = backend
        .port
        .lock()
        .map_err(|_| "document.saveFailed")?
        .ok_or("document.saveFailed")?;
    if !trusted(&window.url().map_err(|_| "document.saveFailed")?, port) {
        return Err("document.saveFailed".into());
    }
    let token = backend.token.clone();
    tauri::async_runtime::spawn_blocking(move || save(port, &token, &session_id, &artifact_id))
        .await
        .map_err(|_| "document.saveFailed".to_string())?
}

fn save(port: u16, token: &str, session: &str, artifact: &str) -> Result<String, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(120)))
        .build()
        .into();
    let base = super::backend_url(
        port,
        &format!("/desktop/chat/sessions/{session}/documents/{artifact}"),
    );
    let mut response = agent
        .get(&base)
        .header("X-SelfAnalyst-Token", token)
        .call()
        .map_err(|_| "document.saveFailed")?;
    let metadata: serde_json::Value = serde_json::from_str(
        &response
            .body_mut()
            .with_config()
            .limit(16384)
            .read_to_string()
            .map_err(|_| "document.saveFailed")?,
    )
    .map_err(|_| "document.saveFailed")?;
    if metadata["status"] != "READY"
        || metadata["id"] != artifact
        || metadata["sessionId"] != session
    {
        return Err("document.saveFailed".into());
    }
    let name = metadata["name"].as_str().ok_or("document.saveFailed")?;
    if name.contains(['/', '\\', '\0']) || name.encode_utf16().count() > 240 {
        return Err("document.saveFailed".into());
    }
    let extension = Path::new(name)
        .extension()
        .and_then(|v| v.to_str())
        .ok_or("document.saveFailed")?;
    if !supported_extension(extension) {
        return Err("document.saveFailed".into());
    }
    let size = metadata["size"]
        .as_u64()
        .filter(|n| *n <= MAX_BYTES)
        .ok_or("document.saveFailed")?;
    let checksum = metadata["sha256"].as_str().ok_or("document.saveFailed")?;
    let Some(target) = choose(name, extension)? else {
        return Ok("cancelled".into());
    };
    if target
        .extension()
        .and_then(|v| v.to_str())
        .map(|v| v.eq_ignore_ascii_case(extension))
        != Some(true)
    {
        return Err("document.saveFailed".into());
    }
    let parent = target
        .parent()
        .ok_or("document.saveFailed")?
        .canonicalize()
        .map_err(|_| "document.saveFailed")?;
    let target = parent.join(target.file_name().ok_or("document.saveFailed")?);
    if let Ok(executable) = std::env::current_exe() {
        if executable
            .parent()
            .map(|directory| target.starts_with(directory))
            .unwrap_or(false)
        {
            return Err("document.saveFailed".into());
        }
    }
    let check =
        serde_json::json!({"path": target.to_str().ok_or("document.saveFailed")?}).to_string();
    agent
        .post(format!("{base}/save-target"))
        .header("X-SelfAnalyst-Token", token)
        .header("Content-Type", "application/json")
        .send(check.as_bytes())
        .map_err(|_| "document.saveFailed")?;
    let previous = snapshot(&target)?;
    let temporary = parent.join(format!(
        ".selfanalyst-save-{}.tmp",
        super::lifecycle_token().map_err(|_| "document.saveFailed")?
    ));
    let result = (|| {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "document.saveFailed")?;
        let mut response = agent
            .get(format!("{base}/content"))
            .header("X-SelfAnalyst-Token", token)
            .call()
            .map_err(|_| "document.saveFailed")?;
        let mut input = response.body_mut().as_reader();
        let mut hash = Sha256::new();
        let mut count = 0u64;
        let mut buffer = [0u8; 65536];
        loop {
            let n = input.read(&mut buffer).map_err(|_| "document.saveFailed")?;
            if n == 0 {
                break;
            }
            count += n as u64;
            if count > size || count > MAX_BYTES {
                return Err("document.saveFailed".into());
            }
            hash.update(&buffer[..n]);
            output
                .write_all(&buffer[..n])
                .map_err(|_| "document.saveFailed")?;
        }
        if count != size || format!("{:x}", hash.finalize()) != checksum {
            return Err("document.saveFailed".into());
        }
        output.sync_all().map_err(|_| "document.saveFailed")?;
        drop(output);
        if snapshot(&target)? != previous {
            return Err("document.saveFailed".into());
        }
        replace(&temporary, &target, previous.is_some())?;
        Ok("saved".into())
    })();
    if temporary.exists() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn supported_extension(extension: &str) -> bool {
    [
        "csv", "json", "md", "xlsx", "docx", "pdf", "pptx", "html", "svg",
    ]
    .contains(&extension)
}

fn choose(name: &str, extension: &str) -> Result<Option<PathBuf>, String> {
    let mut buffer = vec![0u16; 32768];
    let initial: Vec<u16> = name.encode_utf16().collect();
    buffer[..initial.len()].copy_from_slice(&initial);
    let filter: Vec<u16> = format!("{extension}\0*.{extension}\0\0")
        .encode_utf16()
        .collect();
    let extension: Vec<u16> = extension.encode_utf16().chain(Some(0)).collect();
    let mut options: OPENFILENAMEW = unsafe { std::mem::zeroed() };
    options.lStructSize = std::mem::size_of::<OPENFILENAMEW>() as u32;
    options.lpstrFilter = filter.as_ptr();
    options.lpstrFile = buffer.as_mut_ptr();
    options.nMaxFile = buffer.len() as u32;
    options.lpstrDefExt = extension.as_ptr();
    options.Flags = OFN_EXPLORER | OFN_NOCHANGEDIR | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    if unsafe { GetSaveFileNameW(&mut options) } == 0 {
        return if unsafe { CommDlgExtendedError() } == 0 {
            Ok(None)
        } else {
            Err("document.saveFailed".into())
        };
    }
    let length = buffer
        .iter()
        .position(|v| *v == 0)
        .ok_or("document.saveFailed")?;
    Ok(Some(PathBuf::from(
        String::from_utf16(&buffer[..length]).map_err(|_| "document.saveFailed")?,
    )))
}

fn snapshot(path: &Path) -> Result<Option<(u64, std::time::SystemTime)>, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => Ok(Some((
            metadata.len(),
            metadata.modified().map_err(|_| "document.saveFailed")?,
        ))),
        Ok(_) => Err("document.saveFailed".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err("document.saveFailed".into()),
    }
}
fn replace(temporary: &Path, target: &Path, exists: bool) -> Result<(), String> {
    let source = wide(temporary);
    let destination = wide(target);
    let success = unsafe {
        if exists {
            ReplaceFileW(
                destination.as_ptr(),
                source.as_ptr(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                std::ptr::null(),
            )
        } else {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                MOVEFILE_WRITE_THROUGH,
            )
        }
    };
    if success == 0 {
        return Err("document.saveFailed".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identifiers_and_origin_are_restricted() {
        assert!(supported_extension("html"));
        assert!(supported_extension("svg"));
        assert!(!supported_extension("exe"));
        assert!(!supported_extension("html.exe"));
        assert!(valid_id(&"a".repeat(32)));
        assert!(!valid_id("../../private"));
        assert!(!valid_id(&"A".repeat(32)));
        assert!(trusted(
            &tauri::Url::parse("http://localhost:5701/desktop-ui/index.html").unwrap(),
            5701
        ));
        for url in [
            "http://localhost:5700/desktop-ui/index.html",
            "https://example.com/desktop-ui/index.html",
            "http://localhost:5701/other",
        ] {
            assert!(!trusted(&tauri::Url::parse(url).unwrap(), 5701));
        }
    }
    #[test]
    fn replacement_preserves_target_on_missing_source() {
        let root = std::env::temp_dir().join(super::super::lifecycle_token().unwrap());
        fs::create_dir(&root).unwrap();
        let target = root.join("中文 文档.pdf");
        fs::write(&target, b"old").unwrap();
        assert!(replace(&root.join("missing"), &target, true).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"old");
        let temporary = root.join("new.tmp");
        fs::write(&temporary, b"new").unwrap();
        replace(&temporary, &target, true).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"new");
        fs::remove_file(target).unwrap();
        fs::remove_dir(root).unwrap();
    }
}
