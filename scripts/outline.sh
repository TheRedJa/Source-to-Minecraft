#!/usr/bin/env bash
# Prints a line-numbered outline (types and functions) of Rust and Java files,
# so a reader can jump to one function instead of reading a whole file.
# Usage: scripts/outline.sh FILE_OR_DIR... [-g PATTERN]
#   -g PATTERN  only show outline lines matching PATTERN (extended regex)
set -euo pipefail

filter=''
paths=()
while (($#)); do
    case "$1" in
        -g) filter="$2"; shift 2 ;;
        *) paths+=("$1"); shift ;;
    esac
done
((${#paths[@]})) || { echo "usage: $0 FILE_OR_DIR... [-g PATTERN]" >&2; exit 2; }

rust='^\s*(#\[cfg\(test\)\]|(pub(\([^)]*\))?\s+)?((async|const|unsafe|extern "C")\s+)*fn\s|(pub(\([^)]*\))?\s+)?(struct|enum|trait|union|type|mod|macro_rules!)\s|impl[<\s])'
# Class-level declarations: types at any depth, methods/constructors indented
# at most 8 spaces (members of a top-level or nested type), never statements.
java_type='^\s*(@\w+\s+)*((public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(class|interface|enum|record|@interface)\s'
java_member='^ {4}( {4})?((public|private|protected|static|final|abstract|synchronized|native|default)\s+)*[A-Za-z_][][[:alnum:]_<>,.? ]*\s+\w+\s*\([^;]*$'
java_ctor='^ {4}( {4})?((public|private|protected)\s+)?[A-Z]\w*\s*\([^;]*$'
java_skip='^\s*(return|new|throw|if|for|while|switch|catch|else|try|do|case|yield)\b'

outline() {
    local file=$1
    case "$file" in
        *.rs) grep -anE "$rust" "$file" || true ;;
        *.java)
            grep -anE "$java_type|$java_member|$java_ctor" "$file" \
                | grep -avE "^[0-9]+:$java_skip" \
                | grep -avE '^[0-9]+:\s*[a-z]\w*\s*\(' || true ;;
    esac
}

while IFS= read -r -d '' file; do
    lines=$(outline "$file" | sed -E 's/\s*\{?\s*$//')
    [[ -n "$filter" ]] && lines=$(grep -aE "$filter" <<<"$lines" || true)
    [[ -z "$lines" ]] && continue
    printf '== %s (%s lines)\n%s\n' "$file" "$(wc -l <"$file")" "$lines"
done < <(find "${paths[@]}" -type f \( -name '*.rs' -o -name '*.java' \) -print0 | sort -z)
