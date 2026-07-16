package dev.bscribe.core.asr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Placeholder engine used until M1b: audio is captured, nothing is decoded. */
class NoopLiveTranscriber : LiveTranscriber {
    override val events: Flow<LiveEvent> = emptyFlow()
    override fun feed(samples: ShortArray, offset: Int, length: Int) = Unit
    override fun start() = Unit
    override fun stop() = Unit
}
