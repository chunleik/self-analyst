use std::ffi::OsString;
use std::os::windows::ffi::{OsStrExt, OsStringExt};
use std::path::{Component, Path, PathBuf, Prefix};

pub fn for_java(path: &Path) -> PathBuf {
    // Java 能读取 verbatim 路径的 manifest，却不能据此加载 JAR 中的类。
    // 仅在 Java 参数边界转换，保留原有资源根和用户工作目录。
    let Some(Component::Prefix(prefix)) = path.components().next() else {
        return path.to_path_buf();
    };
    let units: Vec<u16> = path.as_os_str().encode_wide().collect();
    match prefix.kind() {
        Prefix::VerbatimDisk(_) => PathBuf::from(OsString::from_wide(&units[4..])),
        Prefix::VerbatimUNC(_, _) => {
            let mut ordinary = vec![b'\\' as u16, b'\\' as u16];
            ordinary.extend_from_slice(&units[8..]);
            PathBuf::from(OsString::from_wide(&ordinary))
        }
        _ => path.to_path_buf(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};

    #[test]
    fn converts_verbatim_disk_and_unc_paths() {
        for (input, expected) in [
            (
                r"\\?\C:\安装 目录\self-analyst-app.jar",
                r"C:\安装 目录\self-analyst-app.jar",
            ),
            (
                r"\\?\UNC\server\共享 目录\app.jar",
                r"\\server\共享 目录\app.jar",
            ),
        ] {
            assert_eq!(for_java(Path::new(input)), PathBuf::from(expected));
        }
    }

    #[test]
    fn preserves_ordinary_and_unknown_device_paths() {
        for input in [
            r"C:\安装 目录\app.jar",
            r"\\server\share\app.jar",
            "app.jar",
            r"\\?\Volume{123}\app.jar",
            r"\\.\device",
        ] {
            assert_eq!(for_java(Path::new(input)), PathBuf::from(input));
        }
    }

    #[test]
    fn preserves_windows_native_path_characters() {
        let mut units: Vec<u16> = r"\\?\C:\".encode_utf16().collect();
        units.extend([0xd800, b'.' as u16, b'j' as u16]);
        let input = PathBuf::from(OsString::from_wide(&units));
        assert_eq!(
            for_java(&input)
                .as_os_str()
                .encode_wide()
                .collect::<Vec<_>>(),
            units[4..]
        );
    }
}
