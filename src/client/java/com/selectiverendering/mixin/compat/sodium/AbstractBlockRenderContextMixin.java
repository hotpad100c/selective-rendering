package com.selectiverendering.mixin.compat.sodium;

import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.render.Alpha;
import com.selectiverendering.render.BlockRenderContextAccess;
import com.selectiverendering.render.MovingBlockRenderContext;
import com.selectiverendering.util.BlockPosScratch;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 修正 Sodium 面剔除，在相邻方块淡化状态不同时保留面。 */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public class AbstractBlockRenderContextMixin implements BlockRenderContextAccess {
	@Shadow
	protected BlockAndTintGetter level;

	@Shadow
	protected BlockState state;

	@Shadow
	protected BlockPos pos;

	@Unique
	@Nullable
	private Integer selectiveRendering$alpha;

	@Override
	@Nullable
	public Integer selectiveRendering$alpha() {
		return this.selectiveRendering$alpha;
	}

	@Override
	public void selectiveRendering$setAlpha(@Nullable Integer alpha) {
		this.selectiveRendering$alpha = alpha;
	}

	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
	private void selectiveRendering$shouldDrawSide(Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (SelectiveRenderingManager.getMode() == SelectiveRenderingManager.Mode.OFF) {
			return;
		}

		if (MovingBlockRenderContext.alpha() != -1) {
			return;
		}

		if (level == null || state == null || pos == null) {
			return;
		}

		Integer cached = this.selectiveRendering$alpha;
		boolean hidden = cached != null
			? cached >= 0
			: Alpha.isHidden(state, pos);

		BlockPos neighbourPos = BlockPosScratch.offset(pos, direction);
		BlockState neighbourState = level.getBlockState(neighbourPos);
		boolean neighbourHidden = Alpha.isHidden(neighbourState, neighbourPos);

		if (hidden == neighbourHidden) {
			return;
		}

		cir.setReturnValue(true);
	}
}
