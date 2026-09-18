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
import re
import shutil
import socket
import statistics
import subprocess
import tempfile
import threading
import time
from contextlib import contextmanager, redirect_stderr
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


GROUP = "benchmark.lookahead"
MARKER = "LOOKAHEAD_RESULT="
PROPERTY = "org.gradle.internal.resolve.metadata.lookahead"
MODULE_MARKER = "do_not_remove: published-with-gradle-metadata"
BUILD = r'''
import groovy.json.JsonOutput
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

repositories {
    maven {
        url = uri('__URL__')
        allowInsecureProtocol = true
        metadataSources {
            mavenPom()
            __REDIRECTION__
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
        dependencies.findAll { it instanceof UnresolvedDependencyResult }.each { it.failure.printStackTrace() }
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


def metadata_path(name, version, extension="pom"):
    return "/{}/{}/{}/{}-{}.{}".format(GROUP.replace(".", "/"), name, version, name, version, extension)


def component(name, version):
    return "{}:{}:{}".format(GROUP, name, version)


def fixture(width, depth, metadata="pom"):
    resources = {}
    edges = []

    def pom(name, version, dependencies=(), managed=(), imports=(), module=False):
        project = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for key, value in (("modelVersion", "4.0.0"), ("groupId", GROUP),
                           ("artifactId", name), ("version", version), ("packaging", "pom")):
            ET.SubElement(project, key).text = value

        def add_dependencies(parent, entries, imported=False):
            for dep_name, dep_version in entries:
                dep = ET.SubElement(parent, "dependency")
                for key, value in (("groupId", GROUP), ("artifactId", dep_name), ("version", dep_version)):
                    if value is not None:
                        ET.SubElement(dep, key).text = value
                if imported:
                    ET.SubElement(dep, "type").text = "pom"
                    ET.SubElement(dep, "scope").text = "import"

        add_dependencies(ET.SubElement(project, "dependencies"), dependencies)
        if managed or imports:
            management = ET.SubElement(ET.SubElement(project, "dependencyManagement"), "dependencies")
            add_dependencies(management, managed)
            add_dependencies(management, imports, imported=True)
        if module:
            project.append(ET.Comment(" " + MODULE_MARKER + " "))
            resources[metadata_path(name, version, "module")] = json.dumps({
                "formatVersion": "1.1",
                "component": {"group": GROUP, "module": name, "version": version},
                "variants": [{"name": "runtime", "dependencies": [
                    {"group": GROUP, "module": dep_name, "version": {"requires": dep_version}}
                    for dep_name, dep_version in dependencies
                ]}]
            }, indent=2).encode()
        resources[metadata_path(name, version)] = ET.tostring(project, encoding="utf-8", xml_declaration=True)

    if metadata == "bom":
        roots = [("root", "1")]
        expected = {component("root", "1"), component("common", "1")}
        for version in ("1", "2"):
            pom("common", version)
        imports = [("bom-{}-0".format(chain), "1") for chain in range(width)]
        leaves = [("leaf-{}".format(chain), None) for chain in range(width)]
        pom("root", "1", leaves + [("common", None)], imports=imports)
        for chain in range(width):
            leaf = "leaf-{}".format(chain)
            pom(leaf, "1")
            expected.add(component(leaf, "1"))
            for level in range(depth):
                name = "bom-{}-{}".format(chain, level)
                if level + 1 < depth:
                    pom(name, "1", imports=[("bom-{}-{}".format(chain, level + 1), "1")])
                else:
                    # The first imported BOM wins the conflicting common version.
                    pom(name, "1", managed=[(leaf, "1"), ("common", "1" if chain == 0 else "2")])
        for name, _ in leaves + [("common", None)]:
            selected = component(name, "1")
            edges.append([component("root", "1"), selected, selected])
    else:
        for version in ("1", "2"):
            pom("common", version, module=metadata == "module" or (metadata == "mixed" and version == "2"))
        roots = [("common", "2")]
        expected = {component("common", "2")}
        for chain in range(width):
            roots.append(("chain-{}-0".format(chain), "1"))
            for level in range(depth):
                name = "chain-{}-{}".format(chain, level)
                deps = [("chain-{}-{}".format(chain, level + 1), "1")] if level + 1 < depth else [
                    ("common", str(1 + chain % 2))]
                pom(name, "1", deps, module=metadata == "module" or (metadata == "mixed" and (chain + level) % 2 == 0))
                expected.add(component(name, "1"))
                for dep_name, dep_version in deps:
                    edges.append([component(name, "1"), component(dep_name, dep_version),
                                  component(dep_name, "2" if dep_name == "common" else dep_version)])
    edges.extend([["<root>", component(name, version), component(name, version)] for name, version in roots])
    misses = {path[:-4] + ".module" for path in resources if path.endswith(".pom")
              and path[:-4] + ".module" not in resources} if metadata in ("mixed", "pom-only") else set()
    return resources, roots, expected, sorted(edges), misses


class Repository(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    request_queue_size = 128

    def __init__(self, resources, latency_ms):
        self.resources = resources
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
        body = server.resources.get(path)
        status = 200 if body is not None else 404
        with server.lock:
            server.active += 1
            server.peak = max(server.peak, server.active)
            server.requests.append(dict(method=self.command, path=path, status=status))
        try:
            time.sleep(server.latency)
            payload = body if body is not None else b"Not found\n"
            self.send_response(status)
            content_type = "application/json" if path.endswith(".module") else "application/xml"
            self.send_header("Content-Type", content_type if status == 200 else "text/plain")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if not head:
                self.wfile.write(payload)
                self.wfile.flush()
        finally:
            with server.lock:
                server.active -= 1


@contextmanager
def repository(resources, latency_ms):
    server = Repository(resources, latency_ms)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def parse_result(output, expected, expected_edges):
    lines = [line[len(MARKER):] for line in output.splitlines() if line.startswith(MARKER)]
    if len(lines) != 1:
        raise ValueError("Expected exactly one graph result in the Gradle log")
    result = json.loads(lines[0])
    if result["unresolved"] != 0:
        raise ValueError("Graph contains unresolved dependencies")
    selected = {item for item in result["components"] if item.startswith(GROUP + ":")}
    if selected != expected:
        raise ValueError("Unexpected selected graph: {}".format(sorted(selected)))
    normalized_edges = sorted([[source if source.startswith(GROUP + ":") else "<root>", requested, selected]
                               for source, requested, selected in result["edges"]])
    if normalized_edges != expected_edges:
        raise ValueError("Unexpected requested/selected edges: {}".format(normalized_edges))
    if not math.isfinite(result["elapsed_ms"]) or result["elapsed_ms"] < 0:
        raise ValueError("Invalid graph timing")
    graph = dict(components=sorted(result["components"]), edges=sorted(result["edges"]))
    result["fingerprint"] = hashlib.sha256(json.dumps(graph, sort_keys=True).encode()).hexdigest()
    return result


def invocation(gradle, project, home, enabled, lookahead_depth=2, max_pending=32, max_candidates=1024, properties=()):
    return [gradle, "--no-daemon", "--no-configuration-cache",
            "--console=plain", "--stacktrace", "--gradle-user-home", str(home),
            "--project-dir", str(project), "-D{}={}".format(PROPERTY, str(enabled).lower()),
            "-D{}.depth={}".format(PROPERTY, lookahead_depth),
            "-D{}.maxPending={}".format(PROPERTY, max_pending),
            "-D{}.maxCandidates={}".format(PROPERTY, max_candidates)] + [
                "-D{}={}".format(name, value) for name, value in dict(properties).items()] + ["resolveGraph"]


def mode_properties(args, mode):
    return args.baseline_property if mode in ("baseline", "off") else args.experimental_property


def summarize_requests(http, resources, allowed_misses):
    counts = Counter((item["method"], item["path"]) for item in http["requests"])
    http["duplicate_requests"] = [dict(method=method, path=path, count=count)
                                  for (method, path), count in sorted(counts.items()) if count > 1]
    http["missing_paths"] = dict(sorted(Counter(item["path"] for item in http["requests"] if item["status"] == 404).items()))
    http["unexpected_requests"] = [item for item in http["requests"] if item["method"] not in ("GET", "HEAD") or not (
        (item["path"] in resources and item["status"] == 200)
        or (item["path"] in allowed_misses and item["status"] == 404))]
    return http


def validate_requests(http):
    if http["unexpected_requests"]:
        raise ValueError("Unexpected metadata requests: " + json.dumps(http["unexpected_requests"]))


def benchmark_modes(args):
    if args.baseline_gradle is not None:
        return (("baseline", args.baseline_gradle, True), ("experimental", args.gradle, True))
    return (("off", args.gradle, False), ("on", args.gradle, True))


def benchmark(args, directory):
    resources, roots, expected, expected_edges, allowed_misses = fixture(args.width, args.depth, args.metadata)
    modes = benchmark_modes(args)
    for mode, gradle, enabled in modes:
        print("{}: gradle={}, lookahead={}, depth={}, maxPending={}, maxCandidates={}, metadata={}, properties={}".format(
            mode, gradle, str(enabled).lower(), args.lookahead_depth, args.max_pending, args.max_candidates,
            args.metadata, dict(mode_properties(args, mode))), flush=True)
    manifest = dict(resources=sorted(resources), allowed_missing_paths=sorted(allowed_misses),
                    expected_components=sorted(expected), expected_edges=expected_edges)
    (directory / "fixture.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    for path, body in resources.items():
        target = directory / "repository" / path.lstrip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
    results = []
    reference = None
    with repository(resources, args.latency_ms) as server:
        build = BUILD.replace("__URL__", "http://127.0.0.1:{}/".format(server.server_port))
        build = build.replace("__REDIRECTION__", "" if args.metadata in ("module", "mixed", "pom-only") else "ignoreGradleMetadataRedirection()")
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
                                         args.lookahead_depth, args.max_pending, args.max_candidates,
                                         mode_properties(args, mode))
                    (base / (temperature + "-command.json")).write_text(json.dumps(command, indent=2), encoding="utf-8")
                    server.reset()
                    log = base / (temperature + ".log")
                    env = dict(os.environ, GRADLE_USER_HOME=str(home))
                    try:
                        with log.open("w", encoding="utf-8") as output:
                            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                                           env=env, check=True, timeout=600)
                    finally:
                        http = summarize_requests(server.snapshot(), resources, allowed_misses)
                        (base / (temperature + "-http.json")).write_text(json.dumps(http, indent=2), encoding="utf-8")
                        print("run={} {} {} HTTP: missing={}, unexpected={}, duplicates={}".format(
                            run, mode, temperature, json.dumps(http["missing_paths"]),
                            json.dumps(http["unexpected_requests"]), json.dumps(http["duplicate_requests"])), flush=True)
                    validate_requests(http)
                    result = parse_result(log.read_text(encoding="utf-8"), expected, expected_edges)
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
    poms, roots, expected, edges, misses = fixture(2, 3)
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
    sample = dict(elapsed_ms=1.0, unresolved=0, components=sorted(expected), edges=edges)
    assert parse_result(MARKER + json.dumps(sample), expected, edges)["fingerprint"]
    for invalid in (dict(sample, unresolved=1), dict(sample, components=[]), dict(sample, edges=[])):
        try:
            parse_result(MARKER + json.dumps(invalid), expected, edges)
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
    assert args.metadata == "pom" and not args.experimental_property and not args.baseline_property
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
    fixture_self_test()
    property_self_test(parser)
    print("Python self-checks passed (fixtures/XML/JSON/properties/HTTP/graphs; no Gradle invocation).")


def fixture_self_test():
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

    def dependencies(project, location="m:dependencies/m:dependency"):
        return [(dep.findtext("m:artifactId", namespaces=namespace), dep.findtext("m:version", namespaces=namespace))
                for dep in project.findall(location, namespace)]

    for width, depth in ((1, 1), (2, 3), (3, 2)):
        reference = fixture(width, depth)
        for metadata in ("pom", "pom-only", "module", "mixed", "bom"):
            resources, roots, expected, edges, misses = fixture(width, depth, metadata)
            assert (resources, roots, expected, edges, misses) == fixture(width, depth, metadata)
            assert not set(resources) & misses
            assert all(path.endswith(".module") for path in misses)
            projects = {}
            for path, body in resources.items():
                assert path.endswith((".pom", ".module"))
                if path.endswith(".pom"):
                    project = ET.fromstring(body)
                    name = project.findtext("m:artifactId", namespaces=namespace)
                    version = project.findtext("m:version", namespaces=namespace)
                    assert project.findtext("m:groupId", namespaces=namespace) == GROUP
                    assert project.findtext("m:packaging", namespaces=namespace) == "pom"
                    assert path == metadata_path(name, version)
                    projects[name, version] = project
                    module_path = metadata_path(name, version, "module")
                    assert (MODULE_MARKER.encode() in body) == (module_path in resources)
                    if module_path in resources:
                        module = json.loads(resources[module_path])
                        assert module["formatVersion"] == "1.1"
                        assert module["component"] == dict(group=GROUP, module=name, version=version)
                        variant, = module["variants"]
                        assert not variant.get("files")
                        assert [(dep["module"], dep["version"]["requires"]) for dep in variant["dependencies"]] == dependencies(project)
                        assert all(dep["group"] == GROUP for dep in variant["dependencies"])
                else:
                    json.loads(body)
            if metadata != "bom":
                assert (roots, expected, edges) == (reference[1], reference[2], reference[3])
                if metadata == "pom":
                    assert len(resources) == width * depth + 2 and not misses
                elif metadata == "pom-only":
                    assert len(resources) == len(misses) == width * depth + 2
                elif metadata == "module":
                    assert len(resources) == 2 * (width * depth + 2) and not misses
                else:
                    assert misses and any(path.endswith(".module") for path in resources)
                reconstructed = [["<root>", component(name, version), component(name, version)] for name, version in roots]
                for (name, version), project in projects.items():
                    for dep_name, dep_version in dependencies(project):
                        reconstructed.append([component(name, version), component(dep_name, dep_version),
                                              component(dep_name, "2" if dep_name == "common" else dep_version)])
                assert sorted(reconstructed) == edges
            else:
                assert len(resources) == 3 + width * (depth + 1) and not misses

                def management(name):
                    project = projects[name, "1"]
                    managed = {}
                    entries = project.findall("m:dependencyManagement/m:dependencies/m:dependency", namespace)
                    for entry in entries:
                        target = entry.findtext("m:artifactId", namespaces=namespace)
                        version = entry.findtext("m:version", namespaces=namespace)
                        if entry.findtext("m:scope", namespaces=namespace) == "import":
                            assert entry.findtext("m:type", namespaces=namespace) == "pom" and version == "1"
                            for key, value in management(target).items():
                                managed.setdefault(key, value)
                        else:
                            managed[target] = version
                    return managed

                root = projects["root", "1"]
                imports = root.findall("m:dependencyManagement/m:dependencies/m:dependency", namespace)
                assert len(imports) == width
                managed = management("root")
                assert managed == dict([("leaf-{}".format(chain), "1") for chain in range(width)] + [("common", "1")])
                assert expected == {component("root", "1")} | {component(name, version) for name, version in managed.items()}
                reconstructed = [["<root>", component("root", "1"), component("root", "1")]]
                for name, version in dependencies(root):
                    assert version is None
                    selected = component(name, managed[name])
                    reconstructed.append([component("root", "1"), selected, selected])
                assert sorted(reconstructed) == edges
            sample = dict(elapsed_ms=1, unresolved=0, components=sorted(expected) + ["root project :"],
                          edges=[["root project :" if source == "<root>" else source, requested, selected]
                                 for source, requested, selected in edges])
            assert parse_result(MARKER + json.dumps(sample), expected, edges)["fingerprint"]

    resources, _, _, _, misses = fixture(2, 2, "mixed")
    path, missing = next(iter(resources)), next(iter(misses))
    requests = [dict(method="GET", path=path, status=200)] * 2 + [dict(method="GET", path=missing, status=404)]
    summary = summarize_requests(dict(requests=requests), resources, misses)
    validate_requests(summary)
    assert summary["missing_paths"] == {missing: 1}
    assert summary["duplicate_requests"] == [dict(method="GET", path=path, count=2)]
    for unexpected in (dict(method="GET", path="/artifact.jar", status=404),
                       dict(method="GET", path="/unknown.pom", status=404),
                       dict(method="GET", path=path, status=404),
                       dict(method="GET", path=missing, status=200),
                       dict(method="POST", path=path, status=200)):
        summary = summarize_requests(dict(requests=[unexpected]), resources, misses)
        assert summary["unexpected_requests"] == [unexpected]
        try:
            validate_requests(summary)
        except ValueError:
            pass
        else:
            raise AssertionError("Unexpected HTTP request accepted")
    assert Repository.request_queue_size == 128


def property_self_test(parser):
    bom = "org.gradle.internal.resolve.metadata.parallelBom"
    redirect = "org.gradle.internal.resolve.metadata.parallelRedirect"
    for baseline in ([], ["--baseline-gradle", "old-gradle"]):
        args = parser.parse_args(["--gradle", "new-gradle", "--experimental-property", bom + "=true",
                                  "--experimental-property", redirect + "=false",
                                  "--experimental-property", redirect + "=true",
                                  "--baseline-property", bom + "=false"] + baseline)
        for mode, gradle, enabled in benchmark_modes(args):
            properties = mode_properties(args, mode)
            command = invocation(gradle, Path("project"), Path(mode) / "home", enabled, properties=properties)
            if mode in ("experimental", "on"):
                assert "-D" + bom + "=true" in command and "-D" + bom + "=false" not in command
                assert "-D" + redirect + "=true" in command and "-D" + redirect + "=false" not in command
            else:
                assert "-D" + bom + "=false" in command and "-D" + bom + "=true" not in command
                assert not any(argument.startswith("-D" + redirect + "=") for argument in command)
    assert system_property("name=value=with=equals") == ("name", "value=with=equals")
    for option in ("--experimental-property", "--baseline-property"):
        for invalid in ("missing", "=value", "name=", "bad name=value", "-Dname=value", PROPERTY + "=false",
                        PROPERTY + ".depth=3", PROPERTY + ".maxPending=7", PROPERTY + ".maxCandidates=19"):
            with redirect_stderr(io.StringIO()):
                try:
                    parser.parse_args([option + "=" + invalid])
                except SystemExit as error:
                    assert error.code == 2
                else:
                    raise AssertionError("Invalid property accepted: " + invalid)
    for metadata in ("pom", "pom-only", "module", "mixed", "bom"):
        assert parser.parse_args(["--metadata", metadata]).metadata == metadata


def system_property(value):
    name, separator, setting = value.partition("=")
    if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", name) or not setting:
        raise argparse.ArgumentTypeError("must be NAME=VALUE with a nonempty name and value")
    if name in (PROPERTY, PROPERTY + ".depth", PROPERTY + ".maxPending", PROPERTY + ".maxCandidates"):
        raise argparse.ArgumentTypeError("use the dedicated lookahead options instead")
    return name, setting


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
    parser.add_argument("--metadata", choices=("pom", "pom-only", "module", "mixed", "bom"), default="pom",
                        help="Metadata fixture (default: pom); bom depth is nested import depth, 1 for direct imports")
    parser.add_argument("--experimental-property", type=system_property, action="append", default=[], metavar="NAME=VALUE",
                        help="Repeatable system property for experimental/on only (last value wins); e.g. org.gradle.internal.resolve.metadata.parallelRedirect=true")
    parser.add_argument("--baseline-property", type=system_property, action="append", default=[], metavar="NAME=VALUE",
                        help="Repeatable system property for baseline/off only (last value wins); e.g. org.gradle.internal.resolve.metadata.parallelBom=false")
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