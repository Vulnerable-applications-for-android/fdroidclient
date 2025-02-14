package org.fdroid.repo

import android.net.Uri
import mu.KotlinLogging
import org.fdroid.database.Repository
import org.fdroid.download.DownloadRequest
import org.fdroid.download.DownloaderFactory
import org.fdroid.download.HttpManager
import org.fdroid.download.getDigestInputStream
import org.fdroid.index.IndexParser
import org.fdroid.index.SigningException
import org.fdroid.index.TempFileProvider
import org.fdroid.index.parseEntry
import org.fdroid.index.v2.EntryVerifier
import org.fdroid.index.v2.FileV2
import org.fdroid.index.v2.IndexV2FullStreamProcessor
import org.fdroid.index.v2.SIGNED_FILE_NAME
import java.net.Proxy

internal class RepoV2Fetcher(
    private val tempFileProvider: TempFileProvider,
    private val downloaderFactory: DownloaderFactory,
    private val httpManager: HttpManager,
    private val proxy: Proxy? = null,
) : RepoFetcher {
    private val log = KotlinLogging.logger {}

    @Throws(SigningException::class)
    override suspend fun fetchRepo(
        uri: Uri,
        repo: Repository,
        receiver: RepoPreviewReceiver,
        fingerprint: String?,
    ) {
        // download and verify entry
        val entryFile = tempFileProvider.createTempFile()
        val entryDownloader = downloaderFactory.create(
            repo = repo,
            uri = uri.buildUpon().appendPath(SIGNED_FILE_NAME).build(),
            indexFile = FileV2.fromPath("/$SIGNED_FILE_NAME"),
            destFile = entryFile,
        )
        val (cert, entry) = try {
            entryDownloader.download()
            val verifier = EntryVerifier(entryFile, null, fingerprint)
            verifier.getStreamAndVerify { inputStream ->
                IndexParser.parseEntry(inputStream)
            }
        } finally {
            entryFile.delete()
        }

        log.info { "Downloaded entry, now streaming index..." }

        // stream index
        val indexRequest = DownloadRequest(
            indexFile = FileV2.fromPath(entry.index.name.trimStart('/')),
            mirrors = repo.getMirrors(),
            proxy = proxy,
            username = repo.username,
            password = repo.password,
        )
        val streamReceiver = RepoV2StreamReceiver(receiver, repo.username, repo.password)
        val streamProcessor = IndexV2FullStreamProcessor(streamReceiver, cert)
        val digestInputStream = httpManager.getDigestInputStream(indexRequest)
        digestInputStream.use { inputStream ->
            streamProcessor.process(entry.version, inputStream) { }
        }
        val hexDigest = digestInputStream.getDigestHex()
        if (!hexDigest.equals(entry.index.sha256, ignoreCase = true)) {
            throw SigningException("Invalid ${entry.index.name} hash: $hexDigest")
        }
    }
}
