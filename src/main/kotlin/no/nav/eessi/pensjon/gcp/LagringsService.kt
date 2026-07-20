package no.nav.eessi.pensjon.gcp

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import no.nav.eessi.pensjon.personoppslag.pdl.model.IdentInformasjon
import no.nav.eessi.pensjon.utils.toJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HASH_PRESET = "HashedUsers"

@Service
class LagringsService (
    @param:Value("\${GCP_BUCKET_UTL_YTELSE}") var utenlandkYtelseBucket: String,
    @param:Value("\${GCP_H070_OPPRETTET}") var h070_opprettetBucket: String,
    private val vurderSveFinEdifactDokument: VurderSveFinEdifactDokument,
    private val gcpStorage: Storage,
    @param:Value("\${HASH_SECRET_KEY}") private val hashSecretKey: String
) {

    private val logger = LoggerFactory.getLogger(LagringsService::class.java)

    fun lagreFnrIS3(fnr: String?, landkode: String?) {
        kanHendelsenOpprettes(fnr, landkode)
        val path = hentBrukerILand(landkode, fnr!!)

        try {
            logger.debug("Hasha : ${hashedValue(fnr)}")
            if (path != null) {
                lagre(path, utenlandkYtelseBucket)
            }
            else logger.warn("Fant ikke path")
        } catch (ex: Exception) {
            logger.error("Feiler ved lagring av data: $path $ex")
        }
    }

    fun lagreFnrForBruker(fnr: String): Boolean {
        val hashafnr = hashedValue(fnr)
        try {
            if(finnesDoedsmeldingAlleredeForBruker(fnr)) return false.also { logger.error("Bruker finnes i bucket.") }
            logger.debug("Hasha : $hashafnr")
            lagre("$HASH_PRESET/$hashafnr", h070_opprettetBucket)
            return true
        } catch (ex: Exception) {
            logger.error("Feiler ved lagring av: $hashafnr $ex")
        }
        return false
    }

    fun kanHendelsenOpprettes(fnr: String?, land: String?) : Boolean {
        logger.debug("liste over obj FI/" + list("FI/", utenlandkYtelseBucket).toString())
        logger.debug("liste over obj SE/" + list("SE/", utenlandkYtelseBucket).toString())
        return !eksisterer(land, fnr, utenlandkYtelseBucket)
    }

    fun finnesDodBrukerILeveAttReg(
        fnr: List<IdentInformasjon>?
    ): Pair<String, String>? {
        logger.debug("Sjekker om fødselsnummer ligger i bucket")

        val identifikatorer = fnr.orEmpty()
        if (identifikatorer.isEmpty()) {
            logger.debug("Ingen identifikatorer mottatt fra PDL")
            return null
        }

        val bucketEntries = listOf("FI/", "SE/", "PL/", "DK/")
            .flatMap { prefix ->
                list(prefix, utenlandkYtelseBucket)
            }

        val resultat = bucketEntries.firstNotNullOfOrNull { bucketEntry ->
            identifikatorer.firstNotNullOfOrNull { identInformasjon ->
                val ident = identInformasjon.ident
                val hash = hashedValue(ident)

                if (bucketEntry.contains(hash)) {
                    ident to hentLandFraPrefix(bucketEntry)
                } else {
                    null
                }
            }
        }

        if (resultat != null) {
            logger.debug("Brukeren finnes i bucket. H070 kan sendes ut")
        } else {
            logger.info("Brukeren finnes ikke i bucket og kan dermed ignoreres")
        }

        return resultat
    }

    fun hentLandFraPrefix(hash: String) : String {
        val land = hash.substring(0, 2)
        if(land in listOf("FI", "SE", "DK")) return land
        throw RuntimeException("Henter landkode fra prefix $land")
    }

    fun finnesDoedsmeldingAlleredeForBruker(fnr: String): Boolean {
        logger.debug("sjekker om fnr allerede ligger inne med dodsmelding i bucket")
        val hasha = hashedValue(fnr)
        val listeOverFnrIBucket = list("$HASH_PRESET/$hasha", h070_opprettetBucket)
        listeOverFnrIBucket.forEach { fnrIBucket ->
            logger.debug("Sjekker om fnr finnes i bucket for bruker: $hasha")
            return if (fnrIBucket.contains(hasha)) {
                logger.debug("Denne brukeren finnes i bucket. H070 er allerede sendt ut")
                true
            } else {
                logger.info("Bruker finnes ikke i bucket, H070 kan opprettes på bruker.")
                false
            }
        }
        return false
    }

    fun hent(storageKey: String): String? {
        val filIS3 =  gcpStorage.get(BlobId.of(utenlandkYtelseBucket, storageKey))
        logger.debug("Henter fila $filIS3")

        if(filIS3!= null && filIS3.exists()){
            return String(filIS3.getContent(), Charsets.UTF_8)
        }
        return null
    }

    fun filLiggerIS3() {
        logger.info("sjekker om filen ligger i bucket")
        val listeOverFiler = list("EdifactFil/", utenlandkYtelseBucket)

        listeOverFiler.forEach { filNavn ->
            logger.info("sjekker: $filNavn")

            val innholdIBlob = hent(filNavn).also { logger.debug("Hentet innhold fra blob: $it") }
            val dokumenter = vurderSveFinEdifactDokument.splittTilDokumenter(innholdIBlob)
            logger.info("Fant ${dokumenter.size} dokumenter i filen $filNavn")

            dokumenter.forEach { dokument ->
                val edidok = vurderSveFinEdifactDokument
                    .vurderEditfactDokument(dokument)
                    .also { logger.debug("Tolket dokument: ${it?.toJson()}") }

                val norskIdent = edidok?.norskIdent
                val avsenderLand = edidok?.avsenderLand
                if (norskIdent == null || avsenderLand == null) return@forEach

                val blobben = list(avsenderLand, utenlandkYtelseBucket)
                val hasha = hashedValue(norskIdent)

                if (blobben.contains(hasha)) {
                    logger.debug("Denne brukeren finnes fra før av i bucket")
                    return@forEach
                }

                val landkode = hentBrukerILand(avsenderLand, norskIdent)
                if (landkode.isNullOrBlank()) {
                    logger.warn("************* manglende landkode **************")
                } else {
                    lagre(landkode, utenlandkYtelseBucket)
                    logger.info("Lagret hashet fnr til s3")
                }
            }
        }
    }
    fun eksisterer(land: String?, fnr: String?, bucketNavn: String): Boolean {
        logger.debug("sjekker om $land finnes i bucket: $bucketNavn")
        val path = hentBrukerILand(land, fnr!!) ?: return false

        kotlin.runCatching {
            gcpStorage.get(BlobId.of(bucketNavn, path)).exists()
        }.onFailure {
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun list(keyPrefix: String, bucket: String) : List<String> {
        logger.debug("lister innhold i fila")
        return gcpStorage.list(bucket , Storage.BlobListOption.prefix(keyPrefix))?.values?.map { v -> v.name}  ?:  emptyList()
    }

    fun hentBrukerILand(landkode: String?, fnr: String): String? {
        val land = when (landkode?.trim()) {
            "FI" -> "FI"
            "SE" -> "SE"
            "PL" -> "PL"
            else -> {
                logger.warn("Ikke gyldig landkode: $landkode")
                return null
            }
        }
        val path =  "$land/${hashedValue(fnr)}"
        logger.debug("Hendelsespath: $path")

        return path
    }

    fun lagre(storageKey: String, bucket: String) {
        val blobInfo =  BlobInfo.newBuilder(BlobId.of(bucket, storageKey)).setContentType("application/json").build()
        kotlin.runCatching {
            gcpStorage.writer(blobInfo).use { it.write(ByteBuffer.wrap(storageKey.toByteArray())) }
        }.onFailure { e ->
            logger.error("Feilet med å lagre dokument med id: ${blobInfo.blobId.name}", e)
        }.onSuccess {
            logger.info("Lagret fil med blobid:  ${blobInfo.blobId.name} og bytes: $it")
        }
    }

    fun hashedValue(input: String?): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hashSecretKey.toByteArray(), "HmacSHA256"))
        }
        return mac.doFinal(input?.toByteArray() ?: ByteArray(0))
            .joinToString("") { "%02x".format(it) }.also { logger.debug("Hashet verdi for input $input: $it") }
    }
}