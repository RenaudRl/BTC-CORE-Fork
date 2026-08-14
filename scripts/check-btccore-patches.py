#!/usr/bin/env python3
"""BTC-CORE: validate the feature patches and regenerate the hook inventory.

Replaces verify-btccore-patches.py, which asked "is every string substitution still applied?" — a
question that no longer exists now that the hooks are Paperweight feature patches applied by
`git am`. A patch that no longer fits makes `git am` fail; there is no silent no-op left to hunt.

What *is* still worth checking is that the patches are well formed. A hunk header whose line counts
disagree with its body is accepted by every editor and rejected by git only at apply time, with
`corrupt patch at .git/rebase-apply/patch:N` pointing at a line number in a temporary file. That
exact defect — two comment lines hand-inserted into 0003-Branding.patch without updating `+40,8` —
kept CI red for weeks. This script finds it in milliseconds, before the build.

Usage:
    python scripts/check-btccore-patches.py             # validate, print a summary
    python scripts/check-btccore-patches.py --markdown  # also write .docs/hooks-inventory.md
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Every feature-patch directory in the repo, ASP-authored ones included: a corrupt patch upstream
# breaks our build exactly like a corrupt patch of ours.
PATCH_DIRS = [
    "aspaper-server/minecraft-patches/features",
    "aspaper-server/paper-patches/features",
    "aspaper-api/paper-patches/features",
]

# The hooks carry `// BTCCore - <option>` (or `// BTCCore start - <option>`) so a reader can go from
# a line of patched vanilla back to the btccore.yml key that governs it.
MARKER = re.compile(r"//\s*BTCCore(?:\s+(?:start|end))?\s*-\s*(.+?)\s*$")
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")

INVENTORY = ".docs/hooks-inventory.md"


class PatchError(Exception):
    pass


def parse_patch(path):
    """Return [(target_file, [hunk, ...])], raising PatchError on a malformed hunk.

    A hunk is (header_line_no, old_count, new_count, [body lines]). Counts are checked against the
    body, which is the whole point of this script.
    """
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    files, current, hunks = [], None, None
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("+++ b/"):
            current = line[6:]
            hunks = []
            files.append((current, hunks))
        elif line.startswith("@@"):
            match = HUNK.match(line)
            if not match:
                raise PatchError(f"{path}:{i + 1}: unreadable hunk header: {line}")
            old_want = int(match.group(2)) if match.group(2) is not None else 1
            new_want = int(match.group(4)) if match.group(4) is not None else 1
            body, old_seen, new_seen = [], 0, 0
            i += 1
            while i < len(lines) and (old_seen < old_want or new_seen < new_want):
                body_line = lines[i]
                if body_line.startswith("+"):
                    new_seen += 1
                elif body_line.startswith("-"):
                    old_seen += 1
                elif body_line.startswith("\\"):
                    pass  # "\ No newline at end of file" counts for neither side
                elif body_line.startswith(" ") or body_line == "":
                    old_seen += 1
                    new_seen += 1
                else:
                    raise PatchError(
                        f"{path}:{i + 1}: hunk claims -{old_want}/+{new_want} but ends early at "
                        f"-{old_seen}/+{new_seen}; next line is not part of a hunk: {body_line[:60]}"
                    )
                body.append(body_line)
                i += 1
            if old_seen != old_want or new_seen != new_want:
                raise PatchError(
                    f"{path}: hunk at line {i + 1 - len(body)} declares -{old_want}/+{new_want} "
                    f"but its body is -{old_seen}/+{new_seen}"
                )
            if hunks is None:
                raise PatchError(f"{path}: hunk before any '+++ b/' header")
            hunks.append((i - len(body), old_want, new_want, body))
            continue
        i += 1
    return files


def hook_labels(body):
    """BTCCore option names introduced by this hunk, in order, deduplicated."""
    labels = []
    for line in body:
        if not line.startswith("+"):
            continue
        match = MARKER.search(line)
        if match and match.group(1) not in labels:
            labels.append(match.group(1))
    return labels


def main():
    os.chdir(ROOT)
    write_markdown = "--markdown" in sys.argv

    errors, parsed = [], []
    for directory in PATCH_DIRS:
        if not os.path.isdir(directory):
            errors.append(f"{directory}: missing patch directory")
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".patch"):
                continue
            path = f"{directory}/{name}"
            try:
                parsed.append((path, parse_patch(path)))
            except PatchError as exc:
                errors.append(str(exc))

    btc = [(path, files) for path, files in parsed if "BTC-CORE-hooks" in path]
    total_hunks = sum(len(hunks) for _, files in btc for _, hunks in files)
    total_files = sum(len(files) for _, files in btc)

    print(f"=== BTC-CORE patch check — {len(parsed)} patches, {len(errors)} malformed ===")
    for error in errors:
        print(f"  [FAIL] {error}")
    print(f"  BTC hooks: {total_hunks} hunks over {total_files} files, in {len(btc)} patches")

    if write_markdown:
        os.makedirs(os.path.dirname(INVENTORY), exist_ok=True)
        with open(INVENTORY, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("# Inventaire des hooks BTC-CORE\n\n")
            fh.write("> Genere par `scripts/check-btccore-patches.py --markdown`. "
                     "Ne pas editer a la main.\n\n")
            fh.write(f"**{total_hunks} hooks** repartis sur {total_files} fichiers.\n\n")
            fh.write("Les hooks sont des patches Paperweight appliques par `git am`. "
                     "Un hook qui ne s'applique plus fait echouer le build ; "
                     "il n'y a plus de no-op silencieux.\n\n")
            for path, files in btc:
                fh.write(f"## `{path}`\n\n")
                fh.write("| Fichier | Hunks | Options btccore.yml |\n|---|---|---|\n")
                for target, hunks in files:
                    labels = []
                    for _, _, _, body in hunks:
                        for label in hook_labels(body):
                            if label not in labels:
                                labels.append(label)
                    fh.write(f"| `{target}` | {len(hunks)} | {', '.join(labels) or '—'} |\n")
                fh.write("\n")
        print(f"  Rapport ecrit : {INVENTORY}")

    if errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
