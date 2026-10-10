package com.selectiverendering.config;

import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;

import java.util.List;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.render.GuiContext;

public final class WidgetRegionRow extends WidgetConfigOption {
	private static final int TOGGLE_WIDTH = 20;
	private static final int GAP = 6;

	private int rows;

	public WidgetRegionRow(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
	}

	@Override
	protected void addConfigOption(int x, int y, int labelWidth, int configWidth, IConfigBase config) {
		if (!(config instanceof RegionRowConfig row)) {
			return;
		}

		Region region = row.region();
		this.rows = ModConfigGui.isExpanded(region) ? row.children() : 0;

		String removeText = ModConfigs.text("region_remove");
		int removeWidth = Math.max(36, this.getStringWidth(removeText) + 12);
		int right = WidgetPresetRow.resetButtonRight(this, x, labelWidth, configWidth);

		ButtonGeneric toggle = new ButtonGeneric(x, y, TOGGLE_WIDTH, 20, ModConfigGui.isExpanded(region) ? "▼" : "▶");
		this.addButton(toggle, (button, mouseButton) -> {
			ModConfigGui.toggle(region);
			this.refresh();
		});

		int labelX = x + TOGGLE_WIDTH + GAP;
		this.addLabel(labelX, y + 6, right - removeWidth - GAP - labelX, 8, 0xFFFFFFFF, row.title());

		ButtonGeneric remove = new ButtonGeneric(right - removeWidth, y, removeWidth, 20, removeText);
		this.addButton(remove, (button, mouseButton) -> {
			RegionEditor.removeRegions(List.of(region));
			ModConfigGui.forget(region);
			this.refresh();
		});
	}

	private void refresh() {
		if (this.host instanceof GuiBase gui) {
			gui.initGui();
		}
	}

	@Override
	public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
		super.render(ctx, mouseX, mouseY, selected);

		if (this.rows > 0) {
			ModConfigGui.addFrame(this.getX() - 2, this.getY() - 1, this.getWidth() + 4, (this.rows + 1) * this.getHeight() + 1);
		}
	}
}
