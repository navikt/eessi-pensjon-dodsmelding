package no.nav.eessi.pensjon.config

import com.google.cloud.storage.StorageException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.springframework.kafka.listener.CommonDelegatingErrorHandler
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import java.io.IOException

class KafkaBatchErrorHandler(
    standardFeilhaandtering: CommonErrorHandler,
    private val stoppendeFeilhaandtering: CommonErrorHandler
) : CommonDelegatingErrorHandler(standardFeilhaandtering) {
    private val metadataTilgangsfeil = Regex(
        "^Unexpected Error code (401|403) trying to get security access token from Compute Engine metadata"
    )

    override fun handleBatch(
        thrownException: Exception,
        data: ConsumerRecords<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        invokeListener: Runnable
    ) {
        val manglerGcpTilgang = generateSequence<Throwable>(thrownException) { it.cause }
            .filterIsInstance<StorageException>()
            .any { it.manglerTilgang() }

        if (manglerGcpTilgang) {
            stoppendeFeilhaandtering.handleBatch(thrownException, data, consumer, container, invokeListener)
        } else {
            super.handleBatch(thrownException, data, consumer, container, invokeListener)
        }
    }

    private fun StorageException.manglerTilgang(): Boolean {
        if (code == 401 || code == 403) return true

        // Metadata-klienten bruker IOException uten HTTP-statusfelt for feil ved tokenhenting.
        return generateSequence(cause) { it.cause }
            .any { it is IOException && it.message?.let(metadataTilgangsfeil::containsMatchIn) == true }
    }
}
