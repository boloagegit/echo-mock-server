#!/bin/sh
set -eu
umask 077

echo_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
echo_jar_path=${ECHO_JAR_PATH:-"$echo_script_dir/echo.jar"}
echo_sqlite_tmp=${SQLITE_TMPDIR:-"${TMPDIR:-/tmp}/echo-sqlite"}

if [ ! -f "$echo_jar_path" ]; then
    echo "Echo JAR not found: $echo_jar_path" >&2
    echo "Copy the built JAR beside this script as echo.jar, or set ECHO_JAR_PATH." >&2
    exit 1
fi

mkdir -p "$echo_sqlite_tmp"
if [ ! -d "$echo_sqlite_tmp" ] || [ ! -w "$echo_sqlite_tmp" ]; then
    echo "SQLite temporary directory is not writable: $echo_sqlite_tmp" >&2
    exit 1
fi

exec java -Dorg.sqlite.tmpdir="$echo_sqlite_tmp" -jar "$echo_jar_path" "$@"
