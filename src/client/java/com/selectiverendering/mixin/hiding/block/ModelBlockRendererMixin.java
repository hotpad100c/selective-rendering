package com.selectiverendering.mixin.hiding.block;

import com.selectiverendering.render.hiding.VanillaCapture;
import com.selectiverendering.render.hiding.TerrainTranslucency;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.compat.Platform;
import com.selectiverendering.render.hiding.AlphaGetter;
import com.selectiverendering.render.hiding.MovingBlockRenderContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 为原版方块渲染应用透明度、半透明材质和面剔除修正。 */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererMixin {
	/** 当前线程正在渲染的方块透明度，-1 表示正常渲染。 */
	@Unique
	private static final ThreadLocal<Integer> selectiveRendering$blockAlpha = ThreadLocal.withInitial(() -> -1);

	@Shadow
	@Final
	private QuadInstance quadInstance;

	@Inject(method = "tesselateBlock", at = @At("HEAD"), require = 1)
	private void selectiveRendering$beginBlock(
		BlockQuadOutput output,
		float x,
		float y,
		float z,
		BlockAndTintGetter level,
		BlockPos pos,
		BlockState blockState,
		BlockStateModel model,
		long seed,
		CallbackInfo ci
	) {
		selectiveRendering$blockAlpha.set(MovingBlockRenderContext.alpha() != -1 ? -1 : AlphaGetter.getAlpha(blockState, pos));
	}

	@Inject(method = "tesselateBlock", at = @At("TAIL"), require = 1)
	private void selectiveRendering$endBlock(
		BlockQuadOutput output,
		float x,
		float y,
		float z,
		BlockAndTintGetter level,
		BlockPos pos,
		BlockState blockState,
		BlockStateModel model,
		long seed,
		CallbackInfo ci
	) {
		selectiveRendering$blockAlpha.set(-1);
	}

	@Inject(method = {"tesselateFlat", "tesselateAmbientOcclusion"}, at = @At("HEAD"), cancellable = true, require = 1)
	private void selectiveRendering$tesselate(BlockQuadOutput output, float x, float y, float z, List<BlockStateModelPart> parts, BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfo ci) {
		if (MovingBlockRenderContext.alpha() != -1) {
			return;
		}

		if (selectiveRendering$blockAlpha.get() == 0) {
			ci.cancel();
		}
	}

	/** 相邻方块的淡化状态不同时保留该面。 */
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 1)
	private void selectiveRendering$shouldRenderFace(
		BlockAndTintGetter level,
		BlockState state,
		Direction direction,
		BlockPos neighbourPos,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (SelectiveRenderingManager.getMode() == SelectiveRenderingManager.Mode.OFF) {
			return;
		}

		if (MovingBlockRenderContext.alpha() != -1) {
			return;
		}

		boolean hidden = selectiveRendering$blockAlpha.get() >= 0;

		BlockState neighbourState = level.getBlockState(neighbourPos);
		boolean neighbourHidden = AlphaGetter.isHidden(neighbourState, neighbourPos);

		if (hidden == neighbourHidden) {
			return;
		}

		cir.setReturnValue(true);
	}

	@Inject(method = "putQuadWithTint", at = @At("HEAD"), require = 1)
	private void selectiveRendering$putQuadWithTint(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci) {
		if (MovingBlockRenderContext.alpha() != -1) {
			return;
		}

		int alpha = selectiveRendering$blockAlpha.get();

		if (alpha > 0 && alpha < 255) {
			quadInstance.multiplyColor(ARGB.color(alpha, 255, 255, 255));
		}
	}

	/** 半透明方块截流到常驻缓冲；否则把需淡化的 quad 切换为半透明材质。 */
	@WrapOperation(
		method = "putQuadWithTint",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
		require = 1
	)
	private void selectiveRendering$putQuad(BlockQuadOutput output, float x, float y, float z, BakedQuad quad, QuadInstance instance, Operation<Void> original) {
		if (MovingBlockRenderContext.alpha() != -1) {
			original.call(output, x, y, z, quad, instance);
			return;
		}

		int alpha = selectiveRendering$blockAlpha.get();
		if (alpha <= 0 || alpha >= 255) {
			original.call(output, x, y, z, quad, instance);
			return;
		}

		//? if >=26.3 {
		if (TerrainTranslucency.capturing()) {
			VanillaCapture.block(x, y, z, quad, instance, alpha);
			return;
		}
		//?}

		BakedQuad.MaterialInfo material = quad.materialInfo();
		if (material.layer() != ChunkSectionLayer.TRANSLUCENT) {
			quad = new BakedQuad(
				quad.position0(), quad.position1(), quad.position2(), quad.position3(),
				quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
				quad.direction(),
				Platform.translucentMaterial(material)
			);
		}

		original.call(output, x, y, z, quad, instance);
	}
}
