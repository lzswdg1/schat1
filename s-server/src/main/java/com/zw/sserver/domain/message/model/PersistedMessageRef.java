package com.zw.sserver.domain.message.model;

/**
 * Stable identity returned for an already committed client message.
 */
public record PersistedMessageRef(Long messageId, Long seq) {
}
