pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bill"

include(
    ":app",
    ":application",
    ":core:designsystem",
    ":core:domain",
    ":core:ledger",
    ":core:model",
    ":data:local",
    ":feature:accounts",
    ":feature:ledger",
    ":feature:overview",
    ":feature:review",
    ":ocr:paddle",
    ":source:contract",
    ":source:generic-photo-ocr",
    ":source:generic-delimited-statement",
    ":source:generic-receipt-image",
    ":source:generic-notification",
    ":source:generic-share-text",
    ":source:pipeline",
    ":source:review-contract",
)
