package com.mycard.wallet.security

import android.util.Log
import com.mycard.wallet.data.StaleDataOperationException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.io.EOFException
import java.io.IOException
import java.io.UTFDataFormatException
import javax.crypto.BadPaddingException

enum class RestoreStage { FILE_OPEN, HEADER, AUTH, MANIFEST, VALIDATION, IMAGE, LOCAL_WRITE, DB_COMMIT, STREAM_CLOSE, CLEANUP, UNKNOWN }
enum class RestoreFailure { AUTHENTICATION, FORMAT, CONFLICT, FILE_ACCESS, LOCAL_WRITE, CANCELLED, RESOURCE_CLOSE, UNKNOWN }

/** Contains no exception object/message, URI, card data, password, key, or archive bytes. */
data class RestoreDiagnostic internal constructor(
    val stage: RestoreStage,
    val failure: RestoreFailure,
    val exceptionType: String,
    val committed: Boolean
) {
    init { require(exceptionType.matches(Regex("[A-Za-z0-9_.$]{1,200}"))) }
    val safeSummary: String get() = "stage=${stage.name} failure=${failure.name} exception=$exceptionType committed=$committed"

    fun failureMessage(): String {
        if (committed) return "恢复数据已提交，请勿重复恢复。"
        return when (failure) {
            RestoreFailure.AUTHENTICATION -> "恢复验证失败：密码不匹配或认证数据损坏。本次恢复未提交。"
            RestoreFailure.FORMAT -> "恢复验证失败：备份格式或内容校验失败，文件可能不完整或版本不兼容。本次恢复未提交。"
            RestoreFailure.CONFLICT -> "恢复已停止：卡包已清空或被其他恢复操作更新。本次恢复未提交。"
            RestoreFailure.FILE_ACCESS -> "恢复未完成：无法读取备份，请检查文件访问权限或文件提供器。本次恢复未提交。"
            RestoreFailure.LOCAL_WRITE -> "恢复未完成：本地加密写入或数据库提交失败。本次恢复未提交。"
            RestoreFailure.CANCELLED -> "恢复已取消，本次恢复未提交。"
            RestoreFailure.RESOURCE_CLOSE -> "恢复未完成：文件资源关闭失败。本次恢复未提交。"
            RestoreFailure.UNKNOWN -> "恢复未完成：处理备份时发生异常。本次恢复未提交。"
        }
    }
}

internal object RestoreDiagnostics {
    const val TAG = "MyCardRestore"

    fun record(stage: RestoreStage, exception: Exception, committed: Boolean): RestoreDiagnostic {
        val failure = when {
            exception is CancellationException -> RestoreFailure.CANCELLED
            exception is StaleDataOperationException -> RestoreFailure.CONFLICT
            stage in setOf(RestoreStage.LOCAL_WRITE, RestoreStage.DB_COMMIT, RestoreStage.CLEANUP) -> RestoreFailure.LOCAL_WRITE
            stage == RestoreStage.STREAM_CLOSE -> RestoreFailure.RESOURCE_CLOSE
            stage == RestoreStage.AUTH && exception is BadPaddingException -> RestoreFailure.AUTHENTICATION
            stage == RestoreStage.FILE_OPEN -> RestoreFailure.FILE_ACCESS
            exception is IllegalArgumentException || exception is JSONException ||
                exception is EOFException || exception is UTFDataFormatException -> RestoreFailure.FORMAT
            exception is IOException || exception is SecurityException -> RestoreFailure.FILE_ACCESS
            else -> RestoreFailure.UNKNOWN
        }
        val type = exception.javaClass.name.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,200}")) } ?: "Exception"
        return RestoreDiagnostic(stage, failure, type, committed).also {
            // Never pass a Throwable: its message, causes and suppressed exceptions may hold secrets.
            Log.w(TAG, it.safeSummary)
        }
    }
}
