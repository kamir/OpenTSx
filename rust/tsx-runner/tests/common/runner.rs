use std::ffi::OsStr;
use std::path::Path;
use std::process::Command;

pub fn runner() -> Command {
    let prefix = std::env::var_os("CARGO_TARGET_ARMV7_UNKNOWN_LINUX_MUSLEABIHF_RUNNER");
    configured_command(
        Path::new(env!("CARGO_BIN_EXE_tsx-runner")),
        prefix.as_deref(),
    )
}

fn configured_command(binary: &Path, prefix: Option<&OsStr>) -> Command {
    let Some(prefix) = prefix else {
        return Command::new(binary);
    };
    let prefix = prefix
        .to_str()
        .expect("the configured ARMv7 runner prefix is not UTF-8");
    let mut words = prefix.split_ascii_whitespace();
    let program = words
        .next()
        .expect("the configured ARMv7 runner prefix is empty");
    let mut command = Command::new(program);
    command.args(words).arg(binary);
    command
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::ffi::OsStrExt;
    use std::process::Stdio;

    #[test]
    fn configured_prefix_fixture_does_not_require_temporary_executables() {
        let executable = std::env::current_exe().unwrap();
        assert!(executable.is_file());
        let prefix = std::env::var_os("CARGO_TARGET_ARMV7_UNKNOWN_LINUX_MUSLEABIHF_RUNNER");
        let test = "cli_runner::tests::configured_prefix_executes_before_the_binary_and_preserves_argument_boundaries";
        let output = configured_command(&executable, prefix.as_deref())
            .args(["--exact", test, "--nocapture"])
            .env("TMPDIR", &executable)
            .output()
            .unwrap();
        let stdout = String::from_utf8(output.stdout).unwrap();
        let stderr = String::from_utf8(output.stderr).unwrap();
        assert!(output.status.success(), "{stdout}\n{stderr}");
        assert!(stdout.contains("running 1 test"), "{stdout}");
        assert!(stdout.contains(&format!("test {test} ... ok")), "{stdout}");
        assert!(stdout.contains("1 passed; 0 failed"), "{stdout}");
    }

    #[test]
    fn configured_prefix_executes_before_the_binary_and_preserves_argument_boundaries() {
        let binary = Path::new("/fixture/foreign runner binary");
        let configured = "/bin/sh -s -- --sysroot /fixture/sysroot";
        let mut child = configured_command(binary, Some(OsStr::new(configured)))
            .args(["--config", "config path with spaces"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let mut input = child.stdin.take().unwrap();
        input.write_all(b"printf '%s\\n' \"$@\"\n").unwrap();
        drop(input);
        let output = child.wait_with_output().unwrap();
        assert!(output.status.success());
        assert!(output.stderr.is_empty());
        assert_eq!(
            String::from_utf8(output.stdout).unwrap(),
            format!(
                "--sysroot\n/fixture/sysroot\n{}\n--config\nconfig path with spaces\n",
                binary.display()
            )
        );
    }

    #[test]
    fn absent_prefix_preserves_the_native_binary_and_arguments() {
        let mut command = configured_command(Path::new("runner path with spaces"), None);
        command.args(["--config", "config path with spaces"]);
        assert_eq!(command.get_program(), "runner path with spaces");
        assert_eq!(
            command.get_args().collect::<Vec<_>>(),
            [
                OsStr::new("--config"),
                OsStr::new("config path with spaces")
            ]
        );
    }

    #[test]
    #[should_panic(expected = "the configured ARMv7 runner prefix is empty")]
    fn empty_prefix_is_rejected() {
        configured_command(Path::new("runner"), Some(OsStr::new(" \t")));
    }

    #[test]
    #[should_panic(expected = "the configured ARMv7 runner prefix is not UTF-8")]
    fn invalid_utf8_prefix_is_rejected() {
        configured_command(Path::new("runner"), Some(OsStr::from_bytes(&[255])));
    }
}
