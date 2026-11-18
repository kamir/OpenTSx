use std::fs;
use std::path::PathBuf;
use std::process::{Command, Output};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

fn cli(arguments: &[&str]) -> Output {
    let binary = env!("CARGO_BIN_EXE_tsx-runner");
    let prefix = match std::env::var("CARGO_TARGET_ARMV7_UNKNOWN_LINUX_MUSLEABIHF_RUNNER") {
        Ok(value) => {
            let words: Vec<_> = value.split_ascii_whitespace().map(str::to_owned).collect();
            assert!(
                !words.is_empty(),
                "the configured ARMv7 runner prefix is empty"
            );
            words
        }
        Err(std::env::VarError::NotPresent) => Vec::new(),
        Err(std::env::VarError::NotUnicode(_)) => {
            panic!("the configured ARMv7 runner prefix is not UTF-8")
        }
    };
    let mut command = if let Some(program) = prefix.first() {
        let mut command = Command::new(program);
        command.args(&prefix[1..]).arg(binary);
        command
    } else {
        Command::new(binary)
    };
    command.args(arguments).output().unwrap()
}

#[test]
fn help_advertises_the_signed_run_and_preserves_bounded_commands() {
    let output = cli(&["--help"]);
    assert!(output.status.success());
    let text = String::from_utf8(output.stdout).unwrap();
    assert!(text.contains("tsx-runner run --plan <file>|- --config <file>"));
    assert!(text.contains("tsx-runner replay --request <file> --checkpoint <file>"));
    assert!(text.contains("tsx-runner serve --listen <addr>"));
    assert!(output.stderr.is_empty());
}

#[test]
fn missing_configuration_is_refused_before_stdin_or_broker_access() {
    let directory = std::env::temp_dir().join(format!(
        "opentsx-run-cli-{}-{}",
        std::process::id(),
        NEXT_DIRECTORY.fetch_add(1, Ordering::SeqCst)
    ));
    fs::create_dir(&directory).unwrap();
    let absent: PathBuf = directory.join("absent.json");
    let output = cli(&["run", "--plan", "-", "--config", absent.to_str().unwrap()]);
    fs::remove_dir(directory).unwrap();
    assert_eq!(output.status.code(), Some(2));
    assert!(output.stdout.is_empty());
    let error = String::from_utf8(output.stderr).unwrap();
    assert!(
        error.contains("plan.invalid: cannot open run configuration"),
        "{error}"
    );
}

#[test]
fn duplicate_or_unknown_run_arguments_are_rejected_explicitly() {
    for (arguments, expected) in [
        (
            ["run", "--plan", "one", "--plan", "two"],
            "duplicate run argument",
        ),
        (
            ["run", "--unrecognized", "one", "--config", "two"],
            "unknown run argument: --unrecognized",
        ),
        (
            ["run", "--config", "one", "--config", "two"],
            "duplicate run argument",
        ),
    ] {
        let output = cli(&arguments);
        assert_eq!(output.status.code(), Some(2));
        assert!(output.stdout.is_empty());
        let error = String::from_utf8(output.stderr).unwrap();
        assert!(error.contains(expected), "{error}");
    }
}

#[test]
fn version_is_the_stamped_package_version() {
    let output = cli(&["--version"]);
    assert!(output.status.success());
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        format!("tsx-runner {}\n", env!("CARGO_PKG_VERSION"))
    );
    assert!(output.stderr.is_empty());
}
