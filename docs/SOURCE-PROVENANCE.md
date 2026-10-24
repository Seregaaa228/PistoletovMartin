# Current Frontier modpack source provenance audit

Read-only audit dated 2026-10-24. Minecraft 1.20.1 / Forge 47.4.0 / Java 17. No source, installation, configuration, or worlds were modified. Only this report and its JSON were written.

All 27 current `/home/chpoka/.minecraft/mods/*.jar` files exactly match the final Industry 0.4.0 multiplayer accepted-mods inventory, after excluding its sole test helper. All six own installed JARs exactly match their release outputs. This confirms the handoff baseline; no clean rebuild was performed by this audit.

## Module map

| Module | Archive project subroot | Production Java / outer classes | Exact release binary SHA-256 |
|---|---|---|---|
| arcade | `frontier-arcade-vehicles/` | 70 / 49 | `2f1c383c1c99fdacfd1549036330a5f91b8b39034ab73500b2ca942a70239b45` |
| style | `./` | 14 / 14 | `58990022bdfb438981a42f8827c1fefa003b0972418ad82b80f91f6a7e89c04d` |
| nations | `work/frontier-nations/` | 8 / 8 | `5fc1374d51ea7e5c931229cf6db5d3a197f6168b9e1a02624f4fb9f9d19abdee` |
| industry | `frontier-industry/` | 56 / 56 | `aee9da30d4acd474ba638e07a76cafee57e794804449c3e210b1f85411d45c75` |
| aviation | `./` | 21 / 21 | `c85713196801b6abcede4b8eb7dd8e06e5d6f294a83edf44fd682e41d661bad3` |
| launch_fix | `./` | 22 / 22 | `28ca51bde23d38c77b0d6f7d7bef4a9dd76c5e3f038ee3850945d245b7242f70` |

Arcade includes 21 opt-in smoke Java files under main; build.gradle excludes their package from the production JAR. Every installed top-level class in all six modules has a corresponding Java path in its archive. This is name coverage, not proof of identical compilation. Nations has 8 production Java files and 14 total class files including inners. Its main resources add 6 files.

## Exact archives

- **arcade**: `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/drone-terminals/frontier-arcade-source-1.7.0.zip`
  SHA-256: `aa859a4c7fd852414bd02fd6ab63ec522670acee819140e0885c4c6735c1d0bb`; 158 archive entries.
- **style**: `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/sbw-unification/frontier-arcade-style-source-1.1.0.zip`
  SHA-256: `be614b01f9ad36c085232c95403fc3116a1aa9cdd87dc5351425783af962f1e1`; 247 archive entries.
- **nations**: `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/frontier-nations/sources/Frontier-Nations-source-0.1.2-alpha.zip`
  SHA-256: `66d44d4710e2fb30a04d20f2a6958f8202b45a56a1c0ab6055884118c3c3e550`; 64 archive entries.
- **industry**: `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/frontier-industry-0.4.0-source.zip`
  SHA-256: `3a5fdda978b331a3ee7c8f321bff5986cb6a6786d7ebc5169b82a33cbeba7ad9`; 308 archive entries.
- **aviation**: `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/aviation-1.1.0/frontier-light-aviation-1.1.0-source.zip`
  SHA-256: `85059dcc8d1ce325a79168a67028c5f9944785ac5019e2f5a5f9a761442c701f`; 92 archive entries.
- **launch_fix**: `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/missile-effects-1.3.0/frontier-flamingo-launch-fix-1.3.0-source.zip`
  SHA-256: `b06334eb99bebd49aa52afda603e170ee258036b132f56835721b4e280406b5c`; 55 archive entries.

## Build and compatibility details

- **Arcade**: keep `frontier-arcade-vehicles/` and sibling `maven/`, `prepare-local-sbw.py`, and `BUILD.md`. Run the root prepare script against the exact installed SBW hotfix, then `bash ./gradlew productionJar` inside the module. The prepare script requires SBW SHA-256 `3aaf4c239bc0fb31f9217927a44d74071d904d1dd03c5308ca3395ecd67d86dc`. Native 0.8.9 is not interchangeable. ForgeGradle 6.0.54 and Java 17; Gradle wrapper included. 74/74 archived runtime resources equal installed entries.
- **Style**: root `src/` and `resources/`; Python `build.py` uses Java 17 javac/FART and SRG-to-official reverse mapping. It hardcodes the old workspace JDK, Gradle cache, `deepstrike-api/javac-test.args`, and live `.minecraft/mods`. It extracts SimpleBedrockModel and its nested model-animation library from SBW. Original assets are packaged under `resourcepacks/frontier_ifv/assets/`; 144/144 runtime resources match. Asset generation uses Pillow and NumPy. `release.py` contains optional installation behavior and expects its historical topology; it should remain reference material in a normalized team project.
- **Nations**: extract `work/frontier-nations/` and keep `work/recruits-tacz-patch/` as its Maven sibling, or adapt that relative repository. `./gradlew clean build`; Java 17 / ForgeGradle 6.0.54. All 6 resources match. Archive root includes historical output/config/packaging content and two binary JARs; select source subroots deliberately. `BUILD-SOURCE.md` describes Python 3.11+; old packaging supports tomllib or fallback tomli, which is not bundled. Main sources exactly cover BootstrapState, FrontierNations, SmokeServer, client/SmokeClient, map/PoliticalMapBridge, and map/client/{PoliticalMapClient,PoliticalMapScreen,PoliticalMapSnapshot}.
- **Industry**: `frontier-industry/` Gradle project. `./gradlew build`, then **after reobfJar** run `python3 tools/preserve_combat.py build/libs/frontier-industry-1.20.1-0.4.0.jar`. Relative Maven sibling patch input also needed. 209/209 runtime resources match after expanding `${mod_version}`. Its own BUILD-0.4.md states normal offline Gradle resolution failed and the released artifact used cached javac/FART; a clean Gradle rebuild from this archive was not separately confirmed. V1/V2 construction plans are frozen; V3 geometry/order cannot change under the same version because the saved cursor records placement order.
- **Aviation**: root `src/`, `resources/`; its build.py embeds the same old-cache/JDK/installed-mod paths as Style, compiles against all installed JARs, and remaps with FART using DeepStrike/SBW libs. DeepStrike **0.9.0** and SBW **0.8.9.1** are exact metadata dependencies. 35 runtime resources match; three adjacent `*_lod.geo.json` authoring copies are intentionally omitted by build.py and their runtime copies live under vehicle_lod. Asset regeneration uses Pillow.
- **Launch fix**: root `src/`, `resources/`; same author-machine cache paths, Java 17 javac/FART. DeepStrike exactly 0.9.0; metadata Industry range `[0.3.1-alpha,0.5)`. 4/4 runtime resources match. Aviation 1.1.0 and fix 1.3.0 were accepted together; keep both exact versions on server and clients. Each archived package_updates.py assumes the original combined project and can install into live mods with its flag; do not use it unadapted in the standalone normalized project.

## Industry preserved bytecode boundary

The source archive includes older `NativeCombatEvents.java` and `ReadyWeapons.java`. BUILD-0.4.md explicitly says those Java files are not replacements for the current combat bridge. The exact production authored Java is not established by this source archive. Keep the two `preserved-combat/` class blobs as declared binary compatibility inputs; do not describe the resulting build as entirely reproduced from current Java sources. Both blobs equal the installed 0.4.0 entries and the backed-up 0.3.1 entries byte for byte.

| Entry | SHA-256 |
|---|---|
| `dev/frontier/industry/combat/NativeCombatEvents.class` | `8bdda1168156fa9ce5577498fb0d7f1b42a29938ed2b4ef3851393f45b56f407` |
| `dev/frontier/industry/combat/ReadyWeapons.class` | `9460e139b74ff680895b2d40065fb18dc9a41e4105e2ded05ba2aec28a29eef9` |

Backup proof: `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/backup-industry-0.3.1/frontier-industry-1.20.1-0.3.1-alpha.jar`.

## Recruits/TaCZ overlay

`work/recruits-tacz-patch/` contains readable ASM patch and verification Java, upstream MIT notice by LogicalMaximus, original JAR, two inspected upstream Java files, and the pinned Maven result. It is an overlay on upstream TACZ Recruits, not a complete upstream source checkout. Commit: `a4e8714dd28194173d8244e3a9e913b2e915c67b`; repository: https://github.com/LogicalMaximus/TACZ-Recruits .

Exact original SHA-256: `c9d69054118f912c8ad7010742d40a432f2579cea71688a4ad66269c9bb2f67a`; installed patched SHA-256: `2d0bc509573f927c141d7972719cf97175e04907b63e3b17dee2e6877d96b965`. Only one original **class** changes (`com/logic/recruitstacz/mixin/MixinAbstractRecruitEntity.class`) plus `META-INF/mods.toml`. Two notices are added; only ZIP directory markers are removed. All other original file entries are identical. Workspace Maven JAR equals the installed patched JAR.

The portable archive script is at `work/recruits-tacz-patch/build_patch_portable.py`. It needs a Java 17 JDK, production-namespace Forge SRG JAR, pinned upstream Recruits/TaCZ inputs and ASM 9.7.1. Its old script expects the archived lock under `outputs/frontier-nations/`; preserve or adapt that input. The unchanged production bridge must be passed through `fg.deobf` for development. Install only the patched addon, not both original and patched versions.

## License and attribution observations

These are exact locally observed declarations, not verification of upstream grants. Keep original notices with the source inputs and identify upstream dependencies separately from own code.

- Arcade source LICENSE.txt says MIT while source and installed mods.toml say GPL-3.0-only. Preserve both and record the unresolved conflict.
- Aviation and launch-fix source metadata says MIT; neither release archive includes a standalone LICENSE. Do not invent copyright-holder text.
- Source Style, Nations and Industry include MIT license text. Recruits/TaCZ patch preserves LogicalMaximus MIT notice and explicit patch attribution.
- Installed TaCZ declares `GPL3 / CC BY-NC-ND 4.0`; uncomplicated-fpv declares `All-Rights-Reserved`; mobplayeranimator declares `C0-1.0`. Historical MODS-LICENSES.md reports different, simpler values. Preserve the observed metadata and label the old document historical.
- DeepStrike, Villager Recruits, Living Villages and both Xaero mods declare All Rights Reserved in installed metadata. No full upstream sources are included in own-module archives. DeepStrike native assets are not own addon source. Existing dsm-source.json records upstream URL and exact SHA.
- The historical modpack lock/docs also list older Arcade/Industry and removed SBW Drone Warfare; they must not define the current 27-JAR baseline. The current JAR inventory and raw metadata are in team-source-audit.json.

## Release and acceptance proof paths

The final combined proof uses every current production JAR byte for byte plus only `frontier-industry-test.jar`. It records two real clients and actual server restart. Its final report distinguishes 27 multiplayer assertions, 10 restart assertions, 5 rendered-building restart assertions, 29 pure checks, and 49 earlier-candidate integration assertions; these counts should not be flattened into a single final-build coverage claim.

Arcade proof records 62 terminal checks and 148 production entries identical to its tested smoke artifact. Style proof records 556 native checks. Aviation/fix proof records 282 functional checks and 6 restart checks; performance was skipped and no new FPS/TPS guarantee is supported.

- `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/drone-terminals/verification.json` — SHA-256 `88a6e750bce1ee5cea415481933e05401ee83cd92556201d158c07a7d120bac5`
- `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/drone-terminals/tested-runtime.json` — SHA-256 `c114cf3b1b1ed5b2b0bdcedb799886c6a288c1dc27cc130b0666404a9c56058f`
- `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/sbw-unification/verification.json` — SHA-256 `d2050f21a49426727fd117b504d141e3d05285e951ee21a8183ee02f24f97c41`
- `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/sbw-unification/tested-runtime.json` — SHA-256 `597932aa3cbfacd01c16c1522042ebbdf7175506776c65af23c7e5306b00f961`
- `/home/chpoka/Documents/Codex/2026-10-23/new-chat/outputs/sbw-unification/native-checks.json` — SHA-256 `f6fed89c8cb80fc664d4995b7bdbd3d106e54f7ed758294cee7b208b6930fd6a`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/ПРОВЕРКИ.json` — SHA-256 `159d6cc8c37a49e6f9dfad3666878879ee79074b87ba871d5fdf768772de692b`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/verification/accepted-mods.json` — SHA-256 `190d1a87aded056d234205af634d1edde4554eba15c46ce9941bbf38735b2b34`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/verification/binary-audit.json` — SHA-256 `ceddbf8f468bb248cb70b7163653e8a3eed4935536bf224e75ff359d2a38c41c`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/verification/dedicated-two-clients.json` — SHA-256 `ed237b255adbaf433c66fc5c278ea9330fd50ec3643bc50e579280a7568d1590`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/verification/dedicated-actual-restart.json` — SHA-256 `e155dbc7eac56d4d80bdbefe16f6851cd4cf3793384f9d506268ea92115315bb`
- `/home/chpoka/Documents/Codex/2026-10-24/minecraft-thread-01a1696f-5a73-7733-93c7/outputs/verification/rendered-buildings-restart.json` — SHA-256 `fb434ace0488beaacbc61b14223bc0bf960af1e6649965c449121c5d5c730a3c`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/aviation-1.1.0/acceptance-summary.json` — SHA-256 `8d4f0b0ee2386bdeca632028cbadd3eb04e8d4f6cf43fa21ff91adf9519624a1`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/aviation-1.1.0/test-runtime-manifest.json` — SHA-256 `661d68b21223ceaae15a97a3ca7d8e19fef6050cabbe3e86fcd6c79087c0eccf`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/aviation-1.1.0/release-checksums.json` — SHA-256 `89b0e9e14f5bcc34938faaa86a5eaef4d42998ab3478583f05bac93443a36f87`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/missile-effects-1.3.0/acceptance-summary.json` — SHA-256 `8d4f0b0ee2386bdeca632028cbadd3eb04e8d4f6cf43fa21ff91adf9519624a1`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/missile-effects-1.3.0/test-runtime-manifest.json` — SHA-256 `661d68b21223ceaae15a97a3ca7d8e19fef6050cabbe3e86fcd6c79087c0eccf`
- `/home/chpoka/Documents/Codex/2026-10-24/flamingo-launch-fix/outputs/missile-effects-1.3.0/release-checksums.json` — SHA-256 `4b6006b4af00dba634cc9901a064c3be7b9c0bd2b8654d5d12823c179996b785`

This audit did not rerun acceptance, rebuild modules, verify external licenses online, or mutate the installation. Full JSON includes every current JAR hash, raw metadata, notice-entry hashes, resource checks, source/class name coverage and exact proof hashes.
