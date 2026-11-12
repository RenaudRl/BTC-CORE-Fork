#!/usr/bin/env python3
"""Wire up every upstream BTC-CORE actually borrows from.

Git remotes are local to a clone: they are not carried by the repository. Without this, updating
means opening several fork repositories by hand and diffing them in a browser, which is exactly the
work this fork keeps paying for at every Minecraft version.

Usage:
    python scripts/setup-upstreams.py            # add/update the remotes, then fetch
    python scripts/setup-upstreams.py --no-fetch # just declare them
    python scripts/setup-upstreams.py --status   # show how far behind each upstream we are
"""
import subprocess
import sys

# The branch to compare against is the one that carries our Minecraft version. `upstream/main` is
# not it: ASP develops per-version branches and main lags behind them.
UPSTREAMS = {
    "upstream": (
        "https://github.com/InfernalSuite/AdvancedSlimePaper.git",
        "dev/26.3",
        "The base this fork is cut from. Everything else is a patch source, not a base.",
    ),
    "paper": (
        "https://github.com/PaperMC/Paper.git",
        "main",
        "Upstream of ASP itself. Useful to read a hook's original context.",
    ),
    "purpur": (
        "https://github.com/PurpurMC/Purpur.git",
        "ver/26.3",
        "Source of the Purpur feature hooks (~17 injections). Same version as us.",
    ),
    "leaf": (
        "https://github.com/Winds-Studio/Leaf.git",
        "ver/26.3",
        "Patch source: multithreaded tracker, async pathfinding, AI micro-optimisations.",
    ),
    "pufferfish": (
        "https://github.com/pufferfish-gg/Pufferfish.git",
        "ver/1.21",
        "Source of the entity optimisation ports. Lags well behind: no 26.x branch exists.",
    ),
    "sparklypaper": (
        "https://github.com/SparklyPower/SparklyPaper.git",
        "ver/26.3",
        "Patch source: parallel world ticking, farm checks. No LICENSE file: port through Leaf or DivineMC.",
    ),
    "divinemc": (
        "https://github.com/BX-Team/DivineMC.git",
        "ver/26.3",
        "Patch source: parallel entity tracker, async pathfinding, raytrace entity culling.",
    ),
    "lophine": (
        "https://github.com/LophineLabs/Lophine.git",
        "ver/26.3",
        "Patch source: Folia-adapted sleeping block entities, teleport fixes.",
    ),
    "canvas": (
        "https://github.com/CraftCanvasMC/Canvas.git",
        "main",
        "Patch source: teleport and world-loading fixes. No 26.3 branch: main is the live one.",
    ),
    "gale": (
        "https://github.com/GaleMC/Gale.git",
        "dev/26.3",
        "Patch source: AI and network micro-optimisations.",
    ),
}

# Every upstream is read-only: we borrow from them, we never publish to them. A push URL that is
# not a URL makes an accidental `git push <upstream>` fail instead of reaching someone else's repo.
PUSH_DISABLED = "no-push://disabled"


def git(*args, check=True):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=check)


def existing_remotes():
    return {line.strip() for line in git("remote").stdout.splitlines() if line.strip()}


def declare():
    have = existing_remotes()
    for name, (url, _branch, why) in UPSTREAMS.items():
        if name in have:
            current = git("remote", "get-url", name).stdout.strip()
            if current != url:
                git("remote", "set-url", name, url)
                print(f"  [UPD] {name:<12} -> {url}")
            else:
                print(f"  [ OK] {name:<12} {why}")
        else:
            git("remote", "add", name, url)
            print(f"  [ADD] {name:<12} {why}")
        git("remote", "set-url", "--push", name, PUSH_DISABLED)


def fetch():
    for name, (_url, branch, _why) in UPSTREAMS.items():
        print(f"  fetching {name}/{branch}...")
        # Only the branch we follow, and no tags: ten forks' worth of branches and colliding
        # tags is noise. A fork can rename or drop a branch; that must not abort the whole run.
        fetched = git("fetch", "--no-tags", name, branch, check=False)
        if fetched.returncode != 0:
            reason = fetched.stderr.strip().splitlines()
            print(f"  [WARN] {name}/{branch}: {reason[-1] if reason else 'fetch failed'}")


def status():
    for name, (_url, branch, _why) in UPSTREAMS.items():
        ref = f"{name}/{branch}"
        probe = git("rev-parse", "--verify", "--quiet", ref, check=False)
        if probe.returncode != 0:
            print(f"  {ref:<28} absent — fetch it, or the branch was renamed upstream")
            continue
        behind = git("rev-list", "--count", f"HEAD..{ref}", check=False).stdout.strip()
        ahead = git("rev-list", "--count", f"{ref}..HEAD", check=False).stdout.strip()
        print(f"  {ref:<28} they are +{behind:<6} we are +{ahead}")


if __name__ == "__main__":
    if "--status" in sys.argv:
        print("Distance from every upstream:")
        status()
        sys.exit(0)

    print("Declaring upstream remotes:")
    declare()
    if "--no-fetch" not in sys.argv:
        print("\nFetching:")
        fetch()
        print("\nDistance from every upstream:")
        status()
    print("\nSee .docs/upstream-maintenance.md before attempting a version bump.")
