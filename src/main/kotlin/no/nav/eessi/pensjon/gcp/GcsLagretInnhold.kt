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
    private val bucketName: String,
    @Value("\${GCP_H070_OPPRETTET}")
    private val h070BucketName: String
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        logger.info("Kontrollerer tilgang til GCP-bucketene ved oppstart")
        for (bucket in listOf(bucketName, h070BucketName).distinct()) {
            val count = storage.list(bucket).iterateAll().count()
            printBucketSummary(bucket, count.toLong())
        }
    }

    private fun printBucketSummary(bucket: String, antallLagredePersoner: Long) {
        val border = "=".repeat(60)

        println(
            """        $border        GCP bucket summary        - bucket: $bucket        - antall lagrede personer: $antallLagredePersoner        $border        """.trimIndent()
        )
    }
}