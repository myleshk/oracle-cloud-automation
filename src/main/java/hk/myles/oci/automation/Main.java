package hk.myles.oci.automation;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.retrier.RetryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entry point. Resolves the image and availability domains once, then retries the launch until it
 * succeeds, a fatal error occurs, or the attempt budget is exhausted.
 */
public final class Main {
    private static final Logger LOG;

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ssXXX");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.log.com.oracle.bmc", "warn");
        LOG = LoggerFactory.getLogger(Main.class);
    }

    public static void main(String[] args) {
        System.exit(new Main().run(args));
    }

    int run(String[] args) {
        boolean checkOnly = false;
        boolean once = false;
        for (String arg : args) {
            switch (arg) {
                case "--help", "-h" -> {
                    printHelp();
                    return 0;
                }
                case "--check" -> checkOnly = true;
                case "--once" -> once = true;
                default -> {
                    System.err.println("Unknown argument: " + arg);
                    printHelp();
                    return 2;
                }
            }
        }

        AppConfig config;
        try {
            config = AppConfig.fromEnv(System.getenv());
        } catch (IllegalArgumentException e) {
            LOG.error("{}", e.getMessage());
            return 2;
        }

        if (checkOnly) {
            System.out.println("Configuration OK (no API calls made)");
            System.out.println(config.describe());
            System.out.println("  Free-tier limits  : within 2 OCPU / 12 GB / 200 GB");
            return 0;
        }

        LOG.info("Starting OCI instance automation for region {}", config.regionId());
        LOG.info("Configuration:{}{}", System.lineSeparator(), config.describe());

        ConfigFileAuthenticationDetailsProvider provider;
        try {
            provider = new ConfigFileAuthenticationDetailsProvider(config.ociConfigFile(), config.profile());
        } catch (IOException | RuntimeException e) {
            LOG.error("Cannot load OCI config {} (profile {}): {}",
                    config.ociConfigFile(), config.profile(), e.getMessage());
            return 2;
        }

        Region region = config.region();
        // The SDK's own retries are disabled so RetryPolicy owns the delay policy end to end.
        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectionTimeoutMillis(15_000)
                .readTimeoutMillis(120_000)
                .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                .build();

        ComputeClient computeClient =
                ComputeClient.builder().configuration(clientConfiguration).build(provider);
        computeClient.setRegion(region);
        VirtualNetworkClient virtualNetworkClient =
                VirtualNetworkClient.builder().configuration(clientConfiguration).build(provider);
        virtualNetworkClient.setRegion(region);
        IdentityClient identityClient =
                IdentityClient.builder().configuration(clientConfiguration).build(provider);
        identityClient.setRegion(region);

        String tenancyId = provider.getTenantId();
        List<String> availabilityDomains;
        try {
            availabilityDomains = resolveAvailabilityDomains(config, identityClient, tenancyId);
        } catch (IllegalStateException e) {
            LOG.error("{}", e.getMessage());
            return 1;
        }
        LOG.info("Availability domains: {}", String.join(", ", availabilityDomains));

        ImageResolver.ResolvedImage image;
        try {
            image = new ImageResolver(computeClient).resolve(config, tenancyId);
        } catch (RuntimeException e) {
            LOG.error("Cannot resolve the image: {}", e.getMessage());
            return 1;
        }

        InstanceLauncher launcher = new InstanceLauncher(computeClient, virtualNetworkClient);
        RetryPolicy policy = new RetryPolicy(
                config.intervalSeconds() * 1000L,
                config.maxBackoffSeconds() * 1000L);

        int maxAttempts = config.maxAttempts() == 0 ? Integer.MAX_VALUE : config.maxAttempts();
        int transientStreak = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String availabilityDomain = availabilityDomains.get((attempt - 1) % availabilityDomains.size());
            try {
                Optional<Instance> existing = launcher.findExisting(config);
                if (existing.isPresent()) {
                    Instance instance = existing.get();
                    LOG.info(
                            "An instance named '{}' already exists in state {}; not creating a duplicate",
                            config.instanceName(),
                            instance.getLifecycleState());
                    if (instance.getLifecycleState() == Instance.LifecycleState.Provisioning
                            || instance.getLifecycleState() == Instance.LifecycleState.Starting) {
                        instance = launcher.waitForRunning(instance.getId());
                    } else if (instance.getLifecycleState() == Instance.LifecycleState.Stopped
                            || instance.getLifecycleState() == Instance.LifecycleState.Stopping) {
                        LOG.warn(
                                "The existing instance is {}; it still consumes the free allowance. "
                                        + "Start it from the OCI console if that was not intended.",
                                instance.getLifecycleState());
                    }
                    LOG.info("Existing instance: {}", instance.getId());
                    launcher.printAddresses(config, instance);
                    return 0;
                }

                Instance instance = launcher.launch(config, image.id(), availabilityDomain);
                LOG.info("SUCCESS: instance {} is RUNNING", instance.getId());
                launcher.printAddresses(config, instance);
                return 0;
            } catch (BmcException e) {
                RetryPolicy.FailureKind kind = RetryPolicy.classify(e);
                String detail = summarize(e);
                if (kind == RetryPolicy.FailureKind.FATAL) {
                    LOG.error(
                            "Fatal OCI error (HTTP {}, code {}): {}",
                            e.getStatusCode(),
                            e.getServiceCode(),
                            detail);
                    LOG.error("Aborting this run; fix the configuration or the account limits first.");
                    return 1;
                }

                transientStreak = kind == RetryPolicy.FailureKind.CAPACITY ? 0 : transientStreak + 1;
                long delayMillis = policy.delayMillis(kind, transientStreak);
                LOG.warn(
                        "Attempt {} in {} failed ({}): {}; retrying in {} s",
                        attempt,
                        availabilityDomain,
                        kind,
                        detail,
                        delayMillis / 1000);

                if (once || attempt == maxAttempts) {
                    return 3;
                }
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Interrupted; stopping");
                    return 1;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted; stopping");
                return 1;
            } catch (RuntimeException e) {
                LOG.error("Unexpected failure, aborting", e);
                return 1;
            }
        }

        LOG.warn("No capacity within the attempt budget; the next scheduled run will try again");
        return 3;
    }

    private List<String> resolveAvailabilityDomains(
            AppConfig config, IdentityClient identityClient, String tenancyId) {
        List<String> names = new ArrayList<>();
        try {
            for (AvailabilityDomain domain : identityClient.listAvailabilityDomains(
                    ListAvailabilityDomainsRequest.builder().compartmentId(tenancyId).build()).getItems()) {
                names.add(domain.getName());
            }
        } catch (BmcException e) {
            throw new IllegalStateException("Cannot list availability domains: " + summarize(e));
        }

        if (names.isEmpty()) {
            throw new IllegalStateException("No availability domains returned for the tenancy");
        }
        if (config.availabilityDomain() == null) {
            return names;
        }
        List<String> matching = names.stream()
                .filter(name -> name.equalsIgnoreCase(config.availabilityDomain()))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalStateException(
                    "AVAILABILITY_DOMAIN '" + config.availabilityDomain()
                            + "' is not present here. Available: " + String.join(", ", names));
        }
        return matching;
    }

    private static String summarize(BmcException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().replace('\n', ' ').trim();
        if (message.length() > 400) {
            message = message.substring(0, 400) + "...";
        }
        String requestId = e.getOpcRequestId();
        return message + (requestId == null ? "" : " [request " + requestId + "]");
    }

    private static void printHelp() {
        System.out.println("""
                OCI Always Free instance automation

                Usage:
                  java -jar oci-instance-automation.jar            run until the instance exists
                  java -jar oci-instance-automation.jar --once     make one attempt, then exit
                  java -jar oci-instance-automation.jar --check    validate configuration, no API calls
                  java -jar oci-instance-automation.jar --help     show this text

                All configuration comes from environment variables (under systemd, an EnvironmentFile).

                Required:
                  COMPARTMENT_OCID          compartment to create the instance in
                  SUBNET_OCID               regional subnet for the VNIC
                  SSH_PUBLIC_KEY_FILE       OpenSSH public key injected into the instance
                  OCI_CONFIG_FILE           OCI CLI config file (default ~/.oci/config)

                Optional:
                  OCI_PROFILE               profile in the config file (default DEFAULT)
                  OCI_REGION                region id (default ap-singapore-1)
                  INSTANCE_NAME             display name, also the idempotency key (default free-a1)
                  SHAPE                     shape (default VM.Standard.A1.Flex)
                  OCPUS                     up to 2 (default 2)
                  MEMORY_GB                 up to 12 (default 12)
                  BOOT_VOLUME_GB            up to 200 (default 200)
                  BOOT_VOLUME_VPUS          10, 20, or 30-120 (default 20)
                  IMAGE_OS                  image OS name (default "Canonical Ubuntu")
                  IMAGE_VERSION             image version (default 26.04)
                  IMAGE_OCID                pin an image and skip discovery
                  AVAILABILITY_DOMAIN       restrict to one AD (default: rotate over all ADs)
                  FAULT_DOMAIN              optional fault domain
                  ASSIGN_PUBLIC_IP          true or false (default true)
                  NSG_IDS                   comma-separated NSG OCIDs (default none)
                  ATTEMPT_INTERVAL_SECONDS  minimum wait between attempts, >= 60 (default 60)
                  MAX_BACKOFF_SECONDS       exponential backoff cap (default 1800)
                  MAX_ATTEMPTS              0 for unlimited (default 0)

                Exit codes: 0 instance running or already exists, 1 fatal error,
                            2 bad configuration, 3 no capacity within the attempt budget.
                """);
    }
}
