/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.hash

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import static java.lang.Thread.currentThread
import static java.util.concurrent.CompletableFuture.supplyAsync
import static java.util.concurrent.Executors.newFixedThreadPool

class HashingTest extends Specification {
    @TempDir
    File tempDir

    def 'default hash function is XXH3-128'() {
        expect:
        Hashing.defaultFunction().is(Hashing.xxh3_128())
        Hashing.defaultFunction().algorithm == 'XXH3-128'
        Hashing.defaultFunction().hexDigits == 32
    }

    def 'XXH3-128 matches reference hashes'() {
        given:
        def bytes = input.getBytes(StandardCharsets.UTF_8)
        def file = new File(tempDir, 'input')
        file.bytes = bytes
        def hasher = Hashing.newPrimitiveHasher()
        hasher.putBytes(bytes)

        expect:
        Hashing.hashBytes(bytes).toString() == expected
        Hashing.hashString(input).toString() == expected
        Hashing.hashStream(new ByteArrayInputStream(bytes)).toString() == expected
        Hashing.hashFile(file).toString() == expected
        hasher.hash().toString() == expected

        where:
        input   | expected
        ''      | '99aa06d3014798d86001c324468d497f'
        'abc'   | '06b05ab6733a618578af5f94892f3950'
        'hello' | 'b5e9c1ad071b3e7fc779cfaa5e523818'
    }

    def 'streaming XXH3-128 agrees with one-shot hashing across block boundaries'() {
        given:
        def bytes = new byte[length]
        new Random(42).nextBytes(bytes)
        def hasher = Hashing.newPrimitiveHasher()
        def byteHasher = Hashing.newPrimitiveHasher()
        for (int offset = 0; offset < length; offset += 37) {
            hasher.putBytes(bytes, offset, Math.min(37, length - offset))
        }
        bytes.each { byteHasher.putByte(it) }

        expect:
        hasher.hash() == Hashing.hashBytes(bytes)
        byteHasher.hash() == Hashing.hashBytes(bytes)
        Hashing.hashStream(new ByteArrayInputStream(bytes)) == Hashing.hashBytes(bytes)

        where:
        length << [0, 1, 3, 4, 8, 9, 16, 17, 32, 33, 64, 65, 128, 129, 240, 241, 255, 256, 257, 1023, 1024, 1025, 8191, 8192, 8193, 65536]
    }

    def 'XXH3-128 preserves primitive encodings'() {
        given:
        def text = 'Grüße 😀'
        def bytes = text.getBytes(StandardCharsets.UTF_8)
        def hash = HashCode.fromString('00112233445566778899aabbccddeeff')
        def nanBits = 0x7ff8000000000001L
        def buffer = ByteBuffer.allocate(1 + 4 + 8 + 8 + 2 + bytes.length + 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put((byte) 0x81).putInt(-123).putLong(Long.MIN_VALUE).putLong(nanBits)
        buffer.put((byte) 1).put((byte) 0).put(bytes).put(hash.toByteArray())
        def hasher = Hashing.newPrimitiveHasher()
        hasher.putByte((byte) 0x81)
        hasher.putInt(-123)
        hasher.putLong(Long.MIN_VALUE)
        hasher.putDouble(Double.longBitsToDouble(nanBits))
        hasher.putBoolean(true)
        hasher.putBoolean(false)
        hasher.putString(text)
        hasher.putHash(hash)

        expect:
        hasher.hash() == Hashing.hashBytes(buffer.array())
        Hashing.hashString(text) == Hashing.hashBytes(bytes)
    }

    def 'explicit message digest algorithms are unchanged'() {
        given:
        def bytes = 'hello'.getBytes(StandardCharsets.UTF_8)

        expect:
        function.hashBytes(bytes) == HashCode.fromBytes(MessageDigest.getInstance(function.algorithm).digest(bytes))

        where:
        function << [Hashing.md5(), Hashing.sha1(), Hashing.sha256(), Hashing.sha512()]
    }

    def 'cannot append to a finalized primitive hasher'() {
        given:
        def hasher = Hashing.newPrimitiveHasher()
        hasher.hash()

        when:
        append(hasher)

        then:
        thrown(IllegalStateException)

        where:
        append << [
            { it.putByte((byte) 1) },
            { it.putBytes(new byte[0]) },
            { it.putBytes(new byte[1], 0, 1) },
            { it.putInt(1) },
            { it.putLong(1L) },
            { it.putDouble(1.0d) },
            { it.putBoolean(true) },
            { it.putString('') },
            { it.putHash(HashCode.fromString('00112233445566778899aabbccddeeff')) }
        ]
    }

    def 'cannot call hash multiple times'() {
        given:
        def hasher = Hashing.newHasher()
        hasher.putInt(1)
        hasher.hash()

        when:
        hasher.hash()

        then:
        thrown(IllegalStateException)
    }

    def 'hashers can overlap'() {
        when:
        def hasher1 = Hashing.newHasher()
        hasher1.putInt(1)

        and:
        def hasher2 = Hashing.newHasher()
        hasher2.putInt(1)

        then: "closing them in reverse order"
        def hash2 = hasher2.hash()
        def hash1 = hasher1.hash()

        then:
        hash2 == hash1
    }

    def 'hasher works without calling final hash method'() {
        given:
        def value = ('a'..'z').join()

        when:
        def hasher1 = Hashing.newHasher()
        hasher1.putString(value)
        def hash = hasher1.hash()

        and:
        def hasher2 = Hashing.newHasher()
        hasher2.putString(value)
        // no call to hasher2.hash()

        and:
        def hasher3 = Hashing.newHasher()
        hasher3.putString(value)
        def hash3 = hasher3.hash()

        then:
        hash == hash3
    }

    def 'hasher can be used from multiple threads'() {
        given:
        def threadRange = 1..100

        when:
        def hashes = threadRange.collect {
            supplyAsync({ Hashing.hashString(currentThread().name) }, newFixedThreadPool(threadRange.size()))
        }*.join().toSet()

        then:
        hashes.size() == threadRange.size()
    }

    def 'null does not collide with other values'() {
        expect:
        def hasher = Hashing.newHasher()
        hasher.putNull()
        def hash = hasher.hash()
        hash != Hashing.hashString("abc")
    }

    def 'hash collision for bytes'() {
        def left = [[1, 2, 3], [4, 5]]
        def right = [[1, 2], [3, 4, 5]]
        expect:
        hashKey(left) != hashKey(right)
    }

    def 'hash collision for strings'() {
        expect:
        hashStrings(["abc", "de"]) != hashStrings(["ab", "cde"])
    }

    def hashStrings(List<String> strings) {
        def hasher = Hashing.newHasher()
        strings.each { hasher.putString(it) }
        hasher.hash()
    }

    def hashKey(List<List<Integer>> bytes) {
        def hasher = Hashing.newHasher()
        bytes.each {
            if (it.size() == 1) {
                hasher.putByte(it[0] as byte)
            } else {
                hasher.putBytes(it as byte[])
            }
        }
        hasher.hash()
    }
}
