package com.selectiverendering.config;

import com.google.common.collect.ImmutableList;
import com.selectiverendering.region.Region;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;

final class RegionConfigs {
	private RegionConfigs() {
	}

	static List<ConfigOptionWrapper> rows(Region region) {
		RegionHandle handle = new RegionHandle(region);
		List<ConfigOptionWrapper> rows = new ArrayList<>();

		rows.add(new ConfigOptionWrapper("    " + ModConfigs.text("region_min") + ": " + format(region.min())));
		rows.add(new ConfigOptionWrapper("    " + ModConfigs.text("region_max") + ": " + format(region.max())));
		rows.add(new ConfigOptionWrapper(new RegionTargetsConfig(handle)));

		ConfigStringList rules = new ConfigStringList("region_rules", ImmutableList.of()).apply(ModConfigs.KEY);
		rules.setStrings(region.options().ruleSources());
		rules.setValueChangeCallback(config -> handle.update(options -> options.withRuleSources(config.getStrings())));
		rows.addAll(ConfigOptionWrapper.createFor(List.of(rules)));

		return rows;
	}

	static String format(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
