# SylvLift contributor instructions

## Project and build

- SylvLift is a Java 25 plugin for Paper 26.3. Use supported Paper APIs; prefer current APIs over deprecated methods, reflection into server internals, or packet manipulation.
- Use the Gradle wrapper, not Maven. Windows: `.\gradlew.bat build`; macOS/Linux: `./gradlew build`. The output is `build/libs/SylvLift.jar`.
- `build.gradle` defines the Paper API version and Java toolchain. `src/main/resources/plugin.yml` defines commands, permissions, and the API version. Keep them consistent.
- Production code is currently in `src/main/java/com/sylvarion/sylvlift/SylvLiftPlugin.java`. Ownership regression tests are in `src/test/java/com/sylvarion/sylvlift/LiftOwnershipTest.java`.
- Some implementation was recovered from a supplied working JAR. Improve recovered local variable names when touching code, but avoid unrelated rewrites.

## Behaviour to preserve

- The ground-floor iron blocks are fixed anchors beneath the moving platform. Upper floors do not require iron anchors.
- Smooth stone is required beneath door columns only. Do not restore a requirement for a complete smooth-stone ring. The `ringBlocks` helper describes the search perimeter, not a required solid ring.
- Call signs are wall signs two blocks above platform level, within one or two horizontal block steps of a middle door pane. Diagonals count as two steps.
- Landing registration must not depend on a complete closed door assembly. Door panes are temporarily replaced with displays while doors are open.
- Registered call signs and interior controls must never open the vanilla sign editor, including while doors are open.
- Preserve full-height floor rescans, registration after sign placement, and registration retries on interaction.
- Nearby lifts must use separate connected platform footprints. Preserve ownership reconciliation and reject ambiguous landing signs.
- Each lift must own a mutable copy of its platform block list. Discovery can return an immutable `Stream.toList()` result; ownership reconciliation calls `removeIf`.
- Preserve queued stops, multi-column door groups, arrival sounds, floor renaming, deletion, permissions, and persisted floor names.
- The merged reference implementation removes legacy floor-map displays. Do not promise or restore that feature incidentally.
- The reference implementation clears blocks in the destination platform footprint. Treat changes to this behaviour as an explicit gameplay decision and document them.

## Movement and lifecycle

- Rider movement uses velocity with smoothstep easing and bounded drift correction. Avoid returning to player teleports every tick: they caused camera jitter and interfered with looking around.
- Moving block displays use teleport interpolation. One landing position adjustment is allowed at arrival.
- Restore each rider's original gravity and clear ride-induced fall distance/vertical velocity when releasing them. Handle arrival, disconnect, death, external teleport, leaving the footprint, and plugin shutdown.
- Keep Bukkit world/entity mutations on the server thread. Async chat handlers should schedule synchronous work.
- Cancel scheduled tasks and remove temporary entities on shutdown/deletion. Do not leave players in altered movement states.
- Do not claim measured performance gains without profiling. Movement work scales with active platform displays and riders.

## Verification

- For code changes, run the relevant tests and `gradlew build`. Add regression tests for actual bugs, especially ownership, registration, and lifecycle errors.
- Check `run/logs/latest.log` for reported console exceptions before guessing their cause.
- A successful build or empty-server startup does not verify camera feel, door animation, sign interaction, or multiplayer behaviour. State which checks were actually performed.
- When testing in-game, cover ascent/descent, free camera movement, open-door call signs, upper-floor registration with stone only under doors, and nearby independent lifts as relevant to the change.
- Keep README setup instructions consistent with implemented behaviour.

## Local test server and installation

- `gradlew runServer` launches Paper 26.3 using `run/`. This directory contains the user's test worlds, lift configuration, and other plugins, including FAWE. Preserve that data.
- The task uses run-paper's legacy plugin loading, which installs `run/plugins/SylvLift_RunServer_plugin.jar`. Do not also install `run/plugins/SylvLift.jar`: Paper reports an ambiguous duplicate plugin.
- The task backs up a leftover manually installed `SylvLift.jar` into `build/plugin-backups/` before starting. Stop the existing server before relaunching so Windows file locks are released.
- If the running JAR is locked, stage updates at `run/plugins/update/SylvLift_RunServer_plugin.jar` and tell the user a restart is required. Do not claim staged changes are already active.
- Avoid Bukkit `/reload` for updates. Do not interrupt the user's running server or modify its worlds just to perform a build check. Use a separate run directory and port for isolated startup checks.
- Reference JARs may be supplied in `src/main/resources/`. Preserve them as reference material, but exclude JAR files from processed resources so they are not nested in the built plugin.
- Do not commit generated output, server worlds/configuration, Gradle caches, or unrelated IntelliJ metadata. Keep the Gradle wrapper files available in source control.
