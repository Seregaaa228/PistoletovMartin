# TACZ Recruits 1.022 → 1.022-frontier.1

This local compatibility patch is for **Recruits 1.15.2** with the pinned TaCZ pack. The original addon is MIT-licensed by LogicalMaximus; the license and credits are retained and embedded in the patched JAR.

The unmodified original actually fails during mixin application: `@Overwrite searchForTargetsAsync(ServerLevel)` no longer exists in Recruits 1.15.2. Its live `searchForTargets()` wrapper invokes `searchForTargetsSync(ServerLevel)`. The addon already has a separate sync-targeting overwrite.

The patch deletes just the unused async overwrite and its five now-unreachable compiler-generated lambda bodies. It preserves the sync targeting overwrite, all shooting/cover/advance/suppressive-fire goals, animation integration, ammo integration, and the original mixin registration. It changes the metadata version/display name and adds an explicit exact Recruits 1.15.2 dependency. It does not change Recruits itself.

Artifact: `maven/dev/frontier/recruits-tacz/1.022-frontier.1/recruits-tacz-1.022-frontier.1.jar`

Local Maven coordinate: `dev.frontier:recruits-tacz:1.022-frontier.1`. Treat this as a production-namespace mod and pass it through ForgeGradle `fg.deobf` in a development environment. Install only this patched addon instead of the original `recruitstacz-1.022.jar` in a production instance.

`patch-manifest.json` records both original and patched SHA-1/SHA-256/SHA-512 and the precise changed ZIP entries. The original JAR is preserved at `upstream/recruitstacz-1.022.jar`. Only the mixin class and mods.toml differ among original entries; license and local-patch notice are added. All remaining original ZIP contents are byte-identical.

The Java ASM transformation is readable in `src/PatchRecruitsTacz.java`. It refuses an input whose SHA-512 differs from the pinned original. `src/VerifyPatch.java` compares every retained mixin method's resolved instructions and annotations, runs JVM basic dataflow verification, and checks unchanged mixin registration. `build_patch.py` uses the workspace's Java 17/ASM 9.7.1 caches and can regenerate the JAR from the preserved original without downloads.

Verified locally: 24 retained methods have identical instruction/annotation text; their BasicVerifier dataflow passes; exactly six obsolete methods are removed; all six remaining overwrite declarations across the addon, including inactive MixinSensing, match exact target declarations. This addresses the observed mixin failure. Runtime mixin application, NPC gunfire and rendering are separate tests being executed by the parent task.

Source inspection used the actual [recursive repository tree](https://api.github.com/repos/LogicalMaximus/TACZ-Recruits/git/trees/a4e8714dd28194173d8244e3a9e913b2e915c67b?recursive=1) at commit `a4e8714dd28194173d8244e3a9e913b2e915c67b`. Relevant files were then read by their verified paths:

- [MixinAbstractRecruitEntity.java](https://github.com/LogicalMaximus/TACZ-Recruits/blob/a4e8714dd28194173d8244e3a9e913b2e915c67b/src/main/java/com/logic/recruitstacz/mixin/MixinAbstractRecruitEntity.java)
- [MixinInnerThirdPersonManager.java](https://github.com/LogicalMaximus/TACZ-Recruits/blob/a4e8714dd28194173d8244e3a9e913b2e915c67b/src/main/java/com/logic/recruitstacz/mixin/tacz/MixinInnerThirdPersonManager.java)
- [MIT license](https://github.com/LogicalMaximus/TACZ-Recruits/blob/a4e8714dd28194173d8244e3a9e913b2e915c67b/LICENSE)

A static member-reference audit alone could not catch this: an @Overwrite target is an annotation contract, not an ordinary method call. Future dependency changes must include mixin-target checks and real transformed game startup.
