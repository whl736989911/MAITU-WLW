#!/usr/bin/env bash
# Sourced by project Bash entry points. Ubuntu/Linux only; never source a secret .env implicitly.
[[ $(uname -s) == Linux ]] || { printf '%s\n' 'This project supports Ubuntu/Linux only.' >&2; return 1; }
: "${ROOT:?Caller must set the absolute project ROOT}"
export FIRE_TOOLS_DIR=${FIRE_TOOLS_DIR:-"$ROOT/.tools/linux"}
if [[ -x "$FIRE_TOOLS_DIR/node-24.16.0/bin/node" ]]; then
  export PATH="$FIRE_TOOLS_DIR/node-24.16.0/bin:$PATH"
fi
if [[ -z ${JAVA_HOME:-} && -x "$FIRE_TOOLS_DIR/jdk-21/bin/java" ]]; then
  export JAVA_HOME="$FIRE_TOOLS_DIR/jdk-21"
fi
if [[ -n ${JAVA_HOME:-} ]]; then
  [[ -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]] || { printf '%s\n' 'JAVA_HOME must select a Linux JDK21.' >&2; return 1; }
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -x "$FIRE_TOOLS_DIR/apache-maven-3.9.11/bin/mvn" ]]; then
  export PATH="$FIRE_TOOLS_DIR/apache-maven-3.9.11/bin:$PATH"
fi
command -v java >/dev/null || { printf '%s\n' 'Install Java21 or run bash scripts/prepare-tools.sh.' >&2; return 1; }
JAVA_VERSION=$(java -version 2>&1)
[[ "$JAVA_VERSION" == *'version "21.'* || "$JAVA_VERSION" == *'version "21"'* ]] || { printf '%s\n' 'Java21 is required.' >&2; return 1; }
unset JAVA_VERSION
