#!/usr/bin/env bash
set -euo pipefail
[[ $# == 2 ]] || exit 2
PHASE=$1; LABEL=$2
ROOT=/home/whl/fire-platform-n05-20261030
[[ $(uname -s) == Linux && $(id -u) == 1000 && $LABEL =~ ^[a-z0-9-]+$ && -f "$ROOT/artifacts/n05/workspace-before.json" ]] || exit 2
export ROOT FIRE_TOOLS_DIR="$ROOT/.tools/linux" JAVA_HOME="$ROOT/.tools/linux/jdk-21" TZ=UTC PYTHONDONTWRITEBYTECODE=1
export LEGACY_APPENDIX="${LEGACY_APPENDIX:-/mnt/d/烟感/_incoming/omp-docs/appendix}"
unset PYTHONOPTIMIZE
for variable in ${!APP_@}; do unset "$variable"; done
source "$ROOT/scripts/runtime-env.sh"
LOG="$ROOT/artifacts/n05/build-$LABEL.log"
[[ ! -e "$LOG" ]] || exit 2
(
  printf 'N05_BUILD_START phase=%s label=%s\n' "$PHASE" "$LABEL"
  java -version; node --version
  case "$PHASE" in
    npm-ci) cd "$ROOT/frontend"; npm ci --offline --no-audit --no-fund ;;
    migration-coupling)
      cd "$ROOT/backend"
      mvn -o -B -ntp '-Dtest=N04IdentityMigrationTest,DeviceGroupIntegrationTest#all46PublicTablesStayByteFactEquivalentAcrossSuccessfulReads,SimCardIntegrationTest#all48PublicTablesRemainFactEquivalentAcrossEverySuccessfulRead,SimBindingIntegrationTest#neverAssociatedStateIsExactVirtualZeroAndReadsNeverMaterializeARow,SimEmbeddedProjectionTest,SimInventoryProjectionTest,SimExpiryIntegrationTest#disabledConfigurationIsVirtualAndMinimalReadAndGrantGatesRemainLive' test ;;
    be-all) cd "$ROOT/backend"; mvn -o -B -ntp test ;;
    be-compile) cd "$ROOT/backend"; mvn -o -B -ntp -DskipTests test-compile ;;
    be-legacy) cd "$ROOT/backend"; mvn -o -B -ntp '-Dtest=LegacyIngestRejectTest,LegacyMirrorPipelineIntegrationTest,LegacyDictionaryImportTest,LegacyIngestVectorTest,LegacyIngestAuthenticationFilterTest,LegacyFactApplyTest,OfflineEventTaskTest,CodecSandboxTest,LegacyMqttSubscriberTest,LegacyMqttTopicParserTest,LegacyIngestExportServiceTest' test ;;
    be-threshold-token) cd "$ROOT/backend"; mvn -o -B -ntp '-Dtest=ProductIntegrationTest#stringThresholdTokenIsRejectedWithoutChangingDraftOrCreatingReceipt' test ;;
    be-n05-smoke) cd "$ROOT/backend"; mvn -o -B -ntp '-Dtest=ArchitectureTest,DeviceGroupOwnershipTest,DeviceDetailIntegrationTest,DeviceWorkbenchIntegrationTest,ProductIntegrationTest' test ;;
    fe-build) cd "$ROOT/frontend"; npm run build ;;
    fe-all) cd "$ROOT/frontend"; npm run build; npm test -- --reporter=default --reporter=json --outputFile.json="$ROOT/artifacts/n05/vitest-$LABEL.json" ;;
    package) cd "$ROOT"; bash scripts/package.sh --skip-install ;;
    backend-package) cd "$ROOT/backend"; mvn -o -B -ntp clean package ;;
    frontend-repackage)
      printf 'N05_BACKEND_TEST_EXECUTION_REUSED: requires exact backend input and non-static JAR payload identity to the fully tested backend reference; this phase does not rerun backend tests.\n'
      cd "$ROOT/frontend"; npm run build
      cd "$ROOT/backend"; mvn -o -B -ntp -DskipTests clean package ;;
    *) exit 2 ;;
  esac
  printf 'N05_BUILD_PASS phase=%s label=%s\n' "$PHASE" "$LABEL"
) > "$LOG" 2>&1
printf 'N05_BUILD_PASS phase=%s label=%s\n' "$PHASE" "$LABEL"
