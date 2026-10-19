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
    app/src/main/java/com/nas/dashboard/presence/PresencePolicy.java \
    app/src/main/java/com/nas/dashboard/presence/GeofencePolicy.java \
    tests/GeofencePolicyTest.java \
    tests/PresencePolicyTest.java \
    tests/OverviewPolicyTest.java \
    tests/HostPolicyTest.java \
    tests/EndpointProbeTest.java
java -cp "$task_classes" OverviewPolicyTest
java -cp "$task_classes" HostPolicyTest
java -cp "$task_classes" EndpointProbeTest

java -cp "$task_classes" PresencePolicyTest

java -cp "$task_classes" GeofencePolicyTest
