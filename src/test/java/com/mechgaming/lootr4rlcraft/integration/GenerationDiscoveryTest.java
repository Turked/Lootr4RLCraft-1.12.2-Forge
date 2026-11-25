package com.mechgaming.lootr4rlcraft.integration;

import com.mechgaming.lootr4rlcraft.generation.GenerationDiscovery;
import com.mechgaming.lootr4rlcraft.adapters.GenericContainersTest;
import com.mechgaming.lootr4rlcraft.chest.ChestEvents;
import com.mechgaming.lootr4rlcraft.container.ContainerChunkData;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.GameType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.feature.WorldGeneratorBonusChest;
import net.minecraft.world.storage.loot.LootTableList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import net.minecraftforge.fml.common.eventhandler.ListenerList;

/** Regression for generation beyond the original four chunks and for disk-loaded player storage. */
public final class GenerationDiscoveryTest {
    private GenerationDiscoveryTest() {}

    public static void verifyProductionGeneration(WorldServer world, ChestEvents events, EntityPlayerMP[] players,
            ResourceLocation apples, ResourceLocation diamonds) throws IOException {
        // The JavaExec test JVM lacks FML's ASM listener-list transformer. Connect the actual Forge
        // constructor event to the mod's handler so the test never supplies the generated positions.
        int bus;
        GenerationDiscovery discovery;
        try {
            Field id = EventBus.class.getDeclaredField("busID");
            id.setAccessible(true);
            bus = id.getInt(MinecraftForge.EVENT_BUS);
            Field field = ChestEvents.class.getDeclaredField("discovery");
            field.setAccessible(true);
            discovery = (GenerationDiscovery) field.get(events);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not connect the Forge constructor listener", failure);
        }
        ListenerList listeners = new AttachCapabilitiesEvent<>(TileEntity.class, new TileEntityChest()).getListenerList();
        IEventListener bridge = event -> dispatchAttachment(discovery, event);
        listeners.register(bus, EventPriority.NORMAL, bridge);
        try {
            FixtureGenerator generator = new FixtureGenerator(world, discovery);
            Random random = new Random(76311L);
            BlockPos starter = new BlockPos(24, 47, 8);
            for (BlockPos pos : BlockPos.getAllInBox(starter.add(-4, 0, -4), starter.add(4, 0, 4))) {
                world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
            }
            discovery.creatingSpawn(new WorldEvent.CreateSpawnPosition(world,
                    new WorldSettings(913L, GameType.SURVIVAL, false, false, WorldType.FLAT).enableBonusChest()));
            generator.bonusChest(starter, random);
            discovery.endTick(world);
            check(world.getChunkFromBlockCoords(starter).getTileEntityMap().values().stream()
                    .anyMatch(tile -> tile.getPos().getY() == 48 && ContainerLoot.isManaged(tile)),
                    "Vanilla starter chest outside population missed at world initialization");
            events.beforePopulation(new PopulateChunkEvent.Pre(generator, world, random, 0, 0, false));
            BlockPos ordinary = new BlockPos(1, 64, 1);
            world.setBlockState(ordinary, Blocks.CHEST.getDefaultState(), 2);
            ((TileEntityChest) world.getTileEntity(ordinary)).setLootTable(apples, 19L);
            EntityMinecartChest ordinaryCart = new EntityMinecartChest(world);
            EntityMinecartChest generatedCart = generator.cart();
            check(!events.generatedEntity(world, ordinaryCart) && events.generatedEntity(world, generatedCart),
                    "Cart registration must distinguish construction inside the generator");
            generator.placeMixedChests(apples, diamonds);
            events.generate(random, 0, 0, world, generator, world.getChunkProvider());
            check(!events.generatedEntity(world, generatedCart), "A completed generator retained its cart");
            check(!ContainerLoot.isManaged(world.getTileEntity(ordinary)), "An unrelated new chest joined the population");
            for (int x : new int[] {3, 6, 9, 10}) {
                check(ContainerLoot.isManaged(world.getTileEntity(new BlockPos(x, 64, 3))),
                        "Generated table, fixed or paired chest missed at x=" + x);
            }
            Map<BlockPos, NBTTagCompound> expected = new LinkedHashMap<>();
            Map<Chunk, Path> saves = new LinkedHashMap<>();
            Path directory = Files.createTempDirectory("Lootr4RLCraft-100-generated-");
            for (int i = 0; i < 100; i++) {
                int chunkX = 32 + i % 10;
                int chunkZ = 32 + i / 10;
                Chunk chunk = world.getChunkProvider().provideChunk(chunkX, chunkZ);
                GenericContainersTest.attachChunk(chunk);
                BlockPos center = new BlockPos(chunkX * 16 + 8, 42, chunkZ * 16 + 8);
                for (BlockPos pos : BlockPos.getAllInBox(center.add(-4, 0, -4), center.add(4, 0, 4))) {
                    world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
                }
                events.beforePopulation(new PopulateChunkEvent.Pre(generator, world, random, chunkX, chunkZ, false));
                generator.bonusChest(center, random);
                TileEntityChest physical = (TileEntityChest) chunk.getTileEntityMap().values().stream()
                        .filter(tile -> tile instanceof TileEntityChest).findFirst().get();
                check(LootTableList.CHESTS_SPAWN_BONUS_CHEST.equals(physical.getLootTable()), "Vanilla generator lost table");
                expected.put(physical.getPos(), physical.writeToNBT(new NBTTagCompound()).copy());
                events.generate(random, chunkX, chunkZ, world, generator, world.getChunkProvider());
                check(ContainerLoot.isManaged(physical), "Newly generated chest missed at " + physical.getPos());
                saves.put(chunk, directory.resolve("chunk-" + i + ".nbt"));
            }
            Capability<ContainerChunkData> capability = ContainerChunkData.CAPABILITY;
            for (Map.Entry<BlockPos, NBTTagCompound> sample : expected.entrySet()) {
                BlockPos pos = sample.getKey();
                TileEntityChest control = (TileEntityChest) TileEntity.create(world, sample.getValue().copy());
                control.setWorld(world);
                control.fillWithLoot(players[0]);
                check(!control.isEmpty(), "Expected nonempty vanilla bonus chest roll");
                IInventory first = open(world, players[0], pos);
                assertSlots(control, first);
                for (int index = 0; index < first.getSizeInventory(); index++) {
                    first.setInventorySlotContents(index, ItemStack.EMPTY);
                }
                players[0].closeScreen();
                control = (TileEntityChest) TileEntity.create(world, sample.getValue().copy());
                control.setWorld(world);
                control.fillWithLoot(players[1]);
                assertSlots(control, open(world, players[1], pos));
                players[1].closeScreen();
            }
            for (Map.Entry<Chunk, Path> sample : saves.entrySet()) {
                Chunk chunk = sample.getKey();
                ContainerChunkData data = chunk.getCapability(capability, null);
                NBTTagCompound envelope = new NBTTagCompound();
                envelope.setTag("Records", capability.getStorage().writeNBT(capability, data, null));
                TileEntity physical = chunk.getTileEntityMap().values().stream().filter(t -> t instanceof TileEntityChest).findFirst().get();
                envelope.setTag("Tile", physical.writeToNBT(new NBTTagCompound()));
                CompressedStreamTools.write(envelope, sample.getValue().toFile());
                NBTTagCompound restored = CompressedStreamTools.read(sample.getValue().toFile());
                capability.getStorage().readNBT(capability, data, null, restored.getCompoundTag("Records"));
                TileEntity reloaded = TileEntity.create(world, restored.getCompoundTag("Tile"));
                world.setTileEntity(reloaded.getPos(), reloaded);
                check(open(world, players[0], reloaded.getPos()).isEmpty(), "Withdrawn items reappeared after NBT reload");
                players[0].closeScreen();
                TileEntityChest control = (TileEntityChest) TileEntity.create(world, expected.get(reloaded.getPos()).copy());
                control.setWorld(world);
                control.fillWithLoot(players[1]);
                assertSlots(control, open(world, players[1], reloaded.getPos()));
                players[1].closeScreen();
            }
            System.out.println("Verified 100/100 newly generated native bonus chests, seeded loot, two private menus and on-disk NBT round trips");
        } finally {
            listeners.unregister(bus, bridge);
        }
    }

    @SuppressWarnings("unchecked")
    private static void dispatchAttachment(GenerationDiscovery discovery, Event event) {
        if (event instanceof AttachCapabilitiesEvent<?>) {
            AttachCapabilitiesEvent<?> attachment = (AttachCapabilitiesEvent<?>) event;
            if (attachment.getObject() instanceof TileEntity) {
                discovery.constructed((AttachCapabilitiesEvent<TileEntity>) attachment);
            } else if (attachment.getObject() instanceof EntityMinecartChest) {
                discovery.entityConstructed((AttachCapabilitiesEvent<Entity>) attachment);
            }
        }
    }

    private static void assertSlots(IInventory expected, IInventory actual) {
        check(expected.getSizeInventory() == actual.getSizeInventory(), "Private chest changed slot count");
        for (int i = 0; i < expected.getSizeInventory(); i++) {
            check(ItemStack.areItemStacksEqual(expected.getStackInSlot(i), actual.getStackInSlot(i)),
                    "Private contents differ from the native seeded roll at slot " + i);
        }
    }

    private static IInventory open(WorldServer world, EntityPlayerMP player, BlockPos pos) {
        player.setPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        check(ContainerLoot.open(player, pos), "Private generated inventory unavailable at " + pos);
        return ((ContainerChest) player.openContainer).getLowerChestInventory();
    }

    public static void verify(WorldServer world) {
        Set<BlockPos> captured = new HashSet<>();
        GenerationDiscovery discovery = new GenerationDiscovery((source, positions) -> {
            check(source == world, "Discovery reported the wrong world");
            captured.addAll(positions);
        });
        FixtureGenerator generator = new FixtureGenerator(world, discovery);
        BlockPos existing = new BlockPos(1, 81, 1);
        BlockPos reused = new BlockPos(5, 81, 5);
        BlockPos nearby = new BlockPos(3, 81, 3);
        BlockPos distant = new BlockPos(210, 81, 170);
        BlockPos playerPlacement = new BlockPos(209, 81, 169);
        BlockPos stale = new BlockPos(9, 81, 9);
        BlockPos fromDisk = new BlockPos(320, 81, 320);
        place(world, discovery, existing);
        place(world, discovery, reused);

        discovery.beginPopulation(world, 0, 0, generator);
        place(world, discovery, playerPlacement);
        generator.place(nearby);
        generator.place(distant);
        generator.place(reused);
        generator.place(stale);
        generator.place(fromDisk);
        Chunk diskChunk = world.getChunkProvider().getLoadedChunk(20, 20);
        discovery.fromDisk(new ChunkDataEvent.Load(diskChunk, new NBTTagCompound()));
        discovery.loaded(new ChunkEvent.Load(diskChunk));
        place(world, discovery, stale);
        discovery.finishPopulation(world, 0, 0);
        check(captured.contains(nearby) && captured.contains(distant),
                "A generator-owned chest outside the original four chunks was missed");
        check(captured.contains(reused), "A new generator tile reusing an existing tile position was missed");
        check(!captured.contains(existing), "Discovery enrolled preexisting storage");
        check(!captured.contains(playerPlacement), "Discovery enrolled a placement outside a generator call");
        check(!captured.contains(stale), "Discovery enrolled a replacement that lacked generation provenance");
        check(!captured.contains(fromDisk), "A cascading disk load was mistaken for generated storage");

        captured.clear();
        BlockPos beforeAdditionalGenerators = new BlockPos(24, 81, 24);
        BlockPos afterAdditionalGenerators = new BlockPos(26, 81, 24);
        discovery.beginPopulation(world, 1, 1, generator);
        generator.place(beforeAdditionalGenerators);
        discovery.ensurePopulation(world, 1, 1, generator);
        generator.place(afterAdditionalGenerators);
        discovery.finishPopulation(world, 1, 1);
        check(captured.contains(beforeAdditionalGenerators) && captured.contains(afterAdditionalGenerators),
                "The additional-generator entry hook reset an existing population scope");

        captured.clear();
        BlockPos additionalGeneratorOnly = new BlockPos(40, 81, 40);
        discovery.ensurePopulation(world, 2, 2, generator);
        generator.place(additionalGeneratorOnly);
        discovery.finishPopulation(world, 2, 2);
        check(captured.contains(additionalGeneratorOnly),
                "An additional generator without a terrain-population event was missed");

        captured.clear();
        BlockPos terrainChest = new BlockPos(336, 81, 336);
        place(world, discovery, terrainChest);
        Chunk terrainChunk = world.getChunkProvider().getLoadedChunk(21, 21);
        discovery.loaded(new ChunkEvent.Load(terrainChunk));
        discovery.beginPopulation(world, 21, 21, generator);
        discovery.finishPopulation(world, 21, 21);
        check(captured.contains(terrainChest), "A chest created during new terrain generation was missed");

        captured.clear();
        discovery.beginPopulation(world, 22, 22, generator);
        discovery.endTick(world);
        BlockPos afterAbort = new BlockPos(352, 81, 352);
        generator.place(afterAbort);
        discovery.finishPopulation(world, 22, 22);
        check(!captured.contains(afterAbort), "An aborted generation scope retained later tile placements");
        discovery.unload(world);
        System.out.println("Verified generation across a 211 by 171 area, replaced tiles, cascading disk loads and ordinary storage exclusion");
    }

    private static TileEntityChest place(WorldServer world, GenerationDiscovery discovery, BlockPos pos) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        TileEntityChest tile = new TileEntityChest();
        tile.setWorld(world);
        tile.setPos(pos);
        // A plain JavaExec omits Forge's event transformer. Invoke its real attachment listener explicitly.
        discovery.constructed(new AttachCapabilitiesEvent<>(TileEntity.class, tile));
        world.setTileEntity(pos, tile);
        return tile;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FixtureGenerator implements IChunkGenerator {
        private final WorldServer world;
        private final GenerationDiscovery discovery;
        private final IChunkGenerator delegate;

        private FixtureGenerator(WorldServer world, GenerationDiscovery discovery) {
            this.world = world;
            this.discovery = discovery;
            delegate = world.getChunkProvider().chunkGenerator;
        }

        private void place(BlockPos pos) {
            GenerationDiscoveryTest.place(world, discovery, pos);
        }

        private EntityMinecartChest cart() {
            return new EntityMinecartChest(world);
        }

        private void placeMixedChests(ResourceLocation apples, ResourceLocation diamonds) {
            for (int x : new int[] {3, 6, 9, 10}) {
                BlockPos pos = new BlockPos(x, 64, 3);
                world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 2);
                TileEntityChest tile = (TileEntityChest) world.getTileEntity(pos);
                if (x == 6) {
                    tile.setInventorySlotContents(0, new ItemStack(Items.BOOK, 2));
                } else {
                    tile.setLootTable(x == 10 ? diamonds : apples, 5L);
                }
            }
        }

        private void bonusChest(BlockPos center, Random random) {
            WorldGeneratorBonusChest feature = new WorldGeneratorBonusChest();
            for (int attempt = 0; attempt < 64; attempt++) {
                if (feature.generate(world, random, center.up())) {
                    return;
                }
            }
            throw new AssertionError("Vanilla bonus chest generator found no site at " + center);
        }

        @Override public Chunk generateChunk(int x, int z) { return delegate.generateChunk(x, z); }
        @Override public void populate(int x, int z) { delegate.populate(x, z); }
        @Override public boolean generateStructures(Chunk chunk, int x, int z) { return delegate.generateStructures(chunk, x, z); }
        @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) {
            return delegate.getPossibleCreatures(type, pos);
        }
        @Override @Nullable public BlockPos getNearestStructurePos(World world, String name, BlockPos pos, boolean unexplored) {
            return delegate.getNearestStructurePos(world, name, pos, unexplored);
        }
        @Override public void recreateStructures(Chunk chunk, int x, int z) { delegate.recreateStructures(chunk, x, z); }
        @Override public boolean isInsideStructure(World world, String name, BlockPos pos) {
            return delegate.isInsideStructure(world, name, pos);
        }
    }
}
