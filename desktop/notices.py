"""Writes the licence texts of every crate linked into the Windows hub, for the release zip.

Usage: python notices.py <output file>. Run from the windows folder after a build, so the registry holds every crate.
"""

import json
import pathlib
import subprocess
import sys

LICENCE_PREFIXES = ("license", "licence", "copying", "notice", "copyright")
# Slint is offered under three licences; the hub takes the royalty-free one, whose terms are credited in Settings.
SLINT_TERMS = "Slint Royalty-free Desktop, Mobile, and Web Applications License 2.0, https://slint.dev/terms-and-conditions#royalty-free"


def linked_crates():
    tree = subprocess.run(
        ["cargo", "tree", "-p", "manifold-hub", "-e", "normal", "--target", "x86_64-pc-windows-msvc", "--prefix", "none", "--format", "{p}"],
        check=True, capture_output=True, text=True,
    ).stdout
    found = set()
    for line in tree.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            found.add((parts[0], parts[1].lstrip("v")))
    return found


def main(output):
    metadata = json.loads(subprocess.run(["cargo", "metadata", "--format-version", "1"], check=True, capture_output=True, text=True).stdout)
    packages = {(package["name"], package["version"]): package for package in metadata["packages"]}
    by_text = {}
    without_files = []
    for name, version in sorted(linked_crates()):
        package = packages.get((name, version))
        # The workspace's own crates are Manifold, under the licence in the repository.
        if package is None or package["source"] is None:
            continue
        crate = f"{name} {version}"
        licence = SLINT_TERMS if "slint" in name else (package.get("license") or "see the source")
        folder = pathlib.Path(package["manifest_path"]).parent
        files = sorted(path for path in folder.iterdir() if path.is_file() and path.name.lower().startswith(LICENCE_PREFIXES))
        if not files:
            without_files.append(f"  {crate}: {licence} ({package.get('repository') or 'no repository listed'})")
        for path in files:
            text = path.read_text(encoding="utf-8", errors="replace").strip()
            by_text.setdefault(text, []).append(crate)
    rule = "\n\n" + "=" * 80 + "\n"
    sections = [f"Used by: {', '.join(crates)}\n\n{text}" for text, crates in sorted(by_text.items(), key=lambda item: item[1][0])]
    preamble = (
        "Licences of the open source software linked into the Manifold hub for Windows.\n"
        "Spout's licence is in Spout2-LICENSE.txt next to this file.\n\n"
        "These crates publish no licence file; the licence they declare applies:\n" + "\n".join(without_files)
    )
    pathlib.Path(output).write_text(preamble + rule + rule.join(sections) + "\n", encoding="utf-8")
    print(f"{len(by_text)} distinct texts, {len(without_files)} crates without a file")


if __name__ == "__main__":
    main(sys.argv[1])
