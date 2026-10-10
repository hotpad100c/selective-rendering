package com.selectiverendering.region;

import com.selectiverendering.matchingrule.BlockMatchRule;

import java.util.ArrayList;
import java.util.List;

public record RegionOptions(boolean blocks, boolean entities, boolean particles, List<BlockMatchRule> rules) {
	public static final RegionOptions DEFAULT = new RegionOptions(true, false, false, List.of());

	public RegionOptions {
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public RegionOptions withBlocks(boolean value) {
		return new RegionOptions(value, entities, particles, rules);
	}

	public RegionOptions withEntities(boolean value) {
		return new RegionOptions(blocks, value, particles, rules);
	}

	public RegionOptions withParticles(boolean value) {
		return new RegionOptions(blocks, entities, value, rules);
	}

	public RegionOptions withRules(List<BlockMatchRule> value) {
		return new RegionOptions(blocks, entities, particles, value);
	}

	public RegionOptions withRuleSources(List<String> sources) {
		List<BlockMatchRule> parsed = new ArrayList<>();

		if (sources != null) {
			for (String source : sources) {
				BlockMatchRule rule = BlockMatchRule.parse(source);
				if (rule != null) {
					parsed.add(rule);
				}
			}
		}

		return withRules(parsed);
	}

	public List<String> ruleSources() {
		return rules.stream().map(BlockMatchRule::source).toList();
	}
}
