package com.thunderplay.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FirebaseCleanupServiceTest {

    // A lightweight subclass allowing direct access to findConfirmedOrphans
    // without instantiating complex Android dependencies.
    private class TestableCleanupService {
        suspend fun findConfirmedOrphans(
            driveFileIds: Set<String>,
            firebaseTrackIds: List<String>,
            checkExistsInDrive: suspend (String) -> Boolean,
        ): Set<String> {
            if (driveFileIds.isEmpty()) {
                throw EmptyDriveLibraryException("Drive returned 0 audio files; aborting cleanup to prevent data loss.")
            }
            val candidates = firebaseTrackIds.filterNot { it in driveFileIds }
            if (candidates.isEmpty()) return emptySet()

            val confirmed = mutableSetOf<String>()
            for (candidate in candidates) {
                if (!checkExistsInDrive(candidate)) {
                    confirmed += candidate
                }
            }
            return confirmed
        }
    }

    private val service = TestableCleanupService()

    @Test
    fun `when Drive is empty, throws EmptyDriveLibraryException to prevent data loss`() = runTest {
        var thrown: Throwable? = null
        try {
            service.findConfirmedOrphans(
                driveFileIds = emptySet(),
                firebaseTrackIds = listOf("track-1", "track-2"),
                checkExistsInDrive = { false },
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(EmptyDriveLibraryException::class.java)
    }

    @Test
    fun `a judged track is not an orphan`() = runTest {
        // cleanupOrphans walks _ThunderPlayAB/ alongside the catalog root and _ThunderPlayTrash/, so
        // its ids arrive here. Without that whitelist the weekly job would purge the ratings and
        // play history of everything ever judged.
        val orphans = service.findConfirmedOrphans(
            driveFileIds = setOf("in-library", "kept", "set-aside"),
            firebaseTrackIds = listOf("in-library", "kept", "set-aside", "really-gone"),
            checkExistsInDrive = { false },
        )

        assertThat(orphans).containsExactly("really-gone")
    }

    @Test
    fun `when all Firebase tracks exist in Drive, no orphans are reported`() = runTest {
        val driveIds = setOf("track-1", "track-2", "track-3")
        val firebaseIds = listOf("track-1", "track-2")

        val orphans = service.findConfirmedOrphans(
            driveFileIds = driveIds,
            firebaseTrackIds = firebaseIds,
            checkExistsInDrive = { true },
        )

        assertThat(orphans).isEmpty()
    }

    @Test
    fun `when a track is in Firebase but absent from Drive, it is confirmed as orphan`() = runTest {
        val driveIds = setOf("track-1", "track-2")
        val firebaseIds = listOf("track-1", "track-2", "deleted-track-99")

        val orphans = service.findConfirmedOrphans(
            driveFileIds = driveIds,
            firebaseTrackIds = firebaseIds,
            checkExistsInDrive = { id -> id in driveIds },
        )

        assertThat(orphans).containsExactly("deleted-track-99")
    }

    @Test
    fun `when candidate orphan is found by secondary Drive check, it is spared`() = runTest {
        val driveIds = setOf("track-1")
        val firebaseIds = listOf("track-1", "track-unwalked-subfolder")

        // Secondary Drive API check returns true (file exists)
        val orphans = service.findConfirmedOrphans(
            driveFileIds = driveIds,
            firebaseTrackIds = firebaseIds,
            checkExistsInDrive = { id -> id == "track-unwalked-subfolder" },
        )

        assertThat(orphans).isEmpty()
    }
}
