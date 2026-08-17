package no.nav.eessi.pensjon.h070

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import no.nav.eessi.pensjon.eux.model.sed.Doedsfall
import no.nav.eessi.pensjon.eux.model.sed.HBruker as SedBruker
import no.nav.eessi.pensjon.eux.model.sed.HNav as SedNav

@JsonIgnoreProperties(ignoreUnknown = true)
data class Nav(
    val bruker: Bruker
) {
    fun toSedNav(): SedNav = SedNav(
        bruker = bruker.toSedBruker()
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Bruker(
    val doedsdato: String,
    val person: Person
) {
    fun toSedBruker(): SedBruker = SedBruker(
        doedsfall = Doedsfall(doedsdato),
        person = person.toSedPerson()
    )
}
