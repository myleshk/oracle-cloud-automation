package hk.myles.oci.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class TestConfigs {
    private TestConfigs() {}

    static Map<String, String> env(Path dir) throws IOException {
        Path ociConfig = dir.resolve("oci-config");
        Files.writeString(ociConfig, "[DEFAULT]\nuser=ocid1.user.oc1..example\n");
        Path publicKey = dir.resolve("id_ed25519.pub");
        Files.writeString(publicKey, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKey test@example\n");

        Map<String, String> env = new HashMap<>();
        env.put("OCI_CONFIG_FILE", ociConfig.toString());
        env.put("OCI_PROFILE", "DEFAULT");
        env.put("OCI_REGION", "ap-singapore-1");
        env.put("COMPARTMENT_OCID", "ocid1.compartment.oc1..aaaaaaaaexamplecompartment");
        env.put("SUBNET_OCID", "ocid1.subnet.oc1.ap-singapore-1..bbbbbbbbexamplesubnet");
        env.put("SSH_PUBLIC_KEY_FILE", publicKey.toString());
        return env;
    }

    static AppConfig config(Path dir) throws IOException {
        return AppConfig.fromEnv(env(dir));
    }
}
