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
import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

        lagringsService.lagreFnrIS3(fnr, "FI")

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

        val result = lagringsService.lagreFnrIS3(fnr, "FI")

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
        val result = lagringsService.hentBrukerILand(landkode, "12345678901")

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
        val result = lagringsService.hentBrukerILand(input, "12345678901")

        assertNotNull(result)
        assertTrue(result!!.startsWith("$expectedPrefix/"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["NO", ""])
    fun `hentBrukerILand returnerer null for ugyldig eller tom landkode`(landkode: String) {
        val result = lagringsService.hentBrukerILand(landkode, "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand returnerer null for null landkode`() {
        val result = lagringsService.hentBrukerILand(null, "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand returnerer null for whitespace only`() {
        val result = lagringsService.hentBrukerILand("   ", "12345678901")

        assertNull(result)
    }

    @Test
    fun `hentBrukerILand genererer hashet fnr i path`() {
        val fnr = "12345678901"
        val result = lagringsService.hentBrukerILand("FI", fnr)

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
        val result1 = lagringsService.hentBrukerILand("FI", fnr)
        val result2 = lagringsService.hentBrukerILand("FI", fnr)

        assertEquals(result1, result2)
    }

    @Test
    fun `hentBrukerILand ulike fnr genererer ulike hash`() {
        val result1 = lagringsService.hentBrukerILand("FI", "12345678901")
        val result2 = lagringsService.hentBrukerILand("FI", "98765432101")

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

        lagringsService.lagreH070(h070)

        assertEquals("h070_opprettetBucket", blobInfoSlot.captured.blobId.bucket)
        assertTrue(blobInfoSlot.captured.blobId.name.startsWith("H070_LAGRET/"))
        assertTrue(blobInfoSlot.captured.blobId.name.endsWith(".json"))
        val root = ObjectMapper().readTree(writtenPayload.toString())
        val lagretIdentifikatorer = root.findParents("person")
            .mapNotNull { it.get("person")?.get("pin") }
            .flatMap { pinArray -> pinArray.map { it.path("identifikator").asText() } }

        assertTrue(lagretIdentifikatorer.contains(lagringsService.hashedValue(norskPin)))
        assertTrue(lagretIdentifikatorer.contains(lagringsService.hashedValue(utenlandskPin)))
        assertTrue(lagretIdentifikatorer.none { it == norskPin || it == utenlandskPin })
    }
}
