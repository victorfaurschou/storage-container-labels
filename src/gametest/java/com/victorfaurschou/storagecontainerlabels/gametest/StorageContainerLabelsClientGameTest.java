package com.victorfaurschou.storagecontainerlabels.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.terraformersmc.modmenu.ModMenu;
import com.victorfaurschou.storagecontainerlabels.client.StorageContainerLabelsClient;
import com.victorfaurschou.storagecontainerlabels.client.StorageContainerLabelsConfig;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Plays through the README: names containers with a named name tag in a real anvil, places them with
 * real input, and checks the labels two ways. The label list the mod builds gives exact positions, names and
 * colors. Screenshots with the label switched off and on, compared pixel by pixel, prove what reached the
 * screen, because rendering is where Minecraft changes the most between versions. Screenshots run with
 * Improved Transparency both off and on, since text goes through different render phases in each.
 *
 * <p>Label positions are written out by hand from the default offsets (X 0, Y -0.8, Z -0.7) rather than
 * computed with the mod's own {@code rotateOffset}, so they can disagree with it.
 */
public class StorageContainerLabelsClientGameTest implements FabricClientGameTest {
	private static final String ID = "storage-container-labels-gametest";
	private static final Logger LOGGER = LoggerFactory.getLogger(ID);

	private static final String MOD_ID = "storage-container-labels";
	private static final String COMMAND = "storage-container-labels";

	/** The mod rescans every 5 ticks; 2 more let a block entity packet arrive first. */
	private static final int RESCAN_TICKS = 7;
	private static final double POSITION_TOLERANCE = 0.0001;

	/** A pixel counts as changed when a color channel moved by more than this. */
	private static final int PIXEL_THRESHOLD = 8;
	/** "Food" at the default size, from 3 blocks away, covers far more pixels than this. */
	private static final int MIN_LABEL_PIXELS = 40;
	/** Anything beyond a few stray pixels means a label reached the screen. */
	private static final int MAX_HIDDEN_LABEL_PIXELS = 4;

	/** Longer than "Name Tag" and "Chest", the names the anvil's text box starts with here. */
	private static final int ANVIL_TEXT_MAX_DEFAULT_LENGTH = 12;

	private static final int GROUND_Y = -60;
	private static final String CHEST_NORTH = "minecraft:chest[facing=north]";

	// Separate areas of the flat test world, far enough apart that labels of one never show up in another
	private static final BlockPos ANVIL_AREA = new BlockPos(0, GROUND_Y, 0);
	private static final BlockPos ALL_TYPES_AREA = new BlockPos(64, GROUND_Y, 64); // chunk corner, to span 4 chunks
	private static final BlockPos RANGE_AREA = new BlockPos(128, GROUND_Y, 128);   // chunk corner as well
	private static final BlockPos NAMING_AREA = new BlockPos(192, GROUND_Y, 192);
	private static final BlockPos STAGE = new BlockPos(256, GROUND_Y, 256);
	private static final BlockPos OVERVIEW_AREA = new BlockPos(320, GROUND_Y, 320);

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		TestWorldSave save;
		List<Label> beforeReload;

		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			TestServerConnection connection = singleplayer.getConnection();
			connection.waitForChunksRender();
			server.runCommand("time set noon");
			server.runCommand("weather clear");

			checkDefaults();
			versionCommand(ctx);

			playerNamesAndPlacesAChest(ctx, singleplayer);
			anvilNamesEveryContainerType(ctx, singleplayer);

			server.runCommand("gamemode creative @a");
			labelPositions(ctx, server, connection);
			perTypeToggles(ctx, server, connection);
			renderDistance(ctx, server, connection);
			namingAPlacedContainer(ctx, server, connection);
			colorCodes(ctx, server, connection);
			customOffsets(ctx, server, connection);

			setHudHidden(ctx, true);
			rendering(ctx, server, connection, false);
			rendering(ctx, server, connection, true);
			restoreImprovedTransparency(ctx, connection);
			overviewScreenshot(ctx, server, connection);
			setHudHidden(ctx, false);

			configScreen(ctx);

			// Back to the area with every type, so the reload below has plenty to bring back
			setConfig(ctx, StorageContainerLabelsClientGameTest::showEverything);
			teleport(ctx, server, connection, ALL_TYPES_AREA);
			ctx.waitTicks(RESCAN_TICKS);
			beforeReload = labels(ctx);
			save = singleplayer.getWorldSave();
		}

		try (TestSingleplayerContext singleplayer = save.open()) {
			reloadedWorld(ctx, singleplayer, beforeReload);
		}

		dedicatedServer(ctx);

		if (failures.isEmpty()) {
			log("ALL CHECKS PASSED");
		} else {
			log(failures.size() + " CHECK(S) FAILED");
			throw new AssertionError("Storage Container Labels game test failed:\n - " + String.join("\n - ", failures));
		}
	}

	// ---- Commands ----

	private void checkDefaults() {
		check("hoppers, droppers and dispensers are off by default",
				!StorageContainerLabelsConfig.showForHoppers && !StorageContainerLabelsConfig.showForDroppers && !StorageContainerLabelsConfig.showForDispensers,
				"hoppers=" + StorageContainerLabelsConfig.showForHoppers + " droppers=" + StorageContainerLabelsConfig.showForDroppers
						+ " dispensers=" + StorageContainerLabelsConfig.showForDispensers);
		check("chests, chest variants, barrels and shulker boxes are on by default",
				StorageContainerLabelsConfig.showForChests && StorageContainerLabelsConfig.showForChestVariants
						&& StorageContainerLabelsConfig.showForBarrels && StorageContainerLabelsConfig.showForShulkerBoxes,
				"chests=" + StorageContainerLabelsConfig.showForChests + " variants=" + StorageContainerLabelsConfig.showForChestVariants
						+ " barrels=" + StorageContainerLabelsConfig.showForBarrels + " shulkers=" + StorageContainerLabelsConfig.showForShulkerBoxes);
	}

	private void versionCommand(ClientGameTestContext ctx) {
		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("missing");
		String mcVersion = SharedConstants.getCurrentVersion().name();
		String[] parts = mcVersion.split("\\.");
		String drop = parts.length >= 2 ? parts[0] + "." + parts[1] : mcVersion;
		check("mod_version carries the running Minecraft drop", version.endsWith("+" + drop),
				"mod version " + version + ", Minecraft " + mcVersion + ", drop " + drop);

		ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		typeCommand(ctx, COMMAND + " version");
		ctx.waitForScreen(null);
		String expected = "Storage Container Labels " + version;
		ctx.waitTicks(2);
		List<String> chat = chatLines(ctx);
		check("/" + COMMAND + " version replies in chat", chat.contains(expected), "expected \"" + expected + "\", got " + chat);
	}

	// ---- The README's naming steps, with a real anvil ----

	private void playerNamesAndPlacesAChest(ClientGameTestContext ctx, TestSingleplayerContext singleplayer) {
		TestServerContext server = singleplayer.getServer();
		TestServerConnection connection = singleplayer.getConnection();
		BlockPos anvil = ANVIL_AREA.south(2);

		server.runCommand("gamemode survival @a");
		teleport(ctx, server, connection, ANVIL_AREA);
		server.runCommand("clear @a");
		server.runCommand("experience set @a 5 levels");
		server.runCommand("setblock " + at(anvil) + " minecraft:anvil[facing=west]");
		server.runCommand("give @a minecraft:name_tag 2");
		server.runCommand("give @a minecraft:chest");
		ctx.waitTicks(3);

		openAnvil(ctx, anvil);

		// 1. "Rename a name tag using an anvil": vanilla's paid rename has to keep working
		moveToInput(ctx, Items.NAME_TAG, 0);
		replaceAnvilText(ctx, "&cFood");
		ctx.waitTicks(3);
		ItemStack renamedTag = anvilResult(singleplayer);
		check("anvil renames a name tag with typed text", renamedTag.is(Items.NAME_TAG) && "&cFood".equals(customName(renamedTag)),
				"result " + renamedTag + " named " + customName(renamedTag));
		int tagCost = anvilCost(singleplayer);
		check("renaming a name tag costs XP as in vanilla", tagCost > 0, "cost " + tagCost);
		takeResult(ctx);
		int levels = levels(singleplayer);
		check("the rename took its XP", levels == 5 - tagCost, "levels " + levels + ", expected " + (5 - tagCost));
		server.runCommand("experience set @a 0 levels");
		server.runCommand("give @a minecraft:name_tag");
		ctx.waitTicks(3);

		// A container alone can't be renamed by typing: name tags replaced that
		moveToInput(ctx, Items.CHEST, 0);
		replaceAnvilText(ctx, "Typed");
		ctx.waitTicks(3);
		check("typing a name on a container gives no result", anvilResult(singleplayer).isEmpty(), "result " + anvilResult(singleplayer));

		// ...and a name tag has to be named to do anything
		moveUnnamedTagToAddition(ctx);
		ctx.waitTicks(3);
		check("a blank name tag gives no result", anvilResult(singleplayer).isEmpty(), "result " + anvilResult(singleplayer));
		returnFromSlot(ctx, 1);

		// 2. "Combine the named tag with a container"
		moveNamedTagToAddition(ctx);
		ctx.waitTicks(3);
		ItemStack named = anvilResult(singleplayer);
		check("named tag + chest gives a chest with the tag's name", named.is(Items.CHEST) && "&cFood".equals(customName(named)),
				"result " + named + " named " + customName(named));
		int cost = anvilCost(singleplayer);
		check("naming a container is free", cost == 0, "cost " + cost);

		// 0 XP levels: vanilla refuses to hand over a 0-cost result, the mod has to allow it
		int hotbarSlot = takeResult(ctx);
		check("a player with 0 levels can take the named chest", clientSlot(ctx, hotbarSlot).is(Items.CHEST),
				"slot " + hotbarSlot + " holds " + clientSlot(ctx, hotbarSlot));
		check("taking the named chest costs no XP", levels(singleplayer) == 0, "levels " + levels(singleplayer));
		int tagsLeft = server.computeOnServer(s -> anvilMenu(singleplayer).getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().getCount());
		check("taking the named chest uses up one of the two named tags", tagsLeft == 1, "tags left in the anvil " + tagsLeft);

		// Menu slot numbers mean something else once the anvil is closed
		int hotbarIndex = ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(hotbarSlot).getContainerSlot());
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitForScreen(null);

		// 3. "Place the container and its name will be displayed as a floating label attached to it"
		BlockPos ground = ANVIL_AREA.east(2).south(2).below();
		BlockPos placed = ground.above();
		ctx.getInput().pressKey(o -> o.keyHotbarSlots[hotbarIndex]);
		ctx.waitTicks(2);
		ctx.getInput().lookAt(ground);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(RESCAN_TICKS);

		String serverName = server.computeOnServer(s -> s.overworld().getBlockEntity(placed) instanceof BaseContainerBlockEntity c && c.getCustomName() != null
				? c.getCustomName().getString() : "none");
		check("the placed chest keeps the name on the server", "&cFood".equals(serverName), "server name " + serverName);
		check("the client knows the chest's name", "&cFood".equals(clientName(ctx, placed)), "client name " + clientName(ctx, placed));

		BlockState state = ctx.computeOnClient(mc -> mc.level.getBlockState(placed));
		Direction facing = state.hasProperty(ChestBlock.FACING) ? state.getValue(ChestBlock.FACING) : Direction.NORTH;
		double[] expected = onFront(placed, facing);
		checkLabel("label of the placed chest (facing " + facing + ")", labels(ctx), "Food", expected);
		log("screenshot " + ctx.takeScreenshot("placed-chest"));
	}

	private void anvilNamesEveryContainerType(ClientGameTestContext ctx, TestSingleplayerContext singleplayer) {
		TestServerContext server = singleplayer.getServer();
		BlockPos anvil = ANVIL_AREA.south(2);

		server.runCommand("gamemode creative @a"); // doesn't wear the anvil out
		server.runCommand("clear @a");
		server.runCommand("setblock " + at(anvil) + " minecraft:anvil[facing=west]");
		server.runCommand("give @a minecraft:name_tag[minecraft:custom_name=\"Tagged\"] 16");
		ctx.waitTicks(3);

		openAnvil(ctx, anvil);
		moveToInput(ctx, Items.NAME_TAG, 1);

		List<String> containers = List.of("chest", "trapped_chest", "copper_chest", "waxed_oxidized_copper_chest",
				"barrel", "shulker_box", "red_shulker_box", "hopper", "dropper", "dispenser");
		for (String id : containers) {
			Item container = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
			server.runCommand("give @a minecraft:" + id);
			ctx.waitTicks(3);
			moveToInput(ctx, container, 0);
			ctx.waitTicks(3);
			ItemStack result = anvilResult(singleplayer);
			check("anvil names " + id, result.is(container) && "Tagged".equals(customName(result)) && anvilCost(singleplayer) == 0,
					"result " + result + " named " + customName(result) + ", cost " + anvilCost(singleplayer));
			returnFromSlot(ctx, AnvilMenu.RESULT_SLOT); // anywhere in the inventory: ten of these don't fit the hotbar
		}

		// Other block entities stay out of it
		server.runCommand("give @a minecraft:furnace");
		ctx.waitTicks(3);
		moveToInput(ctx, Items.FURNACE, 0);
		ctx.waitTicks(3);
		check("a named tag does nothing to a furnace", anvilResult(singleplayer).isEmpty(), "result " + anvilResult(singleplayer));

		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitForScreen(null);
		server.runCommand("clear @a");
	}

	// ---- Labels: what the mod builds ----

	private void labelPositions(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		setConfig(ctx, StorageContainerLabelsClientGameTest::showEverything);
		buildAllTypes(server);
		teleport(ctx, server, connection, ALL_TYPES_AREA);
		ctx.waitTicks(RESCAN_TICKS);
		List<Label> labels = labels(ctx);

		BlockPos o = ALL_TYPES_AREA;
		checkLabel("chest facing north", labels, "Chest N", onFront(o.offset(-5, 0, -5), Direction.NORTH));
		checkLabel("chest facing south", labels, "Chest S", onFront(o.offset(-3, 0, -5), Direction.SOUTH));
		checkLabel("chest facing east", labels, "Chest E", onFront(o.offset(-1, 0, -5), Direction.EAST));
		checkLabel("chest facing west", labels, "Chest W", onFront(o.offset(1, 0, -5), Direction.WEST));
		// Left half at x+3, right half at x+4: one label, centered on the pair
		BlockPos left = o.offset(3, 0, -5);
		checkLabel("double chest", labels, "Double", new double[]{left.getX() + 1.0, left.getY() + 0.45, left.getZ() - 0.2});
		check("a double chest has a single label", labels.stream().filter(l -> l.text().equals("Double")).count() == 1, "labels " + names(labels));
		labels.stream().filter(l -> l.text().equals("Double")).findFirst().ifPresent(l -> {
			long leftKey = left.asLong();
			long rightKey = left.east().asLong();
			check("a double chest's label knows both halves, for Show Focused Only",
					(l.pos1() == leftKey && l.pos2() == rightKey) || (l.pos1() == rightKey && l.pos2() == leftKey),
					"pos1 " + BlockPos.of(l.pos1()) + ", pos2 " + BlockPos.of(l.pos2()));
		});

		checkLabel("trapped chest", labels, "Trapped", onFront(o.offset(-5, 0, -3), Direction.NORTH));
		checkLabel("copper chest", labels, "Copper", onFront(o.offset(-3, 0, -3), Direction.NORTH));
		checkLabel("barrel facing up", labels, "Barrel up", onTop(o.offset(-1, 0, -3)));
		checkLabel("barrel facing down", labels, "Barrel down", below(o.offset(1, 0, -3)));
		checkLabel("barrel facing north", labels, "Barrel N", onFront(o.offset(3, 0, -3), Direction.NORTH));
		checkLabel("barrel facing east", labels, "Barrel E", onFront(o.offset(5, 0, -3), Direction.EAST));
		checkLabel("shulker box facing up", labels, "Shulker up", onTop(o.offset(-5, 0, 3)));
		checkLabel("shulker box facing south", labels, "Shulker S", onFront(o.offset(-3, 0, 3), Direction.SOUTH));
		checkLabel("hopper facing down", labels, "Hopper down", below(o.offset(-1, 0, 3)));
		checkLabel("hopper facing north", labels, "Hopper N", onFront(o.offset(1, 0, 3), Direction.NORTH));
		checkLabel("dropper facing south", labels, "Dropper", onFront(o.offset(3, 0, 3), Direction.SOUTH));
		checkLabel("dispenser facing west", labels, "Dispenser", onFront(o.offset(5, 0, 3), Direction.WEST));

		check("an unnamed chest has no label", labels.stream().noneMatch(l -> l.text().equals("Chest")), "labels " + names(labels));
		check("a named furnace has no label", labels.stream().noneMatch(l -> l.text().equals("Furnace")), "labels " + names(labels));
		check("exactly the expected number of labels", labels.size() == 17, labels.size() + " labels: " + names(labels));
	}

	private void buildAllTypes(TestServerContext server) {
		BlockPos o = ALL_TYPES_AREA;
		place(server, o.offset(-5, 0, -5), named(CHEST_NORTH, "Chest N"));
		place(server, o.offset(-3, 0, -5), named("minecraft:chest[facing=south]", "Chest S"));
		place(server, o.offset(-1, 0, -5), named("minecraft:chest[facing=east]", "Chest E"));
		place(server, o.offset(1, 0, -5), named("minecraft:chest[facing=west]", "Chest W"));
		place(server, o.offset(3, 0, -5), named("minecraft:chest[facing=north,type=left]", "Double"));
		place(server, o.offset(4, 0, -5), "minecraft:chest[facing=north,type=right]");

		place(server, o.offset(-5, 0, -3), named("minecraft:trapped_chest[facing=north]", "Trapped"));
		place(server, o.offset(-3, 0, -3), named("minecraft:copper_chest[facing=north]", "Copper"));
		place(server, o.offset(-1, 0, -3), named("minecraft:barrel[facing=up]", "Barrel up"));
		place(server, o.offset(1, 0, -3), named("minecraft:barrel[facing=down]", "Barrel down"));
		place(server, o.offset(3, 0, -3), named("minecraft:barrel[facing=north]", "Barrel N"));
		place(server, o.offset(5, 0, -3), named("minecraft:barrel[facing=east]", "Barrel E"));

		place(server, o.offset(-5, 0, 3), named("minecraft:shulker_box[facing=up]", "Shulker up"));
		place(server, o.offset(-3, 0, 3), named("minecraft:shulker_box[facing=south]", "Shulker S"));
		place(server, o.offset(-1, 0, 3), named("minecraft:hopper[facing=down]", "Hopper down"));
		place(server, o.offset(1, 0, 3), named("minecraft:hopper[facing=north]", "Hopper N"));
		place(server, o.offset(3, 0, 3), named("minecraft:dropper[facing=south]", "Dropper"));
		place(server, o.offset(5, 0, 3), named("minecraft:dispenser[facing=west]", "Dispenser"));

		place(server, o.offset(-5, 0, 5), CHEST_NORTH);
		place(server, o.offset(-3, 0, 5), named("minecraft:furnace[facing=north]", "Furnace"));
	}

	private void perTypeToggles(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		teleport(ctx, server, connection, ALL_TYPES_AREA);

		record Toggle(String what, Consumer<Boolean> set, List<String> affected) {}
		List<Toggle> toggles = List.of(
				new Toggle("chests", v -> StorageContainerLabelsConfig.showForChests = v, List.of("Chest N", "Chest S", "Chest E", "Chest W", "Double")),
				new Toggle("chest variants", v -> StorageContainerLabelsConfig.showForChestVariants = v, List.of("Trapped", "Copper")),
				new Toggle("barrels", v -> StorageContainerLabelsConfig.showForBarrels = v, List.of("Barrel up", "Barrel down", "Barrel N", "Barrel E")),
				new Toggle("shulker boxes", v -> StorageContainerLabelsConfig.showForShulkerBoxes = v, List.of("Shulker up", "Shulker S")),
				new Toggle("hoppers", v -> StorageContainerLabelsConfig.showForHoppers = v, List.of("Hopper down", "Hopper N")),
				new Toggle("droppers", v -> StorageContainerLabelsConfig.showForDroppers = v, List.of("Dropper")),
				new Toggle("dispensers", v -> StorageContainerLabelsConfig.showForDispensers = v, List.of("Dispenser")));

		setConfig(ctx, StorageContainerLabelsClientGameTest::showEverything);
		ctx.waitTicks(RESCAN_TICKS);
		List<String> all = names(labels(ctx));

		for (Toggle toggle : toggles) {
			setConfig(ctx, () -> toggle.set().accept(false));
			ctx.waitTicks(RESCAN_TICKS);
			List<String> expected = new ArrayList<>(all);
			expected.removeAll(toggle.affected());
			List<String> off = names(labels(ctx));
			check("switching off " + toggle.what() + " removes exactly those labels", off.equals(expected), "expected " + expected + ", got " + off);

			setConfig(ctx, () -> toggle.set().accept(true));
			ctx.waitTicks(RESCAN_TICKS);
			List<String> on = names(labels(ctx));
			check("switching " + toggle.what() + " back on restores them", on.equals(all), "expected " + all + ", got " + on);
		}

		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
		ctx.waitTicks(RESCAN_TICKS);
		List<String> defaults = names(labels(ctx));
		check("with default settings, hoppers, droppers and dispensers have no label",
				defaults.stream().noneMatch(n -> n.startsWith("Hopper") || n.equals("Dropper") || n.equals("Dispenser")) && defaults.contains("Chest N"),
				"labels " + defaults);
	}

	private void renderDistance(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		BlockPos o = RANGE_AREA;
		place(server, o.offset(-3, 0, 2), named(CHEST_NORTH, "West 3"));   // the next chunk over
		place(server, o.offset(-4, 0, 2), named(CHEST_NORTH, "West 4"));
		place(server, o.offset(2, 3, 2), named(CHEST_NORTH, "Up 3"));
		place(server, o.offset(2, 4, 2), named(CHEST_NORTH, "Up 4"));
		teleport(ctx, server, connection, o);

		setConfig(ctx, () -> {
			resetConfig();
			StorageContainerLabelsConfig.renderDistance = 3;
		});
		ctx.waitTicks(RESCAN_TICKS);
		List<String> near = names(labels(ctx));
		check("render distance 3 shows containers 3 blocks away, across a chunk border and upward, and no further",
				near.equals(List.of("Up 3", "West 3")), "labels " + near);

		setConfig(ctx, () -> StorageContainerLabelsConfig.renderDistance = 4);
		ctx.waitTicks(RESCAN_TICKS);
		List<String> far = names(labels(ctx));
		check("render distance 4 adds the containers 4 blocks away", far.equals(List.of("Up 3", "Up 4", "West 3", "West 4")), "labels " + far);
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
	}

	/** Goes through {@code getUpdatePacket}: unlike a chunk load, this tells the client about a block that's already there. */
	private void namingAPlacedContainer(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		BlockPos chest = NAMING_AREA.south(3);
		place(server, chest, CHEST_NORTH);
		teleport(ctx, server, connection, NAMING_AREA);
		ctx.waitTicks(RESCAN_TICKS);
		check("an unnamed chest starts without a label", labels(ctx).isEmpty(), "labels " + names(labels(ctx)));

		server.runCommand("data merge block " + at(chest) + " {CustomName:\"Named later\"}");
		ctx.waitTicks(RESCAN_TICKS);
		checkLabel("a chest named after it was placed", labels(ctx), "Named later", onFront(chest, Direction.NORTH));
	}

	private void colorCodes(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		BlockPos o = NAMING_AREA;
		place(server, o.offset(-3, 0, -3), named(CHEST_NORTH, "&cFood"));
		place(server, o.offset(-1, 0, -3), named(CHEST_NORTH, "&cWeapons &f& &2Armor"));
		place(server, o.offset(1, 0, -3), named(CHEST_NORTH, "Salt & Pepper"));
		place(server, o.offset(3, 0, -3), named(CHEST_NORTH, "&zNot a code"));
		ctx.waitTicks(RESCAN_TICKS);
		List<Label> labels = labels(ctx);

		checkSegments("&cFood", labels, "Food", List.of("Food=FF5555"));
		checkSegments("&cWeapons &f& &2Armor", labels, "Weapons & Armor", List.of("Weapons =FF5555", "& =FFFFFF", "Armor=00AA00"));
		find(labels, "Salt & Pepper").ifPresentOrElse(
				l -> check("a plain & stays in the name and uses the label color", l.color() == StorageContainerLabelsConfig.labelColor, "color " + hex(l.color())),
				() -> fail("a plain & stays in the name and uses the label color", "labels " + names(labels)));
		check("an unknown code stays as it was typed", find(labels, "&zNot a code").isPresent(), "labels " + names(labels));
	}

	private void customOffsets(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		BlockPos o = ALL_TYPES_AREA;
		teleport(ctx, server, connection, o);
		setConfig(ctx, () -> {
			showEverything();
			StorageContainerLabelsConfig.offsetX = 0.3f;
			StorageContainerLabelsConfig.offsetY = 0.5f;
			StorageContainerLabelsConfig.offsetZ = -0.4f;
		});
		ctx.waitTicks(RESCAN_TICKS);
		List<Label> labels = labels(ctx);

		BlockPos north = o.offset(-5, 0, -5);
		BlockPos south = o.offset(-3, 0, -5);
		BlockPos east = o.offset(-1, 0, -5);
		BlockPos west = o.offset(1, 0, -5);
		checkLabel("custom offsets, facing north", labels, "Chest N", new double[]{north.getX() + 0.8, north.getY() + 1.75, north.getZ() + 0.1});
		checkLabel("custom offsets, facing south", labels, "Chest S", new double[]{south.getX() + 0.2, south.getY() + 1.75, south.getZ() + 0.9});
		checkLabel("custom offsets, facing east", labels, "Chest E", new double[]{east.getX() + 0.9, east.getY() + 1.75, east.getZ() + 0.2});
		checkLabel("custom offsets, facing west", labels, "Chest W", new double[]{west.getX() + 0.1, west.getY() + 1.75, west.getZ() + 0.8});
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
	}

	// ---- Labels: what reaches the screen ----

	private void rendering(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, boolean improvedTransparency) {
		String mode = improvedTransparency ? "improved transparency" : "standard transparency";
		String tag = improvedTransparency ? "it-" : "st-";
		ctx.runOnClient(mc -> mc.options.improvedTransparency().set(improvedTransparency));
		ctx.waitTicks(2);
		connection.waitForChunksRender();
		boolean actual = ctx.computeOnClient(mc -> mc.options.improvedTransparency().get());
		check("running with " + mode, actual == improvedTransparency, "option " + actual);

		// setblock only works in loaded chunks, so arrive first
		BlockPos chest = STAGE;
		BlockPos front = chest.north(3);
		teleport(ctx, server, connection, front);
		place(server, chest, named(CHEST_NORTH, "Food"));
		ctx.getInput().lookAt(chest);

		// Default settings go through the translucent "texts" phase...
		Measured defaults = measure(ctx, tag + "default", () -> {});
		check(mode + ": the label reaches the screen", defaults.diff().pixels() >= MIN_LABEL_PIXELS, defaults.diff().toString());

		// ...full opacity with no background through the "solid" one
		Measured solid = measure(ctx, tag + "opaque", () -> StorageContainerLabelsConfig.opacity = 1.0f);
		check(mode + ": a fully opaque label reaches the screen", solid.diff().pixels() >= MIN_LABEL_PIXELS, solid.diff().toString());

		Measured faint = measure(ctx, tag + "faint", () -> StorageContainerLabelsConfig.opacity = 0.25f);
		double opacityRatio = (double) faint.diff().magnitude() / Math.max(1, solid.diff().magnitude());
		check(mode + ": text opacity 25% draws about a quarter as strongly as 100%", opacityRatio > 0.1 && opacityRatio < 0.45,
				String.format(Locale.ROOT, "ratio %.3f, expected 0.1-0.45", opacityRatio));

		Measured red = measure(ctx, tag + "label-color", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.labelColor = 0xFF0000;
		});
		check(mode + ": label color reaches the screen", red.diff().red() > 150 && red.diff().green() < 60 && red.diff().blue() < 60, red.diff().toString());

		Measured big = measure(ctx, tag + "size", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.size = 1.2f;
		});
		double sizeRatio = (double) big.diff().pixels() / Math.max(1, solid.diff().pixels());
		check(mode + ": double the size covers about four times the pixels", sizeRatio > 2.5 && sizeRatio < 6,
				String.format(Locale.ROOT, "ratio %.2f, expected 2.5-6", sizeRatio));

		Measured background = measure(ctx, tag + "background", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.showBackground = true;
			StorageContainerLabelsConfig.backgroundColor = 0x0000FF;
			StorageContainerLabelsConfig.backgroundOpacity = 1.0f;
		});
		check(mode + ": a background fills the label's whole rectangle", background.diff().pixels() >= 2 * solid.diff().pixels(),
				"text only " + solid.diff().pixels() + " px, with background " + background.diff().pixels() + " px");
		check(mode + ": the background has its color", background.diff().blue() > 2 * background.diff().red(), background.diff().toString());

		// Readable from the front: two colors show where the text starts and which line is on top
		String sideBySide = "&cAAAA&9BBBB";
		place(server, chest, named(CHEST_NORTH, sideBySide));
		Measured notMirrored = measure(ctx, tag + "not-mirrored", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.size = 1.2f;
		});
		double redX = notMirrored.centroid(c -> red(c) > 2 * blue(c))[0];
		double blueX = notMirrored.centroid(c -> blue(c) > 2 * red(c))[0];
		check(mode + ": text reads left to right", redX > 0 && blueX > 0 && redX < blueX, String.format(Locale.ROOT, "red x %.1f, blue x %.1f", redX, blueX));

		String stacked = "&cAAAA &9BBBB";
		place(server, chest, named(CHEST_NORTH, stacked));
		Measured upright = measure(ctx, tag + "upright", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.size = 1.2f;
			StorageContainerLabelsConfig.wrapWidth = 30;
		});
		double redY = upright.centroid(c -> red(c) > 2 * blue(c))[1];
		double blueY = upright.centroid(c -> blue(c) > 2 * red(c))[1];
		check(mode + ": text is upright, first line on top", redY > 0 && blueY > 0 && redY < blueY, String.format(Locale.ROOT, "red y %.1f, blue y %.1f", redY, blueY));
		place(server, chest, named(CHEST_NORTH, "&cRed &9Blue"));
		Measured codes = measure(ctx, tag + "color-codes", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.size = 1.2f;
		});
		check(mode + ": color codes reach the screen as two colors",
				codes.centroid(c -> red(c) > 2 * blue(c))[0] > 0 && codes.centroid(c -> blue(c) > 2 * red(c))[0] > 0, codes.diff().toString());

		String longName = "Alpha Beta Gamma Delta";
		place(server, chest, named(CHEST_NORTH, longName));
		Measured oneLine = measure(ctx, tag + "wrap-wide", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.wrapWidth = 200;
		});
		Measured wrapped = measure(ctx, tag + "wrap-narrow", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.wrapWidth = 40;
		});
		check(mode + ": a narrow wrap width turns the name into several shorter lines",
				wrapped.diff().height() >= 2.5 * oneLine.diff().height() && wrapped.diff().width() < oneLine.diff().width(),
				"one line " + oneLine.diff().width() + "x" + oneLine.diff().height() + ", wrapped " + wrapped.diff().width() + "x" + wrapped.diff().height());

		place(server, chest, named(CHEST_NORTH, "Food"));
		Measured frontView = measure(ctx, tag + "front-view", () -> StorageContainerLabelsConfig.opacity = 1.0f);

		// Labels turn to face the camera (this is the camera rotation that moved to PoseStack#rotate)
		teleport(ctx, server, connection, chest.east(3));
		ctx.getInput().lookAt(chest);
		Measured sideView = measure(ctx, tag + "side-view", () -> StorageContainerLabelsConfig.opacity = 1.0f);
		check(mode + ": seen from the side, the label still faces the camera", sideView.diff().width() >= 0.7 * frontView.diff().width(),
				"front width " + frontView.diff().width() + ", side width " + sideView.diff().width());

		showFocusedOnly(ctx, server, connection, mode, tag);
		distanceFade(ctx, server, connection, mode, tag);
		occlusion(ctx, server, connection, mode, tag);
		teleport(ctx, server, connection, front);
	}

	private void showFocusedOnly(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, String mode, String tag) {
		BlockPos chest = STAGE;
		BlockPos companion = chest.east();
		place(server, chest, named("minecraft:chest[facing=north,type=left]", "Food"));
		place(server, companion, "minecraft:chest[facing=north,type=right]");
		teleport(ctx, server, connection, chest.north(3));

		Runnable focused = () -> StorageContainerLabelsConfig.focusedOnly = true;
		ctx.getInput().lookAt(chest);
		Measured onNamed = measure(ctx, tag + "focused-named-half", focused);
		check(mode + ": Show Focused Only shows the label of the container in view", onNamed.diff().pixels() >= MIN_LABEL_PIXELS, onNamed.diff().toString());

		ctx.getInput().lookAt(companion);
		Measured onOther = measure(ctx, tag + "focused-other-half", focused);
		check(mode + ": Show Focused Only counts the other half of a double chest", onOther.diff().pixels() >= MIN_LABEL_PIXELS, onOther.diff().toString());

		ctx.getInput().lookAt(chest.west(2).below());
		Measured away = measure(ctx, tag + "focused-away", focused);
		check(mode + ": Show Focused Only hides the label when looking elsewhere", away.diff().pixels() <= MAX_HIDDEN_LABEL_PIXELS, away.diff().toString());
		Measured awayNormal = measure(ctx, tag + "unfocused-away", () -> {});
		check(mode + ": ...and only because of that setting", awayNormal.diff().pixels() >= MIN_LABEL_PIXELS, awayNormal.diff().toString());

		server.runCommand("setblock " + at(companion) + " minecraft:air");
		place(server, chest, named(CHEST_NORTH, "Food"));
	}

	private void distanceFade(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, String mode, String tag) {
		BlockPos chest = STAGE;

		// 8 blocks away, the label is about 7.4 from the camera: with a fade from 4 to 8, that's about 15% left
		teleport(ctx, server, connection, chest.north(8));
		ctx.getInput().lookAt(chest);
		Measured noFade = measure(ctx, tag + "fade-none", () -> {
			StorageContainerLabelsConfig.renderDistance = 8;
			StorageContainerLabelsConfig.size = 1.5f;
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.fade = 0f;
		});
		Measured faded = measure(ctx, tag + "fade-half", () -> {
			StorageContainerLabelsConfig.renderDistance = 8;
			StorageContainerLabelsConfig.size = 1.5f;
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.fade = 0.5f;
		});
		double farRatio = (double) faded.diff().magnitude() / Math.max(1, noFade.diff().magnitude());
		check(mode + ": distance fade dims a label near the edge", noFade.diff().pixels() >= MIN_LABEL_PIXELS && farRatio < 0.5,
				String.format(Locale.ROOT, "no fade %d px, ratio %.3f, expected < 0.5", noFade.diff().pixels(), farRatio));

		// Close by, the same setting changes nothing
		teleport(ctx, server, connection, chest.north(3));
		ctx.getInput().lookAt(chest);
		Measured closeNoFade = measure(ctx, tag + "fade-close-none", () -> {
			StorageContainerLabelsConfig.renderDistance = 8;
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.fade = 0f;
		});
		Measured closeFaded = measure(ctx, tag + "fade-close-half", () -> {
			StorageContainerLabelsConfig.renderDistance = 8;
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.fade = 0.5f;
		});
		double closeRatio = (double) closeFaded.diff().magnitude() / Math.max(1, closeNoFade.diff().magnitude());
		check(mode + ": distance fade leaves nearby labels alone", closeRatio > 0.9 && closeRatio < 1.1,
				String.format(Locale.ROOT, "ratio %.3f, expected 0.9-1.1", closeRatio));

		// On a diagonal the scan still finds the chest, 6 blocks away each way, but the label is past the render
		// distance by straight-line distance, so it has faded out entirely
		teleport(ctx, server, connection, chest.north(6).west(6));
		ctx.getInput().lookAt(chest);
		ctx.waitTicks(RESCAN_TICKS);
		Measured beyond = measure(ctx, tag + "fade-beyond", () -> {
			StorageContainerLabelsConfig.size = 1.5f;
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.fade = 0.5f;
		});
		check(mode + ": a label past the render distance has faded out completely", beyond.diff().pixels() <= MAX_HIDDEN_LABEL_PIXELS, beyond.diff().toString());
	}

	private void occlusion(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, String mode, String tag) {
		BlockPos chest = STAGE;
		BlockPos wall = chest.north(2);
		String box = at(wall.west(3)) + " " + at(wall.east(3).above(4));

		// Behind stone, only Visible Through Blocks shows the label
		server.runCommand("fill " + box + " minecraft:stone");
		teleport(ctx, server, connection, chest.north(4));
		ctx.getInput().lookAt(chest);
		Measured hidden = measure(ctx, tag + "behind-wall", () -> StorageContainerLabelsConfig.opacity = 1.0f);
		check(mode + ": a wall hides the label", hidden.diff().pixels() <= MAX_HIDDEN_LABEL_PIXELS, hidden.diff().toString());
		Measured seeThrough = measure(ctx, tag + "see-through", () -> {
			StorageContainerLabelsConfig.opacity = 1.0f;
			StorageContainerLabelsConfig.seeThrough = true;
		});
		check(mode + ": Visible Through Blocks shows the label through the wall", seeThrough.diff().pixels() >= MIN_LABEL_PIXELS, seeThrough.diff().toString());

		// Behind stained glass the label has to stay visible, in both transparency modes
		server.runCommand("fill " + box + " minecraft:light_blue_stained_glass");
		connection.waitForChunksRender();
		Measured glass = measure(ctx, tag + "behind-glass", () -> StorageContainerLabelsConfig.opacity = 1.0f);
		check(mode + ": the label shows through stained glass", glass.diff().pixels() >= MIN_LABEL_PIXELS, glass.diff().toString());
		Measured glassTranslucent = measure(ctx, tag + "behind-glass-translucent", () -> {});
		check(mode + ": a translucent label shows through stained glass", glassTranslucent.diff().pixels() >= MIN_LABEL_PIXELS, glassTranslucent.diff().toString());

		server.runCommand("fill " + box + " minecraft:air");
		connection.waitForChunksRender();
	}

	private void overviewScreenshot(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
		BlockPos o = OVERVIEW_AREA;
		List<String> row = List.of(
				named(CHEST_NORTH, "&cFood"),
				named("minecraft:trapped_chest[facing=north]", "Trapped"),
				named("minecraft:copper_chest[facing=north]", "&6Copper"),
				named("minecraft:barrel[facing=north]", "Barrel"),
				named("minecraft:shulker_box[facing=north]", "&dShulker"),
				named("minecraft:hopper[facing=north]", "Hopper"),
				named("minecraft:dropper[facing=north]", "Dropper"),
				named("minecraft:dispenser[facing=north]", "&bDispenser"));
		teleport(ctx, server, connection, o.north(8)); // far enough back to frame the whole row
		for (int i = 0; i < row.size(); i++) {
			place(server, o.offset(i * 2 - 7, 0, 0), row.get(i));
		}
		ctx.getInput().lookAt(o);
		setConfig(ctx, () -> {
			showEverything();
			StorageContainerLabelsConfig.renderDistance = 12;
			StorageContainerLabelsConfig.fade = 0f; // the row's ends are 11 blocks away
		});
		ctx.waitTicks(RESCAN_TICKS);
		check("every container type in a row has its label", labels(ctx).size() == row.size(), "labels " + names(labels(ctx)));
		log("screenshot " + ctx.takeScreenshot("overview"));
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
	}

	private void restoreImprovedTransparency(ClientGameTestContext ctx, TestServerConnection connection) {
		ctx.runOnClient(mc -> mc.options.improvedTransparency().set(false));
		ctx.waitTicks(2);
		connection.waitForChunksRender();
	}

	// ---- Config screen ----

	private void configScreen(ClientGameTestContext ctx) {
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);

		typeCommand(ctx, COMMAND + " config");
		ctx.waitForScreen(ClothConfigScreen.class);
		ctx.waitTicks(3);
		log("screenshot " + ctx.takeScreenshot("config-screen"));

		// Click the real toggle with the mouse, then save with the real button
		double[] toggle = ctx.computeOnClient(mc -> {
			ClothConfigScreen screen = (ClothConfigScreen) mc.gui.screen();
			BooleanListEntry entry = booleanEntry(screen, "Show Focused Only");
			AbstractWidget button = null;
			for (GuiEventListener child : entry.children()) {
				if (child instanceof AbstractWidget widget) {
					button = widget;
					break;
				}
			}
			if (button == null) throw new IllegalStateException("The Show Focused Only entry has no button");
			double scale = mc.getWindow().getGuiScale();
			return new double[]{(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
		});
		ctx.getInput().setCursorPos(toggle[0], toggle[1]);
		ctx.waitTicks(1);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTicks(2);
		boolean toggled = ctx.computeOnClient(mc -> booleanEntry((ClothConfigScreen) mc.gui.screen(), "Show Focused Only").getValue());
		check("clicking the Show Focused Only toggle flips it", toggled, "entry value " + toggled);

		ctx.clickScreenButton("text.cloth-config.save_and_done");
		ctx.waitForScreen(null);
		check("Save & Quit applies the setting", StorageContainerLabelsConfig.focusedOnly, "focusedOnly=" + StorageContainerLabelsConfig.focusedOnly);

		Path file = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
		try {
			JsonObject saved = new Gson().fromJson(Files.readString(file), JsonObject.class);
			check("Save & Quit writes the config file", saved.get("focusedOnly").getAsBoolean(), file + ": focusedOnly=" + saved.get("focusedOnly"));
		} catch (IOException e) {
			fail("Save & Quit writes the config file", "can't read " + file + ": " + e);
		}

		// Every value survives a save and a load
		setConfig(ctx, () -> {
			StorageContainerLabelsConfig.renderDistance = 17;
			StorageContainerLabelsConfig.wrapWidth = 73;
			StorageContainerLabelsConfig.size = 1.3f;
			StorageContainerLabelsConfig.offsetX = 0.4f;
			StorageContainerLabelsConfig.offsetY = -0.3f;
			StorageContainerLabelsConfig.offsetZ = 0.2f;
			StorageContainerLabelsConfig.opacity = 0.55f;
			StorageContainerLabelsConfig.seeThrough = true;
			StorageContainerLabelsConfig.fade = 0.35f;
			StorageContainerLabelsConfig.showForChests = false;
			StorageContainerLabelsConfig.showForChestVariants = false;
			StorageContainerLabelsConfig.showForBarrels = false;
			StorageContainerLabelsConfig.showForShulkerBoxes = false;
			StorageContainerLabelsConfig.showForHoppers = true;
			StorageContainerLabelsConfig.showForDroppers = true;
			StorageContainerLabelsConfig.showForDispensers = true;
			StorageContainerLabelsConfig.labelColor = 0x123456;
			StorageContainerLabelsConfig.showBackground = true;
			StorageContainerLabelsConfig.backgroundColor = 0x654321;
			StorageContainerLabelsConfig.backgroundOpacity = 0.75f;
			StorageContainerLabelsConfig.save();
			resetConfig();
			StorageContainerLabelsConfig.load();
		});
		check("every setting survives a save and a load",
				StorageContainerLabelsConfig.renderDistance == 17 && StorageContainerLabelsConfig.wrapWidth == 73
						&& StorageContainerLabelsConfig.size == 1.3f && StorageContainerLabelsConfig.offsetX == 0.4f
						&& StorageContainerLabelsConfig.offsetY == -0.3f && StorageContainerLabelsConfig.offsetZ == 0.2f
						&& StorageContainerLabelsConfig.opacity == 0.55f && StorageContainerLabelsConfig.seeThrough
						&& StorageContainerLabelsConfig.fade == 0.35f && !StorageContainerLabelsConfig.showForChests
						&& !StorageContainerLabelsConfig.showForChestVariants && !StorageContainerLabelsConfig.showForBarrels
						&& !StorageContainerLabelsConfig.showForShulkerBoxes && StorageContainerLabelsConfig.showForHoppers
						&& StorageContainerLabelsConfig.showForDroppers && StorageContainerLabelsConfig.showForDispensers
						&& StorageContainerLabelsConfig.labelColor == 0x123456 && StorageContainerLabelsConfig.focusedOnly
						&& StorageContainerLabelsConfig.showBackground && StorageContainerLabelsConfig.backgroundColor == 0x654321
						&& StorageContainerLabelsConfig.backgroundOpacity == 0.75f,
				"read back from " + file);
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
		try {
			Files.deleteIfExists(file); // leave no saved settings behind
		} catch (IOException e) {
			throw new IllegalStateException("Can't delete " + file, e);
		}

		// Mod Menu opens the same screen
		Screen fromModMenu = ctx.computeOnClient(mc -> ModMenu.getConfigScreen(MOD_ID, null));
		check("Mod Menu opens the config screen", fromModMenu instanceof ClothConfigScreen, "screen " + fromModMenu);
		ctx.setScreen(() -> fromModMenu);
		ctx.waitForScreen(ClothConfigScreen.class);
		ctx.waitTicks(3);
		log("screenshot " + ctx.takeScreenshot("config-screen-mod-menu"));
		ctx.setScreen(() -> null);
		ctx.waitForScreen(null);
	}

	private static BooleanListEntry booleanEntry(ClothConfigScreen screen, String name) {
		for (List<AbstractConfigEntry<?>> entries : screen.getCategorizedEntries().values()) {
			for (AbstractConfigEntry<?> entry : entries) {
				if (entry instanceof BooleanListEntry bool && entry.getFieldName().getString().equals(name)) {
					return bool;
				}
			}
		}
		throw new IllegalStateException("No toggle named " + name);
	}

	// ---- Reconnecting: names arrive with the chunk (getUpdateTag), not as an update ----

	private void reloadedWorld(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, List<Label> before) {
		singleplayer.getConnection().waitForChunksRender();
		setConfig(ctx, StorageContainerLabelsClientGameTest::showEverything);
		ctx.waitTicks(RESCAN_TICKS);
		List<String> expected = names(before);
		List<String> after = names(labels(ctx));
		check("every label comes back after reloading the world", !expected.isEmpty() && after.equals(expected), "before " + expected + ", after " + after);
		check("an unnamed chest stays unnamed after the reload", !after.contains("Chest"), "labels " + after);
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
	}

	private void dedicatedServer(ClientGameTestContext ctx) {
		BlockPos o = new BlockPos(0, 100, 0);
		BlockPos before = o.north(3);
		BlockPos after = o.north(3).east(2);

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("forceload add -16 -16 15 15");
			server.runCommand("fill " + at(o.offset(-6, -1, -6)) + " " + at(o.offset(6, -1, 6)) + " minecraft:stone");
			place(server, before, named(CHEST_NORTH, "Before join"));

			try (TestDedicatedServerConnection connection = server.connect()) {
				teleport(ctx, server, connection, o);
				ctx.waitTicks(RESCAN_TICKS);
				checkLabel("dedicated server: a chest named before the player joined", labels(ctx), "Before join", onFront(before, Direction.NORTH));

				place(server, after, named("minecraft:barrel[facing=north]", "After join"));
				ctx.waitTicks(RESCAN_TICKS);
				checkLabel("dedicated server: a barrel placed while the player is online", labels(ctx), "After join", onFront(after, Direction.NORTH));
				teleport(ctx, server, connection, o.north(6).east()); // the platform's north edge, facing the fronts
				ctx.getInput().lookAt(o.north(3).east());
				ctx.waitTicks(RESCAN_TICKS);
				log("screenshot " + ctx.takeScreenshot("dedicated-server"));
			}
		}
	}

	// ---- Scene helpers ----

	private static void teleport(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, BlockPos feet) {
		// The block's center: "%d.5" would be wrong for negative coordinates (-6.5 is in block -7)
		server.runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 0", feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5));
		ctx.waitFor(mc -> mc.player != null && mc.player.blockPosition().equals(feet), 100);
		// The test world's view distance is 5 chunks, so a long jump needs new chunks. waitForChunksDownload can
		// return before the server has even started sending them, so wait for the chunks the mod's scan reads.
		int chunkX = feet.getX() >> 4;
		int chunkZ = feet.getZ() >> 4;
		ctx.waitFor(mc -> {
			for (int x = chunkX - 2; x <= chunkX + 2; x++) {
				for (int z = chunkZ - 2; z <= chunkZ + 2; z++) {
					if (mc.level.getChunkSource().getChunkNow(x, z) == null) {
						return false;
					}
				}
			}
			return true;
		}, 200);
		connection.waitForChunksRender();
	}

	private void place(TestServerContext server, BlockPos pos, String block) {
		server.runCommand("setblock " + at(pos) + " minecraft:air");
		server.runCommand("setblock " + at(pos) + " " + block);
	}

	private static String named(String block, String name) {
		return block + "{CustomName:\"" + name + "\"}";
	}

	private static String at(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** Default offsets on the side of a block facing {@code facing}: 0.45 up, 0.7 forward of the block's center. */
	private static double[] onFront(BlockPos p, Direction facing) {
		double x = p.getX(), y = p.getY() + 0.45, z = p.getZ();
		return switch (facing) {
			case NORTH -> new double[]{x + 0.5, y, z - 0.2};
			case SOUTH -> new double[]{x + 0.5, y, z + 1.2};
			case EAST -> new double[]{x + 1.2, y, z + 0.5};
			case WEST -> new double[]{x - 0.2, y, z + 0.5};
			default -> throw new IllegalArgumentException("Not a side: " + facing);
		};
	}

	private static double[] onTop(BlockPos p) {
		return new double[]{p.getX() + 0.5, p.getY() + 1.5, p.getZ() + 0.5};
	}

	private static double[] below(BlockPos p) {
		return new double[]{p.getX() + 0.5, p.getY() - 0.25, p.getZ() + 0.5};
	}

	private static void setConfig(ClientGameTestContext ctx, Runnable change) {
		ctx.runOnClient(mc -> change.run());
	}

	private static void resetConfig() {
		StorageContainerLabelsConfig.renderDistance = 6;
		StorageContainerLabelsConfig.wrapWidth = 110;
		StorageContainerLabelsConfig.size = 0.6f;
		StorageContainerLabelsConfig.offsetX = 0.0f;
		StorageContainerLabelsConfig.offsetY = -0.8f;
		StorageContainerLabelsConfig.offsetZ = -0.7f;
		StorageContainerLabelsConfig.opacity = 0.8f;
		StorageContainerLabelsConfig.seeThrough = false;
		StorageContainerLabelsConfig.focusedOnly = false;
		StorageContainerLabelsConfig.fade = 0.15f;
		StorageContainerLabelsConfig.showForChests = true;
		StorageContainerLabelsConfig.showForChestVariants = true;
		StorageContainerLabelsConfig.showForBarrels = true;
		StorageContainerLabelsConfig.showForShulkerBoxes = true;
		StorageContainerLabelsConfig.showForHoppers = false;
		StorageContainerLabelsConfig.showForDroppers = false;
		StorageContainerLabelsConfig.showForDispensers = false;
		StorageContainerLabelsConfig.labelColor = 0xFFFFFF;
		StorageContainerLabelsConfig.showBackground = false;
		StorageContainerLabelsConfig.backgroundColor = 0x000000;
		StorageContainerLabelsConfig.backgroundOpacity = 0.25f;
	}

	private static void showEverything() {
		resetConfig();
		StorageContainerLabelsConfig.showForHoppers = true;
		StorageContainerLabelsConfig.showForDroppers = true;
		StorageContainerLabelsConfig.showForDispensers = true;
	}

	private static void setHudHidden(ClientGameTestContext ctx, boolean hidden) {
		if (ctx.computeOnClient(mc -> mc.gui.hud.isHidden()) != hidden) {
			ctx.getInput().pressKey(InputConstants.KEY_F1);
			ctx.waitFor(mc -> mc.gui.hud.isHidden() == hidden, 40);
		}
	}

	// ---- Anvil helpers ----

	private static void openAnvil(ClientGameTestContext ctx, BlockPos anvil) {
		ctx.getInput().lookAt(anvil);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitForScreen(AnvilScreen.class);
		ctx.waitTicks(2);
	}

	private static void click(ClientGameTestContext ctx, int slot) {
		ctx.runOnClient(mc -> {
			Player player = mc.player;
			mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, player);
		});
		ctx.waitTicks(2);
	}

	/** First slot of the player's inventory in the open menu. */
	private static int inventoryMenuStart(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.containerMenu.slots.stream()
				.filter(s -> s.container == mc.player.getInventory()).mapToInt(s -> s.index).min().orElseThrow());
	}

	private static int findInInventory(ClientGameTestContext ctx, java.util.function.Predicate<ItemStack> wanted) {
		int start = inventoryMenuStart(ctx);
		return ctx.computeOnClient(mc -> {
			AbstractContainerMenu menu = mc.player.containerMenu;
			for (Slot slot : menu.slots) {
				if (slot.index >= start && wanted.test(slot.getItem())) {
					return slot.index;
				}
			}
			throw new IllegalStateException("Nothing suitable in the inventory");
		});
	}

	private static int emptySlot(ClientGameTestContext ctx) {
		return findInInventory(ctx, stack -> stack.isEmpty());
	}

	private static void moveToInput(ClientGameTestContext ctx, Item item, int inputSlot) {
		click(ctx, findInInventory(ctx, stack -> stack.is(item)));
		click(ctx, inputSlot);
	}

	private static void moveUnnamedTagToAddition(ClientGameTestContext ctx) {
		click(ctx, findInInventory(ctx, stack -> stack.is(Items.NAME_TAG) && !stack.has(DataComponents.CUSTOM_NAME)));
		click(ctx, AnvilMenu.ADDITIONAL_SLOT);
	}

	private static void moveNamedTagToAddition(ClientGameTestContext ctx) {
		click(ctx, findInInventory(ctx, stack -> stack.is(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME)));
		click(ctx, AnvilMenu.ADDITIONAL_SLOT);
	}

	private static void returnFromSlot(ClientGameTestContext ctx, int slot) {
		click(ctx, slot);
		click(ctx, emptySlot(ctx));
	}

	/** Takes the anvil's result into the first free inventory slot and returns that slot. */
	private static int takeResult(ClientGameTestContext ctx) {
		click(ctx, AnvilMenu.RESULT_SLOT);
		int free = emptySlotInHotbar(ctx);
		click(ctx, free);
		return free;
	}

	private static int emptySlotInHotbar(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			for (Slot slot : mc.player.containerMenu.slots) {
				if (slot.container == mc.player.getInventory() && slot.getContainerSlot() < 9 && slot.getItem().isEmpty()) {
					return slot.index;
				}
			}
			throw new IllegalStateException("The hotbar is full");
		});
	}

	/** The text box starts out holding the item's name, with the cursor after it. */
	private static void replaceAnvilText(ClientGameTestContext ctx, String text) {
		for (int i = 0; i < ANVIL_TEXT_MAX_DEFAULT_LENGTH; i++) {
			ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
		}
		ctx.getInput().typeChars(text);
	}

	private static AnvilMenu anvilMenu(TestSingleplayerContext singleplayer) {
		return (AnvilMenu) singleplayer.getConnection().getServerPlayer().containerMenu;
	}

	private static ItemStack anvilResult(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(s -> anvilMenu(singleplayer).getSlot(AnvilMenu.RESULT_SLOT).getItem().copy());
	}

	private static int anvilCost(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(s -> anvilMenu(singleplayer).getCost());
	}

	private static int levels(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(s -> singleplayer.getConnection().getServerPlayer().experienceLevel);
	}

	private static ItemStack clientSlot(ClientGameTestContext ctx, int slot) {
		return ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(slot).getItem().copy());
	}

	private static String customName(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name == null ? null : name.getString();
	}

	private static String clientName(ClientGameTestContext ctx, BlockPos pos) {
		return ctx.computeOnClient(mc -> {
			BlockEntity entity = mc.level.getBlockEntity(pos);
			return entity instanceof Nameable nameable && nameable.hasCustomName() ? nameable.getCustomName().getString() : "none";
		});
	}

	// ---- Chat ----

	private static void typeCommand(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(o -> o.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars("/" + command);
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
	}

	@SuppressWarnings("unchecked")
	private static List<String> chatLines(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			try {
				Field messages = ChatComponent.class.getDeclaredField("allMessages");
				messages.setAccessible(true);
				return ((List<GuiMessage>) messages.get(mc.gui.hud.getChat())).stream().map(m -> m.content().getString()).toList();
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Can't read ChatComponent.allMessages", e);
			}
		});
	}

	// ---- Reading the mod's label list ----

	private record Label(double x, double y, double z, Component name, int color, long pos1, long pos2) {
		String text() {
			return name.getString();
		}
	}

	private static List<Label> labels(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			try {
				Field field = StorageContainerLabelsClient.class.getDeclaredField("chestLabels");
				field.setAccessible(true);
				List<Label> labels = new ArrayList<>();
				for (Object label : (List<?>) field.get(null)) {
					RecordComponent[] components = label.getClass().getRecordComponents();
					Object[] values = new Object[components.length];
					for (int i = 0; i < components.length; i++) {
						Method accessor = components[i].getAccessor();
						accessor.setAccessible(true);
						values[i] = accessor.invoke(label);
					}
					labels.add(new Label((double) values[0], (double) values[1], (double) values[2], (Component) values[3],
							(int) values[4], (long) values[5], (long) values[6]));
				}
				return labels;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Can't read the mod's label list", e);
			}
		});
	}

	private static Optional<Label> find(List<Label> labels, String text) {
		return labels.stream().filter(l -> l.text().equals(text)).findFirst();
	}

	private static List<String> names(List<Label> labels) {
		return labels.stream().map(Label::text).sorted(Comparator.naturalOrder()).toList();
	}

	private void checkLabel(String what, List<Label> labels, String text, double[] expected) {
		Optional<Label> label = find(labels, text);
		if (label.isEmpty()) {
			fail(what, "no label \"" + text + "\" among " + names(labels));
			return;
		}
		Label l = label.get();
		boolean close = Math.abs(l.x() - expected[0]) < POSITION_TOLERANCE
				&& Math.abs(l.y() - expected[1]) < POSITION_TOLERANCE
				&& Math.abs(l.z() - expected[2]) < POSITION_TOLERANCE;
		check(what, close, String.format(Locale.ROOT, "\"%s\" at %.3f %.3f %.3f, expected %.3f %.3f %.3f",
				text, l.x(), l.y(), l.z(), expected[0], expected[1], expected[2]));
	}

	private void checkSegments(String what, List<Label> labels, String text, List<String> expected) {
		Optional<Label> label = find(labels, text);
		if (label.isEmpty()) {
			fail(what, "no label \"" + text + "\" among " + names(labels));
			return;
		}
		List<String> segments = new ArrayList<>();
		label.get().name().visit((style, part) -> {
			if (!part.isEmpty()) {
				TextColor color = style.getColor();
				segments.add(part + "=" + (color == null ? "none" : hex(color.getValue())));
			}
			return Optional.empty();
		}, Style.EMPTY);
		check(what, segments.equals(expected), "expected " + expected + ", got " + segments);
	}

	// ---- Pixels ----

	private static int red(int rgb) {
		return rgb >> 16 & 0xFF;
	}

	private static int green(int rgb) {
		return rgb >> 8 & 0xFF;
	}

	private static int blue(int rgb) {
		return rgb & 0xFF;
	}

	/** Pixels that changed, how far they moved in total, where they are, and their mean color afterwards. */
	private record Diff(int pixels, long magnitude, int minX, int minY, int maxX, int maxY, double red, double green, double blue) {
		int width() {
			return pixels == 0 ? 0 : maxX - minX + 1;
		}

		int height() {
			return pixels == 0 ? 0 : maxY - minY + 1;
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%d px changed, magnitude %d, %dx%d, mean color %.0f/%.0f/%.0f",
					pixels, magnitude, width(), height(), red, green, blue);
		}
	}

	private record Measured(BufferedImage off, BufferedImage on, Diff diff) {
		/** Mean position of the changed pixels of a color; {-1, -1} if there are none. */
		double[] centroid(IntPredicate color) {
			long sumX = 0, sumY = 0, count = 0;
			for (int y = 0; y < on.getHeight(); y++) {
				for (int x = 0; x < on.getWidth(); x++) {
					int after = on.getRGB(x, y);
					if (changed(off.getRGB(x, y), after) && color.test(after)) {
						sumX += x;
						sumY += y;
						count++;
					}
				}
			}
			return count == 0 ? new double[]{-1, -1} : new double[]{(double) sumX / count, (double) sumY / count};
		}
	}

	private static boolean changed(int before, int after) {
		return Math.abs(red(before) - red(after)) > PIXEL_THRESHOLD
				|| Math.abs(green(before) - green(after)) > PIXEL_THRESHOLD
				|| Math.abs(blue(before) - blue(after)) > PIXEL_THRESHOLD;
	}

	private static Diff diff(BufferedImage before, BufferedImage after) {
		int pixels = 0;
		long magnitude = 0, sumRed = 0, sumGreen = 0, sumBlue = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		for (int y = 0; y < after.getHeight(); y++) {
			for (int x = 0; x < after.getWidth(); x++) {
				int a = before.getRGB(x, y);
				int b = after.getRGB(x, y);
				if (!changed(a, b)) {
					continue;
				}
				pixels++;
				magnitude += Math.abs(red(a) - red(b)) + Math.abs(green(a) - green(b)) + Math.abs(blue(a) - blue(b));
				sumRed += red(b);
				sumGreen += green(b);
				sumBlue += blue(b);
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}
		}
		double count = Math.max(1, pixels);
		return new Diff(pixels, magnitude, minX, minY, maxX, maxY, sumRed / count, sumGreen / count, sumBlue / count);
	}

	/**
	 * The same view with chest labels off and on, under settings that start from the defaults. The difference
	 * between the two screenshots is the label, and only the label.
	 */
	private Measured measure(ClientGameTestContext ctx, String name, Runnable settings) {
		setConfig(ctx, () -> {
			resetConfig();
			settings.run();
			StorageContainerLabelsConfig.showForChests = false;
		});
		BufferedImage off = screenshot(ctx, name + "-off");
		setConfig(ctx, () -> StorageContainerLabelsConfig.showForChests = true);
		BufferedImage on = screenshot(ctx, name + "-on");
		Diff diff = diff(off, on);
		log(name + ": " + diff);
		setConfig(ctx, StorageContainerLabelsClientGameTest::resetConfig);
		return new Measured(off, on, diff);
	}

	private BufferedImage screenshot(ClientGameTestContext ctx, String name) {
		ctx.waitTicks(RESCAN_TICKS);
		Path path = ctx.takeScreenshot(name);
		try {
			BufferedImage image = ImageIO.read(path.toFile());
			if (image == null) {
				throw new IllegalStateException("Not an image: " + path);
			}
			return image;
		} catch (IOException e) {
			throw new IllegalStateException("Can't read " + path, e);
		}
	}

	// ---- Reporting ----

	private void check(String what, boolean ok, String detail) {
		log((ok ? "PASS " : "FAIL ") + what + " (" + detail + ")");
		if (!ok) {
			failures.add(what + " (" + detail + ")");
		}
	}

	private void fail(String what, String detail) {
		check(what, false, detail);
	}

	private static String hex(int color) {
		return String.format(Locale.ROOT, "%06X", color);
	}

	private static void log(String message) {
		LOGGER.info("[{}] {}", ID, message);
	}
}
