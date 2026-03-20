#!/usr/bin/env python3
"""Generate a decomp.dev-style treemap progress image from a verify JSON report."""

import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import squarify


# ── Color scheme: clear gradient from gray → red → blue → green → gold ──

COLOR_TIERS = [
    # (min_pct, color,     label)
    (100,      "#FFD700",  "100% matched"),   # gold
    (95,       "#2ecc40",  "95-100%"),         # bright green
    (90,       "#1a8f1a",  "90-95%"),          # medium green
    (75,       "#0e6b2c",  "75-90%"),          # dark green
    (50,       "#1a6b6b",  "50-75%"),          # teal
    (25,       "#0c3795",  "25-50%"),          # dark blue
    (0,        "#0d1b3e",  "0-25%"),            # deep navy
    (-1,       "#353535",  "Not started"),     # gray (sentinel, never matched by pct)
]

COLOR_NOT_COMPILED = "#D35400"  # burnt orange for "decompiled but not compiled"


def get_color(unit, source_dir=None):
    """Color based on match percentage using tiered gradient."""
    status = unit.get("status", "")

    # Class exists in original but not in recompiled output
    if status == "MISSING_RECOMP":
        if source_dir:
            name = unit.get("name", "")
            # Inner classes share the source file of their outer class
            source_name = name.split("$")[0]
            source_file = Path(source_dir) / (source_name + ".java")
            if source_file.exists():
                return COLOR_NOT_COMPILED
        return "#353535"  # no source, genuinely not started

    pct = unit.get("matched_code_percent", 0)
    if pct is None:
        pct = 0

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

    measures = report["measures"]
    units = report["units"]

    # Prepare data for treemap
    sizes = []
    labels = []
    colors = []

    for unit in units:
        total_insns = int(unit.get("total_instructions", 0) or 0)
        if total_insns == 0:
            continue
        name = unit["name"]
        # Use short class name for display, strip package prefix
        short_name = name.rsplit("/", 1)[-1] if "/" in name else name
        sizes.append(total_insns)
        labels.append(short_name)
        colors.append(get_color(unit, source_dir))

    if not sizes:
        print("No units with instructions found!")
        return

    # Create figure
    fig, ax = plt.subplots(1, 1, figsize=(20, 12))
    fig.patch.set_facecolor("#1a1a2e")
    ax.set_facecolor("#1a1a2e")

    # Normalize sizes for squarify
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
        # Only label large enough rectangles
        if dx > 2 and dy > 1.5:
            fontsize = min(8, max(4, min(dx, dy) * 0.8))
            # Use black text on gold/bright colors, white on dark
            text_color = "#1a1a2e" if color in ("#FFD700", "#2ecc40") else "white"
            ax.text(
                x + dx / 2, y + dy / 2,
                label,
                ha="center", va="center",
                color=text_color,
                fontsize=fontsize,
                fontweight="bold",
                alpha=0.85,
            )

    ax.set_xlim(0, 100)
    ax.set_ylim(0, 60)
    ax.set_aspect("equal")
    ax.axis("off")

    # Title with stats
    matched_code_pct = measures.get("matched_code_percent", 0)
    matched_funcs = int(measures.get("matched_methods", 0))
    total_funcs = int(measures.get("total_methods", 0))
    total_insns = int(measures.get("total_instructions", 0))
    matched_insns = int(measures.get("matched_instructions", 0))

    if custom_title:
        header = custom_title
    else:
        header = "Decompilation Progress"

    # Count decompiled classes when source_dir is available
    decompiled_info = ""
    if source_dir:
        decompiled_count = 0
        total_count = 0
        for unit in units:
            total_count += 1
            status = unit.get("status", "")
            if status != "MISSING_RECOMP":
                decompiled_count += 1
            else:
                name = unit.get("name", "")
                source_name = name.split("$")[0]
                source_file = Path(source_dir) / (source_name + ".java")
                if source_file.exists():
                    decompiled_count += 1
        if total_count > 0 and decompiled_count < total_count:
            decompiled_pct = 100.0 * decompiled_count / total_count
            decompiled_info = f"  |  {decompiled_pct:.1f}% decompiled"

    title = (
        f"{header}\n"
        f"{matched_code_pct:.2f}% matched  |  "
        f"{matched_funcs:,}/{total_funcs:,} methods  |  "
        f"{matched_insns:,}/{total_insns:,} instructions"
        f"{decompiled_info}"
    )
    ax.set_title(title, color="white", fontsize=16, fontweight="bold", pad=20)

    # Legend - always show all tiers for consistency across images
    legend_items = []
    for _, color, label in COLOR_TIERS:
        if color == "#353535":
            label = "Not started"  # restore display label for sentinel tier
        ec = "#1a1a2e" if color == "#FFD700" else "white"
        legend_items.append(mpatches.Patch(facecolor=color, edgecolor=ec, label=label))
    legend_items.insert(-1, mpatches.Patch(
        facecolor=COLOR_NOT_COMPILED, edgecolor="white",
        label="Decompiled, not compiled"))

    ax.legend(
        handles=legend_items,
        loc="lower right",
        fontsize=9,
        facecolor="#1a1a2e",
        edgecolor="#444",
        labelcolor="white",
        framealpha=0.9,
    )

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight", facecolor=fig.get_facecolor())
    plt.close()
    print(f"  Code: {matched_code_pct:.2f}% matched ({matched_insns:,} / {total_insns:,} instructions)")
    print(f"  Methods: {matched_funcs:,} / {total_funcs:,}")


if __name__ == "__main__":
    main()
