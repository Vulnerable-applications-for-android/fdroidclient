package org.fdroid.database

import org.fdroid.index.v2.IndexStreamReceiver
import org.fdroid.index.v2.PackageV2
import org.fdroid.index.v2.RepoV2

internal class DbStreamReceiver(
    private val db: FDroidDatabaseInt,
) : IndexStreamReceiver {

    override fun receive(repoId: Long, repo: RepoV2, certificate: String?) {
        db.getRepositoryDao().replace(repoId, repo, certificate)
    }

    override fun receive(repoId: Long, packageId: String, p: PackageV2) {
        db.getAppDao().insert(repoId, packageId, p.metadata)
        db.getVersionDaoInt().insert(repoId, packageId, p.versions)
    }

}
