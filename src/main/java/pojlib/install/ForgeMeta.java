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
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lily, forge-support WIP, 2026-09-19 (processors added same day).
 *
 * Forge loader support for Pojlib: resolve the Forge build for a Minecraft version,
 * read the installer's bundled version.json into a VersionInfo, install the processor
 * libraries, and run the Forge install processors in-process (no subprocess: on Android
 * the launcher runs inside the JVM already, so we URLClassLoader the processor jars).
 *
 * The processors (installertools, ForgeAutoRenamingTool, SpecialSource, binarypatcher,
 * jtool) are what turn the vanilla client jar into the patched jar Forge needs. Without
 * them a Forge instance downloads everything but cannot launch.
 *
 * Still open: the launcher must pass Forge's bootstrap module-path JVM args (extractJvmArgs
 * does that) and there is no Forge + Android/OpenXR Vivecraft build for 1.20.1 yet.
 * See FORGE-PORT.md.
 */
public class ForgeMeta {
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    public static final String FORGE_LIBRARIES_MAVEN = "https://maven.minecraftforge.net/";
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

    /** Downloads the Forge installer jar (once) and returns the local file. */
    public static File getInstaller(ForgeVersion forge, String gameDir) {
        File installer = new File(gameDir + "/forge-" + forge.id() + "-installer.jar");
        if (!installer.exists()) {
            try {
                DownloadUtils.downloadFile(forge.installerUrl(), installer);
            } catch (Exception e) {
                throw new RuntimeException("Failed to download Forge installer " + forge.id(), e);
            }
        }
        return installer;
    }

    /** Downloads the Forge installer jar and parses its bundled version.json. */
    public static VersionInfo getVersionInfo(ForgeVersion forge, String gameDir) {
        try {
            File installer = getInstaller(forge, gameDir);
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

    // ------------------------------------------------------------------
    // Install profile + processors
    // ------------------------------------------------------------------

    public static class InstallProfile {
        public Map<String, String> data = new HashMap<>();
        public List<Processor> processors = new ArrayList<>();
        public List<Lib> libraries = new ArrayList<>();

        public static class Lib {
            public String name;
            public String url;
            public LibDownloads downloads;

            public static class LibDownloads {
                public LibArtifact artifact;

                public static class LibArtifact {
                    public String path;
                    public String url;
                    public String sha1;
                    public long size;
                }
            }
        }

        public static class Processor {
            public String jar;
            public List<String> classpath = new ArrayList<>();
            public List<String> args = new ArrayList<>();
            public Map<String, String> outputs = new HashMap<>();
            public List<String> sides = new ArrayList<>();
        }
    }

    /** Reads install_profile.json from the installer jar. */
    public static InstallProfile getInstallProfile(ForgeVersion forge, String gameDir) {
        try {
            File installer = getInstaller(forge, gameDir);
            try (ZipFile zip = new ZipFile(installer)) {
                ZipEntry entry = zip.getEntry("install_profile.json");
                if (entry == null) {
                    Logger.getInstance().appendToLog("Forge installer has no install_profile.json: " + forge.id());
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return new Gson().fromJson(new InputStreamReader(in), InstallProfile.class);
                }
            }
        } catch (Exception e) {
            Logger.getInstance().appendToLog("Failed to read Forge install_profile: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** group:artifact:version[:classifier][@ext] -> relative maven path. */
    public static String mavenToPath(String coord) {
        String ext = "jar";
        if (coord.contains("@")) {
            int at = coord.indexOf('@');
            ext = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }
        String[] p = coord.split(":");
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length > 3 && !p[3].isEmpty() ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }

    /** Local path for a maven coordinate under the instance's libraries dir. */
    private static String libraryPath(String gameDir, String coord) {
        return new File(gameDir + "/libraries/", mavenToPath(coord)).getAbsolutePath();
    }

    /**
     * Downloads every library listed in the install profile into libraries/.
     * Returns the classpath (processor jars are resolved separately per processor).
     */
    public static List<String> installProcessorLibraries(InstallProfile profile, String gameDir) {
        List<String> paths = new ArrayList<>();
        if (profile == null) return paths;
        for (InstallProfile.Lib lib : profile.libraries) {
            try {
                File out;
                String url;
                if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
                    out = new File(gameDir + "/libraries/", lib.downloads.artifact.path);
                    url = lib.downloads.artifact.url;
                } else {
                    String path = mavenToPath(lib.name);
                    out = new File(gameDir + "/libraries/", path);
                    url = (lib.url != null ? lib.url : FORGE_LIBRARIES_MAVEN) + path;
                }
                if (!out.exists()) {
                    Logger.getInstance().appendToLog("Downloading processor lib: " + lib.name);
                    DownloadUtils.downloadFile(url, out);
                }
                paths.add(out.getAbsolutePath());
            } catch (Exception e) {
                Logger.getInstance().appendToLog("Failed processor lib " + lib.name + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        return paths;
    }

    /**
     * Runs the Forge install processors in-process. Each processor is a small Java program
     * (jar has a Main-Class) that patches the client jar. Args and data use Forge's
     * placeholder syntax: {VARIABLE} resolves through the data map, [group:artifact:version]
     * resolves to a local library path.
     */
    public static void runProcessors(InstallProfile profile, ForgeVersion forge, String gameDir, String minecraftVersion) {
        if (profile == null || profile.processors.isEmpty()) {
            Logger.getInstance().appendToLog("Forge install profile has no processors; nothing to run");
            return;
        }

        String clientJar = new File(gameDir + "/versions/" + minecraftVersion + "/client.jar").getAbsolutePath();

        // Seed the data map with the standard values Forge uses for the client side.
        Map<String, String> data = new HashMap<>(profile.data);
        data.put("SIDE", "client");
        data.put("MINECRAFT_JAR", clientJar);
        data.put("MINECRAFT_VERSION", minecraftVersion);
        data.put("ROOT", new File(gameDir).getAbsolutePath());
        data.put("INSTALLER", getInstaller(forge, gameDir).getAbsolutePath());
        data.put("LIBRARY_DIR", new File(gameDir + "/libraries/").getAbsolutePath());

        List<String> classpath = installProcessorLibraries(profile, gameDir);

        for (InstallProfile.Processor proc : profile.processors) {
            if (proc.sides != null && !proc.sides.isEmpty() && !proc.sides.contains("client")) {
                Logger.getInstance().appendToLog("Skipping server-side processor " + proc.jar);
                continue;
            }
            try {
                runProcessor(proc, data, classpath, gameDir);
                // Register the processor outputs so later steps can reference them.
                for (Map.Entry<String, String> e : proc.outputs.entrySet()) {
                    data.put(e.getKey(), resolveArg(e.getValue(), data, gameDir));
                }
            } catch (Exception e) {
                Logger.getInstance().appendToLog("Forge processor failed (" + proc.jar + "): " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Forge processor failed: " + proc.jar, e);
            }
        }
        Logger.getInstance().appendToLog("Forge processors finished for " + forge.id());
    }

    private static void runProcessor(InstallProfile.Processor proc, Map<String, String> data,
                                     List<String> libClasspath, String gameDir) throws Exception {
        File procJar = new File(libraryPath(gameDir, proc.jar));
        if (!procJar.exists()) {
            String path = mavenToPath(proc.jar);
            DownloadUtils.downloadFile(FORGE_LIBRARIES_MAVEN + path, procJar);
        }

        List<URL> urls = new ArrayList<>();
        urls.add(procJar.toURI().toURL());
        for (String cp : proc.classpath) {
            File f = new File(libraryPath(gameDir, cp));
            if (!f.exists()) {
                String path = mavenToPath(cp);
                DownloadUtils.downloadFile(FORGE_LIBRARIES_MAVEN + path, f);
            }
            urls.add(f.toURI().toURL());
        }
        // Some processors depend on install-profile libs not listed in their own classpath.
        for (String cp : libClasspath) {
            urls.add(new File(cp).toURI().toURL());
        }

        String mainClass;
        try (JarFile jf = new JarFile(procJar)) {
            mainClass = jf.getManifest().getMainAttributes().getValue("Main-Class");
        }
        if (mainClass == null) {
            throw new RuntimeException("No Main-Class in processor jar " + proc.jar);
        }

        List<String> resolvedArgs = new ArrayList<>();
        for (String a : proc.args) {
            resolvedArgs.add(resolveArg(a, data, gameDir));
        }

        Logger.getInstance().appendToLog("Running Forge processor: " + mainClass);

        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ForgeMeta.class.getClassLoader());
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        try {
            Class<?> clazz = Class.forName(mainClass, true, loader);
            Method main = clazz.getMethod("main", String[].class);
            main.invoke(null, (Object) resolvedArgs.toArray(new String[0]));
        } finally {
            current.setContextClassLoader(previous);
            loader.close();
        }
    }

    /** Resolves one argument: {VARIABLE} through data, [maven] to a local path. */
    public static String resolveArg(String arg, Map<String, String> data, String gameDir) {
        if (arg == null) return null;
        String out = arg;
        // [group:artifact:version] -> path
        int lb;
        while ((lb = out.indexOf('[')) >= 0) {
            int rb = out.indexOf(']', lb);
            if (rb < 0) break;
            String coord = out.substring(lb + 1, rb);
            out = out.substring(0, lb) + libraryPath(gameDir, coord) + out.substring(rb + 1);
        }
        // {VARIABLE} -> data value (recursive, guarded)
        int depth = 0;
        int ob;
        while ((ob = out.indexOf('{')) >= 0 && depth < 10) {
            int cb = out.indexOf('}', ob);
            if (cb < 0) break;
            String key = out.substring(ob + 1, cb);
            String val = resolveDataValue(key, data, gameDir);
            out = out.substring(0, ob) + (val != null ? val : "") + out.substring(cb + 1);
            depth++;
        }
        return out;
    }

    private static String resolveDataValue(String key, Map<String, String> data, String gameDir) {
        String val = data.get(key);
        if (val == null) return null;
        if (val.startsWith("[") && val.endsWith("]")) {
            return libraryPath(gameDir, val.substring(1, val.length() - 1));
        }
        if (val.startsWith("{") && val.endsWith("}")) {
            return resolveDataValue(val.substring(1, val.length() - 1), data, gameDir);
        }
        return val;
    }
}
