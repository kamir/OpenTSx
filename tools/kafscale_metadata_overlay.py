import hashlib
from pathlib import Path


SOURCE_SHA256 = "1688d47fc8316b7cee5ea03bf3a633ccb50a2b357820181fb5823fa95d5d1095"
SOURCE_PATH = Path("pkg/metadata/etcd_store.go")


def metadata_overlay(source, output, source_root=None, output_root=None):
    source, output = Path(source), Path(output)
    original = (source / SOURCE_PATH).read_bytes()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise RuntimeError("pinned KafScale EtcdStore source differs")
    text = original.decode("utf-8")
    replacements = [
        ('\t"sync/atomic"\n', '\t"sync"\n\t"sync/atomic"\n'),
        ("type EtcdStore struct {\n", "type EtcdStore struct {\n\tsnapshotMu sync.Mutex\n\n"),
    ]
    for header in [
        "func (s *EtcdStore) CreateTopic(ctx context.Context, spec TopicSpec) (*protocol.MetadataTopic, error) {\n",
        "func (s *EtcdStore) CreatePartitions(ctx context.Context, topic string, partitionCount int32) error {\n",
        "func (s *EtcdStore) DeleteTopic(ctx context.Context, name string) error {\n",
        "func (s *EtcdStore) refreshSnapshot(ctx context.Context) error {\n",
    ]:
        replacements.append((header, header + "\ts.snapshotMu.Lock()\n\tdefer s.snapshotMu.Unlock()\n\n"))
    for marker, replacement in replacements:
        if text.count(marker) != 1:
            raise RuntimeError("pinned KafScale EtcdStore patch anchor differs")
        text = text.replace(marker, replacement, 1)
    patched = output / "metadata-etcd-store.go"
    if output.is_symlink() or patched.is_symlink():
        raise RuntimeError("metadata overlay output must not be a symbolic link")
    output.mkdir(parents=True, exist_ok=True)
    patched.write_text(text, encoding="utf-8")
    return {str(Path(source_root or source) / SOURCE_PATH): str(Path(output_root or output) / patched.name)}
