package net.corda.core.serialization.internal

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ContractAttachment
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.createSimpleCache
import net.corda.core.internal.isUploaderTrusted
import net.corda.core.internal.toSynchronised
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationFactory
import net.corda.core.serialization.internal.AttachmentURLStreamHandlerFactory.toUrl
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.*
import java.util.jar.JarInputStream

/**
 * A custom ClassLoader that knows how to load classes from a set of attachments. The attachments themselves only
 * need to provide JAR streams, and so could be fetched from a database, local disk, etc. Constructing an
 * AttachmentsClassLoader is somewhat expensive, as every attachment is scanned to ensure that there are no overlapping
 * file paths.
 */
class AttachmentsClassLoader(attachments: List<Attachment>, parent: ClassLoader = ClassLoader.getSystemClassLoader()) :
        URLClassLoader(attachments.map(::toUrl).toTypedArray(), parent) {

    companion object {
        private val log = contextLogger()

        init {
            // This is required to register the AttachmentURLStreamHandlerFactory.
            URL.setURLStreamHandlerFactory(AttachmentURLStreamHandlerFactory)
        }

        private const val `META-INF` = "meta-inf"
        private val excludeFromNoOverlapCheck = setOf(
                "manifest.mf",
                "license",
                "license.txt",
                "notice",
                "notice.txt",
                "index.list"
        )

        private fun shouldCheckForNoOverlap(path: String): Boolean {
            if (!path.startsWith(`META-INF`)) return true
            val p = path.substring(`META-INF`.length + 1)
            if (p in excludeFromNoOverlapCheck) return false
            if (p.endsWith(".sf") || p.endsWith(".dsa") || p.endsWith(".rsa")) return false
            return true
        }

        @CordaSerializable
        class OverlappingAttachments(val path: String) : Exception() {
            override fun toString() = "Multiple attachments define a file at path $path"
        }

        private fun requireNoDuplicates(attachments: List<Attachment>) {
            val classLoaderEntries = mutableMapOf<String, SecureHash>()
            val attachmentContentMTHashes = mutableSetOf<SecureHash>()
            for (attachment in attachments) {
                attachment.openAsJAR().use { jar ->
                    val attachmentEntries = calculateEntriesHashes(attachment, jar)
                    if (attachmentEntries.isNotEmpty()) {
                        val contentHashMT = MerkleTree.getMerkleTree(attachmentEntries.map { it.value }).hash
                        if (attachmentContentMTHashes.contains(contentHashMT)) {
                            log.debug { "Duplicate entry: ${attachment.id} has same content hash $contentHashMT as previous attachment" }
                        }
                        else {
                            attachmentEntries.forEach { path, contentHash ->
                                if (path in classLoaderEntries.keys) {
                                    val originalContentHash = classLoaderEntries[path]!!
                                    if (contentHash == originalContentHash) {
                                        log.debug { "Duplicate entry $path has same content hash $contentHash" }
                                    } else
                                        throw OverlappingAttachments(path)
                                }
                            }
                            attachmentContentMTHashes.add(contentHashMT)
                            classLoaderEntries.putAll(attachmentEntries)
                        }
                    }
                }
            }
        }

        fun calculateEntriesHashes(attachment: Attachment, jar: JarInputStream): Map<String, SecureHash> {
            val contentHashes = mutableMapOf<String, SecureHash>()
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.isDirectory) continue
                // We already verified that paths are not strange/game playing when we inserted the attachment
                // into the storage service. So we don't need to repeat it here.
                //
                // We forbid files that differ only in case, or path separator to avoid issues for Windows/Mac developers where the
                // filesystem tries to be case insensitive. This may break developers who attempt to use ProGuard.
                //
                // Also convert to Unix path separators as all resource/class lookups will expect this.
                //
                val filepath = entry.name.toLowerCase().replace('\\', '/')
                if (shouldCheckForNoOverlap(filepath)) {
                    val contentHash = readAttachment(attachment, filepath).sha256()
                    contentHashes[filepath] = contentHash
                }
            }
            return contentHashes
        }

        @VisibleForTesting
        private fun readAttachment(attachment: Attachment, filepath: String): ByteArray {
            ByteArrayOutputStream().use {
                attachment.extractFile(filepath, it)
                return it.toByteArray()
            }
        }
    }

    init {
        require(attachments.mapNotNull { it as? ContractAttachment }.all { isUploaderTrusted(it.uploader) }) {
            "Attempting to load Contract Attachments downloaded from the network"
        }

        requireNoDuplicates(attachments)
    }
}

/**
 * This is just a factory that provides a cache to avoid constructing expensive [AttachmentsClassLoader]s.
 */
@VisibleForTesting
internal object AttachmentsClassLoaderBuilder {

    private const val ATTACHMENT_CLASSLOADER_CACHE_SIZE = 1000

    // This runs in the DJVM so it can't use caffeine.
    private val cache: MutableMap<List<SecureHash>, AttachmentsClassLoader> = createSimpleCache<List<SecureHash>, AttachmentsClassLoader>(ATTACHMENT_CLASSLOADER_CACHE_SIZE)
            .toSynchronised()

    fun build(attachments: List<Attachment>): AttachmentsClassLoader {
        return cache.computeIfAbsent(attachments.map { it.id }.sorted()) {
            AttachmentsClassLoader(attachments)
        }
    }

    fun <T> withAttachmentsClassloaderContext(attachments: List<Attachment>, block: (ClassLoader) -> T): T {

        // Create classloader from the attachments.
        val transactionClassLoader = AttachmentsClassLoaderBuilder.build(attachments)

        // Create a new serializationContext for the current Transaction.
        val transactionSerializationContext = SerializationFactory.defaultFactory.defaultContext.withClassLoader(transactionClassLoader)

        // Deserialize all relevant classes in the transaction classloader.
        return SerializationFactory.defaultFactory.withCurrentContext(transactionSerializationContext) {
            block(transactionClassLoader)
        }
    }
}

/**
 * Registers a new internal "attachment" protocol.
 * This will not be exposed as an API.
 */
object AttachmentURLStreamHandlerFactory : URLStreamHandlerFactory {
    private const val attachmentScheme = "attachment"

    // TODO - what happens if this grows too large?
    private val loadedAttachments = mutableMapOf<String, Attachment>().toSynchronised()

    override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
        return if (attachmentScheme == protocol) {
            AttachmentURLStreamHandler
        } else null
    }

    fun toUrl(attachment: Attachment): URL {
        val id = attachment.id.toString()
        loadedAttachments[id] = attachment
        return URL(attachmentScheme, "", -1, id, AttachmentURLStreamHandler)
    }

    private object AttachmentURLStreamHandler : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection {
            if (url.protocol != attachmentScheme) throw IOException("Cannot handle protocol: ${url.protocol}")
            val attachment = loadedAttachments[url.path] ?: throw IOException("Could not load url: $url .")
            return AttachmentURLConnection(url, attachment)
        }
    }

    private class AttachmentURLConnection(url: URL, private val attachment: Attachment) : URLConnection(url) {
        override fun getContentLengthLong(): Long = attachment.size.toLong()
        override fun getInputStream(): InputStream = attachment.open()
        override fun connect() {
            connected = true
        }
    }
}
