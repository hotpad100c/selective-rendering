package com.selectiverendering.config.region;

import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.config.entrypoint.ModConfigs;
import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;

/** "合并到"一行：点第一个按钮切换目标选区，点第二个按钮执行合并。 */
public final class WidgetRegionMerge extends WidgetConfigOption {
	private int target;

	public WidgetRegionMerge(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
	}

	@Override
	protected void addConfigOption(int x, int y, int labelWidth, int configWidth, IConfigBase config) {
		if (!(config instanceof RegionMergeConfig merge)) {
			return;
		}

		RegionHandle handle = merge.handle();
		Region self = handle.region();

		List<Region> all = SelectiveRenderingManager.regions();
		List<Region> others = new ArrayList<>();
		for (Region region : all) {
			if (!region.equals(self)) {
				others.add(region);
			}
		}

		this.addLabel(x + 4, y + 6, labelWidth, 8, 0xFFFFFFFF, ModConfigs.text("region_merge"));

		int pickX = x + labelWidth + 10;
		String applyText = ModConfigs.text("region_merge_apply");
		int applyWidth = Math.max(36, this.getStringWidth(applyText) + 12);

		if (others.isEmpty()) {
			ButtonGeneric none = new ButtonGeneric(pickX, y, configWidth, 20, ModConfigs.text("region_merge_none"));
			none.setEnabled(false);
			this.addButton(none, (button, mouseButton) -> { });
			return;
		}

		ButtonGeneric pick = new ButtonGeneric(pickX, y, configWidth, 20, this.describe(all, others.get(this.target % others.size())));
		this.addButton(pick, (button, mouseButton) -> {
			this.target = (this.target + 1) % others.size();
			pick.setDisplayString(this.describe(all, others.get(this.target)));
		});

		this.addButton(new ButtonGeneric(pickX + configWidth + 2, y, applyWidth, 20, applyText, ModConfigs.text("region_merge_tip")), (button, mouseButton) -> {
			RegionEditor.mergeRegion(handle.region(), others.get(this.target % others.size()));

			if (this.host instanceof GuiBase gui) {
				gui.initGui();
			}
		});
	}

	private String describe(List<Region> all, Region region) {
		return "#" + (all.indexOf(region) + 1) + "  " + region.areas().size() + " " + ModConfigs.text("region_areas");
	}
}
