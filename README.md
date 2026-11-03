# SylvLift

SylvLift is a Paper plugin for player-built elevators. It targets Paper 26.3
and Java 25; build the plugin with `mvn package` and install
`target/SylvLift.jar` in the server's `plugins` directory.

## Building an elevator

1. Build the lift platform from the blocks you want animated. Place an iron
   block directly underneath each of those blocks at the shaft's bottom
   landing. Those iron blocks stay fixed there and are never part of the
   animated platform.
2. Put a sign two blocks above the platform and enter `[Sylvlift]` on its first
   line. When prompted in chat, enter a unique ID containing 1-32 letters,
   digits, underscores, or hyphens. The sign becomes the interior control. The
   creator receives a live map showing only the current floor. Right-click a
   wall with that map to automatically mount it in an invisible item frame; the
   display continues to update as the lift moves.
3. At each landing, build a complete smooth-stone ring around the platform's
   footprint (one block beyond its edges). Build the landing doors described
   below, then attach a wall sign two blocks above the smooth stone, directly
   beside a glass pane. Any sign text can be used; SylvLift changes it to
   `[Elevator]` with a black square. Standing signs and signs at other heights
   are ignored.
4. At each landing, build the glass-pane door on top of the smooth-stone ring.
   Regular glass panes and any stained-glass pane color work. The supported
   layouts are one block wide by three blocks tall, or two blocks wide by three
   blocks tall. These are stationary landing doors, not part of the moving
   platform. For a two-wide door, the two three-block leaves slide away from
   each other. The doors stay open for 10 seconds after arrival. The lift plays
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

Floor rings and landing signs must be within 12 horizontal blocks of the
interior control sign. Lift IDs and floor locations are saved in the plugin
configuration across restarts. Destination platform/control-sign positions
must be clear before a lift will move.

After building additional landing rings, doors, and adjacent wall signs, run
`/sylvlift reload <id>` to rescan that elevator and add the new floors. This
command requires `sylvlift.reload` permission, granted to operators by default.
It scans generated chunks within the lift's setup area.
