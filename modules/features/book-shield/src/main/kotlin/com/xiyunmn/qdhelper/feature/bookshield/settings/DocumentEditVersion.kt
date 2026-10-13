package com.xiyunmn.qdhelper.feature.bookshield.settings

/** Guarded by the document controller's lock, including the final draft replacement. */
internal class DocumentEditVersion {
    data class Ticket(val document: Long, val edit: Long)
    private var document = 0L
    private var edit = 0L
    fun capture() = Ticket(document, edit)
    fun edited() { edit++ }
    fun invalidate() { document++; edit++ }
    fun belongsToDocument(ticket: Ticket) = ticket.document == document
    fun canReplaceDraft(ticket: Ticket) = belongsToDocument(ticket) && ticket.edit == edit
}
