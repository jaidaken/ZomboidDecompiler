#!/usr/bin/env python3
"""Generate a treemap progress image showing bytecode match quality per class."""

import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import matplotlib.patches as mpatches
import numpy as np
import squarify


# Yellow (0 non-exact) -> bright green (1) -> dark blue (many non-exact)
GRADIENT_YELLOW = np.array([1.0, 1.0, 0.0])         # #FFFF00
GRADIENT_GREEN = np.array([0.180, 0.800, 0.251])     # bright green
GRADIENT_DARK_BLUE = np.array([0.051, 0.106, 0.243]) # #0d1b3e

# Max non-exact count that maps to the darkest color; anything above is clamped
GRADIENT_MAX = 20

COLOR_NOT_STARTED = "#353535"
COLOR_NOT_COMPILED = "#D35400"


def _gradient_color(non_exact: int) -> str:
    """Map non-exact method count to a yellow -> green -> dark blue gradient."""
    if non_exact == 0:
        return mcolors.to_hex(GRADIENT_YELLOW)
    if non_exact == 1:
        return mcolors.to_hex(GRADIENT_GREEN)
    # 2..GRADIENT_MAX: green -> dark blue
    t = min(non_exact - 1, GRADIENT_MAX - 1) / (GRADIENT_MAX - 1)
    rgb = GRADIENT_GREEN * (1 - t) + GRADIENT_DARK_BLUE * t
    return mcolors.to_hex(rgb)


def get_color(unit: dict, source_dir: str | None = None) -> str:
    status = unit.get("status", "")
    if status == "MISSING_RECOMP":
        if source_dir:
            name = unit.get("name", "")
            source_file = Path(source_dir) / (name.split("$")[0] + ".java")
            if not source_file.exists():
                return COLOR_NOT_STARTED
        return COLOR_NOT_COMPILED
    non_exact = len(unit.get("methods", []))
    return _gradient_color(non_exact)


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

    # Calculate stats
    total_methods = 0
    exact_methods = 0
    tier_counts = {}
    missing_classes = 0
    total_classes = 0
    for unit in units:
        total_classes += 1
        tm = unit.get("total_methods", 0)
        total_methods += tm
        methods = unit.get("methods", [])
        exact_methods += tm - len(methods)
        for m in methods:
            tier = m.get("matchTier", "NONE")
            tier_counts[tier] = tier_counts.get(tier, 0) + 1
        if unit.get("status") == "MISSING_RECOMP":
            missing_classes += 1

    exact_pct = 100.0 * exact_methods / total_methods if total_methods > 0 else 0
    structural = tier_counts.get("STRUCTURAL", 0) + tier_counts.get("STRUCTURAL_NO_TRYCATCH", 0)
    reordered = tier_counts.get("SORTED_MULTISET", 0)
    divergent = tier_counts.get("FUZZY_COMPUTATION", 0) + tier_counts.get("CORE_OPS_ONLY", 0)
    unmatched = tier_counts.get("NONE", 0)

    # Treemap data
    sizes, labels, colors = [], [], []
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

    # Layout
    fig, ax = plt.subplots(1, 1, figsize=(20, 12))
    fig.patch.set_facecolor("#1a1a2e")
    ax.set_facecolor("#1a1a2e")

    normed = squarify.normalize_sizes(sizes, 100, 60)
    rects = squarify.squarify(normed, 0, 0, 100, 60)

    for rect, label, color, size in zip(rects, labels, colors, sizes):
        x, y, dx, dy = rect["x"], rect["y"], rect["dx"], rect["dy"]
        ax.add_patch(plt.Rectangle(
            (x, y), dx, dy,
            facecolor=color, edgecolor="#0d0d1a", linewidth=0.5,
        ))
        if dx >= 1.2 and dy >= 0.8:
            # Use dark text on bright backgrounds based on luminance
            rgb = mcolors.to_rgb(color)
            luminance = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]
            text_color = "#1a1a2e" if luminance > 0.45 else "white"
            if dy > dx * 1.5:
                fontsize = min(8, max(3.5, dx * 0.9))
                rotation = 90
            else:
                fontsize = min(8, max(3.5, min(dx, dy) * 0.8))
                rotation = 0
            ax.text(
                x + dx / 2, y + dy / 2, label,
                ha="center", va="center", color=text_color,
                fontsize=fontsize, fontweight="bold", alpha=0.85,
                rotation=rotation,
            )

    ax.set_xlim(0, 100)
    ax.set_ylim(0, 60)
    ax.set_aspect("equal")
    ax.axis("off")

    # Header
    header = custom_title or "Decompilation Progress"

    # Build tier summary line
    parts = []
    if structural > 0:
        parts.append(f"{structural:,} structural")
    if reordered > 0:
        parts.append(f"{reordered:,} reordered")
    if divergent > 0:
        parts.append(f"{divergent:,} divergent")
    if unmatched > 0:
        parts.append(f"{unmatched:,} unmatched")

    non_exact = total_methods - exact_methods
    line2 = f"{exact_methods:,} / {total_methods:,} byte-exact ({exact_pct:.1f}%)"
    if non_exact > 0:
        line2 += f"\n{non_exact:,} non-exact: {', '.join(parts)}"

    # Status line: compile errors and missing classes
    status_parts = []
    if missing_classes > 0:
        status_parts.append(f"{missing_classes:,} classes failed to compile")
    if unmatched > 0:
        status_parts.append(f"{unmatched:,} unmatched methods")

    # Try to read compile error count from nearby error log
    try:
        error_log = Path(report_path).parent.parent / "Recompiled-game" / "compile-errors.log"
        if not error_log.exists():
            # Try build dir pattern
            rp = Path(report_path)
            for parent in [rp.parent.parent.parent]:
                candidates = list(parent.glob("*/Recompiled-game/compile-errors.log"))
                if candidates:
                    error_log = candidates[0]
                    break
        if error_log.exists():
            error_count = sum(1 for line in open(error_log) if "error:" in line)
            if error_count > 0:
                status_parts.append(f"{error_count:,} compile errors")
    except Exception:
        pass

    if not status_parts:
        status_parts.append("0 compile errors")

    line3 = "   |   ".join(status_parts)
    title_text = f"{header}\n{line2}\n{line3}"

    ax.set_title(title_text, color="white", fontsize=14, fontweight="bold", pad=20)

    # Legend: gradient bar + special status patches
    # Gradient colorbar
    gradient_ax = fig.add_axes([0.72, 0.08, 0.22, 0.025])  # [left, bottom, width, height]
    gradient_data = np.linspace(0, GRADIENT_MAX, 256).reshape(1, -1)
    gradient_colors = [_gradient_color(int(v)) for v in np.linspace(0, GRADIENT_MAX, 256)]
    cmap = mcolors.ListedColormap(gradient_colors)
    gradient_ax.imshow(gradient_data, aspect="auto", cmap=cmap)
    gradient_ax.set_xticks([0, 128, 255])
    gradient_ax.set_xticklabels(["0", str(GRADIENT_MAX // 2), f"{GRADIENT_MAX}+"],
                                 color="white", fontsize=8)
    gradient_ax.set_yticks([])
    gradient_ax.set_xlabel("non-exact methods", color="white", fontsize=8, labelpad=2)
    gradient_ax.tick_params(axis="x", colors="white", length=0)
    for spine in gradient_ax.spines.values():
        spine.set_edgecolor("#444")

    # Special status patches
    special_items = [
        mpatches.Patch(facecolor=COLOR_NOT_COMPILED, edgecolor="white", label="Compile error"),
        mpatches.Patch(facecolor=COLOR_NOT_STARTED, edgecolor="white", label="Not started"),
    ]
    ax.legend(
        handles=special_items, loc="lower left", fontsize=10,
        facecolor="#1a1a2e", edgecolor="#444", labelcolor="white", framealpha=0.9,
    )

    plt.savefig(output_path, dpi=150, bbox_inches="tight", facecolor=fig.get_facecolor())
    plt.close()


if __name__ == "__main__":
    main()
