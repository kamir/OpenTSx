"""Chart data of an operator example, written as <id>.csv into OPENTSX_EXAMPLES_DIR when that is set.

tools/operator_charts.py draws docs/user-manual/img/operators/<id>.png from it; the file format is described there.
"""
import os
import pathlib


class ExampleChart:
    def __init__(self, op_id: str, title: str):
        self.op_id = op_id
        self.head = [f"#title={_text(title)}"]
        self.rows = []
        self.panels = 0

    def panel(self, title: str, x: str, y: str, log: str = "") -> "ExampleChart":
        if log not in ("", "x", "y", "xy"):
            raise ValueError(f"log must be '', 'x', 'y' or 'xy', not {log!r}")
        self.panels += 1
        self.head.append(f"#panel={self.panels};title={_text(title)};x={_text(x)};y={_text(y)};log={log}")
        return self

    def note(self, text: str) -> "ExampleChart":
        self.head.append(f"#note={self.panels};{_text(text)}")
        return self

    def add(self, series: str, mark: str, x, y) -> "ExampleChart":
        if self.panels == 0:
            raise ValueError("add a panel first")
        if "," in series:
            raise ValueError(f"a series name holds no comma: {series!r}")
        xs, ys = list(x), list(y)
        if len(xs) != len(ys):
            raise ValueError(f"{series}: {len(xs)} x values but {len(ys)} y values")
        self.rows.extend(f"{self.panels},{series},{mark},{float(a)!r},{float(b)!r}" for a, b in zip(xs, ys))
        return self

    def write(self) -> None:
        target = os.environ.get("OPENTSX_EXAMPLES_DIR")
        if not target:
            return
        text = "\n".join(self.head + ["panel,series,mark,x,y"] + self.rows) + "\n"
        pathlib.Path(target, f"{self.op_id}.csv").write_text(text, encoding="utf-8")


def _text(s: str) -> str:
    if any(c in s for c in ";\n"):
        raise ValueError(f"chart text holds no ';' or line break: {s!r}")
    return s
