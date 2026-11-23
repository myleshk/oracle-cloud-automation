#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR=/opt/oci-instance-automation
CONFIG_DIR=/etc/oci-instance-automation
SERVICE_NAME=oci-instance-automation.service
SERVICE_USER=oci-automation
JAR_NAME=oci-instance-automation.jar

START_NOW=false
if [[ "${1:-}" == "--start" ]]; then
    START_NOW=true
fi

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

if [[ ${EUID} -ne 0 ]]; then
    fail "run as root (sudo $0 ${1:-})"
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)
JAR="${REPO_DIR}/target/${JAR_NAME}"

[[ -f "${JAR}" ]] || fail "missing ${JAR}; build it first with: mvn -q package"
command -v java >/dev/null 2>&1 || fail "java not found; install a JRE 17 or newer"

JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {split($2, parts, "."); print parts[1]; exit}')
[[ -n "${JAVA_MAJOR}" ]] || fail "cannot determine the Java version"
if [[ "${JAVA_MAJOR}" -lt 17 ]]; then
    fail "Java 17 or newer is required, found ${JAVA_MAJOR}"
fi

if ! getent group "${SERVICE_USER}" >/dev/null 2>&1; then
    groupadd --system "${SERVICE_USER}"
    echo "Created system group ${SERVICE_USER}"
fi

if ! id -u "${SERVICE_USER}" >/dev/null 2>&1; then
    useradd --system --gid "${SERVICE_USER}" --home-dir "${CONFIG_DIR}" --no-create-home \
        --shell /usr/sbin/nologin "${SERVICE_USER}"
    echo "Created system user ${SERVICE_USER}"
fi

install -d -o root -g root -m 0755 "${INSTALL_DIR}"
install -d -o root -g "${SERVICE_USER}" -m 0750 "${CONFIG_DIR}"
install -o root -g root -m 0644 "${JAR}" "${INSTALL_DIR}/${JAR_NAME}"
install -o root -g root -m 0644 "${SCRIPT_DIR}/${SERVICE_NAME}" "/etc/systemd/system/${SERVICE_NAME}"

NEW_ENV=false
if [[ ! -f "${CONFIG_DIR}/env" ]]; then
    install -o root -g root -m 0600 "${SCRIPT_DIR}/env.example" "${CONFIG_DIR}/env"
    NEW_ENV=true
fi

systemctl daemon-reload

echo
echo "Installed ${SERVICE_NAME}"
echo "  jar     : ${INSTALL_DIR}/${JAR_NAME}"
echo "  env     : ${CONFIG_DIR}/env"
echo "  config  : ${CONFIG_DIR}/config (you must create this)"
echo

if [[ "${NEW_ENV}" == true ]]; then
    echo "Next: edit ${CONFIG_DIR}/env, then place the OCI config and API key:"
else
    echo "Configuration file already exists; verify it, then make sure the OCI config and key are present:"
fi
cat <<EOF

  1. ${CONFIG_DIR}/config
       [DEFAULT]
       user=ocid1.user...
       fingerprint=...
       key_file=${CONFIG_DIR}/oci_api_key.pem
       tenancy=ocid1.tenancy...
       region=ap-singapore-1

  2. ${CONFIG_DIR}/oci_api_key.pem and the SSH public key referenced by SSH_PUBLIC_KEY_FILE

  3. Ownership and permissions (the service runs as ${SERVICE_USER}):
       chown root:${SERVICE_USER} ${CONFIG_DIR}/config ${CONFIG_DIR}/oci_api_key.pem
       chmod 0640 ${CONFIG_DIR}/config ${CONFIG_DIR}/oci_api_key.pem
       chmod 0644 ${CONFIG_DIR}/id_ed25519.pub

  4. Validate without making API calls:
       set -a; . ${CONFIG_DIR}/env; set +a
       java -jar ${INSTALL_DIR}/${JAR_NAME} --check

  5. Run:
       systemctl enable --now ${SERVICE_NAME}
       journalctl -u ${SERVICE_NAME} -f

EOF

if [[ "${START_NOW}" == true ]]; then
    if grep -v '^[[:space:]]*#' "${CONFIG_DIR}/env" | grep -q 'REPLACE_ME'; then
        fail "${CONFIG_DIR}/env still contains REPLACE_ME; fill it in first"
    fi
    systemctl enable --now "${SERVICE_NAME}"
    echo "Started ${SERVICE_NAME}"
fi
