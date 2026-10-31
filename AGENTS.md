# Repository workflow

- Use `master` as the integration and release base. `main` is a retained legacy branch, not a development base.
- Before starting new work, inspect the working tree and fetch origin. Preserve existing uncommitted work; never reset or overwrite it.
- Start each new feature from the latest `origin/master` on `feature/<short-name>`. Use `fix/<short-name>` for fixes and `chore/<short-name>` for maintenance.
- Continue related work on its existing branch. Do not create a new branch for every follow-up message.
- Never develop or commit directly on master. Make focused, descriptive commits, push the working branch, and open a PR targeting master.
- Run checks appropriate to the change; require the GitHub build checks to pass before merging. Do not bypass failures or force-push shared branches.
- A request to develop or upload does not alone authorize merging a PR or publishing a release. Follow explicit user authorization for those actions.
- Keep signing keys, credentials, recordings, exports, device evidence and generated artifacts out of Git.
- Wear OS source is in apps/watch/wearos; tooling is in tools; phone and desktop clients have reserved directories under apps.
