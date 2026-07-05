package ai.tegmentum.stardog.kibble.webfunctions;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class Version implements PluginVersionService {

    public static final String PLUGIN_VERSION;

    static {
        String version = "0.0.0";
        try {
            CodeSource codeSource = PluginVersion.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                File file = new File(PluginVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (file.isFile()) {
                    JarFile jarFile = new JarFile(file);
                    Manifest manifest = jarFile.getManifest();
                    version = manifest.getAttributes("webfunction").getValue("version");
                }
            }
        } catch(IOException | URISyntaxException e){
        }

        PLUGIN_VERSION = version;
    }

    public String pluginVersion() {
        return PLUGIN_VERSION;
    }
}
