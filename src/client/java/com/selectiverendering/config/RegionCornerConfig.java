package com.selectiverendering.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import fi.dy.masa.malilib.config.ConfigType;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBase;

public final class RegionCornerConfig extends ConfigBase<RegionCornerConfig> implements IConfigValue {
	private final RegionHandle handle;
	private final boolean min;

	RegionCornerConfig(RegionHandle handle, boolean min) {
		super(ConfigType.STRING, min ? "region_min" : "region_max");

		this.handle = handle;
		this.min = min;
	}

	@Override
	public String getConfigGuiDisplayName() {
		return "";
	}

	RegionHandle handle() {
		return this.handle;
	}

	boolean isMin() {
		return this.min;
	}

	@Override
	public String getStringValue() {
		return "";
	}

	@Override
	public String getDefaultStringValue() {
		return "";
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
		return new JsonPrimitive("");
	}
}
