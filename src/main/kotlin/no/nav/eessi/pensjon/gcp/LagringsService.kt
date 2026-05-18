package no.nav.eessi.pensjon.gcp

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import no.nav.eessi.pensjon.dodsmelding.VurderSveFinEdifactDokument
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest

@Service
class LagringsService (
    @param:Value("\${GCP_BUCKET_UTL_YTELSE}") var utenlandkYtelseBucket: String,
    private val vurderSveFinEdifactDokument: VurderSveFinEdifactDokument,
    private val gcpStorage: Storage
) {

    private val logger = LoggerFactory.getLogger(LagringsService::class.java)

    fun lagreFnrIS3(fnr: String?, landkode: String?) {
        kanHendelsenOpprettes(fnr, landkode)
        val path = hentBrukerILand(landkode, hashedValue(fnr))

        try {
            logger.debug("Lagrer bruker fra: $landkode")
            logger.debug("Hasha : ${hashedValue(fnr)}")
            lagre(path)
        } catch (ex: Exception) {
            logger.error("Feiler ved lagring av data: $path $ex")
        }
    }

    fun kanHendelsenOpprettes(fnr: String?, land: String?) : Boolean {
        logger.debug("liste over obj FI/" + list("FI/").toString())
//        logger.debug("liste over obj SE/" + list("SE/").toString())
//        logger.debug("liste over obj PL/" + list("PL/").toString())
        return !eksisterer(land, fnr, utenlandkYtelseBucket)
    }

    fun hent(storageKey: String): String? {
        val filIS3 =  gcpStorage.get(BlobId.of(utenlandkYtelseBucket, storageKey))

        if(filIS3!= null && filIS3.exists()){
            return filIS3.getContent().contentToString()
        }
        return null
    }

    fun filLiggerIS3(bucketNavn: String) {
        logger.debug("sjekker om filen ligger i bucket")
        val listeOverFiler = listFil("EdifactFil/")
        listeOverFiler.forEach {
            hent(it)
            val edidok = vurderSveFinEdifactDokument.vurderEditfactDokument(it)
            if (edidok?.referanse != null && edidok.mottakerLand != null) {
                lagreFnrIS3(edidok.referanse, edidok.mottakerLand)
                logger.info("Lagret hashet fnr til s3")
            }
        }
    }

    fun eksisterer(land: String?, fnr: String?, bucketNavn: String): Boolean {
        logger.debug("sjekker om $land finnes i bucket: $bucketNavn")
        val path =  hentBrukerILand(land, fnr!!)
        kotlin.runCatching {
            gcpStorage.get(BlobId.of(bucketNavn, path)).exists()
        }.onFailure {
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun list(keyPrefix: String) : List<String> {
        return gcpStorage.list(utenlandkYtelseBucket , Storage.BlobListOption.prefix(keyPrefix))?.values?.map { v -> v.name}  ?:  emptyList()
    }

    fun listFil(keyPrefix: String) : List<String> {
        return gcpStorage.list(utenlandkYtelseBucket , Storage.BlobListOption.prefix(keyPrefix))?.values?.map { v -> v.name}  ?:  emptyList()
    }

    fun hentBrukerILand(landkode: String?, fnr: String): String {
        val land = when (landkode) {
            "FI" -> "FI"
            "SE" -> "SE"
            "PL" -> "PL"
            else -> {
                val msg = "Ikke gyldig"
                throw RuntimeException(msg).also { logger.error(msg) }
            }
        }
        val path =  "$land/$fnr"
        logger.debug("Hendelsespath: $path")

        return path
    }

    fun lagre(storageKey: String) {
        val blobInfo =  BlobInfo.newBuilder(BlobId.of(utenlandkYtelseBucket, storageKey)).setContentType("application/json").build()
        kotlin.runCatching {
            gcpStorage.writer(blobInfo).use { it.write(ByteBuffer.wrap(storageKey.toByteArray())) }
        }.onFailure { e ->
            logger.error("Feilet med å lagre dokument med id: ${blobInfo.blobId.name}", e)
        }.onSuccess {
            logger.info("Lagret fil med blobid:  ${blobInfo.blobId.name} og bytes: $it")
        }
    }

    fun hashedValue(input: String?): String {
        val bytes = input?.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

}