@file:Suppress("unused")
package com.vzor.ai.data.remote

/**
 * Typealias для обратной совместимости — StreamChunk перенесён в domain layer.
 * Новый код должен импортировать com.vzor.ai.domain.model.StreamChunk.
 */
typealias StreamChunk = com.vzor.ai.domain.model.StreamChunk
