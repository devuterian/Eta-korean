package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.data.repository.CommunityCatalogRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.i18n.ko
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CommunityCatalogViewModel(application: Application) : AndroidViewModel(application) {
    val store = CommunityCatalogStore(application, viewModelScope)
}

/** 目录浏览页和模型选择页共用同一份已读取数据，避免导航时重新请求。 */
internal class CommunityCatalogStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val catalogRefreshIntervalMillis = 24 * 60 * 60 * 1000L
    private val retryCooldownMillis = 60 * 60 * 1000L
    private val applicationContext = context.applicationContext
    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var lastAutomaticRefreshAttemptAt: Long? = null

    var catalog by mutableStateOf<CommunityCatalog?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadError by mutableStateOf(false)
        private set
    var refreshError by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set

    fun openCatalog() {
        if (catalog != null) {
            refreshIfNeeded()
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            loadLocal()
            refreshIfNeeded()
        }
    }

    fun ensureLoaded() {
        if (catalog != null || loadJob?.isActive == true) return
        loadJob = scope.launch { loadLocal() }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            refreshing = true
            refreshError = null
            try {
                val result = CommunityCatalogRepository.refresh(applicationContext)
                val refreshed = result.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    refreshError = failure.catalogErrorMessage()
                    return@launch
                }
                catalog = refreshed
                loadError = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                refreshError = failure.catalogErrorMessage()
            } finally {
                refreshing = false
            }
        }
    }

    suspend fun importProvider(provider: CommunityCatalogProvider, modelIds: Set<String>): String {
        check(!importing) { "目录导入正在进行" }
        importing = true
        try {
            val added = ProviderRepository.addProvider(
                CommunityCatalogRepository.newProvider(provider, modelIds),
            )
            return added.id
        } finally {
            importing = false
        }
    }

    private suspend fun loadLocal() {
        loading = true
        loadError = false
        try {
            catalog = CommunityCatalogRepository.loadLocal(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadError = true
        } finally {
            loading = false
        }
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (catalog?.shouldAutoRefresh(now) == false) return
        val previous = lastAutomaticRefreshAttemptAt
        if (previous != null && now >= previous && now - previous < retryCooldownMillis) return
        lastAutomaticRefreshAttemptAt = now
        refresh()
    }

    private fun CommunityCatalog.shouldAutoRefresh(now: Long): Boolean = when (source) {
        CommunityCatalogSource.SNAPSHOT -> true
        CommunityCatalogSource.CACHE,
        CommunityCatalogSource.ONLINE ->
            fetchedAt == null || fetchedAt > now || now - fetchedAt >= catalogRefreshIntervalMillis
    }

    private fun Throwable.catalogErrorMessage(): String {
        val status = Regex("^目录更新失败：HTTP ([0-9]{3})$")
            .matchEntire(message.orEmpty())
            ?.groupValues
            ?.get(1)
        return when {
            status != null -> ko("目录服务返回 HTTP $status", "카탈로그 서버 응답: HTTP $status")
            this is SocketTimeoutException -> ko("连接目录服务超时", "카탈로그 서버 연결 시간이 초과되었습니다")
            this is UnknownHostException -> ko("无法解析目录服务地址", "카탈로그 서버 주소를 확인할 수 없습니다")
            this is SSLException -> ko("目录服务安全连接失败", "카탈로그 서버 보안 연결에 실패했습니다")
            message == "模型目录超出大小限制" -> ko("目录文件超出大小限制", "카탈로그 파일이 크기 제한을 초과했습니다")
            this is org.json.JSONException || this is IllegalArgumentException -> ko("目录格式无效", "카탈로그 형식이 올바르지 않습니다")
            this is IOException -> ko("网络连接或本地缓存写入失败", "네트워크 연결 또는 로컬 캐시 쓰기에 실패했습니다")
            else -> ko("更新失败，请稍后重试", "업데이트하지 못했습니다. 잠시 후 다시 시도하세요.")
        }
    }
}
