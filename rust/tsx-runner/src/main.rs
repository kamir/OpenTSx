use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use tsx_runner::publication::HttpFencedBroker;
use tsx_runner::{admission, broker::KafkaBroker, run_loop, run_plan, runner, worker};

const HELP: &str = "Usage: tsx-runner agent --config <file>\n       tsx-runner conform --config <file> --job <job>\n       tsx-runner run --plan <file>|- --config <file>\n       tsx-runner replay --request <file> --checkpoint <file>\n       tsx-runner serve --listen <addr> --node <id> --site <id> --bootstrap <host:port> --state <directory> [--publisher <http-origin> --controller <IP>] [--profile standard|measurement]\n       tsx-runner --version\n\nExecutes qualified signed bounded statistics, isolated signed continuous pass-through and trusted-local assignments. Local site configuration fixes control trust, qualified evidence, jobs, bindings and policy. Fenced local publication requires both publisher and controller options for serve. The measurement profile raises the serve admission ceilings for board measurements.";

fn main() {
    match command() {
        Ok(()) => {}
        Err((code, error)) => {
            eprintln!("{error}");
            std::process::exit(code);
        }
    }
}

fn command() -> Result<(), (i32, String)> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args == ["--help"] || args == ["-h"] || args.is_empty() {
        println!("tsx-runner {}\n{HELP}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if args == ["--version"] {
        println!("tsx-runner {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if args.first().is_some_and(|arg| arg == "serve") {
        return serve(&args[1..]);
    }
    if args.first().is_some_and(|arg| arg == "run") {
        return run(&args[1..]);
    }
    if args
        .first()
        .is_some_and(|arg| arg == "agent" || arg == "conform")
    {
        return site_command(&args[0], &args[1..]);
    }
    if args.len() != 5 || args[0] != "replay" {
        return Err((2, HELP.into()));
    }
    let mut request = None;
    let mut checkpoint = None;
    for pair in args[1..].as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--request" => &mut request,
            "--checkpoint" => &mut checkpoint,
            _ => return Err((2, format!("unknown replay argument: {}", pair[0]))),
        };
        if slot.is_some() {
            return Err((2, "duplicate replay argument".into()));
        }
        *slot = Some(PathBuf::from(&pair[1]));
    }
    let request = request.ok_or((2, "missing --request".into()))?;
    let checkpoint = checkpoint.ok_or((2, "missing --checkpoint".into()))?;
    let bytes = read_bounded(&request, admission::REQUEST_BYTES).map_err(|e| (2, e))?;
    let text = std::str::from_utf8(&bytes).map_err(|_| (2, "request is not UTF-8".into()))?;
    let digest = executable_digest().map_err(|e| (3, e))?;
    let admitted = admission::admit(text, &digest).map_err(|e| (2, e))?;
    let receipt = runner::execute(admitted, &mut KafkaBroker, &checkpoint).map_err(|e| (3, e))?;
    println!(
        "{}",
        serde_json::to_string(&receipt).map_err(|e| (3, format!("cannot encode receipt: {e}")))?
    );
    Ok(())
}

fn run(args: &[String]) -> Result<(), (i32, String)> {
    if args.len() != 4 {
        return Err((2, HELP.into()));
    }
    let mut plan = None;
    let mut config = None;
    for pair in args.as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--plan" => &mut plan,
            "--config" => &mut config,
            _ => return Err((2, format!("unknown run argument: {}", pair[0]))),
        };
        if slot.is_some() {
            return Err((2, "duplicate run argument".into()));
        }
        *slot = Some(pair[1].clone());
    }
    let plan = plan.ok_or((2, "missing --plan".into()))?;
    let config = config.ok_or((2, "missing --config".into()))?;
    let schema = run_plan::configuration_schema(Path::new(&config)).map_err(refusal)?;
    if schema.as_deref() == Some("opentsx.rust-site-config.v1") {
        let config =
            tsx_runner::site_plan::SiteConfig::load(Path::new(&config)).map_err(refusal)?;
        let compact = if plan == "-" {
            read_stdin_plan()?
        } else {
            read_bounded(Path::new(&plan), run_plan::MAX_COMPACT_BYTES).map_err(|e| (2, e))?
        };
        let compact =
            std::str::from_utf8(&compact).map_err(|_| (2, "signed plan is not UTF-8".into()))?;
        let artifact = executable_digest().map_err(|e| (3, e))?;
        let guard = run_loop::SignalGuard::install().map_err(|e| (3, e))?;
        let result =
            tsx_runner::agent::run_signed(compact, &config, &artifact, &run_loop::StopToken::new());
        return print_site_result(result, guard.restore());
    }
    let config = run_plan::RunConfig::load(Path::new(&config))
        .map_err(|error| (2, format!("{}: {}", error.code, error.message)))?;
    let bytes = if plan == "-" {
        let mut bytes = Vec::new();
        std::io::stdin()
            .lock()
            .take(run_plan::MAX_COMPACT_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|error| {
                (
                    2,
                    format!("cannot read the signed plan from standard input: {error}"),
                )
            })?;
        if bytes.len() > run_plan::MAX_COMPACT_BYTES {
            return Err((2, "signed plan exceeds its byte limit".into()));
        }
        bytes
    } else {
        read_bounded(Path::new(&plan), run_plan::MAX_COMPACT_BYTES).map_err(|error| (2, error))?
    };
    let text = std::str::from_utf8(&bytes).map_err(|_| (2, "signed plan is not UTF-8".into()))?;
    let digest = executable_digest().map_err(|error| (3, error))?;
    let plan = run_plan::SignedPlan::verify(text, &config, &digest)
        .map_err(|error| (2, format!("{}: {}", error.code, error.message)))?;
    let signals = run_loop::SignalGuard::install().map_err(|error| (3, error))?;
    let result = run_loop::execute(
        &plan,
        &config,
        &mut KafkaBroker,
        &run_loop::StopToken::new(),
    );
    let restored = signals.restore();
    let outcome = match (result, restored) {
        (Ok(outcome), Ok(())) => outcome,
        (Err(error), Ok(())) => {
            let code = if matches!(error, run_loop::RunError::Refused(_)) {
                2
            } else {
                3
            };
            return Err((code, error.to_string()));
        }
        (Ok(_), Err(error)) => return Err((3, error)),
        (Err(primary), Err(secondary)) => return Err((3, format!("{primary}; {secondary}"))),
    };
    println!(
        "{}",
        serde_json::to_string(&outcome)
            .map_err(|error| (3, format!("cannot encode the stopped outcome: {error}")))?
    );
    Ok(())
}

fn refusal(error: run_plan::Refusal) -> (i32, String) {
    (2, format!("{}: {}", error.code, error.message))
}

fn read_stdin_plan() -> Result<Vec<u8>, (i32, String)> {
    let mut bytes = Vec::new();
    std::io::stdin()
        .lock()
        .take(run_plan::MAX_COMPACT_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| (2, "cannot read the signed plan from standard input".into()))?;
    if bytes.len() > run_plan::MAX_COMPACT_BYTES {
        return Err((2, "signed plan exceeds its byte limit".into()));
    }
    Ok(bytes)
}

fn site_command(mode: &str, args: &[String]) -> Result<(), (i32, String)> {
    if args == ["--help"] || args == ["-h"] {
        println!("tsx-runner {}\n{HELP}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    let expected = if mode == "agent" { 2 } else { 4 };
    if args.len() != expected {
        return Err((2, HELP.into()));
    }
    let mut config = None;
    let mut job = None;
    for pair in args.as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--config" => &mut config,
            "--job" if mode == "conform" => &mut job,
            _ => return Err((2, format!("unknown {mode} argument: {}", pair[0]))),
        };
        if slot.is_some() {
            return Err((2, format!("duplicate {mode} argument")));
        }
        *slot = Some(pair[1].as_str());
    }
    let config = tsx_runner::site_plan::SiteConfig::load(Path::new(
        config.ok_or((2, "missing --config".into()))?,
    ))
    .map_err(refusal)?;
    let artifact = executable_digest().map_err(|e| (3, e))?;
    if mode == "conform" {
        let job = job.ok_or((2, "missing --job".into()))?;
        if !config.allowed_job(job) {
            return Err((
                2,
                "plan.policy: conformance job violates local site policy".into(),
            ));
        }
        let result =
            tsx_runner::conformance::conform_once(&config, &artifact, job).map_err(|e| (3, e))?;
        let signed = result["signed"]
            .as_str()
            .ok_or((3, "conformance outcome has no signed report".into()))?;
        println!("{signed}");
        return Ok(());
    }
    let guard = run_loop::SignalGuard::install().map_err(|e| (3, e))?;
    let result = tsx_runner::agent::run(&config, &artifact, &run_loop::StopToken::new());
    print_site_result(result, guard.restore())
}

fn print_site_result(
    result: Result<serde_json::Value, run_loop::RunError>,
    restored: Result<(), String>,
) -> Result<(), (i32, String)> {
    let outcome = match (result, restored) {
        (Ok(value), Ok(())) => value,
        (Err(error), Ok(())) => {
            let code = if matches!(error, run_loop::RunError::Refused(_)) {
                2
            } else {
                3
            };
            return Err((code, error.to_string()));
        }
        (Ok(_), Err(error)) => return Err((3, error)),
        (Err(primary), Err(secondary)) => return Err((3, format!("{primary}; {secondary}"))),
    };
    println!(
        "{}",
        serde_json::to_string(&outcome).map_err(|_| (3, "cannot encode site outcome".into()))?
    );
    Ok(())
}

fn serve(args: &[String]) -> Result<(), (i32, String)> {
    if ![10, 12, 14, 16].contains(&args.len()) {
        return Err((2, HELP.into()));
    }
    let mut listen = None;
    let mut node = None;
    let mut site = None;
    let mut bootstrap = None;
    let mut state = None;
    let mut publisher = None;
    let mut controller = None;
    let mut profile = None;
    for pair in args.as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--listen" => &mut listen,
            "--node" => &mut node,
            "--site" => &mut site,
            "--bootstrap" => &mut bootstrap,
            "--state" => &mut state,
            "--publisher" => &mut publisher,
            "--controller" => &mut controller,
            "--profile" => &mut profile,
            _ => return Err((2, format!("unknown serve argument: {}", pair[0]))),
        };
        if slot.is_some() {
            return Err((2, "duplicate serve argument".into()));
        }
        *slot = Some(pair[1].clone());
    }
    let config = worker::Config {
        node: node.ok_or((2, "missing --node".into()))?,
        site: site.ok_or((2, "missing --site".into()))?,
        bootstrap: bootstrap.ok_or((2, "missing --bootstrap".into()))?,
        state: PathBuf::from(state.ok_or((2, "missing --state".into()))?),
        artifact_digest: executable_digest().map_err(|e| (3, e))?,
    };
    let listen = listen.ok_or((2, "missing --listen".into()))?;
    if publisher.is_some() != controller.is_some() {
        return Err((
            2,
            "--publisher and --controller must be supplied together".into(),
        ));
    }
    let publication = publisher
        .map(|endpoint| HttpFencedBroker::new(&endpoint))
        .transpose()
        .map_err(|error| (2, error))?;
    let controller = controller
        .map(|value| {
            value
                .parse::<std::net::IpAddr>()
                .map_err(|_| "controller must be an IP address".to_string())
        })
        .transpose()
        .map_err(|error| (2, error))?;
    if controller.is_some_and(|address| address.is_unspecified() || address.is_multicast()) {
        return Err((2, "controller must identify one TCP peer".into()));
    }
    let profile = admission::Profile::named(profile.as_deref().unwrap_or("standard"))
        .map_err(|error| (2, error))?;
    let worker = worker::Worker::open_profiled(config, profile).map_err(|e| (3, e))?;
    match (publication, controller) {
        (Some(broker), Some(controller)) => {
            worker.serve_with_controller(&listen, broker, controller)
        }
        (None, None) => worker.serve(&listen, KafkaBroker),
        _ => return Err((2, "incomplete fenced publication configuration".into())),
    }
    .map_err(|e| (3, e))
}

fn executable_digest() -> Result<String, String> {
    let executable =
        std::env::current_exe().map_err(|e| format!("cannot locate executable: {e}"))?;
    let binary = read_bounded(&executable, 268_435_456)?;
    Ok(format!("sha256:{}", tsx_contracts::sha256_bytes(&binary)))
}

fn read_bounded(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    let file = File::open(path).map_err(|e| format!("cannot open input file: {e}"))?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("cannot read input file: {e}"))?;
    if bytes.len() > limit {
        return Err("input file exceeds its byte limit".into());
    }
    Ok(bytes)
}
