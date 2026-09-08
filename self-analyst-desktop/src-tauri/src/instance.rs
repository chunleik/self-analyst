use std::{
    io,
    mem::size_of,
    sync::Arc,
    thread,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, GetLastError, LocalFree, ERROR_ALREADY_EXISTS, HANDLE, WAIT_OBJECT_0,
    },
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
        },
        GetTokenInformation, TokenUser, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
    },
    System::Threading::{
        CreateEventW, CreateMutexW, GetCurrentProcess, OpenEventW, OpenProcessToken, SetEvent,
        WaitForMultipleObjects, EVENT_MODIFY_STATE, INFINITE,
    },
};

struct Handle(isize);
impl Handle {
    fn new(raw: HANDLE) -> io::Result<Self> {
        if raw.is_null() {
            Err(io::Error::last_os_error())
        } else {
            Ok(Self(raw as isize))
        }
    }
    fn raw(&self) -> HANDLE {
        self.0 as HANDLE
    }
}
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.raw());
        }
    }
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

struct Security {
    descriptor: *mut std::ffi::c_void,
    sid: String,
}
impl Security {
    fn current_user() -> io::Result<Self> {
        unsafe {
            let mut token = std::ptr::null_mut();
            if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
                return Err(io::Error::last_os_error());
            }
            let token = Handle::new(token)?;
            let mut bytes = 0;
            GetTokenInformation(token.raw(), TokenUser, std::ptr::null_mut(), 0, &mut bytes);
            // usize allocation keeps TOKEN_USER aligned.
            let mut buffer = vec![0usize; (bytes as usize).div_ceil(size_of::<usize>())];
            if GetTokenInformation(
                token.raw(),
                TokenUser,
                buffer.as_mut_ptr().cast(),
                bytes,
                &mut bytes,
            ) == 0
            {
                return Err(io::Error::last_os_error());
            }
            let user = &*(buffer.as_ptr().cast::<TOKEN_USER>());
            let mut sid_text = std::ptr::null_mut();
            if ConvertSidToStringSidW(user.User.Sid, &mut sid_text) == 0 {
                return Err(io::Error::last_os_error());
            }
            let mut length = 0;
            while *sid_text.add(length) != 0 {
                length += 1;
            }
            let sid = String::from_utf16_lossy(std::slice::from_raw_parts(sid_text, length));
            LocalFree(sid_text.cast());
            let mut descriptor = std::ptr::null_mut();
            let sddl = wide(&format!("D:P(A;;GA;;;{sid})"));
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut descriptor,
                std::ptr::null_mut(),
            ) == 0
            {
                return Err(io::Error::last_os_error());
            }
            Ok(Self { descriptor, sid })
        }
    }
    fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.descriptor,
            bInheritHandle: 0,
        }
    }
}
impl Drop for Security {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.descriptor);
        }
    }
}

pub struct Instance {
    _mutex: Handle,
    show: Arc<Handle>,
}

impl Instance {
    pub fn acquire(automatic: bool) -> io::Result<Option<Self>> {
        Self::acquire_named(
            "SelfAnalystDesktopSingleInstance",
            automatic,
            Duration::from_secs(5),
        )
    }

    fn acquire_named(prefix: &str, automatic: bool, timeout: Duration) -> io::Result<Option<Self>> {
        let security = Security::current_user()?;
        let name = format!("Local\\{prefix}-{}", security.sid);
        let attributes = security.attributes();
        let raw = unsafe { CreateMutexW(&attributes, 0, wide(&name).as_ptr()) };
        let already_exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
        let mutex = Handle::new(raw)?;
        let event_name = format!("{name}-show");
        if already_exists {
            if !automatic {
                signal_existing(&event_name, timeout)?;
            }
            return Ok(None);
        }
        let show =
            Handle::new(unsafe { CreateEventW(&attributes, 0, 0, wide(&event_name).as_ptr()) })?;
        Ok(Some(Self {
            _mutex: mutex,
            show: Arc::new(show),
        }))
    }

    pub fn listen(&self, on_show: impl Fn() + Send + 'static) -> io::Result<Listener> {
        let stop = Arc::new(Handle::new(unsafe {
            CreateEventW(std::ptr::null(), 1, 0, std::ptr::null())
        })?);
        let thread_stop = stop.clone();
        let show = self.show.clone();
        let worker = thread::spawn(move || {
            let events = [thread_stop.raw(), show.raw()];
            loop {
                let result = unsafe { WaitForMultipleObjects(2, events.as_ptr(), 0, INFINITE) };
                if result == WAIT_OBJECT_0 {
                    break;
                }
                if result != WAIT_OBJECT_0 + 1 {
                    eprintln!(
                        "Window activation listener failed: {}",
                        io::Error::last_os_error()
                    );
                    break;
                }
                on_show();
            }
        });
        Ok(Listener {
            stop,
            worker: Some(worker),
        })
    }
}

fn signal_existing(name: &str, timeout: Duration) -> io::Result<()> {
    let deadline = Instant::now() + timeout;
    loop {
        let raw = unsafe { OpenEventW(EVENT_MODIFY_STATE, 0, wide(name).as_ptr()) };
        if let Ok(event) = Handle::new(raw) {
            if unsafe { SetEvent(event.raw()) } == 0 {
                return Err(io::Error::last_os_error());
            }
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "无法唤起已运行的 SelfAnalyst，请从托盘打开窗口",
            ));
        }
        thread::sleep(Duration::from_millis(25));
    }
}

pub struct Listener {
    stop: Arc<Handle>,
    worker: Option<thread::JoinHandle<()>>,
}
impl Drop for Listener {
    fn drop(&mut self) {
        unsafe {
            SetEvent(self.stop.raw());
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

// Accessed on the Tauri main thread under the app state's mutex. A request
// received before the backend/window is ready survives until window creation.
#[derive(Default)]
pub struct WindowIntent {
    ready: bool,
    requested: bool,
}
impl WindowIntent {
    pub fn request(&mut self) -> bool {
        self.requested = true;
        self.ready
    }
    pub fn ready(&mut self, automatic: bool) -> bool {
        self.ready = true;
        !automatic || self.requested
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows_sys::Win32::{Foundation::WAIT_TIMEOUT, System::Threading::WaitForSingleObject};
    fn unique() -> String {
        format!(
            "SelfAnalystTest-{}-{:?}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )
    }

    #[test]
    fn automatic_duplicate_is_silent_manual_duplicate_is_queued_and_handles_close() {
        let name = unique();
        let first = Instance::acquire_named(&name, true, Duration::ZERO)
            .unwrap()
            .unwrap();
        assert!(Instance::acquire_named(&name, true, Duration::ZERO)
            .unwrap()
            .is_none());
        assert_eq!(
            unsafe { WaitForSingleObject(first.show.raw(), 0) },
            WAIT_TIMEOUT
        );
        // Signal before listener creation: the kernel event retains the request.
        assert!(Instance::acquire_named(&name, false, Duration::ZERO)
            .unwrap()
            .is_none());
        let (send, recv) = std::sync::mpsc::channel();
        let listener = first
            .listen(move || {
                let _ = send.send(());
            })
            .unwrap();
        recv.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(Instance::acquire_named(&name, false, Duration::ZERO)
            .unwrap()
            .is_none());
        recv.recv_timeout(Duration::from_secs(2)).unwrap();
        drop(listener);
        drop(first);
        assert!(Instance::acquire_named(&name, true, Duration::ZERO)
            .unwrap()
            .is_some());
    }

    #[test]
    fn missing_activation_endpoint_times_out() {
        assert_eq!(
            signal_existing(&format!("Local\\{}", unique()), Duration::ZERO)
                .unwrap_err()
                .kind(),
            io::ErrorKind::TimedOut
        );
    }

    #[test]
    fn visibility_preserves_user_intent_at_both_sides_of_readiness() {
        let mut automatic = WindowIntent::default();
        assert!(!automatic.ready(true));
        assert!(automatic.request());
        let mut pending = WindowIntent::default();
        assert!(!pending.request());
        assert!(pending.ready(true));
        assert!(WindowIntent::default().ready(false));
    }
}
