package com.selectiverendering.region;

import com.selectiverendering.matchingrule.BlockMatchRule;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 选区自己的隐藏对象开关和三份本地名单（方块、实体类型、粒子类型）。 */
public record RegionOptions(
	boolean blocks,
	boolean entities,
	boolean particles,
	List<BlockMatchRule> rules,
	List<String> entityTypes,
	List<String> particleTypes
) {
	public static final RegionOptions DEFAULT = new RegionOptions(true, false, false, List.of(), List.of(), List.of());

	private static final Map<EntityType<?>, String> ENTITY_IDS = new ConcurrentHashMap<>();
	private static final Map<ParticleType<?>, String> PARTICLE_IDS = new ConcurrentHashMap<>();

	public RegionOptions {
		rules = rules == null ? List.of() : List.copyOf(rules);
		entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
		particleTypes = particleTypes == null ? List.of() : List.copyOf(particleTypes);
	}

	public RegionOptions withBlocks(boolean value) {
		return new RegionOptions(value, entities, particles, rules, entityTypes, particleTypes);
	}

	public RegionOptions withEntities(boolean value) {
		return new RegionOptions(blocks, value, particles, rules, entityTypes, particleTypes);
	}

	public RegionOptions withParticles(boolean value) {
		return new RegionOptions(blocks, entities, value, rules, entityTypes, particleTypes);
	}

	public RegionOptions withRules(List<BlockMatchRule> value) {
		return new RegionOptions(blocks, entities, particles, value, entityTypes, particleTypes);
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

	public RegionOptions withEntityTypes(List<String> ids) {
		return new RegionOptions(blocks, entities, particles, rules, normalize(ids), particleTypes);
	}

	public RegionOptions withParticleTypes(List<String> ids) {
		return new RegionOptions(blocks, entities, particles, rules, entityTypes, normalize(ids));
	}

	public List<String> ruleSources() {
		return rules.stream().map(BlockMatchRule::source).toList();
	}

	public boolean matchesEntity(EntityType<?> type) {
		return entityTypes.isEmpty() || entityTypes.contains(ENTITY_IDS.computeIfAbsent(type, key -> BuiltInRegistries.ENTITY_TYPE.getKey(key).toString()));
	}

	public boolean matchesParticle(ParticleType<?> type) {
		if (particleTypes.isEmpty()) {
			return true;
		}

		return type != null && particleTypes.contains(PARTICLE_IDS.computeIfAbsent(type, key -> BuiltInRegistries.PARTICLE_TYPE.getKey(key).toString()));
	}

	/** 只写与默认值不同的项 */
	public CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();

		if (!blocks) {
			tag.putBoolean("blocks", false);
		}

		if (entities) {
			tag.putBoolean("entities", true);
		}

		if (particles) {
			tag.putBoolean("particles", true);
		}

		if (!rules.isEmpty()) {
			tag.put("rules", strings(ruleSources()));
		}

		if (!entityTypes.isEmpty()) {
			tag.put("entity_types", strings(entityTypes));
		}

		if (!particleTypes.isEmpty()) {
			tag.put("particle_types", strings(particleTypes));
		}

		return tag;
	}

	public static RegionOptions fromTag(CompoundTag tag) {
		return new RegionOptions(
			tag.getBooleanOr("blocks", true),
			tag.getBooleanOr("entities", false),
			tag.getBooleanOr("particles", false),
			null,
			null,
			null
		).withRuleSources(strings(tag.getListOrEmpty("rules")))
			.withEntityTypes(strings(tag.getListOrEmpty("entity_types")))
			.withParticleTypes(strings(tag.getListOrEmpty("particle_types")));
	}

	/** 试图将 id 规范成带命名空间的形式，无效的丢弃。 */
	private static List<String> normalize(List<String> ids) {
		LinkedHashSet<String> normalized = new LinkedHashSet<>();

		if (ids != null) {
			for (String id : ids) {
				Identifier parsed = id == null ? null : Identifier.tryParse(id.trim().toLowerCase());
				if (parsed != null) {
					normalized.add(parsed.toString());
				}
			}
		}

		return List.copyOf(normalized);
	}

	private static ListTag strings(List<String> values) {
		ListTag list = new ListTag();

		for (String value : values) {
			list.add(StringTag.valueOf(value));
		}

		return list;
	}

	private static List<String> strings(ListTag list) {
		List<String> values = new ArrayList<>();

		for (Tag element : list) {
			element.asString().ifPresent(values::add);
		}

		return values;
	}
}
