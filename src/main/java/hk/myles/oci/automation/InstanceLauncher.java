package hk.myles.oci.automation;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.LaunchInstanceAgentConfigDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.LaunchInstanceRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.LaunchInstanceResponse;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.model.BmcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds launch details, performs an idempotent launch, and reports the instance address. */
public final class InstanceLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(InstanceLauncher.class);
    private static final Duration RUNNING_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration VNIC_TIMEOUT = Duration.ofMinutes(2);
    private static final long POLL_MILLIS = 5_000L;

    private final ComputeClient computeClient;
    private final VirtualNetworkClient virtualNetworkClient;

    public InstanceLauncher(ComputeClient computeClient, VirtualNetworkClient virtualNetworkClient) {
        this.computeClient = computeClient;
        this.virtualNetworkClient = virtualNetworkClient;
    }

    static LaunchInstanceDetails buildDetails(AppConfig config, String imageId, String availabilityDomain) {
        InstanceSourceViaImageDetails source = InstanceSourceViaImageDetails.builder()
                .imageId(imageId)
                .bootVolumeSizeInGBs(config.bootVolumeGb())
                .bootVolumeVpusPerGB(config.bootVolumeVpus())
                .build();

        CreateVnicDetails.Builder vnic = CreateVnicDetails.builder()
                .subnetId(config.subnetId())
                .assignPublicIp(config.assignPublicIp());
        if (!config.nsgIds().isEmpty()) {
            vnic.nsgIds(config.nsgIds());
        }

        LaunchInstanceDetails.Builder details = LaunchInstanceDetails.builder()
                .availabilityDomain(availabilityDomain)
                .compartmentId(config.compartmentId())
                .displayName(config.instanceName())
                .shape(config.shape())
                .shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                        .ocpus(config.ocpus())
                        .memoryInGBs(config.memoryGb())
                        .build())
                .sourceDetails(source)
                .createVnicDetails(vnic.build())
                .metadata(Map.of("ssh_authorized_keys", config.sshPublicKey()))
                .agentConfig(LaunchInstanceAgentConfigDetails.builder()
                        .isMonitoringDisabled(false)
                        .build());

        if (config.faultDomain() != null) {
            details.faultDomain(config.faultDomain());
        }
        return details.build();
    }

    /** Returns an existing instance with the configured display name, if one is still alive. */
    public Optional<Instance> findExisting(AppConfig config) {
        String page = null;
        do {
            ListInstancesResponse response = computeClient.listInstances(ListInstancesRequest.builder()
                    .compartmentId(config.compartmentId())
                    .displayName(config.instanceName())
                    .limit(100)
                    .page(page)
                    .build());
            List<Instance> items = response.getItems();
            if (items != null) {
                for (Instance instance : items) {
                    Instance.LifecycleState state = instance.getLifecycleState();
                    if (state != Instance.LifecycleState.Terminated
                            && state != Instance.LifecycleState.Terminating) {
                        return Optional.of(instance);
                    }
                }
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return Optional.empty();
    }

    public Instance launch(AppConfig config, String imageId, String availabilityDomain)
            throws InterruptedException {
        LaunchInstanceDetails details = buildDetails(config, imageId, availabilityDomain);
        LOG.info(
                "Requesting 1 x {} in {} ({} OCPU, {} GB, {} GB boot at {} VPUs/GB)",
                config.shape(),
                availabilityDomain,
                trim(config.ocpus()),
                trim(config.memoryGb()),
                config.bootVolumeGb(),
                config.bootVolumeVpus());

        LaunchInstanceResponse response = computeClient.launchInstance(
                LaunchInstanceRequest.builder().launchInstanceDetails(details).build());
        Instance instance = response.getInstance();
        LOG.info(
                "Launch accepted: instance {} (work request {})",
                instance.getId(),
                response.getOpcWorkRequestId());

        return waitForRunning(instance.getId());
    }

    public Instance waitForRunning(String instanceId) throws InterruptedException {
        Instant deadline = Instant.now().plus(RUNNING_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                Instance instance = computeClient.getInstance(
                        GetInstanceRequest.builder().instanceId(instanceId).build()).getInstance();
                Instance.LifecycleState state = instance.getLifecycleState();
                if (state == Instance.LifecycleState.Running) {
                    return instance;
                }
                if (state == Instance.LifecycleState.Terminating
                        || state == Instance.LifecycleState.Terminated) {
                    throw new IllegalStateException("Instance " + instanceId + " entered state " + state);
                }
                LOG.debug("Instance {} is {}", instanceId, state);
            } catch (BmcException e) {
                if (e.getStatusCode() != 404) {
                    throw e;
                }
                LOG.debug("Instance {} not visible yet", instanceId);
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException(
                "Instance " + instanceId + " did not reach RUNNING within " + RUNNING_TIMEOUT.toMinutes() + " minutes");
    }

    public void printAddresses(AppConfig config, Instance instance) {
        Instant deadline = Instant.now().plus(VNIC_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                ListVnicAttachmentsResponse response = computeClient.listVnicAttachments(
                        ListVnicAttachmentsRequest.builder()
                                .compartmentId(config.compartmentId())
                                .instanceId(instance.getId())
                                .build());
                List<VnicAttachment> attachments = response.getItems();
                if (attachments != null) {
                    for (VnicAttachment attachment : attachments) {
                        if (attachment.getLifecycleState() == VnicAttachment.LifecycleState.Attached
                                && attachment.getVnicId() != null) {
                            Vnic vnic = virtualNetworkClient.getVnic(
                                    GetVnicRequest.builder().vnicId(attachment.getVnicId()).build()).getVnic();
                            LOG.info(
                                    "Instance {} is running. Public IP: {}  Private IP: {}  VNIC: {}",
                                    instance.getId(),
                                    vnic.getPublicIp() == null ? "(none)" : vnic.getPublicIp(),
                                    vnic.getPrivateIp(),
                                    vnic.getId());
                            return;
                        }
                    }
                }
            } catch (BmcException e) {
                LOG.debug("VNIC not readable yet: {}", e.getMessage());
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for the instance VNIC");
                return;
            }
        }
        LOG.warn(
                "Instance {} is running, but its VNIC was not visible after {} minutes. Check the OCI console.",
                instance.getId(),
                VNIC_TIMEOUT.toMinutes());
    }

    private static String trim(float value) {
        return value == (long) value ? Long.toString((long) value) : Float.toString(value);
    }
}
