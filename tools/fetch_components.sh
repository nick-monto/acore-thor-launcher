#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="${COMPONENTS_URL:-https://github.com/jaredgei/wow-forever-android/releases/download/components-v1}"
FILES=(
    proton-11.0-90624-arm64ec.wcp
    dxvk-2.4.1-wow-aarch64-test.wcp
    turnip-wow-scheduler-test.zip
    turnip-V32-RP6sched.zip
)

# ConsolePortLK ships modified: the upstream release is pinned here, and the pad defaults this project
# gives it (see the README section on the local realm) are applied from a patch to the extracted copy.
# The zip that lands in the APK is the result, so it is built rather than downloaded.
CONSOLEPORT_UPSTREAM="ConsolePortLK-1.5.0-rc2.zip"
CONSOLEPORT_SHA256="9ee20bb1f3c5c5b8d45fcc5980a07bb90d49a707e120613453177c05fea6497f"
CONSOLEPORT_SHIPPED="ConsolePortLK-1.5.0-rc2-thor2.zip"
CONSOLEPORT_PATCH="$REPO_DIR/tools/consoleportlk-thor1.patch"

build_consoleport() {
    if [ -s "$DEST/$CONSOLEPORT_SHIPPED" ]; then
        echo "have $CONSOLEPORT_SHIPPED"
        return
    fi
    if [ -n "${1:-}" ] && [ -s "$1/$CONSOLEPORT_SHIPPED" ]; then
        cp "$1/$CONSOLEPORT_SHIPPED" "$DEST/$CONSOLEPORT_SHIPPED"
        echo "copied $CONSOLEPORT_SHIPPED"
        return
    fi
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN
    curl -fL --retry 3 -o "$work/$CONSOLEPORT_UPSTREAM" \
        "https://github.com/leoaviana/ConsolePortLK/releases/download/1.5.0-rc2/$CONSOLEPORT_UPSTREAM"
    echo "$CONSOLEPORT_SHA256  $work/$CONSOLEPORT_UPSTREAM" | shasum -a 256 -c -
    unzip -q "$work/$CONSOLEPORT_UPSTREAM" -d "$work/addon"
    (cd "$work/addon" && patch -p1 -s < "$CONSOLEPORT_PATCH")
    (cd "$work/addon" && zip -q -r -X "$DEST/$CONSOLEPORT_SHIPPED.part" .)
    mv "$DEST/$CONSOLEPORT_SHIPPED.part" "$DEST/$CONSOLEPORT_SHIPPED"
    echo "built $CONSOLEPORT_SHIPPED"
}

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
    if [ -s "$DEST/$f" ]; then
        echo "have $f"
    elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
        cp "$1/$f" "$DEST/$f"
        echo "copied $f"
    else
        case "$f" in
            turnip-V32-RP6sched.zip)
                curl -fL --retry 3 -o "$DEST/$f.part" "https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip"
                ;;
            *)
                curl -fL --retry 3 -o "$DEST/$f.part" "$RELEASE_URL/$f"
                ;;
        esac
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    fi
done

build_consoleport "${1:-}"

(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")
