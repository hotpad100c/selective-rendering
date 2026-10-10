package com.selectiverendering.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.selectiverendering.region.Region;

import fi.dy.masa.malilib.config.ConfigType;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBase;

public final class RegionRowConfig extends ConfigBase<RegionRowConfig> implements IConfigValue {
	private final Region region;
	private final int index;
	private final int children;

	public RegionRowConfig(Region region, int index, int children) {
		super(ConfigType.STRING, region.toSource());

		this.region = region;
		this.index = index;
		this.children = children;
	}

	public int children() {
		return this.children;
	}

	public Region region() {
		return this.region;
	}

	public String title() {
		return "#" + this.index + "  " + RegionConfigs.format(this.region.min()) + " → " + RegionConfigs.format(this.region.max());
	}

	@Override
	public String getStringValue() {
		return this.region.toSource();
	}

	@Override
	public String getDefaultStringValue() {
		return this.region.toSource();
	}

	@Override
	public void setValueFromString(String value) {
	}

	@Override
	public boolean isModified() {
		return false;
	}

	@Override
	public boolean isModified(String newValue) {
		return false;
	}

	@Override
	public void resetToDefault() {
	}

	@Override
	public void setValueFromJsonElement(JsonElement element) {
	}

	@Override
	public JsonElement getAsJsonElement() {
		return new JsonPrimitive(this.region.toSource());
	}
}
