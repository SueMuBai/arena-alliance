Original prompt: Implement the approved code-review and Arena Hero map-operation UI optimization plan, including unit-adjacent actions, Tick countdown, and move/attack feedback.

- Implemented protocol-safe per-Key serialized command submission; only upstream HTTP 202 commits local state.
- Implemented persistent 32x32 exploration coverage with an 820/1024 completion threshold.
- Implemented unit-adjacent action panel, same-cell picker, top Tick countdown, spawn layout, and move/attack effects.
- Added int64/JavaScript-safe coordinate handling, combat geometry, capacity, dynamic pricing, and Beacon shield-cap checks.
- Fixed the multi-account first-state barrier race and the manual-command E2E queue assertion.
- Added boundary tests for exploration, persistence retry, UUIDs, coordinate overflow, full Core cells, Beacon shields, stale plans, fingerprints, and serialized manual overrides.

- Browser fixture verified at 1440x900: action cards anchor beside the selected object; move targets/path are cyan; shoot targets/beam are red; Core production is a separate three-column group; the Tick bar counts down at the top.

- Final verification: 126 Maven tests passed with 0 failures/errors/skips; JavaScript syntax checks, `git diff --check`, and browser console/request checks passed.
- Browser verification used Windows Chrome over CDP because the WSL Chromium bundle lacks system libraries and sudo is unavailable.

TODO: none for this implementation pass. Changes remain uncommitted for user review.
