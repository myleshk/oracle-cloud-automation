# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## What this repository is

A scheduled automation that creates one Oracle Cloud Always Free `VM.Standard.A1.Flex` instance
(2 OCPU / 12 GB, 200 GB boot volume, no data disk) in the Singapore region using the newest Ubuntu
26.04 aarch64 platform image. It is designed to run as a systemd service on a VPS and retry for as
long as it takes to obtain capacity.

The repository started as a 2023 snapshot of Oracle's Java SDK `CreateInstanceExample`
(commit `8f94b9a`); that single-file sample has been replaced. Its history remains useful for the
original SDK calls, but none of its hardcoded OCIDs or the boot-volume-cloning flow are in use.

## Build, test, run

```bash
mvn -q test                        # 25 unit tests; no OCI credentials or network calls
mvn -q -Dtest=RetryPolicyTest test # single test class
mvn -q package                     # shaded, runnable target/oci-instance-automation.jar (~19 MB)
java -jar target/oci-instance-automation.jar --help
```

`--check` validates configuration without calling OCI, `--once` makes a single launch attempt, and
no arguments runs the retry loop. Exit codes: `0` instance running or already exists, `1` error,
`2` invalid configuration.

Running the real automation requires an OCI API config and `COMPARTMENT_OCID`, `SUBNET_OCID`, and
`SSH_PUBLIC_KEY_FILE`. Configuration is entirely environment-variable driven; see
`deploy/env.example` and `README.md`. There is no test that exercises the live OCI API.

## Architecture

Package `hk.myles.oci.automation`:

- `Main` — parses flags, loads `AppConfig`, builds the config-file auth provider and three clients
  (`ComputeClient`, `VirtualNetworkClient`, `IdentityClient`) pinned to the configured region,
  resolves ADs and the image once, then runs the attempt loop. Each attempt first calls
  `findExisting`, then rotates to the next AD and launches.
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
  The SDK's own retry layer is disabled (`RetryConfiguration.NO_RETRY_CONFIGURATION`) so delays are
  not doubled by the client.

`deploy/` contains the systemd unit (`Restart=on-failure`, `StartLimitIntervalSec=0`, sandboxed
with `ProtectHome=true`), `install.sh` (creates the `oci-automation` system user and installs jar,
unit, and env template), and `env.example`.

## Invariants to preserve

- **Free-tier caps are enforced, not advisory.** Do not raise the limits in `AppConfig` without an
  explicit request; a larger instance is no longer free.
- **Never poll faster than every 60 seconds.** `ATTEMPT_INTERVAL_SECONDS` is validated to a minimum
  of 60 to avoid rate limits.
- **Idempotency depends on `INSTANCE_NAME`.** `findExisting` lists instances by exact display name
  and treats anything not TERMINATING/TERMINATED as already done. Keep that check before every
  launch.
- **Do not add per-attempt API calls** beyond the existing `listInstances` and `launchInstance`;
  this runs indefinitely at one attempt per minute.
- **Region is set per client**, not taken from the OCI config file. `OCI_REGION` wins.
- **Platform-image lookups use the tenancy OCID**; instance operations use `COMPARTMENT_OCID`.
- Unit tests must stay credential-free: retry classification/backoff, config validation, image
  selection, and launch-detail construction are all pure and testable.
