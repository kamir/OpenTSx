use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use tsx_runner::publication::HttpFencedBroker;
use tsx_runner::{admission, broker::KafkaBroker, run_loop, run_plan, runner, worker};

const HELP: &str = "Usage: tsx-runner run --plan <file>|- --config <file>\n       tsx-runner replay --request <file> --checkpoint <file>\n       tsx-runner serve --listen <addr> --node <id> --site <id> --bootstrap <host:port> --state <directory> [--publisher <http-origin> --controller <IP>]\n       tsx-runner --version\n\nRuns isolated signed continuous pass-through plans and trusted-local bounded assignments. Fenced publication requires both publisher and controller options for serve. Fleet control and conformance signing are separate interfaces.";

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

fn serve(args: &[String]) -> Result<(), (i32, String)> {
    if ![10, 12, 14].contains(&args.len()) {
        return Err((2, HELP.into()));
    }
    let mut listen = None;
    let mut node = None;
    let mut site = None;
    let mut bootstrap = None;
    let mut state = None;
    let mut publisher = None;
    let mut controller = None;
    for pair in args.as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--listen" => &mut listen,
            "--node" => &mut node,
            "--site" => &mut site,
            "--bootstrap" => &mut bootstrap,
            "--state" => &mut state,
            "--publisher" => &mut publisher,
            "--controller" => &mut controller,
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
    let worker = worker::Worker::open(config).map_err(|e| (3, e))?;
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
