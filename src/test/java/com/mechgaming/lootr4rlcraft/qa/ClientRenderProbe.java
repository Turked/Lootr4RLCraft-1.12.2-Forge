package com.mechgaming.lootr4rlcraft.qa;

import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.cart.CartEvents;
import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Test-only mod. Each run uses its own new world and exercises the ordinary network and GUI routes. */
@Mod(modid = "lootrrenderprobe", name = "Lootr render probe", version = "1", clientSideOnly = true)
public final class ClientRenderProbe {
    private static final Logger LOG = LogManager.getLogger("LootrRenderProbe");
    private static final String[] TYPES = System.getProperty("lootr.probeCases",
            "chest,double,trapped_chest,spruce,cabinet,charm_barrel,vc_barrel,cart,locked").split(",");
    private static final int Y = 160, Z = 72;
    private String phase = "title";
    private int sample, ticks;
    private String hud = "";
    private volatile String setupError;
    private volatile boolean prepared;
    private ItemStack fixtureKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (Boolean.getBoolean("lootr.renderProbe")) {
            MinecraftForge.EVENT_BUS.register(this);
        }
        if (System.getProperty("lootr.multiplayerProbe") != null) {
            MinecraftForge.EVENT_BUS.register(new MultiplayerRenderProbe());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void overlay(RenderGameOverlayEvent.Text event) {
        hud = String.join(" ", event.getLeft());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        if ("title".equals(phase)) {
            if (mc.currentScreen == null || ticks < 40) return;
            next("join");
            WorldSettings settings = new WorldSettings(826189, GameType.CREATIVE, false, false, WorldType.FLAT).enableCommands();
            String worldName = "render-probe-" + UUID.randomUUID().toString().substring(0, 8);
            LOG.info("Starting isolated world {}", worldName);
            mc.launchIntegratedServer(worldName, worldName, settings);
            return;
        }
        if (mc.player == null || mc.world == null || mc.getIntegratedServer() == null) return;
        final UUID uuid = mc.player.getUniqueID();
        final MinecraftServer server = mc.getIntegratedServer();
        final BlockPos target = new BlockPos(72 + sample * 4, Y, Z);
        switch (phase) {
            case "join":
                if (ticks < 50) break;
                mc.displayGuiScreen(null);
                server.addScheduledTask(() -> {
                    EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
                    WorldServer world = player.getServerWorld();
                    world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                    world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                    world.setWorldTime(6000);
                    for (int x = 66; x < 76 + 4 * TYPES.length; x++) {
                        for (int z = 66; z < 77; z++) world.setBlockState(new BlockPos(x, Y - 1, z), Blocks.STONE.getDefaultState());
                    }
                    player.setGameType(GameType.CREATIVE);
                    player.connection.setPlayerLocation(72.5, Y, Z - 2.8, 0, 19);
                });
                next("prepare");
                break;
            case "prepare":
                if (ticks < 140) break;
                if (mc.currentScreen != null) mc.displayGuiScreen(null);
                final String type = TYPES[sample];
                prepared = false;
                setupError = null;
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
                        WorldServer world = player.getServerWorld();
                        player.connection.setPlayerLocation(target.getX() + 0.5, Y, Z - 2.8, 0, 19);
                        if ("cart".equals(type)) {
                            EntityMinecartChest cart = new EntityMinecartChest(world, target.getX() + 0.5, Y, Z + 0.5);
                            world.spawnEntity(cart);
                            LOG.info("Fixture cart: {}", CartEvents.convert(world, cart, true));
                            return;
                        }
                        IBlockState state = state(type);
                        world.setBlockState(target, state);
                        if ("spruce".equals(type)) {
                            TileEntity tile = world.getTileEntity(target);
                            NBTTagCompound nbt = tile.writeToNBT(new NBTTagCompound());
                            nbt.setString("type", "spruce");
                            tile.readFromNBT(nbt);
                            tile.markDirty();
                            world.notifyBlockUpdate(target, state, state, 3);
                        }
                        if ("double".equals(type)) world.setBlockState(target.east(), state);
                        if ("locked".equals(type)) {
                            player.setGameType(GameType.SURVIVAL);
                            ItemStack lock = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("locks:wood_lock")));
                            fixtureKey = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("locks:key")));
                            Class.forName("melonslise.locks.common.item.LockingItem").getMethod("copyId", ItemStack.class,
                                    ItemStack.class).invoke(null, lock, fixtureKey);
                            player.setHeldItem(EnumHand.MAIN_HAND, lock);
                            lock.getItem().onItemUse(player, world, target, EnumHand.MAIN_HAND, EnumFacing.NORTH, 0.5F, 0.5F, 0F);
                            player.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                            if (LockAccess.canOpen(world, target)) throw new IllegalStateException("Fixture lock did not attach");
                        }
                        if (server.getCommandManager().executeCommand(player, "lootr4rlcraft convert-fixed "
                                + target.getX() + " " + Y + " " + Z) != 1) {
                            throw new IllegalStateException(ContainerLoot.inspect(world, target));
                        }
                        LOG.info("Fixture {} = {}", type, ContainerLoot.inspect(world, target));
                    } catch (Exception failure) {
                        setupError = failure.toString();
                    } finally {
                        prepared = true;
                    }
                });
                next("unopened");
                break;
            case "unopened":
                if (!prepared || ticks < 50) break;
                if (setupError != null) {
                    LOG.error("Fixture {} setup failed: {}", TYPES[sample], setupError);
                    advance(mc);
                    break;
                }
                capture(mc, "before");
                RayTraceResult hit = mc.objectMouseOver;
                if ("cart".equals(TYPES[sample]) && hit != null && hit.entityHit instanceof EntityMinecartChest) {
                    mc.playerController.interactWithEntity(mc.player, hit.entityHit, EnumHand.MAIN_HAND);
                    next("menu");
                    break;
                }
                if (hit == null || !target.equals(hit.getBlockPos())) {
                    LOG.error("Target miss at {}: {}", target, hit);
                    advance(mc);
                    break;
                }
                mc.playerController.processRightClickBlock(mc.player, mc.world, target, hit.sideHit, hit.hitVec, EnumHand.MAIN_HAND);
                next("locked".equals(TYPES[sample]) ? "denied" : "menu");
                break;
            case "denied":
                if (ticks < 30) break;
                if (mc.currentScreen instanceof GuiContainer || !hud.contains("Unopened")) {
                    LOG.error("Locked fixture admitted player or changed opened state");
                    advance(mc);
                    break;
                }
                capture(mc, "locked-denied");
                server.addScheduledTask(() -> {
                    EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
                    player.setHeldItem(EnumHand.MAIN_HAND, fixtureKey.copy());
                    player.inventoryContainer.detectAndSendChanges();
                });
                next("key");
                break;
            case "key":
                if (ticks < 20) break;
                RayTraceResult door = mc.objectMouseOver;
                mc.playerController.processRightClickBlock(mc.player, mc.world, target, door.sideHit, door.hitVec, EnumHand.MAIN_HAND);
                next("unlocked");
                break;
            case "unlocked":
                if (ticks < 20) break;
                server.addScheduledTask(() -> {
                    EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
                    LOG.info("Native key changed lock to accessible={}", LockAccess.canOpen(player.world, target));
                    player.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
                    player.inventoryContainer.detectAndSendChanges();
                });
                next("unlocked-open");
                break;
            case "unlocked-open":
                if (ticks < 20) break;
                RayTraceResult doorAfterKey = mc.objectMouseOver;
                mc.playerController.processRightClickBlock(mc.player, mc.world, target, doorAfterKey.sideHit,
                        doorAfterKey.hitVec, EnumHand.MAIN_HAND);
                next("menu");
                break;
            case "menu":
                if (ticks < 25) break;
                if (mc.currentScreen instanceof GuiContainer) {
                    capture(mc, "menu");
                    mc.player.closeScreen();
                } else {
                    LOG.error("No inventory screen for {}", TYPES[sample]);
                }
                next("opened");
                break;
            case "opened":
                if (ticks < 30) break;
                capture(mc, "after");
                advance(mc);
                break;
            default:
                break;
        }
    }

    private void advance(Minecraft mc) {
        if (++sample == TYPES.length) {
            next("finished");
            LOG.info("Probe finished");
            mc.shutdown();
        } else {
            next("prepare");
        }
    }

    private void capture(Minecraft mc, String stage) {
        String name = "probe-" + TYPES[sample] + "-" + stage + ".png";
        LOG.info("{} at {}, HUD [{}], screen [{}], capture {}", name, mc.objectMouseOver, hud,
                mc.currentScreen, ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight,
                        mc.getFramebuffer()).getUnformattedText());
    }

    private void next(String nextPhase) {
        phase = nextPhase;
        ticks = 0;
    }

    private static IBlockState state(String type) {
        String key;
        switch (type) {
            case "locked": case "double": case "chest": key = "minecraft:chest"; break;
            case "trapped_chest": key = "minecraft:trapped_chest"; break;
            case "spruce": key = "quark:custom_chest"; break;
            case "cabinet": key = "rustic:cabinet"; break;
            case "charm_barrel": key = "charm:barrel"; break;
            case "vc_barrel": key = "variedcommodities:barrel"; break;
            default: throw new IllegalArgumentException(type);
        }
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(key));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalArgumentException("Unknown " + key + ", registry candidates " +
                    ForgeRegistries.BLOCKS.getKeys().stream().map(Object::toString)
                            .filter(s -> s.contains("barrel") || s.contains("cabinet") || s.contains("custom_chest"))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        IBlockState state = block.getDefaultState();
        for (IProperty<?> prop : state.getPropertyKeys()) {
            if ("facing".equals(prop.getName()) && prop.getAllowedValues().contains(EnumFacing.NORTH)) {
                state = property(state, prop, "north");
            }
            if ("spruce".equals(type) && prop.getAllowedValues().stream().anyMatch(v -> "spruce".equalsIgnoreCase(v.toString()))) {
                state = property(state, prop, "spruce");
            }
        }
        LOG.info("Using state {} for {}", state, type);
        return state;
    }

    private static <T extends Comparable<T>> IBlockState property(IBlockState state, IProperty<T> prop, String value) {
        return state.withProperty(prop, prop.parseValue(value.toLowerCase(Locale.ROOT)).get());
    }
}
