pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
	create(rootProject) {
		// 所有要产出 jar 的 MC 版本；每个版本是一个"节点"，共用同一份 src/。
		versions("26.1.2", "26.2", "26.3")

		// src/ 里签入的代码对应哪个版本：该版本的 `//? if` 分支处于展开（未注释）状态。
		// 取最新的一个，开发时面对的就是我们要迁移到的 API。
		// 必须与 stonecutter.gradle.kts 里的 `stonecutter active` 保持一致。
		vcsVersion = "26.3"
	}
}

rootProject.name = "selective-rendering"
