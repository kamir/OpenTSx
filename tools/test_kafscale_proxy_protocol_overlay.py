import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import kafscale_proxy_protocol_overlay as OVERLAY


SOURCE = '''package main

func produceRetry() {
	tr.Partitions = append(tr.Partitions, kmsg.ProduceResponseTopicPartition{
		Partition:  part.Partition,
		ErrorCode:  protocol.NOT_LEADER_OR_FOLLOWER,
		BaseOffset: -1,
	})
}

func produceFailure() {
	topicResp.Partitions = append(topicResp.Partitions, kmsg.ProduceResponseTopicPartition{
		Partition:  part.Partition,
		ErrorCode:  errorCode,
		BaseOffset: -1,
	})
}

func fetchRetry() {
	tr.Partitions = append(tr.Partitions, kmsg.FetchResponseTopicPartition{
		Partition: part.Partition,
		ErrorCode: protocol.NOT_LEADER_OR_FOLLOWER,
	})
}

func fetchFailure() {
	tr.Partitions = append(tr.Partitions, kmsg.FetchResponseTopicPartition{
		Partition: part.Partition,
		ErrorCode: errorCode,
	})
}

func encodeProduceRequest() {
	return formatter.AppendRequest(nil, req, header.CorrelationID)
}

func encodeFetchRequest() {
	return formatter.AppendRequest(nil, req, header.CorrelationID)
}
'''


class KafScaleProxyProtocolOverlayTest(unittest.TestCase):
    def generate(self, directory, text=SOURCE, **roots):
        source, output = Path(directory) / "source", Path(directory) / "output"
        original = source / OVERLAY.SOURCE_PATH
        original.parent.mkdir(parents=True)
        original.write_text(text)
        with patch.object(OVERLAY, "SOURCE_SHA256", hashlib.sha256(text.encode()).hexdigest()):
            mapping = OVERLAY.protocol_overlay(source, output, **roots)
        self.assertEqual(text, original.read_text())
        return mapping, (output / "proxy-main.go").read_text()

    def test_all_synthetic_partitions_use_defaults_and_both_encoders_return_only_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            _, generated = self.generate(directory)
        self.assertEqual(2, generated.count("kmsg.NewProduceResponseTopicPartition()"))
        self.assertEqual(2, generated.count("kmsg.NewFetchResponseTopicPartition()"))
        self.assertNotIn("kmsg.ProduceResponseTopicPartition{", generated)
        self.assertNotIn("kmsg.FetchResponseTopicPartition{", generated)
        self.assertEqual(2, generated.count("responsePartition.ErrorCode = protocol.NOT_LEADER_OR_FOLLOWER"))
        self.assertEqual(2, generated.count("responsePartition.ErrorCode = errorCode"))
        self.assertEqual(2, generated.count("responsePartition.BaseOffset = -1"))
        self.assertEqual(2, generated.count("return formatter.AppendRequest(nil, req, header.CorrelationID)[4:]"))

    def test_mapping_includes_actual_package_regressions_without_editing_upstream(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping, _ = self.generate(directory, source_root="/source", output_root="/artifacts")
            self.assertEqual({"/source/cmd/proxy/main.go": "/artifacts/proxy-main.go",
                "/source/cmd/proxy/opentsx_protocol_test.go": "/artifacts/proxy-protocol_test.go"}, mapping)
            regression = Path(directory) / "output/proxy-protocol_test.go"
            self.assertEqual(Path(OVERLAY.__file__).with_name("kafscale_proxy_protocol_test.go").read_bytes(), regression.read_bytes())
            self.assertFalse((Path(directory) / "source" / OVERLAY.TEST_PATH).exists())

    def test_unknown_source_fails_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source", Path(directory) / "output"
            original = source / OVERLAY.SOURCE_PATH
            original.parent.mkdir(parents=True)
            original.write_text(SOURCE)
            with self.assertRaisesRegex(RuntimeError, "pinned KafScale Proxy protocol source differs"):
                OVERLAY.protocol_overlay(source, output)
            self.assertFalse(output.exists())

    def test_constructor_field_and_framing_changes_fail_closed(self):
        for text in [SOURCE + SOURCE, SOURCE.replace("BaseOffset: -1", "BaseOffset: 0", 1),
                     SOURCE.replace("kmsg.FetchResponseTopicPartition{", "kmsg.MissingPartition{", 1),
                     SOURCE.replace("return formatter.AppendRequest(nil, req, header.CorrelationID)", "return nil", 1)]:
            with self.subTest(source=text[:20]), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(RuntimeError):
                    self.generate(directory, text)
                self.assertFalse((Path(directory) / "output").exists())

    def test_source_hash_pins_the_version_and_output_rejects_symbolic_links(self):
        self.assertEqual("d57b4e0f44208cdcaaeecc8a78eafeaf1e75b79791f7554fe578fe10f7ba6d93", OVERLAY.SOURCE_SHA256)
        with tempfile.TemporaryDirectory() as directory:
            self.generate(directory)
            source, output = Path(directory) / "source", Path(directory) / "output"
            generated = output / "proxy-main.go"
            generated.unlink()
            sentinel = Path(directory) / "sentinel"
            sentinel.write_text("unchanged")
            generated.symlink_to(sentinel)
            with patch.object(OVERLAY, "SOURCE_SHA256", hashlib.sha256(SOURCE.encode()).hexdigest()):
                with self.assertRaisesRegex(RuntimeError, "symbolic link"):
                    OVERLAY.protocol_overlay(source, output)
            self.assertEqual("unchanged", sentinel.read_text())


if __name__ == "__main__":
    unittest.main()
