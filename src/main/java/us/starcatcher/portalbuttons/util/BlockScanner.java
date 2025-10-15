package us.starcatcher.portalbuttons.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Uses stack-based floodfill algorithm to scan for blocks
 */
public class BlockScanner {

	private static final Vec3i EAST = new Vec3i( 1, 0, 0);
	private static final Vec3i WEST = new Vec3i( -1, 0, 0);
	private static final Vec3i SOUTH = new Vec3i( 0, 0, 1);
	private static final Vec3i NORTH = new Vec3i( 0, 0, -1);
	private static final Vec3i UP = new Vec3i( 0, 1, 0);
	private static final Vec3i DOWN = new Vec3i( 0, -1, 0);

	/**
	 * Offsets to use to check all 6 nearby block faces
	 */
	private static final Vec3i[] offsets = {
		EAST, WEST, SOUTH, NORTH, UP, DOWN,
	};

	/**
	 * Find nearby (directly touching in 6 cardinal directions) blocks matching some criteria
	 *
	 * @param pos        Center position
	 * @param matchCheck Function called for each position to check if it matches
	 * @return List of all matching block positions
	 */
	public static List<BlockPos> findNearby(BlockPos pos, Function<BlockPos, Boolean> matchCheck) {
		var ret = new ArrayList<BlockPos>();
		for (var offset : offsets) {
			var scanPos = pos.offset(offset);
			if (matchCheck.apply(scanPos))
				ret.add(scanPos);
		}

		return ret;
	}

	/**
	 * Scan in a direction for matching blocks and return the amount scanned in both directions
	 *
	 * @param startPos   The starting position
	 * @param axis       The axis to scan
	 * @param matchCheck Function called for each position to check if it matches
	 * @return Pair containing amount scanned like (negative axis, positive axis)
	 */
	public static Pair<Integer, Integer> scanDirection( BlockPos startPos, Direction.Axis axis, Function<BlockPos, Boolean> matchCheck) {
		int lowerOffset = 0, upperOffset = 0;
		Vec3i offset = axis == Direction.Axis.Y ? UP : axis == Direction.Axis.X ? EAST : SOUTH;

		while (matchCheck.apply(startPos.offset(offset.multiply(upperOffset))))
			upperOffset++;
		offset = offset.multiply(-1);
		while (matchCheck.apply(startPos.offset(offset.multiply(lowerOffset))))
			lowerOffset++;

		return new ImmutablePair<>(lowerOffset - 1, upperOffset - 1);
	}

	/**
	 * Use a stack-based floodfill algorithm to loop over world for matching blocks / positions
	 *
	 * @param startPos       The start position to scan
	 * @param matchCheck     Function that returns true for blocks that should be scanned. Will be called once for every block touching
	 *                       a matched block.
	 * @param actionCallback Consumer that is called once for all matching blocks
	 */
	public static void scanHorizontally(BlockPos startPos, Direction.Axis axis, Function<BlockPos, Boolean> matchCheck, Consumer<BlockPos> actionCallback) {
		Set<BlockPos> seen = new HashSet<>();
		Stack<BlockPos> stack = new Stack<>();
		stack.push(startPos);

		do {
			var pos = stack.pop();
			seen.add(pos);
			for (var offset : offsets) {
				if ((axis == Direction.Axis.X && offset.getX() != 0) || (axis == Direction.Axis.Z && offset.getZ() != 0))
					continue;
				var scanPos = pos.offset(offset);
				if (!seen.contains(scanPos) && matchCheck.apply(scanPos))
					stack.push(scanPos);
			}

			actionCallback.accept(pos);
		} while (!stack.empty());
	}
}
