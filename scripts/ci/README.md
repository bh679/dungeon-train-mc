# Local CI pre-flight

`run-local.sh` runs the four jobs of `.github/workflows/build.yml` on your machine so a branch
is already green before you push, instead of discovering a red step after a long GitHub run.

```bash
scripts/ci/run-local.sh                 # everything: Gradle build + the three Python jobs
scripts/ci/run-local.sh --skip-build    # Python jobs only, about a minute
scripts/ci/run-local.sh --only net-checks
scripts/ci/run-local.sh --no-fetch      # skip refreshing origin/main before net-checks
```

| CI job | Locally |
|---|---|
| `build` | `./gradlew build` in the background (with the daemon; CI uses `--no-daemon`) |
| `modpack-checks` | the same `scripts/modpack`, `lost-city`, `advancements`, `difficulty`, `credits`, `assets` scripts. `check-mod-ids.py --verify` downloads the pinned jars once into `~/.cache/dungeontrain-modpack-jars`, the path CI caches |
| `net-checks` | `check-protocol-version.py --base origin/main` (CI uses the PR's base branch) |
| `localization-checks` | parity, duplicates, validate-locale, provenance, placeholders and the test files |

Each step writes its output to `build/local-ci/<job>/<nn>-<slug>.log`; the terminal shows one
PASS/FAIL line per step and a list of failures at the end. Steps keep going after a failure so
one run reports everything red. Exit code is 0 only when every step passed.

## Keeping it in sync

The step list is hand-copied from `build.yml`. A drift guard at the end of the script compares
the set of `scripts/...py` paths named in the workflow with the set named in the script and
fails the run when they differ. When you add a step to `build.yml`, add it here too.
`LOCAL_CI_WORKFLOW=<path>` points the guard at another workflow file (used to test the guard).

## What it does not do

A local pass is strong evidence, not proof: CI runs on Linux, cold, with `--no-daemon`. The
GitHub run on the pull request is still required before a Gate 3 merge.
