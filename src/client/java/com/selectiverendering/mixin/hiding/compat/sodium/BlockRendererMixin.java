package com.selectiverendering.mixin.hiding.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.joml.Vector3f;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import com.selectiverendering.render.hiding.TerrainTranslucency;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.selectiverendering.render.hiding.AlphaGetter;
import com.selectiverendering.render.hiding.BlockRenderContextAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 为 Sodium 方块渲染应用透明度及半透明材质，并维护当前方块上下文。 */
@Mixin(value = BlockRenderer.class, remap = false)
public class BlockRendererMixin {
	@Unique
	@Nullable
	private BlockRenderContextAccess selectiveRendering$context;

	@Shadow
	@Final
	private Vector3f posOffset;

	/** 位于方块边界的面向内收一点，避免和相邻方块的面共面闪烁。 */
	@Unique
	private static final float CAPTURE_INSET = 0.005F;

	@WrapMethod(method = "renderModel")
	private void selectiveRendering$renderModel(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, Operation<Void> original) {
		BlockRenderContextAccess context = this.selectiveRendering$context();
		int alpha = AlphaGetter.getAlpha(state, pos);

		context.selectiveRendering$setAlpha(alpha);

		try {
			if (alpha != 0) {
				original.call(model, state, pos, origin);
			}
		} finally {
			context.selectiveRendering$setAlpha(null);
		}
	}

	@Unique
	private BlockRenderContextAccess selectiveRendering$context() {
		BlockRenderContextAccess context = this.selectiveRendering$context;
		if (context == null) {
			context = (BlockRenderContextAccess) (Object) this;
			this.selectiveRendering$context = context;
		}

		return context;
	}

	@Inject(method = "bufferQuad", at = @At("HEAD"), cancellable = true)
	private void selectiveRendering$onBufferQuad(MutableQuadViewImpl quad, float[] brightnesses, Material material, CallbackInfo ci) {
		int alpha = this.selectiveRendering$alpha();
		if (alpha <= 0 || alpha >= 255) {
			return;
		}

		if (TerrainTranslucency.capturing()) {
			this.selectiveRendering$capture(quad, brightnesses, alpha);
			ci.cancel();
			return;
		}

		for (int i = 0; i < 4; i++) {
			int color = quad.getColor(i);
			quad.setColor(i, ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF));
		}
	}

	@ModifyArg(
		method = "processQuad",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"
		),
		index = 2
	)
	private Material selectiveRendering$useTranslucentMaterial(Material material) {
		int alpha = this.selectiveRendering$alpha();
		return alpha > 0 && alpha < 255 ? DefaultMaterials.TRANSLUCENT : material;
	}

	/** 光影包下地形没法半透明：四边形不进区块 mesh，改存起来由实体管线绘制。 */
	@Unique
	private void selectiveRendering$capture(MutableQuadViewImpl quad, float[] brightnesses, int alpha) {
		float[] positions = new float[12];
		float[] uvs = new float[8];
		int[] colors = new int[4];
		int[] lights = new int[4];

		TerrainTranslucency.addSprite(quad.sprite(SpriteFinderCache.forBlockAtlas()));

		Vector3f normal = quad.faceNormal();
		float inset = quad.getCullFace() == null ? 0.0F : CAPTURE_INSET;
		float lightScale = 1.0F / (0.75F + 0.25F * alpha / 255.0F);

		for (int i = 0; i < 4; i++) {
			int source = ModelQuadOrientation.NORMAL.getVertexIndex(i);

			positions[i * 3] = quad.getX(source) + this.posOffset.x - normal.x() * inset;
			positions[i * 3 + 1] = quad.getY(source) + this.posOffset.y - normal.y() * inset;
			positions[i * 3 + 2] = quad.getZ(source) + this.posOffset.z - normal.z() * inset;

			uvs[i * 2] = quad.getTexU(source);
			uvs[i * 2 + 1] = quad.getTexV(source);

			int color = quad.baseColor(source);
			float shade = Math.min(1.0F, brightnesses[source]);
			int red = (int) (((color >> 16) & 0xFF) * shade);
			int green = (int) (((color >> 8) & 0xFF) * shade);
			int blue = (int) ((color & 0xFF) * shade);
			colors[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;

			lights[i] = scaleLight(quad.getLight(source), lightScale);
		}

		TerrainTranslucency.add(new TerrainTranslucency.Quad(positions, uvs, colors, lights, normal.x(), normal.y(), normal.z()));
	}

	/** 部分光影包会按 (0.75 + 0.25 * 顶点 alpha) 缩放光照，alpha 越低越暗，这里按同一公式预先补偿。 */
	@Unique
	private static int scaleLight(int light, float scale) {
		int block = Math.min(240, Math.round((light & 0xFF) * scale));
		int sky = Math.min(240, Math.round(((light >> 16) & 0xFF) * scale));
		return block | (sky << 16);
	}

	@Unique
	private int selectiveRendering$alpha() {
		Integer alpha = this.selectiveRendering$context().selectiveRendering$alpha();
		return alpha == null ? -1 : alpha;
	}
}
