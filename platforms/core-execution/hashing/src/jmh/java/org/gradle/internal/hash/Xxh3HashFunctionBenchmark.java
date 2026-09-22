/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.internal.hash;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Compares XXH3-128 with MD5 on large, in-memory inputs, excluding disk I/O and input generation.
 * <p>
 * Run with {@code ./gradlew :hashing:jmh}. Each operation hashes the entire input; sizes use decimal MB.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(2)
@Threads(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class Xxh3HashFunctionBenchmark {

    @Param({"10", "50", "100"})
    public int sizeMb;

    @Param({"md5", "xxh3_128"})
    public String algorithm;

    private byte[] input;
    private HashFunction hashFunction;

    @Setup(Level.Trial)
    public void setup() {
        input = new byte[sizeMb * 1_000_000];
        new Random(1234L).nextBytes(input);
        switch (algorithm) {
            case "md5":
                hashFunction = Hashing.md5();
                break;
            case "xxh3_128":
                hashFunction = Hashing.xxh3_128();
                break;
            default:
                throw new IllegalArgumentException("Unknown hash algorithm: " + algorithm);
        }
    }

    @Benchmark
    public HashCode hashBytes() {
        return hashFunction.hashBytes(input);
    }

    @Benchmark
    public HashCode hashStream() throws IOException {
        return hashFunction.hashStream(new ByteArrayInputStream(input));
    }
}