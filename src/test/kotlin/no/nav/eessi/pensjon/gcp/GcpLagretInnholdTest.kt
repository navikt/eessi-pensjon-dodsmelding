package no.nav.eessi.pensjon.gcp

import com.google.api.gax.paging.Page
import com.google.cloud.storage.Blob
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration

class GcpLagretInnholdTest {
    private val storage = mockk<Storage>()
    private val page = mockk<Page<Blob>>()
    private val runner = GcpLagretInnhold(storage, "leveattest", "h070")
    private val args = DefaultApplicationArguments()

    @Test
    fun `oppstart kontrollerer begge bucketene`() {
        every { storage.list(any<String>()) } returns page
        every { page.iterateAll() } returns emptyList()

        runner.run(args)

        verify(exactly = 1) { storage.list("leveattest") }
        verify(exactly = 1) { storage.list("h070") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["leveattest", "h070"])
    fun `feil i en av bucketene avbryter oppstart`(bucket: String) {
        val feil = StorageException(403, "Mangler tilgang")
        every { storage.list(any<String>()) } returns page
        every { page.iterateAll() } returns emptyList()
        every { storage.list(bucket) } throws feil

        assertSame(feil, assertThrows<StorageException> { runner.run(args) })
    }

    @Test
    fun `feil under paginering avbryter oppstart`() {
        val feil = StorageException(503, "Feil ved neste side")
        every { storage.list("leveattest") } returns page
        every { page.iterateAll() } throws feil

        assertSame(feil, assertThrows<StorageException> { runner.run(args) })
        verify(exactly = 0) { storage.list("h070") }
    }

    @Test
    fun `Spring lukker applikasjonen naar bucketkontrollen feiler`() {
        every { storage.list("leveattest") } throws StorageException(403, "Mangler tilgang")
        lateinit var context: ConfigurableApplicationContext
        val application = SpringApplication(Testkonfigurasjon::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setRegisterShutdownHook(false)
            addInitializers(org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> {
                context = it
                it.beanFactory.registerSingleton("gcpOppstartskontroll", runner)
            })
        }

        try {
            val feil = assertThrows<StorageException> {
                application.run("--spring.main.banner-mode=off")
            }
            assertTrue(generateSequence<Throwable>(feil) { it.cause }.any { it is StorageException })
            assertFalse(context.isActive)
        } finally {
            context.close()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Testkonfigurasjon
}
