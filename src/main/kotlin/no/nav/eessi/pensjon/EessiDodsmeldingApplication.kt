package no.nav.eessi.pensjon.no.nav.eessi.pensjon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication

class EessiDodsmeldingApplication {
    fun main(args: Array<String>) {
        runApplication<EessiDodsmeldingApplication>(*args)
    }

}