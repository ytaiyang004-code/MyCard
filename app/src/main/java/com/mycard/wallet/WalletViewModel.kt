package com.mycard.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mycard.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.mycard.wallet.images.ImageProcessor
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.RestoreWarning
import com.mycard.wallet.security.RestoreDiagnostic
import com.mycard.wallet.security.RestoreDiagnostics
import com.mycard.wallet.security.RestoreStage
import com.mycard.wallet.security.RestoreFailure
import android.net.Uri

data class Notice(val message: String, val undo: DeleteUndoToken? = null)
enum class BackupAction { EXPORT, RESTORE }
enum class BackupStep { IDLE, SELECTING, PASSWORD, RUNNING, SUCCEEDED, WARNING, VALIDATION_FAILED, FAILED, CANCELLED }
/** In-memory UI state only. Never contains passwords, file contents or provider exceptions. */
data class BackupOperationState(val id: String? = null, val action: BackupAction? = null,
                                val step: BackupStep = BackupStep.IDLE, val message: String? = null,
                                val diagnostic: RestoreDiagnostic? = null)

class WalletViewModel(application: Application, val repository: CardRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, CardRepository(application))
    private val backupManager = BackupManager(application.applicationContext, repository)
    private val _backupOperation = MutableStateFlow(BackupOperationState())
    val backupOperation = _backupOperation.asStateFlow()
    private var backupUri: Uri? = null

    @androidx.annotation.MainThread
    fun requestBackupDocument(action: BackupAction): String? {
        if (_backupOperation.value.step != BackupStep.IDLE) return null
        val id = java.util.UUID.randomUUID().toString()
        backupUri = null
        _backupOperation.value = BackupOperationState(id, action, BackupStep.SELECTING)
        return id
    }

    @androidx.annotation.MainThread
    fun backupDocumentSelected(id: String, uri: Uri?) {
        val state = _backupOperation.value
        if (state.id != id || state.step != BackupStep.SELECTING) return
        backupUri = uri
        _backupOperation.value = if (uri == null) BackupOperationState() else state.copy(step = BackupStep.PASSWORD)
    }

    @androidx.annotation.MainThread
    fun backupDocumentFailed(id: String) {
        val state = _backupOperation.value
        if (state.id != id || state.step != BackupStep.SELECTING) return
        backupUri = null
        _backupOperation.value = state.copy(step = BackupStep.FAILED, message = "无法打开系统文件选择器，请检查系统文件应用。")
    }

    @androidx.annotation.MainThread
    fun dismissBackup(id: String) {
        val state = _backupOperation.value
        if (state.id != id || state.step in listOf(BackupStep.SELECTING, BackupStep.RUNNING)) return
        backupUri = null
        _backupOperation.value = BackupOperationState()
    }

    /** Takes ownership of [password], clearing it even if the launch is cancelled before starting. */
    @androidx.annotation.MainThread
    fun startBackup(id: String, password: CharArray): Boolean {
        val state = _backupOperation.value
        val uri = backupUri
        if (state.id != id || state.step != BackupStep.PASSWORD || uri == null || password.size !in 8..128) {
            password.fill('\u0000')
            return false
        }
        backupUri = null
        _backupOperation.value = state.copy(step = BackupStep.RUNNING)
        viewModelScope.launch {
            var diagnostic: RestoreDiagnostic? = null
            try {
                if (state.action == BackupAction.RESTORE) {
                    val result = backupManager.restoreWithResult(uri, password) { diagnostic = it }
                    retryRead()
                    val message = when (result.warning) {
                        RestoreWarning.STREAM_CLOSE_FAILED -> "已恢复 ${result.count} 张卡片。数据已保存，但备份文件流关闭失败，请勿重复恢复。"
                        RestoreWarning.INTERRUPTED_AFTER_COMMIT -> "已恢复 ${result.count} 张卡片。数据已保存，操作结束阶段被中断，请勿重复恢复。"
                        null -> "已恢复 ${result.count} 张卡片。"
                    }
                    finishBackup(id, if (result.warning == null) BackupStep.SUCCEEDED else BackupStep.WARNING, message, result.diagnostic)
                } else {
                    backupManager.export(uri, password)
                    finishBackup(id, BackupStep.SUCCEEDED, "加密备份已保存。")
                }
            } catch (e: CancellationException) {
                finishBackup(id, BackupStep.CANCELLED, if (state.action == BackupAction.RESTORE)
                    "恢复已取消，本次恢复未提交。" else "备份已取消；目标位置可能留有不完整文件，请勿用于恢复。", diagnostic)
                throw e
            } catch (e: IncompleteWalletException) {
                finishBackup(id, BackupStep.FAILED,
                    "完整备份未创建：存在 ${e.damagedCount} 条无法解密的记录。原始数据未改动，目标文件未被写入。")
            } catch (e: Exception) {
                if (state.action == BackupAction.RESTORE) {
                    val failure = diagnostic ?: RestoreDiagnostics.record(RestoreStage.UNKNOWN, e, false)
                    val step = when (failure.failure) {
                        RestoreFailure.AUTHENTICATION, RestoreFailure.FORMAT -> BackupStep.VALIDATION_FAILED
                        RestoreFailure.CONFLICT, RestoreFailure.CANCELLED -> BackupStep.CANCELLED
                        else -> BackupStep.FAILED
                    }
                    finishBackup(id, step, failure.failureMessage(), failure)
                } else finishBackup(id, BackupStep.FAILED, "备份失败，请检查目标存储空间。若已生成不完整文件，可在文件管理器中删除。")
            } finally { password.fill('\u0000') }
        }.invokeOnCompletion {
            password.fill('\u0000')
            finishBackup(id, BackupStep.CANCELLED, "操作已取消。")
        }
        return true
    }

    private fun finishBackup(id: String, step: BackupStep, message: String, diagnostic: RestoreDiagnostic? = null) {
        _backupOperation.update { state ->
            if (state.id == id && state.step == BackupStep.RUNNING) state.copy(step = step,
                message = message + (diagnostic?.let { "\n诊断：${it.safeSummary}" } ?: ""), diagnostic = diagnostic) else state
        }
    }

    val unlocked = androidx.compose.runtime.mutableStateOf(false)
    var authenticating = false
    var pendingLockChange: Boolean? = null
    fun onHostStopped(changingConfigurations: Boolean) {
        // Leaving the protected UI drops the decrypted artwork. This is a privacy measure, not an
        // erasure guarantee: copies may still exist in heap pages or the decoder for some time.
        if (!changingConfigurations) { unlocked.value = false; CardImageCache.clear() }
    }
    val draft = MutableStateFlow<WalletCard?>(null)
    val draftImage = androidx.compose.runtime.mutableStateOf<ByteArray?>(null)
    val importBitmap = androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    // Keep crop and redaction state alongside the in-memory source across rotation / app-lock UI disposal.
    val importTransform = androidx.compose.runtime.mutableStateOf(com.mycard.wallet.images.CropTransform())
    val importMasks = androidx.compose.runtime.mutableStateOf<List<com.mycard.wallet.images.Mask>>(emptyList())
    val importMaskMode = androidx.compose.runtime.mutableStateOf(false)
    val importWorking = androidx.compose.runtime.mutableStateOf(false)
    val importError = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var importJob: Job? = null
    private var importGeneration = 0L
    private var draftGeneration = 0L
    private var draftDataGeneration = repository.dataGeneration
    val draftSession: Long get() = draftGeneration
    var originalDraft: WalletCard? = null
    var editingNew = true
    fun beginEdit(card: WalletCard?) {
        discardDraft()
        editingNew = card == null
        val value = card ?: WalletCard(position = (cards.value.maxOfOrNull { it.position } ?: -1) + 1)
        originalDraft = value
        draftDataGeneration = repository.dataGeneration
        draft.value = value
    }
    fun releaseImport() {
        importGeneration++
        importJob?.cancel()
        importJob = null
        importWorking.value = false
        importError.value = null
        importBitmap.value = null
        importTransform.value = com.mycard.wallet.images.CropTransform()
        importMasks.value = emptyList()
        importMaskMode.value = false
    }
    fun discardDraft() {
        draftGeneration++
        draft.value = null
        draftImage.value?.fill(0)
        draftImage.value = null
        releaseImport()
    }
    fun loadImage(uri: android.net.Uri) {
        if (busy.value || draft.value == null || importWorking.value) return
        startImageWork { generation ->
            val decoded = ImageProcessor.decode(getApplication<Application>().contentResolver, uri)
            if (generation == importGeneration) importBitmap.value = decoded
        }
    }
    fun trimImage() {
        val bitmap = importBitmap.value ?: return
        startImageWork { generation ->
            val trimmed = ImageProcessor.trimWhitespace(bitmap)
            if (generation == importGeneration) {
                importBitmap.value = trimmed
                importTransform.value = com.mycard.wallet.images.CropTransform()
            }
        }
    }
    fun acceptImage() {
        val bitmap = importBitmap.value ?: return
        val transform = importTransform.value
        val masks = importMasks.value.toList()
        startImageWork { generation ->
            val bytes = ImageProcessor.render(bitmap, transform, masks)
            if (generation == importGeneration && draft.value != null) {
                draftImage.value?.fill(0)
                draftImage.value = bytes
                importBitmap.value = null
                importTransform.value = com.mycard.wallet.images.CropTransform()
                importMasks.value = emptyList()
                importMaskMode.value = false
            } else bytes.fill(0)
        }
    }
    private fun startImageWork(block: suspend (Long) -> Unit) {
        if (importWorking.value) return
        val generation = importGeneration
        importWorking.value = true
        importError.value = null
        importJob = runTask {
            try { block(generation) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == importGeneration) {
                    importError.value = "图片处理未完成，请尝试其他图片或手动裁剪。"
                    if (importBitmap.value == null) notices.send(Notice(importError.value!!))
                }
            }
            finally { if (generation == importGeneration) importWorking.value = false }
        }
    }
    fun saveDraft(done: () -> Unit) {
        if (busy.value || importWorking.value) return
        val card = draft.value ?: return
        val generation = draftGeneration
        val dataGeneration = draftDataGeneration
        val original = if (editingNew) null else originalDraft
        // A back gesture may discard the editor while IO completes. Own the bytes for this save.
        val image = draftImage.value?.copyOf()
        busy.value = true
        runTask {
            try {
                repository.saveWithImage(card, image, original, dataGeneration)
                if (generation == draftGeneration && dataGeneration == repository.dataGeneration) {
                    discardDraft()
                    done()
                }
            } finally { image?.fill(0); busy.value = false }
        }.invokeOnCompletion { image?.fill(0); busy.value = false }
    }
    fun palette(value: AppPalette) { runTask { preferences.palette(value) } }
    fun layout(value: CardLayout) { runTask { preferences.layout(value) } }
    fun sort(value: SortOrder) { runTask { preferences.text("sort", value.name) } }
    val preferences = Preferences(application)
    private val _cards = MutableStateFlow<List<WalletCard>>(emptyList())
    val cards = _cards.asStateFlow()
    val cardsLoaded = MutableStateFlow(false)
    /** Unreadable records in the last successful read; 0 means the wallet was complete. */
    val damagedCount = MutableStateFlow(0)
    private val _settings = MutableStateFlow<WalletSettings?>(null)
    val settings = _settings.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val notices = Channel<Notice>(Channel.BUFFERED)
    private val reload = Channel<Unit>(Channel.CONFLATED)
    fun retryRead() { reload.trySend(Unit) }
    init {
        viewModelScope.launch {
          while (true) {
            try {
                repository.collectOrphans()
                repository.cards.collect { result ->
                    _cards.value = result.cards
                    damagedCount.value = result.damaged.size
                    cardsLoaded.value = true
                    error.value = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = when (e) {
                    is WalletUnavailableException -> when (e.reason) {
                        WalletUnavailableException.Reason.KEY_UNAVAILABLE ->
                            "无法访问本机加密密钥，卡包暂时无法打开。原始数据未被删除，请勿清除应用数据。"
                        WalletUnavailableException.Reason.ALL_RECORDS_UNREADABLE ->
                            "卡包无法解密：可能是密钥变更或数据整体损坏。原始密文已保留，请从本地备份恢复。"
                    }
                    else -> "无法读取加密卡包。请保留应用数据并尝试重新启动，或从本地备份恢复。"
                }
                reload.receive()
            }
          }
        }
        viewModelScope.launch {
            try { preferences.flow.collect { _settings.value = it } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "无法读取设置，请重新启动应用。" }
        }
    }
    fun runTask(block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                notices.send(Notice(when (e) {
                    is StaleDataOperationException -> "卡包已发生变化，请重新打开卡片后重试。"
                    is DamagedRecordException -> "该记录无法解密；原始密文已保留，因此不能修改或删除。"
                    is IncompleteWalletException -> "存在 ${e.damagedCount} 条无法解密的记录，操作已取消。"
                    else -> "操作未完成，请检查文件或存储空间后重试。"
                }))
            }
        }
    fun save(card: WalletCard, done: () -> Unit) {
        if (busy.value) return
        busy.value = true
        val generation = repository.dataGeneration
        runTask {
            try { repository.update(card, generation); if (generation == repository.dataGeneration) done() }
            finally { busy.value = false }
        }.invokeOnCompletion { busy.value = false }
    }
    fun update(card: WalletCard): Job {
        val generation = repository.dataGeneration
        return runTask { repository.update(card, generation) }
    }
    fun delete(card: WalletCard, done: () -> Unit): Job {
        val generation = repository.dataGeneration
        return runTask {
            repository.deleteForUndo(card, generation)?.let { token ->
                if (generation == repository.dataGeneration) done()
                notices.send(Notice("卡片已删除", token))
            }
        }
    }
    fun undo(token: DeleteUndoToken) = runTask { repository.undo(token) }
    fun finishDelete(token: DeleteUndoToken) = runTask { repository.finishDelete(token) }
    private var clearJob: Job? = null
    fun clearWallet(): Job {
        clearJob?.takeIf { it.isActive }?.let { return it }
        val generation = repository.dataGeneration
        return runTask {
            repository.clear(generation)
            if (draftDataGeneration != repository.dataGeneration) discardDraft()
            retryRead(); notices.send(Notice("全部卡片已清空"))
        }.also { clearJob = it }
    }
    fun reorder(ids: List<String>, done: () -> Unit = {}): Job {
        val generation = repository.dataGeneration
        return runTask {
            repository.reorder(ids, generation)
            preferences.text("sort", SortOrder.CUSTOM.name)
            if (generation == repository.dataGeneration) done()
        }
    }
    fun boolean(key: String, value: Boolean) = runTask { preferences.boolean(key, value) }
    fun text(key: String, value: String) = runTask { preferences.text(key, value) }
}
