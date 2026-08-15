plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "me.sisko"
version = "2.1.0"
description = "Cross-server join/leave/switch announcements, server alias commands, and Redis presence bridge for the Left4Craft proxy"

java {
    // Velocity 4.0.0's own class files are major version 69 (Java 25), so the
    // compiler has to be at least 25 to read them.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // Provided by the proxy. Also puts gson, adventure, guice, slf4j and
    // snakeyaml on the compile classpath transitively -- all of which Velocity
    // ships, so none of them get shaded.
    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    // LiteBans publishes no public Maven artifact, so this is the `litebans.api`
    // package lifted straight out of LiteBans.jar 2.19.0. Regenerate with:
    //   unzip -q LiteBans.jar 'litebans/api/*' -d /tmp/lb && (cd /tmp/lb && jar cf litebans-api-<ver>.jar litebans)
    compileOnly(files("libs/litebans-api-2.19.0.jar"))

    implementation("redis.clients:jedis:8.0.0")

    // Velocity ships SnakeYAML, but shading our own copy costs ~330 KB and
    // removes any dependence on what the proxy happens to expose to plugins.
    implementation("org.yaml:snakeyaml:2.5")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.shadowJar {
    archiveClassifier = ""

    // Jedis and its transitive deps must not collide with anything else on the
    // proxy classpath (LuckPerms and TAB both ship Redis clients of their own).
    listOf(
        "redis.clients",
        "org.apache.commons.pool2",
        "org.json",
        "io.github.resilience4j",
        "io.vavr",
        "org.yaml.snakeyaml",
    ).forEach { relocate(it, "me.sisko.left4chat.lib.$it") }

    // Velocity already provides these; bundling them risks classloader conflicts.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.code.gson:.*"))
        // Compile-time-only annotation stubs dragged in transitively.
        exclude(dependency("com.google.errorprone:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
    exclude("com/google/errorprone/**")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    // Only the shaded jar is publishable; keep the thin one out of the way.
    archiveClassifier = "thin"
}
