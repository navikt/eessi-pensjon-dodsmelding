package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.utils.toJson
import org.springframework.stereotype.Component

@Component
class VurderSveFinEdifactDokument {

    fun vurderEditfactDokument(edifactDokument: String?): EdifactDokument? {
        if (edifactDokument.isNullOrBlank()) return null

        val segments = normaliser(edifactDokument)
            .split("'")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) return null

        val unb = finnSegment(segments, "UNB")
        val bgm = finnSegment(segments, "BGM")
        val frNad = finnNadForRole(segments, "FR")
        val mrNad = finnNadForRole(segments, "MR")
        val dtm329 = finnDtmForQualifier(segments, "329")

        val avsenderLand = hentSisteFelt(frNad)
        val mottakerLand = hentSisteFelt(mrNad)

        val sveFin = setOf("SE", "FI", "SWE", "FIN")

        return EdifactDokument(
            avsender = hentFelt(unb, 2),
            mottaker = hentFelt(unb, 3),
            meldingstype = hentFelt(bgm, 1),
            norskIdent = hentNorskGirIdent(edifactDokument),
            avsenderLand = avsenderLand,
            mottakerLand = mottakerLand,
            fodselsdato = hentDatoFraDtm(dtm329),
            erSveFin = listOf(avsenderLand, mottakerLand).any { it in sveFin }
        ).also { println( "Tolkning av EDIFACT: ${it.toJson()}") }
    }

    fun splittTilDokumenter(filInnhold: String?): List<String> {
        if (filInnhold.isNullOrBlank()) return emptyList()

        val segments = normaliser(filInnhold)
            .split("'")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val dokumenter = mutableListOf<String>()
        var currentDoc = mutableListOf<String>()
        var inDocument = false

        for (segment in segments) {
            when {
                segment.startsWith("UNH+") -> {
                    inDocument = true
                    currentDoc = mutableListOf()
                    currentDoc.add(segment)
                }
                segment.startsWith("UNT+") -> {
                    currentDoc.add(segment)
                    dokumenter.add(currentDoc.joinToString("'"))
                    inDocument = false
                }
                inDocument -> currentDoc.add(segment)
            }
        }

        return dokumenter
    }

    private fun normaliser(edifact: String): String =
        edifact
            .replace('’', '\'')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private fun finnSegment(segments: List<String>, navn: String): String? =
        segments.firstOrNull { it.startsWith("$navn+") }

    private fun finnNadForRole(segments: List<String>, role: String): String? =
        segments.firstOrNull { it.startsWith("NAD+$role+") }

    private val norskGirRegex = Regex("""NO'GIR\s*\+\d+\+(\d+)""")

    private fun hentNorskGirIdent(edifact: String?): String? =
        edifact
            ?.let(::normaliser)
            ?.let { norskGirRegex.find(it)?.groupValues?.get(1) }

    private fun finnDtmForQualifier(segments: List<String>, qualifier: String): String? =
        segments.firstOrNull { it.startsWith("DTM+$qualifier:") }

    private fun hentFelt(segment: String?, index: Int): String? =
        segment?.split('+')?.getOrNull(index)?.takeIf { it.isNotBlank() }

    private fun hentSisteFelt(segment: String?): String? =
        segment
            ?.split('+')
            ?.lastOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun hentDatoFraDtm(segment: String?): String? =
        segment
            ?.split('+')
            ?.getOrNull(1)
            ?.split(':')
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

}
