#!/usr/bin/env python3
"""Exercise real instrumentation and failure propagation in an isolated consumer."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(project, *args, expected=None):
    result = subprocess.run(
        ["sh", str(project / "kotlin"), *args], cwd=project, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600,
    )
    if expected is None:
        assert result.returncode == 0, result.stdout
    else:
        assert result.returncode != 0 and expected in result.stdout, result.stdout
    print(f"Verified: {' '.join(args)} ({expected or 'success'})", flush=True)


with tempfile.TemporaryDirectory(prefix="ktc kover smoke ") as temporary:
    project = Path(temporary) / "consumer"
    shutil.copytree(ROOT, project, ignore=shutil.ignore_patterns(".git", "build", ".idea", "__pycache__"))
    module = project / "example/module.yaml"
    original_module = module.read_text()
    test = project / "example/test/GreeterTest.kt"
    original_test = test.read_text()
    run(project, "check", "koverCheck", "-m", "example")
    reports = list((project / "build/tasks").glob("*/coverage.xml"))
    assert len(reports) == 1, reports
    xml = reports[0]
    counter = ET.parse(xml).getroot().find("./counter[@type='LINE']")
    assert counter is not None
    assert int(counter.attrib["covered"]) > 0 and int(counter.attrib["missed"]) > 0
    assert (xml.parent / "html/index.html").is_file()

    module.write_text(original_module.replace("minimumLineCoverage: 50", "minimumLineCoverage: 100"))
    run(project, "check", "koverCheck", "-m", "example", expected="below 100%")
    module.write_text(original_module)

    test.write_text(original_test.replace('"Hello, Kotlin!"', '"wrong expectation"'))
    run(project, "do", "koverReport", "-m", "example", expected="Instrumented tests failed")
    assert not xml.exists(), "A failed run retained a stale report"
    assert not (xml.parent / "html").exists()
    test.write_text(original_test)

    module.write_text(original_module + '    includeTags: ["missing-tag"]\n')
    run(project, "do", "koverReport", "-m", "example", expected="no tests were discovered")
    assert not xml.exists()
    module.write_text(original_module)

    run(project, "check", "koverCheck", "-m", "example")
    print("Coverage smoke passed: real reports, threshold, failed tests, empty selection, recovery, paths with spaces.")
