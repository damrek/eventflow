pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "eventflow"

// One Gradle project per service, physically located under services/.
include("catalog")
project(":catalog").projectDir = file("services/catalog")

include("booking")
project(":booking").projectDir = file("services/booking")

include("notification")
project(":notification").projectDir = file("services/notification")

include("payment")
project(":payment").projectDir = file("services/payment")

include("payment-simulator")
project(":payment-simulator").projectDir = file("services/payment-simulator")
