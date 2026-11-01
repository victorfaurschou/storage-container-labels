package com.victorfaurschou.storagecontainerlabels.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.TextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StorageContainerLabelsClient implements ClientModInitializer {
	private record ChestLabel(double worldX, double worldY, double worldZ, Component name, int color, long pos1, long pos2) {}

	private static ChestLabel buildLabel(double x, double y, double z, Component name, long pos1, long pos2) {
		String raw = name.getString();
		if (!raw.contains("&")) {
			return new ChestLabel(x, y, z, name, StorageContainerLabelsConfig.labelColor, pos1, pos2);
		}

		var styled = Component.empty();
		int currentColor = StorageContainerLabelsConfig.labelColor;
		boolean anyCodeFound = false;
		boolean stripNext = false;
		var buf = new StringBuilder();
		int i = 0;

		while (i < raw.length()) {
			if (i + 1 < raw.length() && raw.charAt(i) == '&') {
				ChatFormatting fmt = ChatFormatting.getByCode(raw.charAt(i + 1));
				TextColor textColor = fmt != null ? TextColor.fromLegacyFormat(fmt) : null;
				if (textColor != null) {
					String seg = buf.toString();
					if (stripNext) seg = seg.stripLeading();
					if (!seg.isEmpty()) {
						final int c = currentColor;
						styled.append(Component.literal(seg).withStyle(s -> s.withColor(c)));
					}
					buf = new StringBuilder();
					stripNext = !anyCodeFound && seg.isEmpty();
					currentColor = textColor.getValue();
					anyCodeFound = true;
					i += 2;
					continue;
				}
			}
			buf.append(raw.charAt(i));
			i++;
		}

		if (!anyCodeFound) {
			return new ChestLabel(x, y, z, name, StorageContainerLabelsConfig.labelColor, pos1, pos2);
		}

		String remaining = buf.toString();
		if (stripNext) remaining = remaining.stripLeading();
		if (!remaining.isEmpty()) {
			final int c = currentColor;
			styled.append(Component.literal(remaining).withStyle(s -> s.withColor(c)));
		}

		return new ChestLabel(x, y, z, styled, 0xFFFFFF, pos1, pos2);
	}

	private static List<ChestLabel> chestLabels = List.of();
	private static int tickCount = 0;

	@Override
	public void onInitializeClient() {
		StorageContainerLabelsConfig.load();
		StorageContainerLabels.LOGGER.info("Storage Container Labels initialized");

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("storage-container-labels")
						.then(ClientCommands.literal("config")
								.executes(ctx -> {
									Minecraft mc = Minecraft.getInstance();
									// Queued rather than run right away: the chat screen closes itself after running
									// a command, and would close this screen along with it
									mc.schedule(() -> mc.gui.setScreen(ClothConfigScreen.build(null)));
									return 1;
								}))
						.then(ClientCommands.literal("version")
								.executes(ctx -> {
									String version = FabricLoader.getInstance()
											.getModContainer("storage-container-labels")
											.map(c -> c.getMetadata().getVersion().getFriendlyString())
											.orElse("unknown");
									ctx.getSource().sendFeedback(Component.literal("Storage Container Labels " + version));
									return 1;
								}))));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null || client.player == null) {
				chestLabels = List.of();
				return;
			}

			if (++tickCount % 5 != 0) return;

			BlockPos playerPos = client.player.blockPosition();
			int range = StorageContainerLabelsConfig.renderDistance;
			Set<Long> seen = new HashSet<>();
			List<ChestLabel> labels = new ArrayList<>();

			int chunkMinX = (playerPos.getX() - range) >> 4;
			int chunkMaxX = (playerPos.getX() + range) >> 4;
			int chunkMinZ = (playerPos.getZ() - range) >> 4;
			int chunkMaxZ = (playerPos.getZ() + range) >> 4;

			for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
				for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
					var chunk = client.level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
						BlockPos pos = entry.getKey();
						if (Math.abs(pos.getX() - playerPos.getX()) > range) continue;
						if (Math.abs(pos.getY() - playerPos.getY()) > range) continue;
						if (Math.abs(pos.getZ() - playerPos.getZ()) > range) continue;
						var be = entry.getValue();

						if (be instanceof ChestBlockEntity chest) {
							if (!chest.hasCustomName()) continue;
							var blockState = chunk.getBlockState(pos);
							boolean isVariant = blockState.getBlock() != Blocks.CHEST;
							if (isVariant && !StorageContainerLabelsConfig.showForChestVariants) continue;
							if (!isVariant && !StorageContainerLabelsConfig.showForChests) continue;

							Direction facing = blockState.getValue(ChestBlock.FACING);
							ChestType chestType = blockState.getValue(ChestBlock.TYPE);

							long myKey = pos.asLong();
							long canonKey = myKey;
							long companionKey = -1L;
							double centerX = pos.getX() + 0.5;
							double centerZ = pos.getZ() + 0.5;

							if (chestType != ChestType.SINGLE) {
								Direction companionDir = (chestType == ChestType.LEFT)
									? facing.getClockWise()
									: facing.getCounterClockWise();
								BlockPos companionPos = pos.relative(companionDir);
								if (client.level.getBlockEntity(companionPos) instanceof ChestBlockEntity) {
									companionKey = companionPos.asLong();
									canonKey = Math.min(myKey, companionKey);
									centerX = (pos.getX() + companionPos.getX()) / 2.0 + 0.5;
									centerZ = (pos.getZ() + companionPos.getZ()) / 2.0 + 0.5;
								}
							}

							if (!seen.add(canonKey)) continue;

							double[] rotated = StorageContainerLabels.rotateOffset(
								StorageContainerLabelsConfig.offsetX,
								StorageContainerLabelsConfig.offsetY,
								StorageContainerLabelsConfig.offsetZ,
								facing
							);
							labels.add(buildLabel(
								centerX + rotated[0],
								pos.getY() + 1.25 + rotated[1],
								centerZ + rotated[2],
								chest.getDisplayName(),
								myKey, companionKey
							));

						} else if (be instanceof BarrelBlockEntity barrel) {
							if (!StorageContainerLabelsConfig.showForBarrels) continue;
							if (!barrel.hasCustomName()) continue;
							Direction facing = chunk.getBlockState(pos).getValue(BarrelBlock.FACING);
							labels.add(labelForFacingBlock(pos, facing, barrel.getDisplayName()));

						} else if (be instanceof ShulkerBoxBlockEntity shulker) {
							if (!StorageContainerLabelsConfig.showForShulkerBoxes) continue;
							if (!shulker.hasCustomName()) continue;
							Direction facing = chunk.getBlockState(pos).getValue(ShulkerBoxBlock.FACING);
							labels.add(labelForFacingBlock(pos, facing, shulker.getDisplayName()));

						} else if (be instanceof DropperBlockEntity dropper) {
							if (!StorageContainerLabelsConfig.showForDroppers) continue;
							if (!dropper.hasCustomName()) continue;
							Direction facing = chunk.getBlockState(pos).getValue(DispenserBlock.FACING);
							labels.add(labelForFacingBlock(pos, facing, dropper.getDisplayName()));

						} else if (be instanceof DispenserBlockEntity dispenser) {
							if (!StorageContainerLabelsConfig.showForDispensers) continue;
							if (!dispenser.hasCustomName()) continue;
							Direction facing = chunk.getBlockState(pos).getValue(DispenserBlock.FACING);
							labels.add(labelForFacingBlock(pos, facing, dispenser.getDisplayName()));

						} else if (be instanceof HopperBlockEntity hopper) {
							if (!StorageContainerLabelsConfig.showForHoppers) continue;
							if (!hopper.hasCustomName()) continue;
							Direction facing = chunk.getBlockState(pos).getValue(HopperBlock.FACING);
							labels.add(labelForFacingBlock(pos, facing, hopper.getDisplayName()));
						}
					}
				}
			}

			chestLabels = labels;
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			List<ChestLabel> labels = chestLabels;
			if (labels.isEmpty()) return;

			Minecraft client = Minecraft.getInstance();
			CameraRenderState camera = context.levelState().cameraRenderState;

			PoseStack poseStack = context.poseStack();
			SubmitNodeCollector collector = context.submitNodeCollector();

			int alpha = (int)(StorageContainerLabelsConfig.opacity * 255);
			int bgAlpha = (int)(StorageContainerLabelsConfig.backgroundOpacity * 255);
			float scale = StorageContainerLabelsConfig.size * 0.025f;
			float fadeRange = StorageContainerLabelsConfig.renderDistance * StorageContainerLabelsConfig.fade;

			long focusedKey = -1L;
			if (StorageContainerLabelsConfig.focusedOnly && client.hitResult != null
					&& client.hitResult.getType() == HitResult.Type.BLOCK) {
				focusedKey = ((BlockHitResult) client.hitResult).getBlockPos().asLong();
			}

			for (ChestLabel label : labels) {
				if (StorageContainerLabelsConfig.focusedOnly
						&& label.pos1() != focusedKey && label.pos2() != focusedKey) continue;

				double dx = label.worldX() - camera.pos.x;
				double dy = label.worldY() - camera.pos.y;
				double dz = label.worldZ() - camera.pos.z;

				int argb;
				int bgArgb = 0;
				if (fadeRange > 0f) {
					float fadeStart = StorageContainerLabelsConfig.renderDistance - fadeRange;
					float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
					float fadeMult = Math.max(0f, dist <= fadeStart ? 1.0f : 1.0f - (dist - fadeStart) / fadeRange);
					argb = ((int)(alpha * fadeMult) << 24) | label.color();
					if (StorageContainerLabelsConfig.showBackground)
						bgArgb = ((int)(bgAlpha * fadeMult) << 24) | StorageContainerLabelsConfig.backgroundColor;
				} else {
					argb = (alpha << 24) | label.color();
					if (StorageContainerLabelsConfig.showBackground)
						bgArgb = (bgAlpha << 24) | StorageContainerLabelsConfig.backgroundColor;
				}

				List<FormattedCharSequence> lines = client.font.split(label.name(), StorageContainerLabelsConfig.wrapWidth);
				int lineHeight = client.font.lineHeight;
				float startY = -(lines.size() - 1) * lineHeight / 2.0f;
				Font.DisplayMode displayMode = StorageContainerLabelsConfig.seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

				poseStack.pushPose();
				poseStack.translate(dx, dy, dz);
				poseStack.rotate(camera.orientation);
				poseStack.scale(scale, -scale, scale);

				for (int li = 0; li < lines.size(); li++) {
					FormattedCharSequence line = lines.get(li);
					float lineX = -client.font.width(line) / 2.0f;
					collector.submitText(poseStack, lineX, startY + li * lineHeight, line, false, displayMode, LightCoordsUtil.FULL_BRIGHT, argb, bgArgb, 0);
				}

				poseStack.popPose();
			}
		});
	}

	private static ChestLabel labelForFacingBlock(BlockPos pos, Direction facing, Component name) {
		double labelX, labelY, labelZ;
		if (facing == Direction.UP) {
			labelX = pos.getX() + 0.5;
			labelY = pos.getY() + 1.5;
			labelZ = pos.getZ() + 0.5;
		} else if (facing == Direction.DOWN) {
			labelX = pos.getX() + 0.5;
			labelY = pos.getY() - 0.25;
			labelZ = pos.getZ() + 0.5;
		} else {
			double[] rotated = StorageContainerLabels.rotateOffset(
				StorageContainerLabelsConfig.offsetX,
				StorageContainerLabelsConfig.offsetY,
				StorageContainerLabelsConfig.offsetZ,
				facing
			);
			labelX = pos.getX() + 0.5 + rotated[0];
			labelY = pos.getY() + 1.25 + rotated[1];
			labelZ = pos.getZ() + 0.5 + rotated[2];
		}
		return buildLabel(labelX, labelY, labelZ, name, pos.asLong(), -1L);
	}
}
