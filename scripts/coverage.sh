#!/usr/bin/env sh
# Runs the tests with JaCoCo and fails under 90% line or branch coverage (see gradle/coverage.gradle).
# The report: build/reports/jacoco/test/html/index.html
set -eu
cd "$(dirname "$0")/.."
./gradlew -I gradle/coverage.gradle test jacocoTestReport jacocoTestCoverageVerification "$@"
