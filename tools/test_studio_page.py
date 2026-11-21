from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "docs/prototypes/smart-topics-studio.template.html"
PAGE = ROOT / "docs/prototypes/smart-topics-studio.html"
DATA_START = "const ALL = "
DATA_END = ";\nlet SC = null"
URL = re.compile(r"https?://[^\s\"'<>]+")
GERMAN = ("Überblick", "Vergleich", "Systembild", "Messreihen", "Erläuterung", "Einklappen", "Ausklappen", "Kennzahlen",
          "Referenzlauf", "Ereignis", "Ereignisse", "Prognose", "Baustein", "Bausteine", "Verbindung", "Zeitpunkt",
          "Zeitschritt", "Wiedergabe", "Verbinden", "Trennen", "Schließen", "Beispieldaten", "Eingang", "Ausgang",
          "Fahrweise", "Sicherung", "Anlagenmodell", "Hersteller", "Seriennummer", "Datei", "Ansicht", "Hilfe",
          "Szenario", "Strg", "Wasserstoffkette", "Einfamilienhaus", "Schutzabschaltungen", "Ladezustand",
          "Landstrom", "Höchstdruck", "Bestellschwelle", "Hebel", "Worum", "Betriebsvorgaben", "Übergabestation",
          "Elektrolyseur", "und", "nicht", "oder", "wird", "werden", "eine", "für", "über", "zum", "zur", "Uhr")


def german_words(path):
    text = URL.sub("", path.read_text(encoding="utf-8"))
    return sorted({word for word in GERMAN if re.search(r"(?<!\w)" + re.escape(word) + r"(?!\w)", text)})


class StudioPageTest(unittest.TestCase):
    def test_page_declares_english(self):
        head = PAGE.read_text(encoding="utf-8").split("\n", 2)[:2]
        self.assertEqual(["<!doctype html>", '<html lang="en">'], head)

    def test_template_and_page_show_no_german_words(self):
        for path in (TEMPLATE, PAGE):
            self.assertEqual([], german_words(path), path.relative_to(ROOT))

    def test_page_is_the_template_built_with_its_data(self):
        page = PAGE.read_text(encoding="utf-8")
        start = page.index(DATA_START) + len(DATA_START)
        data = page[start:page.index(DATA_END, start)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "docs/prototypes").mkdir(parents=True)
            shutil.copy(ROOT / "tools/build_studio.py", root / "tools")
            shutil.copy(TEMPLATE, root / "docs/prototypes")
            (root / "studio.json").write_text(data, encoding="utf-8")
            subprocess.run([sys.executable, "tools/build_studio.py", "studio.json"], cwd=root, check=True,
                           capture_output=True)
            built = (root / "docs/prototypes/smart-topics-studio.html").read_text(encoding="utf-8")
        self.assertEqual(page, built, "rebuild docs/prototypes/smart-topics-studio.html with tools/build_studio.py")


if __name__ == "__main__":
    unittest.main()
