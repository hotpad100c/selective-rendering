package com.selectiverendering.render.information;

import com.selectiverendering.SelectiveRendering;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.region.Area;
import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;
import com.selectiverendering.region.RegionOutline;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class InfoRenderer {
    private static final int SELECTION_STROKE = 0xFFFFFFFF;
    private static final int CORNER_1_STROKE = 0xFFFF3333;
    private static final int CORNER_2_STROKE = 0xFF3355FF;
    private static final int CORNER_1_FILL = 0x33FF3333;
    private static final int CORNER_2_FILL = 0x333355FF;
    private static final int FILL_ALPHA = 0x18;
    private static final int SELECTED_FILL_ALPHA = 0x38;
    private static final float STROKE_WIDTH = 2.0F;
    private static final float SELECTED_STROKE_WIDTH = 4.0F;
    public static boolean gizmosDrained = true;

    public static void emit() {
        if (!gizmosDrained) {
            return;
        }

        if (!SelectiveRendering.isWandHeld()) {
            return;
        }

        Region selected = SelectiveRenderingManager.selectedRegion();

        for (Region region : SelectiveRenderingManager.regions()) {
            boolean chosen = region.equals(selected);
            int stroke = 0xFF000000 | region.color();
            int fill = ((chosen ? SELECTED_FILL_ALPHA : FILL_ALPHA) << 24) | region.color();
            float width = chosen ? SELECTED_STROKE_WIDTH : STROKE_WIDTH;

            for (Area area : region.areas()) {
                Gizmos.cuboid(area.box(), GizmoStyle.fill(fill));
            }

            for (RegionOutline.Segment segment : region.outline()) {
                Gizmos.line(
                        new Vec3(segment.x1(), segment.y1(), segment.z1()),
                        new Vec3(segment.x2(), segment.y2(), segment.z2()),
                        stroke,
                        width
                ).setAlwaysOnTop();
            }
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

        gizmosDrained = false;
    }

    private static void corner(int index, BlockPos pos) {
        if (pos == null) {
            return;
        }

        boolean first = index == 0;
        int stroke = first ? CORNER_1_STROKE : CORNER_2_STROKE;

        if (RegionEditor.isCornerSelected(index)) {
            int fill = first ? CORNER_1_FILL : CORNER_2_FILL;
            Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(stroke, STROKE_WIDTH, fill));
            return;
        }

        Gizmos.cuboid(pos, GizmoStyle.stroke(stroke, STROKE_WIDTH)).setAlwaysOnTop();
    }
}
