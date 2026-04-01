rootProject.name = "mcp-data-gateway"

pluginManagement {
    repositories {
        maven("https://repo.spring.io/milestone")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://repo.spring.io/milestone")
        mavenCentral()
    }
}
