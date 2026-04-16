package us.starcatcher.portalbuttons;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.starcatcher.portalbuttons.util.BlockScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Silly plugin that automatically moves you through a portal when you click a button on the portal frame
 *
 * @author jacob614 2025-10-14
 */
public class Portalbuttons implements ModInitializer {

	public static final Identifier FLAN_EVENT_PHASE = Identifier.fromNamespaceAndPath("flan", "events");
	public static final Identifier BUTTON_EVENT_PHASE = Identifier.fromNamespaceAndPath("starcatcher", "events");

	public static final Logger LOGGER = LoggerFactory.getLogger("portalbuttons");

	/**
	 * Initialize plugin
	 */
	@Override
	public void onInitialize() {
		UseBlockCallback.EVENT.addPhaseOrdering(BUTTON_EVENT_PHASE, FLAN_EVENT_PHASE);
		UseBlockCallback.EVENT.addPhaseOrdering(BUTTON_EVENT_PHASE, Event.DEFAULT_PHASE);
		UseBlockCallback.EVENT.register(BUTTON_EVENT_PHASE, (player, level, hand, hitResult) -> {
			if (level instanceof ServerLevel serverLevel) {
				if (processAttackBlock(player, serverLevel, hitResult.getBlockPos()))
					return InteractionResult.CONSUME;
			}

			return InteractionResult.PASS;
		});
		LOGGER.info("[portalbuttons] Portalbuttons Initialized");
	}

	/**
	 * Process block attack action to look for button presses on portal blocks
	 *
	 * @param player The player that did the action
	 * @param level  The world the action happened in
	 * @param pos    The position of the action
	 * @return True if action should be canceled
	 */
	private boolean processAttackBlock(Player player, ServerLevel level, BlockPos pos) {
		var state = level.getBlockState(pos);
		// Must press button
		if (!(state.getBlock() instanceof ButtonBlock))
			return false;

		var obsidianPos = pos.relative(getDirection(state), -1);
		// Button must be on obsidian
		if (!level.getBlockState(obsidianPos).is(Blocks.OBSIDIAN))
			return false;

		// Find nearby portal blocks
		var portalBlockPositions = BlockScanner.findNearby(obsidianPos, scanPos -> level.getBlockState(scanPos).is(Blocks.NETHER_PORTAL));
		if (portalBlockPositions.isEmpty())
			return false;

		// It's a portal block, process the possible teleport
		if (level.getBlockState(portalBlockPositions.getFirst()).getBlock() instanceof NetherPortalBlock portalBlock) {
			return processPortalClick(level, portalBlock, portalBlockPositions.getFirst(), player);
		}

		return false;
	}

	/**
	 * Process portal button click event
	 *
	 * @param level          The world
	 * @param portalBlock    The portal block
	 * @param portalBlockPos The portal block's position
	 * @param player         The player that clicked it
	 * @return True on successful teleport
	 */
	private boolean processPortalClick(ServerLevel level, NetherPortalBlock portalBlock, BlockPos portalBlockPos, Player player) {
		var destination = portalBlock.getPortalDestination(level, player, portalBlockPos);
		if (destination == null)
			return false;

		var destinationBlockPos = roundToBlockPos(destination.position());
		var destinationBlockState = destination.newLevel().getBlockState(destinationBlockPos);
		if (destinationBlockState.getBlock() instanceof NetherPortalBlock) {
			if (findTeleport(destination.newLevel(), destinationBlockPos, player, destinationBlockState.getValue(NetherPortalBlock.AXIS)))
				return true;
		}

		return false;
	}

	/**
	 * Find a spot to teleport to
	 *
	 * @param level               The world the destination portal is located in
	 * @param destinationBlockPos Block position of the destination portal (any part of the portal will do)
	 * @param player              The player to teleport
	 * @param axis                The axis the portal is on (either X or Z)
	 * @return True on successful teleport
	 */
	private boolean findTeleport(ServerLevel level, BlockPos destinationBlockPos, Player player, Direction.Axis axis) {
		var playerDimensions = player.getDimensions(Pose.STANDING);
		var shapes = getScannableShapes(level, destinationBlockPos, axis);
		for (var shape : shapes) {

			// Sometimes, teleportation fails because chunk collision isn't loaded on the other side
			// I have no idea how to fix this. Instead, add a ticket to keep the chunks loaded for a few seconds for the 2nd press
			var chunkPos = roundToBlockPos(shape.shape().bounds().getBottomCenter());
			level.getChunkSource().addTicket(new Ticket(TicketType.PORTAL, 31), ChunkPos.containing(chunkPos));

			var freePositionOpt = level.findFreePosition(
				player, shape.shape(), shape.shape().bounds().getBottomCenter(), playerDimensions.width(), playerDimensions.height(), playerDimensions.width()
			);

			// No match
			if (freePositionOpt.isEmpty())
				continue;

			var freePosition = freePositionOpt.get();
			// Free position is at shape floor. That means there's no floor on the other side, unknown how long the fall is
			if (freePosition.y == shape.shape().min(Direction.Axis.Y)) {
				LOGGER.info("[portalbuttons] Couldn't teleport because of unknown floor height at {}. This means there's a hole on the other end, or the portal is by a chunk border and collisions haven't loaded", freePosition);
				continue;
			}

			// Dangerous block at position (like, lava)
			if (EntityType.PLAYER.isBlockDangerous(level.getBlockState(roundToBlockPos(freePosition)))) {
				LOGGER.info("[portalbuttons] Couldn't teleport because of dangerous block at {}", freePosition);
				continue;
			}

			// Do teleport. For some reason we need to adjust for player hitbox here
			player.teleportTo(level, freePosition.x, freePosition.y - playerDimensions.height() / 2, freePosition.z, Set.of(), Direction.getYRot(shape.direction()), 0, true);

			return true;
		}

		return false;
	}

	/**
	 * Get areas to scan near the portal. Should return both sides of the portal, exactly 1 block offset on both planes and capped .5 blocks
	 * horizontally.
	 *
	 * @param level          The world
	 * @param portalBlockPos Any BlockPos of a nether portal block
	 * @param axis           The axis this portal is on (either X or Z)
	 * @return The list of areas to scan
	 */
	private List<PortalScanArea> getScannableShapes(ServerLevel level, BlockPos portalBlockPos, Direction.Axis axis) {
		var height = BlockScanner.scanDirection(portalBlockPos, Direction.Axis.Y, bp -> level.getBlockState(bp).getBlock() instanceof NetherPortalBlock);
		var width = BlockScanner.scanDirection(portalBlockPos, axis, bp -> level.getBlockState(bp).getBlock() instanceof NetherPortalBlock);
		var direction = axis.getPositive().getClockWise();

		// minus 1 to scan 1 extra block for portals not flush with the ground
		BlockPos bottomLeft = portalBlockPos.relative(Direction.Axis.Y, -height.getLeft() - 1).relative(axis, -width.getLeft());
		BlockPos upperRight = portalBlockPos.relative(Direction.Axis.Y, height.getRight()).relative(axis, width.getRight());

		List<PortalScanArea> shapes = new ArrayList<>();
		shapes.add(new PortalScanArea(Shapes.create(
			new AABB(bottomLeft.relative(direction, 1).getBottomCenter(),
				upperRight.relative(direction, 1).getBottomCenter()).inflate(1.0E-6)
		), direction));

		shapes.add(new PortalScanArea(Shapes.create(
			new AABB(bottomLeft.relative(direction, -1).getBottomCenter(),
				upperRight.relative(direction, -1).getBottomCenter()).inflate(1.0E-6)
		), direction.getOpposite()));

		return shapes;
	}

	/**
	 * Get the direction of a directional block state
	 *
	 * @param state The block state
	 * @return The direction
	 */
	private static Direction getDirection(BlockState state) {
		return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
			case CEILING -> Direction.DOWN;
			case FLOOR -> Direction.UP;
			default -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
		};
	}

	/**
	 * Get the BlockPos where a Vec3 is located
	 *
	 * @param vec3 The vector
	 * @return The BlockPos
	 */
	private static BlockPos roundToBlockPos(Vec3 vec3) {
		return new BlockPos((int)Math.floor(vec3.x), (int)Math.floor(vec3.y), (int)Math.floor(vec3.z));
	}
}
