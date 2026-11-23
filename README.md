# OCI Always Free instance automation

Creates one `VM.Standard.A1.Flex` instance (2 OCPU / 12 GB) in Singapore with a 200 GB boot
volume, no data disk, and the newest Ubuntu 26.04 aarch64 image. It runs on a VPS, retries
capacity failures every 60 seconds, and backs off exponentially when OCI returns HTTP 429 or a
transient 5xx.

The program is idempotent: if an instance with `INSTANCE_NAME` already exists (or the service is
restarted mid-run), it reports the existing instance and exits 0 instead of creating a duplicate.
A successful launch also exits 0, so systemd does not restart it.

## Requirements

- VPS with Java 17+ (the jar is self-contained)
- OCI API key for an account whose **home region is Singapore** (Always Free is home-region only)
- A public subnet and a compartment in `ap-singapore-1`
- Maven 3.9+ and JDK 17+ only on the machine that builds the jar

## Build and test

```bash
mvn test            # 25 unit tests, no OCI credentials needed
mvn -q package      # target/oci-instance-automation.jar
```

## Install on the VPS

```bash
# from the repository root on the VPS
mvn -q package
sudo deploy/install.sh
```

Then follow the printed instructions: edit `/etc/oci-instance-automation/env`, add the OCI config
and API private key, and fix ownership. Validate and start:

```bash
set -a; . /etc/oci-instance-automation/env; set +a
java -jar /opt/oci-instance-automation/oci-instance-automation.jar --check

sudo systemctl enable --now oci-instance-automation
journalctl -u oci-instance-automation -f
```

`deploy/install.sh --start` starts it immediately and refuses if `REPLACE_ME` placeholders remain.

## Where to get the OCIDs

- **Compartment**: Identity & Security -> Compartments. The tenancy root OCID also works.
- **Subnet**: Networking -> Virtual Cloud Networks -> your VCN -> Subnets. Use a regional public
  subnet. The subnet's route table needs an internet gateway for outbound access.
- **Image**: leave `IMAGE_OCID` unset; the program lists platform images for `Canonical Ubuntu`,
  shape `VM.Standard.A1.Flex`, picks the newest aarch64 build matching `IMAGE_VERSION`, and prefers
  the full image over the Minimal variant. If nothing matches, it prints the available images.
- **Availability domain**: leave `AVAILABILITY_DOMAIN` unset to rotate over every AD in Singapore on
  successive attempts.

## Behaviour

- Sizing above 2 OCPU / 12 GB / 200 GB boot is rejected at startup, so it cannot silently leave the
  Always Free allowance.
- The tool assumes the account's 200 GB block-storage allowance is otherwise unused. It does not
  enumerate existing boot or data volumes; check for leftovers before the first run.
- `BOOT_VOLUME_VPUS=20` is the Higher Performance tier (the fastest normal tier); `10` is Balanced
  and is the default for free boot volumes. Ultra High (`30`-`120`) is accepted but may be billed.
  Oracle's Always Free page covers 200 GB of storage; VPU performance is metered separately. Check
  the console's cost estimate if you need a guaranteed-zero bill, and drop to `10` if unsure.
- Capacity errors (`Out of host capacity`) wait a fixed 60 seconds and rotate to the next AD.
- HTTP 429 and 5xx double the delay (60, 120, 240 ... capped by `MAX_BACKOFF_SECONDS`) with
  +/-20% jitter. The SDK's own retry layer is disabled so this is the only backoff.
- Authentication, authorization, bad parameters, and quota/limit errors stop the run instead of
  backing off. The systemd unit restarts after 60 seconds, so a persistent misconfiguration
  repeats in the journal until it is fixed.
- `--once` performs a single attempt. `--check` validates configuration without any API call.
- Exit codes: `0` success or already exists, `1` error, `2` bad configuration.

## Configuration reference

All values are environment variables; see `deploy/env.example` for the full annotated list.

| Variable | Default | Notes |
| --- | --- | --- |
| `COMPARTMENT_OCID` | required | target compartment |
| `SUBNET_OCID` | required | public regional subnet |
| `SSH_PUBLIC_KEY_FILE` | required | injected as `ssh_authorized_keys` |
| `OCI_CONFIG_FILE` | `~/.oci/config` | under systemd set to `/etc/oci-instance-automation/config` |
| `OCI_REGION` | `ap-singapore-1` | must be the home region |
| `INSTANCE_NAME` | `free-a1` | idempotency key |
| `OCPUS` / `MEMORY_GB` | `2` / `12` | hard-capped at free tier |
| `BOOT_VOLUME_GB` | `200` | hard-capped at free tier |
| `BOOT_VOLUME_VPUS` | `20` | 10 Balanced, 20 Higher, 30-120 Ultra |
| `IMAGE_VERSION` | `26.04` | set `IMAGE_OCID` to pin instead |
| `ATTEMPT_INTERVAL_SECONDS` | `60` | minimum enforced |
| `MAX_BACKOFF_SECONDS` | `1800` | cap for exponential backoff |
| `MAX_ATTEMPTS` | `0` | 0 means unlimited |

## Troubleshooting

- `Out of host capacity`: normal; leave it running. Different ADs are tried in rotation.
- `LimitExceeded` / service limits: another A1 instance or boot volume is already consuming the
  account's allowance. Terminate it or free the boot volume, then restart the service.
- `NotAuthenticated` / `NotAuthorizedOrNotFound`: wrong profile, key, compartment, or subnet OCID;
  the service account must be able to read the tenancy and manage instances in the compartment.
- No image found: the code prints the available images for that OS and shape. Set `IMAGE_OCID` or
  change `IMAGE_VERSION`.
- `--check` fails on the OCI config path: the systemd unit runs as `oci-automation` with
  `ProtectHome=true`, so configurations under `/home` are not readable. Keep them in
  `/etc/oci-instance-automation`.
