# Forge support for QuestCraftPlus+ — WIP port notes (Lily, 2026-09-19)

Creator asked: "add full forge support for the mods button and 1.20.1 and make sure when I
pick 1.20.1 the defaults mods are forge mods so it can work."

This branch is the first cut. It is NOT finished and a Forge instance will not boot yet.
Ground truth below, from the actual sources.

## What was missing (verified in source)
- `pojlib/InstanceHandler.create()`: the loader switch had `case "Forge": case "NeoForge": break;`
  → `modLoaderVersionInfo` stayed null → `instance.mainClass = modLoaderVersionInfo.mainClass` NPE'd.
  Forge was never implemented, only listed in the UI.
- `wrapper/Assets/Scripts/InstanceManager.cs`: the loader dropdown lists Fabric/Forge/Quilt/NeoForge,
  but `defaultModsToggle` was disabled for Forge, and instance labels were hardcoded `" - Fabric"`.
- `wrapper/Assets/Scripts/ModManager.cs`: the mod search hardcoded the Modrinth facet
  `["categories:fabric"]`, so the mods button only ever returned Fabric mods.
- `pojlib/mods.json`: no loader field; the 1.20.1 entry's core+default mods are all Fabric jars.
- The launcher command line was `-cp <classpath> <mainClass> <mcArgs>` only. Forge 1.20.1 runs
  through `cpw.mods.bootstraplauncher.BootstrapLauncher` and needs module-path JVM args.

## What this branch does
- `Instance.modLoader` is now stored on the instance and exposed to the wrapper.
- `ForgeMeta`: resolves the Forge build (`files.minecraftforge.net/.../promotions_slim.json`) and
  reads the installer's bundled `version.json` into a `VersionInfo`.
- `create()` Forge branch fetches the profile + libraries via the existing `Installer.installLibraries`.
- `generateLaunchArgs()` now prepends loader JVM args (with `${library_directory}`,
  `${classpath_separator}`, `${classpath}`, `${version_name}` substituted).
- Wrapper: mod search uses the instance's loader facet; default-mods toggle allowed for Forge;
  instance label shows the real loader.

## The two hard blockers that remain
1. **Forge install processors.** Forge's installer does not just download libraries; it runs
   processors (installertools, ForgeAutoRenamingTool, SpecialSource, binarypatcher, ...) that
   produce the patched client artifacts. None of that is implemented. The clean route is to run
   the installer jar itself (`--installClient <gameDir>`) with the bundled JRE before launch.
2. **A Forge + Android/OpenXR Vivecraft build does not exist.** QuestCraftPlusPlus/VivecraftMod
   only publishes Fabric Android builds for 1.20.1. The official Vivecraft 1.20.1 release has a
   `-forge` jar, but it is the desktop build (no Android/OpenXR input path), so VR controllers
   would not work with it. Making 1.20.1 Forge *usable in VR* needs a Forge variant of the
   QuestCraftPlus Android port built from source.

## Verified dependency facts (2026-09-19)
- Forge 1.20.1 recommended = `47.4.10` (latest 47.4.23).
- Installer: `https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-installer.jar`
- Official Vivecraft 1.20.1 Forge jar (desktop):
  `https://github.com/Vivecraft/VivecraftMod/releases/download/1.20.1-1.3.15/vivecraft-1.20.1-1.3.15-forge.jar`
- Forge 1.20.1 mod examples for a Forge default set:
  Embeddium `https://cdn.modrinth.com/data/sk9rgfiA/versions/UTbfe5d1/embeddium-0.3.31%2Bmc1.20.1.jar`,
  ModernFix (Forge) `https://cdn.modrinth.com/data/nmDcB62a/versions/jAZ7Ge3d/modernfix-forge-5.27.83%2Bmc1.20.1.jar`
  (Sodium is Fabric-only; on Forge the equivalent is Embeddium.)
- NeoForge has no 1.20.1 build (starts at 1.20.2).

## Next steps (in order)
1. Run the Forge installer headlessly to complete the install (blocker 1).
2. Add a loader-aware lookup in `mods.json` so 1.20.1 can carry both a Fabric and a Forge set.
3. Build a Forge Android/OpenXR Vivecraft variant (blocker 2) — separate, larger job.
4. Device test: create 1.20.1 + Forge, confirm boot, then confirm VR input.
