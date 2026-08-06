package no.nav.eessi.pensjon.gcp

import com.google.api.gax.paging.Page
import com.google.cloud.WriteChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.nav.eessi.pensjon.eux.model.SedType
import no.nav.eessi.pensjon.eux.model.sed.H070
import no.nav.eessi.pensjon.eux.model.sed.HBruker
import no.nav.eessi.pensjon.eux.model.sed.HNav
import no.nav.eessi.pensjon.eux.model.sed.Person
import no.nav.eessi.pensjon.eux.model.sed.PinItem
import no.nav.eessi.pensjon.dodsmelding.EdifactDokument
import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class LagringsServiceTest {

    private val gcpStorage =  mockk<Storage>(relaxed = true)
    private val vurderSveFinEdifactDokument =  mockk<VurderSveFinEdifactDokument>(relaxed = true)
    private lateinit var lagringsService: LagringsService

    @BeforeEach
    fun setup() {
        lagringsService = LagringsService("dod", "h070_opprettetBucket", vurderSveFinEdifactDokument, gcpStorage, "eessipensjonn")
    }

    @Test
    fun test() {
        val fnr = "12345678901"
        mockGcpListeSok(fnr)
        every { gcpStorage.writer(any(), any(), any(), any(), any(), any()) } returns mockk<WriteChannel>(relaxed = true)

//        lagringsService.lagreFnrIS3(fnr, "FI")

//        verify { gcpStorage.writer(BlobInfo.newBuilder(BlobId.of("dod", "FI/254aa248acb47dd654ca3ea53f48c2c26d641d23d7e2e93a1ec56258df7674c4")).setContentType("application/json").build()) }
    }

    private fun mockGcpListeSok(fnr: String) {
        val blob = mockk<Blob>(relaxed = true)
        every { blob.name } returns lagringsService.hashedValue(fnr)

        val page = mockk<Page<Blob>>(relaxed = true)
        every { page.iterateAll() } returns listOf(blob)
        every { gcpStorage.list(any<String>(), *anyVararg()) } returns page

    }

    @Disabled
    @Test
    fun `Fnr fra fil lagre til s3 i riktig mappe`() {
        val fnr = "12345678901"
        mockGcpListeSok(fnr)
        every { gcpStorage.writer(any(), any(), any(), any(), any(), any()) } returns mockk<WriteChannel>(relaxed = true)

//        val result = lagringsService.lagreFnrIS3(fnr, "FI")

        verify (exactly = 1) { gcpStorage.writer(BlobInfo.newBuilder(BlobId.of("dod", "FI/12345678901")).setContentType("application/json").build()) }
    }

    @Test
    fun `hashedValue skal returnere samme hash for samme input`() {
        val fnr = "12345678901"
        val hash1 = lagringsService.hashedValue(fnr)
        val hash2 = lagringsService.hashedValue(fnr)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `hashedValue skal returnere forskjellig hash for samme input`() {
        val fnr1 = "12345678901"
        val fnr2 = "98765432109"
        val fnr3 = "01495517019"

        val hash1 = lagringsService.hashedValue(fnr1)
        val hash2 = lagringsService.hashedValue(fnr2)
        val hash3 = lagringsService.hashedValue(fnr3)

        assertNotEquals(hash1, hash2, hash3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["FI", "SE", "PL"])
    fun `hentBrukerILand returnerer korrekt path for gyldig landkode`(landkode: String) {
        val result = lagringsService.landOgIdent(landkode, "12345678901")

        assertNotNull(result)
        assertTrue(result!!.startsWith("$landkode/"))
    }

    @ParameterizedTest
    @CsvSource(
        "' FI', FI",
        "'FI ', FI",
        "'  SE  ', SE",
        "'F I', FI",
        "'P  L', PL",
        "'  S  E  ', SE"
    )
    fun `hentBrukerILand handterer landkode med whitespace`(input: String, expectedPrefix: String) {
        val result = lagringsService.landOgIdent(input, "12345678901")

        assertNotNull(result)
        assertTrue(result!!.startsWith("$expectedPrefix/"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["NO", ""])
    fun `hentBrukerILand returnerer null for ugyldig eller tom landkode`(landkode: String) {
        val result = lagringsService.landOgIdent(landkode, "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand returnerer null for null landkode`() {
        val result = lagringsService.landOgIdent(null, "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand returnerer null for whitespace only`() {
        val result = lagringsService.landOgIdent("   ", "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand genererer hashet fnr i path`() {
        val fnr = "12345678901"
        val result = lagringsService.landOgIdent("FI", fnr)

        assertNotNull(result)
        val pathParts = result!!.split("/")
        assertEquals(2, pathParts.size)
        assertEquals("FI", pathParts[0])
        assertTrue(pathParts[1].length > 0)
        assertNotEquals(fnr, pathParts[1])
    }

    @Test
    fun `hentBrukerILand samme fnr genererer samme hash`() {
        val fnr = "12345678901"
        val result1 = lagringsService.landOgIdent("FI", fnr)
        val result2 = lagringsService.landOgIdent("FI", fnr)

        assertEquals(result1, result2)
    }

    @Test
    fun `hentBrukerILand ulike fnr genererer ulike hash`() {
        val result1 = lagringsService.landOgIdent("FI", "12345678901")
        val result2 = lagringsService.landOgIdent("FI", "98765432101")

        assertNotEquals(result1, result2)
    }

    @Test
    fun `lagreH070 skal obfuskere pin og lagre json i H070_LAGRET path`() {
        val writeChannel = mockk<WriteChannel>(relaxed = true)
        val blobInfoSlot = slot<BlobInfo>()
        val writtenPayload = StringBuilder()

        every { gcpStorage.writer(capture(blobInfoSlot)) } returns writeChannel
        every { writeChannel.write(any()) } answers {
            val buffer = firstArg<ByteBuffer>().duplicate()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            writtenPayload.append(String(bytes, Charsets.UTF_8))
            bytes.size
        }

        val norskPin = "12345678901"
        val utenlandskPin = "SE1234567890"
        val h070 = H070(
            type = SedType.H070,
            hnav = HNav(
                bruker = HBruker(
                    person = Person(
                        pin = listOf(
                            PinItem(identifikator = norskPin, land = "NOR"),
                            PinItem(identifikator = utenlandskPin, land = "SWE")
                        )
                    )
                )
            )
        )

        lagringsService.lagreH070(h070, "H070_LAGRET_PREFIX_STANDARD")

        assertEquals("h070_opprettetBucket", blobInfoSlot.captured.blobId.bucket)
        assertTrue(blobInfoSlot.captured.blobId.name.startsWith("H070_LAGRET"))
        assertTrue(blobInfoSlot.captured.blobId.name.endsWith(".json"))
        val root = ObjectMapper().readTree(writtenPayload.toString())
        val lagretIdentifikatorer = root.findParents("person")
            .mapNotNull { it.get("person")?.get("pin") }
            .flatMap { pinArray -> pinArray.map { it.path("identifikator").asText() } }

        assertTrue(lagretIdentifikatorer.contains(lagringsService.hashedValue(norskPin)))
        assertTrue(lagretIdentifikatorer.contains(lagringsService.hashedValue(utenlandskPin)))
        assertTrue(lagretIdentifikatorer.none { it == norskPin || it == utenlandskPin })
    }

    @Test
    fun `finnesDoedsmeldingAlleredeForBruker finner treff selv om det ikke er forste element i lista`() {
        // Regresjonstest: metoden brukte "forEach { ... return ... }", som terminerer
        // (non-local return) etter aa ha vurdert KUN det forste elementet i lista.
        // Dersom bucket-listingen noensinne returnerer flere elementer og treffet ikke
        // ligger forst, vil brukeren feilaktig bli behandlet som "ikke lagret fra for"
        // - med fare for at H070 opprettes paa nytt for en bruker som allerede har fatt en.
        val fnr = "12345678901"
        val hasha = lagringsService.hashedValue(fnr)

        val page = mockk<Page<Blob>>(relaxed = true)
        every { page.iterateAll() } returns listOf(
            mockk<Blob>(relaxed = true).also { every { it.name } returns "HashedUsers/uannet-element" },
            mockk<Blob>(relaxed = true).also { every { it.name } returns "HashedUsers/$hasha" }
        )
        every { gcpStorage.list(any<String>(), *anyVararg()) } returns page

        val resultat = lagringsService.finnesDoedsmeldingAlleredeForBruker(fnr)

        assertTrue(resultat, "Skal finne treff selv om det korrekte elementet ikke ligger forst i lista")
    }

    @Test
    fun `finnesDoedsmeldingAlleredeForBruker finner treff selv om det ligger paa senere side i listingen`() {
        // Regresjonstest: Page#values gir kun forste side av en paginert GCS-listing.
        // Dersom bucketen inneholder flere blobber enn en enkelt side, ma alle sidene
        // gjennomgas (iterateAll) - ellers vil identer paa senere sider feilaktig bli
        // behandlet som "ikke lagret fra for" i hver kjoring av batchen.
        val fnr = "12345678901"
        val hasha = lagringsService.hashedValue(fnr)

        val page = mockk<Page<Blob>>(relaxed = true)
        every { page.values } returns listOf(
            mockk<Blob>(relaxed = true).also { every { it.name } returns "HashedUsers/forste-side-element" }
        )
        every { page.iterateAll() } returns listOf(
            mockk<Blob>(relaxed = true).also { every { it.name } returns "HashedUsers/forste-side-element" },
            mockk<Blob>(relaxed = true).also { every { it.name } returns "HashedUsers/$hasha" }
        )
        every { gcpStorage.list(any<String>(), *anyVararg()) } returns page

        val resultat = lagringsService.finnesDoedsmeldingAlleredeForBruker(fnr)

        assertTrue(resultat, "Skal finne treff selv om elementet ligger paa en senere side enn den forste")
    }

    @Test
    fun `filLiggerIS3 hopper over mappe-plassholder og behandler den faktiske fila`() {
        // Regresjonstest for produksjonsfeil: GCS-bucketen kan inneholde en "mappe-plassholder"
        // - en blob med navn identisk med prefixet ("EdifactFil/"), uten reelt innhold. Denne
        // ble tidligere behandlet som en fil, og logget en villedende
        // "Oppsummering for EdifactFil/: 0 lagt til, 0 allerede lagret" for hver kjoring,
        // mens den faktiske EDIFACT-fila (med reelt innhold) skal fortsatt behandles normalt.
        val fnr = "12345678901"
        val avsenderLand = "FI"

        val inputBlob = mockk<Blob>(relaxed = true)
        every { inputBlob.exists() } returns true
        every { inputBlob.getContent() } returns "UNH+1+H070'BGM+1'NO'GIR+1+$fnr'NAD+FR++++++$avsenderLand'UNT+4+1'".toByteArray()

        val mappePlassholder = mockk<Blob>(relaxed = true).also { every { it.name } returns "EdifactFil/" }
        val ekteFil = mockk<Blob>(relaxed = true).also { every { it.name } returns "EdifactFil/FIETK.NORTV.E512E512.P03122.DEFF" }

        val filPage = mockk<Page<Blob>>(relaxed = true)
        every { filPage.iterateAll() } returns listOf(mappePlassholder, ekteFil)

        val landPage = mockk<Page<Blob>>(relaxed = true)
        every { landPage.iterateAll() } returns emptyList()

        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("EdifactFil/")) } returns filPage
        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("FI/")) } returns landPage
        every { gcpStorage.get(any<BlobId>()) } returns inputBlob
        every { vurderSveFinEdifactDokument.splittTilDokumenter(any()) } returns listOf("doc")
        every { vurderSveFinEdifactDokument.vurderEditfactDokument(any()) } returns EdifactDokument(
            avsender = null,
            mottaker = null,
            meldingstype = null,
            norskIdent = fnr,
            avsenderLand = avsenderLand,
            mottakerLand = null,
            fodselsdato = null,
            erSveFin = false
        )

        lagringsService.hentIdenterFraEdifact()

        // Mappe-plassholderen skal aldri hentes/behandles - kun den faktiske fila.
        verify(exactly = 0) { gcpStorage.get(BlobId.of("dod", "EdifactFil/")) }
        verify(exactly = 1) { gcpStorage.get(BlobId.of("dod", "EdifactFil/FIETK.NORTV.E512E512.P03122.DEFF")) }
        verify(exactly = 1) { gcpStorage.writer(any<BlobInfo>()) }
    }

    @Test
    fun `filLiggerIS3 lagrer ikke identifikator som allerede finnes i bucket`() {
        val fnr = "12345678901"
        val avsenderLand = "FI"
        val hash = lagringsService.hashedValue(fnr)

        val inputBlob = mockk<Blob>(relaxed = true)
        every { inputBlob.exists() } returns true
        every { inputBlob.getContent() } returns "UNH+1+H070'BGM+1'NO'GIR+1+$fnr'NAD+FR++++++$avsenderLand'UNT+4+1'".toByteArray()

        val existingBlob = mockk<Blob>(relaxed = true)
        every { existingBlob.name } returns "$avsenderLand/$hash"

        val filPage = mockk<Page<Blob>>(relaxed = true)
        every { filPage.iterateAll() } returns listOf(mockk<Blob>(relaxed = true).also { every { it.name } returns "EdifactFil/test.txt" })

        val landPage = mockk<Page<Blob>>(relaxed = true)
        every { landPage.iterateAll() } returns listOf(existingBlob)

        // Stubber pa faktisk prefix-argument (ikke rekkefolge/antall kall), slik at oppsettet
        // gir riktig svar uansett hvor mange ganger hentIdenterFraEdifact() kjores i testen.
        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("EdifactFil/")) } returns filPage
        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("FI/")) } returns landPage
        every { gcpStorage.get(any<BlobId>()) } returns inputBlob
        every { vurderSveFinEdifactDokument.splittTilDokumenter(any()) } returns listOf("doc")
        every { vurderSveFinEdifactDokument.vurderEditfactDokument(any()) } returns EdifactDokument(
            avsender = null,
            mottaker = null,
            meldingstype = null,
            norskIdent = fnr,
            avsenderLand = avsenderLand,
            mottakerLand = null,
            fodselsdato = null,
            erSveFin = false
        )

        // Kjorer to ganger for aa simulere flere kjoringer av batchen (f.eks. daglig cron):
        // andre kjoring skal fortsatt finne den faktiske EDIFACT-fila og korrekt telle den som
        // allerede lagret, ikke behandle land-prefixet som om det var filnavnet.
        lagringsService.hentIdenterFraEdifact()
        lagringsService.hentIdenterFraEdifact()

        verify(exactly = 0) { gcpStorage.writer(any<BlobInfo>()) }
    }

    @Test
    fun `filLiggerIS3 lagrer ikke identifikator paa nytt naar avsenderLand har whitespace`() {
        // Regresjonstest: avsenderLand hentet fra EDIFACT-parsing kan ha whitespace (f.eks. "FI "),
        // mens allerede lagrede identer alltid ligger under den normaliserte, trimmede landkoden.
        // Eksisterer-sjekken må derfor normalisere avsenderLand på samme måte som ved lagring,
        // ellers vil samme bruker bli forsøkt lagret på nytt i hver kjøring av batchen.
        val fnr = "12345678901"
        val avsenderLandMedWhitespace = "FI "
        val hash = lagringsService.hashedValue(fnr)

        val inputBlob = mockk<Blob>(relaxed = true)
        every { inputBlob.exists() } returns true
        every { inputBlob.getContent() } returns "UNH+1+H070'BGM+1'NO'GIR+1+$fnr'NAD+FR++++++FI'UNT+4+1'".toByteArray()

        val existingBlob = mockk<Blob>(relaxed = true)
        every { existingBlob.name } returns "FI/$hash"

        val filPage = mockk<Page<Blob>>(relaxed = true)
        every { filPage.iterateAll() } returns listOf(mockk<Blob>(relaxed = true).also { every { it.name } returns "EdifactFil/test.txt" })

        val landPage = mockk<Page<Blob>>(relaxed = true)
        every { landPage.iterateAll() } returns listOf(existingBlob)

        // Stubber pa faktisk prefix-argument, se kommentar i testen over.
        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("EdifactFil/")) } returns filPage
        every { gcpStorage.list("dod", Storage.BlobListOption.prefix("FI/")) } returns landPage
        every { gcpStorage.get(any<BlobId>()) } returns inputBlob
        every { vurderSveFinEdifactDokument.splittTilDokumenter(any()) } returns listOf("doc")
        every { vurderSveFinEdifactDokument.vurderEditfactDokument(any()) } returns EdifactDokument(
            avsender = null,
            mottaker = null,
            meldingstype = null,
            norskIdent = fnr,
            avsenderLand = avsenderLandMedWhitespace,
            mottakerLand = null,
            fodselsdato = null,
            erSveFin = false
        )

        lagringsService.hentIdenterFraEdifact()
        lagringsService.hentIdenterFraEdifact()

        verify(exactly = 0) { gcpStorage.writer(any<BlobInfo>()) }
    }
}
