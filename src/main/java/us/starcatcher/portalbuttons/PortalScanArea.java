package us.starcatcher.portalbuttons;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Represents area to scan near a portal
 *
 * @param shape     The shape
 * @param direction The direction to face if teleported here
 */
public record PortalScanArea(VoxelShape shape, Direction direction) {
}
