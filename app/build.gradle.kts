plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val verifyPublishedEtaRelease = tasks.register("verifyPublishedEtaRelease") {
    val reportFile = layout.projectDirectory.file("src/main/assets/eta-release-verification.txt")
    outputs.file(reportFile)

    doLast {
        fun readUrl(url: String): ByteArray {
            val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Eta-korean-release-verifier")
            val status = connection.responseCode
            require(status in 200..299) { "HTTP $status while reading $url" }
            return connection.inputStream.use { it.readBytes() }
        }

        fun jsonString(key: String, json: String): String? =
            Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                .find(json)
                ?.groupValues
                ?.get(1)

        fun jsonNumber(key: String, json: String): String? =
            Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)

        val tag = "2.2.0-ko"
        val expectedAsset = "Eta-2.2.0-ko.apk"
        val expectedMainCommit = "daf31f8d8d9505fd4f3a1369aacbdf6e86c81702"
        val releaseJson = readUrl(
            "https://api.github.com/repos/devuterian/Eta-korean/releases/tags/$tag"
        ).toString(Charsets.UTF_8)

        val releaseTag = jsonString("tag_name", releaseJson)
        val releaseName = jsonString("name", releaseJson)
        val releaseTarget = jsonString("target_commitish", releaseJson)
        val assetUrl = Regex(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]*Eta-2\\.2\\.0-ko\\.apk)\\\""
        ).find(releaseJson)?.groupValues?.get(1)

        require(releaseTag == tag) { "Unexpected release tag: $releaseTag" }
        require(releaseName == "Eta 2.2.0-ko") { "Unexpected release name: $releaseName" }
        require(releaseTarget == expectedMainCommit) {
            "Unexpected release target: $releaseTarget"
        }
        require(!assetUrl.isNullOrBlank()) { "Release asset $expectedAsset is missing" }

        val assetBytes = readUrl(assetUrl)
        val assetSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(assetBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        val runsJson = readUrl(
            "https://api.github.com/repos/devuterian/Eta-korean/actions/runs" +
                "?head_sha=$expectedMainCommit&per_page=10"
        ).toString(Charsets.UTF_8)
        val firstRun = runsJson.substringAfter("\"workflow_runs\"", "")
        val runId = jsonNumber("id", firstRun)
        val runNumber = jsonNumber("run_number", firstRun)
        val runName = jsonString("name", firstRun)
        val runEvent = jsonString("event", firstRun)
        val runStatus = jsonString("status", firstRun)
        val runConclusion = jsonString("conclusion", firstRun)

        require(runName == "Android APK") { "Unexpected workflow name: $runName" }
        require(runEvent == "push") { "Unexpected workflow event: $runEvent" }
        require(runStatus == "completed") { "Main workflow is not completed: $runStatus" }
        require(runConclusion == "success") { "Main workflow failed: $runConclusion" }

        reportFile.asFile.parentFile.mkdirs()
        reportFile.asFile.writeText(
            """
            release_tag=$releaseTag
            release_name=$releaseName
            release_target=$releaseTarget
            asset_name=$expectedAsset
            asset_size=${assetBytes.size}
            asset_sha256=$assetSha256
            actions_run_id=$runId
            actions_run_number=$runNumber
            actions_name=$runName
            actions_event=$runEvent
            actions_status=$runStatus
            actions_conclusion=$runConclusion
            main_commit=$expectedMainCommit
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyPublishedEtaRelease)
}

android {
    namespace = "fuck.andes"
    compileSdk = 37

    defaultConfig {
        applicationId = "fuck.andes"
        minSdk = 34
        targetSdk = 36
        versionCode = 220
        versionName = "2.2.0"
        versionNameSuffix = "-ko"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    packaging {
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.lucide.icons)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
