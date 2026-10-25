package gr.simpleac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleAC extends JavaPlugin implements Listener {

    private static class Data {
        final Map<String, Double> vl = new HashMap<>();
        int hoverMoves = 0;
        double ascent = 0;
        long graceUntil = 0;           // ms: ignore movement checks (teleport, knockback...)
        final Deque<Long> clicks = new ArrayDeque<>();
    }

    private final Map<UUID, Data> data = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        // VL decay
        getServer().getScheduler().runTaskTimer(this, () -> {
            double mul = getConfig().getDouble("decay-multiplier", 0.8);
            for (Data d : data.values()) {
                d.vl.replaceAll((k, v) -> v * mul);
                d.vl.values().removeIf(v -> v < 0.5);
            }
        }, 200L, 200L);
        getLogger().info("SimpleAC enabled.");
    }

    private Data get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    private boolean exempt(Player p) {
        GameMode gm = p.getGameMode();
        return p.hasPermission("simpleac.bypass") || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private void flag(Player p, String check, String info) {
        Data d = get(p);
        double vl = d.vl.merge(check, 1.0, Double::sum);
        String msg = getConfig().getString("alert-format", "")
                .replace("%player%", p.getName())
                .replace("%check%", check)
                .replace("%vl%", String.format("%.1f", vl))
                .replace("%info%", info);
        Component c = legacy.deserialize(msg);
        for (Player staff : getServer().getOnlinePlayers()) {
            if (staff.hasPermission("simpleac.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.sendMessage(c);
            }
        }
        getLogger().info(p.getName() + " failed " + check + " (VL " + String.format("%.1f", vl) + ") " + info);
        if (vl >= getConfig().getInt("kick-vl", 15)) {
            d.vl.remove(check);
            p.kick(legacy.deserialize(getConfig().getString("kick-message", "&cKicked")
                    .replace("%check%", check)));
        }
    }

    // ---------- helpers ----------

    private boolean onSolidGround(Player p) {
        BoundingBox box = p.getBoundingBox();
        for (double x : new double[]{box.getMinX(), box.getMaxX()}) {
            for (double z : new double[]{box.getMinZ(), box.getMaxZ()}) {
                Block b = p.getWorld().getBlockAt((int) Math.floor(x), (int) Math.floor(box.getMinY() - 0.3), (int) Math.floor(z));
                if (!b.isPassable() || b.isLiquid()) return true;
            }
        }
        return false;
    }

    private boolean specialMovement(Player p) {
        Material in = p.getLocation().getBlock().getType();
        Material feet = p.getLocation().clone().add(0, -0.1, 0).getBlock().getType();
        String n = in.name();
        return p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isInsideVehicle()
                || p.isRiptiding() || p.isClimbing() || p.isSwimming()
                || p.getLocation().getBlock().isLiquid()
                || n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLDING")
                || n.contains("COBWEB") || n.contains("POWDER_SNOW") || n.contains("BERRY")
                || feet == Material.SLIME_BLOCK || feet == Material.HONEY_BLOCK
                || n.contains("BED") || feet.name().contains("BED")
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private void grace(Player p, long ms) {
        Data d = get(p);
        d.graceUntil = System.currentTimeMillis() + ms;
        d.hoverMoves = 0;
        d.ascent = 0;
    }

    // ---------- events ----------

    @EventHandler public void onJoin(PlayerJoinEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { data.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onTeleport(PlayerTeleportEvent e) { grace(e.getPlayer(), 2000); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { grace(e.getPlayer(), 3000); }
    @EventHandler public void onVelocity(PlayerVelocityEvent e) { grace(e.getPlayer(), 2500); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { grace(e.getPlayer(), 3000); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (exempt(p) || e.getTo() == null) return;
        Data d = get(p);
        if (System.currentTimeMillis() < d.graceUntil) return;

        double dx = e.getTo().getX() - e.getFrom().getX();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        double dy = e.getTo().getY() - e.getFrom().getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // ---- Speed ----
        if (getConfig().getBoolean("checks.speed.enabled", true) && !specialMovement(p)) {
            double max = getConfig().getDouble("checks.speed.max-horizontal", 0.75);
            if (p.hasPotionEffect(PotionEffectType.SPEED)) {
                int amp = p.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
                max *= 1.0 + 0.2 * amp;
            }
            String below = p.getLocation().clone().add(0, -0.5, 0).getBlock().getType().name();
            if (below.contains("ICE")) max *= 1.8;
            max = Math.max(max, p.getWalkSpeed() * 3.75);
            if (horizontal > max) {
                flag(p, "Speed", String.format("%.2f > %.2f", horizontal, max));
            }
        }

        // ---- Fly ----
        if (getConfig().getBoolean("checks.fly.enabled", true)) {
            if (specialMovement(p) || onSolidGround(p)) {
                d.hoverMoves = 0;
                d.ascent = 0;
            } else {
                // hovering in the air
                if (Math.abs(dy) < 0.005) {
                    d.hoverMoves++;
                    if (d.hoverMoves > getConfig().getInt("checks.fly.max-hover-moves", 12)) {
                        flag(p, "Fly", "hover " + d.hoverMoves + " moves");
                        d.hoverMoves = 0;
                    }
                } else {
                    d.hoverMoves = 0;
                }
                // rising too high
                if (dy > 0) {
                    d.ascent += dy;
                    double max = getConfig().getDouble("checks.fly.max-ascent", 1.7);
                    if (p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                        max += (p.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1) * 0.6;
                    }
                    if (d.ascent > max) {
                        flag(p, "Fly", String.format("ascent %.2f > %.2f", d.ascent, max));
                        d.ascent = 0;
                    }
                } else if (dy < 0) {
                    d.ascent = 0;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || exempt(p)) return;
        Entity target = e.getEntity();

        // ---- Reach ----
        if (getConfig().getBoolean("checks.reach.enabled", true) && p.getWorld().equals(target.getWorld())) {
            BoundingBox bb = target.getBoundingBox();
            Vector eye = p.getEyeLocation().toVector();
            double cx = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
            double cy = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
            double cz = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
            double dist = eye.distance(new Vector(cx, cy, cz));
            double max = getConfig().getDouble("checks.reach.max-reach", 3.6);
            if (dist > max) {
                e.setCancelled(true);
                flag(p, "Reach", String.format("%.2f > %.2f", dist, max));
                return;
            }
        }
        click(p);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            Player p = e.getPlayer();
            if (!exempt(p)) click(p);
        }
    }

    // ---- AutoClicker ----
    private void click(Player p) {
        if (!getConfig().getBoolean("checks.autoclicker.enabled", true)) return;
        Data d = get(p);
        long now = System.currentTimeMillis();
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.pollFirst();
        int max = getConfig().getInt("checks.autoclicker.max-cps", 20);
        if (d.clicks.size() > max) {
            flag(p, "AutoClicker", d.clicks.size() + " CPS");
            d.clicks.clear();
        }
    }

    // ---------- command ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!s.hasPermission("simpleac.admin")) {
            s.sendMessage(legacy.deserialize("&cNo permission."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage(legacy.deserialize("&aSimpleAC config reloaded."));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("alerts") && s instanceof Player p) {
            if (!alertsOff.remove(p.getUniqueId())) {
                alertsOff.add(p.getUniqueId());
                s.sendMessage(legacy.deserialize("&cAlerts off."));
            } else {
                s.sendMessage(legacy.deserialize("&aAlerts on."));
            }
        } else {
            s.sendMessage(legacy.deserialize("&7Usage: /simpleac <reload|alerts>"));
        }
        return true;
    }
}
