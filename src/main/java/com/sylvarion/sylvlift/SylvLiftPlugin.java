package com.sylvarion.sylvlift;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.block.BlockFace;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.awt.Color;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SylvLiftPlugin extends JavaPlugin implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Map<String, Lift> lifts = new HashMap<>();
    private final Map<UUID, Location> namingPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLifts();
        lifts.values().forEach(lift -> {
            spawnControlInteraction(lift);
            setupFloorMap(lift, null);
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("sylvlift").setExecutor(this::handleCommand);
    }

    private boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Usage: /sylvlift reload <id>", NamedTextColor.YELLOW));
            return true;
        }
        Lift lift = lifts.get(args[1].toLowerCase(Locale.ROOT));
        if (lift == null) {
            sender.sendMessage(Component.text("No elevator exists with ID '" + args[1] + "'.", NamedTextColor.RED));
            return true;
        }
        if (lift.moving) {
            sender.sendMessage(Component.text("Wait until the elevator is idle before rescanning floors.",
                    NamedTextColor.RED));
            return true;
        }
        int addedFloors = discoverExistingLandingSigns(lift);
        saveLifts();
        sender.sendMessage(Component.text("Rescanned elevator '" + lift.id + "': " + addedFloors
                + " new floor(s) detected; " + lift.floors.size() + " floor(s) total.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public void onDisable() {
        for (Lift lift : lifts.values()) {
            if (lift.activeTask != null) {
                lift.activeTask.cancel();
            }
            if (lift.activeStop != null) {
                lift.activeStop.callSigns.forEach(location -> setCallSign(lift, location, false));
            }
            for (StopRequest stop : lift.stops) {
                stop.callSigns.forEach(location -> setCallSign(lift, location, false));
            }
            lift.stops.clear();
            if (lift.controlInteraction != null) {
                lift.controlInteraction.remove();
                lift.controlInteraction = null;
            }
            World world = Bukkit.getWorld(lift.worldId);
            if (world != null) {
                restoreIronAnchors(lift, world);
            }
            if (!lift.moving) {
                continue;
            }
            if (world == null) {
                continue;
            }
            for (PlatformBlock block : lift.blocks) {
                world.getBlockAt(lift.signLocation.getBlockX() + block.x, lift.currentFloorY,
                        lift.signLocation.getBlockZ() + block.z)
                        .setBlockData(Bukkit.createBlockData(block.data), false);
            }
            lift.signLocation.getBlock().setBlockData(Bukkit.createBlockData(lift.signData), false);
            lift.activeDisplays.forEach(BlockDisplay::remove);
            lift.activeDisplays.clear();
            if (lift.controlInteraction != null) {
                lift.controlInteraction.remove();
                lift.controlInteraction = null;
            }
            lift.moving = false;
            lift.activeStop = null;
        }
        saveLifts();
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String firstLine = plain(event.line(0)).trim();
        if (firstLine.equalsIgnoreCase("[Sylvlift]")) {
            Location sign = event.getBlock().getLocation();
            if (findLiftBlocks(sign).isEmpty()) {
                event.getPlayer().sendMessage(Component.text(
                        "Place iron blocks directly under every moving platform block first.", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            namingPlayers.put(event.getPlayer().getUniqueId(), sign);
            event.getPlayer().sendMessage(Component.text(
                    "Type an elevator ID in chat. This sign will become the elevator control.", NamedTextColor.YELLOW));
            return;
        }

        if (!event.getBlock().getType().name().endsWith("_SIGN")
                && !event.getBlock().getType().name().endsWith("_WALL_SIGN")) {
            return;
        }
        registerLandingSign(event.getBlock().getLocation());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Location signLocation = namingPlayers.remove(event.getPlayer().getUniqueId());
        if (signLocation == null) {
            return;
        }
        event.setCancelled(true);
        String id = plain(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> createLift(player, signLocation, id));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (placeFloorMapFrame(event)) {
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Sign)) {
            return;
        }
        Lift lift = liftAtInteriorSign(event.getClickedBlock().getLocation());
        if (lift != null) {
            event.setCancelled(true);
            openFloorMenu(event.getPlayer(), lift, 0);
            return;
        }
        for (Lift candidate : lifts.values()) {
            Integer floorY = candidate.landingSigns.get(key(event.getClickedBlock().getLocation()));
            if (floorY != null) {
                event.setCancelled(true);
                setCallSign(candidate, event.getClickedBlock().getLocation(), true);
                enqueueStop(candidate, floorY, event.getPlayer(), event.getClickedBlock().getLocation());
                return;
            }
        }
    }

    private boolean placeFloorMapFrame(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        if (held == null || held.getType() != Material.FILLED_MAP
                || !(held.getItemMeta() instanceof MapMeta mapMeta) || !mapMeta.hasMapView()) {
            return false;
        }
        Lift lift = lifts.values().stream()
                .filter(candidate -> candidate.floorMapId != null
                        && candidate.floorMapId == mapMeta.getMapView().getId())
                .findFirst().orElse(null);
        if (lift == null) {
            return false;
        }
        BlockFace face = event.getBlockFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Place the floor map on a wall.", NamedTextColor.RED));
            return true;
        }
        Block frameBlock = event.getClickedBlock().getRelative(face);
        if (!frameBlock.isEmpty() || !event.getClickedBlock().getType().isSolid()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("There is no room to place the floor display here.",
                    NamedTextColor.RED));
            return true;
        }
        Location frameLocation = frameBlock.getLocation().add(0.5, 0.5, 0.5);
        ItemFrame frame = frameBlock.getWorld().spawn(frameLocation, ItemFrame.class, spawned -> {
            spawned.setFacingDirection(face, true);
            spawned.setVisible(false);
            spawned.setFixed(true);
            spawned.setInvulnerable(true);
            spawned.setItem(held.clone(), false);
        });
        if (!frame.isValid()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Could not place the floor display frame.",
                    NamedTextColor.RED));
            return true;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            if (held.getAmount() <= 1) {
                event.getPlayer().getInventory().setItemInMainHand(null);
            } else {
                held.setAmount(held.getAmount() - 1);
            }
        } else {
            if (held.getAmount() <= 1) {
                event.getPlayer().getInventory().setItemInOffHand(null);
            } else {
                held.setAmount(held.getAmount() - 1);
            }
        }
        return true;
    }

    @EventHandler
    public void onControlEntityInteract(PlayerInteractEntityEvent event) {
        for (Lift lift : lifts.values()) {
            if (lift.controlInteraction != null
                    && lift.controlInteraction.getUniqueId().equals(event.getRightClicked().getUniqueId())) {
                event.setCancelled(true);
                openFloorMenu(event.getPlayer(), lift, 0);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FloorMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 45 && menu.page > 0) {
            openFloorMenu(player, menu.lift, menu.page - 1);
        } else if (slot == 53 && (menu.page + 1) * 45 < menu.lift.floors.size()) {
            openFloorMenu(player, menu.lift, menu.page + 1);
        } else if (slot >= 0 && slot < 45) {
            int index = menu.page * 45 + slot;
            if (index < menu.lift.floors.size()) {
                enqueueStop(menu.lift, menu.lift.floors.get(index), player, null);
                openFloorMenu(player, menu.lift, menu.page);
            }
        }
    }

    private void createLift(Player player, Location signLocation, String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,32}")) {
            player.sendMessage(Component.text("Use an ID of 1-32 letters, numbers, underscores, or hyphens.", NamedTextColor.RED));
            namingPlayers.put(player.getUniqueId(), signLocation);
            player.sendMessage(Component.text("Try again by entering an elevator ID in chat.", NamedTextColor.YELLOW));
            return;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (lifts.containsKey(normalized)) {
            player.sendMessage(Component.text("That elevator ID is already in use.", NamedTextColor.RED));
            namingPlayers.put(player.getUniqueId(), signLocation);
            player.sendMessage(Component.text("Try again with a different elevator ID.", NamedTextColor.YELLOW));
            return;
        }
        Block signBlock = signLocation.getBlock();
        if (!(signBlock.getState() instanceof Sign signState)) {
            return;
        }
        List<PlatformBlock> blocks = findLiftBlocks(signLocation);
        if (blocks.isEmpty()) {
            player.sendMessage(Component.text("No iron-supported platform blocks were found.", NamedTextColor.RED));
            return;
        }
        Lift lift = new Lift(normalized, signLocation.getWorld().getUID(), signLocation, blocks,
                signBlock.getBlockData().getAsString());
        lift.currentFloorY = signLocation.getBlockY() - 2;
        lift.floors.add(lift.currentFloorY);
        signState.line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
        signState.line(1, Component.text("Floor 1", NamedTextColor.GRAY));
        signState.update();
        lifts.put(normalized, lift);
        spawnControlInteraction(lift);
        discoverExistingLandingSigns(lift);
        setupFloorMap(lift, player);
        saveLifts();
        player.sendMessage(Component.text("Elevator '" + id + "' created with " + lift.floors.size() + " floor(s).",
                NamedTextColor.GREEN));
    }

    private List<PlatformBlock> findLiftBlocks(Location sign) {
        List<PlatformBlock> found = new ArrayList<>();
        World world = sign.getWorld();
        int markerY = sign.getBlockY() - 3;
        for (int x = sign.getBlockX() - 8; x <= sign.getBlockX() + 8; x++) {
            for (int z = sign.getBlockZ() - 8; z <= sign.getBlockZ() + 8; z++) {
                Block marker = world.getBlockAt(x, markerY, z);
                Block platform = world.getBlockAt(x, markerY + 1, z);
                if (marker.getType() == Material.IRON_BLOCK && !platform.isEmpty()) {
                    found.add(new PlatformBlock(x - sign.getBlockX(), 0, z - sign.getBlockZ(),
                            platform.getBlockData().getAsString()));
                }
            }
        }
        return found;
    }

    private void registerLandingSign(Location signLocation) {
        for (Lift lift : lifts.values()) {
            registerLandingSign(lift, signLocation);
        }
    }

    private boolean registerLandingSign(Lift lift, Location signLocation) {
        if (!lift.worldId.equals(signLocation.getWorld().getUID())) {
            return false;
        }
        int floorY = signLocation.getBlockY() - 2;
        if (!hasStoneRing(lift, floorY) || !nearPlatform(lift, signLocation)
                || !isNextToLandingDoor(lift, signLocation, floorY)) {
            return false;
        }
        String signKey = key(signLocation);
        boolean newSign = !lift.landingSigns.containsKey(signKey);
        lift.landingSigns.put(signKey, floorY);
        boolean newFloor = !lift.floors.contains(floorY);
        if (newFloor) {
            lift.floors.add(floorY);
            lift.floors.sort(Comparator.naturalOrder());
        }
        if (newSign) {
            Bukkit.getScheduler().runTask(this, () -> setCallSign(lift, signLocation, false));
        }
        if (newSign || newFloor) {
            saveLifts();
        }
        return newFloor;
    }

    private boolean isNextToLandingDoor(Lift lift, Location signLocation, int floorY) {
        if (signLocation.getBlockY() != floorY + 2
                || !(signLocation.getBlock().getBlockData() instanceof WallSign)) {
            return false;
        }
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return false;
        }
        DoorAssembly doors = findLandingDoors(lift, world, floorY);
        if (doors == null) {
            return false;
        }
        for (DoorLeaf leaf : doors.leaves) {
            for (Block pane : leaf.blocks) {
                if (pane.getY() == signLocation.getBlockY()
                        && Math.abs(pane.getX() - signLocation.getBlockX())
                        + Math.abs(pane.getZ() - signLocation.getBlockZ()) == 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private int discoverExistingLandingSigns(Lift lift) {
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return 0;
        }
        int addedFloors = 0;
        int baseY = lift.currentFloorY;
        int minY = Math.max(world.getMinHeight() + 2, baseY - 128);
        int maxY = Math.min(world.getMaxHeight() - 3, baseY + 128);
        int minChunkX = Math.floorDiv(lift.signLocation.getBlockX() - 12, 16);
        int maxChunkX = Math.floorDiv(lift.signLocation.getBlockX() + 12, 16);
        int minChunkZ = Math.floorDiv(lift.signLocation.getBlockZ() - 12, 16);
        int maxChunkZ = Math.floorDiv(lift.signLocation.getBlockZ() + 12, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkGenerated(chunkX, chunkZ)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockState state : chunk.getTileEntities()) {
                    Location location = state.getLocation();
                    if (!(state instanceof Sign) || location.getBlockY() < minY || location.getBlockY() > maxY
                            || Math.abs(location.getBlockX() - lift.signLocation.getBlockX()) > 12
                            || Math.abs(location.getBlockZ() - lift.signLocation.getBlockZ()) > 12
                            || sameBlock(location, lift.signLocation)) {
                        continue;
                    }
                    if (registerLandingSign(lift, location)) {
                        addedFloors++;
                    }
                }
            }
        }
        return addedFloors;
    }

    private boolean hasStoneRing(Lift lift, int floorY) {
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return false;
        }
        return ringBlocks(lift, floorY).stream()
                .allMatch(location -> location.getBlock().getType() == Material.SMOOTH_STONE);
    }

    private List<Location> ringBlocks(Lift lift, int floorY) {
        List<Location> ring = new ArrayList<>();
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return ring;
        }
        int minX = lift.blocks.stream().mapToInt(b -> b.x).min().orElse(0) + lift.signLocation.getBlockX() - 1;
        int maxX = lift.blocks.stream().mapToInt(b -> b.x).max().orElse(0) + lift.signLocation.getBlockX() + 1;
        int minZ = lift.blocks.stream().mapToInt(b -> b.z).min().orElse(0) + lift.signLocation.getBlockZ() - 1;
        int maxZ = lift.blocks.stream().mapToInt(b -> b.z).max().orElse(0) + lift.signLocation.getBlockZ() + 1;
        if (minX == maxX) {
            minX--;
            maxX++;
        }
        if (minZ == maxZ) {
            minZ--;
            maxZ++;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x != minX && x != maxX && z != minZ && z != maxZ) {
                    continue;
                }
                ring.add(new Location(world, x, floorY, z));
            }
        }
        return ring;
    }

    private boolean nearPlatform(Lift lift, Location location) {
        return Math.abs(location.getBlockX() - lift.signLocation.getBlockX()) <= 12
                && Math.abs(location.getBlockZ() - lift.signLocation.getBlockZ()) <= 12;
    }

    private void setCallSign(Lift lift, Location location, boolean active) {
        if (!(location.getBlock().getState() instanceof Sign sign)) {
            return;
        }
        sign.line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
        sign.line(1, Component.text("■", active ? NamedTextColor.RED : NamedTextColor.BLACK));
        sign.update();
    }

    private void openFloorMenu(Player player, Lift lift, int page) {
        FloorMenu menu = new FloorMenu(lift, page);
        Inventory inventory = Bukkit.createInventory(menu, 54, Component.text("Elevator " + lift.id));
        int start = page * 45;
        for (int index = start; index < Math.min(start + 45, lift.floors.size()); index++) {
            int floorY = lift.floors.get(index);
            boolean queued = isQueued(lift, floorY);
            Material material = floorY == lift.currentFloorY ? Material.LIME_CONCRETE
                    : queued ? Material.BLUE_CONCRETE : Material.IRON_INGOT;
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("Floor " + (index + 1), NamedTextColor.GOLD));
            String description = floorY == lift.currentFloorY ? "Current floor"
                    : queued ? "Already queued" : "Click to add a stop";
            meta.lore(List.of(Component.text(description, NamedTextColor.GRAY)));
            item.setItemMeta(meta);
            inventory.setItem(index - start, item);
        }
        if (page > 0) {
            inventory.setItem(45, menuItem(Material.ARROW, "Previous floors"));
        }
        if (start + 45 < lift.floors.size()) {
            inventory.setItem(53, menuItem(Material.ARROW, "More floors"));
        }
        player.openInventory(inventory);
    }

    private ItemStack menuItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isQueued(Lift lift, int floorY) {
        return (lift.activeStop != null && lift.activeStop.floorY == floorY)
                || lift.stops.stream().anyMatch(stop -> stop.floorY == floorY);
    }

    private void enqueueStop(Lift lift, int destinationY, Player caller, Location callSign) {
        if (destinationY == lift.currentFloorY && !lift.moving && lift.stops.isEmpty()) {
            if (callSign != null) {
                setCallSign(lift, callSign, false);
            }
            return;
        }
        StopRequest duplicate = lift.activeStop != null && lift.activeStop.floorY == destinationY
                ? lift.activeStop
                : lift.stops.stream().filter(stop -> stop.floorY == destinationY).findFirst().orElse(null);
        if (duplicate != null) {
            duplicate.addCallSign(callSign);
            return;
        }
        lift.stops.addLast(new StopRequest(destinationY, caller.getUniqueId(), callSign));
        if (!lift.moving) {
            lift.moving = true;
            beginNextStop(lift);
        }
    }

    private void beginNextStop(Lift lift) {
        StopRequest stop = lift.stops.pollFirst();
        if (stop == null) {
            lift.moving = false;
            lift.activeTask = null;
            return;
        }
        lift.activeStop = stop;
        World world = Bukkit.getWorld(lift.worldId);
        if (world != null) {
            restoreIronAnchors(lift, world);
        }
        if (stop.floorY == lift.currentFloorY) {
            finishStop(lift);
            return;
        }
        Player caller = Bukkit.getPlayer(stop.callerId);
        if (world == null) {
            failStop(lift, stop, caller, "Elevator world is unavailable.");
            return;
        }
        int delta = stop.floorY - lift.currentFloorY;
        lift.lastTravelDirection = Integer.signum(delta);
        for (PlatformBlock block : lift.blocks) {
            Block target = world.getBlockAt(lift.signLocation.getBlockX() + block.x,
                    stop.floorY, lift.signLocation.getBlockZ() + block.z);
            if (!target.isEmpty()) {
                failStop(lift, stop, caller, "The destination platform is obstructed.");
                return;
            }
        }
        Location destinationSign = lift.signLocation.clone().add(0, delta, 0);
        if (!destinationSign.getBlock().isEmpty()) {
            failStop(lift, stop, caller, "The destination control-sign space is obstructed.");
            return;
        }

        Location origin = lift.signLocation.clone();
        List<BlockDisplay> displays = new ArrayList<>();
        List<Player> riders = riders(lift, world);
        for (PlatformBlock block : lift.blocks) {
            Block source = world.getBlockAt(origin.getBlockX() + block.x, lift.currentFloorY,
                    origin.getBlockZ() + block.z);
            BlockDisplay display = world.spawn(source.getLocation(), BlockDisplay.class);
            display.setBlock(Bukkit.createBlockData(block.data));
            display.setInterpolationDuration(2);
            source.setType(Material.AIR, false);
            displays.add(display);
            lift.activeDisplays.add(display);
        }
        Block signBlock = origin.getBlock();
        BlockDisplay signDisplay = world.spawn(origin, BlockDisplay.class);
        signDisplay.setBlock(Bukkit.createBlockData(lift.signData));
        signBlock.setType(Material.AIR, false);
        lift.activeDisplays.add(signDisplay);

        int steps = Math.max(20, Math.abs(delta) * 4);
        lift.activeTask = new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                step++;
                double movedY = (double) delta / steps;
                for (BlockDisplay display : displays) {
                    display.teleport(display.getLocation().add(0, movedY, 0));
                }
                signDisplay.teleport(signDisplay.getLocation().add(0, movedY, 0));
                if (lift.controlInteraction != null) {
                    lift.controlInteraction.teleport(lift.controlInteraction.getLocation().add(0, movedY, 0));
                }
                for (Player rider : riders) {
                    if (rider.isOnline()) {
                        rider.teleport(rider.getLocation().add(0, movedY, 0));
                    }
                }
                if (step < steps) {
                    return;
                }
                for (int i = 0; i < lift.blocks.size(); i++) {
                    PlatformBlock block = lift.blocks.get(i);
                    Location destination = origin.clone().add(block.x, delta, block.z);
                    destination.getBlock().setBlockData(Bukkit.createBlockData(block.data), false);
                    displays.get(i).remove();
                }
                lift.currentFloorY = stop.floorY;
                lift.signLocation = destinationSign;
                lift.signLocation.getBlock().setBlockData(Bukkit.createBlockData(lift.signData), false);
                restoreIronAnchors(lift, world);
                signDisplay.remove();
                lift.activeDisplays.clear();
                lift.activeTask = null;
                updateInteriorSign(lift);
                saveLifts();
                cancel();
                finishStop(lift);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void failStop(Lift lift, StopRequest stop, Player caller, String message) {
        if (caller != null) {
            caller.sendMessage(Component.text(message, NamedTextColor.RED));
        }
        stop.callSigns.forEach(location -> setCallSign(lift, location, false));
        lift.activeStop = null;
        Bukkit.getScheduler().runTaskLater(this, () -> beginNextStop(lift), 20L);
    }

    private void restoreIronAnchors(Lift lift, World world) {
        for (IronAnchor anchor : lift.ironAnchors) {
            world.getBlockAt(anchor.x, anchor.y, anchor.z).setType(Material.IRON_BLOCK, false);
        }
    }

    private void finishStop(Lift lift) {
        StopRequest stop = lift.activeStop;
        if (stop != null) {
            stop.callSigns.forEach(location -> setCallSign(lift, location, false));
        }
        lift.activeStop = null;
        openDoors(lift, Bukkit.getWorld(lift.worldId), lift.currentFloorY);
    }

    private List<Player> riders(Lift lift, World world) {
        int minX = lift.blocks.stream().mapToInt(b -> b.x).min().orElse(0) + lift.signLocation.getBlockX();
        int maxX = lift.blocks.stream().mapToInt(b -> b.x).max().orElse(0) + lift.signLocation.getBlockX() + 1;
        int minZ = lift.blocks.stream().mapToInt(b -> b.z).min().orElse(0) + lift.signLocation.getBlockZ();
        int maxZ = lift.blocks.stream().mapToInt(b -> b.z).max().orElse(0) + lift.signLocation.getBlockZ() + 1;
        return world.getPlayers().stream().filter(player -> {
            Location location = player.getLocation();
            return location.getBlockY() == lift.currentFloorY + 1
                    && location.getX() >= minX && location.getX() <= maxX
                    && location.getZ() >= minZ && location.getZ() <= maxZ;
        }).toList();
    }

    private void openDoors(Lift lift, World world, int floorY) {
        if (world == null) {
            lift.lastTravelDirection = 0;
            lift.activeTask = Bukkit.getScheduler().runTaskLater(this, () -> beginNextStop(lift), 20L);
            return;
        }
        DoorAssembly doors = findLandingDoors(lift, world, floorY);
        if (doors == null) {
            lift.lastTravelDirection = 0;
            lift.activeTask = Bukkit.getScheduler().runTaskLater(this, () -> beginNextStop(lift), 20L);
            return;
        }
        int direction = lift.lastTravelDirection;
        lift.lastTravelDirection = 0;
        playArrivalDing(direction, world, lift.signLocation, floorY);
        lift.moving = true;
        List<Block> panes = doors.leaves.stream().flatMap(leaf -> leaf.blocks.stream()).toList();
        List<BlockDisplay> displays = new ArrayList<>();
        List<BlockData> data = new ArrayList<>();
        List<Location> closedLocations = new ArrayList<>();
        List<Vector> offsets = new ArrayList<>();
        for (DoorLeaf leaf : doors.leaves) {
            for (Block pane : leaf.blocks) {
                BlockDisplay display = world.spawn(pane.getLocation(), BlockDisplay.class);
                BlockData blockData = pane.getBlockData();
                display.setBlock(blockData);
                display.setInterpolationDuration(2);
                pane.setType(Material.AIR, false);
                displays.add(display);
                data.add(blockData);
                closedLocations.add(pane.getLocation());
                offsets.add(leaf.direction.clone());
                lift.activeDisplays.add(display);
            }
        }
        lift.activeTask = new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                tick++;
                if (tick <= 10 || tick > 210) {
                    double fraction = tick <= 10 ? tick / 10.0 : (220 - tick) / 10.0;
                    for (int i = 0; i < displays.size(); i++) {
                        Location closed = closedLocations.get(i);
                        displays.get(i).teleport(closed.clone().add(offsets.get(i).clone().multiply(fraction)));
                    }
                }
                if (tick < 220) {
                    return;
                }
                for (int i = 0; i < panes.size(); i++) {
                    panes.get(i).setBlockData(data.get(i), false);
                    displays.get(i).remove();
                    lift.activeDisplays.remove(displays.get(i));
                }
                cancel();
                lift.activeTask = Bukkit.getScheduler().runTaskLater(SylvLiftPlugin.this,
                        () -> beginNextStop(lift), 20L);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private DoorAssembly findLandingDoors(Lift lift, World world, int floorY) {
        List<DoorPanel> panels = new ArrayList<>();
        for (Location ringLocation : ringBlocks(lift, floorY)) {
            Block ring = ringLocation.getBlock();
            if (ring.getType() != Material.SMOOTH_STONE) {
                continue;
            }
            List<Block> verticalPanes = new ArrayList<>(3);
            boolean valid = true;
            for (int height = 1; height <= 3; height++) {
                Block pane = ring.getRelative(0, height, 0);
                if (!isGlassPane(pane.getType())) {
                    valid = false;
                    break;
                }
                verticalPanes.add(pane);
            }
            if (valid && !isGlassPane(ring.getRelative(0, 4, 0).getType())) {
                panels.add(new DoorPanel(ring.getX(), ring.getZ(), verticalPanes));
            }
        }

        if (panels.size() == 1) {
            DoorPanel panel = panels.getFirst();
            int minX = panelsRingMin(lift, floorY, true);
            int maxX = panelsRingMax(lift, floorY, true);
            int minZ = panelsRingMin(lift, floorY, false);
            int maxZ = panelsRingMax(lift, floorY, false);
            double centerX = (minX + maxX) / 2.0;
            double centerZ = (minZ + maxZ) / 2.0;
            Vector slideDirection = Math.abs(panel.z - centerZ) >= Math.abs(panel.x - centerX)
                    ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
            return new DoorAssembly(List.of(new DoorLeaf(panel.blocks, slideDirection)));
        }
        if (panels.size() != 2) {
            return null;
        }

        DoorPanel first = panels.get(0);
        DoorPanel second = panels.get(1);
        int deltaX = second.x - first.x;
        int deltaZ = second.z - first.z;
        if (Math.abs(deltaX) + Math.abs(deltaZ) != 1) {
            return null;
        }
        Vector axis = deltaX != 0 ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
        int projectionFirst = first.x * (deltaX != 0 ? 1 : 0) + first.z * (deltaZ != 0 ? 1 : 0);
        int projectionSecond = second.x * (deltaX != 0 ? 1 : 0) + second.z * (deltaZ != 0 ? 1 : 0);
        DoorPanel lower = projectionFirst < projectionSecond ? first : second;
        DoorPanel upper = projectionFirst < projectionSecond ? second : first;
        return new DoorAssembly(List.of(
                new DoorLeaf(lower.blocks, axis.clone().multiply(-1)),
                new DoorLeaf(upper.blocks, axis)));
    }

    private boolean isGlassPane(Material material) {
        return material == Material.GLASS_PANE || material.name().endsWith("_STAINED_GLASS_PANE");
    }

    private int panelsRingMin(Lift lift, int floorY, boolean xAxis) {
        return ringBlocks(lift, floorY).stream()
                .mapToInt(location -> xAxis ? location.getBlockX() : location.getBlockZ()).min().orElse(0);
    }

    private int panelsRingMax(Lift lift, int floorY, boolean xAxis) {
        return ringBlocks(lift, floorY).stream()
                .mapToInt(location -> xAxis ? location.getBlockX() : location.getBlockZ()).max().orElse(0);
    }

    private void spawnControlInteraction(Lift lift) {
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return;
        }
        if (lift.controlInteraction != null && lift.controlInteraction.isValid()) {
            lift.controlInteraction.remove();
        }
        lift.controlInteraction = world.spawn(lift.signLocation.clone().add(0.5, 0, 0.5), Interaction.class);
        lift.controlInteraction.setInteractionWidth(0.9f);
        lift.controlInteraction.setInteractionHeight(1.0f);
        lift.controlInteraction.setResponsive(true);
    }

    private void setupFloorMap(Lift lift, Player recipient) {
        World world = Bukkit.getWorld(lift.worldId);
        if (world == null) {
            return;
        }
        MapView mapView = lift.floorMapId == null ? null : Bukkit.getMap(lift.floorMapId);
        if (mapView == null) {
            mapView = Bukkit.createMap(world);
            lift.floorMapId = mapView.getId();
        }
        mapView.setWorld(world);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);
        mapView.setLocked(true);
        List.copyOf(mapView.getRenderers()).forEach(mapView::removeRenderer);
        mapView.addRenderer(new FloorMapRenderer(lift));

        if (recipient == null) {
            return;
        }
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        if (!(mapItem.getItemMeta() instanceof MapMeta mapMeta)) {
            recipient.sendMessage(Component.text("Could not create the elevator floor map.", NamedTextColor.RED));
            return;
        }
        mapMeta.setMapView(mapView);
        mapMeta.displayName(Component.text("Elevator " + lift.id + " Floor Display", NamedTextColor.GOLD));
        mapMeta.lore(List.of(Component.text("Right-click a wall to mount this display.", NamedTextColor.GRAY)));
        mapItem.setItemMeta(mapMeta);
        recipient.getInventory().addItem(mapItem).values()
                .forEach(item -> world.dropItemNaturally(recipient.getLocation(), item));
        recipient.sendMessage(Component.text("You received a live floor map. Right-click a wall with it to mount an invisible frame.",
                NamedTextColor.GREEN));
    }

    private void playArrivalDing(int direction, World world, Location liftLocation, int floorY) {
        if (direction == 0) {
            return;
        }
        Location soundLocation = liftLocation.clone();
        soundLocation.setY(floorY + 1);
        world.playSound(soundLocation, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        if (direction < 0) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> world.playSound(soundLocation, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f), 6L);
        }
    }

    private void updateInteriorSign(Lift lift) {
        Block signBlock = lift.signLocation.getBlock();
        if (signBlock.getState() instanceof Sign sign) {
            sign.line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
            int floorNumber = lift.floors.indexOf(lift.currentFloorY) + 1;
            sign.line(1, Component.text("Floor " + floorNumber, NamedTextColor.GRAY));
            sign.update();
        }
    }

    private Lift liftAtInteriorSign(Location location) {
        return lifts.values().stream().filter(lift -> sameBlock(lift.signLocation, location)).findFirst().orElse(null);
    }

    private void saveLifts() {
        getConfig().set("lifts", null);
        for (Lift lift : lifts.values()) {
            String path = "lifts." + lift.id;
            getConfig().set(path + ".world", lift.worldId.toString());
            getConfig().set(path + ".sign", key(lift.signLocation));
            getConfig().set(path + ".sign-data", lift.signData);
            getConfig().set(path + ".current", lift.currentFloorY);
            getConfig().set(path + ".floor-map-id", lift.floorMapId);
            getConfig().set(path + ".floors", lift.floors);
            getConfig().set(path + ".blocks", lift.blocks.stream()
                    .map(block -> block.x + "," + block.y + "," + block.z + "|" + block.data).toList());
            getConfig().set(path + ".iron-anchors", lift.ironAnchors.stream()
                    .map(anchor -> anchor.x + "," + anchor.y + "," + anchor.z).toList());
            getConfig().set(path + ".landings", lift.landingSigns.entrySet().stream()
                    .map(entry -> entry.getKey() + "|" + entry.getValue()).toList());
        }
        saveConfig();
    }

    private void loadLifts() {
        for (String id : getConfig().getConfigurationSection("lifts") == null
                ? List.<String>of() : getConfig().getConfigurationSection("lifts").getKeys(false)) {
            String path = "lifts." + id;
            UUID worldId = UUID.fromString(getConfig().getString(path + ".world"));
            Location sign = parseLocation(getConfig().getString(path + ".sign"));
            if (sign == null) {
                continue;
            }
            sign.setWorld(Bukkit.getWorld(worldId));
            if (sign.getWorld() == null) {
                continue;
            }
            List<PlatformBlock> blocks = new ArrayList<>();
            for (String serialized : getConfig().getStringList(path + ".blocks")) {
                String[] parts = serialized.split("\\|", 2);
                String[] coordinates = parts[0].split(",");
                blocks.add(new PlatformBlock(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                        Integer.parseInt(coordinates[2]), parts[1]));
            }
            Lift lift = new Lift(id, worldId, sign, blocks,
                    getConfig().getString(path + ".sign-data", "oak_sign[rotation=0]"));
            lift.currentFloorY = getConfig().getInt(path + ".current");
            if (getConfig().contains(path + ".floor-map-id")) {
                lift.floorMapId = getConfig().getInt(path + ".floor-map-id");
            }
            lift.floors.addAll(getConfig().getIntegerList(path + ".floors"));
            List<String> serializedAnchors = getConfig().getStringList(path + ".iron-anchors");
            if (!serializedAnchors.isEmpty()) {
                lift.ironAnchors.clear();
            }
            for (String serialized : serializedAnchors) {
                String[] coordinates = serialized.split(",");
                lift.ironAnchors.add(new IronAnchor(Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])));
            }
            if (lift.ironAnchors.isEmpty()) {
                int anchorY = lift.floors.stream().mapToInt(Integer::intValue).min()
                        .orElse(lift.currentFloorY) - 1;
                for (PlatformBlock block : blocks) {
                    lift.ironAnchors.add(new IronAnchor(sign.getBlockX() + block.x, anchorY,
                            sign.getBlockZ() + block.z));
                }
            }
            for (String serialized : getConfig().getStringList(path + ".landings")) {
                String[] parts = serialized.split("\\|", 2);
                lift.landingSigns.put(parts[0], Integer.parseInt(parts[1]));
            }
            lifts.put(id, lift);
        }
    }

    private Location parseLocation(String serialized) {
        if (serialized == null) {
            return null;
        }
        String[] parts = serialized.split(",");
        World world = Bukkit.getWorld(UUID.fromString(parts[0]));
        if (world == null) {
            return null;
        }
        return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private String key(Location location) {
        return location.getWorld().getUID() + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    private final class FloorMenu implements InventoryHolder {
        private final Lift lift;
        private final int page;

        private FloorMenu(Lift lift, int page) {
            this.lift = lift;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class FloorMapRenderer extends MapRenderer {
        private final Lift lift;

        private FloorMapRenderer(Lift lift) {
            super(true);
            this.lift = lift;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            Color background = new Color(238, 232, 210);
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    canvas.setPixelColor(x, y, background);
                }
            }
            int floorNumber = lift.floors.indexOf(lift.currentFloorY) + 1;
            String label = "FLOOR " + Math.max(floorNumber, 1);
            int textWidth = MinecraftFont.Font.getWidth(label);
            canvas.drawText(Math.max(0, (128 - textWidth) / 2), 60, MinecraftFont.Font, label);
        }
    }

    private static final class Lift {
        private final String id;
        private final UUID worldId;
        private Location signLocation;
        private final List<PlatformBlock> blocks;
        private final List<IronAnchor> ironAnchors = new ArrayList<>();
        private String signData;
        private final List<Integer> floors = new ArrayList<>();
        private final Map<String, Integer> landingSigns = new HashMap<>();
        private final Deque<StopRequest> stops = new ArrayDeque<>();
        private final List<BlockDisplay> activeDisplays = new ArrayList<>();
        private BukkitTask activeTask;
        private Interaction controlInteraction;
        private StopRequest activeStop;
        private Integer floorMapId;
        private int currentFloorY;
        private int lastTravelDirection;
        private boolean moving;

        private Lift(String id, UUID worldId, Location signLocation, List<PlatformBlock> blocks, String signData) {
            this.id = id;
            this.worldId = worldId;
            this.signLocation = signLocation;
            this.blocks = blocks;
            this.signData = signData;
            for (PlatformBlock block : blocks) {
                ironAnchors.add(new IronAnchor(signLocation.getBlockX() + block.x,
                        signLocation.getBlockY() - 3, signLocation.getBlockZ() + block.z));
            }
        }
    }

    private record PlatformBlock(int x, int y, int z, String data) {
    }

    private record IronAnchor(int x, int y, int z) {
    }

    private record DoorPanel(int x, int z, List<Block> blocks) {
    }

    private record DoorLeaf(List<Block> blocks, Vector direction) {
    }

    private record DoorAssembly(List<DoorLeaf> leaves) {
    }

    private static final class StopRequest {
        private final int floorY;
        private final UUID callerId;
        private final List<Location> callSigns = new ArrayList<>();

        private StopRequest(int floorY, UUID callerId, Location callSign) {
            this.floorY = floorY;
            this.callerId = callerId;
            addCallSign(callSign);
        }

        private void addCallSign(Location location) {
            if (location != null && callSigns.stream().noneMatch(existing ->
                    existing.getWorld().getUID().equals(location.getWorld().getUID())
                            && existing.getBlockX() == location.getBlockX()
                            && existing.getBlockY() == location.getBlockY()
                            && existing.getBlockZ() == location.getBlockZ())) {
                callSigns.add(location.clone());
            }
        }
    }
}
