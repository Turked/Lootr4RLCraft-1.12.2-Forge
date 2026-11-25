package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Looks for an actual Battle Towers generator call during ordinary terrain exploration. */
public final class NaturalTowerProbe extends CommandBase {
    private static final Logger LOG = LogManager.getLogger("lootr-natural-probe");
    private final Supplier<EntityPlayerMP> viewer;
    private final Set<TileEntityChest> born = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BlockPos> eligible = new LinkedHashSet<>();
    private WorldServer world;
    private int baseX;
    private int baseZ;
    private int requested;
    private int wait;
    private boolean observing;

    NaturalTowerProbe(Supplier<EntityPlayerMP> viewer) {
        this.viewer = viewer;
    }

    @Override public String getName() { return "lootrqanatural"; }
    @Override public String getUsage(ICommandSender sender) { return "lootrqanatural [originChunkX originChunkZ]"; }
    @Override public int getRequiredPermissionLevel() { return 4; }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (sender != server || world != null || (args.length != 0 && args.length != 2)) throw new CommandException(getUsage(sender));
        WorldServer target = server.getWorld(0);
        baseX = args.length == 2 ? parseInt(args[0], -1800000, 1800000) : (target.getSpawnPoint().getX() >> 4) + 170;
        baseZ = args.length == 2 ? parseInt(args[1], -1800000, 1800000) : (target.getSpawnPoint().getZ() >> 4) + 170;
        world = target;
        requested = 0;
        wait = 0;
        born.clear();
        eligible.clear();
        LOG.info("Searching fresh terrain for natural Battle Towers at chunk {},{}; maximum 64 requested chunks", baseX, baseZ);
    }

    @SubscribeEvent
    public void constructed(AttachCapabilitiesEvent<TileEntity> event) {
        if (!observing || !(event.getObject() instanceof TileEntityChest)) return;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals("atomicstryker.battletowers.common.AS_WorldGenTower")) {
                born.add((TileEntityChest) event.getObject());
                return;
            }
        }
    }

    @SubscribeEvent
    public void sample(TickEvent.WorldTickEvent event) {
        if (event.world != world || event.phase != TickEvent.Phase.END) return;
        try {
            if (wait > 0) {
                if (--wait == 0) report();
                return;
            }
            // Try four sites separated by over the pack's 800-block tower spacing.
            int site = requested / 16;
            int cx = baseX + 80 * site + requested % 4;
            int cz = baseZ + requested % 16 / 4;
            requested++;
            observing = true;
            world.getChunkProvider().provideChunk(cx, cz);
            observing = false;
            Set<ChunkPos> watched = new HashSet<>();
            for (TileEntityChest chest : born) {
                BlockPos pos = chest.getPos();
                if (chest.getWorld() != world || chest.isInvalid() || world.getTileEntity(pos) != chest) continue;
                boolean hasLoot = hasLoot(chest);
                for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
                    TileEntity other = world.getTileEntity(pos.offset(side));
                    if (other instanceof TileEntityChest && born.contains(other)) hasLoot |= hasLoot((TileEntityChest) other);
                }
                if (hasLoot) {
                    eligible.add(pos.toImmutable());
                    watched.add(new ChunkPos(pos));
                }
            }
            if (!eligible.isEmpty()) {
                EntityPlayerMP actor = viewer.get();
                for (ChunkPos pos : watched) {
                    for (int dx = -1; dx <= 2; dx++) {
                        for (int dz = -1; dz <= 2; dz++) world.getChunkProvider().provideChunk(pos.x + dx, pos.z + dz);
                    }
                    MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(world.getChunkFromChunkCoords(pos.x, pos.z), actor));
                }
                wait = 100;
            } else if (requested >= 64) {
                LOG.warn("NO SAMPLE: no eligible Battle Towers chest constructed by the installed generator in these regions");
                world = null;
            }
        } catch (RuntimeException failure) {
            LOG.error("Natural generation probe failed", failure);
            world = null;
        } finally {
            observing = false;
        }
    }

    private boolean hasLoot(TileEntityChest chest) {
        return ContainerLoot.isManaged(chest) || chest.getLootTable() != null || !chest.isEmpty();
    }

    private void report() {
        int managed = 0;
        int ready = 0;
        Set<UUID> groups = new HashSet<>();
        for (BlockPos pos : eligible) {
            TileEntity tile = world.getTileEntity(pos);
            if (LockAccess.worldgenReady(world.getChunkFromBlockCoords(pos))) ready++;
            if (ContainerLoot.isManaged(tile)) {
                managed++;
                groups.add(tile.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id"));
            } else {
                LOG.warn("Unregistered naturally generated Battle Towers half at {}", pos);
            }
        }
        LOG.info("NATURAL {} requestedChunks={} eligibleHalves={} managedHalves={} lockPhaseReady={} distinctGroups={} example={}",
                managed == eligible.size() ? "PASS" : "FAIL", requested, eligible.size(), managed, ready, groups.size(), eligible.iterator().next());
        world = null;
        born.clear();
        eligible.clear();
    }
}
