package no.nav.eessi.pensjon.dodsmelding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random

class VurderSveFinEdifactDokumentTest {

    private val tolk = VurderSveFinEdifactDokument()

    @Test
    fun `vurderEditfactDokument gir forventede felter for gyldig edifact`() {
        val resultat = tolk.vurderEditfactDokument(edifactDokForSveFin())

        assertEquals("SESFAE5PC", resultat?.avsender)
        assertEquals("NORTVE5LA", resultat?.mottaker)
        assertEquals("512", resultat?.meldingstype)
        assertEquals("445566778833", resultat?.norskIdent)
        assertEquals("SE", resultat?.avsenderLand)
        assertEquals("NO", resultat?.mottakerLand)
        assertEquals("19350951", resultat?.fodselsdato)
        assertTrue(resultat?.erSveFin == true)
    }

    @Test
    fun `Finn fnr i edifact fil og lagre den i s3`() {
        val resultat = tolk.vurderEditfactDokument(edifactDokForSveFin())

        assertEquals("SESFAE5PC", resultat?.avsender)
        assertEquals("NORTVE5LA", resultat?.mottaker)
        assertEquals("512", resultat?.meldingstype)
        assertEquals("445566778833", resultat?.norskIdent)
        assertEquals("SE", resultat?.avsenderLand)
        assertEquals("NO", resultat?.mottakerLand)
        assertEquals("19350951", resultat?.fodselsdato)
        assertTrue(resultat?.erSveFin == true)
    }


    @Test
    fun `splittTilDokumenter behandler anonymisert testfil med 5 dokumenter`() {
        val filInnhold = Files.readString(
            Paths.get("src/test/resources/FIETK.NORTV.TEST5DOCS.DEFF")
        )
        val dokumenter = tolk.splittTilDokumenter(filInnhold)

        assertEquals(5, dokumenter.size, "Skal finne nøyaktig 5 dokumenter")

        val forventet = listOf(
            Triple("01010112345", "FI", "19610101"),
            Triple("02020223456", "FI", "19560202"),
            Triple("03030334567", "FI", "19410303"),
            Triple("04040445678", "FI", "19450404"),
            Triple("05050556789", "FI", "19511105"),
        )

        dokumenter.zip(forventet).forEachIndexed { i, (dok, expected) ->
            val resultat = tolk.vurderEditfactDokument(dok)
            assertEquals(expected.first, resultat?.norskIdent, "Doc ${i + 1}: norskIdent")
            assertEquals(expected.second, resultat?.avsenderLand, "Doc ${i + 1}: avsenderLand")
            assertEquals("NO", resultat?.mottakerLand, "Doc ${i + 1}: mottakerLand")
            assertEquals(expected.third, resultat?.fodselsdato, "Doc ${i + 1}: fodselsdato")
            assertTrue(resultat?.erSveFin == true, "Doc ${i + 1}: erSveFin")
        }
    }

    private fun edifactDokForSveFin(): String {
        return """
            UNA:+.? 'UNB+UNOC:1+SESFAE5PC+NORTVE5LA+153121:2145+1222035112521'UNH+026030001+
            SSREGW:D:94B:UN'BGM+512+1112190988'DTM+137:20251121:102'GIS+1'NAD+FR+RFV++FORSAK
            RINGSKASSAN+++++SE'GIR+903+6455545099:RN'NAD+MR+RTV++RIKSTRYGDEVERKET+++++NO'GIR
            +903+445566778833:RN'PNA+SIP++2+1+1:FOYKE++2:USTABIL'NAT+1+NO'ADR+1::1+1:FREDSGAT
            AN 1+KARLSTAD+61225+NO'DTM+329:19350951:102'PDI+2+3'UNT+14+052000101'UNZ+15235+5
            122225132121'
        """.trimIndent()
    }
}
