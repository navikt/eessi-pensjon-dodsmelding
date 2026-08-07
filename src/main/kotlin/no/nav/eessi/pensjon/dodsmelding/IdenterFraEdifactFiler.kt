package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.gcp.LagringsService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.collections.forEach
import kotlin.collections.orEmpty
import kotlin.text.substringBefore

private const val EDIFACT_FIL_PREFIX = "EdifactFil/"

@Service
@EnableScheduling
class IdenterFraEdifactFiler (
    @param:Value("\${GCP_BUCKET_UTL_YTELSE}") var utenlandkYtelseBucket: String,
    private val vurderSveFinEdifactDokument: VurderSveFinEdifactDokument,
    private val lagringsService: LagringsService
) {
    private val logger: Logger by lazy { LoggerFactory.getLogger(IdenterFraEdifactFiler::class.java) }

        @Scheduled(cron = "0 32 07 * * *")
    fun hentIdenterFraEdifactBatch() {
        logger.info("Starter lesing av fil for å legge fnr til S3 ")
        try {
            hentIdenterFraEdifact()
            Thread.sleep(3000)
        } catch (e: Exception) {
            logger.error("Feil ved oppdatering", e)
        }
    }

    fun hentIdenterFraEdifact() {
        logger.info("sjekker om filen ligger i bucket")

        var totaltLagtTil = 0
        var totaltAlleredeLagret = 0

        lagringsService.hentListeFraS3(EDIFACT_FIL_PREFIX, utenlandkYtelseBucket).forEach { filNavn ->
            // GCS kan inneholde "mappe-navn" - blobber med navn som er identisk med
            // prefixet (f.eks. "EdifactFil/") eller som ender pa "/", uten noe reelt filnavn/
            // innhold etter seg. Disse ma hoppes over, ellers blir de feilaktig behandlet som
            // en fil uten dokumenter og logger en villedende "0 lagt til, 0 allerede lagret"
            // for hver kjoring av batchen.
            if (filNavn == EDIFACT_FIL_PREFIX || filNavn.endsWith("/")) {
                logger.info("Hopper over mappe-plassholder: $filNavn")
                return@forEach
            }

            logger.info("sjekker: $filNavn")

            var lagtTilIFil = 0
            var alleredeLagretIFil = 0

            val dokumenter = lagringsService.hentFraGcp(filNavn)
                .also { logger.debug("Hentet innhold fra blob: $it") }
                ?.let(vurderSveFinEdifactDokument::splittTilDokumenter)
                .orEmpty()

            logger.info("Fant ${dokumenter.size} dokumenter i filen $filNavn")

            dokumenter.forEach { dokument ->
                val edidok = vurderSveFinEdifactDokument
                    .vurderEditfactDokument(dokument)

                val norskIdent = edidok?.norskIdent
                val avsenderLand = edidok?.avsenderLand
                if (norskIdent == null || avsenderLand == null) return@forEach

                // Bruker samme normaliserte land+hash-path her som ved lagring, slik at
                // eksisterer-sjekken og selve lagringen aldri kan komme ut av synk
                // (f.eks. pga. whitespace i avsenderLand fra EDIFACT-parsingen).
                val landMedIdent = lagringsService.landOgIdent(avsenderLand, norskIdent)
                if (landMedIdent.isNullOrBlank()) {
                    logger.warn("************* manglende landkode **************")
                    return@forEach
                }

                val landPrefix = landMedIdent.substringBefore("/")
                if (lagringsService.hentListeFraS3("$landPrefix/", utenlandkYtelseBucket).contains(landMedIdent)) {
                    logger.debug("Denne brukeren finnes fra før av i bucket")
                    alleredeLagretIFil++
                    totaltAlleredeLagret++
                    return@forEach
                }

                lagringsService.lagre(landMedIdent, utenlandkYtelseBucket)
                lagtTilIFil++
                totaltLagtTil++
            }
            logger.info("Oppsummering for $filNavn: $lagtTilIFil lagt til, $alleredeLagretIFil allerede lagret")
        }

        logger.info(
            "Oppsummering totalt: $totaltLagtTil lagt til, $totaltAlleredeLagret allerede lagret"
        )
    }
}