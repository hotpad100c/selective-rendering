package com.selectiverendering.mixin;

import com.selectiverendering.SelectiveRendering;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 绘制已保存选区、当前选区和角点的线框。 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	private static final int SELECTION_STROKE = 0xFFFFFFFF;
	private static final int CORNER_1_STROKE = 0xFFFF3333;
	private static final int CORNER_2_STROKE = 0xFF3355FF;
	private static final int CORNER_1_FILL = 0x33FF3333;
	private static final int CORNER_2_FILL = 0x333355FF;
	private static final int ADDED_STROKE = 0xFF00E5FF;
	private static final int ADDED_FILL = 0x1800E5FF;
	private static final float STROKE_WIDTH = 2.0F;

	/** 标记尚未清空的 gizmo，防止暂停渲染时重复累积。 */
	@Unique
	private static boolean selectiveRendering$gizmosDrained = true;

	//? if >=26.2 {
	@Inject(method = "collectPerFrameRenderThreadGizmos", at = @At("TAIL"))
	private void selectiveRendering$emitRegionGizmo(CallbackInfoReturnable<Gizmos.TemporaryCollection> cir) {
		emit();
	}
	//?} else {
	/*@Inject(method = "collectPerFrameGizmos", at = @At("TAIL"))
	private void selectiveRendering$emitRegionGizmo(CallbackInfoReturnable<Gizmos.TemporaryCollection> cir) {
		emit();
	}
	*///?}

	@Inject(method = "finalizeGizmoCollection", at = @At("TAIL"), require = 0)
	private void selectiveRendering$markGizmosDrained(CallbackInfo ci) {
		selectiveRendering$gizmosDrained = true;
	}

	@Unique
    private static void emit() {
		if (!selectiveRendering$gizmosDrained) {
			return;
		}

		if (!SelectiveRendering.isWandHeld()) {
			return;
		}

		for (Region added : SelectiveRenderingManager.regions()) {
			Gizmos.cuboid(added.box(), GizmoStyle.fill(ADDED_FILL));
			var gizmoProp = Gizmos.cuboid(added.box(), GizmoStyle.stroke(ADDED_STROKE, STROKE_WIDTH));
			gizmoProp.setAlwaysOnTop();
		}

		BlockPos first = RegionEditor.getCorner(0);
		BlockPos second = RegionEditor.getCorner(1);

		if (first != null && second != null) {
			AABB box = RegionEditor.regionBox();
			if (box != null) {
				Gizmos.cuboid(box, GizmoStyle.stroke(SELECTION_STROKE, STROKE_WIDTH));
			}
		}

		corner(0, first);
		corner(1, second);

		selectiveRendering$gizmosDrained = false;
	}

	@Unique
    private static void corner(int index, BlockPos pos) {
		if (pos == null) {
			return;
		}

		boolean first = index == 0;
		int stroke = first ? CORNER_1_STROKE : CORNER_2_STROKE;

		if (RegionEditor.isCornerSelected(index)) {
			int fill = first ? CORNER_1_FILL : CORNER_2_FILL;
			Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(stroke, STROKE_WIDTH, fill)).setAlwaysOnTop();
			return;
		}

		Gizmos.cuboid(pos, GizmoStyle.stroke(stroke, STROKE_WIDTH)).setAlwaysOnTop();
	}
}
