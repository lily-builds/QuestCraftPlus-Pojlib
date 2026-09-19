package pojlib.install;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pojlib.util.Constants;
import pojlib.util.FileUtil;
import pojlib.util.Logger;
import pojlib.util.download.DownloadUtils;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lily, forge-support WIP, 2026-09-19.
 *
 * Minimal Forge loader support for Pojlib: resolve the Forge build for a Minecraft
 * version and read the installer's bundled version.json into a VersionInfo, so the
 * existing Installer.installLibraries() can fetch Forge's libraries.
 *
 * NOT DONE YET (the hard part): the Forge install *processors* (installertools,
 * ForgeAutoRenamingTool, SpecialSource, binarypatcher, ...) that patch the client jar.
 * Without them a Forge instance downloads its libraries but will not launch. Also the
 * launcher must pass Forge's bootstrap module-path JVM args. See FORGE-PORT.md.
 */
public class ForgeMeta {
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    public static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    public static class ForgeVersion {
        public String mcVersion;
        public String forgeVersion;

        public String id() {
            return mcVersion + "-" + forgeVersion;
        }

        public String installerUrl() {
            return FORGE_MAVEN + "/" + id() + "/forge-" + id() + "-installer.jar";
        }
    }

    /** Resolves the recommended (falling back to latest) Forge build for a Minecraft version. */
    public static ForgeVersion getForgeVersion(String mcVersion) {
        try {
            File promos = new File(Constants.USER_HOME + "/forge_promotions.json");
            DownloadUtils.downloadFile(FORGE_PROMOS, promos);
            JsonObject all = JsonParser.parseString(FileUtil.read(promos.getPath())).getAsJsonObject();
            JsonObject promosObj = all.getAsJsonObject("promos");
            String key = mcVersion + "-recommended";
            if (!promosObj.has(key)) key = mcVersion + "-latest";
            if (!promosObj.has(key)) {
                Logger.getInstance().appendToLog("No Forge build found for " + mcVersion);
                return null;
            }
            ForgeVersion v = new ForgeVersion();
            v.mcVersion = mcVersion;
            v.forgeVersion = promosObj.get(key).getAsString();
            return v;
        } catch (Exception e) {
            Logger.getInstance().appendToLog("Failed to resolve Forge version: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** Downloads the Forge installer jar and parses its bundled version.json. */
    public static VersionInfo getVersionInfo(ForgeVersion forge, String gameDir) {
        try {
            File installer = new File(gameDir + "/forge-" + forge.id() + "-installer.jar");
            DownloadUtils.downloadFile(forge.installerUrl(), installer);
            try (ZipFile zip = new ZipFile(installer)) {
                ZipEntry entry = zip.getEntry("version.json");
                if (entry == null) {
                    Logger.getInstance().appendToLog("Forge installer has no version.json: " + forge.id());
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return new Gson().fromJson(new InputStreamReader(in), VersionInfo.class);
                }
            }
        } catch (Exception e) {
            Logger.getInstance().appendToLog("Failed to read Forge version.json: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Pulls the plain-string JVM args from a loader version.json. Placeholders are kept raw;
     * MinecraftInstances.Instance.generateLaunchArgs substitutes them at launch time.
     */
    public static List<String> extractJvmArgs(VersionInfo info) {
        List<String> out = new ArrayList<>();
        if (info == null || info.arguments == null || info.arguments.jvm == null) {
            return out;
        }
        for (Object o : info.arguments.jvm) {
            if (o instanceof String) {
                out.add((String) o);
            }
        }
        return out;
    }
}
