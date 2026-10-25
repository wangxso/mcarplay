plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

// 代码格式化：ktlint（Kotlin 官方代码风格），规则见根目录 .editorconfig
//  ./gradlew spotlessApply   —— 自动格式化
//  ./gradlew spotlessCheck   —— 仅校验（pre-commit 钩子提交前自动执行）
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.3.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.3.1")
    }
}
