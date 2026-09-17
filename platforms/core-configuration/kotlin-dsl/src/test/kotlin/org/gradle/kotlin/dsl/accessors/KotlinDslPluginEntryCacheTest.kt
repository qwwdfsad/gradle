/*
 * Copyright 2026 Gradle and contributors.
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

package org.gradle.kotlin.dsl.accessors

import org.gradle.cache.CacheBuilder
import org.gradle.cache.CacheDecorator
import org.gradle.cache.IndexedCache
import org.gradle.cache.IndexedCacheParameters
import org.gradle.cache.PersistentCache
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory
import org.gradle.cache.scopes.BuildTreeScopedCacheBuilderFactory
import org.gradle.internal.hash.FileHasher
import org.gradle.internal.hash.HashCode
import org.gradle.internal.hash.Hashing
import org.gradle.internal.service.ServiceRegistryBuilder
import org.gradle.kotlin.dsl.internal.sharedruntime.codegen.PluginEntry
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import org.mockito.Answers.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.util.function.Supplier


class KotlinDslPluginEntryCacheTest {

    @Test
    fun `service uses the injected file hasher as the cache key and defers plugin extraction`() {
        val jar = File("plugins.jar")
        val hash = Hashing.hashString("jar contents")
        val entries = listOf(PluginEntry("my-plugin", "MyPlugin"))
        val fileHasher = mock<FileHasher> {
            on { hash(jar) } doReturn hash
        }
        val indexedCache = mock<IndexedCache<HashCode, List<PluginEntry>>> {
            on { get(eq(hash), any<Supplier<List<PluginEntry>>>()) } doReturn entries
        }
        val persistentCache = mock<PersistentCache>()
        whenever(persistentCache.createIndexedCache(any<IndexedCacheParameters<HashCode, List<PluginEntry>>>()))
            .thenReturn(indexedCache)
        val cacheBuilder = mock<CacheBuilder>(defaultAnswer = RETURNS_SELF) {
            on { open() } doReturn persistentCache
        }
        val cacheBuilderFactory = mock<BuildTreeScopedCacheBuilderFactory> {
            on { createCacheBuilder("kotlin-dsl-plugin-entries") } doReturn cacheBuilder
        }
        val decorator = mock<CacheDecorator>()
        val decoratorFactory = mock<InMemoryCacheDecoratorFactory> {
            on { decorator(any(), any()) } doReturn decorator
        }
        val producer = mock<(File) -> List<PluginEntry>> {
            on { invoke(jar) } doReturn entries
        }

        ServiceRegistryBuilder.builder()
            .provider { registration ->
                registration.add(BuildTreeScopedCacheBuilderFactory::class.java, cacheBuilderFactory)
                registration.add(InMemoryCacheDecoratorFactory::class.java, decoratorFactory)
                registration.add(FileHasher::class.java, fileHasher)
            }
            .provider(BuildTreeServices)
            .build().use { services ->
                val cache = services.get(KotlinDslPluginEntryCache::class.java)

                assertThat(cache.computeIfAbsent(jar, producer), sameInstance(entries))
                verify(fileHasher).hash(jar)
                verifyNoInteractions(producer)
                val supplier = argumentCaptor<Supplier<List<PluginEntry>>>()
                verify(indexedCache).get(eq(hash), supplier.capture())
                assertThat(supplier.firstValue.get(), sameInstance(entries))
                verify(producer).invoke(jar)
            }

        verify(persistentCache).close()
    }
}