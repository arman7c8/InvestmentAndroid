package com.arman.investmentandroid

/** One durable, immutable OFFLINE financial request; no financial commands run here. */
class PendingCoreFinancialJournal(private val storage: Storage) {
    interface Storage {
        fun read(): String?
        fun write(value: String)
        fun clear()
    }

    /** Corrupt or unsupported records require explicit human discard. */
    fun load(): String? {
        val raw = storage.read() ?: return null
        return PendingCoreFinancialRequest.restore(raw)
            ?: throw IllegalStateException(
                "Stored financial request is invalid. Do not generate a new operation ID."
            )
    }

    /** Write/read-back before opening the system document picker. */
    fun save(raw: String): String {
        val valid = PendingCoreFinancialRequest.restore(raw)
            ?: throw IllegalArgumentException("Financial request is not a supported immutable proposal.")
        val current = load()
        require(current == null || current == valid) {
            "Different financial operation is already pending; explicitly discard it first."
        }
        storage.write(valid)
        check(storage.read() == valid) { "Financial request journal write-back verification failed." }
        return valid
    }

    fun discardByUser() {
        storage.clear()
        check(storage.read() == null) { "Could not discard the pending financial request." }
    }

    // Export-to-file is not a server receipt; only explicit user discard can
    // remove the draft until authenticated Windows receipt handling exists.
}
