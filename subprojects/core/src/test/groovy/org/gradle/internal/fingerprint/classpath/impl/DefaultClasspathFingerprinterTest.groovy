/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.fingerprint.classpath.impl

import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.changedetection.state.DefaultResourceSnapshotterCacheService
import org.gradle.api.internal.changedetection.state.PropertiesFileFilter
import org.gradle.api.internal.changedetection.state.ResourceEntryFilter
import org.gradle.api.internal.changedetection.state.ResourceFilter
import org.gradle.api.internal.file.TestFiles
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint
import org.gradle.internal.fingerprint.LineEndingSensitivity
import org.gradle.internal.fingerprint.impl.DefaultFileCollectionSnapshotter
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.HashCodeSerializer
import org.gradle.test.fixtures.file.CleanupTestDirectory
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.testfixtures.internal.TestInMemoryIndexedCache
import org.gradle.util.UsesNativeServices
import org.junit.Rule
import spock.lang.Specification

@CleanupTestDirectory(fieldName = "tmpDir")
@UsesNativeServices
class DefaultClasspathFingerprinterTest extends Specification {
    @Rule
    public final TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider(getClass())

    def stringInterner = Stub(StringInterner) {
        intern(_) >> { String s -> s }
    }
    def fileSystemAccess = TestFiles.fileSystemAccess()
    def snapshotter = new DefaultFileCollectionSnapshotter(fileSystemAccess, TestFiles.fileSystem())
    TestInMemoryIndexedCache<HashCode, HashCode> resourceHashesCache = new TestInMemoryIndexedCache<>(new HashCodeSerializer())
    def cacheService = new DefaultResourceSnapshotterCacheService(resourceHashesCache)
    def fingerprinter = new DefaultClasspathFingerprinter(
        cacheService,
        ResourceFilter.FILTER_NOTHING,
        ResourceEntryFilter.FILTER_NOTHING,
        PropertiesFileFilter.FILTER_NOTHING,
        stringInterner,
        LineEndingSensitivity.DEFAULT
    )

    def "directories and missing files are ignored"() {
        def emptyDir = file('root/emptyDir').createDir()
        def missingFile = file('some').createDir().file('does-not-exist')
        def missingRootFile = file('missing-root')

        when:
        def fileCollectionFingerprint = fingerprint(emptyDir.parentFile, missingFile.parentFile, missingRootFile)

        then:
        fileCollectionFingerprint.empty
    }

    def "root elements are unsorted, non-root elements are sorted amongst themselves"() {
        def rootFile1 = file("root1.txt") << "root1"
        def rootDir = file("dir").createDir()
        rootDir.file("file1.txt") << "file1"
        rootDir.file("file2.txt") << "file2"
        def rootFile2 = file("root2.txt") << "root2"

        when:
        def fileCollectionFingerprint = fingerprint(rootFile1, rootDir, rootFile2)

        then:
        fileCollectionFingerprint == [
            ['root1.txt', '', 'ccd9cdea88e7fad8d12818f51e9235e3'],
            ['file1.txt', 'file1.txt', 'ed1fbe2fc2ce1b4e2658e9cc02db3291'],
            ['file2.txt', 'file2.txt', 'd2bd2deafcab41e51d736360e2add532'],
            ['root2.txt', '', '1aab2283185edff4cae6b4914297f50e'],
        ]

        when:
        fileCollectionFingerprint = fingerprint(rootFile2, rootFile1, rootDir)
        then:
        fileCollectionFingerprint == [
            ['root2.txt', '', '1aab2283185edff4cae6b4914297f50e'],
            ['root1.txt', '', 'ccd9cdea88e7fad8d12818f51e9235e3'],
            ['file1.txt', 'file1.txt', 'ed1fbe2fc2ce1b4e2658e9cc02db3291'],
            ['file2.txt', 'file2.txt', 'd2bd2deafcab41e51d736360e2add532'],
        ]
    }

    def "fingerprints runtime classpath files"() {
        def zipFile = file('library.jar')
        file('zipContents').create {
            file('firstFile.txt').text = "Some text"
            file('secondFile.txt').text = "Second File"
            subdir {
                file('someOtherFile.log').text = "File in subdir"
            }
        }.zipTo(zipFile)
        def classes = file('classes').create {
            file('thirdFile.txt').text = "Third file"
            file('fourthFile.txt').text = "Fourth file"
            subdir {
                file('build.log').text = "File in subdir"
            }
        }

        when:
        def fileCollectionFingerprint = fingerprint(zipFile, classes)
        then:

        fileCollectionFingerprint == [
            ['library.jar', '', '9fc7a684d9211b57ea92bdb21d772e63'],
            ['fourthFile.txt', 'fourthFile.txt', '2b844b1593547b5b149e90b29228e8cd'],
            ['build.log', 'subdir/build.log', '460e431d1fd8e9236b412efba37be4c5'],
            ['thirdFile.txt', 'thirdFile.txt', 'cc6b00efe623fbf2ee23764693917505'],
        ]

        resourceHashesCache.keySet().size() == 1
        def key = resourceHashesCache.keySet().iterator().next()
        resourceHashesCache.getIfPresent(key).toString() == '9fc7a684d9211b57ea92bdb21d772e63'
    }

    def "detects moving of files in jars and directories"() {
        def zipFile = file('library.jar')
        file('zipContents').create {
            file('firstFile.txt').text = "Some text"
            subdir {}
        }.zipTo(zipFile)
        def classes = file('classes').create {
            file('thirdFile.txt').text = "Third file"
            subdir {}
        }

        when:
        def fileCollectionFingerprint = fingerprint(zipFile, classes)
        then:
        fileCollectionFingerprint == [
            ['library.jar', '', '29a00c2ac5692da34878feb98cb44cdb'],
            ['thirdFile.txt', 'thirdFile.txt', 'cc6b00efe623fbf2ee23764693917505'],
        ]

        when:
        file('zipContents/firstFile.txt').moveToDirectory(file('zipContents/subdir'))
        file('classes/thirdFile.txt').moveToDirectory(file('classes/subdir'))
        file('zipContents').zipTo(zipFile)

        fileCollectionFingerprint = fingerprint(zipFile, classes)

        then:
        fileCollectionFingerprint == [
            ['library.jar', '', '56fd59484c3b68c2a953e5a17cf816af'],
            ['thirdFile.txt', 'subdir/thirdFile.txt', 'cc6b00efe623fbf2ee23764693917505'],
        ]
    }

    def "cache hashes for jar files"() {
        def zipFile = file('library.jar')
        file('zipContents').create {
            file('firstFile.txt').text = "Some text"
            file('secondFile.txt').text = "Second File"
            subdir {
                file('someOtherFile.log').text = "File in subdir"
            }
        }.zipTo(zipFile)

        def zipFile2 = file('another-library.jar')
        file('anotherZipContents').create {
            file('thirdFile.txt').text = "third file"
            file('forthFile.txt').text = "forth file"
            subdir {
                file('someEvenOtherFile.log').text = "another file in subdir"
            }
        }.zipTo(zipFile2)

        when:
        def fileCollectionFingerprint = fingerprint(zipFile, zipFile2)

        then:
        fileCollectionFingerprint == [
            ['library.jar', '', '9fc7a684d9211b57ea92bdb21d772e63'],
            ['another-library.jar', '', 'a6147596a87057248a3cc33436458aef']
        ]
        resourceHashesCache.keySet().size() == 2
        def values = resourceHashesCache.keySet().collect { resourceHashesCache.getIfPresent(it).toString() } as Set
        values == ['9fc7a684d9211b57ea92bdb21d772e63', 'a6147596a87057248a3cc33436458aef'] as Set

        when:
        fileCollectionFingerprint = fingerprint(zipFile, zipFile2)
        values = resourceHashesCache.keySet().collect { resourceHashesCache.getIfPresent(it).toString() } as Set

        then:
        fileCollectionFingerprint == [
            ['library.jar', '', '9fc7a684d9211b57ea92bdb21d772e63'],
            ['another-library.jar', '', 'a6147596a87057248a3cc33436458aef']
        ]
        resourceHashesCache.keySet().size() == 2
        values == ['9fc7a684d9211b57ea92bdb21d772e63', 'a6147596a87057248a3cc33436458aef'] as Set
    }

    def "empty jars are not ignored"() {
        def emptyJar = file('empty.jar')
        file('emptyDir').createDir().zipTo(emptyJar)
        def nonEmptyJar = file('nonEmpty.jar')
        file('nonEmptyDir').create{
            file('some-resource').text = 'not-empty'
        }.zipTo(nonEmptyJar)

        when:
        def classpathFingerprint = fingerprint(emptyJar, nonEmptyJar)
        then:
        classpathFingerprint == [
            ['empty.jar', '', '7f0e8bdf6acd4193c9f7dad3007d07df'],
            ['nonEmpty.jar', '', '1a06709b2bb411921af59ca7a541c8f6']
        ]
    }

    def fingerprint(TestFile... classpath) {
        fileSystemAccess.invalidate(classpath.collect { it.absolutePath })
        def snapshot = snapshotter.snapshot(files(classpath))
        def fileCollectionFingerprint = fingerprinter.fingerprint(snapshot, null)
        return fileCollectionFingerprint.fingerprints.collect { String path, FileSystemLocationFingerprint fingerprint ->
            [new File(path).getName(), fingerprint.normalizedPath, fingerprint.normalizedContentHash.toString()]
        }
    }

    def files(File... files) {
        return TestFiles.fixed(files)
    }

    def file(Object... path) {
        tmpDir.file(path)
    }
}
