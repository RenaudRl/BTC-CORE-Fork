#!/usr/bin/env python3
"""BTC-CORE: verifie l'etat des hooks sans rien ecrire.

Rejoue les definitions de apply-btccore-patches.py en mode simulation et classe chaque hook :

  APPLIED  le hook est present dans l'overlay decompile
  PENDING  l'ancre matche, le hook n'est pas encore applique (apply le poserait)
  DEAD     l'ancre ne matche plus ET le hook n'est pas present : no-op silencieux
  NOFILE   le fichier cible n'existe pas dans l'overlay

DEAD est le cas dangereux : le build reste vert, l'option reste dans btccore.yml, la commande
debug dit toujours "enabled", et le hook n'existe nulle part.

Le script source reste la seule source de verite : rien n'est duplique ici, les definitions de
patch sont lues depuis apply-btccore-patches.py et executees avec patch() et open() neutralises.

Usage :
    python scripts/verify-btccore-patches.py              # tableau sur stdout
    python scripts/verify-btccore-patches.py --markdown   # + rapport .docs/hooks-inventory.md

Sortie non nulle si au moins un hook est DEAD ou NOFILE.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SOURCE = os.path.join(HERE, "apply-btccore-patches.py")

records = []


class _NullWriter:
    """Absorbe les ecritures de la section 0 (build.gradle.kts) sans toucher au disque."""

    def __init__(self, path):
        self.path = path

    def write(self, _data):
        records.append({
            "line": 0,
            "file": self.path,
            "status": "BUILD",
            "detail": "modification build.gradle.kts (non verifiee)",
        })

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False

    def close(self):
        pass


def _dry_open(path, mode="r", *args, **kwargs):
    if "w" in mode or "a" in mode or "+" in mode:
        return _NullWriter(path)
    return open(path, mode, *args, **kwargs)


def build_dry_patch(injection_marker):
    """Reproduit exactement la logique de decision de patch(), sans ecrire."""

    def dry_patch(filepath, old, new):
        line = sys._getframe(1).f_lineno
        if not os.path.exists(filepath):
            records.append({"line": line, "file": filepath, "status": "NOFILE", "detail": ""})
            return False

        with open(filepath, "r", encoding="utf-8") as fh:
            content = fh.read()

        if new in content:
            records.append({"line": line, "file": filepath, "status": "APPLIED", "detail": "bloc complet"})
            return False

        marker = injection_marker(old, new)
        if marker and marker in content:
            records.append({"line": line, "file": filepath, "status": "APPLIED", "detail": "marqueur"})
            return False

        if old not in content:
            records.append({
                "line": line,
                "file": filepath,
                "status": "DEAD",
                "detail": old.strip().splitlines()[0][:70] if old.strip() else "",
            })
            return False

        records.append({"line": line, "file": filepath, "status": "PENDING", "detail": "ancre trouvee"})
        return False

    return dry_patch


def labels_by_line(source_lines):
    """Associe a chaque ligne le dernier commentaire numerote `# N. Titre` qui la precede."""
    labels = {}
    current = "(sans titre)"
    # `\.\s+` est ce qui distingue un titre de section ("17. Hopper throttle") d'un numero de
    # version en tete de commentaire ("26.2 turned EntityGetter into..."), qui n'a pas d'espace
    # apres le point et se ferait sinon passer pour un titre.
    heading = re.compile(r"^#\s*(\d{1,2}[a-z]?\.\s+\S.*)$")
    for idx, raw in enumerate(source_lines, start=1):
        match = heading.match(raw.strip())
        if match:
            current = match.group(1).strip()
        labels[idx] = current
    return labels


def main():
    os.chdir(ROOT)

    with open(SOURCE, "r", encoding="utf-8") as fh:
        source = fh.read()

    # Neutralise la definition d'origine pour que la notre survive a l'exec.
    source = source.replace("def patch(filepath, old, new):", "def _orig_patch(filepath, old, new):", 1)

    namespace = {
        "__name__": "__btccore_verify__",
        "os": os,
        "sys": sys,
        "open": _dry_open,
        "print": lambda *_a, **_k: None,
    }

    # Le corps commence a la premiere instruction executable ; tout ce qui precede est definitions.
    body_start = source.index('print("=== BTC-CORE')
    header, body = source[:body_start], source[body_start:]

    # 1) Definitions seules (injection_marker, constantes, _orig_patch neutralise).
    exec(compile(header, SOURCE, "exec"), namespace)  # noqa: S102 - source du depot, pas d'entree externe

    # 2) patch() simule, puis le corps. Le padding garde les numeros de ligne alignes sur le
    #    fichier d'origine, ce qui est ce qui rattache chaque appel a son titre de section.
    namespace["patch"] = build_dry_patch(namespace["injection_marker"])
    padding = "\n" * header.count("\n")
    try:
        exec(compile(padding + body, SOURCE, "exec"), namespace)  # noqa: S102
    except SystemExit:
        pass

    labels = labels_by_line(source.splitlines())
    for record in records:
        record["label"] = labels.get(record["line"], "(section 0 / build)")

    hooks = [r for r in records if r["status"] != "BUILD"]
    counts = {}
    for record in hooks:
        counts[record["status"]] = counts.get(record["status"], 0) + 1

    width = max((len(r["label"]) for r in hooks), default=10)
    print("=== BTC-CORE : inventaire des hooks ===\n")
    for record in hooks:
        target = record["file"].split("/")[-1]
        print(f"  {record['status']:<8} {record['label']:<{width}}  {target}")
        if record["status"] == "DEAD":
            print(f"           ancre perdue : {record['detail']}")

    total = len(hooks)
    print(f"\n  Total {total} hooks — " + ", ".join(f"{k}: {v}" for k, v in sorted(counts.items())))

    if "--markdown" in sys.argv:
        write_markdown(hooks, counts, total)

    broken = counts.get("DEAD", 0) + counts.get("NOFILE", 0)
    if broken:
        print(f"\n  {broken} hook(s) sans effet — voir DEAD/NOFILE ci-dessus.")
        return 1
    return 0


def write_markdown(hooks, counts, total):
    out = os.path.join(ROOT, ".docs", "hooks-inventory.md")
    lines = [
        "# Inventaire des hooks BTC-CORE",
        "",
        "> Genere par `scripts/verify-btccore-patches.py --markdown`. Ne pas editer a la main.",
        "",
        f"**{total} hooks** — " + ", ".join(f"{k} : {v}" for k, v in sorted(counts.items())),
        "",
        "| Statut | Hook | Fichier cible | Detail |",
        "|---|---|---|---|",
    ]
    for record in hooks:
        detail = record["detail"].replace("|", "\\|")
        lines.append(f"| `{record['status']}` | {record['label']} | `{record['file']}` | {detail} |")
    lines.append("")

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"\n  Rapport ecrit : .docs/hooks-inventory.md")


if __name__ == "__main__":
    sys.exit(main())
