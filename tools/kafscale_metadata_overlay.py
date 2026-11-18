import hashlib
from pathlib import Path


SOURCE_SHA256 = "1688d47fc8316b7cee5ea03bf3a633ccb50a2b357820181fb5823fa95d5d1095"
SOURCE_PATH = Path("pkg/metadata/etcd_store.go")
TEST_PATH = Path("pkg/metadata/opentsx_metadata_test.go")


def metadata_overlay(source, output, source_root=None, output_root=None):
    source, output = Path(source), Path(output)
    original = (source / SOURCE_PATH).read_bytes()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise RuntimeError("pinned KafScale EtcdStore source differs")
    if (source / TEST_PATH).exists():
        raise RuntimeError("pinned KafScale metadata regression filename already exists")
    text = original.decode("utf-8")
    replacements = [
        ('\t"fmt"\n', '\t"fmt"\n\t"math"\n'),
        ('''	next := lastOffset + 1
	_, err := s.client.Put(ctx, offsetKey(topic, partition), strconv.FormatInt(next, 10))
	s.recordEtcdResult(err)
	return err
''', '''	if lastOffset < 0 || lastOffset == math.MaxInt64 {
		return errors.New("last offset must be nonnegative and below MaxInt64")
	}
	next := lastOffset + 1
	key := offsetKey(topic, partition)
	for {
		response, err := s.client.Get(ctx, key)
		s.recordEtcdResult(err)
		if err != nil {
			return err
		}
		condition := clientv3.Compare(clientv3.Version(key), "=", 0)
		if len(response.Kvs) != 0 {
			value := strings.TrimSpace(string(response.Kvs[0].Value))
			current := int64(0)
			if value != "" {
				current, err = strconv.ParseInt(value, 10, 64)
				if err != nil {
					return fmt.Errorf("parse offset for %s: %w", key, err)
				}
			}
			if current < 0 {
				return fmt.Errorf("committed offset for %s must be nonnegative", key)
			}
			if current >= next {
				return nil
			}
			condition = clientv3.Compare(clientv3.ModRevision(key), "=", response.Kvs[0].ModRevision)
		}
		result, err := s.client.Txn(ctx).If(condition).Then(clientv3.OpPut(key, strconv.FormatInt(next, 10))).Commit()
		s.recordEtcdResult(err)
		if err != nil {
			return err
		}
		if result.Succeeded {
			return nil
		}
	}
'''),
        ('\t"sync/atomic"\n', '\t"sync"\n\t"sync/atomic"\n'),
        ("type EtcdStore struct {\n", "type EtcdStore struct {\n\tsnapshotMu sync.Mutex\n\n"),
        ("// Metadata delegates to the snapshot captured at startup (operator keeps it fresh).\n", ""),
        ("func (s *EtcdStore) Metadata(ctx context.Context, topics []string) (*ClusterMetadata, error) {\n",
         "func (s *EtcdStore) Metadata(ctx context.Context, topics []string) (*ClusterMetadata, error) {\n"
         "\tif err := s.refreshSnapshot(ctx); err != nil {\n\t\treturn nil, err\n\t}\n"),
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
    patched, regression = output / "metadata-etcd-store.go", output / "metadata_test.go"
    if output.is_symlink() or patched.is_symlink() or regression.is_symlink():
        raise RuntimeError("metadata overlay output must not be a symbolic link")
    output.mkdir(parents=True, exist_ok=True)
    patched.write_text(text, encoding="utf-8")
    regression.write_bytes(Path(__file__).with_name("kafscale_metadata_test.go").read_bytes())
    return {str(Path(source_root or source) / name): str(Path(output_root or output) / generated.name)
            for name, generated in [(SOURCE_PATH, patched), (TEST_PATH, regression)]}
