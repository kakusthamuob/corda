package net.corda.tools.error.codes.server.commons.events

import reactor.core.publisher.EmitterProcessor
import reactor.core.publisher.Flux
import java.time.Duration
import javax.annotation.PreDestroy

abstract class PublishingEventSource<EVENT : Event> : EventSource<EVENT>, EventSink<EVENT>, AutoCloseable {

    private companion object {
        private val EVENTS_LOG_TTL = Duration.ofSeconds(5)
    }

    private val processor = EmitterProcessor.create<EVENT>()

    // This is to ensure a subscriber receives events that were published up to 5 seconds before. It helps during initialisation.
    override val events: Flux<EVENT> = processor.cache(EVENTS_LOG_TTL)

    override fun publish(event: EVENT) = processor.onNext(event)

    @PreDestroy
    override fun close() {

        processor.onComplete()
    }
}