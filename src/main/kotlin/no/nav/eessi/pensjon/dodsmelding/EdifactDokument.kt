package no.nav.eessi.pensjon.dodsmelding

open class EdifactDokument(
    val avsender: String? = null,
    val mottaker: String? = null,
    val meldingstype: String? = null,
    val norskIdent: String? = null,
    val svenskIdent: String? = null,
    val avsenderLand: String? = null,
    val mottakerLand: String? = null,
    val fodselsdato: String? = null,
    val erSveFin: Boolean,
    val doedsdato: String? = null
)