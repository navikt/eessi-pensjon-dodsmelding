package no.nav.eessi.pensjon.h070

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import no.nav.eessi.pensjon.eux.model.sed.Doedsfall
import no.nav.eessi.pensjon.eux.model.sed.HBruker as SedBruker
import no.nav.eessi.pensjon.eux.model.sed.HNav as SedNav

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Nav(
    val bruker: Bruker
) {
    fun toSedNav(): SedNav = SedNav(
        bruker = bruker.toSedBruker()
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Bruker(
    val doedsfall: Doedsfall,
    val person: no.nav.eessi.pensjon.h070.Person
) {
    fun toSedBruker(): SedBruker = SedBruker(
        doedsfall = doedsfall,
        person = person.toSedPerson()
    )
}
