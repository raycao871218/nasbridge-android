#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
task_classes=$(mktemp -d)
trap 'rm -rf "$task_classes"' EXIT
javac -encoding UTF-8 -d "$task_classes" \
    app/src/main/java/com/nas/dashboard/network/HostConfig.java \
    app/src/main/java/com/nas/dashboard/network/HostPolicy.java \
    app/src/main/java/com/nas/dashboard/network/EndpointProbe.java \
    app/src/main/java/com/nas/dashboard/network/OverviewPolicy.java \
    tests/OverviewPolicyTest.java \
    tests/HostPolicyTest.java \
    tests/EndpointProbeTest.java
java -cp "$task_classes" OverviewPolicyTest
java -cp "$task_classes" HostPolicyTest
java -cp "$task_classes" EndpointProbeTest
