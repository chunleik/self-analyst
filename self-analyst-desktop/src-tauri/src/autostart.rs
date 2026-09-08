use std::{io, path::Path};
use windows_sys::Win32::{
    Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS},
    System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegDeleteKeyValueW, RegGetValueW, RegSetValueExW,
        HKEY_CURRENT_USER, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE, REG_SZ, RRF_RT_REG_SZ,
    },
};

pub const ARGUMENT: &str = "--autostart";
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
// Tauri's NSIS template unconditionally deletes the product-name Run value.
// Use a distinct name so our ownership-aware uninstall hook controls deletion.
const VALUE_NAME: &str = "SelfAnalystDesktop";

pub trait Store {
    fn read(&self) -> io::Result<Option<String>>;
    fn write(&self, command: &str) -> io::Result<()>;
    fn remove(&self) -> io::Result<()>;
}

#[derive(Debug, PartialEq, Eq)]
pub enum Status {
    Disabled,
    Enabled,
    OtherDistribution,
}

pub fn command(exe: &Path) -> io::Result<String> {
    let path = exe
        .to_str()
        .ok_or_else(|| io::Error::other("程序路径不是有效 Unicode"))?;
    if !exe.is_absolute() || path.contains(['"', '\0', '\r', '\n']) {
        return Err(io::Error::other("自启动需要有效的程序绝对路径"));
    }
    let value = format!("\"{path}\" {ARGUMENT}");
    // Windows documents a maximum command line of 260 characters for Run values.
    if value.encode_utf16().count() > 260 {
        return Err(io::Error::other(
            "程序路径过长，请将应用移到较短路径后开启自启动",
        ));
    }
    Ok(value)
}

pub fn status(store: &impl Store, exe: &Path) -> io::Result<Status> {
    let expected = command(exe)?;
    Ok(match store.read()? {
        None => Status::Disabled,
        Some(value) if value.to_lowercase() == expected.to_lowercase() => Status::Enabled,
        Some(_) => Status::OtherDistribution,
    })
}

pub fn set_enabled(store: &impl Store, exe: &Path, enabled: bool) -> io::Result<Status> {
    let expected = command(exe)?;
    if enabled {
        store.write(&expected)?;
    } else if status(store, exe)? == Status::Enabled {
        store.remove()?;
    }
    let actual = status(store, exe)?;
    if (actual == Status::Enabled) != enabled {
        return Err(io::Error::other(
            "无法确认自启动设置已生效，请重新打开托盘菜单检查",
        ));
    }
    Ok(actual)
}

pub struct RegistryStore;

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn check(code: u32) -> io::Result<()> {
    if code == ERROR_SUCCESS {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(code as i32))
    }
}

impl Store for RegistryStore {
    fn read(&self) -> io::Result<Option<String>> {
        // Bounded buffer; oversized or invalid externally edited values are errors,
        // never silently truncated or treated as a disabled entry.
        let mut buffer = vec![0u16; 32768];
        let mut bytes = (buffer.len() * 2) as u32;
        let code = unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                wide(RUN_KEY).as_ptr(),
                wide(VALUE_NAME).as_ptr(),
                RRF_RT_REG_SZ,
                std::ptr::null_mut(),
                buffer.as_mut_ptr().cast(),
                &mut bytes,
            )
        };
        if code == ERROR_FILE_NOT_FOUND {
            return Ok(None);
        }
        check(code)?;
        if !bytes.is_multiple_of(2) {
            return Err(io::Error::other("自启动项编码无效"));
        }
        buffer.truncate(bytes as usize / 2);
        if buffer.last() == Some(&0) {
            buffer.pop();
        }
        String::from_utf16(&buffer)
            .map(Some)
            .map_err(|_| io::Error::other("自启动项编码无效"))
    }

    fn write(&self, command: &str) -> io::Result<()> {
        let value = wide(command);
        let mut key = std::ptr::null_mut();
        check(unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide(RUN_KEY).as_ptr(),
                0,
                std::ptr::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                std::ptr::null(),
                &mut key,
                std::ptr::null_mut(),
            )
        })?;
        let code = unsafe {
            RegSetValueExW(
                key,
                wide(VALUE_NAME).as_ptr(),
                0,
                REG_SZ,
                value.as_ptr().cast(),
                (value.len() * 2) as u32,
            )
        };
        unsafe {
            RegCloseKey(key);
        }
        check(code)
    }

    fn remove(&self) -> io::Result<()> {
        let code = unsafe {
            RegDeleteKeyValueW(
                HKEY_CURRENT_USER,
                wide(RUN_KEY).as_ptr(),
                wide(VALUE_NAME).as_ptr(),
            )
        };
        if code == ERROR_FILE_NOT_FOUND {
            Ok(())
        } else {
            check(code)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::{Cell, RefCell};

    #[derive(Default)]
    struct MemoryStore {
        value: RefCell<Option<String>>,
        fail_read: Cell<bool>,
        fail_write: Cell<bool>,
        ignore_write: Cell<bool>,
    }
    impl Store for MemoryStore {
        fn read(&self) -> io::Result<Option<String>> {
            if self.fail_read.get() {
                return Err(io::Error::other("read denied"));
            }
            Ok(self.value.borrow().clone())
        }
        fn write(&self, command: &str) -> io::Result<()> {
            if self.fail_write.get() {
                return Err(io::Error::other("write denied"));
            }
            if !self.ignore_write.get() {
                *self.value.borrow_mut() = Some(command.into());
            }
            Ok(())
        }
        fn remove(&self) -> io::Result<()> {
            if self.fail_write.get() {
                return Err(io::Error::other("delete denied"));
            }
            *self.value.borrow_mut() = None;
            Ok(())
        }
    }

    #[test]
    fn enable_disable_roundtrip_and_unicode_path() {
        let store = MemoryStore::default();
        let exe = Path::new(r"C:\个人 工具\SelfAnalyst.exe");
        assert_eq!(status(&store, exe).unwrap(), Status::Disabled);
        assert_eq!(set_enabled(&store, exe, true).unwrap(), Status::Enabled);
        assert_eq!(
            store.read().unwrap().unwrap(),
            "\"C:\\个人 工具\\SelfAnalyst.exe\" --autostart"
        );
        assert_eq!(set_enabled(&store, exe, false).unwrap(), Status::Disabled);
    }

    #[test]
    fn moved_distribution_requires_explicit_enable_and_preserves_other_owner() {
        let store = MemoryStore::default();
        let old = Path::new(r"C:\old\SelfAnalyst.exe");
        let new = Path::new(r"D:\新 位置\SelfAnalyst.exe");
        set_enabled(&store, old, true).unwrap();
        assert_eq!(status(&store, new).unwrap(), Status::OtherDistribution);
        set_enabled(&store, new, false).unwrap();
        assert_eq!(status(&store, old).unwrap(), Status::Enabled);
        set_enabled(&store, new, true).unwrap();
        set_enabled(&store, old, false).unwrap();
        assert_eq!(status(&store, new).unwrap(), Status::Enabled);
    }

    #[test]
    fn failed_or_unconfirmed_changes_never_report_success() {
        let store = MemoryStore::default();
        let exe = Path::new(r"C:\SelfAnalyst.exe");
        store.fail_write.set(true);
        assert!(set_enabled(&store, exe, true).is_err());
        store.fail_write.set(false);
        store.ignore_write.set(true);
        assert!(set_enabled(&store, exe, true).is_err());
        store.ignore_write.set(false);
        set_enabled(&store, exe, true).unwrap();
        store.fail_write.set(true);
        assert!(set_enabled(&store, exe, false).is_err());
        assert_eq!(status(&store, exe).unwrap(), Status::Enabled);
        store.fail_write.set(false);
        store.fail_read.set(true);
        assert!(set_enabled(&store, exe, true).is_err());
        assert!(status(&store, exe).is_err());
    }

    #[test]
    fn rejects_invalid_and_overlong_commands_without_writing() {
        let store = MemoryStore::default();
        for path in [
            "relative.exe".to_owned(),
            "C:\\bad\"path.exe".into(),
            format!("C:\\{}\\SelfAnalyst.exe", "长".repeat(260)),
        ] {
            assert!(set_enabled(&store, Path::new(&path), true).is_err());
            assert!(store.read().unwrap().is_none());
        }
    }
}
