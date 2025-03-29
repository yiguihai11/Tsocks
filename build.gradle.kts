// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        google()
        mavenCentral()
        // 如果插件不在默认仓库中，也可以加上 Gradle Plugin Portal
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }
    dependencies {
        // 添加 org.mozilla.rust-android-gradle 插件的依赖
        classpath(libs.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
}
