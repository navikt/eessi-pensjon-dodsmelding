package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.gcp.LagringsService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service


@Service
@EnableScheduling
class KjorFil (
    @param:Value("\${GCP_BUCKET_UTL_YTELSE}") var utenlandkYtelseBucket: String,
    private val lagringsService: LagringsService
) {
    private val logger: Logger by lazy { LoggerFactory.getLogger(KjorFil::class.java) }

        @Scheduled(cron = "0 23 10 * * *")
    fun lesFilOgLagreTilS3() {
        logger.info("Starter lesing av fil for å legge fnr til S3 ")
        try {
            lagringsService.filLiggerIS3()
            Thread.sleep(3000)
        } catch (e: Exception) {
            logger.error("Feil ved oppdatering", e)
        }
    }
}