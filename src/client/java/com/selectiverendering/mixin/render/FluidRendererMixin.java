package com.selectiverendering.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.selectiverendering.render.Alpha;
import com.selectiverendering.util.BlockPosScratch;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 为原版流体应用透明度，并修正隐藏邻居导致的面剔除。 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Unique
    private static final ThreadLocal<Integer> ALPHA = ThreadLocal.withInitial(() -> -1);

	@Unique
    private static final ThreadLocal<BlockPos> POS = new ThreadLocal<>();

	/** 当前流体渲染使用的世界数据，供邻居判定读取。 */
	@Unique
    private static final ThreadLocal<BlockGetter> LEVEL = new ThreadLocal<>();

	@Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
	private void selectiveRendering$onTesselate(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState state, FluidState fluidState, CallbackInfo ci) {
		int alpha = Alpha.getFluidAlpha(state, pos);
		ALPHA.set(alpha);
		POS.set(pos);
		LEVEL.set(level);

		if (alpha == 0) {
			ci.cancel();
        }
	}

	@WrapOperation(
		method = "tesselate",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/FluidRenderer;isFaceOccludedByNeighbor(Lnet/minecraft/core/Direction;FLnet/minecraft/world/level/block/state/BlockState;)Z"
		)
	)
	private static boolean selectiveRendering$isFaceOccludedByNeighbor(
		Direction direction,
		float height,
		BlockState neighborState,
		Operation<Boolean> original
	) {
		if (!original.call(direction, height, neighborState)) {
			return false;
		}

		if (direction.getAxis().isVertical()) {
			return true;
		}

		BlockPos pos = POS.get();
		if (pos == null) {
			return true;
		}

		BlockPos neighbourPos = BlockPosScratch.offset(pos, direction);
		return !Alpha.isHiddenIn(LEVEL.get(), neighbourPos);
	}

	@WrapOperation(
		method = "tesselate",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/FluidRenderer;shouldRenderFace(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)Z"
		)
	)
	private static boolean selectiveRendering$shouldRenderFace(
		FluidState fluidState,
		BlockState blockState,
		Direction direction,
		FluidState neighborFluidState,
		Operation<Boolean> original
	) {
		if (original.call(fluidState, blockState, direction, neighborFluidState)) {
			return true;
		}

		return selectiveRendering$isNeighborHidden(direction)
			&& original.call(fluidState, blockState, direction, Fluids.EMPTY.defaultFluidState());
	}

	@WrapOperation(
		method = "tesselate",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/FluidRenderer;isNeighborSameFluid(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/FluidState;)Z"
		)
	)
	private static boolean selectiveRendering$isNeighborSameFluid(
		FluidState fluidState,
		FluidState neighborFluidState,
		Operation<Boolean> original
	) {
		return original.call(fluidState, neighborFluidState) && !selectiveRendering$isNeighborHidden(Direction.UP);
	}

	@Unique
	private static boolean selectiveRendering$isNeighborHidden(Direction direction) {
		BlockPos pos = POS.get();
		return pos != null && Alpha.isHiddenIn(LEVEL.get(), BlockPosScratch.offset(pos, direction));
	}

	@ModifyVariable(method = "vertex", at = @At("HEAD"), ordinal = 0)
	private int selectiveRendering$applyAlpha(int color) {
		int alpha = ALPHA.get();
		return alpha < 0 ? color : (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}
}
