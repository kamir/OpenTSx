#!/usr/bin/env python3
"""Draws the example charts of the operator chapter (docs/user-manual/17-operators.md).

Usage: python3 tools/operator_charts.py <examples-dir>   - needs matplotlib (pip install -e "python-package[viz]")

The example tests write one CSV file per operator into the directory named by OPENTSX_EXAMPLES_DIR. This script turns
every <id>.csv there into docs/user-manual/img/operators/<id>.png. File format, UTF-8:

    #title=<chart title>
    #panel=<n>;title=<panel title>;x=<x label>;y=<y label>;log=<empty, x, y or xy>
    #note=<n>;<text written into panel n>
    panel,series,mark,x,y
    <n>,<series name>,<line|point|step|bar>,<x>,<y>

Panels are numbered from 1 and drawn side by side, at most 3. A series keeps its color in every panel. A chart has
at most 4 series names; one with points has at most 3, so that every pair of colors stays apart for color-blind
readers.
"""
import csv
import pathlib
import sys

PALETTE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
MARKS = {"line", "point", "step", "bar"}


def parse(path: pathlib.Path) -> dict:
    title, panels, notes, rows = None, {}, {}, []
    lines = path.read_text(encoding="utf-8").splitlines()
    body = []
    for line in lines:
        if line.startswith("#title="):
            title = line[len("#title="):]
        elif line.startswith("#panel="):
            fields = dict(part.split("=", 1) for part in line[1:].split(";"))
            panels[int(fields["panel"])] = fields
        elif line.startswith("#note="):
            number, text = line[len("#note="):].split(";", 1)
            notes.setdefault(int(number), []).append(text)
        elif line.startswith("#"):
            raise ValueError(f"{path}: unknown directive {line!r}")
        else:
            body.append(line)
    reader = csv.DictReader(body)
    if reader.fieldnames != ["panel", "series", "mark", "x", "y"]:
        raise ValueError(f"{path}: header must be panel,series,mark,x,y, found {reader.fieldnames}")
    for r in reader:
        if r["mark"] not in MARKS:
            raise ValueError(f"{path}: mark {r['mark']!r} is not one of {sorted(MARKS)}")
        rows.append((int(r["panel"]), r["series"], r["mark"], float(r["x"]), float(r["y"])))
    if not title:
        raise ValueError(f"{path}: #title= is missing")
    used = sorted({r[0] for r in rows})
    if not used or used != list(range(1, len(used) + 1)) or len(used) > 3:
        raise ValueError(f"{path}: panels must be numbered 1..n with n <= 3, found {used}")
    missing = [p for p in used if p not in panels]
    if missing:
        raise ValueError(f"{path}: #panel= is missing for panels {missing}")
    series = list(dict.fromkeys(r[1] for r in rows))
    limit = 3 if any(r[2] == "point" for r in rows) else 4
    if len(series) > limit:
        raise ValueError(f"{path}: {len(series)} series, at most {limit}: {series}")
    return {"title": title, "panels": panels, "notes": notes, "rows": rows, "series": series}


def draw(chart: dict, out: pathlib.Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FuncFormatter, LogLocator, NullFormatter

    plt.rcParams.update({
        "font.family": "sans-serif",
        "font.sans-serif": ["DejaVu Sans"],
        "font.size": 9,
    })
    color = {name: PALETTE[i] for i, name in enumerate(chart["series"])}
    n = len(chart["panels"])
    fig, axes = plt.subplots(1, n, figsize=(4.2 * n, 3.3), squeeze=False, facecolor=SURFACE)
    for index, ax in enumerate(axes[0], start=1):
        spec = chart["panels"][index]
        ax.set_facecolor(SURFACE)
        rows = [r for r in chart["rows"] if r[0] == index]
        names = list(dict.fromkeys(r[1] for r in rows))
        bars = [nm for nm in names if any(r[2] == "bar" for r in rows if r[1] == nm)]
        for name in names:
            pts = [r for r in rows if r[1] == name]
            mark = pts[0][2]
            xs = [r[3] for r in pts]
            ys = [r[4] for r in pts]
            c = color[name]
            if mark == "line":
                ax.plot(xs, ys, color=c, linewidth=1.0, label=name, solid_capstyle="round")
            elif mark == "step":
                ax.step(xs, ys, where="post", color=c, linewidth=1.0, label=name)
            elif mark == "point":
                ax.plot(xs, ys, linestyle="none", marker="o", markersize=4.2, color=c,
                        markeredgecolor=SURFACE, markeredgewidth=0.6, label=name)
            else:
                width = (min(b - a for a, b in zip(xs, xs[1:])) if len(xs) > 1 else 1.0) / max(1, len(bars))
                shift = (bars.index(name) - (len(bars) - 1) / 2) * width
                ax.bar([x + shift for x in xs], ys, width=width * 0.9, color=c, label=name,
                       edgecolor=SURFACE, linewidth=0.6)
        log = spec.get("log", "")
        for axis, flag in ((ax.xaxis, "x"), (ax.yaxis, "y")):
            if flag in log:
                (ax.set_xscale if flag == "x" else ax.set_yscale)("log")
                axis.set_major_locator(LogLocator(base=10, subs=(1.0, 2.0, 5.0)))
                axis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
                axis.set_minor_formatter(NullFormatter())
        ax.set_title(spec.get("title", ""), color=INK, fontsize=9.5, loc="left")
        ax.set_xlabel(spec.get("x", ""), color=INK_2)
        ax.set_ylabel(spec.get("y", ""), color=INK_2)
        ax.grid(True, color=GRID, linewidth=0.5)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(AXIS)
        ax.tick_params(which="both", colors=MUTED, labelcolor=INK_2)
        for k, text in enumerate(chart["notes"].get(index, [])):
            ax.text(0.02, 0.97 - 0.075 * k, text, transform=ax.transAxes, va="top", ha="left",
                    fontsize=8, color=INK_2)
    fig.suptitle(chart["title"], x=0.01, y=0.98, ha="left", color=INK, fontsize=11)
    if len(chart["series"]) >= 2:
        handles = {}
        for ax in axes[0]:
            for h, label in zip(*ax.get_legend_handles_labels()):
                handles.setdefault(label, h)
        fig.legend([handles[s] for s in chart["series"]], chart["series"], loc="upper right",
                   bbox_to_anchor=(0.995, 0.995), ncol=len(chart["series"]), frameon=False, fontsize=8,
                   labelcolor=INK_2)
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(out, dpi=150, facecolor=SURFACE, metadata={"Software": None})
    plt.close(fig)


def main(src: pathlib.Path) -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    target = root / "docs/user-manual/img/operators"
    target.mkdir(parents=True, exist_ok=True)
    files = sorted(src.glob("*.csv"))
    if not files:
        print(f"no example data in {src}; run the example tests with OPENTSX_EXAMPLES_DIR={src}")
        return 1
    for f in files:
        out = target / (f.stem + ".png")
        draw(parse(f), out)
        print(f"wrote {out.relative_to(root)}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1])))
