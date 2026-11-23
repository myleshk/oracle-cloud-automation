package hk.myles.oci.automation;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.requests.ListImagesRequest;
import com.oracle.bmc.core.responses.ListImagesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves the newest Ubuntu aarch64 platform image at runtime, so no region-specific image OCID
 * is baked into configuration. Platform images live in the tenancy root compartment.
 */
public final class ImageResolver {
    private static final Logger LOG = LoggerFactory.getLogger(ImageResolver.class);
    private static final int MAX_PAGES = 50;

    private final ComputeClient computeClient;

    public ImageResolver(ComputeClient computeClient) {
        this.computeClient = computeClient;
    }

    public ResolvedImage resolve(AppConfig config, String tenancyId) {
        if (config.imageId() != null) {
            LOG.info("Using image OCID from IMAGE_OCID: {}", AppConfig.mask(config.imageId()));
            return new ResolvedImage(config.imageId());
        }

        List<Image> images = listPlatformImages(config, tenancyId);
        Optional<Image> selected = selectBest(images, config.imageVersion());
        if (selected.isEmpty()) {
            String available = images.stream()
                    .map(i -> i.getDisplayName()
                            + " [" + i.getOperatingSystemVersion() + ", "
                            + i.getLifecycleState() + "]")
                    .distinct()
                    .limit(12)
                    .collect(Collectors.joining("\n    "));
            throw new IllegalStateException(
                    "No " + config.imageOs() + " " + config.imageVersion()
                            + " aarch64 image found for shape " + config.shape()
                            + " in " + config.regionId()
                            + ". Set IMAGE_OCID to pin an image, or adjust IMAGE_VERSION."
                            + (available.isEmpty() ? "" : "\nAvailable images for this OS/shape:\n    " + available));
        }

        Image image = selected.get();
        LOG.info(
                "Resolved image: {} (version {}, created {})",
                image.getDisplayName(),
                image.getOperatingSystemVersion(),
                image.getTimeCreated());
        return new ResolvedImage(image.getId());
    }

    private List<Image> listPlatformImages(AppConfig config, String tenancyId) {
        List<Image> images = new ArrayList<>();
        String page = null;
        int pages = 0;
        do {
            ListImagesRequest request = ListImagesRequest.builder()
                    .compartmentId(tenancyId)
                    .operatingSystem(config.imageOs())
                    .shape(config.shape())
                    .sortBy(ListImagesRequest.SortBy.Timecreated)
                    .sortOrder(ListImagesRequest.SortOrder.Desc)
                    .page(page)
                    .build();
            ListImagesResponse response = computeClient.listImages(request);
            if (response.getItems() != null) {
                images.addAll(response.getItems());
            }
            page = response.getOpcNextPage();
            pages++;
        } while (page != null && pages < MAX_PAGES);
        return images;
    }

    static Optional<Image> selectBest(List<Image> images, String version) {
        List<Image> matches = images.stream()
                .filter(i -> i.getLifecycleState() == Image.LifecycleState.Available)
                .filter(i -> isArmImage(i.getDisplayName()))
                .filter(i -> matchesVersion(i, version))
                .sorted(Comparator.comparing(ImageResolver::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        // Prefer the full server image over the minimal variant; fall back if only minimal exists.
        return matches.stream()
                .filter(i -> !lower(i.getDisplayName()).contains("minimal"))
                .findFirst()
                .or(() -> Optional.of(matches.get(0)));
    }

    private static boolean isArmImage(String displayName) {
        String name = lower(displayName);
        return name.contains("aarch64") || name.contains("arm64");
    }

    private static boolean matchesVersion(Image image, String version) {
        String osVersion = image.getOperatingSystemVersion();
        if (osVersion != null && osVersion.startsWith(version)) {
            return true;
        }
        return lower(image.getDisplayName()).contains(version.toLowerCase(Locale.ROOT));
    }

    private static Date createdAt(Image image) {
        return image.getTimeCreated();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** A resolved platform image id. */
    public record ResolvedImage(String id) {}
}
