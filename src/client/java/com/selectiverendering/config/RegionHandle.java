package com.selectiverendering.config;

import com.selectiverendering.region.Region;
import com.selectiverendering.region.RegionEditor;
import com.selectiverendering.region.RegionOptions;

import java.util.function.UnaryOperator;

final class RegionHandle {
	private Region current;

	RegionHandle(Region region) {
		this.current = region;
	}

	Region region() {
		return this.current;
	}

	void update(UnaryOperator<RegionOptions> change) {
		Region next = this.current.withOptions(change.apply(this.current.options()));

		if (RegionEditor.updateRegion(this.current, next)) {
			this.current = next;
		}
	}
}
