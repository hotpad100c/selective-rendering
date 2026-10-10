plugins {
	id("dev.kikugie.stonecutter")
}

// 当前"展开"的版本：src/ 里的 `//? if` 分支按它来注释 / 取消注释。
// 提交前请切回最新版本（= settings.gradle.kts 里的 vcsVersion）。
//   ./gradlew "Set active project to 26.2"
stonecutter active "26.3"

// 默认只处理 java / kt 等源码；mixin 配置要按版本增删条目，所以让 .json 也走预处理。
stonecutter handlers {
	inherit("json5", "json")
}
