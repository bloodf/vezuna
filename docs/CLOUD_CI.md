# Cloud build policy

Product development remains on HOLD. Tooling changes and passing checks do not resume it.

## GitHub Actions

`ci.yml` runs the existing iOS network guard, source safety/consistency checks, and dependency-free deployment gate tests on pushes to `main` only. Pull requests use local validation. It also supports manual dispatch. No dependencies are installed and no application build runs in this workflow.

Website checks/build, Android tests/lint/builds, and release-tooling dependency validation live in `manual-ci.yml`. Native iOS compilation remains in `ios-native.yml`. Both workflows run only through `workflow_dispatch`; feature pushes, PRs, tags, and main pushes do not start them. Concurrency cancellation and job timeouts bound authorized manual runs. Do not dispatch them during product HOLD without explicit authorization.

Keep local lint, affected unit tests, the full merge gate described in `LOCAL_CI.md`, objective review evidence, security checks, and branch protections. Record commands actually run and any missing coverage in each PR. A skipped build is not a passing build. Manual workflow checks do not satisfy PR-required Actions checks under GitHub's current event rules. If required contexts still name the old automatic build jobs, stop publication/merge and have the central owner reconcile the policy without bypassing or fabricating checks.

## Vercel

The only checked-in web project is `website/`, with `website/vercel.json`, an Astro static build, and canonical origin `https://vezuna.app`. Root package tooling is EAS submission tooling; `shared/` is native Kotlin, not a website package. No `.vercel/project.json` exists at the repository or website root. `RELEASING.md` records an earlier staging-directory deployment to `hr-teconologia/vezuna`. That historical name does not prove the current project ID, team ID, Git connection, or configured Root Directory. Central must inventory every live project connected to `bloodf/vezuna` before publication.

`git.deploymentEnabled` disables every branch using `**` and explicitly enables `main`. Vercel documents that a matching true rule wins. `ignoreCommand` runs `node scripts/vercel-ignore.mjs` from the project root, before application dependencies are needed. Exit 0 cancels the build; exit 1 permits it. This is an ignored-build decision, not a GitHub success check.

The gate permits only a production `main` commit with no PR ID, matching checkout SHA, an available ancestor `VERCEL_GIT_PREVIOUS_SHA`, and a changed website input. Vercel defines that previous SHA as the last successful deployment of this project and branch. Tree comparisons cover merge commits, squash merges, and multiple undeployed commits. NUL-delimited paths and disabled rename detection cover deletions, renames into/out of the site, and unusual filenames.

Inputs include all of `website/`, canonical brand assets, dependency patches, root Node dependency manifests/locks/workspace/version/config files listed in the gate, the canonical publisher policy, and its/brand synchronization scripts. New shared website dependencies must extend this list and its focused test. Native-only source and unrelated docs do not trigger a build.

Missing, invalid, shallow/unavailable, or divergent history cancels the build with a recovery message. The gate never guesses `HEAD^`, silently builds everything, fetches history, installs packages, or accesses cloud APIs. First deployments also stop. Central must establish a reviewed successful baseline and reachable history through an explicitly authorized recovery; no bootstrap deployment occurs automatically. A conservative skip can delay publication and requires attention. Git controls and the gate do not prove a commit had PR review; main integration rules remain authoritative.

## Central live-setting prerequisites

Before pushing this patch, central must contain existing live triggers and queued/in-progress runs. Repository changes cannot retroactively stop old branch configurations. For every linked Vercel project, confirm `bloodf/vezuna`, Root Directory `website`, production branch `main`, access to system environment variables, the ignored-build command, and availability of root/shared inputs outside the website directory. Confirm that custom environments, deploy hooks, direct CLI/API deployments, and existing feature branches cannot bypass the intended policy. These settings and credentials are outside this change.

Read-only GitHub inspection at preparation time returned `Branch not protected` for classic main protection and `[]` for effective branch rules. No protection or live setting was changed. Central must independently confirm the current state and preserve all applicable review/security requirements; this patch does not establish enforcement by itself.

## Focused local verification

```sh
node --test website/scripts/vercel-ignore.test.mjs
bash scripts/test-check-ios-network.sh
bash scripts/check-ios-network.sh
```

Run existing affected local checks as documented in `LOCAL_CI.md`. No install, full build, hosted run, deployment, or merge is needed to test the deployment gate.

## Official references

- Vercel Git configuration: https://vercel.com/docs/project-configuration/git-configuration
- Vercel `ignoreCommand`: https://vercel.com/docs/project-configuration/vercel-json#ignorecommand
- Vercel system variables: https://vercel.com/docs/environment-variables/system-environment-variables
- GitHub manual workflows: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- GitHub required status checks: https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/collaborating-on-repositories-with-code-quality-features/troubleshooting-required-status-checks
