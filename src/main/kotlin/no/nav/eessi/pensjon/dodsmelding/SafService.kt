package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.saf.BrukerIdType
import no.nav.eessi.pensjon.saf.Journalpost
import no.nav.eessi.pensjon.saf.SafClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SafService (
    private val safClient: SafClient,
){
    private val logger: Logger = LoggerFactory.getLogger(SafService::class.java)


    fun brukerRinasakIdFraP6000(valgtPersonident: String): String? =
        hentJournalposter(valgtPersonident)
            .firstNotNullOfOrNull(::bucIdForP6000Journalpost)

    fun brukerRinasakIdFraH070(valgtPersonident: String): String? =
        hentJournalposter(valgtPersonident)
            .firstNotNullOfOrNull(::bucIdForH070Journalpost)

    private fun hentJournalposter(valgtPersonident: String) =
        safClient
            .hentDokumentMetadata(valgtPersonident, BrukerIdType.FNR)
            .data
            .dokumentoversiktBruker
            .journalposter

    private fun bucIdForP6000Journalpost(journalpost: Journalpost): String? {
        val bucId = hentBucId(journalpost) ?: return null

        if (!harDokumentMedTittel(journalpost, "P6000")) {
            return null
        }

        loggTreff(journalpost, bucId)
        return bucId
    }

    private fun bucIdForH070Journalpost(journalpost: Journalpost): String? {
        val bucId = hentBucId(journalpost) ?: return null

        if (!harDokumentMedTittel(journalpost, "H070")) {
            return null
        }

        loggTreff(journalpost, bucId)
        return bucId
    }

    private fun hentBucId(journalpost: Journalpost): String? =
        journalpost.tilleggsopplysninger
            .firstNotNullOfOrNull {
                it.takeIf { opplysning -> opplysning["nokkel"] == "eessi_pensjon_bucid" }
                    ?.get("verdi")
            }


    private fun harDokumentMedTittel(journalpost: Journalpost, tittel: String): Boolean =
        journalpost.dokumenter.orEmpty()
            .any { it.tittel?.contains(tittel) == true }

    private fun loggTreff(journalpost: Journalpost, bucId: String) {
        logger.info(
            "Treff for journalpostId: ${journalpost.journalpostId}, " +
                    "bucId: $bucId, " +
                    "datoOpprettet: ${journalpost.datoOpprettet}, " +
                    "journalfoerendeEnhet: ${journalpost.tilleggsopplysninger}"
        )
    }
}