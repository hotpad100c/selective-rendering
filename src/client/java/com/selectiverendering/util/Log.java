package com.selectiverendering.util;

import com.selectiverendering.SelectiveRendering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 日志入口，可用 JVM 参数 -Dselective_rendering.log=warn 将输出级别设为 WARN。 */
public final class Log {
	private static final Logger LOGGER = LoggerFactory.getLogger(SelectiveRendering.MOD_ID);
	private static final boolean WARN = "warn".equalsIgnoreCase(System.getProperty("selective_rendering.log"));

	private Log() {
	}

	public static void say(String message, Object... args) {
		if (WARN) {
			LOGGER.warn(message, args);
			return;
		}

		LOGGER.info(message, args);
	}

	public static void error(String message, Object... args) {
		LOGGER.error(message, args);
	}
}
