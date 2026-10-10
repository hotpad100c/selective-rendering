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

	static List<ConfigOptionWrapper> rows(RegionHandle handle) {
		Region region = handle.region();
		List<ConfigOptionWrapper> rows = new ArrayList<>();

		rows.add(new ConfigOptionWrapper(new RegionCornerConfig(handle, true)));
		rows.add(new ConfigOptionWrapper(new RegionCornerConfig(handle, false)));
		rows.add(new ConfigOptionWrapper(new RegionTargetsConfig(handle)));

		ConfigStringList rules = new ConfigStringList("region_rules", ImmutableList.of()).apply(ModConfigs.KEY);
		rules.setStrings(region.options().ruleSources());
		rules.setValueChangeCallback(config -> handle.update(options -> options.withRuleSources(config.getStrings())));

		ConfigStringList entities = new ConfigStringList("region_entity_types", ImmutableList.of()).apply(ModConfigs.KEY);
		entities.setStrings(region.options().entityTypes());
		entities.setValueChangeCallback(config -> handle.update(options -> options.withEntityTypes(config.getStrings())));

		ConfigStringList particles = new ConfigStringList("region_particle_types", ImmutableList.of()).apply(ModConfigs.KEY);
		particles.setStrings(region.options().particleTypes());
		particles.setValueChangeCallback(config -> handle.update(options -> options.withParticleTypes(config.getStrings())));

		rows.addAll(ConfigOptionWrapper.createFor(List.of(rules, entities, particles)));
		return rows;
	}

	static String format(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
}
