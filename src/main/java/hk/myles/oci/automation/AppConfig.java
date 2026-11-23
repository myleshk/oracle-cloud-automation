package hk.myles.oci.automation;

import com.oracle.bmc.Region;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable runtime configuration, loaded from environment variables (systemd EnvironmentFile). */
public final class AppConfig {
    static final int FREE_TIER_MAX_OCPUS = 2;
    static final float FREE_TIER_MAX_MEMORY_GB = 12f;
    static final long FREE_TIER_MAX_BOOT_GB = 200L;
    static final int MIN_ATTEMPT_INTERVAL_SECONDS = 60;

    private final String ociConfigFile;
    private final String profile;
    private final String regionId;
    private final Region region;
    private final String compartmentId;
    private final String subnetId;
    private final String sshPublicKeyFile;
    private final String sshPublicKey;
    private final String instanceName;
    private final String shape;
    private final float ocpus;
    private final float memoryGb;
    private final long bootVolumeGb;
    private final long bootVolumeVpus;
    private final String imageOs;
    private final String imageVersion;
    private final String imageId;
    private final String availabilityDomain;
    private final String faultDomain;
    private final boolean assignPublicIp;
    private final List<String> nsgIds;
    private final int intervalSeconds;
    private final int maxBackoffSeconds;
    private final int maxAttempts;

    private AppConfig(
            String ociConfigFile,
            String profile,
            String regionId,
            Region region,
            String compartmentId,
            String subnetId,
            String sshPublicKeyFile,
            String sshPublicKey,
            String instanceName,
            String shape,
            float ocpus,
            float memoryGb,
            long bootVolumeGb,
            long bootVolumeVpus,
            String imageOs,
            String imageVersion,
            String imageId,
            String availabilityDomain,
            String faultDomain,
            boolean assignPublicIp,
            List<String> nsgIds,
            int intervalSeconds,
            int maxBackoffSeconds,
            int maxAttempts) {
        this.ociConfigFile = ociConfigFile;
        this.profile = profile;
        this.regionId = regionId;
        this.region = region;
        this.compartmentId = compartmentId;
        this.subnetId = subnetId;
        this.sshPublicKeyFile = sshPublicKeyFile;
        this.sshPublicKey = sshPublicKey;
        this.instanceName = instanceName;
        this.shape = shape;
        this.ocpus = ocpus;
        this.memoryGb = memoryGb;
        this.bootVolumeGb = bootVolumeGb;
        this.bootVolumeVpus = bootVolumeVpus;
        this.imageOs = imageOs;
        this.imageVersion = imageVersion;
        this.imageId = imageId;
        this.availabilityDomain = availabilityDomain;
        this.faultDomain = faultDomain;
        this.assignPublicIp = assignPublicIp;
        this.nsgIds = nsgIds;
        this.intervalSeconds = intervalSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.maxAttempts = maxAttempts;
    }

    public static AppConfig fromEnv(Map<String, String> env) {
        List<String> errors = new ArrayList<>();

        String defaultConfigFile =
                Path.of(System.getProperty("user.home", "/root"), ".oci", "config").toString();
        String ociConfigFile = string(env, "OCI_CONFIG_FILE", defaultConfigFile);
        String profile = string(env, "OCI_PROFILE", "DEFAULT");
        String regionId = string(env, "OCI_REGION", "ap-singapore-1");

        Region region = null;
        try {
            region = Region.fromRegionId(regionId);
        } catch (IllegalArgumentException e) {
            errors.add("OCI_REGION is not a known OCI region id: " + regionId);
        }

        String compartmentId = required(env, "COMPARTMENT_OCID", errors);
        String subnetId = required(env, "SUBNET_OCID", errors);
        String sshPublicKeyFile = required(env, "SSH_PUBLIC_KEY_FILE", errors);
        String instanceName = string(env, "INSTANCE_NAME", "free-a1");
        String shape = string(env, "SHAPE", "VM.Standard.A1.Flex");

        float ocpus = decimal(env, "OCPUS", 2f, errors);
        float memoryGb = decimal(env, "MEMORY_GB", 12f, errors);
        long bootVolumeGb = integer(env, "BOOT_VOLUME_GB", 200L, errors);
        long bootVolumeVpus = integer(env, "BOOT_VOLUME_VPUS", 20L, errors);
        String imageOs = string(env, "IMAGE_OS", "Canonical Ubuntu");
        String imageVersion = string(env, "IMAGE_VERSION", "26.04");
        String imageId = optional(env, "IMAGE_OCID");
        String availabilityDomain = optional(env, "AVAILABILITY_DOMAIN");
        String faultDomain = optional(env, "FAULT_DOMAIN");
        boolean assignPublicIp = bool(env, "ASSIGN_PUBLIC_IP", true, errors);
        List<String> nsgIds = nsgIds(env, errors);

        int intervalSeconds = (int) integer(env, "ATTEMPT_INTERVAL_SECONDS", 60L, errors);
        int maxBackoffSeconds = (int) integer(env, "MAX_BACKOFF_SECONDS", 1800L, errors);
        int maxAttempts = (int) integer(env, "MAX_ATTEMPTS", 0L, errors);

        if (compartmentId != null && !compartmentId.startsWith("ocid1.")) {
            errors.add("COMPARTMENT_OCID must be an OCID (ocid1...)");
        }
        if (subnetId != null && !subnetId.startsWith("ocid1.")) {
            errors.add("SUBNET_OCID must be an OCID (ocid1...)");
        }
        if (imageId != null && !imageId.startsWith("ocid1.image.")) {
            errors.add("IMAGE_OCID must be an image OCID (ocid1.image...)");
        }
        if (!instanceName.matches("[A-Za-z0-9._-]{1,255}")) {
            errors.add("INSTANCE_NAME may only contain letters, digits, dot, underscore and hyphen (max 255)");
        }
        if (ocpus <= 0 || ocpus > FREE_TIER_MAX_OCPUS) {
            errors.add("OCPUS must be greater than 0 and at most " + FREE_TIER_MAX_OCPUS
                    + " to stay inside Always Free");
        }
        if (memoryGb < 1 || memoryGb > FREE_TIER_MAX_MEMORY_GB) {
            errors.add("MEMORY_GB must be between 1 and " + (int) FREE_TIER_MAX_MEMORY_GB
                    + " to stay inside Always Free");
        }
        if (bootVolumeGb < 47 || bootVolumeGb > FREE_TIER_MAX_BOOT_GB) {
            errors.add("BOOT_VOLUME_GB must be between 47 (OCI minimum) and " + FREE_TIER_MAX_BOOT_GB
                    + " to stay inside Always Free");
        }
        if (!(bootVolumeVpus == 10 || bootVolumeVpus == 20
                || (bootVolumeVpus >= 30 && bootVolumeVpus <= 120))) {
            errors.add("BOOT_VOLUME_VPUS must be 10 (Balanced), 20 (Higher Performance) or 30-120 (Ultra High)");
        }
        if (intervalSeconds < MIN_ATTEMPT_INTERVAL_SECONDS) {
            errors.add("ATTEMPT_INTERVAL_SECONDS must be at least " + MIN_ATTEMPT_INTERVAL_SECONDS);
        }
        if (maxBackoffSeconds < intervalSeconds) {
            errors.add("MAX_BACKOFF_SECONDS must be at least ATTEMPT_INTERVAL_SECONDS");
        }
        if (maxAttempts < 0) {
            errors.add("MAX_ATTEMPTS must be 0 (unlimited) or a positive number");
        }

        if (!Files.isReadable(Path.of(ociConfigFile))) {
            errors.add("OCI config file is not readable: " + ociConfigFile);
        }

        String sshPublicKey = null;
        if (sshPublicKeyFile != null) {
            try {
                sshPublicKey = Files.readString(Path.of(sshPublicKeyFile), StandardCharsets.UTF_8).trim();
                if (sshPublicKey.isEmpty()) {
                    errors.add("SSH_PUBLIC_KEY_FILE is empty: " + sshPublicKeyFile);
                } else if (!sshPublicKey.startsWith("ssh-") && !sshPublicKey.startsWith("ecdsa-")) {
                    errors.add("SSH_PUBLIC_KEY_FILE does not look like an OpenSSH public key: " + sshPublicKeyFile);
                }
            } catch (IOException e) {
                errors.add("Cannot read SSH_PUBLIC_KEY_FILE " + sshPublicKeyFile + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid configuration:\n  - " + String.join("\n  - ", errors));
        }

        return new AppConfig(
                ociConfigFile,
                profile,
                regionId,
                region,
                compartmentId,
                subnetId,
                sshPublicKeyFile,
                sshPublicKey,
                instanceName,
                shape,
                ocpus,
                memoryGb,
                bootVolumeGb,
                bootVolumeVpus,
                imageOs,
                imageVersion,
                imageId,
                availabilityDomain,
                faultDomain,
                assignPublicIp,
                nsgIds,
                intervalSeconds,
                maxBackoffSeconds,
                maxAttempts);
    }

    private static String required(Map<String, String> env, String key, List<String> errors) {
        String value = optional(env, key);
        if (value == null) {
            errors.add(key + " is required");
        }
        return value;
    }

    private static String string(Map<String, String> env, String key, String defaultValue) {
        String value = optional(env, key);
        return value == null ? defaultValue : value;
    }

    private static String optional(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean bool(
            Map<String, String> env, String key, boolean defaultValue, List<String> errors) {
        String value = optional(env, key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        errors.add(key + " must be true or false");
        return defaultValue;
    }

    private static long integer(
            Map<String, String> env, String key, long defaultValue, List<String> errors) {
        String value = optional(env, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            errors.add(key + " must be an integer, got: " + value);
            return defaultValue;
        }
    }

    private static float decimal(
            Map<String, String> env, String key, float defaultValue, List<String> errors) {
        String value = optional(env, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            errors.add(key + " must be a number, got: " + value);
            return defaultValue;
        }
    }

    private static List<String> nsgIds(Map<String, String> env, List<String> errors) {
        String value = optional(env, "NSG_IDS");
        if (value == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!id.startsWith("ocid1.networksecuritygroup.")) {
                errors.add("NSG_IDS contains a value that is not a network security group OCID: " + id);
            } else {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    public String describe() {
        return String.join(
                System.lineSeparator(),
                "  OCI config        : " + ociConfigFile + " (profile " + profile + ")",
                "  Region            : " + regionId,
                "  Compartment       : " + mask(compartmentId),
                "  Subnet            : " + mask(subnetId),
                "  Instance name     : " + instanceName,
                "  Shape             : " + shape + " / " + trimNumber(ocpus) + " OCPU / "
                        + trimNumber(memoryGb) + " GB",
                "  Boot volume       : " + bootVolumeGb + " GB at " + bootVolumeVpus + " VPUs/GB"
                        + " (no data disk)",
                "  Image             : " + (imageId != null ? mask(imageId)
                        : imageOs + " " + imageVersion + " aarch64 (resolved at runtime)"),
                "  Availability AD   : " + (availabilityDomain == null ? "all ADs, rotating" : availabilityDomain),
                "  Fault domain      : " + (faultDomain == null ? "OCI default" : faultDomain),
                "  Public IP         : " + assignPublicIp,
                "  NSGs              : " + (nsgIds.isEmpty() ? "none" : String.join(",", nsgIds)),
                "  Retry interval    : " + intervalSeconds + " s (minimum, capacity waits)",
                "  Rate-limit backoff: exponential, cap " + maxBackoffSeconds + " s",
                "  Max attempts      : " + (maxAttempts == 0 ? "unlimited" : maxAttempts),
                "  SSH public key    : " + sshPublicKeyFile + " [" + keyType(sshPublicKey) + "]");
    }

    private static String keyType(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            return "unreadable";
        }
        String[] parts = publicKey.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    private static String trimNumber(float value) {
        return value == (long) value ? Long.toString((long) value) : Float.toString(value);
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(unset)";
        }
        if (value.length() <= 20) {
            return "***";
        }
        return value.substring(0, 16) + "..." + value.substring(value.length() - 6);
    }

    public String ociConfigFile() {
        return ociConfigFile;
    }

    public String profile() {
        return profile;
    }

    public String regionId() {
        return regionId;
    }

    public Region region() {
        return region;
    }

    public String compartmentId() {
        return compartmentId;
    }

    public String subnetId() {
        return subnetId;
    }

    public String sshPublicKeyFile() {
        return sshPublicKeyFile;
    }

    public String sshPublicKey() {
        return sshPublicKey;
    }

    public String instanceName() {
        return instanceName;
    }

    public String shape() {
        return shape;
    }

    public float ocpus() {
        return ocpus;
    }

    public float memoryGb() {
        return memoryGb;
    }

    public long bootVolumeGb() {
        return bootVolumeGb;
    }

    public long bootVolumeVpus() {
        return bootVolumeVpus;
    }

    public String imageOs() {
        return imageOs;
    }

    public String imageVersion() {
        return imageVersion;
    }

    public String imageId() {
        return imageId;
    }

    public String availabilityDomain() {
        return availabilityDomain;
    }

    public String faultDomain() {
        return faultDomain;
    }

    public boolean assignPublicIp() {
        return assignPublicIp;
    }

    public List<String> nsgIds() {
        return nsgIds;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public int maxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public String toString() {
        return "AppConfig{region=" + regionId + ", shape=" + shape + ", name=" + instanceName + "}";
    }
}
