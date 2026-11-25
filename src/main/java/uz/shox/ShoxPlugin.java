package uz.shox;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.block.data.Rail;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * ShoxPlugin - Paper 1.21.4
 * Har 2 soatda: tog'dan poyezd chiqadi -> 3 daqiqada yuradi -> yangi "Asr" dunyosi
 * -> missiyalar -> lut -> katta eshik -> /spawn.
 */
public class ShoxPlugin extends JavaPlugin implements Listener {

    static final int SEC = 30;                 // har bir xona uzunligi
    static final int[] KILLS = {8, 12, 6};     // missiyalardagi moblar soni
    static final String[] NAMES = {
            "1-missiya: Qadimiy jangchilar",
            "2-missiya: Qal'a qo'riqchilari",
            "OXIRGI missiya: Qo'mondon"
    };
    static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_TP = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    final Random rnd = new Random();
    NamespacedKey mobKey;
    Session session;

    // ---------------------------------------------------------------- data
    static class Session {
        World world;
        Location station;
        final List<Entity> train = new ArrayList<>();
        final Set<UUID> boarded = new HashSet<>();
        final Set<UUID> finished = new HashSet<>();
        final Map<String, LootData> loot = new HashMap<>();
        BossBar bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        int mission = -1, remaining, total;
        boolean ready, departed;
        long startMs = System.currentTimeMillis();
    }

    static class LootData {
        UUID owner; int mission; Location loc;
    }

    static class LootHolder implements InventoryHolder {
        final String key; Inventory inv;
        LootHolder(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class VoidGen extends ChunkGenerator {
        @Override public Location getFixedSpawnLocation(World w, Random r) {
            return new Location(w, 2.5, 65, 12.5);
        }
    }

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobKey = new NamespacedKey(this, "shox_mob");
        getServer().getPluginManager().registerEvents(this, this);
        cleanupOldWorlds();
        long iv = getConfig().getLong("interval-minutes", 120) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::startEvent, iv, iv);
        getLogger().info("ShoxPlugin yoqildi.");
    }

    @Override
    public void onDisable() {
        endSession();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) { sender.sendMessage("§e/shox <start|stop|setstation>"); return true; }
        switch (a[0].toLowerCase()) {
            case "start" -> sender.sendMessage(startEvent() ? "§aEvent boshlandi." : "§cEvent allaqachon ketyapti.");
            case "stop" -> { endSession(); sender.sendMessage("§aEvent to'xtatildi."); }
            case "setstation" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Faqat o'yinchi."); return true; }
                Location l = p.getLocation();
                getConfig().set("station.world", l.getWorld().getName());
                getConfig().set("station.x", l.getX());
                getConfig().set("station.y", l.getY());
                getConfig().set("station.z", l.getZ());
                saveConfig();
                sender.sendMessage("§aStansiya o'rnatildi. Poyezd shu yerga +X tomonga chiqadi.");
            }
            case "maketunnel" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Faqat o'yinchi."); return true; }
                Location l = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                getConfig().set("station.world", l.getWorld().getName());
                getConfig().set("station.x", l.getX());
                getConfig().set("station.y", l.getY());
                getConfig().set("station.z", l.getZ());
                saveConfig();
                carveTunnel(l);
                sender.sendMessage("§aTunnel qazildi (g'arb tomonga 49 blok) va stansiya o'rnatildi.");
            }
            default -> sender.sendMessage("§e/shox <start|stop|setstation|maketunnel>");
        }
        return true;
    }

    /** Turgan joyingizdan g'arbga (-X) qarab togni teshib, relsli tunnel qazadi. */
    void carveTunnel(Location st) {
        World w = st.getWorld();
        int sx = st.getBlockX(), sy = st.getBlockY(), sz = st.getBlockZ();
        Rail rail = (Rail) Material.RAIL.createBlockData();
        rail.setShape(Rail.Shape.EAST_WEST);
        for (int x = sx - 49; x <= sx; x++) {
            for (int z = sz - 4; z <= sz + 4; z++) {
                for (int y = sy - 1; y <= sy + 6; y++) {
                    boolean edge = z == sz - 4 || z == sz + 4 || y == sy - 1 || y == sy + 6;
                    boolean end = x == sx - 49;
                    Block b = w.getBlockAt(x, y, z);
                    if (end || (edge && x < sx)) {
                        boolean lamp = y == sy + 6 && x % 6 == 0 && z == sz;
                        b.setType(lamp ? Material.SEA_LANTERN : (y == sy - 1 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS), false);
                    } else if (edge) {
                        // kirish og'zi: faqat yon ustunlar va tepa ramka
                        if (y != sy - 1) b.setType(Material.STONE_BRICKS, false);
                        else b.setType(Material.POLISHED_ANDESITE, false);
                    } else {
                        b.setType(Material.AIR, false);
                    }
                }
                if (z == sz && x < sx) w.getBlockAt(x, sy, z).setBlockData(rail, false);
            }
        }
    }

    Location loadStation() {
        World w = Bukkit.getWorld(getConfig().getString("station.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(w, getConfig().getDouble("station.x"), getConfig().getDouble("station.y"),
                getConfig().getDouble("station.z"));
    }

    // ---------------------------------------------------------------- event
    boolean startEvent() {
        if (session != null) return false;
        Session s = new Session();
        session = s;
        s.station = loadStation();
        s.world = createWorld();
        buildArena(s.world);
        spawnTrain(s);

        int dep = getConfig().getInt("departure-minutes", 3);
        Bukkit.broadcastMessage("§6§l[Shox] §e🚂 Tog'dan poyezd paydo bo'ldi! Poyezd §c" + dep
                + " daqiqada§e yuradi. Poyezdni o'ng tugma bilan bosib chiqing!");
        s.station.getWorld().playSound(s.station, Sound.ENTITY_WARDEN_SONIC_BOOM, 4f, 0.6f);

        // poyezd tog'dan chiqish animatsiyasi
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (session != s) { cancel(); return; }
                moveTrain(s, 0.5);
                if (t % 10 == 0) s.station.getWorld().playSound(s.train.get(0).getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
                if (++t >= 50) { s.ready = true; cancel(); }
            }
        }.runTaskTimer(this, 0, 1);

        // orqaga sanash
        new BukkitRunnable() {
            int left = dep * 60;
            public void run() {
                if (session != s) { cancel(); return; }
                left--;
                if (left == 120 || left == 60 || left == 30 || left == 10 || (left <= 5 && left > 0)) {
                    Bukkit.broadcastMessage("§6[Shox] §e🚂 Poyezd §c" + left + " soniyada§e yuradi!");
                }
                if (left <= 0) { cancel(); depart(s); }
            }
        }.runTaskTimer(this, 20, 20);
        return true;
    }

    World createWorld() {
        String name = "shox_asr_" + System.currentTimeMillis();
        World w = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generator(new VoidGen())
                .generateStructures(false)
                .createWorld();
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setTime(6000);
        w.setDifficulty(Difficulty.NORMAL);
        w.setSpawnLocation(2, 65, 12);
        return w;
    }

    // ---------------------------------------------------------------- train
    void part(Session s, Location o, Material m, float tx, float ty, float tz, float sx, float sy, float sz) {
        BlockDisplay d = o.getWorld().spawn(o, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(tx, ty, tz), new Quaternionf(),
                    new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setTeleportDuration(2);
            e.setPersistent(false);
        });
        s.train.add(d);
    }

    void spawnTrain(Session s) {
        Location o = s.station.clone().add(-25, 0, 0); // tog' ichidan boshlanadi
        // lokomotiv
        part(s, o, Material.BLACK_CONCRETE, -4, 0.5f, -1.5f, 4, 2.5f, 3);
        part(s, o, Material.RED_CONCRETE, -4.05f, 1.2f, -1.55f, 4.1f, 0.3f, 3.1f);
        part(s, o, Material.GRAY_CONCRETE, -1.5f, 3f, -0.4f, 0.8f, 1.5f, 0.8f);
        part(s, o, Material.GLOWSTONE, 0f, 1.2f, -0.4f, 0.2f, 0.6f, 0.8f);
        // ikki vagon (ochiq, ichida turish mumkin)
        for (int i = 0; i < 2; i++) {
            float x = -11 - i * 7;
            part(s, o, Material.SPRUCE_PLANKS, x, 0.5f, -1.5f, 6, 0.3f, 3);
            part(s, o, Material.OAK_PLANKS, x, 0.8f, -1.5f, 6, 1.4f, 0.2f);
            part(s, o, Material.OAK_PLANKS, x, 0.8f, 1.3f, 6, 1.4f, 0.2f);
            part(s, o, Material.OAK_PLANKS, x, 0.8f, -1.3f, 0.2f, 1.4f, 2.6f);
            part(s, o, Material.BLACK_CONCRETE, x, 0f, -1.4f, 6, 0.5f, 0.3f);
            part(s, o, Material.BLACK_CONCRETE, x, 0f, 1.1f, 6, 0.5f, 0.3f);
        }
        // bosish uchun ko'rinmas hududlar
        for (int dx : new int[]{-6, -13}) {
            Interaction in = o.getWorld().spawn(o.clone().add(dx, 0, 0), Interaction.class, e -> {
                e.setInteractionWidth(8f);
                e.setInteractionHeight(3f);
                e.addScoreboardTag("shox_train");
                e.setPersistent(false);
            });
            s.train.add(in);
        }
    }

    void moveTrain(Session s, double dx) {
        for (Entity e : s.train) e.teleport(e.getLocation().add(dx, 0, 0));
    }

    Location trainSpot(Session s, Player p) {
        Location l = s.train.get(0).getLocation().add(-11, 0.9, 0);
        l.setYaw(p.getLocation().getYaw());
        l.setPitch(p.getLocation().getPitch());
        return l;
    }

    void depart(Session s) {
        s.departed = true;
        s.boarded.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (s.boarded.isEmpty()) {
            Bukkit.broadcastMessage("§6[Shox] §7Poyezdga hech kim chiqmadi. Event bekor qilindi.");
            endSession();
            return;
        }
        Bukkit.broadcastMessage("§6[Shox] §e🚂 Poyezd yo'lga chiqdi! Keyingi event 2 soatdan keyin.");
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (session != s) { cancel(); return; }
                moveTrain(s, 0.6);
                for (UUID id : s.boarded) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) { p.teleport(trainSpot(s, p)); p.setFallDistance(0); }
                }
                if (t % 8 == 0) s.station.getWorld().playSound(s.train.get(0).getLocation(), Sound.ENTITY_MINECART_RIDING, 2f, 0.6f);
                if (++t >= 100) { cancel(); arrive(s); }
            }
        }.runTaskTimer(this, 0, 1);
    }

    void arrive(Session s) {
        s.train.forEach(Entity::remove);
        s.train.clear();
        Location sp = new Location(s.world, 2.5, 65, 12.5, -90, 0);
        for (UUID id : s.boarded) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.teleport(sp);
            s.bar.addPlayer(p);
            p.sendTitle("§6§lASR DUNYOSI", "§7Missiyalarni bajarmay chiqib bo'lmaydi!", 10, 80, 20);
        }
        if (s.world.getPlayers().isEmpty()) { endSession(); return; }

        Bukkit.getScheduler().runTaskLater(this, () -> startMission(s, 0), 100L);

        // nazorat: dunyo bo'shasa yoki 40 daqiqa o'tsa yopamiz
        new BukkitRunnable() {
            public void run() {
                if (session != s) { cancel(); return; }
                boolean timeout = System.currentTimeMillis() - s.startMs > 40L * 60_000L;
                if (s.world.getPlayers().isEmpty() || timeout) { cancel(); endSession(); }
            }
        }.runTaskTimer(this, 200, 100);
    }

    // ---------------------------------------------------------------- arena
    void setB(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    void buildArena(World w) {
        int maxX = 101, maxZ = 24;
        Material[] wallMats = {Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.STONE_BRICKS};
        for (int x = 0; x <= maxX; x++) {
            for (int z = 0; z <= maxZ; z++) {
                setB(w, x, 64, z, ((x + z) % 2 == 0) ? Material.POLISHED_ANDESITE : Material.DEEPSLATE_BRICKS);
                boolean wall = z == 0 || z == maxZ || x == 0 || x == maxX || x == SEC - 1 || x == 2 * SEC - 1 || x == 3 * SEC - 1;
                if (wall) for (int y = 65; y <= 72; y++) setB(w, x, y, z, wallMats[rnd.nextInt(wallMats.length)]);
                boolean lamp = x % 6 == 3 && z % 6 == 3;
                setB(w, x, 73, z, lamp ? Material.SEA_LANTERN : Material.DEEPSLATE_TILES);
            }
        }
        // ichki darvozalar (temir panjara)
        for (int gx : new int[]{SEC - 1, 2 * SEC - 1})
            for (int z = 10; z <= 14; z++) for (int y = 65; y <= 69; y++) setB(w, gx, y, z, Material.IRON_BARS);
        // KATTA ESHIK
        for (int z = 9; z <= 15; z++) for (int y = 65; y <= 71; y++) setB(w, 3 * SEC - 1, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
        for (int y = 65; y <= 72; y++) { setB(w, 3 * SEC - 1, y, 8, Material.CRYING_OBSIDIAN); setB(w, 3 * SEC - 1, y, 16, Material.CRYING_OBSIDIAN); }
        for (int z = 8; z <= 16; z++) setB(w, 3 * SEC - 1, 72, z, Material.CRYING_OBSIDIAN);
    }

    void openOpening(World w, int x, int zMin, int zMax, int yMin, int yMax, boolean big) {
        new BukkitRunnable() {
            int y = yMax;
            public void run() {
                if (y < yMin || w.getPlayers().isEmpty()) { cancel(); return; }
                for (int z = zMin; z <= zMax; z++) setB(w, x, y, z, Material.AIR);
                w.playSound(new Location(w, x, 66, (zMin + zMax) / 2.0),
                        big ? Sound.BLOCK_PISTON_EXTEND : Sound.BLOCK_IRON_DOOR_OPEN, 3f, big ? 0.5f : 1f);
                y--;
            }
        }.runTaskTimer(this, 0, big ? 6 : 4);
    }

    // -------------------------------------------------------------- missions
    void startMission(Session s, int m) {
        if (session != s) return;
        s.mission = m;
        s.total = s.remaining = KILLS[m];
        int x0 = m * SEC;
        for (int i = 0; i < KILLS[m]; i++) {
            boolean boss = m == 2 && i == 0;
            Location l = new Location(s.world, x0 + 12 + rnd.nextInt(16) + 0.5, 65, 3 + rnd.nextInt(19) + 0.5);
            spawnMob(l, boss);
        }
        s.bar.setColor(m == 2 ? BarColor.RED : BarColor.YELLOW);
        updateBar(s);
        for (Player p : s.world.getPlayers()) {
            p.sendTitle("§e" + NAMES[m], "§7Qilich bilan jang qiling!", 10, 60, 10);
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 2f, 1f);
        }
    }

    void updateBar(Session s) {
        s.bar.setTitle("§6" + NAMES[Math.max(0, s.mission)] + " §7— qolgan: §e" + s.remaining);
        s.bar.setProgress(Math.max(0, Math.min(1, s.remaining / (double) Math.max(1, s.total))));
    }

    void spawnMob(Location l, boolean boss) {
        EntityType t = (boss || rnd.nextBoolean()) ? EntityType.ZOMBIE : EntityType.HUSK;
        Zombie z = (Zombie) l.getWorld().spawnEntity(l, t);
        z.setBaby(false);
        z.setCustomName(boss ? "§4§lQadimiy Qo'mondon" : "§6Qadimiy jangchi");
        z.setCustomNameVisible(true);
        var eq = z.getEquipment();
        eq.setItemInMainHand(new ItemStack(boss ? Material.DIAMOND_SWORD : (rnd.nextBoolean() ? Material.IRON_SWORD : Material.STONE_SWORD)));
        eq.setHelmet(new ItemStack(boss ? Material.NETHERITE_HELMET : Material.CHAINMAIL_HELMET));
        eq.setChestplate(new ItemStack(boss ? Material.DIAMOND_CHESTPLATE : Material.LEATHER_CHESTPLATE));
        eq.setItemInMainHandDropChance(0f);
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        double hp = boss ? 120 : 26;           // "medium" qiyinlik
        z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
        z.setHealth(hp);
        if (boss) {
            z.getAttribute(Attribute.SCALE).setBaseValue(1.6);
            z.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(7);
        }
        z.setRemoveWhenFarAway(false);
        z.setCanPickupItems(false);
        z.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
    }

    void completeMission(Session s) {
        int m = s.mission;
        for (Player p : s.world.getPlayers()) {
            p.sendTitle("§a✔ Missiya bajarildi!", m < 2 ? "§7Xonangizda lut paydo bo'ldi" : "§7Katta eshik ochilmoqda...", 10, 50, 10);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            giveLootChest(s, p, m);
        }
        if (m < 2) {
            openOpening(s.world, (m + 1) * SEC - 1, 10, 14, 65, 69, false);
            Bukkit.getScheduler().runTaskLater(this, () -> startMission(s, m + 1), 140L);
        } else {
            s.bar.setTitle("§aBarcha missiyalar bajarildi!");
            s.bar.setProgress(1);
            openOpening(s.world, 3 * SEC - 1, 9, 15, 65, 71, true);
            for (Player p : s.world.getPlayers()) {
                s.finished.add(p.getUniqueId());
                p.sendMessage("§6§l[Shox] §aKatta eshik ochildi! Chiqish uchun §e/spawn§a yozing.");
            }
            Bukkit.getScheduler().runTaskLater(this, () -> s.bar.removeAll(), 200L);
        }
    }

    // ------------------------------------------------------------------ loot
    String key(Location l) { return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }

    void giveLootChest(Session s, Player p, int m) {
        Location loc;
        int tries = 0;
        do {
            loc = new Location(s.world, m * SEC + 5 + rnd.nextInt(10), 65, 3 + rnd.nextInt(19));
            tries++;
        } while (tries < 30 && (loc.getBlock().getType() != Material.AIR || s.loot.containsKey(key(loc))));
        LootData d = new LootData();
        d.owner = p.getUniqueId(); d.mission = m; d.loc = loc;
        s.loot.put(key(loc), d);
        loc.getBlock().setType(Material.BARRIER, false);                       // haqiqiy (ko'rinmas) blok
        p.sendBlockChange(loc, Material.CHEST.createBlockData());               // faqat shu o'yinchi sandiqni ko'radi
        spawnGuide(s, p, loc);
    }

    void spawnGuide(Session s, Player p, Location chest) {
        Location start = p.getLocation().add(0, 1.5, 0);
        BlockDisplay g = s.world.spawn(start, BlockDisplay.class, e -> {
            e.setBlock(Material.GOLD_BLOCK.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.2f, -0.2f, -0.2f), new Quaternionf(),
                    new Vector3f(0.4f, 0.4f, 0.4f), new Quaternionf()));
            e.setTeleportDuration(2);
            e.setGlowing(true);
            e.setGlowColorOverride(Color.YELLOW);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setVisibleByDefault(false);      // boshqalar ko'rmaydi
            e.setPersistent(false);
        });
        p.showEntity(this, g);
        Location target = chest.clone().add(0.5, 1.3, 0.5);
        new BukkitRunnable() {
            public void run() {
                if (!p.isOnline() || session != s || !g.isValid()) { g.remove(); cancel(); return; }
                Vector d = target.toVector().subtract(g.getLocation().toVector());
                if (d.length() < 0.6) {
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                    g.remove(); cancel(); return;
                }
                g.teleport(g.getLocation().add(d.normalize().multiply(0.25)));
                p.spawnParticle(Particle.END_ROD, g.getLocation(), 1, 0, 0, 0, 0);
            }
        }.runTaskTimer(this, 10, 1);
    }

    List<ItemStack> lootFor(int m) {
        List<ItemStack> l = new ArrayList<>();
        switch (m) {
            case 0 -> {
                l.add(new ItemStack(Material.IRON_INGOT, 6 + rnd.nextInt(5)));
                l.add(new ItemStack(Material.COOKED_BEEF, 12));
                l.add(new ItemStack(Material.IRON_CHESTPLATE));
                l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 6));
            }
            case 1 -> {
                l.add(new ItemStack(Material.GOLD_INGOT, 8));
                l.add(new ItemStack(Material.DIAMOND, 2 + rnd.nextInt(2)));
                l.add(new ItemStack(Material.GOLDEN_APPLE, 2));
                l.add(new ItemStack(Material.IRON_SWORD));
                l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 12));
            }
            default -> {
                l.add(new ItemStack(Material.DIAMOND, 6));
                l.add(new ItemStack(Material.NETHERITE_SCRAP, 2));
                l.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
                l.add(new ItemStack(Material.DIAMOND_SWORD));
                l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 24));
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- events
    @EventHandler
    public void onTrainClick(PlayerInteractEntityEvent e) {
        Session s = session;
        if (s == null || !e.getRightClicked().getScoreboardTags().contains("shox_train")) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!s.ready || s.departed) { p.sendMessage("§cPoyezd hozir chiqmaydi."); return; }
        s.boarded.add(p.getUniqueId());
        p.teleport(trainSpot(s, p));
        p.sendMessage("§aPoyezdga chiqdingiz! Yurishini kuting, tushib ketmang.");
    }

    @EventHandler
    public void onLootClick(PlayerInteractEvent e) {
        Session s = session;
        if (s == null || e.getClickedBlock() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK
                || e.getHand() != EquipmentSlot.HAND) return;
        String k = key(e.getClickedBlock().getLocation());
        LootData d = s.loot.get(k);
        if (d == null) return;
        e.setCancelled(true);
        if (!d.owner.equals(e.getPlayer().getUniqueId())) return;
        LootHolder h = new LootHolder(k);
        h.inv = Bukkit.createInventory(h, 27, "§6Qadimiy lut");
        lootFor(d.mission).forEach(h.inv::addItem);
        e.getPlayer().openInventory(h.inv);
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
    }

    @EventHandler
    public void onLootClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof LootHolder h)) return;
        Session s = session;
        Player p = (Player) e.getPlayer();
        for (ItemStack it : e.getInventory().getContents()) {
            if (it == null) continue;
            p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        }
        e.getInventory().clear();
        if (s == null) return;
        LootData d = s.loot.remove(h.key);
        if (d != null) {
            d.loc.getBlock().setType(Material.AIR, false);
            p.sendBlockChange(d.loc, Material.AIR.createBlockData());
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        Session s = session;
        if (s == null || e.getEntity().getWorld() != s.world) return;
        if (!e.getEntity().getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        s.remaining--;
        updateBar(s);
        if (s.remaining <= 0) completeMission(s);
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        Session s = session;
        if (s != null && e.getLocation().getWorld() == s.world
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) e.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Session s = session;
        Player p = e.getPlayer();
        if (s == null || p.getWorld() != s.world || p.hasPermission("shox.bypass")) return;
        String cmd = e.getMessage().toLowerCase().split(" ")[0];
        e.setCancelled(true);
        if (cmd.equals("/spawn") && s.finished.contains(p.getUniqueId())) {
            leave(p);
        } else {
            p.sendMessage("§cBu yerda buyruqlar ishlamaydi! Avval barcha missiyalarni bajaring.");
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Session s = session;
        if (s == null || e.getFrom().getWorld() != s.world || e.getPlayer().hasPermission("shox.bypass")) return;
        if (BLOCKED_TP.contains(e.getCause())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Session s = session;
        if (s != null && e.getBlock().getWorld() == s.world && !e.getPlayer().hasPermission("shox.bypass")) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Session s = session;
        if (s != null && e.getBlock().getWorld() == s.world && !e.getPlayer().hasPermission("shox.bypass")) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Session s = session;
        if (s == null || e.getEntity().getWorld() != s.world) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Session s = session;
        if (s == null || !s.world.getPlayers().contains(e.getPlayer()) && e.getPlayer().getWorld() != s.world) return;
        int sec = Math.min(Math.max(s.mission, 0), 2);
        e.setRespawnLocation(new Location(s.world, sec * SEC + 2.5, 65, 12.5, -90, 0));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String n = p.getWorld().getName();
        if (n.startsWith("shox_asr_") && (session == null || session.world != p.getWorld())) leave(p);
        else if (session != null && p.getWorld() == session.world && !session.finished.contains(p.getUniqueId()))
            session.bar.addPlayer(p);
    }

    // ---------------------------------------------------------------- helpers
    void leave(Player p) {
        if (session != null) session.bar.removePlayer(p);
        p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        p.sendMessage("§6[Shox] §aAsr dunyosidan qaytdingiz!");
    }

    void endSession() {
        Session s = session;
        if (s == null) return;
        session = null;
        s.train.forEach(Entity::remove);
        s.bar.removeAll();
        if (s.world == null) return;
        World w = s.world;
        Path folder = w.getWorldFolder().toPath();
        for (Player p : new ArrayList<>(w.getPlayers())) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        w.getEntities().forEach(en -> { if (!(en instanceof Player)) en.remove(); });
        Bukkit.unloadWorld(w, false);
        deleteFolder(folder);
    }

    void cleanupOldWorlds() {
        try (Stream<Path> st = Files.list(Bukkit.getWorldContainer().toPath())) {
            st.filter(pa -> pa.getFileName().toString().startsWith("shox_asr_")).forEach(this::deleteFolder);
        } catch (IOException ignored) { }
    }

    void deleteFolder(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> st = Files.walk(p)) {
            st.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
