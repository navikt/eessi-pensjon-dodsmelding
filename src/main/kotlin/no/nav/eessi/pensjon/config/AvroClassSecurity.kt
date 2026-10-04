package no.nav.eessi.pensjon.config

object AvroSecurityConfig {
    init {
        System.setProperty("org.apache.avro.SERIALIZABLE_PACKAGES", "no.nav.person")
    }

    fun ensureTrustedPackagesConfigured() = Unit
}
