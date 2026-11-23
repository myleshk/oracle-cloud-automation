package hk.myles.oci.automation;

import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceLauncherTest {
    @TempDir Path dir;

    @Test
    void buildsFreeTierLaunchDetails() throws IOException {
        AppConfig config = TestConfigs.config(dir);

        LaunchInstanceDetails details =
                InstanceLauncher.buildDetails(config, "ocid1.image.oc1.ap-singapore-1..ubuntu", "AD-1");

        assertEquals("AD-1", details.getAvailabilityDomain());
        assertEquals(config.compartmentId(), details.getCompartmentId());
        assertEquals("VM.Standard.A1.Flex", details.getShape());
        assertEquals(2f, details.getShapeConfig().getOcpus());
        assertEquals(12f, details.getShapeConfig().getMemoryInGBs());

        InstanceSourceViaImageDetails source = (InstanceSourceViaImageDetails) details.getSourceDetails();
        assertEquals("ocid1.image.oc1.ap-singapore-1..ubuntu", source.getImageId());
        assertEquals(200L, source.getBootVolumeSizeInGBs());
        assertEquals(20L, source.getBootVolumeVpusPerGB());

        assertEquals(config.subnetId(), details.getCreateVnicDetails().getSubnetId());
        assertEquals(Boolean.TRUE, details.getCreateVnicDetails().getAssignPublicIp());
        assertTrue(details.getCreateVnicDetails().getNsgIds() == null
                || details.getCreateVnicDetails().getNsgIds().isEmpty());
        assertEquals(config.sshPublicKey(), details.getMetadata().get("ssh_authorized_keys"));
        assertNull(details.getFaultDomain());
    }

    @Test
    void appliesNsgAndFaultDomainWhenConfigured() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put("NSG_IDS", "ocid1.networksecuritygroup.oc1.ap-singapore-1..nsg");
        env.put("FAULT_DOMAIN", "FAULT-DOMAIN-2");
        AppConfig config = AppConfig.fromEnv(env);

        LaunchInstanceDetails details =
                InstanceLauncher.buildDetails(config, "ocid1.image.oc1.ap-singapore-1..ubuntu", "AD-2");

        assertEquals(1, details.getCreateVnicDetails().getNsgIds().size());
        assertEquals(
                "ocid1.networksecuritygroup.oc1.ap-singapore-1..nsg",
                details.getCreateVnicDetails().getNsgIds().get(0));
        assertEquals("FAULT-DOMAIN-2", details.getFaultDomain());
    }

    @Test
    void canDisablePublicIp() throws IOException {
        Map<String, String> env = TestConfigs.env(dir);
        env.put("ASSIGN_PUBLIC_IP", "false");
        AppConfig config = AppConfig.fromEnv(env);

        LaunchInstanceDetails details =
                InstanceLauncher.buildDetails(config, "ocid1.image.oc1.ap-singapore-1..ubuntu", "AD-1");

        assertFalse(details.getCreateVnicDetails().getAssignPublicIp());
    }

    @Test
    void anyActiveInstanceCountsEvenWithADifferentName() {
        Instance running = instance("manually-created", Instance.LifecycleState.Running);
        Instance terminated = instance("old", Instance.LifecycleState.Terminated);

        Optional<Instance> found = InstanceLauncher.firstActive(List.of(terminated, running));

        assertTrue(found.isPresent());
        assertEquals(running.getId(), found.get().getId());
    }

    @Test
    void terminatingStillCountsButTerminatedDoesNot() {
        Instance terminating = instance("a", Instance.LifecycleState.Terminating);

        assertTrue(InstanceLauncher.firstActive(List.of(instance("b", Instance.LifecycleState.Terminated))).isEmpty());
        assertEquals(terminating.getId(), InstanceLauncher.firstActive(List.of(terminating)).orElseThrow().getId());
        assertTrue(InstanceLauncher.firstActive(null).isEmpty());
    }

    @Test
    void returnsTheFirstActiveInstance() {
        Instance provisioning = instance("new", Instance.LifecycleState.Provisioning);
        Instance running = instance("old", Instance.LifecycleState.Running);

        Optional<Instance> found = InstanceLauncher.firstActive(List.of(provisioning, running));

        assertTrue(found.isPresent());
        assertEquals(provisioning.getId(), found.get().getId());
    }

    private static Instance instance(String name, Instance.LifecycleState state) {
        return Instance.builder()
                .id("ocid1.instance.oc1.ap-singapore-1.." + name)
                .displayName(name)
                .shape("VM.Standard.A1.Flex")
                .lifecycleState(state)
                .build();
    }
}
