# Getting OCI credentials for GitHub Actions

You need seven values. Five come from the OCI Console, one comes from the API key you create
there, and one is an SSH key generated on your machine.

Sign in at <https://cloud.oracle.com>. Make sure the region selector in the top bar is Singapore
(`ap-singapore-1`); Always Free resources must live in the tenancy's home region.

## 1. Tenancy OCID (`OCI_TENANCY_OCID`)

1. Click the **Profile** menu in the top-right corner.
2. Click **Tenancy: `<your tenancy name>`**.
3. On **Tenancy Details**, under **Tenancy Information**, copy the **OCID**.

The same page shows the home region. It must be Singapore for the free A1 allowance.

## 2. User OCID (`OCI_USER_OCID`)

1. Click the **Profile** menu.
2. Click **User settings**.
3. Under **User Information**, copy the **OCID**.

If you use a dedicated automation user, an administrator can find it under
**Identity & Security -> Identity -> Users -> `<user>`**.

## 3. API private key and fingerprint (`OCI_PRIVATE_KEY`, `OCI_FINGERPRINT`)

1. **Profile** menu -> **User settings**.
2. Open **API keys** (under **Resources**; the current UI also labels this **Token and keys**).
3. Click **Add API key**.
4. Choose **Generate API key pair**, then **Download private key**.
5. Click **Add**.

The private key downloads once and cannot be retrieved later. If you lose it, delete the API key in
the Console and add a new one. It is an RSA **PEM** key for API signing, not an SSH key. Open the
file in a text editor and copy everything, including the `-----BEGIN ...-----` and
`-----END ...-----` lines, into the `OCI_PRIVATE_KEY` secret.

After the key is added, the API keys list shows its **fingerprint** (for example
`12:34:56:78:90:ab:cd:ef:12:34:56:78:90:ab:cd:ef`). Copy it into `OCI_FINGERPRINT`.

If you already have a key pair, you can paste the public key instead and compute the fingerprint:

```bash
openssl rsa -pubout -outform DER -in ~/.oci/oci_api_key.pem | openssl md5 -c
```

The Console-generated key has no passphrase, which is what the workflow expects.

## 4. Compartment OCID (`OCI_COMPARTMENT_OCID`)

1. Open the navigation menu -> **Identity & Security** -> **Identity** -> **Compartments**.
2. Select the compartment where the instance should live.
3. Copy its **OCID**.

The root compartment's OCID is the tenancy OCID, which is also valid here. For a fresh account,
using the root compartment is the simplest choice.

## 5. Subnet OCID (`OCI_SUBNET_OCID`)

1. Navigation menu -> **Networking** -> **Virtual Cloud Networks**.
2. Select the VCN.
3. Under **Resources**, click **Subnets** and select a **regional public** subnet.
4. Copy the subnet **OCID**.

The subnet needs a route to an internet gateway, and the VCN needs that gateway, otherwise the
instance will not get outbound connectivity. If you do not have a VCN, create one with
**Create Virtual Cloud Network Plus Related Resources**, which produces a public subnet, an
internet gateway, and the required route rule. To log in over SSH, the subnet's security list or
NSG must also allow ingress TCP 22.

## 6. SSH public key (`SSH_PUBLIC_KEY`)

This is your login key, separate from the OCI API signing key. If you do not have one:

```bash
ssh-keygen -t ed25519 -C "oci-free-tier"
cat ~/.ssh/id_ed25519.pub
```

Copy the single output line into `SSH_PUBLIC_KEY`. If you already have a key, use that public key.

## 7. Region (`OCI_REGION`, optional)

Set the repository variable `OCI_REGION` to `ap-singapore-1`, or leave it unset; the workflow
defaults to that value.

## Add the values to GitHub

In the repository: **Settings -> Secrets and variables -> Actions**.

Create these **repository secrets** with the exact names:

| Secret | From step |
| --- | --- |
| `OCI_TENANCY_OCID` | 1 |
| `OCI_USER_OCID` | 2 |
| `OCI_FINGERPRINT` | 3 |
| `OCI_PRIVATE_KEY` | 3 |
| `OCI_COMPARTMENT_OCID` | 4 |
| `OCI_SUBNET_OCID` | 5 |
| `SSH_PUBLIC_KEY` | 6 |

Optionally create **repository variables** for `OCI_REGION`, `INSTANCE_NAME`, `IMAGE_VERSION`,
`IMAGE_OCID`, `BOOT_VOLUME_VPUS`, and `MAX_ATTEMPTS`.

Secret values are masked in logs and cannot be read back after saving, only replaced. Do not put
any of these in a file under version control.

## Verify

Run **Actions -> Create OCI Always Free instance -> Run workflow**. A healthy log shows:

```text
Availability domains: ...
Resolved image: Canonical-Ubuntu-26.04-aarch64-...
Attempt 1 in ... failed (CAPACITY): Out of host capacity; retrying in 60 s
```

`CAPACITY` is expected and the run stays green. `NotAuthenticated` means the fingerprint or
private key is wrong; `NotAuthorizedOrNotFound` means the compartment or subnet OCID is wrong or
the user lacks permission.
