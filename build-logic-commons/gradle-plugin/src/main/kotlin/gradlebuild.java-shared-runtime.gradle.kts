plugins {
    `java-library`
    groovy

    id("gradlebuild.ci-reporting")
    id("gradlebuild.code-quality")
    id("gradlebuild.module-jar")
    id("gradlebuild.repositories")
    id("gradlebuild.reproducible-archives")
    id("gradlebuild.private-javadoc")
}

description = "A plugin that sets up a Java code that is shared between build-logic and runtime"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useSpock()
            dependencies {
                implementation(platform("gradlebuild:build-platform"))
            }
        }
    }
}
