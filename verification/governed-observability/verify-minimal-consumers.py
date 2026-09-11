#!/usr/bin/env python3
"""Build/run separate public consumers after source-matched framework installation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

repo = Path(__file__).resolve().parents[2]
evidence = Path(sys.argv[1]).resolve()
receipts = {}
for capability, artifact, statement in (
    ("kernel", "moduvera-kernel", 'if (!new io.github.ande1922.moduvera.context.TenantId("minimal").value().equals("minimal")) throw new AssertionError();'),
    ("message", "moduvera-message-core", 'if (!new io.github.ande1922.moduvera.message.MessageId("minimal").value().equals("minimal")) throw new AssertionError();'),
    ("logging", "moduvera-logging-spring-boot-starter", '''
        var app = new org.springframework.boot.SpringApplication(Consumer.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.setDefaultProperties(java.util.Map.of("logging.structured.format.console",
            "io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter"));
        try (var context = app.run()) {
            org.slf4j.LoggerFactory.getLogger(Consumer.class).info("Minimal public logging consumer started");
        }
    '''),
):
    work = evidence / ("minimal-" + capability)
    source = work / "src/main/java"
    source.mkdir(parents=True, exist_ok=True)
    (work / "pom.xml").write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.1</version><relativePath/></parent>
      <groupId>notes.qualification</groupId><artifactId>minimal-{capability}</artifactId><version>1</version>
      <properties><java.version>26</java.version></properties>
      <dependencyManagement><dependencies><dependency><groupId>io.github.ande1922.moduvera</groupId><artifactId>moduvera-bom</artifactId><version>0.1.0-SNAPSHOT</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
      <dependencies><dependency><groupId>io.github.ande1922.moduvera</groupId><artifactId>{artifact}</artifactId></dependency></dependencies>
    </project>\n''')
    (source / "Consumer.java").write_text('public class Consumer { public static void main(String[] args) throws Exception {\n' + statement + '''
        for (String forbidden : java.util.List.of("jakarta.servlet.Servlet", "reactor.core.publisher.Mono",
                "org.apache.kafka.clients.producer.KafkaProducer", "io.opentelemetry.sdk.OpenTelemetrySdk")) {
            try { Class.forName(forbidden); throw new AssertionError("Unexpected dependency: " + forbidden); }
            catch (ClassNotFoundException expected) { }
        }
        System.out.println("MINIMAL_CONSUMER_PASS");
    }}\n''')
    command = [str(repo / "mvnw"), "-B", "-ntp", "-f", str(work / "pom.xml"), "package",
               "help:effective-pom", "-Doutput=" + str(work / "effective-pom.xml"),
               "dependency:tree", "dependency:build-classpath", "-Dmdep.outputFile=" + str(work / "classpath.txt")]
    with (work / "build.log").open("w") as log:
        built = subprocess.run(command, cwd=work, stdout=log, stderr=subprocess.STDOUT)
    (work / "build.exit").write_text(str(built.returncode) + "\n")
    built.check_returncode()
    jars = [Path(part) for part in (work / "classpath.txt").read_text().strip().split(os.pathsep)]
    forbidden_names = ("servlet", "reactor", "kafka", "opentelemetry-sdk", "micrometer-tracing")
    assert not any(any(word in jar.name for word in forbidden_names) for jar in jars), jars
    if capability in ("kernel", "message"):
        assert all(jar.name.startswith(("moduvera-kernel-", "moduvera-message-core-")) for jar in jars), jars
    command = ["java", "-cp", os.pathsep.join([str(work / "target/classes"), *(str(jar) for jar in jars)]), "Consumer"]
    with (work / "runtime.log").open("w") as log:
        ran = subprocess.run(command, cwd=work, stdout=log, stderr=subprocess.STDOUT)
    (work / "runtime.exit").write_text(str(ran.returncode) + "\n")
    ran.check_returncode()
    assert "MINIMAL_CONSUMER_PASS" in (work / "runtime.log").read_text()
    receipts[capability] = {"artifact": artifact, "jars": {str(jar): hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars}}
(evidence / "minimal-consumers.json").write_text(json.dumps(receipts, indent=2) + "\n")
# Bind the standalone application's installed public jars to this checkout's build outputs.
notes_jars = [Path(part) for part in (evidence / "notes-runtime-classpath.txt").read_text().strip().split(os.pathsep)]
installed = {}
for jar in notes_jars:
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if jar.name.startswith("moduvera-"):
        outputs = [path for path in (repo / "framework").rglob(jar.name) if path.parent.name == "target"]
        assert len(outputs) == 1, (jar.name, outputs)
        assert hashlib.sha256(outputs[0].read_bytes()).hexdigest() == digest, jar.name
    installed[str(jar)] = digest
paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--",
    "framework", "examples/simple-notes-demo", "verification/governed-observability", ".mvn", "mvnw", "pom.xml"], cwd=repo, text=True).splitlines()
inputs = {path: hashlib.sha256((repo / path).read_bytes()).hexdigest() for path in paths if (repo / path).is_file()}
(evidence / "notes-inputs.json").write_text(json.dumps({
    "checkout": str(repo), "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
    "status": subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True),
    "source_sha256": inputs, "runtime_sha256": installed,
}, indent=2) + "\n")
print("Minimal independent Parent/BOM consumers: 3 built and started; transport/SDK absence PASS")
