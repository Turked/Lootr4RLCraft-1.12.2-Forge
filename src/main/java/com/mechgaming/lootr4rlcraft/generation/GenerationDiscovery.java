package com.mechgaming.lootr4rlcraft.generation;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import com.mechgaming.lootr4rlcraft.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.feature.WorldGeneratorBonusChest;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Records inventory provenance at construction during an actual generator call, independently of chunk reach. */
public final class GenerationDiscovery {
    private final BiConsumer<WorldServer, Set<BlockPos>> generated;
    private final ThreadLocal<Deque<Scope>> active = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<WorldServer> initializingSpawn = new ThreadLocal<>();
    private final Map<WorldServer, Map<ChunkPos, Scope>> populations = new IdentityHashMap<>();
    private final Map<WorldServer, Map<String, Deque<Scope>>> structures = new IdentityHashMap<>();
    private final Map<WorldServer, Map<ChunkPos, Set<TileEntity>>> terrainTiles = new IdentityHashMap<>();
    private final Map<WorldServer, Set<TileEntity>> completed = new IdentityHashMap<>();
    private final Set<Chunk> diskLoaded = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean registered;

    public GenerationDiscovery(BiConsumer<WorldServer, Set<BlockPos>> generated) {
        this.generated = generated;
    }

    public void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(this);
        if (Loader.isModLoaded("reccomplex")) {
            try {
                Class<?> events = Class.forName("ivorius.reccomplex.events.RCEventBus");
                ((EventBus) events.getField("INSTANCE").get(null)).register(this);
            } catch (ReflectiveOperationException | LinkageError failure) {
                Lootr4RLCraft.LOGGER.warn("Could not subscribe to Recurrent Complex's structure event bus", failure);
            }
        }
    }

    public void beginPopulation(WorldServer world, int chunkX, int chunkZ, IChunkGenerator generator) {
        Map<ChunkPos, Scope> byChunk = populations.computeIfAbsent(world, ignored -> new HashMap<>());
        ChunkPos key = new ChunkPos(chunkX, chunkZ);
        Scope previous = byChunk.remove(key);
        if (previous != null) {
            active.get().remove(previous);
        }
        Set<String> sources = new HashSet<>();
        for (Class<?> type = generator.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            sources.add(type.getName());
        }
        sources.add("net.minecraftforge.fml.common.registry.GameRegistry");
        Scope scope = new Scope(world, sources);
        byChunk.put(key, scope);
        active.get().push(scope);
        Map<ChunkPos, Set<TileEntity>> born = terrainTiles.get(world);
        if (born != null) {
            Set<TileEntity> initial = born.get(key);
            if (initial != null) {
                scope.tiles.addAll(initial);
            }
        }
    }

    public void ensurePopulation(WorldServer world, int chunkX, int chunkZ, IChunkGenerator generator) {
        Map<ChunkPos, Scope> byChunk = populations.get(world);
        if (byChunk == null || !byChunk.containsKey(new ChunkPos(chunkX, chunkZ))) {
            beginPopulation(world, chunkX, chunkZ, generator);
        }
    }

    public void finishPopulation(WorldServer world, int chunkX, int chunkZ) {
        Map<ChunkPos, Scope> byChunk = populations.get(world);
        Scope scope = byChunk == null ? null : byChunk.remove(new ChunkPos(chunkX, chunkZ));
        if (scope != null) {
            finish(scope);
        }
        if (byChunk != null && byChunk.isEmpty()) {
            populations.remove(world);
        }
        Map<ChunkPos, Set<TileEntity>> born = terrainTiles.get(world);
        if (born != null) {
            Set<TileEntity> initial = born.remove(new ChunkPos(chunkX, chunkZ));
            if (initial != null) {
                completed.computeIfAbsent(world, ignored -> identities()).addAll(initial);
            }
            if (born.isEmpty()) {
                terrainTiles.remove(world);
            }
        }
        flush(world);
    }

    @SubscribeEvent
    public void constructed(AttachCapabilitiesEvent<TileEntity> event) {
        capture(event.getObject(), null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void creatingSpawn(WorldEvent.CreateSpawnPosition event) {
        if (Settings.registerNewChests && event.getWorld() instanceof WorldServer && event.getSettings().isBonusChestEnabled()) {
            initializingSpawn.set((WorldServer) event.getWorld());
        }
    }

    @SubscribeEvent
    public void entityConstructed(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof EntityMinecartChest) {
            capture(null, event.getObject());
        }
    }

    /** The same object must be constructed and spawned by a generator in this world and scope. */
    public boolean generatedEntity(WorldServer world, Entity entity) {
        for (Scope scope : active.get()) {
            if (scope.world == world && scope.entities.contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private void capture(TileEntity tile, Entity entity) {
        Deque<Scope> scopes = active.get();
        WorldServer spawn = initializingSpawn.get();
        if (scopes.isEmpty() && spawn == null) {
            return;
        }
        Set<String> callers = new HashSet<>();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            // A generator may synchronously load an old chunk, constructing its entities inside
            // the generator's call stack. Neither old tile inventories nor old carts belong to it.
            if (AnvilChunkLoader.class.getName().equals(frame.getClassName())) {
                return;
            }
            callers.add(frame.getClassName());
        }
        if (tile != null && spawn != null && callers.contains(WorldGeneratorBonusChest.class.getName())) {
            completed.computeIfAbsent(spawn, ignored -> identities()).add(tile);
        }
        for (Scope scope : scopes) {
            if (!Collections.disjoint(scope.sources, callers)) {
                if (tile != null) {
                    scope.tiles.add(tile);
                } else if (entity.world == scope.world) {
                    scope.entities.add(entity);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void fromDisk(ChunkDataEvent.Load event) {
        Chunk chunk = event.getChunk();
        if (!(chunk.getWorld() instanceof WorldServer)) {
            return;
        }
        diskLoaded.add(chunk);
        Set<TileEntity> oldTiles = identities();
        oldTiles.addAll(chunk.getTileEntityMap().values());
        for (Scope scope : active.get()) {
            scope.tiles.removeAll(oldTiles);
        }
        Set<TileEntity> queued = completed.get(chunk.getWorld());
        if (queued != null) {
            queued.removeAll(oldTiles);
        }
    }

    @SubscribeEvent
    public void loaded(ChunkEvent.Load event) {
        Chunk chunk = event.getChunk();
        if (!(chunk.getWorld() instanceof WorldServer) || diskLoaded.contains(chunk) || !Settings.registerNewChests) {
            return;
        }
        Set<TileEntity> initial = identities();
        initial.addAll(chunk.getTileEntityMap().values());
        if (!initial.isEmpty()) {
            terrainTiles.computeIfAbsent((WorldServer) chunk.getWorld(), ignored -> new HashMap<>())
                    .put(chunk.getPos(), initial);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void structureEvent(Event event) {
        if (!Settings.registerNewChests) {
            return;
        }
        String type = event.getClass().getName();
        try {
            if (type.equals("ivorius.reccomplex.events.StructureGenerationEventLite$Pre")
                    || type.equals("ivorius.reccomplex.events.StructureGenerationEventLite$Post")) {
                World world = ((WorldEvent) event).getWorld();
                if (world instanceof WorldServer) {
                    String key = "rc-lite:" + call(event, "getStructureName") + ":" + call(event, "getBoundingBox")
                            + ":" + call(event, "getGenerationLayer");
                    structure((WorldServer) world, key, "ivorius.reccomplex.world.gen.feature.StructureGenerator",
                            type.endsWith("$Pre"), event.isCanceled());
                }
            } else if (type.equals("ivorius.reccomplex.events.StructureGenerationEvent$Pre")
                    || type.equals("ivorius.reccomplex.events.StructureGenerationEvent$Post")) {
                World world = ((WorldEvent) event).getWorld();
                if (world instanceof WorldServer) {
                    Object context = event.getClass().getField("spawnContext").get(event);
                    String key = "rc-full:" + System.identityHashCode(context);
                    structure((WorldServer) world, key, "ivorius.reccomplex.world.gen.feature.StructureGenerator",
                            type.endsWith("$Pre"), event.isCanceled());
                }
            } else if (type.equals("atomicstryker.ruins.common.EventRuinTemplateSpawn")) {
                World world = ((WorldEvent) event).getWorld();
                if (world instanceof WorldServer) {
                    String key = "ruins:" + System.identityHashCode(field(event, "template")) + ":"
                            + field(event, "x") + ":" + field(event, "y") + ":" + field(event, "z") + ":" + field(event, "rotation");
                    structure((WorldServer) world, key, "atomicstryker.ruins.common.RuinTemplate",
                            (Boolean) field(event, "isPrePhase"), event.isCanceled());
                }
            } else if (type.equals("jaredbgreat.dldungeons.api.DLDEvent$AfterChestTileEntity")) {
                World world = (World) call(event, "getWorld");
                if (world instanceof WorldServer) {
                    completed.computeIfAbsent((WorldServer) world, ignored -> identities())
                            .add((TileEntity) call(event, "getContents"));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Lootr4RLCraft.LOGGER.warn("Could not observe structure generation event {}", type, failure);
        }
    }

    private void structure(WorldServer world, String key, String source, boolean before, boolean canceled) {
        Map<String, Deque<Scope>> bySource = structures.computeIfAbsent(world, ignored -> new HashMap<>());
        if (before) {
            if (!canceled) {
                Scope scope = new Scope(world, Collections.singleton(source));
                bySource.computeIfAbsent(key, ignored -> new ArrayDeque<>()).push(scope);
                active.get().push(scope);
            }
        } else {
            Deque<Scope> waiting = bySource.get(key);
            if (waiting != null && !waiting.isEmpty()) {
                finish(waiting.pop());
                if (waiting.isEmpty()) {
                    bySource.remove(key);
                }
            }
        }
        if (bySource.isEmpty()) {
            structures.remove(world);
        }
    }

    private void finish(Scope scope) {
        active.get().remove(scope);
        completed.computeIfAbsent(scope.world, ignored -> identities()).addAll(scope.tiles);
    }

    public void endTick(WorldServer world) {
        // A generator that aborts before its matching completion event cannot own later player placements.
        active.get().removeIf(scope -> scope.world == world);
        populations.remove(world);
        structures.remove(world);
        if (initializingSpawn.get() == world) initializingSpawn.remove();
        flush(world);
    }

    public void flush(WorldServer world) {
        for (Scope scope : active.get()) {
            if (scope.world == world) {
                // A nested generator can return while the outer structure is still filling or pairing chests.
                return;
            }
        }
        Set<TileEntity> tiles = completed.remove(world);
        if (tiles == null || tiles.isEmpty()) {
            return;
        }
        Set<BlockPos> positions = new HashSet<>();
        for (TileEntity tile : tiles) {
            BlockPos pos = tile.getPos();
            if (!tile.isInvalid() && tile.getWorld() == world && world.isBlockLoaded(pos)
                    && world.getTileEntity(pos) == tile) {
                positions.add(pos.toImmutable());
            }
        }
        if (!positions.isEmpty()) {
            generated.accept(world, positions);
        }
    }

    public void unload(Chunk chunk) {
        diskLoaded.remove(chunk);
        Map<ChunkPos, Set<TileEntity>> born = terrainTiles.get(chunk.getWorld());
        if (born != null) {
            born.remove(chunk.getPos());
            if (born.isEmpty()) {
                terrainTiles.remove(chunk.getWorld());
            }
        }
        Set<TileEntity> queued = completed.get(chunk.getWorld());
        if (queued != null) {
            queued.removeIf(tile -> tile.getPos().getX() >> 4 == chunk.x && tile.getPos().getZ() >> 4 == chunk.z);
        }
    }

    public void unload(WorldServer world) {
        populations.remove(world);
        structures.remove(world);
        terrainTiles.remove(world);
        completed.remove(world);
        active.get().removeIf(scope -> scope.world == world);
        if (initializingSpawn.get() == world) initializingSpawn.remove();
        diskLoaded.removeIf(chunk -> chunk.getWorld() == world);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static Set<TileEntity> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final class Scope {
        private final WorldServer world;
        private final Set<String> sources;
        private final Set<TileEntity> tiles = identities();
        private final Set<Entity> entities = Collections.newSetFromMap(new IdentityHashMap<>());

        private Scope(WorldServer world, Set<String> sources) {
            this.world = world;
            this.sources = sources;
        }
    }
}
