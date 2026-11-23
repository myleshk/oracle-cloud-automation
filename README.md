# OCI Always Free instance automation

Creates one Oracle Cloud `VM.Standard.A1.Flex` instance (2 OCPU / 12 GB, 200 GB boot volume, no
data disk) in Singapore, using the newest Ubuntu 26.04 aarch64 image. The primary deployment is a
scheduled **GitHub Actions** workflow; the same jar can run on a VPS as a fallback.

It retries capacity failures every 60 seconds and backs off exponentially when OCI returns HTTP 429
or a transient 5xx. Before every launch attempt it checks for **any** non-terminated instance in
the target compartment. If one exists — regardless of its name — it reports that instance and
exits 0 instead of creating a second one.

## Build and test

```bash
mvn test            # 28 unit tests, no OCI credentials or network calls
mvn -q package      # target/oci-instance-automation.jar
```

## GitHub Actions setup

1. Push this repository to GitHub. Scheduled workflows only run from the **default branch**.
2. In **Settings -> Secrets and variables -> Actions**, add these **secrets**:

| Secret | Value |
| --- | --- |
| `OCI_TENANCY_OCID` | tenancy OCID |
| `OCI_USER_OCID` | OCI user the API key belongs to |
| `OCI_FINGERPRINT` | API key fingerprint, e.g. `aa:bb:...` |
| `OCI_PRIVATE_KEY` | full PEM contents, including BEGIN/END lines |
| `OCI_COMPARTMENT_OCID` | compartment for the instance |
| `OCI_SUBNET_OCID` | public regional subnet |
| `SSH_PUBLIC_KEY` | one-line OpenSSH public key |

3. Optionally add these **repository variables** (Settings -> Secrets and variables -> Actions ->
   Variables):

| Variable | Default | Purpose |
| --- | --- | --- |
| `OCI_REGION` | `ap-singapore-1` | must be the account's home region |
| `INSTANCE_NAME` | `free-a1` | display name for the created instance |
| `IMAGE_VERSION` | `26.04` | Ubuntu version to discover |
| `IMAGE_OCID` | unset | pin an exact image and skip discovery |
| `BOOT_VOLUME_VPUS` | `20` | 10 Balanced, 20 Higher, 30-120 Ultra |
| `MAX_ATTEMPTS` | `3` | attempts per workflow run |

4. Open **Actions -> Create OCI Always Free instance -> Run workflow** for a first manual run.
5. The schedule then runs every 10 minutes. Each run builds the jar, writes the OCI config from
   secrets, makes up to `MAX_ATTEMPTS` launches 60 seconds apart, and reports the result.

Generate the API key in **Identity -> Users -> your user -> API keys -> Add API key**. Copy the
fingerprint, user OCID, and tenancy OCID into secrets. The private key is shown once; paste the
whole PEM into `OCI_PRIVATE_KEY`.

For click-by-click instructions covering every value, including the compartment, subnet, and SSH
key, see [docs/oci-credentials.md](docs/oci-credentials.md).

### How a run behaves

- **Any non-terminated instance already in the compartment** -> prints it and exits 0, green;
  creation is skipped even if that instance has a different name or shape. The check is limited to
  `COMPARTMENT_OCID`; an instance in another compartment is not seen and will surface later as a
  quota error.
- **Success or already exists** -> exit 0, green.
- **No capacity within the attempt budget** -> exit 3, green with a notice; the next run retries.
- **Authentication, permission, parameter, or quota error** -> exit 1, red.
- **Bad configuration** -> exit 2, red.
- The workflow's concurrency group prevents two runs from launching at the same time.
- A successful run exits immediately; later scheduled runs see the instance by name and do nothing.

### Scheduling caveats

- GitHub cron is UTC and best effort; runs can be delayed by several minutes under load.
- Scheduled workflows are disabled in public repositories after 60 days without repository
  activity. Push a commit or run the workflow manually if that happens.
- Standard runners are free for public repositories. For **private** repositories each run costs
  roughly 2-3 minutes of the monthly allowance; at every 10 minutes that exceeds the 2,000-minute
  free tier. Increase the cron interval, lower `MAX_ATTEMPTS`, or use the VPS fallback below.
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
| `ATTEMPT_INTERVAL_SECONDS` | `60` | minimum enforced |
| `MAX_BACKOFF_SECONDS` | `1800` | 429/5xx backoff cap |
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
