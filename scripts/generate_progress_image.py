#!/usr/bin/env python3
"""Generate a treemap progress image showing bytecode match quality per class."""

import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import squarify


# Color by percentage of methods that are EXACT matches
COLOR_TIERS = [
    # (min_pct, color,     label)
    (100,      "#FFD700",  "100% exact"),
    (95,       "#2ecc40",  "95-99%"),
    (90,       "#1a8f1a",  "90-95%"),
    (75,       "#0e6b2c",  "75-90%"),
    (50,       "#1a6b6b",  "50-75%"),
    (25,       "#0c3795",  "25-50%"),
    (0,        "#0d1b3e",  "< 25%"),
    (-1,       "#353535",  "Not started"),
]

COLOR_NOT_COMPILED = "#D35400"


def get_exact_pct(unit):
    total = unit.get("total_methods", 0)
    if total == 0:
        return 0
    non_exact = len(unit.get("methods", []))
    exact = total - non_exact
    return 100.0 * exact / total


def get_color(unit, source_dir=None):
    status = unit.get("status", "")
    if status == "MISSING_RECOMP":
        if source_dir:
            name = unit.get("name", "")
            source_name = name.split("$")[0]
            source_file = Path(source_dir) / (source_name + ".java")
            if not source_file.exists():
                return "#353535"
        return COLOR_NOT_COMPILED
    pct = get_exact_pct(unit)
    for min_pct, color, _ in COLOR_TIERS:
        if pct >= min_pct:
            return color
    return "#353535"


def main():
    if len(sys.argv) < 2:
        print("Usage: generate_progress_image.py <report.json> [output.png] [title] [source_dir]")
        sys.exit(1)

    report_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else "progress.png"
    custom_title = sys.argv[3] if len(sys.argv) > 3 else None
    source_dir = sys.argv[4] if len(sys.argv) > 4 else None

    with open(report_path) as f:
        report = json.load(f)

    units = report["units"]

    # Calculate global stats
    total_methods = 0
    exact_methods = 0
    tier_counts = {}
    for unit in units:
        tm = unit.get("total_methods", 0)
        total_methods += tm
        methods = unit.get("methods", [])
        exact_methods += tm - len(methods)
        for m in methods:
            tier = m.get("matchTier", "NONE")
            tier_counts[tier] = tier_counts.get(tier, 0) + 1

    none_count = tier_counts.get("NONE", 0)
    exact_pct = 100.0 * exact_methods / total_methods if total_methods > 0 else 0

    # Prepare treemap data
    sizes = []
    labels = []
    colors = []

    for unit in units:
        total_insns = int(unit.get("total_instructions", 0) or 0)
        if total_insns == 0:
            continue
        name = unit["name"]
        short_name = name.rsplit("/", 1)[-1] if "/" in name else name
        sizes.append(total_insns)
        labels.append(short_name)
        colors.append(get_color(unit, source_dir))

    if not sizes:
        print("No units with instructions found!")
        return

    fig, ax = plt.subplots(1, 1, figsize=(20, 12))
    fig.patch.set_facecolor("#1a1a2e")
    ax.set_facecolor("#1a1a2e")

    normed = squarify.normalize_sizes(sizes, 100, 60)
    rects = squarify.squarify(normed, 0, 0, 100, 60)

    for rect, label, color, size in zip(rects, labels, colors, sizes):
        x, y, dx, dy = rect["x"], rect["y"], rect["dx"], rect["dy"]
        ax.add_patch(plt.Rectangle(
            (x, y), dx, dy,
            facecolor=color,
            edgecolor="#0d0d1a",
            linewidth=0.5,
        ))
        if dx >= 1.2 and dy >= 0.8:
            text_color = "#1a1a2e" if color in ("#FFD700", "#2ecc40") else "white"
            if dy > dx * 1.5:
                fontsize = min(8, max(3.5, dx * 0.9))
                rotation = 90
            else:
                fontsize = min(8, max(3.5, min(dx, dy) * 0.8))
                rotation = 0
            ax.text(
                x + dx / 2, y + dy / 2,
                label,
                ha="center", va="center",
                color=text_color,
                fontsize=fontsize,
                fontweight="bold",
                alpha=0.85,
                rotation=rotation,
            )

    ax.set_xlim(0, 100)
    ax.set_ylim(0, 60)
    ax.set_aspect("equal")
    ax.axis("off")

    # Title
    header = custom_title or "Decompilation Progress"
    subtitle = f"{exact_pct:.1f}% byte-exact ({exact_methods:,} / {total_methods:,} methods)"
    if none_count > 0:
        subtitle += f"  |  {none_count:,} unmatched"

    ax.set_title(
        f"{header}\n{subtitle}",
        color="white", fontsize=16, fontweight="bold", pad=20
    )

    # Legend
    legend_items = []
    for _, color, label in COLOR_TIERS:
        ec = "#1a1a2e" if color == "#FFD700" else "white"
        legend_items.append(mpatches.Patch(facecolor=color, edgecolor=ec, label=label))
    legend_items.insert(-1, mpatches.Patch(
        facecolor=COLOR_NOT_COMPILED, edgecolor="white",
        label="Compile error"))

    ax.legend(
        handles=legend_items,
        loc="lower right",
        fontsize=10,
        facecolor="#1a1a2e",
        edgecolor="#444",
        labelcolor="white",
        framealpha=0.9,
    )

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight", facecolor=fig.get_facecolor())
    plt.close()


if __name__ == "__main__":
    main()
