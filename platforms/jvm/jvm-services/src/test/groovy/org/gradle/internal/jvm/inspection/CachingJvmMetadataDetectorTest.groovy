/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.jvm.inspection

import org.gradle.api.internal.file.TestFiles
import org.gradle.internal.jvm.Jvm
import org.gradle.jvm.toolchain.internal.InstallationLocation
import org.gradle.test.fixtures.concurrent.ConcurrentSpec
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.FileSystemTestPreconditions

import org.gradle.testfixtures.internal.NativeServicesTestFixture
import spock.lang.TempDir

import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachingJvmMetadataDetectorTest extends ConcurrentSpec {

    @TempDir
    File temporaryFolder

    def "returned metadata from delegate"() {
        def metadata = Mock(JvmInstallationMetadata)
        given:
        def delegate = Mock(JvmMetadataDetector) {
            getMetadata(_ as InstallationLocation) >> metadata
        }

        def detector = new CachingJvmMetadataDetector(delegate)

        when:
        def actual = detector.getMetadata(testLocation("jdk"))

        then:
        actual.is(metadata)
    }

    def "caches metadata by home"() {
        given:
        def delegate = Mock(JvmMetadataDetector) {
            getMetadata(_ as InstallationLocation) >> Mock(JvmInstallationMetadata)
        }

        def detector = new CachingJvmMetadataDetector(delegate)

        when:
        def metadata1 = detector.getMetadata(testLocation("jdk"))
        def metadata2 = detector.getMetadata(testLocation("jdk"))

        then:
        metadata1.is(metadata2)
    }

    @Requires(FileSystemTestPreconditions.Symlinks)
    def "cached probe are not affected by symlink changes"() {
        given:
        NativeServicesTestFixture.initialize()
        def metaDataDetector = new DefaultJvmMetadataDetector(
            TestFiles.execHandleFactory(),
            TestFiles.tmpDirTemporaryFileProvider(temporaryFolder)
        )
        def detector = new CachingJvmMetadataDetector(metaDataDetector)
        File javaHome1 = Jvm.current().javaHome
        def link = new TestFile(Files.createTempDirectory(temporaryFolder.toPath(), null).toFile(), "jdklink")
        link.createLink(javaHome1)

        when:
        def metadata1 = detector.getMetadata(testLocation(link.absolutePath))
        link.createLink(new File("doesntExist"))
        def metadata2 = detector.getMetadata(testLocation(link.absolutePath))

        then:
        metadata1.javaHome.toString().contains(Jvm.current().javaHome.canonicalPath)
        metadata2.errorMessage.contains("No such directory")
    }


    def "invalidation takes predicate into account"() {
        def location1 = testLocation("jdk1")
        def location2 = testLocation("jdk2")
        def metadata1 = Mock(JvmInstallationMetadata)
        def metadata2 = Mock(JvmInstallationMetadata)
        def delegate = Mock(JvmMetadataDetector) {
            getMetadata(location1) >> metadata1
            getMetadata(location2) >> metadata2
        }
        def metadataDetector = new CachingJvmMetadataDetector(delegate)
        metadataDetector.getMetadata(location1)
        metadataDetector.getMetadata(location2)

        when: "cache gets invalidated by predicate, and some calls are made that match it and some that don't"
        metadataDetector.invalidateItemsMatching(it -> it == metadata1)
        metadataDetector.getMetadata(location1)
        metadataDetector.getMetadata(location2)
        then: "only the calls that don't match the predicate get executed again"
        1 * delegate.getMetadata(location1)
        0 * delegate.getMetadata(location2)
    }

    def "probes different installations concurrently"() {
        given:
        def locations = [testLocation("jdk1"), testLocation("jdk2")]
        def probesStarted = new CyclicBarrier(2)
        def metadata = Stub(JvmInstallationMetadata)
        def delegate = { InstallationLocation location ->
            if (location.location in locations*.location) {
                probesStarted.await(10, TimeUnit.SECONDS)
            }
            metadata
        } as JvmMetadataDetector
        def detector = new CachingJvmMetadataDetector(delegate)

        when:
        async {
            locations.each { location ->
                start {
                    assert detector.getMetadata(location).is(metadata)
                }
            }
        }

        then:
        noExceptionThrown()
    }

    def "concurrent requests for the same canonical home share one probe"() {
        given:
        def home = new File(temporaryFolder, "jdk")
        def locations = [testLocation(home.path), testLocation(new File(home, ".").path)]
        def requestsStarted = new CyclicBarrier(2)
        def probes = new AtomicInteger()
        def metadata = Stub(JvmInstallationMetadata)
        def delegate = { InstallationLocation location ->
            if (location.location.canonicalFile == home.canonicalFile) {
                probes.incrementAndGet()
            }
            metadata
        } as JvmMetadataDetector
        def detector = new CachingJvmMetadataDetector(delegate)

        when:
        async {
            locations.each { location ->
                start {
                    requestsStarted.await(10, TimeUnit.SECONDS)
                    assert detector.getMetadata(location).is(metadata)
                }
            }
        }

        then:
        probes.get() == 1
    }

    def "invalidation waits for an in-flight probe and removes its result"() {
        given:
        def location = testLocation("jdk")
        def probes = new AtomicInteger()
        def metadata = Stub(JvmInstallationMetadata)
        def delegate = { InstallationLocation candidate ->
            if (candidate.location == location.location && probes.incrementAndGet() == 1) {
                instant.probing
                thread.blockUntil.invalidating
                instant.probeFinished
            }
            metadata
        } as JvmMetadataDetector
        def detector = new CachingJvmMetadataDetector(delegate)

        when:
        async {
            start {
                detector.getMetadata(location)
            }
            start {
                thread.blockUntil.probing
                instant.invalidating
                detector.invalidateItemsMatching { it.is(metadata) }
                instant.invalidated
            }
        }
        detector.getMetadata(location)

        then:
        instant.probeFinished < instant.invalidated
        probes.get() == 2
    }

    private InstallationLocation testLocation(String filePath) {
        return InstallationLocation.userDefined(new File(filePath), "test")
    }
}
