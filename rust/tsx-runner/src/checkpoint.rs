use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CompletedPosition {
    pub partition: String,
    pub next: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Receipt {
    pub schema: String,
    pub request_hash: String,
    pub artifact_digest: String,
    pub deployment: String,
    pub generation: String,
    pub assignment: String,
    pub node: String,
    pub input_topic: String,
    pub output_topic: String,
    pub positions: Vec<CompletedPosition>,
    pub input_records: usize,
    pub output_records: usize,
    pub output_digest: String,
    pub state_digest: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Completion {
    pub request_hash: String,
    pub receipt: Receipt,
    pub state: Value,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    schema: String,
    checksum: String,
    payload: Value,
}

pub struct Store {
    path: PathBuf,
    pending: PathBuf,
    max_bytes: usize,
    _lock: File,
}

impl Store {
    pub fn open(path: &Path, max_bytes: usize) -> Result<Self, String> {
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or("checkpoint needs a file name")?;
        if name.is_empty() || name == "." || name == ".." {
            return Err("invalid checkpoint file name".into());
        }
        let parent = path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        if !parent.is_dir() {
            return Err("checkpoint parent directory does not exist".into());
        }
        let lock_path = parent.join(format!(".{name}.lock"));
        regular_or_absent(&lock_path)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&lock_path)
            .map_err(|e| format!("cannot open checkpoint lock: {e}"))?;
        lock.try_lock()
            .map_err(|e| format!("checkpoint is locked or cannot be locked: {e}"))?;
        regular_or_absent(path)?;
        let pending = parent.join(format!(".{name}.pending"));
        regular_or_absent(&pending)?;
        Ok(Self {
            path: path.to_path_buf(),
            pending,
            max_bytes,
            _lock: lock,
        })
    }

    pub fn load(&self) -> Result<Option<Completion>, String> {
        regular_or_absent(&self.path)?;
        let file = match File::open(&self.path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(format!("cannot open checkpoint: {error}")),
        };
        let mut bytes = Vec::new();
        file.take(self.max_bytes as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("cannot read checkpoint: {e}"))?;
        if bytes.len() > self.max_bytes {
            return Err("checkpoint exceeds its byte limit".into());
        }
        let text = std::str::from_utf8(&bytes).map_err(|_| "checkpoint is not UTF-8")?;
        let parsed = tsx_contracts::parse_json(text, self.max_bytes).map_err(str::to_string)?;
        let envelope: Envelope =
            serde_json::from_value(parsed).map_err(|_| "invalid checkpoint envelope")?;
        if envelope.schema != "opentsx.local-checkpoint.v1" {
            return Err("unsupported checkpoint ABI".into());
        }
        let payload = serde_json::to_vec(&envelope.payload)
            .map_err(|e| format!("cannot validate checkpoint: {e}"))?;
        if tsx_contracts::sha256_bytes(&payload) != envelope.checksum {
            return Err("checkpoint checksum mismatch".into());
        }
        let completion =
            serde_json::from_value(envelope.payload).map_err(|_| "invalid checkpoint payload")?;
        Ok(Some(completion))
    }

    pub fn validate(&self, completion: &Completion) -> Result<(), String> {
        self.encode(completion).map(|_| ())
    }

    fn encode(&self, completion: &Completion) -> Result<Vec<u8>, String> {
        let payload = serde_json::to_value(completion)
            .map_err(|e| format!("cannot encode checkpoint: {e}"))?;
        let canonical =
            serde_json::to_vec(&payload).map_err(|e| format!("cannot encode checkpoint: {e}"))?;
        let envelope = Envelope {
            schema: "opentsx.local-checkpoint.v1".into(),
            checksum: tsx_contracts::sha256_bytes(&canonical),
            payload,
        };
        let bytes =
            serde_json::to_vec(&envelope).map_err(|e| format!("cannot encode checkpoint: {e}"))?;
        if bytes.len() > self.max_bytes {
            return Err("checkpoint exceeds its byte limit".into());
        }
        Ok(bytes)
    }

    pub fn commit(&self, completion: &Completion) -> Result<(), String> {
        let bytes = self.encode(completion)?;
        regular_or_absent(&self.pending)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&self.pending)
            .map_err(|e| format!("cannot open pending checkpoint: {e}"))?;
        file.write_all(&bytes)
            .map_err(|e| format!("cannot write pending checkpoint: {e}"))?;
        file.sync_all()
            .map_err(|e| format!("cannot synchronize pending checkpoint: {e}"))?;
        fs::rename(&self.pending, &self.path)
            .map_err(|e| format!("cannot publish checkpoint: {e}"))?;
        let parent = self
            .path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(|e| format!("cannot synchronize checkpoint directory: {e}"))?;
        Ok(())
    }
}

fn regular_or_absent(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_file() => Ok(()),
        Ok(_) => Err("checkpoint paths must be regular files, not links or directories".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("cannot inspect checkpoint path: {error}")),
    }
}
