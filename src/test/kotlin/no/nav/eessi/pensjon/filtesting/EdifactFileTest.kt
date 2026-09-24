package no.nav.eessi.pensjon.filtesting

import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.text.Charsets.ISO_8859_1

@Disabled

/**
 * Tester at alle edifactkoder i testfilene har beskrivelse i kodefilene.
 * Dette er en test som kan brukes for å validere at alle koder som brukes i edifactfiler har beskrivelse.
 */
class EdifactFileTest {

    private val tolk = VurderSveFinEdifactDokument()

    @Test
    fun `analyserer anonymiserte edifactfiler`() {
        val filInnhold = lesAnonymisertEdifactFil()
        val filInnhold2 = lesAnonymisertEdifactFil2()
        val dokumenter = tolk.splittTilDokumenter(filInnhold)
        val dokumenter2 = tolk.splittTilDokumenter(filInnhold2)

        val norskIdenter1 = dokumenter.mapNotNull { tolk.vurderEditfactDokument(it)?.norskIdent }.toSet()
        val norskIdenter2 = dokumenter2.mapNotNull { tolk.vurderEditfactDokument(it)?.norskIdent }.toSet()
        val svenskIdenter1 = dokumenter.mapNotNull { tolk.vurderEditfactDokument(it)?.svenskIdent }.toSet()
        val svenskIdenter2 = dokumenter2.mapNotNull { tolk.vurderEditfactDokument(it)?.svenskIdent }.toSet()

        val felles = norskIdenter1.intersect(norskIdenter2)
        val fellesSvensk = svenskIdenter2.intersect(svenskIdenter1)

        println("Antall dokumenter 1: ${dokumenter.size}, unike norskIdent: ${norskIdenter1.size}, ${svenskIdenter1.size}")
        println("Antall dokumenter 2: ${dokumenter2.size}, unike norskIdent: ${norskIdenter2.size}, ${svenskIdenter2.size}")
        println("Antall felles norskIdent i begge dokumenter: ${felles.size}")
        println("Antall felles svenskIdent i begge dokumenter: ${fellesSvensk.size}")
    }

    @Test
    fun `finner alle dokumenter med DTM 901`() {
        val dokumenterMedDoedsdato1 = tolk.finnDokumenterMedDtm901(lesAnonymisertEdifactFil())
        val dokumenterMedDoedsdato2 = tolk.finnDokumenterMedDtm901(lesAnonymisertEdifactFil2())

        assertEquals(63, dokumenterMedDoedsdato1.size)
        assertEquals(0, dokumenterMedDoedsdato2.size)
        assertTrue(dokumenterMedDoedsdato1.all { it.doedsdato != null })
        assertTrue(dokumenterMedDoedsdato2.all { it.doedsdato != null })
        println("Antall dokumenter med DTM+901 i fil 1: ${dokumenterMedDoedsdato1.size}")
        println("Antall dokumenter med DTM+901 i fil 2: ${dokumenterMedDoedsdato2.size}")
    }

    @Test
    fun `alle edifactkoder i testfilene har beskrivelse`() {
        val filer = listOf(
            "app_512_SE.DEFF" to "app_512_SE-koder.properties",
            "app_511_SE.DEFF" to "app_511_SE-koder.properties"
        )

        filer.forEach { (filnavn, kodefilnavn) ->
            val koder = hentEdifactKoder(lesEdifactFil(filnavn)).toSortedSet()
            val kodebeskrivelser = lesKodebeskrivelser(kodefilnavn)
            val koderUtenBeskrivelse = koder.filterNot(kodebeskrivelser::containsKey)

            koder.forEach { kode ->
                println("$filnavn: $kode = ${kodebeskrivelser.getProperty(kode, "MANGLER BESKRIVELSE")}")
            }

            assertTrue(
                koderUtenBeskrivelse.isEmpty(),
                "$filnavn mangler beskrivelse for EDIFACT-koder: $koderUtenBeskrivelse"
            )
        }
    }

    private fun hentEdifactKoder(filInnhold: String): List<String> =
        filInnhold
            .replace("\n", "")
            .replace("\r", "")
            .split("'")
            .mapNotNull(::hentEdifactKode)

    private fun hentEdifactKode(segment: String): String? {
        if (segment.trim().startsWith("UNA")) return "UNA"

        val elementer = segment.trim().split('+')
        val segmentkode = elementer.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return when (segmentkode) {
            "UNT", "UNZ" -> segmentkode
            "UNH" -> elementer.getOrNull(2)
                ?.substringBefore(':')
                ?.let { "$segmentkode+$it" }
            else -> elementer.getOrNull(1)
                ?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }
                ?.let { "$segmentkode+$it" }
        }
    }

    private fun lesKodebeskrivelser(filnavn: String): Properties =
        Properties().apply {
            requireNotNull(EdifactFileTest::class.java.getResourceAsStream("/$filnavn")) {
                "Fant ikke kodefil: $filnavn"
            }.use(::load)
        }

    private fun lesEdifactFil(filnavn: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$filnavn")) {
            "Fant ikke testfil: $filnavn"
        }
            .bufferedReader(ISO_8859_1)
            .use { it.readText() }

    private fun lesAnonymisertEdifactFil(): String = lesEdifactFil("app_512_SE.DEFF")

    private fun lesAnonymisertEdifactFil2(): String = lesEdifactFil("app_511_SE.DEFF")
}