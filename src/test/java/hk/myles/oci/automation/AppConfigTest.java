package hk.myles.oci.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {
    @TempDir Path dir;

    @Test
    void appliesFreeTierDefaults() throws IOException {
        AppConfig config = TestConfigs.config(dir);

        assertEquals("ap-singapore-1", config.regionId());
        assertEquals("VM.Standard.A1.Flex", config.shape());
        assertEquals(2f, config.ocpus());
        assertEquals(12f, config.memoryGb());
        assertEquals(199L, config.bootVolumeGb());
        assertEquals(20L, config.bootVolumeVpus());
        assertEquals(60, config.intervalSeconds());
        assertEquals(1800, config.maxBackoffSeconds());
        assertEquals(0, config.maxAttempts());
        assertEquals("Canonical Ubuntu", config.imageOs());
        assertEquals("26.04", config.imageVersion());
        assertNull(config.imageId());
        assertNull(config.availabilityDomain());
        assertTrue(config.assignPublicIp());
        assertTrue(config.nsgIds().isEmpty());
        assertTrue(config.sshPublicKey().startsWith("ssh-ed25519 "));
    }

    @Test
    void rejectsSizingAboveAlwaysFree() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put("OCPUS", "4");
        env.put("MEMORY_GB", "24");
        env.put("BOOT_VOLUME_GB", "250");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env));
        assertTrue(error.getMessage().contains("OCPUS"));
        assertTrue(error.getMessage().contains("MEMORY_GB"));
        assertTrue(error.getMessage().contains("BOOT_VOLUME_GB"));
    }

    @Test
    void rejectsPollingFasterThanOncePerMinute() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put("ATTEMPT_INTERVAL_SECONDS", "10");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env));
        assertTrue(error.getMessage().contains("ATTEMPT_INTERVAL_SECONDS"));
    }

    @Test
    void rejectsBadVpusAndNsgIds() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put("BOOT_VOLUME_VPUS", "15");
        env.put("NSG_IDS", "not-an-ocid");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env));
        assertTrue(error.getMessage().contains("BOOT_VOLUME_VPUS"));
        assertTrue(error.getMessage().contains("NSG_IDS"));
    }

    @Test
    void rejectsMissingRequiredValues() {
        Map<String, String> env = Map.of("OCI_REGION", "ap-singapore-1");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env));
        assertTrue(error.getMessage().contains("COMPARTMENT_OCID"));
        assertTrue(error.getMessage().contains("SUBNET_OCID"));
        assertTrue(error.getMessage().contains("SSH_PUBLIC_KEY_FILE"));
    }

    @Test
    void parsesNsgListAndImageOverride() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put(
                "NSG_IDS",
                "ocid1.networksecuritygroup.oc1.ap-singapore-1..one,ocid1.networksecuritygroup.oc1.ap-singapore-1..two");
        env.put("IMAGE_OCID", "ocid1.image.oc1.ap-singapore-1..ubuntu2604");

        AppConfig config = AppConfig.fromEnv(env);

        assertEquals(2, config.nsgIds().size());
        assertEquals("ocid1.image.oc1.ap-singapore-1..ubuntu2604", config.imageId());
    }

    @Test
    void descriptionDoesNotExposeFullOcids() throws IOException {
        AppConfig config = TestConfigs.config(dir);
        String description = config.describe();

        assertFalse(description.contains(config.compartmentId()));
        assertFalse(description.contains(config.subnetId()));
        assertTrue(description.contains("..."));
    }

    @Test
    void maskKeepsOnlyEdges() {
        String masked = AppConfig.mask("ocid1.compartment.oc1..aaaaaaaaexamplecompartment");

        assertTrue(masked.startsWith("ocid1."));
        assertTrue(masked.contains("..."));
        assertTrue(masked.endsWith("rtment"));
        assertEquals("***", AppConfig.mask("short"));
    }
}
