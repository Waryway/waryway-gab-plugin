package com.waryway.gab.chat

import com.waryway.gab.model.ContextAttachment
import com.waryway.gab.model.Conversation

/**
 * In-memory conversation store for the current IDE session.
 * Supports multiple chats with tab + dropdown navigation.
 */
class ConversationManager {

    private val conversations = mutableListOf<Conversation>()
    private var activeId: String? = null

    init {
        createNew()
    }

    fun createNew(): Conversation {
        val conv = Conversation()
        conversations.add(0, conv)
        activeId = conv.id
        return conv
    }

    fun getActive(): Conversation {
        val id = activeId
        if (id != null) {
            conversations.find { it.id == id }?.let { return it }
        }
        return conversations.firstOrNull() ?: createNew()
    }

    fun switchTo(id: String): Conversation? {
        val conv = conversations.find { it.id == id } ?: return null
        activeId = id
        conv.touch()
        return conv
    }

    fun getAll(): List<Conversation> = conversations.sortedByDescending { it.lastActiveAt }

    /** Two most recently active conversations for browser-style tabs. */
    fun getRecentTabs(): List<Conversation> = getAll().take(2)

    fun getAttachments(): MutableList<ContextAttachment> = getActive().attachments

    fun addAttachment(attachment: ContextAttachment) {
        val active = getActive()
        if (active.attachments.none { it.path == attachment.path && it.type == attachment.type }) {
            active.attachments.add(attachment)
            active.touch()
        }
    }

    fun removeAttachment(attachment: ContextAttachment) {
        getActive().attachments.remove(attachment)
    }

    fun clearAttachments() {
        getActive().attachments.clear()
    }
}