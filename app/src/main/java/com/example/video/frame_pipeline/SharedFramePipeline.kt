package com.example.video.frame_pipeline

import com.example.core.models.VideoFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Pipeline vidéo partagé entre l'affichage FPV et le moteur d'analyse IA.
 * Utilise STRATEGY_KEEP_ONLY_LATEST (Channel.CONFLATED) pour ne jamais accumuler
 * de trames périmées dans la file d'inférence IA.
 */
class SharedFramePipeline {
    private val _displayFrame = MutableStateFlow<VideoFrame?>(null)
    val displayFrame: StateFlow<VideoFrame?> = _displayFrame.asStateFlow()

    private val aiLatestChannel = Channel<VideoFrame>(capacity = Channel.CONFLATED)
    val aiFrames: Flow<VideoFrame> = aiLatestChannel.receiveAsFlow()

    @Volatile
    var droppedByAiBackpressure: Long = 0L
        private set

    fun publishFrame(frame: VideoFrame) {
        _displayFrame.value = frame
        // CONFLATED remplace toujours l'élément non encore consommé par le plus récent
        aiLatestChannel.trySend(frame)
    }

    fun clear() {
        _displayFrame.value = null
    }
}
