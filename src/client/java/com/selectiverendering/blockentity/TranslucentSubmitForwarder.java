package com.selectiverendering.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
//? if <26.2 {
/*import net.minecraft.client.model.geom.ModelPart;
*///?}
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if <26.2 {
/*import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
*///?}
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
//? if >=26.2 {
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
//?}
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//? if >=26.2 {
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
//?}
//? if >=26.3 {
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
//?} else {
/*import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
//? if >=26.2 {
import net.minecraft.world.phys.shapes.VoxelShape;
//?}
import org.joml.Quaternionf;

import java.util.List;

/**
 * 把 {@link OrderedSubmitNodeCollector} 的每个 {@code submitXxx} 转发给 {@link #target()}，
 * 其中真正用 {@code RenderType} 画几何的几个，转发前把 RenderType 换成
 * {@link TranslucentRenderTypes#translucentVariant} 的半透明变体。
 *
 * <p>这是 {@link SelectiveSubmitNodeCollector} 的"样板代码"部分，单独成类是为了
 * 只维护<b>一份</b>方法清单：外层的 collector 和 {@code order(n)} 返回的层都继承它，
 * 只是 {@link #target()} 不同。
 *
 * <h2>⚠ 维护成本警告</h2>
 * <p>{@code OrderedSubmitNodeCollector} 每个 MC 版本都会增删方法，这里都得跟着改。
 * 漏实现的方法在<b>编译期</b>就会报错（接口方法是抽象的），但<b>签名写错</b>（比如参数顺序）
 * 就只是少包装了一个方法，表现为"那个类型的方块实体不变淡"。
 * 各版本的差异用 Stonecutter 的 {@code //? if} 在本文件里按版本取舍，
 * 新增 MC 版本时要把接口的每个 {@code submitXxx} 逐个核对一遍。
 *
 * <p>只有走 RenderType 画几何的提交会被改写
 * （{@code submitModel} / {@code submitCrumblingOverlay} / {@code submitModelPart} /
 * {@code submitBlockModel} / {@code submitCustomGeometry}）；
 * {@code submitShadow} / {@code submitNameTag} / {@code submitText} 等与方块材质无关的提交原样转发。
 */
abstract class TranslucentSubmitForwarder implements OrderedSubmitNodeCollector {
	/** 转发的目的地。 */
	protected abstract OrderedSubmitNodeCollector target();

	// ───────────── 会换成半透明 RenderType 的提交 ─────────────

	//? if >=26.3 {
	@Override
	public <S> void submitModel(
		Model<? super S> model,
		S state,
		PoseStack poseStack,
		RenderType renderType,
		int lightCoords,
		int overlayCoords,
		int tintedColor,
		UvMapping uvMapping,
		int outlineColor
	) {
		target().submitModel(model, state, poseStack, TranslucentRenderTypes.translucentVariant(renderType), lightCoords, overlayCoords, tintedColor, uvMapping, outlineColor);
	}

	@Override
	public <S> void submitCrumblingOverlay(
		Model<? super S> model,
		S state,
		PoseStack poseStack,
		RenderType renderType,
		int lightCoords,
		int overlayCoords,
		int outlineColor,
		ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
	) {
		target().submitCrumblingOverlay(model, state, poseStack, TranslucentRenderTypes.translucentVariant(renderType), lightCoords, overlayCoords, outlineColor, crumblingOverlay);
	}
	//?} else {
	/*@Override
	public <S> void submitModel(
		Model<? super S> model,
		S state,
		PoseStack poseStack,
		RenderType renderType,
		int lightCoords,
		int overlayCoords,
		int tintedColor,
		TextureAtlasSprite sprite,
		int outlineColor,
		ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
	) {
		target().submitModel(model, state, poseStack, TranslucentRenderTypes.translucentVariant(renderType), lightCoords, overlayCoords, tintedColor, sprite, outlineColor, crumblingOverlay);
	}
	*///?}

	//? if <26.2 {
	/*@Override
	public void submitModelPart(
		ModelPart modelPart,
		PoseStack poseStack,
		RenderType renderType,
		int lightCoords,
		int overlayCoords,
		TextureAtlasSprite sprite,
		boolean cullNear,
		boolean cullFar,
		int outlineColor,
		ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
		int extra
	) {
		target().submitModelPart(modelPart, poseStack, TranslucentRenderTypes.translucentVariant(renderType), lightCoords, overlayCoords, sprite, cullNear, cullFar, outlineColor, crumblingOverlay, extra);
	}
	*///?}

	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
		target().submitBlockModel(poseStack, TranslucentRenderTypes.translucentVariant(renderType), parts, tintLayers, lightCoords, overlayCoords, outlineColor);
	}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		target().submitCustomGeometry(poseStack, TranslucentRenderTypes.translucentVariant(renderType), renderer);
	}

	// ───────────── 原样转发：各版本签名一致的 ─────────────

	@Override
	public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
		target().submitShadow(poseStack, radius, pieces);
	}

	@Override
	public void submitText(
		PoseStack poseStack,
		float x,
		float y,
		FormattedCharSequence string,
		boolean dropShadow,
		Font.DisplayMode displayMode,
		int lightCoords,
		int color,
		int backgroundColor,
		int outlineColor
	) {
		target().submitText(poseStack, x, y, string, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
	}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
		target().submitFlame(poseStack, renderState, rotation);
	}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
		target().submitLeash(poseStack, leashState);
	}

	// ───────────── 原样转发：随版本变的 ─────────────

	//? if >=26.2 {
	@Override
	public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords, CameraRenderState camera) {
		target().submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough, lightCoords, camera);
	}
	//?} else {
	/*@Override
	public void submitNameTag(PoseStack poseStack, Vec3 offset, int backgroundColor, Component text, boolean transparent, int lightCoords, double scale, CameraRenderState cameraRenderState) {
		target().submitNameTag(poseStack, offset, backgroundColor, text, transparent, lightCoords, scale, cameraRenderState);
	}
	*///?}

	//? if >=26.3 {
	@Override
	public void submitTextBackground(PoseStack poseStack, float x, float y, float width, float height, int color, Font.DisplayMode displayMode, int lightCoords) {
		target().submitTextBackground(poseStack, x, y, width, height, color, displayMode, lightCoords);
	}
	//?}

	//? if >=26.2 {
	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
		target().submitMovingBlock(poseStack, movingBlockRenderState, outlineColor);
	}
	//?} else {
	/*@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState renderState) {
		target().submitMovingBlock(poseStack, renderState);
	}
	*///?}

	//? if >=26.3 {
	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean afterTerrain) {
		target().submitBreakingBlockModel(poseStack, parts, progress, afterTerrain);
	}
	//?} else if >=26.2 {
	/*@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
		target().submitBreakingBlockModel(poseStack, parts, progress);
	}
	*///?} else {
	/*@Override
	public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
		target().submitBreakingBlockModel(poseStack, model, seed, progress);
	}
	*///?}

	//? if >=26.2 {
	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
		target().submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
	}
	//?}

	//? if >=26.3 {
	@Override
	public void submitItem(
		PoseStack poseStack,
		ItemDisplayContext displayContext,
		int lightCoords,
		int overlayCoords,
		int outlineColor,
		int[] tintLayers,
		ItemQuads quads,
		ItemStackRenderState.FoilType foilType
	) {
		target().submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
	}
	//?} else {
	/*@Override
	public void submitItem(
		PoseStack poseStack,
		ItemDisplayContext displayContext,
		int lightCoords,
		int overlayCoords,
		int outlineColor,
		int[] tintLayers,
		List<BakedQuad> quads,
		ItemStackRenderState.FoilType foilType
	) {
		target().submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
	}
	*///?}

	//? if >=26.2 {
	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState particles) {
		target().submitQuadParticleGroup(particles);
	}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
		target().submitGizmoPrimitives(group, camera, onTop);
	}
	//?} else {
	/*@Override
	public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
		target().submitParticleGroup(renderer);
	}
	*///?}
}
