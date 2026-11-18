import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import kafscale_metadata_overlay as OVERLAY


SOURCE = '''package metadata

import (
	"context"
	"fmt"
	"sync/atomic"
)

type EtcdStore struct {
	lastError atomic.Value
}

// Metadata delegates to the snapshot captured at startup (operator keeps it fresh).
func (s *EtcdStore) Metadata(ctx context.Context, topics []string) (*ClusterMetadata, error) {
	return s.metadata.Metadata(ctx, topics)
}

func (s *EtcdStore) CreateTopic(ctx context.Context, spec TopicSpec) (*protocol.MetadataTopic, error) {
	topic, err := s.metadata.CreateTopic(ctx, spec)
	if err != nil { return nil, err }
	if err := s.persistSnapshot(ctx); err != nil { return nil, err }
	return topic, nil
}

func (s *EtcdStore) CreatePartitions(ctx context.Context, topic string, partitionCount int32) error {
	if err := s.metadata.CreatePartitions(ctx, topic, partitionCount); err != nil { return err }
	return s.persistSnapshot(ctx)
}

func (s *EtcdStore) DeleteTopic(ctx context.Context, name string) error {
	if err := s.metadata.DeleteTopic(ctx, name); err != nil { return err }
	return s.persistSnapshot(ctx)
}

func (s *EtcdStore) refreshSnapshot(ctx context.Context) error {
	response, err := s.client.Get(ctx, snapshotKey())
	if err != nil { return err }
	s.metadata.Update(response)
	return nil
}

func (s *EtcdStore) UpdateOffsets(ctx context.Context, topic string, partition int32, lastOffset int64) error {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	next := lastOffset + 1
	_, err := s.client.Put(ctx, offsetKey(topic, partition), strconv.FormatInt(next, 10))
	s.recordEtcdResult(err)
	return err
}
'''


class KafScaleMetadataOverlayTest(unittest.TestCase):
    def generate(self, directory, text=SOURCE, **roots):
        source, output = Path(directory) / "source", Path(directory) / "output"
        path = source / OVERLAY.SOURCE_PATH
        path.parent.mkdir(parents=True)
        path.write_text(text)
        with patch.object(OVERLAY, "SOURCE_SHA256", hashlib.sha256(text.encode()).hexdigest()):
            mapping = OVERLAY.metadata_overlay(source, output, **roots)
        self.assertEqual(text.encode(), path.read_bytes())
        return mapping, (output / "metadata-etcd-store.go").read_text()

    def test_every_snapshot_mutation_and_complete_refresh_share_the_same_function_scoped_mutex(self):
        with tempfile.TemporaryDirectory() as directory:
            _, modified = self.generate(directory)
        self.assertIn('\t"sync"\n', modified)
        self.assertEqual(1, modified.count("snapshotMu sync.Mutex"))
        for name in ["CreateTopic", "CreatePartitions", "DeleteTopic", "refreshSnapshot"]:
            header = "func (s *EtcdStore) " + name + "("
            body = modified.split(header, 1)[1].split("{\n", 1)[1].split("\n}", 1)[0]
            with self.subTest(method=name):
                self.assertTrue(body.startswith("\ts.snapshotMu.Lock()\n\tdefer s.snapshotMu.Unlock()\n"))
                self.assertEqual(1, body.count("s.snapshotMu.Lock()"))
                self.assertEqual(1, body.count("s.snapshotMu.Unlock()"))
                if name == "refreshSnapshot":
                    self.assertLess(body.index("s.snapshotMu.Lock()"), body.index("s.client.Get("))
                    self.assertIn("s.metadata.Update(", body)
                else:
                    self.assertLess(body.index("s.snapshotMu.Lock()"), body.index("s.metadata." + name + "("))
                    self.assertIn("s.persistSnapshot(", body)

    def test_generated_mapping_uses_container_roots_without_touching_the_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping, modified = self.generate(directory, source_root="/source", output_root="/artifacts")
            self.assertEqual({"/source/pkg/metadata/etcd_store.go": "/artifacts/metadata-etcd-store.go",
                              "/source/pkg/metadata/opentsx_metadata_test.go": "/artifacts/metadata_test.go"}, mapping)
            self.assertNotEqual(SOURCE, modified)
            regression = Path(directory) / "output/metadata_test.go"
            self.assertEqual(Path(OVERLAY.__file__).with_name("kafscale_metadata_test.go").read_bytes(), regression.read_bytes())
            self.assertFalse((Path(directory) / "source/pkg/metadata/opentsx_metadata_test.go").exists())

    def test_public_metadata_refreshes_the_durable_snapshot_before_returning_a_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            _, modified = self.generate(directory)
        body = modified.split("func (s *EtcdStore) Metadata(", 1)[1].split("{\n", 1)[1].split("\n}", 1)[0]
        self.assertEqual("\tif err := s.refreshSnapshot(ctx); err != nil {\n\t\treturn nil, err\n\t}\n\treturn s.metadata.Metadata(ctx, topics)", body)
        self.assertNotIn("snapshotMu", body)

    def test_unknown_upstream_source_fails_before_writing_an_overlay(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source", Path(directory) / "output"
            path = source / OVERLAY.SOURCE_PATH
            path.parent.mkdir(parents=True)
            path.write_text(SOURCE)
            with self.assertRaisesRegex(RuntimeError, "pinned KafScale EtcdStore source differs"):
                OVERLAY.metadata_overlay(source, output)
            self.assertFalse(output.exists())
            self.assertEqual(SOURCE, path.read_text())

    def test_source_digest_matches_the_pinned_version(self):
        self.assertEqual("1688d47fc8316b7cee5ea03bf3a633ccb50a2b357820181fb5823fa95d5d1095", OVERLAY.SOURCE_SHA256)

    def test_missing_or_duplicate_patch_anchors_fail_before_writing(self):
        mutations = [SOURCE.replace('\t"sync/atomic"\n', ''), SOURCE.replace('type EtcdStore struct {\n', ''),
                     SOURCE.replace('func (s *EtcdStore) Metadata(', 'func (s *EtcdStore) CachedMetadata('),
                     SOURCE.replace('func (s *EtcdStore) DeleteTopic(', 'func (s *EtcdStore) RemovedTopic('),
                     SOURCE + SOURCE]
        for text in mutations:
            with self.subTest(source=text[:20]), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "patch anchor differs"):
                    self.generate(directory, text)
                self.assertFalse((Path(directory) / "output").exists())

    def test_generation_is_repeatable_and_refuses_symbolic_link_output(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping, first = self.generate(directory)
            source, output = Path(directory) / "source", Path(directory) / "output"
            with patch.object(OVERLAY, "SOURCE_SHA256", hashlib.sha256(SOURCE.encode()).hexdigest()):
                self.assertEqual(mapping, OVERLAY.metadata_overlay(source, output))
                generated = output / "metadata-etcd-store.go"
                self.assertEqual(first, generated.read_text())
                generated.unlink()
                sentinel = Path(directory) / "unchanged.go"
                sentinel.write_text("retain this file")
                generated.symlink_to(sentinel)
                with self.assertRaisesRegex(RuntimeError, "symbolic link"):
                    OVERLAY.metadata_overlay(source, output)
                self.assertEqual("retain this file", sentinel.read_text())
            self.assertEqual(SOURCE, (source / OVERLAY.SOURCE_PATH).read_text())

    def test_regression_filename_collision_fails_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source", Path(directory) / "output"
            original = source / OVERLAY.SOURCE_PATH
            original.parent.mkdir(parents=True)
            original.write_text(SOURCE)
            (original.parent / "opentsx_metadata_test.go").write_text("existing source")
            with patch.object(OVERLAY, "SOURCE_SHA256", hashlib.sha256(SOURCE.encode()).hexdigest()):
                with self.assertRaisesRegex(RuntimeError, "regression filename already exists"):
                    OVERLAY.metadata_overlay(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
