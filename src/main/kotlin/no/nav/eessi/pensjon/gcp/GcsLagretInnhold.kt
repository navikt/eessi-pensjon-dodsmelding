package no.nav.eessi.pensjon.gcp

import org.springframework.beans.factory.annotation.Value
import com.google.cloud.storage.Storage
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class GcpLagretInnhold (
    private val storage: Storage,
    @Value("\${GCP_BUCKET_UTL_YTELSE}")
    private val bucketName: String
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        logger.info("Starting gcp-lagret-innhold")
        runCatching {
            storage
                .list(bucketName)
                .iterateAll()
                .count()
        }.onSuccess { count ->
            logger.info(
                "Bucket {} har {} personer",
                bucketName,
                count
            )
        }.onFailure { error ->
            logger.error(
                "Error under henting av info fra $bucketName {}",
                bucketName,
                error
            )
        }
    }
}