package com.selectiverendering.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.screens.Screen;

import com.selectiverendering.SelectiveRendering;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.region.Region;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

public final class ModConfigGui extends GuiConfigsBase {
	private static final int FRAME_COLOR = 0xFF5F8FBF;

	private static final Set<Region> EXPANDED = new HashSet<>();
	private static final List<int[]> FRAMES = new ArrayList<>();

	public ModConfigGui(Screen parent) {
		super(10, 50, SelectiveRendering.MOD_ID, parent, SelectiveRendering.MOD_ID + ".config.title");
		this.setParent(parent);
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		List<ConfigOptionWrapper> configs = new ArrayList<>(ConfigOptionWrapper.createFor(ModConfigs.OPTIONS));

		configs.add(new ConfigOptionWrapper(ModConfigs.text("regions_heading")));

		int index = 1;
		for (Region region : SelectiveRenderingManager.regions()) {
			List<ConfigOptionWrapper> children = EXPANDED.contains(region) ? RegionConfigs.rows(region) : List.of();

			configs.add(new ConfigOptionWrapper(new RegionRowConfig(region, index++, children.size())));
			configs.addAll(children);
		}

		configs.add(new ConfigOptionWrapper(presetsHeading()));

		for (Preset preset : SelectiveRenderingManager.presets()) {
			configs.add(new ConfigOptionWrapper(new PresetRowConfig(preset)));
		}

		return configs;
	}

	@Override
	protected WidgetListConfigOptions createListWidget(int listX, int listY) {
		return new WidgetListConfigOptions(listX, listY,
				this.getBrowserWidth(), this.getBrowserHeight(), this.getConfigWidth(), 0.f, this.useKeybindSearch(), this) {
			@Override
			protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ConfigOptionWrapper entry) {
				if (entry.getType() == ConfigOptionWrapper.Type.LABEL && presetsHeading().equals(entry.getLabel())) {
					return new WidgetPresetHeader(x, y, this.browserEntryWidth, this.browserEntryHeight,
							this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
				}

				if (entry.getConfig() instanceof RegionRowConfig) {
					return new WidgetRegionRow(x, y, this.browserEntryWidth, this.browserEntryHeight,
							this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
				}

				if (entry.getConfig() instanceof RegionTargetsConfig) {
					return new WidgetRegionTargets(x, y, this.browserEntryWidth, this.browserEntryHeight,
							this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
				}

				if (entry.getConfig() instanceof PresetRowConfig) {
					return new WidgetPresetRow(x, y, this.browserEntryWidth, this.browserEntryHeight,
							this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
				}

				if (entry.getConfig() instanceof ModConfigs.ModeConfig) {
					return new WidgetModeRow(x, y, this.browserEntryWidth, this.browserEntryHeight,
							this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
				}

				return super.createListEntryWidget(x, y, listIndex, isOdd, entry);
			}

			@Override
			public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
				super.drawContents(ctx, mouseX, mouseY, partialTicks);

				for (WidgetConfigOption widget : this.listWidgets) {
					if (widget instanceof WidgetModeRow row) {
						row.drawOpenDropdown(ctx, mouseX, mouseY);
					}
				}

				drawFrames(ctx, this.posY, this.posY + this.browserHeight);
			}
		};
	}

	static void addFrame(int x, int y, int width, int height) {
		FRAMES.add(new int[] {x, y, width, height});
	}

	/** 在列表可见范围内画出各个展开选区的外框，画完清空。 */
	private static void drawFrames(GuiContext ctx, int top, int bottom) {
		for (int[] frame : FRAMES) {
			int x = frame[0];
			int right = x + frame[2] - 1;
			int y1 = frame[1];
			int y2 = frame[1] + frame[3] - 1;

			int from = Math.max(y1, top);
			int to = Math.min(y2, bottom - 1);

			if (from <= to) {
				RenderUtils.drawRect(ctx, x, from, 1, to - from + 1, FRAME_COLOR);
				RenderUtils.drawRect(ctx, right, from, 1, to - from + 1, FRAME_COLOR);
			}

			if (y1 >= top && y1 < bottom) {
				RenderUtils.drawRect(ctx, x, y1, frame[2], 1, FRAME_COLOR);
			}

			if (y2 >= top && y2 < bottom) {
				RenderUtils.drawRect(ctx, x, y2, frame[2], 1, FRAME_COLOR);
			}
		}

		FRAMES.clear();
	}

	static boolean isExpanded(Region region) {
		return EXPANDED.contains(region);
	}

	static void toggle(Region region) {
		if (!EXPANDED.remove(region)) {
			EXPANDED.add(region);
		}
	}

	static void forget(Region region) {
		EXPANDED.remove(region);
	}

	private static String presetsHeading() {
		return ModConfigs.text("presets");
	}
}
