package com.qidian.QDReader.component.user

class QDUserManager {
    var loggedIn = false
    var id = 0L
    var failReadingId = false

    fun sessionActive(): Boolean = loggedIn
    fun accountId(): Long = if (failReadingId) error("Host account unavailable") else id

    companion object {
        private val instance = QDUserManager()
        @JvmStatic fun getInstance(): QDUserManager = instance
    }
}
