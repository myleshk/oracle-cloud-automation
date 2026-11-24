# OCI Always Free instance automation

[![Create OCI Always Free instance](https://github.com/myleshk/oracle-cloud-automation/actions/workflows/create-oci-instance.yml/badge.svg)](https://github.com/myleshk/oracle-cloud-automation/actions/workflows/create-oci-instance.yml)

<!-- STATUS:START -->
### Latest run

| Field | Value |
| --- | --- |
| Time | 2026-11-24 12:04:23 UTC ([run #51](https://github.com/myleshk/oracle-cloud-automation/actions/runs/45389314712)) |
| Result | No instance yet (capacity or rate limit); the next run retries |
| Launch attempts | 3 |
| Last failure | Attempt 3 in cUpg:AP-SINGAPORE-1-AD-1 failed (CAPACITY): Error returned by LaunchInstance operation in Compute service.(500, InternalError, false) Out of host capacity. (opc-request-id: 446C9805DE81474498F443D4BBA78390/A7A74ACD57053794467D056B53494E4B/7D2FB2EFE4DC0DB85A14248F96FE7AF9) Timestamp: 202 |
| Trigger | workflow_dispatch |
<!-- STATUS:END -->

Creates one Oracle Cloud `VM.Standard.A1.Flex` instance (2 OCPU / 12 GB, 200 GB boot volume, no
data disk) in Singapore, using the newest Ubuntu 26.04 aarch64 image. The primary deployment is a
scheduled **GitHub Actions** workflow; the same jar can run on a VPS as a fallback.

It retries capacity failures every 120 seconds in the workflow, and stops the run as soon as OCI
returns HTTP 429 so the next trigger makes the next attempt; transient 5xx errors back off
exponentially. Before every launch attempt it checks for **any** non-terminated instance in
the target compartment. If one exists — regardless of its name — it reports that instance and
exits 0 instead of creating a second one.

## Build and test

```bash
mvn test            # 28 unit tests, no OCI credentials or network calls
mvn -q package      # target/oci-instance-automation.jar
```

## GitHub Actions setup

1. Push this repository to GitHub. Scheduled workflows only run from the **default branch**.
2. The **Build automation jar** workflow runs on pushes that touch `src/`, `pom.xml`, or the
   build workflow. It runs the tests and publishes the jar to the `automation-latest` release.
   If the release step fails, enable **Settings -> Actions -> General -> Workflow permissions ->
   Read and write permissions**.
3. In **Settings -> Secrets and variables -> Actions**, add these **secrets**:

| Secret | Value |
| --- | --- |
| `OCI_TENANCY_OCID` | tenancy OCID |
| `OCI_USER_OCID` | OCI user the API key belongs to |
| `OCI_FINGERPRINT` | API key fingerprint, e.g. `aa:bb:...` |
| `OCI_PRIVATE_KEY` | full PEM contents, including BEGIN/END lines |
| `OCI_COMPARTMENT_OCID` | compartment for the instance |
| `OCI_SUBNET_OCID` | public regional subnet |
| `SSH_PUBLIC_KEY` | one-line OpenSSH public key |

4. Optionally add these **repository variables** (Settings -> Secrets and variables -> Actions ->
   Variables):

| Variable | Default | Purpose |
| --- | --- | --- |
| `OCI_REGION` | `ap-singapore-1` | must be the account's home region |
| `INSTANCE_NAME` | `free-a1` | display name for the created instance |
| `IMAGE_VERSION` | `26.04` | Ubuntu version to discover |
| `IMAGE_OCID` | unset | pin an exact image and skip discovery |
| `BOOT_VOLUME_VPUS` | `20` | 10 Balanced, 20 Higher, 30-120 Ultra |
| `MAX_ATTEMPTS` | `3` | attempts per workflow run |

5. Wait for **Actions -> Build automation jar** to finish, or run it manually. It must publish the
   `automation-latest` release before the scheduled workflow can start.
6. Open **Actions -> Create OCI Always Free instance -> Run workflow** for a first manual run.
7. The schedule then runs every 10 minutes. Each run downloads the prebuilt jar, writes the OCI
   config from secrets, makes up to `MAX_ATTEMPTS` launches 120 seconds apart, and reports the
   result. It does not compile anything.

Generate the API key in **Identity -> Users -> your user -> API keys -> Add API key**. Copy the
fingerprint, user OCID, and tenancy OCID into secrets. The private key is shown once; paste the
whole PEM into `OCI_PRIVATE_KEY`.

For click-by-click instructions covering every value, including the compartment, subnet, and SSH
key, see [docs/oci-credentials.md](docs/oci-credentials.md).

### How the jar gets built

`build.yml` runs the tests and `mvn package` once per source change (push to `main` touching
`src/`, `pom.xml`, or the build workflow), then overwrites `oci-instance-automation.jar` on the
`automation-latest` prerelease. `create-oci-instance.yml` downloads that asset with `gh release
download`; there is no Maven or compile step in the scheduled job.

To rebuild manually, run **Actions -> Build automation jar -> Run workflow**.

### How a run behaves

- **Any non-terminated instance already in the compartment** -> prints it and exits 0, green;
  creation is skipped even if that instance has a different name or shape. The check is limited to
  `COMPARTMENT_OCID`; an instance in another compartment is not seen and will surface later as a
  quota error.
- **Success or already exists** -> exit 0, green.
- **No capacity within the attempt budget** -> exit 3, green with a notice; the next run retries.
- **HTTP 429 from OCI** -> exit 3 at once, green with a notice; the run does not retry internally,
  so the tenant limit gets a full 10-minute window before the next trigger.
- **Authentication, permission, parameter, or quota error** -> exit 1, red.
- **Bad configuration** -> exit 2, red.
- The workflow's concurrency group prevents two runs from launching at the same time.
- A successful run exits immediately; later scheduled runs see any instance in the compartment and do nothing.

### Scheduling caveats

GitHub's `on.schedule` has not been reliable for this repository since November 2026: the workflow
lists as active, `workflow_dispatch` starts immediately, and no `schedule` run is ever created.
`cloudflare-worker/` therefore owns the clock and calls `workflow_dispatch` every 10 minutes. The
`schedule` block stays in the workflow as a fallback, and the workflow's concurrency group keeps
the two paths from racing.

- Deploy the worker: `cd cloudflare-worker`, then `npx wrangler secret put GITHUB_TOKEN` with a
  fine-grained PAT scoped to this repository (Actions: read and write), then `npx wrangler deploy`.
  Add the optional `TRIGGER_KEY` secret if you want the `POST /dispatch` endpoint guarded. If
  `wrangler login` fails with `request_forbidden` (a browser cookie problem), export
  `CLOUDFLARE_API_TOKEN` from a token made with the *Edit Cloudflare Workers* template instead and
  wrangler will use it without the OAuth flow.
- Trigger a run by hand: `scripts/dispatch.sh`. It reads the same PAT from `$GITHUB_TOKEN`, or from
  `~/.config/github-tokens/myleshk-oracle-cloud-automation`, and prints the workflow run URL.
- GitHub cron is UTC and best effort; runs can be delayed by several minutes under load.
- Scheduled workflows are disabled in public repositories after 60 days without repository
  activity. Push a commit or run the workflow manually if that happens.
- Standard runners are free for public repositories, so a 10-minute cadence costs nothing now that
  this repository is public. A **private** repository pays roughly 1-2 minutes of the monthly
  allowance per run, which exceeds the 2,000-minute free tier at this cadence.
- Use a dedicated OCI user with permission to manage instances in the target compartment, rather
  than a tenancy administrator.

## Configuration reference

The workflow supplies these; the same variables work when running the jar directly.

| Variable | Default | Notes |
| --- | --- | --- |
| `COMPARTMENT_OCID` | required | target compartment |
| `SUBNET_OCID` | required | public regional subnet |
| `SSH_PUBLIC_KEY_FILE` | required | file containing the public key |
| `OCI_CONFIG_FILE` | `~/.oci/config` | written by the workflow |
| `OCI_PROFILE` | `DEFAULT` | profile in the config file |
| `OCI_REGION` | `ap-singapore-1` | must be the home region |
| `INSTANCE_NAME` | `free-a1` | display name for the created instance |
| `SHAPE` | `VM.Standard.A1.Flex` | |
| `OCPUS` / `MEMORY_GB` | `2` / `12` | hard-capped at Always Free |
| `BOOT_VOLUME_GB` | `200` | hard-capped at Always Free |
| `BOOT_VOLUME_VPUS` | `20` | see the billing note below |
| `IMAGE_OS` | `Canonical Ubuntu` | |
| `IMAGE_VERSION` | `26.04` | |
| `IMAGE_OCID` | unset | pin instead of discovering |
| `AVAILABILITY_DOMAIN` | unset | default rotates over all ADs |
| `ASSIGN_PUBLIC_IP` | `true` | |
| `NSG_IDS` | unset | comma-separated NSG OCIDs |
| `ATTEMPT_INTERVAL_SECONDS` | `60` | jar default; the workflow sets `120` |
| `MAX_BACKOFF_SECONDS` | `1800` | transient 5xx backoff cap |
| `MAX_ATTEMPTS` | `0` | program default; the workflow uses 3 |

`BOOT_VOLUME_VPUS=20` is Higher Performance, the fastest normal tier; `10` is Balanced and is the
default for free boot volumes. Ultra High (`30`-`120`) may be billed. Oracle's Always Free page
covers 200 GB of storage, but VPU performance is metered separately. Check the console cost
estimate if you need a guaranteed-zero bill, and drop to `10` if unsure.

The tool assumes the account's 200 GB block-storage allowance is otherwise unused. It does not
enumerate existing boot or data volumes; check for leftovers before the first run.

## Optional: VPS fallback

If Actions minutes are a problem, the same jar can run continuously on a VPS:

```bash
mvn -q package
sudo deploy/install.sh
```

Then edit `/etc/oci-instance-automation/env`, add the OCI config and API key as described by the
installer, validate with `--check`, and `systemctl enable --now oci-instance-automation`. See
`deploy/env.example` and the comments printed by `deploy/install.sh`.

## Troubleshooting

- `Out of host capacity`: normal; the workflow stays green and the next run retries.
- `LimitExceeded` / service limits: another A1 instance or boot volume is consuming the allowance.
  Terminate it or free the boot volume, then run the workflow again.
- `NotAuthenticated`: check `OCI_USER_OCID`, `OCI_FINGERPRINT`, and that `OCI_PRIVATE_KEY` is the
  complete PEM with real newlines. Re-download the key if necessary.
- `NotAuthorizedOrNotFound`: wrong compartment or subnet OCID, or the user lacks permission.
- No image found: the log lists available images for that OS and shape. Set the `IMAGE_VERSION`
  variable or pin `IMAGE_OCID`.
