package hk.myles.oci.automation;

import com.oracle.bmc.core.model.Image;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageResolverTest {
    private static final long DAY = 86_400_000L;

    @Test
    void picksNewestNonMinimalAarch64Image() {
        Image minimal = image("Canonical-Ubuntu-26.04-Minimal-aarch64-2026.06.01-0", "26.04", 3 * DAY);
        Image older = image("Canonical-Ubuntu-26.04-aarch64-2026.05.01-0", "26.04", DAY);
        Image newest = image("Canonical-Ubuntu-26.04-aarch64-2026.07.01-0", "26.04", 5 * DAY);

        Optional<Image> selected = ImageResolver.selectBest(List.of(minimal, older, newest), "26.04");

        assertTrue(selected.isPresent());
        assertEquals(newest.getId(), selected.get().getId());
    }

    @Test
    void ignoresOtherVersionsAndArchitectures() {
        Image arm2404 = image("Canonical-Ubuntu-24.04-aarch64-2026.07.01-0", "24.04", 9 * DAY);
        Image x86 = image("Canonical-Ubuntu-26.04-x86_64-2026.07.01-0", "26.04", 8 * DAY);
        Image arm2604 = image("Canonical-Ubuntu-26.04-aarch64-2026.06.01-0", "26.04", DAY);

        Optional<Image> selected = ImageResolver.selectBest(List.of(arm2404, x86, arm2604), "26.04");

        assertTrue(selected.isPresent());
        assertEquals(arm2604.getId(), selected.get().getId());
    }

    @Test
    void usesMinimalVariantOnlyWhenNothingElseExists() {
        Image minimal = image("Canonical-Ubuntu-26.04-Minimal-aarch64-2026.06.01-0", "26.04", DAY);

        Optional<Image> selected = ImageResolver.selectBest(List.of(minimal), "26.04");

        assertTrue(selected.isPresent());
        assertEquals(minimal.getId(), selected.get().getId());
    }

    @Test
    void returnsEmptyWhenNoImageMatches() {
        Image arm2404 = image("Canonical-Ubuntu-24.04-aarch64-2026.07.01-0", "24.04", DAY);

        assertTrue(ImageResolver.selectBest(List.of(arm2404), "26.04").isEmpty());
    }

    @Test
    void skipsUnavailableImages() {
        Image unavailable = Image.builder()
                .id("ocid1.image.oc1..unavailable")
                .displayName("Canonical-Ubuntu-26.04-aarch64-2026.08.01-0")
                .operatingSystemVersion("26.04")
                .lifecycleState(Image.LifecycleState.Disabled)
                .timeCreated(new Date(10 * DAY))
                .build();
        Image available = image("Canonical-Ubuntu-26.04-aarch64-2026.07.01-0", "26.04", DAY);

        Optional<Image> selected = ImageResolver.selectBest(List.of(unavailable, available), "26.04");

        assertTrue(selected.isPresent());
        assertEquals(available.getId(), selected.get().getId());
    }

    private static Image image(String displayName, String version, long createdOffset) {
        return Image.builder()
                .id("ocid1.image.oc1.ap-singapore-1.." + displayName.toLowerCase().replaceAll("[^a-z0-9]", ""))
                .displayName(displayName)
                .operatingSystem("Canonical Ubuntu")
                .operatingSystemVersion(version)
                .lifecycleState(Image.LifecycleState.Available)
                .timeCreated(new Date(1_700_000_000_000L + createdOffset))
                .build();
    }
}
