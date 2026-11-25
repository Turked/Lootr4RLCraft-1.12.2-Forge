package com.mechgaming.lootr4rlcraft.integration;

import com.mechgaming.lootr4rlcraft.Settings;
import com.mechgaming.lootr4rlcraft.adapters.GenericContainersTest;
import com.mechgaming.lootr4rlcraft.adapters.GenericMimicTest;
import com.mechgaming.lootr4rlcraft.chest.ChestEvents;
import com.mechgaming.lootr4rlcraft.chest.InstancedChestTile;
import com.mechgaming.lootr4rlcraft.chest.DeferredChestRegistrationTest;
import com.mechgaming.lootr4rlcraft.command.LootrCommand;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.container.ContainerEvents;
import com.mechgaming.lootr4rlcraft.network.ChestViews;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockChest;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketOpenWindow;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.server.management.PlayerList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.translation.LanguageMap;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.network.FMLEmbeddedChannel;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.relauncher.Side;

/** Native world, command, chest and loot integration without a server run loop or listening socket. */
public final class NativeChestIntegrationTest {
    private static final ResourceLocation APPLES = new ResourceLocation("lootr4rlcraft_test", "apples");
    private static final ResourceLocation DIAMONDS = new ResourceLocation("lootr4rlcraft_test", "diamonds");

    private NativeChestIntegrationTest() {}

    public static void main(String[] args) {
        try {
            Bootstrap.register();
            if (TileEntity.getKey(InstancedChestTile.class) == null) {
                GameRegistry.registerTileEntity(InstancedChestTile.class, new ResourceLocation("lootr4rlcraft", "structure_chest"));
            }
            verify();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        // Native Anvil loading starts Minecraft's persistent I/O thread. This task owns its isolated JVM.
        System.exit(0);
    }

    public static void verify() throws IOException {
        if (DimensionManager.getWorld(0) != null) {
            throw new AssertionError("Integration fixture requires no running world");
        }
        Path directory = Files.createTempDirectory("Lootr4RLCraft-NativeChestTest-");
        initializeForgeDirectory(directory);
        loadDefaultSettings(directory);
        try (InputStream language = NativeChestIntegrationTest.class.getResourceAsStream("/assets/lootr4rlcraft/lang/en_us.lang")) {
            if (language == null) {
                throw new AssertionError("Missing Lootr4RLCraft menu labels");
            }
            LanguageMap.inject(language);
        }
        check(Settings.registerNewChests && Settings.generatedFixedChests,
                "Default settings must register newly generated table and fixed loot chests");
        DataFixer fixer = DataFixesManager.createFixer();
        FixtureServer server = new FixtureServer(directory.toFile(), fixer);
        bindForgeServer(server);
        AnvilSaveHandler save = new AnvilSaveHandler(directory.toFile(), "world", true, fixer);
        writeTable(save, APPLES, "minecraft:apple", 4);
        writeTable(save, DIAMONDS, "minecraft:diamond", 3);
        WorldSettings settings = new WorldSettings(7L, GameType.ADVENTURE, false, false, WorldType.FLAT)
                .setGeneratorOptions("3;minecraft:air;1;");
        WorldInfo info = new WorldInfo(settings, "Lootr4RLCraft native integration");
        info.setSpawn(new BlockPos(8, 70, 8));
        WorldServer world = new WorldServer(server, save, info, 0, new Profiler());
        world.init();
        ChestEvents events = new ChestEvents();
        try {
            GenericContainersTest.initializeCapabilities();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    GenericContainersTest.attachChunk(world.getChunkProvider().provideChunk(x, z));
                }
            }
            ChestViews.register();
            EntityPlayerMP[] players = verifyCommandsAndInventories(server, world, events);
            SaveOrderTest.verify(world);
            com.mechgaming.lootr4rlcraft.cart.CartIntegrationTest.verify(world, players, APPLES);
            GenerationDiscoveryTest.verifyProductionGeneration(world, events, players, APPLES, DIAMONDS);
            GenericContainersTest.verify(world, players);
            GenericMimicTest.verify(world, players);
            if (Boolean.getBoolean("lootr4rlcraft.testInstalledContainers")) {
                InstalledContainerTest.verify(world, player(server, world, "InstalledAdapterTest", 99L));
            }
            DeferredChestRegistrationTest.verify(world);
            GenerationDiscoveryTest.verify(world);
            System.out.println("Verified native world generation, conversion commands, chest menus, loot tables and saved player inventories");
        } finally {
            DimensionManager.setWorld(0, null, server);
            save.flush();
        }
    }

    private static EntityPlayerMP[] verifyCommandsAndInventories(FixtureServer server, WorldServer world, ChestEvents events) {
        LootrCommand command = new LootrCommand();
        ConsoleSender sender = new ConsoleSender(server, world);
        check(command.getRequiredPermissionLevel() == 2, "Conversion must require operator permissions");

        BlockPos single = new BlockPos(3, 70, 8);
        place(world, single, APPLES);
        run(command, server, sender, "inspect", single);
        check(sender.message.contains("minecraft:chest"), "Inspect omitted the actual block identifier");
        run(command, server, sender, "convert", single);
        check(managed(world.getTileEntity(single)), "Command did not convert the single chest");

        EntityPlayerMP first = player(server, world, "First", 1L);
        EntityPlayerMP second = player(server, world, "Second", 2L);
        EntityPlayerMP third = player(server, world, "Third", 3L);
        EntityPlayerMP[] players = { first, second, third };
        for (EntityPlayerMP viewer : players) {
            Container container = open(world, events, single, viewer, 27);
            check(count(container, Items.APPLE, 27) == 4, "Native single-chest table rules changed");
            if (viewer == first) {
                clearLoot(container, 27);
            }
            viewer.closeScreen();
        }
        reload(world, single);
        Container reopened = open(world, events, single, first, 27);
        check(count(reopened, Items.APPLE, 27) == 0, "Saved withdrawal rerolled the first player's inventory");
        first.closeScreen();

        BlockPos doubleLeft = new BlockPos(6, 70, 8);
        BlockPos doubleRight = doubleLeft.east();
        place(world, doubleLeft, APPLES);
        place(world, doubleRight, DIAMONDS);
        run(command, server, sender, "convert", doubleRight);
        for (EntityPlayerMP viewer : players) {
            Container container = open(world, events, doubleRight, viewer, 54);
            check(count(container, Items.APPLE, 54) == 4 && count(container, Items.DIAMOND, 54) == 3,
                    "Double chest did not retain both native loot tables");
            if (viewer == first) {
                clearLoot(container, 54);
            }
            viewer.closeScreen();
        }
        reload(world, doubleLeft);
        reload(world, doubleRight);
        reopened = open(world, events, doubleLeft, first, 54);
        check(count(reopened, Items.APPLE, 54) == 0 && count(reopened, Items.DIAMOND, 54) == 0,
                "Double chest lost persisted player state after reloading both halves");
        first.closeScreen();

        BlockPos fixed = new BlockPos(11, 70, 8);
        TileEntityChest prefilled = place(world, fixed, null);
        prefilled.setInventorySlotContents(4, new ItemStack(Items.BOOK, 8));
        expectFailure(command, server, sender, "convert", fixed);
        check(world.getTileEntity(fixed) == prefilled && prefilled.getStackInSlot(4).getCount() == 8,
                "Table-only conversion altered a prefilled inventory");
        run(command, server, sender, "convert-fixed", fixed);
        for (EntityPlayerMP viewer : players) {
            Container container = open(world, events, fixed, viewer, 27);
            check(container.getSlot(4).getStack().getItem() == Items.BOOK
                    && container.getSlot(4).getStack().getCount() == 8, "Fixed conversion changed item positions or counts");
            if (viewer == first) {
                clearLoot(container, 27);
            }
            viewer.closeScreen();
        }
        reload(world, fixed);
        reopened = open(world, events, fixed, first, 27);
        check(count(reopened, Items.BOOK, 27) == 0, "Fixed conversion lost an emptied inventory after reload");
        first.closeScreen();
        expectFailure(command, server, sender, "convert-fixed", fixed);

        BlockPos busy = new BlockPos(13, 70, 8);
        TileEntityChest shared = place(world, busy, APPLES);
        activate(world, events, busy, first);
        check(first.openContainer != first.inventoryContainer && shared.getLootTable() == null,
                "Could not open the ordinary native chest for the active-viewer conversion check");
        expectFailure(command, server, sender, "convert-fixed", busy);
        check(world.getTileEntity(busy) == shared, "Conversion replaced a chest with an active native viewer");
        first.closeScreen();

        BlockPos unloaded = new BlockPos(160000, 70, 160000);
        expectFailure(command, server, sender, "convert", unloaded);
        check(world.getChunkProvider().getLoadedChunk(unloaded.getX() >> 4, unloaded.getZ() >> 4) == null,
                "Command loaded the target chunk");
        return players;
    }

    private static TileEntityChest place(WorldServer world, BlockPos pos, ResourceLocation table) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState().withProperty(BlockChest.FACING, EnumFacing.NORTH), 3);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(pos);
        if (table != null) {
            chest.setLootTable(table, 42L);
        }
        return chest;
    }

    private static void run(LootrCommand command, FixtureServer server, ConsoleSender sender, String action, BlockPos pos) {
        try {
            command.execute(server, sender, new String[] {action, String.valueOf(pos.getX()),
                    String.valueOf(pos.getY()), String.valueOf(pos.getZ())});
        } catch (CommandException error) {
            throw new AssertionError("Command " + action + " failed at " + pos + ": " + error.getMessage(), error);
        }
    }

    private static void expectFailure(LootrCommand command, FixtureServer server, ConsoleSender sender,
            String action, BlockPos pos) {
        try {
            command.execute(server, sender, new String[] {action, String.valueOf(pos.getX()),
                    String.valueOf(pos.getY()), String.valueOf(pos.getZ())});
        } catch (CommandException expected) {
            return;
        }
        throw new AssertionError("Command unexpectedly accepted " + action + " at " + pos);
    }

    private static EntityPlayerMP player(FixtureServer server, WorldServer world, String name, long id) {
        EntityPlayerMP player = new EntityPlayerMP(server, world, new GameProfile(new UUID(0L, id), name),
                new PlayerInteractionManager(world));
        player.interactionManager.setGameType(GameType.SURVIVAL);
        NetworkManager manager = new NetworkManager(EnumPacketDirection.SERVERBOUND) {
            private final Channel embedded = new EmbeddedChannel();

            @Override
            public Channel channel() {
                return embedded;
            }
        };
        new RecordingConnection(server, manager, player);
        check(world.spawnEntity(player), "Could not attach the fixture player to its native world");
        server.getPlayerList().getPlayers().add(player);
        return player;
    }

    private static Container open(WorldServer world, ChestEvents events, BlockPos pos, EntityPlayerMP player, int slots) {
        activate(world, events, pos, player);
        Container container = player.openContainer;
        check(container != player.inventoryContainer && container.inventorySlots.size() == slots + 36,
                "Native chest open did not create a private menu with " + slots + " loot slots");
        RecordingConnection connection = (RecordingConnection) player.connection;
        check(connection.title != null && connection.title.getUnformattedText().equals("lootr"),
                "Opened menu does not identify its Lootr chest");
        check(container instanceof ContainerChest, "Managed chest did not expose a chest container");
        IInventory lower = ((ContainerChest) container).getLowerChestInventory();
        check(lower.getSizeInventory() == slots, "Chest inventory accessor has the wrong private slot count");
        for (int slot = 0; slot < slots; slot++) {
            check(ItemStack.areItemStacksEqual(lower.getStackInSlot(slot), container.getSlot(slot).getStack()),
                    "Chest inventory accessor exposes different contents from the private menu");
        }
        return container;
    }

    private static void activate(WorldServer world, ChestEvents events, BlockPos pos, EntityPlayerMP player) {
        player.setPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() - 1.5D);
        // A plain JavaExec omits Forge's event bytecode transformer. Exercise the same handlers explicitly.
        PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(player,
                EnumHand.MAIN_HAND, pos, EnumFacing.NORTH, new Vec3d(pos).addVector(0.5D, 0.5D, 0.5D)) {
            @Override
            public boolean isCancelable() {
                return true;
            }
        };
        events.beforeClick(click);
        ContainerEvents generic = new ContainerEvents();
        generic.beforeClick(click);
        generic.prepareClick(click);
        events.afterClick(click);
        generic.afterClick(click);
        if (!click.isCanceled() && click.getUseBlock() != Event.Result.DENY) {
            player.interactionManager.processRightClickBlock(player, world, ItemStack.EMPTY, EnumHand.MAIN_HAND,
                    pos, EnumFacing.NORTH, 0.5F, 0.5F, 0.5F);
        }
    }

    private static int count(Container container, Item item, int slots) {
        int total = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = container.getSlot(slot).getStack();
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void clearLoot(Container container, int slots) {
        for (int slot = 0; slot < slots; slot++) {
            container.getSlot(slot).putStack(ItemStack.EMPTY);
        }
    }

    private static void reload(WorldServer world, BlockPos pos) {
        NBTTagCompound saved = world.getTileEntity(pos).writeToNBT(new NBTTagCompound());
        TileEntity reloaded = TileEntity.create(world, saved);
        check(managed(reloaded), "Saved tile did not restore its managed identity");
        world.setTileEntity(pos, reloaded);
    }

    private static boolean managed(TileEntity tile) {
        return tile instanceof InstancedChestTile || ContainerLoot.isManaged(tile);
    }

    private static void writeTable(AnvilSaveHandler save, ResourceLocation name, String item, int count) throws IOException {
        Path target = save.getWorldDirectory().toPath().resolve("data/loot_tables/")
                .resolve(name.getResourceDomain()).resolve(name.getResourcePath() + ".json");
        Files.createDirectories(target.getParent());
        String json = "{\"pools\":[{\"name\":\"fixture\",\"rolls\":1,\"entries\":[{\"type\":\"item\",\"name\":\""
                + item + "\",\"weight\":1,\"functions\":[{\"function\":\"set_count\",\"count\":" + count + "}]}]}]}";
        Files.write(target, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void loadDefaultSettings(Path directory) {
        try {
            Method load = Settings.class.getDeclaredMethod("load", File.class);
            load.setAccessible(true);
            load.invoke(null, directory.resolve("lootr4rlcraft.cfg").toFile());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not read default mod configuration", error);
        }
    }

    private static void initializeForgeDirectory(Path directory) {
        try {
            Method initialize = FMLInjectionData.class.getDeclaredMethod("build", File.class, LaunchClassLoader.class);
            initialize.setAccessible(true);
            initialize.invoke(null, directory.toFile(), null);
            Loader.injectData(FMLInjectionData.data());
            Field indexedMods = Loader.class.getDeclaredField("namedMods");
            indexedMods.setAccessible(true);
            indexedMods.set(Loader.instance(), Collections.emptyMap());
            ModMetadata metadata = new ModMetadata();
            metadata.modId = "lootr4rlcraft";
            metadata.name = "Lootr4RLCraft integration fixture";
            LoadController controller = new LoadController(Loader.instance());
            Field active = LoadController.class.getDeclaredField("activeContainer");
            active.setAccessible(true);
            active.set(controller, new DummyModContainer(metadata));
            Field controllerField = Loader.class.getDeclaredField("modController");
            controllerField.setAccessible(true);
            controllerField.set(Loader.instance(), controller);
            initializeMappedNetworkChannels();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize Forge's isolated test directory", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void initializeMappedNetworkChannels() throws ReflectiveOperationException {
        // ForgeGradle's 1.12.2 merge tool supplies a Side enum with an extra BUKKIT entry.
        // Forge's registry creates maps for CLIENT and SERVER, but newChannel iterates all
        // enum entries. The production Forge JAR has only the two real network sides.
        for (Side side : Side.values()) {
            if ("BUKKIT".equals(side.name())) {
                Field channelsField = NetworkRegistry.class.getDeclaredField("channels");
                channelsField.setAccessible(true);
                Map<Side, Map<String, FMLEmbeddedChannel>> channels =
                        (Map<Side, Map<String, FMLEmbeddedChannel>>) channelsField.get(NetworkRegistry.INSTANCE);
                channels.computeIfAbsent(side, unused -> new HashMap<>());
            }
        }
    }

    private static void bindForgeServer(MinecraftServer server) {
        // Starting FMLServerHandler initializes Forge's obfuscated tool mappings, which requires
        // LaunchWrapper. Supply the native server's real CompoundDataFixer to Entity.writeToNBT
        // without booting a second game in this MCP-named, plain-Java integration fixture.
        try {
            IFMLSidedHandler delegate = (IFMLSidedHandler) java.lang.reflect.Proxy.newProxyInstance(
                    IFMLSidedHandler.class.getClassLoader(), new Class<?>[] {IFMLSidedHandler.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getDataFixer": return server.getDataFixer();
                            case "getServer": return server;
                            case "getSide": return Side.SERVER;
                            default: throw new UnsupportedOperationException("Unexpected fixture side operation: " + method);
                        }
                    });
            Field field = FMLCommonHandler.class.getDeclaredField("sidedDelegate");
            field.setAccessible(true);
            field.set(FMLCommonHandler.instance(), delegate);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not attach the native Forge server data fixer", error);
        }
    }


    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class ConsoleSender implements ICommandSender {
        private final FixtureServer server;
        private final WorldServer world;
        private String message;

        private ConsoleSender(FixtureServer server, WorldServer world) {
            this.server = server;
            this.world = world;
        }

        @Override public String getName() { return "Lootr integration console"; }
        @Override public boolean canUseCommand(int level, String name) { return level <= 2; }
        @Override public World getEntityWorld() { return world; }
        @Override public MinecraftServer getServer() { return server; }
        @Override public void sendMessage(ITextComponent component) { message = component.getUnformattedText(); }
    }

    private static final class RecordingConnection extends NetHandlerPlayServer {
        private ITextComponent title;

        private RecordingConnection(MinecraftServer server, NetworkManager network, EntityPlayerMP player) {
            super(server, network, player);
        }

        @Override
        public void sendPacket(Packet<?> packet) {
            if (packet instanceof SPacketOpenWindow) {
                title = ((SPacketOpenWindow) packet).getWindowTitle();
            }
        }
    }

    private static final class FixtureServer extends MinecraftServer {
        private FixtureServer(File directory, DataFixer fixer) {
            super(directory, Proxy.NO_PROXY, fixer, null, null, null, null);
            setPlayerList(new PlayerList(this) {});
            getPlayerList().setViewDistance(3);
        }

        @Override public boolean init() { return true; }
        @Override public boolean canStructuresSpawn() { return false; }
        @Override public GameType getGameType() { return GameType.ADVENTURE; }
        @Override public EnumDifficulty getDifficulty() { return EnumDifficulty.NORMAL; }
        @Override public boolean isHardcore() { return false; }
        @Override public int getOpPermissionLevel() { return 4; }
        @Override public boolean shouldBroadcastRconToOps() { return false; }
        @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        @Override public boolean isDedicatedServer() { return false; }
        @Override public boolean shouldUseNativeTransport() { return false; }
        @Override public boolean isCommandBlockEnabled() { return true; }
        @Override public String shareToLAN(GameType mode, boolean cheats) { return null; }
        @Override public boolean isCallingFromMinecraftThread() { return true; }
    }
}
