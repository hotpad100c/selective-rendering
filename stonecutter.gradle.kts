plugins {
	id("dev.kikugie.stonecutter")
}

// 当前"展开"的版本：src/ 里的 `//? if` 分支按它来注释 / 取消注释。
// 提交前请切回最新版本（= settings.gradle.kts 里的 vcsVersion）。
//   ./gradlew "Set active project to 26.2"
stonecutter active "26.3"
