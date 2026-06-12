package no.nav.eessi.pensjon.gcp

import com.google.api.gax.paging.Page
import com.google.cloud.WriteChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

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

}