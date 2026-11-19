use crate::checkpoint::Store;
use crate::run_plan::Refusal;
use std::collections::BTreeSet;
use std::fs::{self, DirBuilder};
use std::os::unix::fs::{DirBuilderExt, MetadataExt};
use std::path::Path;

pub(crate) fn acquire(job: &str, bootstrap: &str, topic: &str) -> Result<Store, Refusal> {
    let uid = unsafe { libc::geteuid() };
    let temporary = fs::canonicalize("/tmp").map_err(|error| {
        policy(format!(
            "cannot locate the local reservation directory: {error}"
        ))
    })?;
    let root = temporary.join(format!("opentsx-runner-reservations-{uid}"));
    acquire_at(&root, uid, job, bootstrap, topic)
}

fn acquire_at(
    root: &Path,
    uid: u32,
    job: &str,
    bootstrap: &str,
    topic: &str,
) -> Result<Store, Refusal> {
    match DirBuilder::new().mode(0o700).create(root) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(error) => {
            return Err(policy(format!(
                "cannot create the local reservation directory: {error}"
            )))
        }
    }
    validate_root(root, uid)?;
    let key = reservation_key(job, bootstrap, topic)?;
    Store::open(&root.join(format!("{key}.json")), 65_536).map_err(|error| {
        if error.starts_with("checkpoint is locked") {
            Refusal::new(
                "plan.busy",
                "this job already owns the complete input topic on this host",
            )
        } else {
            policy(format!(
                "cannot reserve the complete local input topic: {error}"
            ))
        }
    })
}

fn validate_root(root: &Path, uid: u32) -> Result<(), Refusal> {
    let metadata = fs::symlink_metadata(root).map_err(|error| {
        policy(format!(
            "cannot inspect the local reservation directory: {error}"
        ))
    })?;
    if !metadata.file_type().is_dir() || metadata.uid() != uid || metadata.mode() & 0o7777 != 0o700
    {
        return Err(policy(
            "the local reservation directory must be a private, owned directory with mode 0700",
        ));
    }
    Ok(())
}

fn reservation_key(job: &str, bootstrap: &str, topic: &str) -> Result<String, Refusal> {
    crate::admission::validate_bootstrap(bootstrap).map_err(policy)?;
    let mut endpoints = BTreeSet::new();
    for address in bootstrap.split(',') {
        let (host, port) = address
            .rsplit_once(':')
            .ok_or_else(|| policy("invalid reservation broker address"))?;
        let port = port
            .parse::<u16>()
            .map_err(|_| policy("invalid reservation broker port"))?;
        let host = if let Some(ip) = host
            .strip_prefix('[')
            .and_then(|value| value.strip_suffix(']'))
        {
            format!(
                "[{}]",
                ip.parse::<std::net::Ipv6Addr>()
                    .map_err(|_| policy("invalid reservation IPv6 address"))?
            )
        } else {
            host.to_ascii_lowercase()
        };
        endpoints.insert(format!("{host}:{port}"));
    }
    let brokers = endpoints.into_iter().collect::<Vec<_>>().join(",");
    let mut framed = Vec::new();
    for field in [job, brokers.as_str(), topic] {
        framed.extend_from_slice(&(field.len() as u64).to_be_bytes());
        framed.extend_from_slice(field.as_bytes());
    }
    Ok(tsx_contracts::sha256_bytes(&framed))
}

fn policy(message: impl Into<String>) -> Refusal {
    Refusal::new("plan.policy", message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_loop::run_profile::Fixture;
    use std::os::unix::fs::{symlink, PermissionsExt};

    #[test]
    fn reservation_identity_normalizes_endpoint_order_duplicates_case_ports_and_ipv6() {
        let first = reservation_key(
            "job",
            "HOST:09092,[2001:0DB8:0:0:0:0:0:1]:9093,host:9092",
            "input",
        )
        .unwrap();
        assert_eq!(
            first,
            reservation_key("job", "[2001:db8::1]:09093,host:9092", "input").unwrap()
        );
        for (job, bootstrap, topic) in [
            ("other", "host:9092,[2001:db8::1]:9093", "input"),
            ("job", "host:9094,[2001:db8::1]:9093", "input"),
            ("job", "host:9092,[2001:db8::1]:9093", "other"),
        ] {
            assert_ne!(first, reservation_key(job, bootstrap, topic).unwrap());
        }
        assert_ne!(
            reservation_key("ab", "host:9092", "c").unwrap(),
            reservation_key("a", "host:9092", "bc").unwrap()
        );
        assert!(reservation_key("job", "user@host:9092", "input").is_err());
    }

    #[test]
    fn ownership_is_exclusive_per_job_and_released_without_unlinking_after_drop() {
        let fixture = Fixture::new();
        let root = fixture.directory.join("reservations");
        let uid = unsafe { libc::geteuid() };
        let owner = acquire_at(&root, uid, "job", "HOST:09092", "input").unwrap();
        assert_eq!(
            acquire_at(&root, uid, "job", "host:9092,host:9092", "input")
                .err()
                .unwrap()
                .code,
            "plan.busy"
        );
        let other = acquire_at(&root, uid, "another-job", "host:9092", "input").unwrap();
        let files: Vec<_> = fs::read_dir(&root)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        assert_eq!(files.len(), 2);
        drop(owner);
        let reopened = acquire_at(&root, uid, "job", "host:9092", "input").unwrap();
        assert!(files.iter().all(|path| path.is_file()));
        drop(reopened);
        drop(other);
    }

    #[test]
    fn unsafe_reservation_roots_and_lock_links_are_refused() {
        let fixture = Fixture::new();
        let root = fixture.directory.join("reservations");
        let uid = unsafe { libc::geteuid() };
        fs::write(&root, b"file").unwrap();
        assert!(acquire_at(&root, uid, "job", "host:9092", "input").is_err());
        fs::remove_file(&root).unwrap();
        symlink(&fixture.directory, &root).unwrap();
        assert!(acquire_at(&root, uid, "job", "host:9092", "input").is_err());
        fs::remove_file(&root).unwrap();
        fs::create_dir(&root).unwrap();
        fs::set_permissions(&root, fs::Permissions::from_mode(0o755)).unwrap();
        assert!(acquire_at(&root, uid, "job", "host:9092", "input").is_err());
        fs::set_permissions(&root, fs::Permissions::from_mode(0o700)).unwrap();
        assert!(validate_root(&root, uid.wrapping_add(1)).is_err());
        let target = fixture.directory.join("untouched");
        fs::write(&target, b"original").unwrap();
        let key = reservation_key("job", "host:9092", "input").unwrap();
        symlink(&target, root.join(format!(".{key}.json.lock"))).unwrap();
        assert!(acquire_at(&root, uid, "job", "host:9092", "input").is_err());
        assert_eq!(fs::read(target).unwrap(), b"original");
    }
}
