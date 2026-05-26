plugins {
    java
    id("com.gradleup.shadow") version "8.3.5" // Compatible with the bundled Gradle 8.5 runtime
}

group = "com.servercore"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://jitpack.io")
    maven("https://mvn.lumine.io/repository/maven-public/") {
        name = "lumine"
    }
    maven("https://nexus.phoenixdevt.fr/repository/maven-public/")
}

dependencies {
    // Paper API 1.21
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    
    // Vault API (Economy Bridge)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    
    // HikariCP (Connection Pool)
    implementation("com.zaxxer:HikariCP:5.1.0")
    
    // SQLite JDBC Driver
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")
    
    // PlaceholderAPI
    compileOnly("me.clip:placeholderapi:2.11.6")
    
    // ProtocolLib
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    
    // AuraSkills API
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.3.12")
    
    // MythicLib
    compileOnly("io.lumine:MythicLib-dist:1.7.1-SNAPSHOT")
    
    // InventoryFramework (stefvanschie) - 必须 Shade 进插件内，否则在 IF 内部获取 JavaPlugin 时会导致 ClassLoader 异常
    implementation("com.github.stefvanschie.inventoryframework:IF:0.10.11")

    // MythicMobs 兼容模块
    compileOnly("io.lumine:Mythic-Dist:5.6.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    build {
        dependsOn("shadowJar")
    }
}

tasks.shadowJar {
    // Relocate IF
    relocate("com.github.stefvanschie.inventoryframework", "com.servercore.libs.inventoryframework")

    // Relocate HikariCP
    relocate("com.zaxxer.hikari", "com.servercore.libs.hikari")
    
    // Relocate SQLite JDBC
    relocate("org.sqlite", "com.servercore.libs.sqlite")
    
    archiveFileName.set("ServerCore-${project.version}.jar")
}
