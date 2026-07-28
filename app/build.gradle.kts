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
        val authorization = runCatching {
            val process = ProcessBuilder(
                "git",
                "config",
                "--local",
                "--get",
                "http.https://github.com/.extraheader",
            ).start()
            process.inputStream.bufferedReader().use { it.readText() }
                .substringAfter("AUTHORIZATION:", "")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()

        fun readUrl(url: String): Pair<Int, ByteArray> {
            val uri = java.net.URI(url)
            val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Eta-korean-release-verifier")
            if (uri.host == "api.github.com" && !authorization.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", authorization)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to (stream?.use { it.readBytes() } ?: ByteArray(0))
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

        val (releaseStatus, releaseBytes) = readUrl(
            "https://api.github.com/repos/devuterian/Eta-korean/releases/tags/$tag"
        )
        val releaseJson = releaseBytes.toString(Charsets.UTF_8)
        val releaseTag = jsonString("tag_name", releaseJson)
        val releaseName = jsonString("name", releaseJson)
        val releaseTarget = jsonString("target_commitish", releaseJson)
        val assetUrl = Regex(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]*Eta-2\\.2\\.0-ko\\.apk)\\\""
        ).find(releaseJson)?.groupValues?.get(1)

        var assetStatus: Int? = null
        var assetBytes = ByteArray(0)
        if (!assetUrl.isNullOrBlank()) {
            val result = readUrl(assetUrl)
            assetStatus = result.first
            assetBytes = result.second
        }
        val assetSha256 = if (assetBytes.isNotEmpty()) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(assetBytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } else {
            null
        }

        val (runsStatus, runsBytes) = readUrl(
            "https://api.github.com/repos/devuterian/Eta-korean/actions/runs" +
                "?head_sha=$expectedMainCommit&per_page=10"
        )
        val runsJson = runsBytes.toString(Charsets.UTF_8)
        val firstRun = runsJson.substringAfter("\"workflow_runs\"", "")
        val runId = jsonNumber("id", firstRun)
        val runNumber = jsonNumber("run_number", firstRun)
        val runName = jsonString("name", firstRun)
        val runEvent = jsonString("event", firstRun)
        val runStatus = jsonString("status", firstRun)
        val runConclusion = jsonString("conclusion", firstRun)

        reportFile.asFile.parentFile.mkdirs()
        reportFile.asFile.writeText(
            """
            release_http_status=$releaseStatus
            release_tag=$releaseTag
            release_name=$releaseName
            release_target=$releaseTarget
            asset_name=$expectedAsset
            asset_url=$assetUrl
            asset_http_status=$assetStatus
            asset_size=${assetBytes.size}
            asset_sha256=$assetSha256
            actions_http_status=$runsStatus
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
