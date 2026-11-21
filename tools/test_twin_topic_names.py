from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
TWIN_TOPIC = r"""\.events(\.v[0-9]+)?["'`]|(^|[^[:alnum:]_.])twin\.[^[:space:]"'`]*\.events"""
# The Studio is a static page and builds the name in the browser; EventTopicLiveTest pins it to TopicLayout.
BUILDERS = ["docs/prototypes/smart-topics-studio.html", "docs/prototypes/smart-topics-studio.template.html",
            "opentsx-kafka-v2/src/main/java/org/opentsx/kafka/TopicLayout.java"]


class TwinTopicNamesTest(unittest.TestCase):
    def test_only_topic_layout_and_the_studio_build_twin_event_topic_names(self):
        found = subprocess.run(["git", "grep", "-l", "-I", "-E", TWIN_TOPIC, "--", ".", ":!*.md", ":!archive",
                                ":!*/src/test/*", ":!tools/test_*.py"], cwd=ROOT, capture_output=True, text=True)
        self.assertIn(found.returncode, (0, 1), found.stderr)
        self.assertEqual(BUILDERS, sorted(found.stdout.split()),
                         "name twin event topics only through TopicLayout.twinEvents")


if __name__ == "__main__":
    unittest.main()
