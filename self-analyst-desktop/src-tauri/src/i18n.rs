use serde_json::Value;
use std::sync::OnceLock;

include!(concat!(env!("OUT_DIR"), "/languages.rs"));
static LANGUAGE: OnceLock<String> = OnceLock::new();

#[cfg(all(test, feature = "native-menu-review"))]
pub fn review_language(code: &str) {
    let payload = serde_json::json!({ "language": code }).to_string();
    LANGUAGE
        .set(backend_language(&payload).expect("supported review language"))
        .expect("review initialized once");
}

fn system_language() -> String {
    let mut buffer = [0u16; 85];
    let count = unsafe {
        windows_sys::Win32::Globalization::GetUserDefaultLocaleName(
            buffer.as_mut_ptr(),
            buffer.len() as i32,
        )
    };
    if count > 1 {
        String::from_utf16_lossy(&buffer[..count as usize - 1])
    } else {
        "en".into()
    }
}

fn resolve_system(locale: &str, registry: &Value) -> String {
    let base = locale.split('-').next().unwrap_or("en").to_lowercase();
    registry
        .as_array()
        .and_then(|items| items.iter().find(|item| item["code"] == base))
        .and_then(|item| item["code"].as_str())
        .unwrap_or("en")
        .to_owned()
}

pub fn initialize_backend(
    port: u16,
    token: &str,
    timeout: std::time::Duration,
) -> Result<(), String> {
    let code = request_backend_language(port, token, timeout)?;
    LANGUAGE
        .set(code)
        .map_err(|_| "Language already initialized".to_owned())
}

fn request_backend_language(
    port: u16,
    token: &str,
    timeout: std::time::Duration,
) -> Result<String, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(timeout))
        .max_redirects(0)
        .build()
        .into();
    let mut response = agent
        .get(&format!("http://localhost:{port}/desktop/status"))
        .header("X-SelfAnalyst-Token", token)
        .call()
        .map_err(|_| "Language request failed")?;
    let payload = response
        .body_mut()
        .read_to_string()
        .map_err(|_| "Invalid language body")?;
    backend_language(&payload)
}

fn backend_language(payload: &str) -> Result<String, String> {
    let status: Value = serde_json::from_str(payload).map_err(|_| "Invalid language response")?;
    let code = status["language"].as_str().ok_or("Missing language")?;
    if !CATALOGS.iter().any(|(supported, _)| *supported == code) {
        return Err("Unsupported backend language".into());
    }
    Ok(code.to_owned())
}

pub fn text(key: &str) -> String {
    let registry: Value = serde_json::from_str(REGISTRY).expect("bundled registry");
    let language = LANGUAGE
        .get()
        .cloned()
        .unwrap_or_else(|| resolve_system(&system_language(), &registry));
    lookup(&language, key, CATALOGS)
}

fn lookup(language: &str, key: &str, catalogs: &[(&str, &str)]) -> String {
    for code in [language, "en"] {
        if let Some((_, raw)) = catalogs.iter().find(|(candidate, _)| *candidate == code) {
            if let Ok(catalog) = serde_json::from_str::<Value>(raw) {
                if let Some(message) = catalog[key].as_str() {
                    return message.to_owned();
                }
            }
        }
    }
    key.to_owned()
}

pub fn render(key: &str, parameters: &[(&str, &str)]) -> String {
    render_template(&text(key), parameters)
}

fn render_template(template: &str, parameters: &[(&str, &str)]) -> String {
    let mut output = String::new();
    let mut rest = template;
    while let Some(start) = rest.find('{') {
        output.push_str(&rest[..start]);
        let Some(end) = rest[start..].find('}') else {
            output.push_str(&rest[start..]);
            return output;
        };
        let end = start + end;
        let name = &rest[start + 1..end];
        output.push_str(
            parameters
                .iter()
                .find(|(key, _)| *key == name)
                .map(|(_, value)| *value)
                .unwrap_or(&rest[start..=end]),
        );
        rest = &rest[end + 1..];
    }
    output.push_str(rest);
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn languages_and_extension() {
        assert_eq!(lookup("en", "show", CATALOGS), "Show window");
        assert_eq!(lookup("zh", "show", CATALOGS), "显示窗口");
        let registry = serde_json::json!([{"code":"en"},{"code":"zh"},{"code":"fr"}]);
        assert_eq!(resolve_system("fr-CA", &registry), "fr");
        assert_eq!(resolve_system("ja-JP", &registry), "en");
        let catalogs = [
            ("en", r#"{"show":"Show"}"#),
            ("fr", r#"{"show":"Afficher"}"#),
        ];
        assert_eq!(lookup("fr", "show", &catalogs), "Afficher");
        assert_eq!(lookup("de", "show", &catalogs), "Show");
    }
    #[test]
    fn validates_backend_and_preserves_parameters() {
        assert_eq!(backend_language(r#"{"language":"en"}"#).unwrap(), "en");
        assert!(backend_language(r#"{"language":"fr"}"#).is_err());
        assert!(backend_language("{}").is_err());
        assert_eq!(
            render_template("{value}", &[("value", "{other}"), ("other", "changed")]),
            "{other}"
        );
    }

    #[test]
    fn authenticated_handshake_and_timeout() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::time::{Duration, Instant};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = [0; 4096];
            let size = stream.read(&mut request).unwrap();
            let request = String::from_utf8_lossy(&request[..size]).to_lowercase();
            assert!(request.contains("/desktop/status"));
            assert!(request.contains("x-selfanalyst-token: test-token"));
            let body = r#"{"language":"en"}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
            .unwrap();
        });
        assert_eq!(
            request_backend_language(port, "test-token", Duration::from_secs(2)).unwrap(),
            "en"
        );
        server.join().unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let (_stream, _) = listener.accept().unwrap();
            std::thread::sleep(Duration::from_millis(300));
        });
        let start = Instant::now();
        assert!(request_backend_language(port, "test-token", Duration::from_millis(50)).is_err());
        assert!(start.elapsed() < Duration::from_secs(2));
        server.join().unwrap();
    }
}
