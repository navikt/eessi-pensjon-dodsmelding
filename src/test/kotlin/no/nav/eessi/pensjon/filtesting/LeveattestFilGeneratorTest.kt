package no.nav.eessi.pensjon.filtesting

import io.mockk.mockk
import no.nav.eessi.pensjon.gcp.LagringsService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

@Disabled
class LeveattestFilGeneratorTest {

    val identerFraSverige = listOf("")

    @Test
    fun `genererer filer for opplasting til leveattestregisteret`() {
        val hashSecretKey = ""
        //TODO: Sett inn riktig hashSecretKey for å generere filene. Denne må hentes fra secrets
        //SKAL IKKE SJEKKES INN.
        val utMappe = File("build/leveattest-upload").apply {
            deleteRecursively()
            mkdirs()
        }
        val lagringsService = LagringsService("ikke-brukt", "ikke-brukt", mockk(), hashSecretKey)

        val linjer = identerFraSverige.joinToString("\n").trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty()}

        var antallUgyldige = 0
        val stier = linjer.mapNotNull { linje ->
            val (land, ident) = linje.split(";").map { it.trim() }.takeIf { it.size == 2 }
                ?: return@mapNotNull null.also { antallUgyldige++ }
            lagringsService.landOgIdent(land, ident) ?: null.also { antallUgyldige++ }
        }.toSet()

        stier.forEach { sti ->
            File(utMappe, sti).apply {
                parentFile.mkdirs()
                writeText(sti)
            }
        }

        println("Genererte ${stier.size} filer i ${utMappe.absolutePath} (linjer: ${linjer.size}, ugyldige: $antallUgyldige)")
        assertTrue(antallUgyldige == 0, "Fant $antallUgyldige ugyldige linjer i IDENT_FIL")
    }
}
