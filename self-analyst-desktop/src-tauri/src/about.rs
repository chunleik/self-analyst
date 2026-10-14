//! Public release metadata for the exact installed desktop version.
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const RELEASE_API: &str = "https://api.github.com/repos/chunleik/self-analyst/releases/tags/";
const UNAVAILABLE: &str = "help.releaseUnavailable";
const MAX_RESPONSE: u64 = 1024 * 1024;
static FETCHING: AtomicBool = AtomicBool::new(false);

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AboutInfo {
    version: String,
    backend_url: String,
    development: bool,
}

pub fn information(backend_url: String) -> AboutInfo {
    AboutInfo {
        version: env!("CARGO_PKG_VERSION").into(),
        backend_url,
        development: cfg!(debug_assertions),
    }
}

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseInfo {
    version: String,
    published_at: Option<String>,
}

#[derive(serde::Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    published_at: Option<String>,
}

struct FetchPermit;
impl FetchPermit {
    fn acquire() -> Result<Self, String> {
        FETCHING
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| Self)
            .map_err(|_| UNAVAILABLE.into())
    }
}
impl Drop for FetchPermit {
    fn drop(&mut self) {
        FETCHING.store(false, Ordering::Release);
    }
}

fn release_api(version: &str) -> Result<String, String> {
    if version.len() > 128 || semver::Version::parse(version).is_err() {
        return Err(UNAVAILABLE.into());
    }
    Ok(format!("{RELEASE_API}v{version}"))
}

// GitHub publishes second-precision UTC timestamps; impossible dates must not normalize.
fn valid_timestamp(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() != 20
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || bytes[19] != b'Z'
    {
        return false;
    }
    let mut fields = [0u32; 6];
    for (field, (start, end)) in
        fields
            .iter_mut()
            .zip([(0, 4), (5, 7), (8, 10), (11, 13), (14, 16), (17, 19)])
    {
        let digits = &bytes[start..end];
        if !digits.iter().all(u8::is_ascii_digit) {
            return false;
        }
        *field = digits
            .iter()
            .fold(0, |n, digit| n * 10 + u32::from(digit - b'0'));
    }
    let [year, month, day, hour, minute, second] = fields;
    let leap_year =
        year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400));
    let days = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap_year => 29,
        2 => 28,
        _ => 0,
    };
    year > 0 && day > 0 && day <= days && hour < 24 && minute < 60 && second < 60
}

fn parse_release(bytes: &[u8], version: &str) -> Result<ReleaseInfo, String> {
    let release: Release = serde_json::from_slice(bytes).map_err(|_| UNAVAILABLE)?;
    if release.draft || release.tag_name != format!("v{version}") {
        return Err(UNAVAILABLE.into());
    }
    let published_at = release.published_at.filter(|value| valid_timestamp(value));
    if published_at.is_none() {
        return Err(UNAVAILABLE.into());
    }
    Ok(ReleaseInfo {
        version: version.into(),
        published_at,
    })
}

fn fetch(url: &str, version: &str, timeout: Duration) -> Result<ReleaseInfo, String> {
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
        .map_err(|_| UNAVAILABLE)?;
    if response.status().as_u16() != 200 {
        return Err(UNAVAILABLE.into());
    }
    let bytes = response
        .body_mut()
        .with_config()
        .limit(MAX_RESPONSE)
        .read_to_vec()
        .map_err(|_| UNAVAILABLE)?;
    parse_release(&bytes, version)
}

fn for_build(
    version: &str,
    development: bool,
    request: impl FnOnce() -> Result<ReleaseInfo, String>,
) -> Result<ReleaseInfo, String> {
    if development {
        return Ok(ReleaseInfo {
            version: version.into(),
            published_at: None,
        });
    }
    request()
}

pub fn release_info() -> Result<ReleaseInfo, String> {
    let version = env!("CARGO_PKG_VERSION");
    for_build(version, cfg!(debug_assertions), || {
        let _permit = FetchPermit::acquire()?;
        fetch(&release_api(version)?, version, Duration::from_secs(5))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    fn release(tag: &str, published_at: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "tag_name": tag, "draft": false, "prerelease": true,
            "published_at": published_at,
        }))
        .unwrap()
    }

    #[test]
    fn exact_installed_tags_include_stable_beta_and_rc() {
        for version in ["0.6.0", "0.6.1-beta.8", "0.6.1-rc.1"] {
            let tag = format!("v{version}");
            assert_eq!(release_api(version).unwrap(), format!("{RELEASE_API}{tag}"));
            let info = parse_release(&release(&tag, "2026-10-13T12:34:56Z"), version).unwrap();
            assert_eq!(info.version, version);
            assert_eq!(info.published_at.as_deref(), Some("2026-10-13T12:34:56Z"));
        }
        for version in [
            "latest",
            "v0.6.0",
            "../1.0.0",
            "1.0.0?token=x",
            "1.0.0/other",
        ] {
            assert!(release_api(version).is_err());
        }
    }

    #[test]
    fn rejects_mismatches_drafts_and_missing_or_invalid_publication_time() {
        for bytes in [
            release("v0.6.0", "2026-10-13T12:34:56Z"),
            release("0.6.1-beta.8", "2026-10-13T12:34:56Z"),
            release("v0.6.1-beta.8", "2026-02-30T12:34:56Z"),
            br#"{"tag_name":"v0.6.1-beta.8","draft":true,"published_at":"2026-10-13T12:34:56Z"}"#
                .to_vec(),
            br#"{"tag_name":"v0.6.1-beta.8","draft":false,"published_at":null}"#.to_vec(),
            br#"{"tag_name":"v0.6.1-beta.8","draft":false}"#.to_vec(),
            br#"{"tag_name":"v0.6.1-beta.8","published_at":"2026-10-13T12:34:56Z"}"#.to_vec(),
            b"not json".to_vec(),
        ] {
            assert_eq!(
                parse_release(&bytes, "0.6.1-beta.8").unwrap_err(),
                UNAVAILABLE
            );
        }
    }

    #[test]
    fn utc_timestamp_validation_checks_calendar_and_time() {
        for value in ["2024-02-29T23:59:59Z", "2000-02-29T00:00:00Z"] {
            assert!(valid_timestamp(value), "{value}");
        }
        for value in [
            "",
            "2026-02-29T12:34:56Z",
            "1900-02-29T00:00:00Z",
            "2026-04-31T12:34:56Z",
            "0000-01-01T00:00:00Z",
            "2026-00-01T12:34:56Z",
            "2026-13-01T12:34:56Z",
            "2026-01-00T12:34:56Z",
            "2026-10-13T24:34:56Z",
            "2026-10-13T12:60:56Z",
            "2026-10-13T12:34:60Z",
            "2026-10-13T12:34:56.Z",
            "2026-10-13T12:34:56.123Z",
            "2026-10-13 12:34:56Z",
            "2026-10-13T12:34:56",
            "2026-10-13T12:34:56+08:00",
            "2026-10-13T12:34:56Z\n",
            "2026-10-13T12:34:56.xZ",
            "２０２６-10-13T12:34:56Z",
        ] {
            assert!(!valid_timestamp(value), "{value}");
        }
    }

    #[test]
    fn development_does_not_request_public_release_metadata() {
        let info = for_build("0.6.1-beta.8", true, || {
            panic!("development requested network")
        })
        .unwrap();
        assert_eq!(info.version, "0.6.1-beta.8");
        assert!(info.published_at.is_none());
        assert_eq!(
            serde_json::to_value(info).unwrap(),
            serde_json::json!({"version": "0.6.1-beta.8", "publishedAt": null})
        );
        let info = information("http://localhost:5700".into());
        let json = serde_json::to_value(info).unwrap();
        assert_eq!(json["version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(json["backendUrl"], "http://localhost:5700");
        assert_eq!(json["development"], cfg!(debug_assertions));
    }

    // These local HTTP tests never contact GitHub or require credentials.
    fn server(
        status: &str,
        body: Vec<u8>,
        delay: Duration,
    ) -> (String, std::thread::JoinHandle<()>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!(
            "http://{}/releases/tags/v0.6.1-beta.8",
            listener.local_addr().unwrap()
        );
        let status = status.to_owned();
        let handle = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut request = [0; 4096];
            let n = socket.read(&mut request).unwrap();
            let request = String::from_utf8_lossy(&request[..n]).to_lowercase();
            assert!(request.starts_with("get /releases/tags/v0.6.1-beta.8 "));
            assert!(request.contains("user-agent: selfanalyst/"));
            assert!(request.contains("accept: application/vnd.github+json"));
            for forbidden in [
                "authorization:",
                "cookie:",
                "referer:",
                "x-selfanalyst-token:",
            ] {
                assert!(!request.contains(forbidden), "unexpected {forbidden}");
            }
            std::thread::sleep(delay);
            let _ = write!(
                socket,
                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\nLocation: /unexpected-redirect\r\n\r\n",
                body.len()
            );
            let _ = socket.write_all(&body);
            drop(socket);
            if status.starts_with("302") {
                listener.set_nonblocking(true).unwrap();
                let deadline = std::time::Instant::now() + Duration::from_millis(150);
                while std::time::Instant::now() < deadline {
                    match listener.accept() {
                        Ok(_) => panic!("release request followed a redirect"),
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(error) => panic!("redirect listener failed: {error}"),
                    }
                }
            }
        });
        (url, handle)
    }

    #[test]
    fn http_fetch_is_bounded_private_and_does_not_follow_redirects() {
        for (status, body, success) in [
            (
                "200 OK",
                release("v0.6.1-beta.8", "2026-10-13T12:34:56Z"),
                true,
            ),
            ("429 Too Many Requests", vec![], false),
            ("403 Forbidden", vec![], false),
            ("404 Not Found", vec![], false),
            ("302 Found", vec![], false),
            ("500 Error", vec![], false),
            ("200 OK", b"invalid".to_vec(), false),
            ("200 OK", vec![b'x'; MAX_RESPONSE as usize + 1], false),
        ] {
            let (url, handle) = server(status, body, Duration::ZERO);
            let result = fetch(&url, "0.6.1-beta.8", Duration::from_secs(2));
            assert_eq!(result.is_ok(), success, "{status}");
            if !success {
                assert_eq!(result.unwrap_err(), UNAVAILABLE);
            }
            handle.join().unwrap();
        }
        let (url, handle) = server(
            "200 OK",
            release("v0.6.1-beta.8", "2026-10-13T12:34:56Z"),
            Duration::from_millis(300),
        );
        assert_eq!(
            fetch(&url, "0.6.1-beta.8", Duration::from_millis(50)).unwrap_err(),
            UNAVAILABLE
        );
        handle.join().unwrap();
    }

    #[test]
    fn failed_connection_is_unavailable() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/release", listener.local_addr().unwrap());
        drop(listener);
        assert_eq!(
            fetch(&url, "0.6.1-beta.8", Duration::from_secs(1)).unwrap_err(),
            UNAVAILABLE
        );
    }

    #[test]
    fn concurrent_fetches_are_rejected_and_permit_is_released() {
        let permit = FetchPermit::acquire().unwrap();
        assert!(FetchPermit::acquire().is_err());
        drop(permit);
        assert!(FetchPermit::acquire().is_ok());
    }
}
