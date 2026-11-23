import com.oracle.bmc.Region;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.ComputeWaiters;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.core.responses.*;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.workrequests.WorkRequestClient;

import java.util.*;

@SuppressWarnings("ALL")
public class Main {
    private static final String PROFILE_DEFAULT = "DEFAULT";
    private static final String OPERATING_SYSTEM = "Oracle Linux";
    private static final String compartmentId = "ocid1.tenancy.oc1..aaaaaaaa6yecobcktzeteot6scvglwd3hwagn2ocpy4fuptmxgzz6mqddzsq";
    private static final String networkCidrBlock = null;
    private static final String sshPublicKey = null;
    private static final String kmsKeyId = null;
    private static final String imageId = "ocid1.image.oc1.ap-tokyo-1.aaaaaaaas4kux7nqvefoyy4gnvjzji6c4rmnjtwdvt3rt6yyl7cxv6e6x3hq";
    private static final String availabilityDomainName = "ralh:AP-TOKYO-1-AD-1";
    private static final String shapId = "VM.Standard.A1.Flex";
    private static final Region region = Region.AP_TOKYO_1;
    private static final String subnetId = "ocid1.subnet.oc1.ap-tokyo-1.aaaaaaaaquagby2pyun2wokdoozskvdtlzybdkfysg2or2uezzspvch74a5a";
    private static final String networkSecurityGroupId;


    public static void main(String[] args) throws Exception {

        // Read config from the profile DEFAULT in the file "~/.oci/config". You can switch to
        // different profile.
        AuthenticationDetailsProvider authenticationDetailsProvider =
                new ConfigFileAuthenticationDetailsProvider(PROFILE_DEFAULT);

        IdentityClient identityClient =
                IdentityClient.builder().build(authenticationDetailsProvider);
        identityClient.setRegion(region);

        ComputeClient computeClient = ComputeClient.builder().build(authenticationDetailsProvider);
        computeClient.setRegion(region);
        WorkRequestClient workRequestClient =
                WorkRequestClient.builder().build(authenticationDetailsProvider);
        workRequestClient.setRegion(region);
        ComputeWaiters computeWaiters = computeClient.newWaiters(workRequestClient);

        VirtualNetworkClient virtualNetworkClient =
                VirtualNetworkClient.builder().build(authenticationDetailsProvider);
        virtualNetworkClient.setRegion(region);

        BlockstorageClient blockstorageClient =
                BlockstorageClient.builder().build(authenticationDetailsProvider);
        blockstorageClient.setRegion(region);

        LaunchInstanceDetails launchInstanceDetails;
        Instance instance = null;
        Instance instanceFromBootVolume = null;
        BootVolume bootVolume;

        // The Internet Gateway with updated Route Rules will enable the instance to connect to
        // the public
        // internet. If it is not desired, remove the following two lines below that create an
        // internet
        // gateway and add that internet gateway to the VCN route table.


        // The Network Security Group with Security Rules will allow external HTTP traffic go to
        // the instance
        // through port 80. The HTTP server hosted on the instance will be open to the public.
        // You update
        // the Security Rules with your need accordingly.
        System.out.println("Instance is being created via image and KMS key ...");
        launchInstanceDetails =
                createLaunchInstanceDetails(
                        availabilityDomainName,
                        shapId,
                        imageId,
                        subnetId,
                        networkSecurityGroupId
                );
        instance = createInstance(computeWaiters, launchInstanceDetails);
        printInstance(computeClient, virtualNetworkClient, instance);

        System.out.println("Instance is being created via boot volume ...");
        // This boot volume is created based on the boot volume of previous instance which needs
        // to be running
        bootVolume =
                createBootVolume(
                        blockstorageClient, availabilityDomainName, imageId);
        launchInstanceDetails =
                createLaunchInstanceDetailsFromBootVolume(launchInstanceDetails, bootVolume);
        instanceFromBootVolume = createInstance(computeWaiters, launchInstanceDetails);
        printInstance(computeClient, virtualNetworkClient, instanceFromBootVolume);
    }

    private static Instance createInstance(
            ComputeWaiters computeWaiters, LaunchInstanceDetails launchInstanceDetails)
            throws Exception {
        LaunchInstanceRequest launchInstanceRequest =
                LaunchInstanceRequest.builder()
                        .launchInstanceDetails(launchInstanceDetails)
                        .build();
        LaunchInstanceResponse launchInstanceResponse =
                computeWaiters.forLaunchInstance(launchInstanceRequest).execute();

        GetInstanceRequest getInstanceRequest =
                GetInstanceRequest.builder()
                        .instanceId(launchInstanceResponse.getInstance().getId())
                        .build();
        GetInstanceResponse getInstanceResponse =
                computeWaiters
                        .forInstance(getInstanceRequest, Instance.LifecycleState.Running)
                        .execute();
        Instance instance = getInstanceResponse.getInstance();

        System.out.println("Launched Instance: " + instance.getId());
        System.out.println(instance);
        System.out.println();

        return instance;
    }

    private static LaunchInstanceDetails createLaunchInstanceDetails(
            String availabilityDomainName,
            String shapeId,
            String imageId,
            String subnetId,
            String networkSecurityGroupId) {
        String instanceName = "java-sdk-example-instance";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("ssh_authorized_keys", Main.sshPublicKey);
        metadata.put("java-sdk-example-metadata-key", "java-sdk-example-metadata-value");
        metadata = Collections.unmodifiableMap(metadata);
        Map<String, Object> extendedMetadata;
        extendedMetadata = Map.of("java-sdk-example-extended-metadata-key", "java-sdk-example-extended-metadata-value");
        InstanceSourceViaImageDetails instanceSourceViaImageDetails =
                InstanceSourceViaImageDetails.builder()
                        .imageId(imageId)
                        .kmsKeyId((Main.kmsKeyId == null || "".equals(Main.kmsKeyId)) ? null : Main.kmsKeyId)
                        .build();
        CreateVnicDetails createVnicDetails =
                CreateVnicDetails.builder()
                        .subnetId(subnetId)
                        .nsgIds(Collections.singletonList(networkSecurityGroupId))
                        .build();
        LaunchInstanceAgentConfigDetails launchInstanceAgentConfigDetails =
                LaunchInstanceAgentConfigDetails.builder().isMonitoringDisabled(false).build();
        return LaunchInstanceDetails.builder()
                .availabilityDomain(availabilityDomainName)
                .compartmentId(Main.compartmentId)
                .displayName(instanceName)
                // faultDomain is optional parameter
//                .faultDomain("FAULT-DOMAIN-1")
                .sourceDetails(instanceSourceViaImageDetails)
                .metadata(metadata)
                .extendedMetadata(extendedMetadata)
                .shape(shapeId)
                .createVnicDetails(createVnicDetails)
                // agentConfig is an optional parameter
                .agentConfig(launchInstanceAgentConfigDetails)
                .build();
    }


    private static void printInstance(
            ComputeClient computeClient,
            VirtualNetworkClient virtualNetworkClient,
            Instance instance) {
        ListVnicAttachmentsRequest listVnicAttachmentsRequest =
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(instance.getCompartmentId())
                        .instanceId(instance.getId())
                        .build();
        ListVnicAttachmentsResponse listVnicAttachmentsResponse =
                computeClient.listVnicAttachments(listVnicAttachmentsRequest);
        List<VnicAttachment> vnicAttachments = listVnicAttachmentsResponse.getItems();
        VnicAttachment vnicAttachment = vnicAttachments.get(0);

        GetVnicRequest getVnicRequest =
                GetVnicRequest.builder().vnicId(vnicAttachment.getVnicId()).build();
        GetVnicResponse getVnicResponse = virtualNetworkClient.getVnic(getVnicRequest);
        Vnic vnic = getVnicResponse.getVnic();

        System.out.println("Virtual Network Interface Card :" + vnic.getId());
        System.out.println(vnic);
        System.out.println();

        InstanceAgentConfig instanceAgentConfig = instance.getAgentConfig();
        boolean monitoringEnabled =
                (instanceAgentConfig != null) && !instanceAgentConfig.getIsMonitoringDisabled();
        String monitoringStatus = (monitoringEnabled ? "Enabled" : "Disabled");
        System.out.println("Instance " + instance.getId() + " has monitoring " + monitoringStatus);
        System.out.println();
    }

    private static BootVolume createBootVolume(
            BlockstorageClient blockstorageClient,
            String availabilityDomainName,
            String imageId)
            throws Exception {
        String bootVolumeName = "java-sdk-example-boot-volume";
        // find existing boot volume by image
        ListBootVolumesRequest listBootVolumesRequest =
                ListBootVolumesRequest.builder()
                        .availabilityDomain(availabilityDomainName)
                        .compartmentId(Main.compartmentId)
                        .build();
        ListBootVolumesResponse listBootVolumesResponse =
                blockstorageClient.listBootVolumes(listBootVolumesRequest);
        List<BootVolume> bootVolumes = listBootVolumesResponse.getItems();
        String bootVolumeId = null;
        for (BootVolume bootVolume : bootVolumes) {
            if (BootVolume.LifecycleState.Available.equals(bootVolume.getLifecycleState())
                    && imageId.equals(bootVolume.getImageId())) {
                bootVolumeId = bootVolume.getId();
                break;
            }
        }
        System.out.println("Found BootVolume: " + bootVolumeId);

        // create a new boot volume based on existing one
        BootVolumeSourceDetails bootVolumeSourceDetails =
                BootVolumeSourceFromBootVolumeDetails.builder().id(bootVolumeId).build();
        CreateBootVolumeDetails details =
                CreateBootVolumeDetails.builder()
                        .availabilityDomain(availabilityDomainName)
                        .compartmentId(Main.compartmentId)
                        .displayName(bootVolumeName)
                        .sourceDetails(bootVolumeSourceDetails)
                        .kmsKeyId(Main.kmsKeyId)
                        .build();
        CreateBootVolumeRequest createBootVolumeRequest =
                CreateBootVolumeRequest.builder().createBootVolumeDetails(details).build();
        CreateBootVolumeResponse createBootVolumeResponse =
                blockstorageClient.createBootVolume(createBootVolumeRequest);
        System.out.println(
                "Provisioning new BootVolume: " + createBootVolumeResponse.getBootVolume().getId());

        // wait for boot volume to be ready
        GetBootVolumeRequest getBootVolumeRequest =
                GetBootVolumeRequest.builder()
                        .bootVolumeId(createBootVolumeResponse.getBootVolume().getId())
                        .build();
        GetBootVolumeResponse getBootVolumeResponse =
                blockstorageClient
                        .getWaiters()
                        .forBootVolume(getBootVolumeRequest, BootVolume.LifecycleState.Available)
                        .execute();
        BootVolume bootVolume = getBootVolumeResponse.getBootVolume();

        System.out.println("Provisioned BootVolume: " + bootVolume.getId());
        System.out.println(bootVolume);
        System.out.println();

        return bootVolume;
    }

    private static LaunchInstanceDetails createLaunchInstanceDetailsFromBootVolume(
            LaunchInstanceDetails launchInstanceDetails, BootVolume bootVolume) {
        String bootVolumeName = "java-sdk-example-instance-from-boot-volume";
        InstanceSourceViaBootVolumeDetails instanceSourceViaBootVolumeDetails =
                InstanceSourceViaBootVolumeDetails.builder()
                        .bootVolumeId(bootVolume.getId())
                        .build();
        LaunchInstanceAgentConfigDetails launchInstanceAgentConfigDetails =
                LaunchInstanceAgentConfigDetails.builder().isMonitoringDisabled(true).build();
        return LaunchInstanceDetails.builder()
                .copy(launchInstanceDetails)
                .sourceDetails(instanceSourceViaBootVolumeDetails)
                .agentConfig(launchInstanceAgentConfigDetails)
                .build();
    }
}