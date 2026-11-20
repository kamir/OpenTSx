from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import unittest


DOCKERFILE = Path(__file__).with_name("opentsx-tools.Dockerfile")
PASSWD = "opentsx:x:10001:10001:OpenTSx:/tmp:/usr/sbin/nologin\n"
GROUP = "opentsx:x:10001:\n"


class ImageAccountsTest(unittest.TestCase):
    def assert_accounts(self, source):
        start = "FROM --platform=$BUILDPLATFORM ${JRE_IMAGE} AS accounts\n"
        self.assertIn(start, source)
        native, runtime = source.split(start, 1)[1].split("\nFROM ${JRE_IMAGE}\n", 1)
        self.assertEqual(1, len(re.findall(r"(?m)^RUN ", native)))
        self.assertIn(
            "printf '" + PASSWD.replace("\n", r"\n") + "' >> /etc/passwd", native
        )
        self.assertIn(
            "printf '" + GROUP.replace("\n", r"\n") + "' >> /etc/group", native
        )
        self.assertIn("COPY --from=accounts /etc/passwd /etc/passwd", runtime)
        self.assertIn("COPY --from=accounts /etc/group /etc/group", runtime)
        self.assertRegex(runtime, r"(?m)^USER 10001:10001$")
        self.assertNotRegex(runtime, r"(?m)^RUN\b")
        self.assertNotIn("--platform=$TARGETPLATFORM", source)
        return native.split("RUN ", 1)[1].strip()

    def test_fixed_runtime_identity_has_a_native_account_stage(self):
        self.assert_accounts(DOCKERFILE.read_text())

    def test_account_commands_preserve_existing_passwd_and_group_entries(self):
        command = self.assert_accounts(DOCKERFILE.read_text())
        with tempfile.TemporaryDirectory(prefix="opentsx accounts ") as directory:
            root = Path(directory)
            passwd = root / "passwd"
            group = root / "group"
            original_passwd = "root:x:0:0:root:/root:/bin/bash\n"
            original_group = "root:x:0:\n"
            passwd.write_text(original_passwd)
            group.write_text(original_group)
            command = command.replace("/etc/passwd", shlex.quote(str(passwd)))
            command = command.replace("/etc/group", shlex.quote(str(group)))
            result = subprocess.run(
                ["sh", "-ec", command], text=True, capture_output=True
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(original_passwd + PASSWD, passwd.read_text())
            self.assertEqual(original_group + GROUP, group.read_text())

    def test_contract_rejects_missing_accounts_wrong_ids_and_target_execution(self):
        source = DOCKERFILE.read_text()
        self.assert_accounts(source)
        mutations = {
            "target execution": source.replace(
                "--platform=$BUILDPLATFORM", "--platform=$TARGETPLATFORM"
            ),
            "unselected build platform": source.replace(
                "--platform=$BUILDPLATFORM ", ""
            ),
            "wrong uid": source.replace(
                "opentsx:x:10001:10001:", "opentsx:x:10002:10001:"
            ),
            "wrong group": source.replace("opentsx:x:10001:\\n", "opentsx:x:10002:\\n"),
            "discarded base accounts": source.replace(
                ">> /etc/passwd", "> /etc/passwd"
            ),
            "missing passwd copy": source.replace(
                "COPY --from=accounts /etc/passwd /etc/passwd", ""
            ),
            "missing group copy": source.replace(
                "COPY --from=accounts /etc/group /etc/group", ""
            ),
            "root runtime": source.replace("USER 10001:10001", "USER 0:0"),
            "runtime command": source + "\nRUN true\n",
        }
        for name, mutated in mutations.items():
            self.assertNotEqual(source, mutated, name)
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.assert_accounts(mutated)


if __name__ == "__main__":
    unittest.main()
