package com.selectiverendering.config;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.Screen;

import com.selectiverendering.SelectiveRendering;
import com.selectiverendering.SelectiveRenderingManager;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

public final class ModConfigGui extends GuiConfigsBase {
	public ModConfigGui(Screen parent) {
		super(10, 50, SelectiveRendering.MOD_ID, parent, SelectiveRendering.MOD_ID + ".config.title");
		this.setParent(parent);
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		List<ConfigOptionWrapper> configs = new ArrayList<>(ConfigOptionWrapper.createFor(ModConfigs.OPTIONS));

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
			}
		};
	}

	private static String presetsHeading() {
		return ModConfigs.text("presets");
	}
}
