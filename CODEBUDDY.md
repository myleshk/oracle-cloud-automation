# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## What this repository is

A scheduled automation that creates one Oracle Cloud Always Free `VM.Standard.A1.Flex` instance
(2 OCPU / 12 GB, 200 GB boot volume, no data disk) in the Singapore region using the newest Ubuntu
26.04 aarch64 platform image. It retries against capacity shortages for as long as necessary.

The primary deployment is two GitHub Actions workflows:
`.github/workflows/build.yml` tests and packages the jar on source changes and publishes it to the
`automation-latest` release, while `.github/workflows/create-instance.yml` runs every 10 minutes,
downloads that prebuilt jar, materializes OCI credentials from repository secrets, and runs the
retry loop. `deploy/` holds an optional systemd/VPS fallback for the same jar.

The repository started as a 2023 snapshot of Oracle's Java SDK `CreateInstanceExample`
(commit `8f94b9a`); that single-file sample has been replaced. Its history remains useful for the
original SDK calls, but none of its hardcoded OCIDs or the boot-volume-cloning flow are in use.

## Build, test, run

```bash
mvn -q test                        # 28 unit tests; no OCI credentials or network calls
mvn -q -Dtest=RetryPolicyTest test # single test class
mvn -q package                     # shaded, runnable target/oci-instance-automation.jar (~19 MB)
java -jar target/oci-instance-automation.jar --help
```

`--check` validates configuration without calling OCI, `--once` makes a single attempt, and no
arguments runs the retry loop. Exit codes: `0` instance running or already exists, `1` fatal error,
`2` invalid configuration, `3` no capacity within the attempt budget. The workflow treats `3` as a
green "try again next run" and fails only on `1`/`2`.

## Architecture

Package `hk.myles.oci.automation`:

- `Main` — parses flags, loads `AppConfig`, builds the config-file auth provider and three clients
  (`ComputeClient`, `VirtualNetworkClient`, `IdentityClient`) pinned to the configured region,
  resolves ADs and the image once, then runs the attempt loop. Each attempt first calls
  `findAnyInstance`, then rotates to the next AD and launches. The SDK's own retries are disabled
  (`RetryConfiguration.NO_RETRY_CONFIGURATION`) so the delay policy is authoritative.
- `AppConfig` — parses all environment variables, applies defaults, and rejects any configuration
  that leaves the Always Free envelope (>2 OCPU, >12 GB, >200 GB boot), any attempt interval below
  60 s, invalid VPU values, malformed OCIDs, or a missing SSH public key. Also validates the region
  id against `Region.fromRegionId`.
- `ImageResolver` — paginates `listImages` in the **tenancy root** (platform images do not live in
  child compartments), filtered by `operatingSystem` and shape, then selects the newest AVAILABLE
  aarch64 build whose OS version starts with `IMAGE_VERSION`, preferring the full image over the
  `Minimal` variant. `IMAGE_OCID` bypasses discovery entirely.
- `InstanceLauncher` — `buildDetails` is a pure function that sets `shapeConfig` (OCPUs/memory),
  `InstanceSourceViaImageDetails` (boot size and VPUs/GB), the VNIC (subnet, public IP, optional
  NSGs), and the SSH key metadata. `launch` calls `launchInstance`, then polls `getInstance` every
  5 s for up to 20 minutes until RUNNING. `printAddresses` polls VNIC attachments for up to
  2 minutes to report the public/private IP.
- `RetryPolicy` — classifies `BmcException`: "out of host capacity" is `CAPACITY` (fixed 60 s wait),
  HTTP 429 is `RATE_LIMIT`, 5xx/timeouts are `TRANSIENT` (both double the delay up to the cap with
  +/-20% jitter), and 4xx plus quota/limit messages are `FATAL`. Unknown runtime exceptions abort.
  A 429 is checked before quota strings because "rate limit exceeded" also contains "limit
  exceeded".

`.github/workflows/build.yml` runs on pushes to `main` that touch `src/`, `pom.xml`, or itself (and
on `workflow_dispatch`). It runs `mvn -q package`, then creates or overwrites
`oci-instance-automation.jar` on the `automation-latest` prerelease. It needs `contents: write`.

`.github/workflows/create-instance.yml` runs every 10 minutes and on `workflow_dispatch`. It
downloads the release asset with `gh release download` (no checkout, no Maven), writes
`~/.oci/config` and the private key from secrets, exports `OCI_CONFIG_FILE` and
`SSH_PUBLIC_KEY_FILE`, and runs up to `MAX_ATTEMPTS` (default 3) attempts 60 s apart. A concurrency
group prevents overlapping launches. Repository variables tune region, name, image version/OCID,
VPUs, and attempts.

`deploy/` contains the optional systemd unit (`Restart=on-failure`, `StartLimitIntervalSec=0`,
`ProtectHome=true`), `install.sh`, and `env.example`.

## Invariants to preserve

- **Do not add a build step to the scheduled workflow.** It must download `automation-latest`;
  building there wastes runner minutes on every attempt. Source changes belong in `build.yml`.
- **No credentials in the repository.** The workflow expects `OCI_TENANCY_OCID`, `OCI_USER_OCID`,
  `OCI_FINGERPRINT`, `OCI_PRIVATE_KEY`, `OCI_COMPARTMENT_OCID`, `OCI_SUBNET_OCID`, and
  `SSH_PUBLIC_KEY` as Actions secrets. Never add keys, OCIDs, or a real config file to the tree.
- **Free-tier caps are enforced, not advisory.** Do not raise the limits in `AppConfig` without an
  explicit request; a larger instance is no longer free.
- **Never poll faster than every 60 seconds.** `ATTEMPT_INTERVAL_SECONDS` is validated to a minimum
  of 60 to avoid rate limits.
- **Idempotency is compartment-wide, not name-based.** `findAnyInstance` lists every instance in
  the target compartment and treats anything not TERMINATED as already done, even with a different
  display name or shape. `INSTANCE_NAME` is only the display name of the created instance. Keep
  this check before every launch, and keep the workflow concurrency group in place.
- **Exit code 3 is meaningful.** It means "retryable failure, no instance" and keeps scheduled runs
  green; do not collapse it into 1.
- **Do not add per-attempt API calls** beyond the existing `listInstances` and `launchInstance`;
  this runs indefinitely at one attempt per minute per active run.
- **Region is set per client**, not taken from the OCI config file. `OCI_REGION` wins.
- **Platform-image lookups use the tenancy OCID**; instance operations use `COMPARTMENT_OCID`.
- Unit tests must stay credential-free: retry classification/backoff, config validation, image
  selection, and launch-detail construction are all pure and testable.
