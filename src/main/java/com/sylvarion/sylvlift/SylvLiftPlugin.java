package com.sylvarion.sylvlift;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class SylvLiftPlugin extends JavaPlugin implements Listener {
   private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
   private static final int FLOOR_BELOW_SIGN = 2;
   private static final int MAX_FLOOR_NAME_LENGTH = 50;
   private record Rider(Player player, double height, boolean gravity) {}
   private final Map<String, SylvLiftPlugin.Lift> lifts = new HashMap<>();
   private final Map<UUID, Location> namingPlayers = new ConcurrentHashMap<>();
   private final Map<UUID, SylvLiftPlugin.RenameSession> renameSessions = new HashMap<>();

   public void onEnable() {
      this.saveDefaultConfig();
      this.loadLifts();
      this.reconcilePlatformOwnership();
      this.lifts.values().forEach(var1 -> {
         this.removeControlInteractions(var1);
         this.pruneInvalidLandingSigns(var1);
         this.removeFloorMapDisplays(var1);
      });
      this.saveLifts();
      Bukkit.getPluginManager().registerEvents(this, this);
      this.getCommand("sylvlift").setExecutor(this::handleCommand);
   }

   @EventHandler
   public void onChunkLoad(ChunkLoadEvent var1) {
      for (Entity var5 : var1.getChunk().getEntities()) {
         if (var5 instanceof Interaction && var5.getScoreboardTags().stream().anyMatch(var0 -> var0.startsWith("sylvlift-control-"))) {
            var5.remove();
         }
      }
   }

   private boolean handleCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var4.length == 2 && (var4[0].equalsIgnoreCase("reload") || var4[0].equalsIgnoreCase("delete") || var4[0].equalsIgnoreCase("edit"))) {
         String var5 = var4[0].toLowerCase(Locale.ROOT);
         if (!var1.hasPermission("sylvlift." + var5)) {
            var1.sendMessage(Component.text("You do not have permission to " + var5 + " elevators.", NamedTextColor.RED));
            return true;
         } else {
            SylvLiftPlugin.Lift var6 = this.lifts.get(var4[1].toLowerCase(Locale.ROOT));
            if (var6 == null) {
               var1.sendMessage(Component.text("No elevator exists with ID '" + var4[1] + "'.", NamedTextColor.RED));
               return true;
            } else if (var5.equals("edit")) {
               if (var1 instanceof Player var10) {
                  this.openFloorMenu(var10, var6, 0, true);
                  return true;
               } else {
                  var1.sendMessage(Component.text("Only players can edit floor names.", NamedTextColor.RED));
                  return true;
               }
            } else if (var6.moving) {
               var1.sendMessage(Component.text("Wait until the elevator is idle before running this command.", NamedTextColor.RED));
               return true;
            } else if (var5.equals("delete")) {
               this.deleteLift(var6);
               var1.sendMessage(Component.text("Elevator '" + var6.id + "' deleted.", NamedTextColor.GREEN));
               return true;
            } else {
               int var7 = var6.landingSigns.size();
               int var8 = this.discoverExistingLandingSigns(var6);
               int var9 = var6.landingSigns.size() - var7;
               this.saveLifts();
               var1.sendMessage(
                  Component.text(
                     "Rescanned elevator '"
                        + var6.id
                        + "': found "
                        + var9
                        + " new exterior sign(s), detected "
                        + var8
                        + " new floor(s), "
                        + var6.floors.size()
                        + " floor(s) total.",
                     NamedTextColor.GREEN
                  )
               );
               if (var1 instanceof Player player) {
                  explainTargetLanding(player, var6);
               }
               return true;
            }
         }
      } else {
         var1.sendMessage(Component.text("Usage: /sylvlift <reload|delete|edit> <id>", NamedTextColor.YELLOW));
         return true;
      }
   }

   private void explainTargetLanding(Player player, Lift lift) {
      Block sign = player.getTargetBlockExact(8);
      if (sign == null || !(sign.getState() instanceof Sign)
            || sameBlock(sign.getLocation(), lift.signLocation)) {
         player.sendMessage(Component.text("Look at a landing sign within 8 blocks and rescan to check its setup.", NamedTextColor.YELLOW));
         return;
      }
      Location location = sign.getLocation();
      int floorY = floorYForSign(location);
      String problem;
      if (!sign.getWorld().getUID().equals(lift.worldId)) {
         problem = "The sign is in a different world from this lift.";
      } else if (!(sign.getBlockData() instanceof WallSign)) {
         problem = "Use a wall sign, not a standing or hanging sign.";
      } else if (!nearPlatform(lift, location)) {
         problem = "The sign must be within 12 blocks horizontally of the interior control.";
      } else if (!isSignBesideLandingDoor(lift, location, floorY)) {
         problem = "At the platform edge, place smooth stone at Y=" + floorY
               + " beneath a door pane at Y=" + (floorY + 2)
               + ". The sign must be 1 or 2 horizontal block steps from that pane. No full stone ring is required.";
      } else if (!isUniqueLandingSignForLift(lift, location)) {
         problem = "This call sign also matches another lift. Move it so it belongs to only one platform.";
      } else {
         player.sendMessage(Component.text("Landing registered at platform Y=" + floorY + ".", NamedTextColor.GREEN));
         return;
      }
      player.sendMessage(Component.text(problem, NamedTextColor.YELLOW));
   }

   private void deleteLift(SylvLiftPlugin.Lift var1) {
      if (var1.activeTask != null) {
         var1.activeTask.cancel();
      }

      releaseRiders(var1, var1.currentFloorY, true);
      this.removeControlInteractions(var1);
      var1.activeDisplays.forEach(Entity::remove);
      var1.activeDisplays.clear();
      var1.stops.clear();
      World var2 = Bukkit.getWorld(var1.worldId);
      if (var2 != null) {
         this.restoreIronAnchors(var1, var2);
      }

      this.removeFloorMapDisplays(var1);
      this.clearManagedSign(var1.signLocation);
      var1.landingSigns.keySet().stream().map(this::parseLocation).filter(var0 -> var0 != null).forEach(this::clearManagedSign);
      this.lifts.remove(var1.id);
      this.saveLifts();
   }

   private void clearManagedSign(Location var1) {
      if (var1.getBlock().getState() instanceof Sign var2 && this.plain(var2.getSide(org.bukkit.block.sign.Side.FRONT).line(0)).equalsIgnoreCase("[Elevator]")) {
         var2.getSide(org.bukkit.block.sign.Side.FRONT).line(0, Component.empty());
         var2.getSide(org.bukkit.block.sign.Side.FRONT).line(1, Component.empty());
         var2.update();
      }
   }

   public void onDisable() {
      for (UUID var2 : List.copyOf(this.renameSessions.keySet())) {
         Player var3 = Bukkit.getPlayer(var2);
         if (var3 != null) {
            var3.closeInventory();
         }
      }

      this.renameSessions.clear();

      for (SylvLiftPlugin.Lift var7 : this.lifts.values()) {
         if (var7.activeTask != null) {
            var7.activeTask.cancel();
         }

         releaseRiders(var7, var7.currentFloorY, true);
         if (var7.activeStop != null) {
            var7.activeStop.callSigns.forEach(var2x -> this.setCallSign(var7, var2x, false));
         }

         for (SylvLiftPlugin.StopRequest var4 : var7.stops) {
            var4.callSigns.forEach(var2x -> this.setCallSign(var7, var2x, false));
         }

         var7.stops.clear();
         this.removeControlInteractions(var7);
         World var9 = Bukkit.getWorld(var7.worldId);
         if (var9 != null) {
            this.restoreIronAnchors(var7, var9);
         }

         if (var7.moving && var9 != null) {
            for (SylvLiftPlugin.PlatformBlock var5 : var7.blocks) {
               var9.getBlockAt(var7.signLocation.getBlockX() + var5.x, var7.currentFloorY + var5.y, var7.signLocation.getBlockZ() + var5.z)
                  .setBlockData(Bukkit.createBlockData(var5.data), false);
            }

            var7.signLocation.getBlock().setBlockData(Bukkit.createBlockData(var7.signData), false);
            var7.activeDisplays.forEach(Entity::remove);
            var7.activeDisplays.clear();
            this.removeControlInteractions(var7);
            var7.moving = false;
            var7.activeStop = null;
         }
      }

      this.saveLifts();
   }

   @EventHandler
   public void onSignChange(SignChangeEvent var1) {
      String var2 = this.plain(var1.line(0)).trim();
      if (var2.equalsIgnoreCase("[Sylvlift]")) {
         Location var4 = var1.getBlock().getLocation();
         if (this.findLiftBlocks(var4).isEmpty()) {
            var1.getPlayer().sendMessage(Component.text("Place iron blocks directly under every moving platform block first.", NamedTextColor.RED));
            var1.setCancelled(true);
         } else {
            this.namingPlayers.put(var1.getPlayer().getUniqueId(), var4);
            var1.getPlayer().sendMessage(Component.text("Type an elevator ID in chat. This sign will become the elevator control.", NamedTextColor.YELLOW));
         }
      } else if (var1.getBlock().getType().name().endsWith("_SIGN") || var1.getBlock().getType().name().endsWith("_WALL_SIGN")) {
         Location var3 = var1.getBlock().getLocation();
         Bukkit.getScheduler().runTask(this, () -> this.registerLandingSign(var3));
      }
   }

   @EventHandler
   public void onChat(AsyncChatEvent var1) {
      Location var2 = this.namingPlayers.remove(var1.getPlayer().getUniqueId());
      if (var2 != null) {
         var1.setCancelled(true);
         String var3 = this.plain(var1.message()).trim();
         Player var4 = var1.getPlayer();
         Bukkit.getScheduler().runTask(this, () -> this.createLift(var4, var2, var3));
      }
   }

   @EventHandler
   public void onOpenLiftSign(PlayerOpenSignEvent event) {
      Location location = event.getSign().getLocation();
      if (liftAtInteriorSign(location) != null
            || lifts.values().stream().anyMatch(lift -> lift.landingSigns.containsKey(key(location)))) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onInteract(PlayerInteractEvent var1) {
      if (var1.getAction() == Action.RIGHT_CLICK_BLOCK && var1.getClickedBlock() != null) {
         if (var1.getClickedBlock().getState() instanceof Sign) {
            SylvLiftPlugin.Lift var2 = this.liftAtInteriorSign(var1.getClickedBlock().getLocation());
            if (var2 != null) {
               var1.setCancelled(true);
               this.openFloorMenu(var1.getPlayer(), var2, 0);
            } else {
               for (SylvLiftPlugin.Lift var4 : this.lifts.values()) {
                  Location var5 = var1.getClickedBlock().getLocation();
                  String var6 = this.key(var5);
                  Integer var7 = var4.landingSigns.get(var6);
                  // Doors become display entities while open. The sign remains
                  // managed even when its physical panes cannot be validated.
                  if (var7 != null) {
                     var1.setCancelled(true);
                     if (var4.moving && var7 == var4.currentFloorY) {
                        return;
                     }
                  }
                  if (var7 == null) {
                     this.registerLandingSign(var4, var5);
                     var7 = var4.landingSigns.get(var6);
                  }

                  if (var7 != null
                     && var7 == this.floorYForSign(var5)
                     && this.isUniqueLandingSignForLift(var4, var5)
                     && this.isSignBesideLandingDoor(var4, var5, var7)) {
                     var1.setCancelled(true);
                     this.setCallSign(var4, var5, true);
                     this.enqueueStop(var4, var7, var1.getPlayer(), var5);
                     return;
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         SylvLiftPlugin.RenameSession var8 = this.renameSessions.get(var2.getUniqueId());
         if (var8 != null && var1.getView() == var8.view) {
            var1.setCancelled(true);
            if (var1.getRawSlot() == 2 && var1.getCurrentItem() != null) {
               this.finishFloorRename(var2, var8);
            }

            return;
         }
      }

      if (var1.getInventory().getHolder() instanceof SylvLiftPlugin.FloorMenu var7) {
         var1.setCancelled(true);
         if (var1.getWhoClicked() instanceof Player var10) {
            if (this.lifts.get(var7.lift.id) == var7.lift && (!var7.edit || var10.hasPermission("sylvlift.edit"))) {
               int var11 = var1.getRawSlot();
               if (var11 == 45 && var7.page > 0) {
                  Bukkit.getScheduler().runTask(this, () -> this.openFloorMenu(var10, var7.lift, var7.page - 1, var7.edit));
               } else if (var11 == 53 && (var7.page + 1) * 45 < var7.lift.floors.size()) {
                  Bukkit.getScheduler().runTask(this, () -> this.openFloorMenu(var10, var7.lift, var7.page + 1, var7.edit));
               } else if (var11 >= 0 && var11 < 45) {
                  int var5 = var7.page * 45 + var11;
                  if (var5 < var7.floorYs.size() && var7.lift.floors.contains(var7.floorYs.get(var5))) {
                     int var6 = var7.floorYs.get(var5);
                     if (var7.edit) {
                        Bukkit.getScheduler().runTask(this, () -> this.openFloorRename(var10, var7.lift, var6, var7.page));
                     } else {
                        this.enqueueStop(var7.lift, var6, var10, null);
                        Bukkit.getScheduler().runTask(this, () -> this.openFloorMenu(var10, var7.lift, var7.page));
                     }
                  }
               }
            } else {
               var10.closeInventory();
            }
         }
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent var1) {
      if (var1.getInventory().getHolder() instanceof SylvLiftPlugin.FloorMenu
         || var1.getWhoClicked() instanceof Player var2
            && this.renameSessions.containsKey(var2.getUniqueId())
            && var1.getView() == this.renameSessions.get(var2.getUniqueId()).view) {
         var1.setCancelled(true);
      }
   }

   @EventHandler
   public void onPrepareAnvil(PrepareAnvilEvent var1) {
      if (var1.getView().getPlayer() instanceof Player var2) {
         SylvLiftPlugin.RenameSession var5 = this.renameSessions.get(var2.getUniqueId());
         if (var5 != null && var1.getView() == var5.view) {
            String var4 = var1.getView().getRenameText();
            if (var4 != null && !var4.trim().isEmpty() && var4.trim().length() <= 50) {
               var1.setResult(this.menuItem(Material.PAPER, var4.trim()));
               var1.getView().setRepairCost(0);
            } else {
               var1.setResult(null);
            }
         }
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent var1) {
      SylvLiftPlugin.RenameSession var2 = this.renameSessions.get(var1.getPlayer().getUniqueId());
      if (var2 != null && var1.getView() == var2.view) {
         var2.view.getTopInventory().clear();
         this.renameSessions.remove(var1.getPlayer().getUniqueId());
      }
   }

   private void createLift(Player var1, Location var2, String var3) {
      if (!var3.matches("[A-Za-z0-9_-]{1,32}")) {
         var1.sendMessage(Component.text("Use an ID of 1-32 letters, numbers, underscores, or hyphens.", NamedTextColor.RED));
         this.namingPlayers.put(var1.getUniqueId(), var2);
         var1.sendMessage(Component.text("Try again by entering an elevator ID in chat.", NamedTextColor.YELLOW));
      } else {
         String var4 = var3.toLowerCase(Locale.ROOT);
         if (this.lifts.containsKey(var4)) {
            var1.sendMessage(Component.text("That elevator ID is already in use.", NamedTextColor.RED));
            this.namingPlayers.put(var1.getUniqueId(), var2);
            var1.sendMessage(Component.text("Try again with a different elevator ID.", NamedTextColor.YELLOW));
         } else {
            Block var5 = var2.getBlock();
            if (var5.getState() instanceof Sign var6) {
               List<PlatformBlock> var9 = this.findLiftBlocks(var2);
               if (var9.isEmpty()) {
                  var1.sendMessage(Component.text("No iron-supported platform blocks were found.", NamedTextColor.RED));
               } else {
                  SylvLiftPlugin.Lift var8 = new SylvLiftPlugin.Lift(var4, var2.getWorld().getUID(), var2, var9, var5.getBlockData().getAsString());
                  var8.currentFloorY = this.floorYForSign(var2);
                  var8.floors.add(var8.currentFloorY);
                  var6.getSide(org.bukkit.block.sign.Side.FRONT).line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
                  var6.getSide(org.bukkit.block.sign.Side.FRONT).line(1, Component.text("Floor 1", NamedTextColor.GRAY));
                  var6.update();
                  this.lifts.put(var4, var8);
                  this.reconcilePlatformOwnership();
                  this.discoverExistingLandingSigns(var8);
                  this.saveLifts();
                  var1.sendMessage(Component.text("Elevator '" + var3 + "' created with " + var8.floors.size() + " floor(s).", NamedTextColor.GREEN));
               }
            }
         }
      }
   }

   private List<SylvLiftPlugin.PlatformBlock> findLiftBlocks(Location var1) {
      Map<AnchorPosition, PlatformBlock> var2 = new HashMap<>();
      World var3 = var1.getWorld();
      int var4 = var1.getBlockY() - 3;

      for (int var5 = var1.getBlockX() - 8; var5 <= var1.getBlockX() + 8; var5++) {
         for (int var6 = var1.getBlockZ() - 8; var6 <= var1.getBlockZ() + 8; var6++) {
            Block var7 = var3.getBlockAt(var5, var4, var6);
            Block var8 = var3.getBlockAt(var5, var4 + 1, var6);
            if (var7.getType() == Material.IRON_BLOCK && !var8.isEmpty()) {
               var2.put(
                  new SylvLiftPlugin.AnchorPosition(var5, var6),
                  new SylvLiftPlugin.PlatformBlock(var5 - var1.getBlockX(), 0, var6 - var1.getBlockZ(), var8.getBlockData().getAsString())
               );
            }
         }
      }

      List<List<AnchorPosition>> var13 = new ArrayList<>();
      Set<AnchorPosition> var14 = new HashSet<>(var2.keySet());

      while (!var14.isEmpty()) {
         SylvLiftPlugin.AnchorPosition var15 = (SylvLiftPlugin.AnchorPosition)var14.iterator().next();
         List<AnchorPosition> var16 = new ArrayList<>();
         Deque<AnchorPosition> var9 = new ArrayDeque<>();
         var9.add(var15);
         var14.remove(var15);

         while (!var9.isEmpty()) {
            SylvLiftPlugin.AnchorPosition var10 = (SylvLiftPlugin.AnchorPosition)var9.removeFirst();
            var16.add(var10);

            for (SylvLiftPlugin.AnchorPosition var12 : List.of(
               new SylvLiftPlugin.AnchorPosition(var10.x + 1, var10.z),
               new SylvLiftPlugin.AnchorPosition(var10.x - 1, var10.z),
               new SylvLiftPlugin.AnchorPosition(var10.x, var10.z + 1),
               new SylvLiftPlugin.AnchorPosition(var10.x, var10.z - 1)
            )) {
               if (var14.remove(var12)) {
                  var9.addLast(var12);
               }
            }
         }

         var13.add(var16);
      }

      return var13.stream()
         .min(
            Comparator.<List<AnchorPosition>>comparingInt(
                  var1x -> var1x.stream()
                     .mapToInt(
                        var1xx -> Math.abs(((SylvLiftPlugin.AnchorPosition)var1xx).x - var1.getBlockX())
                           + Math.abs(((SylvLiftPlugin.AnchorPosition)var1xx).z - var1.getBlockZ())
                     )
                     .min()
                     .orElse(Integer.MAX_VALUE)
               )
               .thenComparing(Comparator.<List<AnchorPosition>>comparingInt(List::size).reversed())
         )
         .orElseGet(List::of)
         .stream()
         .map(var2::get)
         .toList();
   }

   private void reconcilePlatformOwnership() {
      Map<String, Lift> var1 = new HashMap<>();

      for (SylvLiftPlugin.Lift var3 : this.lifts.values()) {
         for (SylvLiftPlugin.PlatformBlock var5 : var3.blocks) {
            String var6 = this.platformPositionKey(var3, var5);
            var1.merge(var6, var3, (var2, var3x) -> this.preferredPlatformOwner(var6, var2, var3x));
         }
      }

      for (SylvLiftPlugin.Lift var8 : this.lifts.values()) {
         Set<String> var9 = new HashSet<>();
         var8.blocks.removeIf(var4 -> {
            String var5x = this.platformPositionKey(var8, var4);
            return !var9.add(var5x) || var1.get(var5x) != var8;
         });
         Set var10 = var8.blocks
            .stream()
            .map(var1x -> var8.signLocation.getBlockX() + var1x.x + "," + (var8.signLocation.getBlockZ() + var1x.z))
            .collect(Collectors.toSet());
         var8.ironAnchors.removeIf(var1x -> !var10.contains(var1x.x + "," + var1x.z));
         var8.platformAvailable = !var8.blocks.isEmpty();
         if (!var8.platformAvailable) {
            this.getLogger().warning("Elevator '" + var8.id + "' has no uniquely owned platform blocks and has been disabled.");
         }
      }
   }

   private String platformPositionKey(SylvLiftPlugin.Lift var1, SylvLiftPlugin.PlatformBlock var2) {
      return var1.worldId + "," + (var1.signLocation.getBlockX() + var2.x) + "," + (var1.signLocation.getBlockZ() + var2.z);
   }

   private SylvLiftPlugin.Lift preferredPlatformOwner(String var1, SylvLiftPlugin.Lift var2, SylvLiftPlugin.Lift var3) {
      String[] var4 = var1.split(",", 3);
      int var5 = Integer.parseInt(var4[1]);
      int var6 = Integer.parseInt(var4[2]);
      int var7 = Math.abs(var5 - var2.signLocation.getBlockX()) + Math.abs(var6 - var2.signLocation.getBlockZ());
      int var8 = Math.abs(var5 - var3.signLocation.getBlockX()) + Math.abs(var6 - var3.signLocation.getBlockZ());
      if (var7 != var8) {
         return var7 < var8 ? var2 : var3;
      } else {
         return var2.id.compareTo(var3.id) <= 0 ? var2 : var3;
      }
   }

   private void registerLandingSign(Location var1) {
      for (SylvLiftPlugin.Lift var3 : this.lifts.values()) {
         this.registerLandingSign(var3, var1);
      }
   }

   private boolean registerLandingSign(SylvLiftPlugin.Lift var1, Location var2) {
      if (!this.isUniqueLandingSignForLift(var1, var2)) {
         return false;
      } else {
         int var3 = this.floorYForSign(var2);
         String var4 = this.key(var2);
         boolean var5 = !var1.landingSigns.containsKey(var4);
         var1.landingSigns.put(var4, var3);
         boolean var6 = !var1.floors.contains(var3);
         if (var6) {
            var1.floors.add(var3);
            var1.floors.sort(Comparator.naturalOrder());
         }

         this.setCallSign(var1, var2, false);
         if (var5 || var6) {
            this.saveLifts();
         }

         return var6;
      }
   }

   private boolean isUniqueLandingSignForLift(SylvLiftPlugin.Lift var1, Location var2) {
      if (var2.getWorld() == null) {
         return false;
      } else {
         int var3 = this.floorYForSign(var2);
         if (var3 >= var2.getWorld().getMinHeight() && var3 < var2.getWorld().getMaxHeight() && var2.getBlock().getBlockData() instanceof WallSign) {
            long var4 = this.lifts
               .values()
               .stream()
               .filter(var1x -> var1x.worldId.equals(var2.getWorld().getUID()))
               .filter(var2x -> this.nearPlatform(var2x, var2))
               .filter(var3x -> this.isSignBesideLandingDoor(var3x, var2, var3))
               .count();
            return var1.worldId.equals(var2.getWorld().getUID())
               && this.nearPlatform(var1, var2)
               && var4 == 1L
               && this.isSignBesideLandingDoor(var1, var2, var3);
         } else {
            return false;
         }
      }
   }

   private void pruneInvalidLandingSigns(SylvLiftPlugin.Lift var1) {
      List<String> var2 = var1.landingSigns
         .keySet()
         .stream()
         .filter(
            var2x -> {
               Location var3 = this.parseLocation(var2x);
               return var3 == null
                  || !(var3.getBlock().getBlockData() instanceof WallSign)
                  || var1.landingSigns.get(var2x) != this.floorYForSign(var3)
                  || !this.nearPlatform(var1, var3)
                  || !this.isUniqueLandingSignForLift(var1, var3)
                  || !this.isSignBesideLandingDoor(var1, var3, var1.landingSigns.get(var2x));
            }
         )
         .toList();
      var2.forEach(var1.landingSigns::remove);
      var1.floors.clear();
      var1.floors.add(var1.currentFloorY);

      for (int var4 : var1.landingSigns.values()) {
         if (!var1.floors.contains(var4)) {
            var1.floors.add(var4);
         }
      }

      var1.floors.sort(Comparator.naturalOrder());
   }

   private int discoverExistingLandingSigns(SylvLiftPlugin.Lift var1) {
      World var2 = Bukkit.getWorld(var1.worldId);
      if (var2 == null) {
         return 0;
      } else {
         int var3 = 0;
         int var4 = var2.getMinHeight() + 2;
         int var5 = var2.getMaxHeight() - 3;
         int var6 = Math.floorDiv(var1.signLocation.getBlockX() - 12, 16);
         int var7 = Math.floorDiv(var1.signLocation.getBlockX() + 12, 16);
         int var8 = Math.floorDiv(var1.signLocation.getBlockZ() - 12, 16);
         int var9 = Math.floorDiv(var1.signLocation.getBlockZ() + 12, 16);

         for (int var10 = var6; var10 <= var7; var10++) {
            for (int var11 = var8; var11 <= var9; var11++) {
               if (var2.isChunkGenerated(var10, var11)) {
                  Chunk var12 = var2.getChunkAt(var10, var11, false);
                  if (var12 != null) {
                     for (BlockState var16 : var12.getTileEntities()) {
                        Location var17 = var16.getLocation();
                        if (var16 instanceof Sign
                           && var17.getBlockY() >= var4
                           && var17.getBlockY() <= var5
                           && Math.abs(var17.getBlockX() - var1.signLocation.getBlockX()) <= 12
                           && Math.abs(var17.getBlockZ() - var1.signLocation.getBlockZ()) <= 12
                           && !this.sameBlock(var17, var1.signLocation)
                           && this.registerLandingSign(var1, var17)) {
                           var3++;
                        }
                     }
                  }
               }
            }
         }

         return var3;
      }
   }

   private boolean isSignBesideLandingDoor(SylvLiftPlugin.Lift var1, Location var2, int var3) {
      World var4 = Bukkit.getWorld(var1.worldId);
      if (var4 == null) {
         return false;
      } else {
         for (Location var6 : this.ringBlocks(var1, var3)) {
            Block var7 = var6.getBlock();
            if (var7.getType() == Material.SMOOTH_STONE && this.isGlassPane(var7.getRelative(0, 2, 0).getType())) {
               int var8 = Math.abs(var7.getX() - var2.getBlockX()) + Math.abs(var7.getZ() - var2.getBlockZ());
               if (var7.getY() + 2 == var2.getBlockY() && var8 >= 1 && var8 <= 2) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private List<Location> ringBlocks(SylvLiftPlugin.Lift var1, int var2) {
      List<Location> var3 = new ArrayList<>();
      World var4 = Bukkit.getWorld(var1.worldId);
      if (var4 == null) {
         return var3;
      } else {
         int var5 = var1.blocks.stream().mapToInt(var0 -> var0.x).min().orElse(0) + var1.signLocation.getBlockX() - 1;
         int var6 = var1.blocks.stream().mapToInt(var0 -> var0.x).max().orElse(0) + var1.signLocation.getBlockX() + 1;
         int var7 = var1.blocks.stream().mapToInt(var0 -> var0.z).min().orElse(0) + var1.signLocation.getBlockZ() - 1;
         int var8 = var1.blocks.stream().mapToInt(var0 -> var0.z).max().orElse(0) + var1.signLocation.getBlockZ() + 1;
         if (var5 == var6) {
            var5--;
            var6++;
         }

         if (var7 == var8) {
            var7--;
            var8++;
         }

         for (int var9 = var5; var9 <= var6; var9++) {
            for (int var10 = var7; var10 <= var8; var10++) {
               if (var9 == var5 || var9 == var6 || var10 == var7 || var10 == var8) {
                  var3.add(new Location(var4, var9, var2, var10));
               }
            }
         }

         return var3;
      }
   }

   private boolean nearPlatform(SylvLiftPlugin.Lift var1, Location var2) {
      return Math.abs(var2.getBlockX() - var1.signLocation.getBlockX()) <= 12 && Math.abs(var2.getBlockZ() - var1.signLocation.getBlockZ()) <= 12;
   }

   private void setCallSign(SylvLiftPlugin.Lift var1, Location var2, boolean var3) {
      if (var2.getBlock().getState() instanceof Sign var4) {
         var4.getSide(org.bukkit.block.sign.Side.FRONT).line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
         var4.getSide(org.bukkit.block.sign.Side.FRONT).line(1, Component.text("■", var3 ? NamedTextColor.RED : NamedTextColor.BLACK));
         var4.update();
      }
   }

   private void openFloorMenu(Player var1, SylvLiftPlugin.Lift var2, int var3) {
      this.openFloorMenu(var1, var2, var3, false);
   }

   private void openFloorMenu(Player var1, SylvLiftPlugin.Lift var2, int var3, boolean var4) {
      if (var1.isOnline() && this.lifts.get(var2.id) == var2 && (!var4 || var1.hasPermission("sylvlift.edit"))) {
         SylvLiftPlugin.FloorMenu var5 = new SylvLiftPlugin.FloorMenu(var2, var3, var4);
         Inventory var6 = Bukkit.createInventory(var5, 54, Component.text((var4 ? "Edit floors: " : "Elevator ") + var2.id));
         int var7 = var3 * 45;

         for (int var8 = var7; var8 < Math.min(var7 + 45, var5.floorYs.size()); var8++) {
            int var9 = var5.floorYs.get(var8);
            boolean var10 = this.isQueued(var2, var9);
            Material var11 = var9 == var2.currentFloorY ? Material.LIME_CONCRETE : (var10 ? Material.BLUE_CONCRETE : Material.IRON_INGOT);
            ItemStack var12 = new ItemStack(var11);
            ItemMeta var13 = var12.getItemMeta();
            var13.displayName(Component.text(this.floorName(var2, var9), NamedTextColor.GOLD));
            String var14 = var4 ? "Click to rename" : (var9 == var2.currentFloorY ? "Current floor" : (var10 ? "Already queued" : "Click to add a stop"));
            var13.lore(List.of(Component.text(var14, NamedTextColor.GRAY)));
            var12.setItemMeta(var13);
            var6.setItem(var8 - var7, var12);
         }

         if (var3 > 0) {
            var6.setItem(45, this.menuItem(Material.ARROW, "Previous floors"));
         }

         if (var7 + 45 < var2.floors.size()) {
            var6.setItem(53, this.menuItem(Material.ARROW, "More floors"));
         }

         var1.openInventory(var6);
      }
   }

   private void openFloorRename(Player var1, SylvLiftPlugin.Lift var2, int var3, int var4) {
      if (var1.isOnline() && var1.hasPermission("sylvlift.edit") && this.lifts.get(var2.id) == var2 && var2.floors.contains(var3)) {
         AnvilView var5 = org.bukkit.inventory.MenuType.ANVIL.builder().checkReachable(false).build(var1);
         var1.openInventory(var5);
         if (var1.getOpenInventory().getTopInventory().equals(var5.getTopInventory())) {
            SylvLiftPlugin.RenameSession var7 = new SylvLiftPlugin.RenameSession(var2, var3, var4, var5);
            this.renameSessions.put(var1.getUniqueId(), var7);
            var5.getTopInventory().setItem(0, this.menuItem(Material.PAPER, this.floorName(var2, var3)));
         } else {
            var1.sendMessage(Component.text("Could not open the floor rename screen.", NamedTextColor.RED));
         }
      }
   }

   private void finishFloorRename(Player var1, SylvLiftPlugin.RenameSession var2) {
      String var3 = var2.view.getRenameText();
      if (!var1.hasPermission("sylvlift.edit") || this.lifts.get(var2.lift.id) != var2.lift || !var2.lift.floors.contains(var2.floorY)) {
         var1.sendMessage(Component.text("This floor can no longer be edited.", NamedTextColor.RED));
         Bukkit.getScheduler().runTask(this, () -> var1.closeInventory());
      } else if (var3 != null && !var3.trim().isEmpty() && var3.trim().length() <= 50) {
         var2.lift.floorNames.put(var2.floorY, var3.trim());
         this.saveLifts();
         if (!var2.lift.moving && var2.lift.currentFloorY == var2.floorY) {
            this.updateInteriorSign(var2.lift);
         }

         var1.sendMessage(Component.text("Floor renamed to " + var3.trim() + ".", NamedTextColor.GREEN));
         Bukkit.getScheduler().runTask(this, () -> this.openFloorMenu(var1, var2.lift, var2.page, true));
      } else {
         var1.sendMessage(Component.text("Floor names must be 1-50 characters.", NamedTextColor.RED));
      }
   }

   private String floorName(SylvLiftPlugin.Lift var1, int var2) {
      return var1.floorNames.getOrDefault(var2, "Floor " + (var1.floors.indexOf(var2) + 1));
   }

   private ItemStack menuItem(Material var1, String var2) {
      ItemStack var3 = new ItemStack(var1);
      ItemMeta var4 = var3.getItemMeta();
      var4.displayName(Component.text(var2, NamedTextColor.YELLOW));
      var3.setItemMeta(var4);
      return var3;
   }

   private boolean isQueued(SylvLiftPlugin.Lift var1, int var2) {
      return var1.activeStop != null && var1.activeStop.floorY == var2 || var1.stops.stream().anyMatch(var1x -> var1x.floorY == var2);
   }

   private void enqueueStop(SylvLiftPlugin.Lift var1, int var2, Player var3, Location var4) {
      if (!var1.platformAvailable) {
         var3.sendMessage(Component.text("This elevator has no uniquely owned platform blocks and cannot move.", NamedTextColor.RED));
      } else if (var2 == var1.currentFloorY && !var1.moving && var1.stops.isEmpty()) {
         if (var4 != null) {
            this.setCallSign(var1, var4, false);
         }

         var1.moving = true;
         this.openDoors(var1, Bukkit.getWorld(var1.worldId), var1.currentFloorY);
      } else {
         SylvLiftPlugin.StopRequest var5 = var1.activeStop != null && var1.activeStop.floorY == var2
            ? var1.activeStop
            : var1.stops.stream().filter(var1x -> var1x.floorY == var2).findFirst().orElse(null);
         if (var5 != null) {
            var5.addCallSign(var4);
         } else {
            var1.stops.addLast(new SylvLiftPlugin.StopRequest(var2, var3.getUniqueId(), var4));
            if (!var1.moving) {
               var1.moving = true;
               this.beginNextStop(var1);
            }
         }
      }
   }

   private void beginNextStop(final SylvLiftPlugin.Lift var1) {
      final SylvLiftPlugin.StopRequest var2 = var1.stops.pollFirst();
      if (var2 == null) {
         var1.moving = false;
         var1.activeTask = null;
      } else {
         var1.activeStop = var2;
         final World var3 = Bukkit.getWorld(var1.worldId);
         if (var3 != null) {
            this.restoreIronAnchors(var1, var3);
         }

         if (var2.floorY == var1.currentFloorY) {
            this.finishStop(var1);
         } else {
            Player var4 = Bukkit.getPlayer(var2.callerId);
            if (var3 == null) {
               this.failStop(var1, var2, var4, "Elevator world is unavailable.");
            } else {
               final int var5 = var2.floorY - var1.currentFloorY;
               int var6 = var1.currentFloorY;
               var1.lastTravelDirection = Integer.signum(var5);

               for (SylvLiftPlugin.PlatformBlock var8 : var1.blocks) {
                  Block var9 = var3.getBlockAt(var1.signLocation.getBlockX() + var8.x, var2.floorY + var8.y, var1.signLocation.getBlockZ() + var8.z);
                  if (!var9.isEmpty() && !var9.getBlockData().getAsString().equals(var8.data)) {
                     var9.setType(Material.AIR, false);
                  }
               }

               final Location var16 = var1.signLocation.clone().add(0.0, var5, 0.0);
               Block var17 = var16.getBlock();
               if (var17.getState() instanceof Sign var18
                  && this.plain(var18.getSide(org.bukkit.block.sign.Side.FRONT).line(0)).equalsIgnoreCase("[Elevator]")
                  && (this.plain(var18.getSide(org.bukkit.block.sign.Side.FRONT).line(1)).startsWith("Floor ") || var1.floorNames.containsValue(this.plain(var18.getSide(org.bukkit.block.sign.Side.FRONT).line(1))))
                  && this.liftAtInteriorSign(var16) == null
                  && !var1.landingSigns.containsKey(this.key(var16))) {
                  var17.setType(Material.AIR, false);
               }

               if (!var17.isEmpty()) {
                  this.failStop(var1, var2, var4, "The destination control-sign space is obstructed.");
               } else {
                  final Location var19 = var1.signLocation.clone();
                  final List<BlockDisplay> var20 = new ArrayList<>();
                  final List<Player> var11 = this.riders(var1, var3);
                  for (Player rider : var11) {
                     var1.riders.put(rider.getUniqueId(), new Rider(rider,
                        rider.getY() - var1.currentFloorY, rider.hasGravity()));
                     rider.setGravity(false);
                     rider.setFallDistance(0);
                  }

                  for (SylvLiftPlugin.PlatformBlock var13 : var1.blocks) {
                     Block var14 = var3.getBlockAt(var19.getBlockX() + var13.x, var1.currentFloorY + var13.y, var19.getBlockZ() + var13.z);
                     var14.setType(Material.AIR);
                     BlockDisplay var15 = (BlockDisplay)var3.spawn(var14.getLocation(), BlockDisplay.class);
                     var15.setBlock(Bukkit.createBlockData(var13.data));
                     var15.setTeleportDuration(1);
                     var20.add(var15);
                     var1.activeDisplays.add(var15);
                  }

                  Block var21 = var19.getBlock();
                  final BlockDisplay var22 = (BlockDisplay)var3.spawn(var19, BlockDisplay.class);
                  var22.setBlock(Bukkit.createBlockData(var1.signData));
                  var22.setTeleportDuration(1);
                  var21.setType(Material.AIR);
                  var1.activeDisplays.add(var22);
                  final int var23 = Math.max(20, Math.abs(var5) * 4);
                  var1.activeTask = (new BukkitRunnable() {
                     private int step;

                     {
                        Objects.requireNonNull(SylvLiftPlugin.this);
                     }

                     public void run() {
                        this.step++;
                        double progress = (double) step / var23;
                        double previous = (double) (step - 1) / var23;
                        double offset = var5 * progress * progress * (3 - 2 * progress);
                        double previousOffset = var5 * previous * previous * (3 - 2 * previous);
                        double var1x = offset - previousOffset;

                        for (BlockDisplay var4x : var20) {
                           var4x.teleport(var4x.getLocation().add(0.0, var1x, 0.0));
                        }

                        var22.teleport(var22.getLocation().add(0.0, var1x, 0.0));

                for (Rider state : List.copyOf(var1.riders.values())) {
                    Player rider = state.player;
                    boolean abovePlatform = var1.blocks.stream().anyMatch(block ->
                            rider.getX() >= var19.getBlockX() + block.x - 0.3
                                    && rider.getX() <= var19.getBlockX() + block.x + 1.3
                                    && rider.getZ() >= var19.getBlockZ() + block.z - 0.3
                                    && rider.getZ() <= var19.getBlockZ() + block.z + 1.3);
                    if (!rider.isOnline() || rider.isDead() || !rider.getWorld().equals(var3)
                            || !abovePlatform) {
                        releaseRider(var1, state, var1.currentFloorY, false);
                        continue;
                    }
                    // Velocity leaves the client's mouse movement alone. Correct drift
                    // gently instead of sending a position/rotation teleport every tick.
                    double targetY = var1.currentFloorY + state.height + offset;
                    double correction = Math.clamp((targetY - var1x - rider.getY()) * 0.25, -0.1, 0.1);
                    Vector velocity = rider.getVelocity();
                    rider.setVelocity(velocity.setY(var1x + correction));
                    rider.setFallDistance(0);
                }

                        if (this.step >= var23) {
                           for (int var7 = 0; var7 < var1.blocks.size(); var7++) {
                              SylvLiftPlugin.PlatformBlock var10 = var1.blocks.get(var7);
                              Block var5x = var3.getBlockAt(var19.getBlockX() + var10.x, var2.floorY + var10.y, var19.getBlockZ() + var10.z);
                              var5x.setBlockData(Bukkit.createBlockData(var10.data), true);
                              ((BlockDisplay)var20.get(var7)).remove();
                           }

                           var1.currentFloorY = var2.floorY;
                           var1.signLocation = var16;
                           var1.signLocation.getBlock().setBlockData(Bukkit.createBlockData(var1.signData), false);
                           SylvLiftPlugin.this.restoreIronAnchors(var1, var3);

                           releaseRiders(var1, var2.floorY, true);

                           var22.remove();
                           var1.activeDisplays.clear();
                           var1.activeTask = null;
                           SylvLiftPlugin.this.updateInteriorSign(var1);
                           SylvLiftPlugin.this.saveLifts();
                           this.cancel();
                           SylvLiftPlugin.this.finishStop(var1);
                        }
                     }
                  }).runTaskTimer(this, 1L, 1L);
               }
            }
         }
      }
   }

   private void failStop(SylvLiftPlugin.Lift var1, SylvLiftPlugin.StopRequest var2, Player var3, String var4) {
      if (var3 != null) {
         var3.sendMessage(Component.text(var4, NamedTextColor.RED));
      }

      var2.callSigns.forEach(var2x -> this.setCallSign(var1, var2x, false));
      var1.activeStop = null;
      Bukkit.getScheduler().runTaskLater(this, () -> this.beginNextStop(var1), 20L);
   }

    private void releaseRiders(Lift lift, int floorY, boolean land) {
        for (Rider rider : List.copyOf(lift.riders.values())) {
            releaseRider(lift, rider, floorY, land);
        }
    }

    private void releaseRider(Lift lift, Rider state, int floorY, boolean land) {
        Player player = state.player;
        lift.riders.remove(player.getUniqueId());
        player.setGravity(state.gravity);
        player.setFallDistance(0);
        player.setVelocity(player.getVelocity().setY(0));
        if (land && player.isOnline() && !player.isDead()
                && player.getWorld().getUID().equals(lift.worldId)) {
            Location landing = player.getLocation();
            landing.setY(floorY + state.height);
            // Only settle once at arrival, retaining the player's current view.
            player.teleport(landing);
        }
    }

    @EventHandler
    public void onRiderQuit(PlayerQuitEvent event) {
        releasePlayer(event.getPlayer(), false);
    }

    @EventHandler
    public void onRiderDeath(PlayerDeathEvent event) {
        releasePlayer(event.getEntity(), false);
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onRiderTeleport(PlayerTeleportEvent event) {
        releasePlayer(event.getPlayer(), false);
    }

    private void releasePlayer(Player player, boolean land) {
        for (Lift lift : lifts.values()) {
            Rider rider = lift.riders.get(player.getUniqueId());
            if (rider != null) {
                releaseRider(lift, rider, lift.currentFloorY, land);
            }
        }
    }


   private void restoreIronAnchors(SylvLiftPlugin.Lift var1, World var2) {
      for (SylvLiftPlugin.IronAnchor var4 : var1.ironAnchors) {
         var2.getBlockAt(var4.x, var4.y, var4.z).setType(Material.IRON_BLOCK, false);
      }
   }

   private void finishStop(SylvLiftPlugin.Lift var1) {
      SylvLiftPlugin.StopRequest var2 = var1.activeStop;
      if (var2 != null) {
         var2.callSigns.forEach(var2x -> this.setCallSign(var1, var2x, false));
      }

      var1.activeStop = null;
      this.openDoors(var1, Bukkit.getWorld(var1.worldId), var1.currentFloorY);
   }

   private List<Player> riders(SylvLiftPlugin.Lift var1, World var2) {
      int var3 = var1.blocks.stream().mapToInt(var0 -> var0.x).min().orElse(0) + var1.signLocation.getBlockX();
      int var4 = var1.blocks.stream().mapToInt(var0 -> var0.x).max().orElse(0) + var1.signLocation.getBlockX() + 1;
      int var5 = var1.blocks.stream().mapToInt(var0 -> var0.z).min().orElse(0) + var1.signLocation.getBlockZ();
      int var6 = var1.blocks.stream().mapToInt(var0 -> var0.z).max().orElse(0) + var1.signLocation.getBlockZ() + 1;
      return var2.getPlayers()
         .stream()
         .filter(
            var5x -> {
               Location var6x = var5x.getLocation();
               return var6x.getY() >= var1.currentFloorY + 0.5
                  && var6x.getY() <= var1.currentFloorY + 1.5
                  && var6x.getX() >= var3
                  && var6x.getX() <= var4
                  && var6x.getZ() >= var5
                  && var6x.getZ() <= var6;
            }
         )
         .toList();
   }

   private void openDoors(final SylvLiftPlugin.Lift var1, World var2, int var3) {
      if (var2 == null) {
         var1.lastTravelDirection = 0;
         var1.activeTask = Bukkit.getScheduler().runTaskLater(this, () -> this.beginNextStop(var1), 20L);
      } else {
         SylvLiftPlugin.DoorAssembly var4 = this.findLandingDoors(var1, var2, var3);
         if (var4 == null) {
            var1.lastTravelDirection = 0;
            var1.activeTask = Bukkit.getScheduler().runTaskLater(this, () -> this.beginNextStop(var1), 20L);
         } else {
            int var5 = var1.lastTravelDirection;
            var1.lastTravelDirection = 0;
            this.playArrivalDing(var5, var2, var1.signLocation, var3);
            var1.moving = true;
            final List<Block> var6 = var4.leaves.stream().flatMap(var0 -> var0.blocks.stream()).toList();
            final List<BlockDisplay> var7 = new ArrayList<>();
            final List<BlockData> var8 = new ArrayList<>();
            final List<Location> var9 = new ArrayList<>();
            final List<Vector> var10 = new ArrayList<>();

            for (SylvLiftPlugin.DoorLeaf var12 : var4.leaves) {
               for (Block var14 : var12.blocks) {
                  BlockDisplay var15 = (BlockDisplay)var2.spawn(var14.getLocation(), BlockDisplay.class);
                  BlockData var16 = var14.getBlockData();
                  var15.setBlock(var16);
                  var15.setTeleportDuration(1);
                  var14.setType(Material.AIR, false);
                  var7.add(var15);
                  var8.add(var16);
                  var9.add(var14.getLocation());
                  var10.add(var12.direction.clone());
                  var1.activeDisplays.add(var15);
               }
            }

            var1.activeTask = (new BukkitRunnable() {
               private int tick;

               {
                  Objects.requireNonNull(SylvLiftPlugin.this);
               }

               public void run() {
                  this.tick++;
                  if (this.tick <= 10 || this.tick > 210) {
                     double var1x = this.tick <= 10 ? this.tick / 10.0 : (220 - this.tick) / 10.0;

                     for (int var3x = 0; var3x < var7.size(); var3x++) {
                        Location var4x = (Location)var9.get(var3x);
                        ((BlockDisplay)var7.get(var3x)).teleport(var4x.clone().add(((Vector)var10.get(var3x)).clone().multiply(var1x)));
                     }
                  }

                  if (this.tick >= 220) {
                     for (int var5x = 0; var5x < var6.size(); var5x++) {
                        ((Block)var6.get(var5x)).setBlockData((BlockData)var8.get(var5x), false);
                        ((BlockDisplay)var7.get(var5x)).remove();
                        var1.activeDisplays.remove(var7.get(var5x));
                     }

                     this.cancel();
                     var1.activeTask = Bukkit.getScheduler().runTaskLater(SylvLiftPlugin.this, () -> SylvLiftPlugin.this.beginNextStop(var1), 20L);
                  }
               }
            }).runTaskTimer(this, 1L, 1L);
         }
      }
   }

   private SylvLiftPlugin.DoorAssembly findLandingDoors(SylvLiftPlugin.Lift var1, World var2, int var3) {
      if (var2 != null && var1.worldId.equals(var2.getUID())) {
         Map<DoorEdge, List<DoorPanel>> var4 = new HashMap<>();
         int var5 = this.panelsRingMin(var1, var3, true);
         int var6 = this.panelsRingMax(var1, var3, true);
         int var7 = this.panelsRingMin(var1, var3, false);
         int var8 = this.panelsRingMax(var1, var3, false);

         for (Location var10 : this.ringBlocks(var1, var3)) {
            Block var11 = var10.getBlock();
            if (var11.getType() == Material.SMOOTH_STONE) {
               List<Block> var12 = new ArrayList<>(3);
               boolean var13 = true;

               for (int var14 = 1; var14 <= 3; var14++) {
                  Block var15 = var11.getRelative(0, var14, 0);
                  if (!this.isGlassPane(var15.getType())) {
                     var13 = false;
                     break;
                  }

                  var12.add(var15);
               }

               if (var13) {
                  boolean var22 = var11.getZ() == var7 || var11.getZ() == var8;
                  SylvLiftPlugin.DoorEdge var24 = new SylvLiftPlugin.DoorEdge(var22, var22 ? var11.getZ() : var11.getX());
                  var4.computeIfAbsent(var24, var0 -> new ArrayList<>()).add(new SylvLiftPlugin.DoorPanel(var11.getX(), var11.getZ(), var12));
               }
            }
         }

         List<DoorLeaf> var17 = new ArrayList<>();

         for (Entry<DoorEdge, List<DoorPanel>> var19 : var4.entrySet()) {
            SylvLiftPlugin.DoorEdge var20 = (SylvLiftPlugin.DoorEdge)var19.getKey();
            List<DoorPanel> var21 = var19.getValue();
            var21.sort(Comparator.comparingInt(var1x -> var20.horizontal ? var1x.x : var1x.z));
            List<DoorPanel> var23 = new ArrayList<>();

            for (SylvLiftPlugin.DoorPanel var16 : var21) {
               if (!var23.isEmpty() && this.panelCoordinate(var16, var20) != this.panelCoordinate((SylvLiftPlugin.DoorPanel)var23.getLast(), var20) + 1) {
                  this.addAuthorizedDoorCluster(var1, var3, var20, var23, var17);
                  var23.clear();
               }

               var23.add(var16);
            }

            this.addAuthorizedDoorCluster(var1, var3, var20, var23, var17);
         }

         return var17.isEmpty() ? null : new SylvLiftPlugin.DoorAssembly(var17);
      } else {
         return null;
      }
   }

   private int panelCoordinate(SylvLiftPlugin.DoorPanel var1, SylvLiftPlugin.DoorEdge var2) {
      return var2.horizontal ? var1.x : var1.z;
   }

   private void addAuthorizedDoorCluster(
      SylvLiftPlugin.Lift var1, int var2, SylvLiftPlugin.DoorEdge var3, List<SylvLiftPlugin.DoorPanel> var4, List<SylvLiftPlugin.DoorLeaf> var5
   ) {
      if (!var4.isEmpty() && !var4.stream().noneMatch(var3x -> this.hasRegisteredSignBesideRing(var1, var3x, var2))) {
         int var6 = var4.size() / 2;

         for (int var7 = 0; var7 < var4.size(); var7++) {
            int var8 = var4.size() != 1 && var7 < var6 ? -1 : 1;
            SylvLiftPlugin.DoorPanel var9 = (SylvLiftPlugin.DoorPanel)var4.get(var7);
            Vector var10 = var3.horizontal ? new Vector(var8, 0, 0) : new Vector(0, 0, var8);
            var5.add(new SylvLiftPlugin.DoorLeaf(var9.blocks, var10));
         }
      }
   }

   private boolean hasRegisteredSignBesideRing(SylvLiftPlugin.Lift var1, SylvLiftPlugin.DoorPanel var2, int var3) {
      return var1.landingSigns
         .entrySet()
         .stream()
         .anyMatch(
            var4 -> {
               if (var4.getValue() != var3) {
                  return false;
               } else {
                  Location var5 = this.parseLocation(var4.getKey());
                  int var6 = var5 == null ? Integer.MAX_VALUE : Math.abs(var5.getBlockX() - var2.x) + Math.abs(var5.getBlockZ() - var2.z);
                  return var5 != null
                     && var5.getWorld().getUID().equals(var1.worldId)
                     && var5.getBlockY() == var3 + 2
                     && var5.getBlock().getBlockData() instanceof WallSign
                     && var6 >= 1
                     && var6 <= 2
                     && this.isUniqueLandingSignForLift(var1, var5);
               }
            }
         );
   }

   private boolean isGlassPane(Material var1) {
      return var1 == Material.GLASS_PANE || var1.name().endsWith("_STAINED_GLASS_PANE");
   }

   private int panelsRingMin(SylvLiftPlugin.Lift var1, int var2, boolean var3) {
      return this.ringBlocks(var1, var2).stream().mapToInt(var1x -> var3 ? var1x.getBlockX() : var1x.getBlockZ()).min().orElse(0);
   }

   private int panelsRingMax(SylvLiftPlugin.Lift var1, int var2, boolean var3) {
      return this.ringBlocks(var1, var2).stream().mapToInt(var1x -> var3 ? var1x.getBlockX() : var1x.getBlockZ()).max().orElse(0);
   }

   private void removeControlInteractions(SylvLiftPlugin.Lift var1) {
      World var2 = Bukkit.getWorld(var1.worldId);
      if (var2 != null) {
         Location var3 = var1.signLocation.clone().add(0.5, 0.0, 0.5);

         for (Interaction var5 : var2.getEntitiesByClass(Interaction.class)) {
            if (var5.getScoreboardTags().contains(this.controlInteractionTag(var1)) || var5.getLocation().distanceSquared(var3) < 0.01) {
               var5.remove();
            }
         }
      }
   }

   private String controlInteractionTag(SylvLiftPlugin.Lift var1) {
      return "sylvlift-control-" + var1.id;
   }

   private void removeFloorMapDisplays(SylvLiftPlugin.Lift var1) {
      List<Integer> var2 = new ArrayList<>();
      if (var1.floorMapId != null) {
         var2.add(var1.floorMapId);
      }

      if (var1.floorMapRightId != null) {
         var2.add(var1.floorMapRightId);
      }

      World var3 = Bukkit.getWorld(var1.worldId);
      if (var3 != null && !var2.isEmpty()) {
         for (ItemFrame var5 : var3.getEntitiesByClass(ItemFrame.class)) {
            ItemStack var6 = var5.getItem();
            if (var6.getType() == Material.FILLED_MAP
               && var6.getItemMeta() instanceof MapMeta var7
               && var7.hasMapView()
               && var2.contains(var7.getMapView().getId())) {
               var5.remove();
            }
         }
      }

      for (Integer var10 : var2) {
         MapView var11 = Bukkit.getMap(var10);
         if (var11 != null) {
            List.copyOf(var11.getRenderers()).forEach(var11::removeRenderer);
         }
      }

      var1.floorMapId = null;
      var1.floorMapRightId = null;
   }

   private void playArrivalDing(int var1, World var2, Location var3, int var4) {
      if (var1 != 0) {
         Location var5 = var3.clone();
         var5.setY(var4 + 1);
         var2.playSound(var5, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0F, 1.0F);
         if (var1 < 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> var2.playSound(var5, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0F, 1.0F), 6L);
         }
      }
   }

   private void updateInteriorSign(SylvLiftPlugin.Lift var1) {
      Block var2 = var1.signLocation.getBlock();
      if (var2.getState() instanceof Sign var3) {
         var3.getSide(org.bukkit.block.sign.Side.FRONT).line(0, Component.text("[Elevator]", NamedTextColor.DARK_GREEN));
         var3.getSide(org.bukkit.block.sign.Side.FRONT).line(1, Component.text(this.floorName(var1, var1.currentFloorY), NamedTextColor.GRAY));
         var3.update();
      }
   }

   private SylvLiftPlugin.Lift liftAtInteriorSign(Location var1) {
      return this.lifts.values().stream().filter(var2 -> this.sameBlock(var2.signLocation, var1)).findFirst().orElse(null);
   }

   private void saveLifts() {
      this.getConfig().set("lifts", null);

      for (SylvLiftPlugin.Lift var2 : this.lifts.values()) {
         String var3 = "lifts." + var2.id;
         this.getConfig().set(var3 + ".world", var2.worldId.toString());
         this.getConfig().set(var3 + ".sign", this.key(var2.signLocation));
         this.getConfig().set(var3 + ".sign-data", var2.signData);
         this.getConfig().set(var3 + ".current", var2.currentFloorY);
         this.getConfig().set(var3 + ".floor-map-id", null);
         this.getConfig().set(var3 + ".floor-map-right-id", null);
         this.getConfig().set(var3 + ".floors", var2.floors);

         for (Entry<Integer, String> var5 : var2.floorNames.entrySet()) {
            this.getConfig().set(var3 + ".floor-names." + var5.getKey(), var5.getValue());
         }

         this.getConfig().set(var3 + ".blocks", var2.blocks.stream().map(var0 -> var0.x + "," + var0.y + "," + var0.z + "|" + var0.data).toList());
         this.getConfig().set(var3 + ".iron-anchors", var2.ironAnchors.stream().map(var0 -> var0.x + "," + var0.y + "," + var0.z).toList());
         this.getConfig().set(var3 + ".landings", var2.landingSigns.entrySet().stream().map(var0 -> var0.getKey() + "|" + var0.getValue()).toList());
      }

      this.saveConfig();
   }

   private void loadLifts() {
      for (String var2 : this.getConfig().getConfigurationSection("lifts") == null
         ? List.<String>of()
         : this.getConfig().getConfigurationSection("lifts").getKeys(false)) {
         String var3 = "lifts." + var2;
         UUID var4 = UUID.fromString(this.getConfig().getString(var3 + ".world"));
         Location var5 = this.parseLocation(this.getConfig().getString(var3 + ".sign"));
         if (var5 != null) {
            var5.setWorld(Bukkit.getWorld(var4));
            if (var5.getWorld() != null) {
               List<PlatformBlock> var6 = new ArrayList<>();

               for (String var8 : this.getConfig().getStringList(var3 + ".blocks")) {
                  String[] var9 = var8.split("\\|", 2);
                  String[] var10 = var9[0].split(",");
                  var6.add(new SylvLiftPlugin.PlatformBlock(Integer.parseInt(var10[0]), Integer.parseInt(var10[1]), Integer.parseInt(var10[2]), var9[1]));
               }

               SylvLiftPlugin.Lift var14 = new SylvLiftPlugin.Lift(
                  var2, var4, var5, var6, this.getConfig().getString(var3 + ".sign-data", "oak_sign[rotation=0]")
               );
               var14.currentFloorY = this.getConfig().getInt(var3 + ".current");
               if (this.getConfig().contains(var3 + ".floor-map-id")) {
                  var14.floorMapId = this.getConfig().getInt(var3 + ".floor-map-id");
               }

               if (this.getConfig().contains(var3 + ".floor-map-right-id")) {
                  var14.floorMapRightId = this.getConfig().getInt(var3 + ".floor-map-right-id");
               }

               var14.floors.addAll(this.getConfig().getIntegerList(var3 + ".floors"));
               ConfigurationSection var15 = this.getConfig().getConfigurationSection(var3 + ".floor-names");
               if (var15 != null) {
                  for (String var18 : var15.getKeys(false)) {
                     try {
                        String var11 = var15.getString(var18);
                        if (var11 != null && !var11.isBlank()) {
                           var14.floorNames.put(Integer.parseInt(var18), var11);
                        }
                     } catch (NumberFormatException var13) {
                        this.getLogger().warning("Invalid floor height '" + var18 + "' for elevator '" + var2 + "'.");
                     }
                  }
               }

               List<String> var17 = this.getConfig().getStringList(var3 + ".iron-anchors");
               if (!var17.isEmpty()) {
                  var14.ironAnchors.clear();
               }

               for (String var22 : var17) {
                  String[] var12 = var22.split(",");
                  var14.ironAnchors.add(new SylvLiftPlugin.IronAnchor(Integer.parseInt(var12[0]), Integer.parseInt(var12[1]), Integer.parseInt(var12[2])));
               }

               if (var14.ironAnchors.isEmpty()) {
                  int var20 = var14.floors.stream().mapToInt(Integer::intValue).min().orElse(var14.currentFloorY) - 1;

                  for (SylvLiftPlugin.PlatformBlock var25 : var6) {
                     var14.ironAnchors.add(new SylvLiftPlugin.IronAnchor(var5.getBlockX() + var25.x, var20, var5.getBlockZ() + var25.z));
                  }
               }

               for (String var24 : this.getConfig().getStringList(var3 + ".landings")) {
                  String[] var26 = var24.split("\\|", 2);
                  var14.landingSigns.put(var26[0], Integer.parseInt(var26[1]));
               }

               this.lifts.put(var2, var14);
            }
         }
      }
   }

   private Location parseLocation(String var1) {
      if (var1 == null) {
         return null;
      } else {
         String[] var2 = var1.split(",");
         World var3 = Bukkit.getWorld(UUID.fromString(var2[0]));
         return var3 == null ? null : new Location(var3, Integer.parseInt(var2[1]), Integer.parseInt(var2[2]), Integer.parseInt(var2[3]));
      }
   }

   private String key(Location var1) {
      return var1.getWorld().getUID() + "," + var1.getBlockX() + "," + var1.getBlockY() + "," + var1.getBlockZ();
   }

   private int floorYForSign(Location var1) {
      return var1.getBlockY() - 2;
   }

   private boolean sameBlock(Location var1, Location var2) {
      return var1.getWorld().getUID().equals(var2.getWorld().getUID())
         && var1.getBlockX() == var2.getBlockX()
         && var1.getBlockY() == var2.getBlockY()
         && var1.getBlockZ() == var2.getBlockZ();
   }

   private String plain(Component var1) {
      return var1 == null ? "" : PLAIN.serialize(var1);
   }

   private record AnchorPosition(int x, int z) {
   }

   private record DoorAssembly(List<SylvLiftPlugin.DoorLeaf> leaves) {
   }

   private record DoorEdge(boolean horizontal, int coordinate) {
   }

   private record DoorLeaf(List<Block> blocks, Vector direction) {
   }

   private record DoorPanel(int x, int z, List<Block> blocks) {
   }

   private final class FloorMenu implements InventoryHolder {
      private final SylvLiftPlugin.Lift lift;
      private final int page;
      private final boolean edit;
      private final List<Integer> floorYs;

      private FloorMenu(SylvLiftPlugin.Lift nullx, int nullxx, boolean nullxxx) {
         Objects.requireNonNull(SylvLiftPlugin.this);
         super();
         this.lift = nullx;
         this.page = nullxx;
         this.edit = nullxxx;
         this.floorYs = List.copyOf(nullx.floors);
      }

      public Inventory getInventory() {
         return null;
      }
   }

   private record IronAnchor(int x, int y, int z) {
   }

   private static final class Lift {
      private final String id;
      private final UUID worldId;
      private Location signLocation;
      private final List<SylvLiftPlugin.PlatformBlock> blocks;
      private final List<SylvLiftPlugin.IronAnchor> ironAnchors = new ArrayList<>();
      private String signData;
      private final List<Integer> floors = new ArrayList<>();
      private final Map<Integer, String> floorNames = new HashMap<>();
      private final Map<String, Integer> landingSigns = new HashMap<>();
      private final Deque<SylvLiftPlugin.StopRequest> stops = new ArrayDeque<>();
      private final List<BlockDisplay> activeDisplays = new ArrayList<>();
      private final Map<UUID, Rider> riders = new HashMap<>();
      private BukkitTask activeTask;
      private SylvLiftPlugin.StopRequest activeStop;
      private Integer floorMapId;
      private Integer floorMapRightId;
      private int currentFloorY;
      private int lastTravelDirection;
      private boolean moving;
      private boolean platformAvailable = true;

      private Lift(String var1, UUID var2, Location var3, List<SylvLiftPlugin.PlatformBlock> var4, String var5) {
         this.id = var1;
         this.worldId = var2;
         this.signLocation = var3;
         // Discovery uses Stream.toList(), which is immutable. Ownership cleanup
         // must be able to remove blocks shared with another lift.
         this.blocks = new ArrayList<>(var4);
         this.signData = var5;

         for (SylvLiftPlugin.PlatformBlock var7 : var4) {
            this.ironAnchors.add(new SylvLiftPlugin.IronAnchor(var3.getBlockX() + var7.x, var3.getBlockY() - 3, var3.getBlockZ() + var7.z));
         }
      }
   }

   private record PlatformBlock(int x, int y, int z, String data) {
   }

   private record RenameSession(SylvLiftPlugin.Lift lift, int floorY, int page, AnvilView view) {
   }

   private static final class StopRequest {
      private final int floorY;
      private final UUID callerId;
      private final List<Location> callSigns = new ArrayList<>();

      private StopRequest(int var1, UUID var2, Location var3) {
         this.floorY = var1;
         this.callerId = var2;
         this.addCallSign(var3);
      }

      private void addCallSign(Location var1) {
         if (var1 != null
            && this.callSigns
               .stream()
               .noneMatch(
                  var1x -> var1x.getWorld().getUID().equals(var1.getWorld().getUID())
                     && var1x.getBlockX() == var1.getBlockX()
                     && var1x.getBlockY() == var1.getBlockY()
                     && var1x.getBlockZ() == var1.getBlockZ()
               )) {
            this.callSigns.add(var1.clone());
         }
      }
   }
}
