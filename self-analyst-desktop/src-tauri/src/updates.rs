use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const LATEST_API: &str = "https://api.github.com/repos/chunleik/self-analyst/releases/latest";
pub const RELEASES_URL: &str = "https://github.com/chunleik/self-analyst/releases/latest";
const MAX_RESPONSE: u64 = 1024 * 1024;
static CHECKING: AtomicBool = AtomicBool::new(false);

pub struct CheckPermit;
impl CheckPermit {
    pub fn acquire() -> Result<Self, String> {
        CHECKING
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| Self)
            .map_err(|_| "update.busy".into())
    }
}
impl Drop for CheckPermit {
    fn drop(&mut self) {
        CHECKING.store(false, Ordering::Release);
    }
}

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    current_version: String,
    latest_version: String,
    tag: String,
    available: bool,
}

#[derive(serde::Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
}

fn version(tag: &str) -> Result<semver::Version, String> {
    if tag.len() > 128 {
        return Err("update.invalid".into());
    }
    semver::Version::parse(tag.strip_prefix('v').unwrap_or(tag))
        .map_err(|_| "update.invalid".into())
}

pub fn release_url(tag: &str) -> Result<String, String> {
    version(tag)?;
    Ok(format!(
        "https://github.com/chunleik/self-analyst/releases/tag/{tag}"
    ))
}

fn parse_release(bytes: &[u8], current: &str) -> Result<UpdateInfo, String> {
    let release: Release = serde_json::from_slice(bytes).map_err(|_| "update.invalid")?;
    let latest = version(&release.tag_name)?;
    let installed = version(current)?;
    if release.draft || release.prerelease || !latest.pre.is_empty() {
        return Err("update.invalid".into());
    }
    Ok(UpdateInfo {
        current_version: installed.to_string(),
        latest_version: latest.to_string(),
        available: latest.cmp_precedence(&installed).is_gt(),
        tag: release.tag_name,
    })
}

fn network_error(error: ureq::Error) -> String {
    match error {
        ureq::Error::Timeout(_) => "update.timeout",
        _ => "update.failed",
    }
    .into()
}

fn fetch(url: &str, current: &str, timeout: Duration) -> Result<UpdateInfo, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(timeout))
        .max_redirects(0)
        .http_status_as_error(false)
        .build()
        .into();
    let mut response = agent
        .get(url)
        .header(
            "User-Agent",
            concat!("SelfAnalyst/", env!("CARGO_PKG_VERSION")),
        )
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(network_error)?;
    match response.status().as_u16() {
        200 => {}
        403 | 429 => return Err("update.rateLimited".into()),
        404 => return Err("update.noRelease".into()),
        _ => return Err("update.failed".into()),
    }
    let bytes = response
        .body_mut()
        .with_config()
        .limit(MAX_RESPONSE)
        .read_to_vec()
        .map_err(network_error)?;
    parse_release(&bytes, current)
}

pub fn check() -> Result<UpdateInfo, String> {
    fetch(
        LATEST_API,
        env!("CARGO_PKG_VERSION"),
        Duration::from_secs(15),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    fn release(tag: &str) -> Vec<u8> {
        serde_json::to_vec(
            &serde_json::json!({"tag_name": tag, "draft": false, "prerelease": false}),
        )
        .unwrap()
    }

    #[test]
    fn compares_semantic_precedence_without_build_metadata() {
        for (latest, current, available) in [
            ("v0.2.10", "0.2.9", true),
            ("0.2.9", "0.2.10", false),
            ("v0.2.6", "0.2.6", false),
            ("1.0.0", "1.0.0-rc.1", true),
            ("1.0.0+new", "1.0.0+old", false),
        ] {
            assert_eq!(
                parse_release(&release(latest), current).unwrap().available,
                available
            );
        }
    }

    #[test]
    fn rejects_invalid_unpublished_and_unsafe_tags() {
        for tag in [
            "",
            "latest",
            "v1",
            "01.2.3",
            "1.0.0-rc.1",
            "https://evil.test",
            "../1.2.3",
            "1.2.3?token=x",
        ] {
            assert!(parse_release(&release(tag), "1.0.0").is_err());
        }
        for bytes in [
            br#"{}"#.as_slice(),
            br#"{"tag_name":"1.0.0","draft":true,"prerelease":false}"#,
            br#"{"tag_name":"1.0.0","draft":false,"prerelease":true}"#,
            b"not json",
        ] {
            assert!(parse_release(bytes, "1.0.0").is_err());
        }
        assert!(parse_release(&release("1.0.0"), "invalid").is_err());
        assert_eq!(
            release_url("v1.2.3").unwrap(),
            "https://github.com/chunleik/self-analyst/releases/tag/v1.2.3"
        );
        for tag in ["https://evil.test", "../1.2.3", "1.2.3#bad", "1.2.3/other"] {
            assert!(release_url(tag).is_err());
        }
    }

    // A real local HTTP server exercises status handling, body bounds and timeouts without GitHub.
    fn server(
        status: &str,
        body: Vec<u8>,
        delay: Duration,
    ) -> (String, std::thread::JoinHandle<()>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/latest", listener.local_addr().unwrap());
        let status = status.to_owned();
        let handle = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut request = [0; 4096];
            let n = socket.read(&mut request).unwrap();
            let request = String::from_utf8_lossy(&request[..n]).to_lowercase();
            assert!(request.contains("user-agent: selfanalyst/"));
            assert!(!request.contains("authorization:") && !request.contains("cookie:"));
            std::thread::sleep(delay);
            let _ = write!(
                socket,
                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = socket.write_all(&body);
        });
        (url, handle)
    }

    #[test]
    fn http_results_are_bounded_and_failures_are_not_up_to_date() {
        for (status, body, expected) in [
            ("200 OK", release("v1.0.0"), None),
            ("429 Too Many Requests", vec![], Some("update.rateLimited")),
            ("403 Forbidden", vec![], Some("update.rateLimited")),
            ("404 Not Found", vec![], Some("update.noRelease")),
            ("302 Found", vec![], Some("update.failed")),
            ("500 Error", vec![], Some("update.failed")),
            ("200 OK", b"invalid".to_vec(), Some("update.invalid")),
            (
                "200 OK",
                vec![b'x'; MAX_RESPONSE as usize + 1],
                Some("update.failed"),
            ),
        ] {
            let (url, handle) = server(status, body, Duration::ZERO);
            let result = fetch(&url, "0.2.6", Duration::from_secs(2));
            if let Some(code) = expected {
                assert_eq!(result.unwrap_err(), code);
            } else {
                assert!(result.unwrap().available);
            }
            handle.join().unwrap();
        }
        let (url, handle) = server("200 OK", release("1.0.0"), Duration::from_millis(300));
        assert_eq!(
            fetch(&url, "0.2.6", Duration::from_millis(50)).unwrap_err(),
            "update.timeout"
        );
        handle.join().unwrap();
    }

    #[test]
    fn only_one_check_runs_and_permit_is_released() {
        let permit = CheckPermit::acquire().unwrap();
        assert!(CheckPermit::acquire().is_err());
        drop(permit);
        assert!(CheckPermit::acquire().is_ok());
    }
}
