use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use tsx_runner::{admission, broker::KafkaBroker, runner, worker};

const HELP: &str = "Usage: tsx-runner replay --request <file> --checkpoint <file>\n       tsx-runner serve --listen <addr> --node <id> --site <id> --bootstrap <host:port> --state <directory>\n       tsx-runner --version\n\nExecutes trusted-local bounded whole-topic assignments. Signed fleet activation is a separate interface.";

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

fn serve(args: &[String]) -> Result<(), (i32, String)> {
    if args.len() != 10 {
        return Err((2, HELP.into()));
    }
    let mut listen = None;
    let mut node = None;
    let mut site = None;
    let mut bootstrap = None;
    let mut state = None;
    for pair in args.as_chunks::<2>().0 {
        let slot = match pair[0].as_str() {
            "--listen" => &mut listen,
            "--node" => &mut node,
            "--site" => &mut site,
            "--bootstrap" => &mut bootstrap,
            "--state" => &mut state,
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
    let worker = worker::Worker::open(config).map_err(|e| (3, e))?;
    worker.serve(&listen, KafkaBroker).map_err(|e| (3, e))
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
