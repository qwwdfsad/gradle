#!/usr/bin/env python3
# Copyright 2026 Gradle and contributors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Metadata-only lookahead benchmark; uses only the Python standard library."""

import argparse
import concurrent.futures
import hashlib
import http.client
import io
import json
import math
import os
from pathlib import Path
import shutil
import socket
import statistics
import subprocess
import tempfile
import threading
import time
from contextlib import contextmanager, redirect_stderr
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


GROUP = "benchmark.lookahead"
MARKER = "LOOKAHEAD_RESULT="
PROPERTY = "org.gradle.internal.resolve.metadata.lookahead"
BUILD = r'''
import groovy.json.JsonOutput
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

repositories {
    maven {
        url = uri('__URL__')
        allowInsecureProtocol = true
        metadataSources {
            mavenPom()
            ignoreGradleMetadataRedirection()
        }
    }
}
configurations { graph { canBeConsumed = false; canBeResolved = true } }
dependencies {
__DEPENDENCIES__
}
tasks.register('resolveGraph') {
    doLast {
        def start = System.nanoTime()
        def result = configurations.graph.incoming.resolutionResult
        def components = result.allComponents
        def dependencies = result.allDependencies
        def elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        def unresolved = dependencies.count { it instanceof UnresolvedDependencyResult }
        def ids = components.collect { it.id.displayName }.sort()
        def edges = dependencies.findAll { !(it instanceof UnresolvedDependencyResult) }.collect {
            [it.from.id.displayName, it.requested.displayName, it.selected.id.displayName]
        }.sort { a, b -> JsonOutput.toJson(a) <=> JsonOutput.toJson(b) }
        println 'LOOKAHEAD_RESULT=' + JsonOutput.toJson([
            elapsed_ms: elapsedMs, components: ids, edges: edges, unresolved: unresolved
        ])
    }
}
'''


def fixture(width, depth):
    poms = {}

    def pom(name, version, dependencies):
        project = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for key, value in (("modelVersion", "4.0.0"), ("groupId", GROUP),
                           ("artifactId", name), ("version", version), ("packaging", "pom")):
            ET.SubElement(project, key).text = value
        deps = ET.SubElement(project, "dependencies")
        for dep_name, dep_version in dependencies:
            dep = ET.SubElement(deps, "dependency")
            for key, value in (("groupId", GROUP), ("artifactId", dep_name), ("version", dep_version)):
                ET.SubElement(dep, key).text = value
        path = "/{}/{}/{}/{}-{}.pom".format(GROUP.replace(".", "/"), name, version, name, version)
        poms[path] = ET.tostring(project, encoding="utf-8", xml_declaration=True)

    for version in ("1", "2"):
        pom("common", version, [])
    roots = [("common", "2")]
    expected = {GROUP + ":common:2"}
    for chain in range(width):
        roots.append(("chain-{}-0".format(chain), "1"))
        for level in range(depth):
            name = "chain-{}-{}".format(chain, level)
            deps = [("chain-{}-{}".format(chain, level + 1), "1")] if level + 1 < depth else [
                ("common", str(1 + chain % 2))]
            pom(name, "1", deps)
            expected.add(GROUP + ":" + name + ":1")
    return poms, roots, expected


class Repository(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True

    def __init__(self, poms, latency_ms):
        self.poms = poms
        self.latency = latency_ms / 1000
        self.lock = threading.Lock()
        self.reset()
        super().__init__(("127.0.0.1", 0), Handler)

    def reset(self):
        with self.lock:
            self.requests = []
            self.active = 0
            self.peak = 0
            self.connections = 0

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(2)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with self.lock:
            self.connections += 1
        return sock, address

    def snapshot(self):
        with self.lock:
            return dict(request_count=len(self.requests), peak_concurrency=self.peak,
                        connections=self.connections, requests=list(self.requests))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.respond(False)

    def do_HEAD(self):
        self.respond(True)

    def respond(self, head):
        server = self.server
        path = urlsplit(self.path).path
        body = server.poms.get(path)
        status = 200 if body is not None else 404
        with server.lock:
            server.active += 1
            server.peak = max(server.peak, server.active)
            server.requests.append(dict(method=self.command, path=path, status=status))
        try:
            time.sleep(server.latency)
            payload = body if body is not None else b"Not found\n"
            self.send_response(status)
            self.send_header("Content-Type", "application/xml" if status == 200 else "text/plain")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if not head:
                self.wfile.write(payload)
                self.wfile.flush()
        finally:
            with server.lock:
                server.active -= 1


@contextmanager
def repository(poms, latency_ms):
    server = Repository(poms, latency_ms)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def parse_result(output, expected):
    lines = [line[len(MARKER):] for line in output.splitlines() if line.startswith(MARKER)]
    if len(lines) != 1:
        raise ValueError("Expected exactly one graph result in the Gradle log")
    result = json.loads(lines[0])
    if result["unresolved"] != 0:
        raise ValueError("Graph contains unresolved dependencies")
    selected = {item for item in result["components"] if item.startswith(GROUP + ":")}
    if selected != expected:
        raise ValueError("Unexpected selected graph: {}".format(sorted(selected)))
    if not math.isfinite(result["elapsed_ms"]) or result["elapsed_ms"] < 0:
        raise ValueError("Invalid graph timing")
    graph = dict(components=sorted(result["components"]), edges=sorted(result["edges"]))
    result["fingerprint"] = hashlib.sha256(json.dumps(graph, sort_keys=True).encode()).hexdigest()
    return result


def invocation(gradle, project, home, enabled, lookahead_depth=2, max_pending=32, max_candidates=1024):
    return [gradle, "--no-daemon", "--no-configuration-cache",
            "--console=plain", "--stacktrace", "--gradle-user-home", str(home),
            "--project-dir", str(project), "-D{}={}".format(PROPERTY, str(enabled).lower()),
            "-D{}.depth={}".format(PROPERTY, lookahead_depth),
            "-D{}.maxPending={}".format(PROPERTY, max_pending),
            "-D{}.maxCandidates={}".format(PROPERTY, max_candidates), "resolveGraph"]


def benchmark_modes(args):
    if args.baseline_gradle is not None:
        return (("baseline", args.baseline_gradle, True), ("experimental", args.gradle, True))
    return (("off", args.gradle, False), ("on", args.gradle, True))


def benchmark(args, directory):
    poms, roots, expected = fixture(args.width, args.depth)
    modes = benchmark_modes(args)
    for mode, gradle, enabled in modes:
        print("{}: gradle={}, lookahead={}, depth={}, maxPending={}, maxCandidates={}".format(
            mode, gradle, str(enabled).lower(), args.lookahead_depth, args.max_pending, args.max_candidates), flush=True)
    for path, body in poms.items():
        target = directory / "repository" / path.lstrip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
    results = []
    reference = None
    with repository(poms, args.latency_ms) as server:
        build = BUILD.replace("__URL__", "http://127.0.0.1:{}/".format(server.server_port))
        build = build.replace("__DEPENDENCIES__", "\n".join(
            "    graph '{}:{}:{}'".format(GROUP, name, version) for name, version in roots))
        for run in range(1, args.runs + 1):
            order = modes if run % 2 else modes[::-1]
            projects = {}
            for mode, gradle, enabled in order:
                base = directory / "run-{}".format(run) / mode
                project, home = base / "project", base / "gradle-user-home"
                project.mkdir(parents=True)
                home.mkdir()
                (project / "settings.gradle").write_text("rootProject.name = 'metadata-lookahead'\n", encoding="utf-8")
                (project / "build.gradle").write_text(build, encoding="utf-8")
                projects[mode] = (base, project, home)
            for temperature in ("cold", "warm"):
                for mode, gradle, enabled in order:
                    base, project, home = projects[mode]
                    command = invocation(gradle, project, home, enabled,
                                         args.lookahead_depth, args.max_pending, args.max_candidates)
                    (base / (temperature + "-command.json")).write_text(json.dumps(command, indent=2), encoding="utf-8")
                    server.reset()
                    log = base / (temperature + ".log")
                    env = dict(os.environ, GRADLE_USER_HOME=str(home))
                    try:
                        with log.open("w", encoding="utf-8") as output:
                            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                                           env=env, check=True, timeout=600)
                    finally:
                        http = server.snapshot()
                        (base / (temperature + "-http.json")).write_text(json.dumps(http, indent=2), encoding="utf-8")
                    result = parse_result(log.read_text(encoding="utf-8"), expected)
                    if any(not item["path"].endswith(".pom") for item in http["requests"]):
                        raise ValueError("Unexpected non-POM request; see " + str(base))
                    graph = (sorted(result["components"]), sorted(result["edges"]))
                    if reference is not None and graph != reference:
                        raise ValueError("Selected graphs differ; see " + str(log))
                    reference = graph
                    result.update(run=run, mode=mode, temperature=temperature, http=http)
                    results.append(result)
                    (directory / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
                    print("run={} {} {}: {:.3f} ms, requests={}, peak={}, connections={}, unresolved={}, sha256={}".format(
                        run, mode, temperature, result["elapsed_ms"], http["request_count"],
                        http["peak_concurrency"], http["connections"], result["unresolved"], result["fingerprint"]), flush=True)
                    print("  components: " + ", ".join(result["components"]), flush=True)
    print("All selected graphs match (components and requested/selected edges).")
    for temperature in ("cold", "warm"):
        for mode, gradle, enabled in modes:
            samples = [r["elapsed_ms"] for r in results if r["temperature"] == temperature and r["mode"] == mode]
            print("{} {} median: {:.3f} ms".format(mode, temperature, statistics.median(samples)))


def self_test():
    poms, roots, expected = fixture(2, 3)
    assert len(poms) == 8 and len(roots) == 3 and len(expected) == 7
    for body in poms.values():
        ET.fromstring(body)
    with repository(poms, 30) as server:
        path = next(iter(poms))
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        try:
            for method, target, status in (("GET", path, 200), ("HEAD", path, 200), ("GET", "/missing", 404)):
                connection.request(method, target)
                response = connection.getresponse()
                body = response.read()
                assert response.status == status
                assert int(response.getheader("Content-Length")) == (len(poms[path]) if method == "HEAD" else len(body))
            assert server.snapshot()["connections"] == 1
        finally:
            connection.close()
        barrier = threading.Barrier(4)

        def fetch(_):
            client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
            try:
                barrier.wait(timeout=5)
                client.request("GET", path)
                response = client.getresponse()
                assert response.read() == poms[path]
            finally:
                client.close()

        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(fetch, range(4)))
        metrics = server.snapshot()
        assert metrics["request_count"] == 7 and metrics["peak_concurrency"] > 1
        assert metrics["connections"] == 5
    sample = dict(elapsed_ms=1.0, unresolved=0, components=sorted(expected), edges=[])
    assert parse_result(MARKER + json.dumps(sample), expected)["fingerprint"]
    for invalid in (dict(sample, unresolved=1), dict(sample, components=[])):
        try:
            parse_result(MARKER + json.dumps(invalid), expected)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid graph accepted")
    command = invocation("gradle", Path("project"), Path("home"), True)
    assert command[1:3] == ["--no-daemon", "--no-configuration-cache"]
    assert "--priority" not in command
    assert "-D" + PROPERTY + "=true" in command
    for suffix in (".depth=2", ".maxPending=32", ".maxCandidates=1024"):
        assert "-D" + PROPERTY + suffix in command
    parser = argument_parser()
    args = parser.parse_args(["--gradle", "new-gradle"])
    assert (args.lookahead_depth, args.max_pending, args.max_candidates) == (2, 32, 1024)
    assert benchmark_modes(args) == (("off", "new-gradle", False), ("on", "new-gradle", True))
    args = parser.parse_args(["--gradle", "new-gradle", "--baseline-gradle", "old-gradle",
                              "--lookahead-depth", "3", "--max-pending", "7", "--max-candidates", "19"])
    assert benchmark_modes(args) == (("baseline", "old-gradle", True), ("experimental", "new-gradle", True))
    for baseline in (None, "old-gradle"):
        args.baseline_gradle = baseline
        for mode, gradle, enabled in benchmark_modes(args):
            home = Path(mode) / "home"
            command = invocation(gradle, Path("project"), home, enabled,
                                 args.lookahead_depth, args.max_pending, args.max_candidates)
            assert command[0] == gradle
            assert command[command.index("--gradle-user-home") + 1] == str(home)
            assert "-D{}={}".format(PROPERTY, str(enabled).lower()) in command
            for suffix in (".depth=3", ".maxPending=7", ".maxCandidates=19"):
                assert "-D" + PROPERTY + suffix in command
    for option in ("--lookahead-depth", "--max-pending", "--max-candidates"):
        assert getattr(parser.parse_args([option, "1"]), option[2:].replace("-", "_")) == 1
        for invalid in ("0", "-1", "1.5", "invalid"):
            with redirect_stderr(io.StringIO()):
                try:
                    parser.parse_args([option, invalid])
                except SystemExit as error:
                    assert error.code == 2
                else:
                    raise AssertionError("Invalid positive integer accepted: " + option)
    print("Python self-checks passed (no Gradle invocation).")


def positive_int(value):
    try:
        number = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be a positive integer")
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", help="Installed custom Gradle executable (not the repository wrapper)")
    parser.add_argument("--baseline-gradle", help="Baseline Gradle executable; compares baseline/experimental with lookahead enabled in both instead of off/on")
    parser.add_argument("--lookahead-depth", type=positive_int, default=2, help="Lookahead depth in both modes (default: 2)")
    parser.add_argument("--max-pending", type=positive_int, default=32, help="Maximum pending lookahead requests in both modes (default: 32)")
    parser.add_argument("--max-candidates", type=positive_int, default=1024, help="Maximum lookahead candidates in both modes (default: 1024)")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--latency-ms", type=float, default=50)
    parser.add_argument("--width", type=int, default=8)
    parser.add_argument("--depth", type=int, default=4)
    parser.add_argument("--self-test", action="store_true", help="Check Python fixture/server/validation without Gradle")
    return parser


def main():
    parser = argument_parser()
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.gradle:
        parser.error("--gradle is required unless --self-test is used")
    if min(args.runs, args.width, args.depth) < 1 or not math.isfinite(args.latency_ms) or args.latency_ms < 0:
        parser.error("runs, width and depth must be positive; latency must be finite and nonnegative")
    for option in ("gradle", "baseline_gradle"):
        value = getattr(args, option)
        if value is not None:
            executable = shutil.which(value)
            if executable is None:
                parser.error("Gradle executable not found for --{}: {}".format(option.replace("_", "-"), value))
            setattr(args, option, str(Path(executable).resolve()))
    parent = Path(__file__).resolve().parents[1] / "build" / "metadata-lookahead-benchmark"
    parent.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    print("Retaining fixtures, caches, commands and logs in " + str(directory), flush=True)
    (directory / "parameters.json").write_text(json.dumps(vars(args), indent=2), encoding="utf-8")
    benchmark(args, directory)


if __name__ == "__main__":
    main()