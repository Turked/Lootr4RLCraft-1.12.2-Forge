package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Console-only QA fixture for a disposable server. Never packaged with the game mod. */
@Mod(modid = "lootrserverprobe", name = "Lootr Dedicated Server Probe", version = "1",
        acceptableRemoteVersions = "*", dependencies = "required-after:lootr4rlcraft")
public final class ServerProbe {
    private static final Logger LOG = LogManager.getLogger("lootr-server-probe");
    private static final int SIDE = 5;
    private static final int Y = 200;
    private WorldServer world;
    private Object template;
    private Method build;
    private NBTTagCompound evidence;
    private int tower;
    private EntityPlayerMP[] actors;
    private int remoteWait;
    private int rowWait;
    private Path file;

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        world = event.getServer().getWorld(0);
        file = world.getSaveHandler().getWorldDirectory().toPath().resolve("data/lootr-server-probe.dat");
        MinecraftForge.EVENT_BUS.register(this);
        NaturalTowerProbe natural = new NaturalTowerProbe(() -> actors()[0]);
        MinecraftForge.EVENT_BUS.register(natural);
        event.registerServerCommand(natural);
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() { return "lootrqa"; }
            @Override public String getUsage(ICommandSender sender) { return "lootrqa <generate|sample|verify|compat|physical|remote|status|luck-prime|luck>"; }
            @Override public int getRequiredPermissionLevel() { return 4; }
            @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
                if (sender != server || args.length != 1) throw new CommandException(getUsage(sender));
                try {
                    switch (args[0]) {
                        case "crash-prime": case "crash-baseline": case "crash-arm": case "crash-verify":
                            CrashRecoveryProbe.run(args[0], world, actors()); break;
                        case "generate": begin(); break;
                        case "sample": examine(false); break;
                        case "verify": examine(true); break;
                        case "compat": case "physical": compat("physical".equals(args[0])); break;
                        case "luck-prime": LuckifiedProbe.prime(world, actors()[0]); break;
                        case "luck": LuckifiedProbe.run(world, actors()); break;
                        case "remote": prepareRemote(); break;
                        case "row":
                            RenderRow.watch(world, actors()[0]);
                            rowWait = 240;
                            break;
                        case "status": status(); break;
                        default: throw new IllegalArgumentException(getUsage(sender));
                    }
                } catch (Exception failure) {
                    LOG.error("Probe {} failed", args[0], failure);
                    throw new CommandException("Probe failed: " + failure);
                }
                sender.sendMessage(new TextComponentString("Lootr server probe " + args[0] + " completed or queued. See server log."));
            }
        });
    }

    private void prepareRemote() {
        BlockPos target = new BlockPos(72, 160, 72);
        neighbors(target.getX() >> 4, target.getZ() >> 4);
        Chunk chunk = world.getChunkFromBlockCoords(target);
        if (!LockAccess.worldgenReady(chunk)) {
            MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(chunk, actors()[0]));
            remoteWait = 200;
            LOG.info("Waiting for the installed Locks generation pass before remote fixture setup");
            return;
        }
        TileEntity tile = world.getTileEntity(target);
        if (tile == null) {
            if (!world.isAirBlock(target)) throw new IllegalStateException("Remote chest position is occupied");
            for (int x = 67; x <= 77; x++) for (int z = 65; z <= 76; z++) {
                BlockPos floor = new BlockPos(x, 159, z);
                if (!world.isAirBlock(floor)) throw new IllegalStateException("Remote platform is occupied: " + floor);
            }
            for (int x = 67; x <= 77; x++) for (int z = 65; z <= 76; z++) {
                world.setBlockState(new BlockPos(x, 159, z), net.minecraft.init.Blocks.STONE.getDefaultState());
            }
            world.setBlockState(target, net.minecraft.init.Blocks.CHEST.getDefaultState());
            TileEntityChest chest = (TileEntityChest) world.getTileEntity(target);
            chest.setInventorySlotContents(0, new ItemStack(Items.DIAMOND, 3));
            tile = chest;
        }
        if (!ContainerLoot.isManaged(tile)) {
            if (!(tile instanceof TileEntityChest)) throw new IllegalStateException("Unexpected remote fixture tile");
            TileEntityChest chest = (TileEntityChest) tile;
            if (chest.getLootTable() != null || !ItemStack.areItemStacksEqual(chest.getStackInSlot(0), new ItemStack(Items.DIAMOND, 3)))
                throw new IllegalStateException("Unexpected remote fixture contents");
            for (int i = 1; i < chest.getSizeInventory(); i++)
                if (!chest.getStackInSlot(i).isEmpty()) throw new IllegalStateException("Unexpected item in remote chest");
            ContainerLoot.convert(world, target, true);
        }
        for (String name : new String[] {"RenderOne", "RenderTwo", "RenderThree"}) {
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            world.getMinecraftServer().getPlayerList().addOp(new GameProfile(id, name));
        }
        LOG.info("REMOTE chest={} {}", target, ContainerLoot.inspect(world, target));
    }

    private void begin() throws Exception {
        if (Files.exists(file) || template != null) throw new IllegalStateException("Use a fresh disposable world for generation");
        File source = new File("config/ruins_config/plains/TowerMedium.tml").getCanonicalFile();
        if (!source.isFile()) throw new IllegalArgumentException("Missing " + source);
        Class<?> type = Class.forName("atomicstryker.ruins.common.RuinTemplate");
        template = type.getConstructor(PrintWriter.class, String.class, String.class, boolean.class)
                .newInstance(new PrintWriter(new StringWriter()), source.getPath(), source.getName(), false);
        build = type.getMethod("doBuild", World.class, Random.class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class);
        evidence = new NBTTagCompound();
        evidence.setTag("Chests", new NBTTagList());
        evidence.setInteger("ChunkX", (world.getSpawnPoint().getX() >> 4) - SIDE / 2);
        evidence.setInteger("ChunkZ", (world.getSpawnPoint().getZ() >> 4) - SIDE / 2);
        evidence.setString("Template", source.getName());
        actors = actors();
        tower = 0;
        LOG.info("Generating {} real Ruins {} towers in a controlled grid around spawn", SIDE * SIDE, source.getName());
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world != world) return;
        if (rowWait > 0) {
            rowWait--;
            if (RenderRow.ready(world)) {
                rowWait = 0;
                try { RenderRow.prepare(world, actors()[0]); }
                catch (Exception failure) { LOG.error("Render row failed", failure); }
            } else if (rowWait == 0) LOG.error("Render row timed out waiting for native Locks phases");
        }
        if (remoteWait > 0) {
            remoteWait--;
            if (LockAccess.worldgenReady(world.getChunkFromChunkCoords(4, 4))) {
                remoteWait = 0;
                try { prepareRemote(); }
                catch (RuntimeException failure) { LOG.error("Remote fixture preparation failed", failure); }
            } else if (remoteWait == 0) LOG.error("Remote fixture timed out waiting for native Locks generation");
        }
        if (template == null) return;
        try {
            int cx = evidence.getInteger("ChunkX") + tower % SIDE;
            int cz = evidence.getInteger("ChunkZ") + tower / SIDE;
            int x = (cx << 4) + 8;
            int z = (cz << 4) + 8;
            neighbors(cx, cz);
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    if (world.getHeight(new BlockPos(x + dx, 0, z + dz)).getY() >= Y - 18)
                        throw new IllegalStateException("Selected sky volume contains existing blocks at " + x + "," + z);
                }
            }
            int floor = (Integer) build.invoke(template, world, new Random(8301 + tower), x, Y, z, 0, false, false);
            if (floor < 0) throw new IllegalStateException("Ruins canceled tower " + tower);
            int collected = 0;
            for (TileEntity tile : new ArrayList<>(chunk.getTileEntityMap().values())) {
                if (!(tile instanceof TileEntityChest) || tile.getPos().getY() < Y - 18) continue;
                TileEntityChest chest = (TileEntityChest) tile;
                NBTTagCompound tag = new NBTTagCompound();
                tag.setLong("Pos", tile.getPos().toLong());
                if (chest.getLootTable() == null) tag.setTag("Original", contents(chest));
                else tag.setString("Table", chest.getLootTable().toString());
                evidence.getTagList("Chests", 10).appendTag(tag);
                collected++;
            }
            if (collected != 7) throw new IllegalStateException("Expected 7 original tower chests; found " + collected + " at " + x + "," + z);
            // With no connected client, drive the actual Forge watch subscribers and let Locks run its delayed native pass.
            MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(chunk, actors[0]));
            tower++;
            if (tower == SIDE * SIDE) {
                template = null;
                save();
                LOG.info("GENERATED towers={} chests={} using installed Ruins; wait at least 40 server ticks then use lootrqa sample",
                        tower, evidence.getTagList("Chests", 10).tagCount());
            }
        } catch (Exception failure) {
            template = null;
            LOG.error("GENERATION FAILED at tower {}", tower, failure);
        }
    }

    private void neighbors(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                world.getChunkFromChunkCoords(cx + dx, cz + dz);
    }

    private void examine(boolean verify) throws Exception {
        if (template != null || !Files.exists(file)) throw new IllegalStateException("Tower generation is incomplete");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            evidence = CompressedStreamTools.readCompressed(input);
        }
        if (verify && !evidence.getBoolean("Sampled")) throw new IllegalStateException("Complete a sample before verifying a restart");
        actors = actors();
        if (evidence.getBoolean("PlayerFiles") || verify) {
            for (EntityPlayerMP actor : actors) {
                check(world.getSaveHandler().getPlayerNBTManager().readPlayerData(actor) != null,
                        "Missing saved QA player data " + actor.getUniqueID());
            }
        }
        int registered = 0, locked = 0, obstructed = 0, tested = 0, failures = 0;
        NBTTagList entries = evidence.getTagList("Chests", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            BlockPos pos = BlockPos.fromLong(entry.getLong("Pos"));
            neighbors(pos.getX() >> 4, pos.getZ() >> 4);
            try {
                TileEntity tile = world.getTileEntity(pos);
                check(ContainerLoot.isManaged(tile), "No automatic registration");
                registered++;
                for (EntityPlayerMP actor : actors) actor.setPosition(pos.getX()+0.5, pos.getY(), pos.getZ()+1.0);
                if (!LockAccess.canOpen(world, pos)) {
                    click(actors[0], pos);
                    check(actors[0].openContainer == actors[0].inventoryContainer, "Locked chest granted access");
                    check(!entry.hasKey("After"), "A previously tested chest became locked");
                    locked++;
                    continue;
                }
                if (!ContainerLoot.canOpen(actors[0], pos)) {
                    check(!entry.hasKey("After"), "Previously tested chest is inaccessible");
                    obstructed++;
                    continue;
                }
                boolean alreadySampled = entry.hasKey("After", 9);
                if (alreadySampled || verify) {
                    check(entry.hasKey("After", 9), "Missing original sample evidence");
                    check(entry.getString("Id").equals(tile.getTileData().getCompoundTag(ContainerLoot.MARKER)
                            .getUniqueId("Id").toString()), "Group identity changed");
                } else {
                    entry.setString("Id", tile.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id").toString());
                }
                List<IInventory> menus = new ArrayList<>();
                for (EntityPlayerMP actor : actors) {
                    click(actor, pos);
                    check(actor.openContainer instanceof ContainerChest, "Native interaction did not open a chest");
                    IInventory inventory = ((ContainerChest) actor.openContainer).getLowerChestInventory();
                    check("lootr".equals(inventory.getDisplayName().getUnformattedText()), "Native physical menu replaced private menu");
                    check(!menus.contains(inventory), "Players share the same live inventory");
                    menus.add(inventory);
                }
                if (alreadySampled || verify) {
                    for (int a = 0; a < 3; a++)
                        check(contents(menus.get(a)).equals(entry.getTagList("After", 10).getCompoundTagAt(a)), "Restart lost actor " + a + " contents");
                } else {
                    NBTTagList after = new NBTTagList();
                    for (int a = 0; a < 3; a++) {
                        IInventory inv = menus.get(a);
                        if (entry.hasKey("Original"))
                            check(contents(inv).equals(entry.getCompoundTag("Original")), "Assigned fixed source differs for actor " + a);
                        NBTTagCompound before = contents(inv);
                        entry.setTag("Before" + a, before);
                        NonNullList<ItemStack> intended = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
                        ItemStackHelper.loadAllItems(before, intended);
                        ItemStack marker = new ItemStack(Items.PAPER, a + 1);
                        marker.setStackDisplayName("probe-" + i + "-" + a);
                        intended.set(0, marker);
                        after.appendTag(ItemStackHelper.saveAllItems(new NBTTagCompound(), intended));
                    }
                    entry.setTag("After", after);
                    save();
                    for (int a = 0; a < 3; a++) {
                        ItemStack marker = new ItemStack(Items.PAPER, a + 1);
                        marker.setStackDisplayName("probe-" + i + "-" + a);
                        actors[a].inventory.setItemStack(marker);
                        actors[a].openContainer.slotClick(0, 0, ClickType.PICKUP, actors[a]);
                        actors[a].inventory.setItemStack(ItemStack.EMPTY);
                    }
                    for (int a = 0; a < 3; a++)
                        check(contents(menus.get(a)).equals(after.getCompoundTagAt(a)), "Concurrent viewer changed another actor's contents");
                }
                tested++;
            } catch (RuntimeException | AssertionError failure) {
                if (++failures <= 8) LOG.error("Failure at {} in {}", pos, verify ? "restart" : "sample", failure);
            } finally {
                for (EntityPlayerMP actor : actors) if (actor.openContainer != actor.inventoryContainer) actor.closeScreen();
            }
        }
        LOG.info("{} total={} registered={} lockedDenied={} otherUnavailable={} threeIndependentActors={} failed={}",
                verify ? "RESTART" : "SAMPLE", entries.tagCount(), registered, locked, obstructed, tested, failures);
        if (!verify) {
            for (EntityPlayerMP actor : actors) world.getSaveHandler().getPlayerNBTManager().writePlayerData(actor);
            evidence.setBoolean("PlayerFiles", true);
            save();
        }
        check(failures == 0 && tested >= 100 && registered == entries.tagCount(), "Acceptance threshold missed; consult server log");
        if (!verify) {
            evidence.setBoolean("Sampled", true);
            save();
        }
    }

    private void save() throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
            CompressedStreamTools.writeCompressed(evidence, out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private void status() throws Exception {
        if (!Files.exists(file)) {
            LOG.info("tower={} busy={} evidence={}", tower, template != null, file);
            return;
        }
        NBTTagCompound snapshot;
        try (java.io.InputStream input = Files.newInputStream(file)) {
            snapshot = CompressedStreamTools.readCompressed(input);
        }
        NBTTagList entries = snapshot.getTagList("Chests", 10);
        int loaded = 0, managed = 0;
        for (int i = 0; i < entries.tagCount(); i++) {
            BlockPos pos = BlockPos.fromLong(entries.getCompoundTagAt(i).getLong("Pos"));
            Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) {
                loaded++;
                if (ContainerLoot.isManaged(chunk.getTileEntityMap().get(pos))) managed++;
            }
        }
        LOG.info("STATUS quiet snapshot total={} loaded={} managed={} sampled={}", entries.tagCount(), loaded, managed, snapshot.getBoolean("Sampled"));
    }

    private void click(EntityPlayerMP actor, BlockPos pos) {
        actor.interactionManager.processRightClickBlock(actor, world, actor.getHeldItemMainhand(), EnumHand.MAIN_HAND, pos,
                EnumFacing.SOUTH, 0.5F, 0.5F, 1.0F);
    }

    private void compat(boolean physical) throws Exception {
        if (!Files.exists(file)) throw new IllegalStateException("Generate the disposable Ruins grid before compatibility checks");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            evidence = CompressedStreamTools.readCompressed(input);
        }
        int x = evidence.getInteger("ChunkX"), z = evidence.getInteger("ChunkZ");
        neighbors(x, z);
        if (physical) {
            PhysicalProbe.run(world, actors(), new BlockPos((x << 4) + 4, 161, (z << 4) + 4));
        } else {
            CompatibilityProbe.run(world, actors(), new BlockPos((x << 4) + 2, 246, (z << 4) + 2));
        }
    }

    private EntityPlayerMP[] actors() {
        EntityPlayerMP[] result = new EntityPlayerMP[3];
        for (int i = 0; i < result.length; i++) {
            EntityPlayerMP actor = new EntityPlayerMP(world.getMinecraftServer(), world,
                    new GameProfile(new UUID(0x1f55054d37734a01L, 900 + i), "LootrProbe" + i), new PlayerInteractionManager(world));
            actor.interactionManager.setGameType(GameType.SURVIVAL);
            NetworkManager network = new NetworkManager(EnumPacketDirection.SERVERBOUND) {
                private final Channel loopback = new EmbeddedChannel();
                @Override public Channel channel() { return loopback; }
            };
            new NetHandlerPlayServer(world.getMinecraftServer(), network, actor) {
                @Override public void sendPacket(Packet<?> packet) { /* Server-side actors have no remote client. */ }
            };
            result[i] = actor;
        }
        return result;
    }

    private static NBTTagCompound contents(IInventory inventory) {
        NonNullList<ItemStack> slots = NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
        for (int i = 0; i < slots.size(); i++) slots.set(i, inventory.getStackInSlot(i).copy());
        return ItemStackHelper.saveAllItems(new NBTTagCompound(), slots);
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }
}
