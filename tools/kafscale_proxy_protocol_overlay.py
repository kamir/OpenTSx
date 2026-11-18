import hashlib
from pathlib import Path
import re


SOURCE_SHA256 = "d57b4e0f44208cdcaaeecc8a78eafeaf1e75b79791f7554fe578fe10f7ba6d93"
SOURCE_PATH = Path("cmd/proxy/main.go")
TEST_PATH = Path("cmd/proxy/opentsx_protocol_test.go")


def protocol_overlay(source, output, source_root=None, output_root=None):
    source, output = Path(source), Path(output)
    original = (source / SOURCE_PATH).read_bytes()
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise RuntimeError("pinned KafScale Proxy protocol source differs")
    if (source / TEST_PATH).exists():
        raise RuntimeError("pinned KafScale Proxy regression filename already exists")
    text = original.decode("utf-8")
    pattern = re.compile(
        r"(?m)^(?P<indent>\t+)(?P<target>\w+)\.Partitions = append\((?P=target)\.Partitions, "
        r"kmsg\.(?P<kind>Produce|Fetch)ResponseTopicPartition\{\n"
        r"(?P<fields>(?:\t+\w+:[ \t]+[^\n]+,\n)+)(?P=indent)\}\)")
    matches = list(pattern.finditer(text))
    if sorted(match["kind"] for match in matches) != ["Fetch", "Fetch", "Produce", "Produce"]:
        raise RuntimeError("pinned KafScale Proxy partition constructor anchors differ")

    def partition(match):
        indent, target, kind = match["indent"], match["target"], match["kind"]
        fields = re.findall(r"(?m)^\t+(\w+):[ \t]+([^\n]+),$", match["fields"])
        expected = ["Partition", "ErrorCode", "BaseOffset"] if kind == "Produce" else ["Partition", "ErrorCode"]
        if ([name for name, _ in fields] != expected or fields[0][1] != "part.Partition"
                or fields[1][1] not in ("errorCode", "protocol.NOT_LEADER_OR_FOLLOWER")
                or (kind == "Produce" and fields[2][1] != "-1")):
            raise RuntimeError("pinned KafScale Proxy partition error fields differ")
        lines = [indent + "responsePartition := kmsg.New" + kind + "ResponseTopicPartition()"]
        lines.extend(indent + "responsePartition." + name + " = " + value for name, value in fields)
        lines.append(indent + target + ".Partitions = append(" + target + ".Partitions, responsePartition)")
        return "\n".join(lines)

    text = pattern.sub(partition, text)
    formatter = "return formatter.AppendRequest(nil, req, header.CorrelationID)"
    if text.count(formatter) != 2:
        raise RuntimeError("pinned KafScale Proxy request framing anchors differ")
    text = text.replace(formatter, formatter + "[4:]")
    healthy_backend = "\t\ttriedBackends[targetAddr] = true\n"
    if text.count(healthy_backend) != 2:
        raise RuntimeError("pinned KafScale Proxy healthy backend exclusion anchors differ")
    text = text.replace(healthy_backend, "")
    patched, regression = output / "proxy-main.go", output / "proxy-protocol_test.go"
    if output.is_symlink() or patched.is_symlink() or regression.is_symlink():
        raise RuntimeError("protocol overlay output must not be a symbolic link")
    output.mkdir(parents=True, exist_ok=True)
    patched.write_text(text, encoding="utf-8")
    regression.write_bytes(Path(__file__).with_name("kafscale_proxy_protocol_test.go").read_bytes())
    return {str(Path(source_root or source) / name): str(Path(output_root or output) / generated.name)
            for name, generated in [(SOURCE_PATH, patched), (TEST_PATH, regression)]}
