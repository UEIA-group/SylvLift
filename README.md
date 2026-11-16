# SylvLift

SylvLift is a Paper plugin for player-built elevators. It targets Paper 26.3
and Java 25. With JDK 25 installed, build with `./gradlew build` (macOS/Linux)
or `.\gradlew.bat build` (Windows) and install `build/libs/SylvLift.jar` in the
server's `plugins` directory. The Gradle wrapper downloads Gradle automatically;
the first build requires internet access to resolve dependencies.

To launch a local Paper 26.3 test server with the plugin installed automatically,
run `.\gradlew.bat runServer` on Windows or `./gradlew runServer` on macOS/Linux.
In IntelliJ, reload the Gradle project and open **Tasks → run paper → runServer**.
Server files are stored in `run/`. On first launch, review the Minecraft EULA and
accept it in `run/eula.txt` if you agree, then run the task again.

## Building an elevator

1. Build the lift platform from the blocks you want animated. Place an iron
   block directly underneath each of those blocks at the shaft's bottom
   landing. Those iron blocks stay fixed there and are never part of the
   animated platform.
2. Put a sign two blocks above the platform and enter `[Sylvlift]` on its first
   line. When prompted in chat, enter a unique ID containing 1-32 letters,
   digits, underscores, or hyphens. The sign becomes the interior control.
   Floor-map displays from older versions are removed automatically.
3. At each landing, place smooth stone directly beneath the door columns,
   one block outside the platform's footprint. A full ring is not required. Build the landing doors described
   below, then attach a wall sign two blocks above the smooth stone, within
   one or two horizontal block steps of a glass pane (diagonals count as two).
   Any sign text can be used; SylvLift changes it to
   `[Elevator]` with a black square. Standing signs and signs at other heights
   are ignored.
4. At each landing, build the glass-pane door on top of those smooth-stone blocks.
   Regular glass panes and any stained-glass pane color work. Doors must be
   at least three panes tall and may have multiple adjacent columns. Each
   separate door group needs a registered call sign nearby. These are stationary
   landing doors, not part of the moving platform. Wider doors split into leaves
   that slide away from each other. The doors stay open for 10 seconds after arrival. The lift plays
   one bell when arriving while going up and two bells when arriving while
   going down.

Right-click a landing sign to call the lift; its square turns red while the
lift is moving and returns to black on arrival. Right-click the sign inside
the lift to open the floor selector. Select multiple floors in one menu; the
lift queues the stops in selection order, pauses at each floor, holds the
doors open for 10 seconds, then continues one second after they close. The
menu remains usable during travel, and blue floor buttons indicate queued
stops. Floor labels are ordered from lowest to highest. Riders standing on
the platform are carried with it.

Landing doors and signs must be within 12 horizontal blocks of the
interior control sign. Lift IDs and floor locations are saved in the plugin
configuration across restarts. As in the supplied working JAR, the lift clears
blocks occupying its destination platform footprint. Keep that space reserved
for the lift. The destination control-sign space must be clear.

After building additional landing doors and adjacent wall signs, run
`/sylvlift reload <id>` to rescan that elevator and add the new floors. This
command requires `sylvlift.reload` permission, granted to operators by default.
It scans generated chunks within the lift's setup area.

Use `/sylvlift edit <id>` to rename floors through the menu and anvil screen,
or `/sylvlift delete <id>` to remove a lift's registration. These require the
matching `sylvlift.edit` and `sylvlift.delete` permissions (operators by default).
Call signs must belong unambiguously to one lift; nearby lifts use separate
connected platform footprints.
