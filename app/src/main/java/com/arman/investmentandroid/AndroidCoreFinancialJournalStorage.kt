package com.arman.investmentandroid

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Private crash-safe Android storage. Kept out of Auto Backup; never cloud-synced.
 * Do not put entire Windows Core snapshot or live account credentials here.
 */
class AndroidCoreFinancialJournalStorage(context: Context) :
    PendingCoreFinancialJournal.Storage {
    private val backingFile = File(
        context.noBackupFilesDir, "core_financial_pending_v1.json"
    )
    private val atomic = AtomicFile(backingFile)

    override fun read(): String? {
        if (!backingFile.exists() &&
            !File(backingFile.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { it.readBytes() }
        require(bytes.size <= PendingCoreFinancialRequest.MAX_BYTES) {
            "Pending financial request exceeds the permitted size."
        }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= PendingCoreFinancialRequest.MAX_BYTES) {
            "Pending financial request exceeds the permitted size."
        }
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
    }

    override fun clear() = atomic.delete()
}
