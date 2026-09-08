use std::{
    fs::OpenOptions,
    io::Write,
    time::{SystemTime, UNIX_EPOCH},
};

// Bootstrap diagnostics must work before the distribution and backend are ready.
// Only callers' lifecycle messages are recorded: never tokens, URLs or user data.
pub fn write(message: &str) {
    let Some(local_data) = std::env::var_os("LOCALAPPDATA") else {
        return;
    };
    let directory = std::path::PathBuf::from(local_data).join("com.selfanalyst.desktop");
    if std::fs::create_dir_all(&directory).is_err() {
        return;
    }
    let path = directory.join("self-analyst-shell.log");
    let truncate = std::fs::metadata(&path).is_ok_and(|m| m.len() > 1_048_576);
    if let Ok(mut file) = OpenOptions::new()
        .create(true)
        .write(true)
        .append(!truncate)
        .truncate(truncate)
        .open(path)
    {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let _ = writeln!(file, "{timestamp} pid={} {message}", std::process::id());
    }
}

pub fn install_panic_hook() {
    let original = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        // A dependency panic may include a request or secret in its payload.
        // Recording only its source location still identifies a bootstrap failure.
        if let Some(location) = info.location() {
            write(&format!("panic at {}:{}", location.file(), location.line()));
        } else {
            write("panic without source location");
        }
        original(info);
    }));
}
