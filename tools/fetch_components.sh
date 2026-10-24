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
    ConsolePortLK-1.5.0-rc2.zip
)

mkdir -p "$DEST"
# Components this project used to ship. A cache restored from an older key still holds them, and
# anything in this directory is packaged into the APK, so they are removed rather than kept.
for f in ConsolePortLK-1.5.0-rc2-thor2.zip; do
    rm -f "$DEST/$f"
done

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
            ConsolePortLK-*.zip)
                # ConsolePortLK has its own upstream release, and it ships stock: this launcher no
                # longer patches its controller templates, so the file is the release asset itself.
                curl -fL --retry 3 -o "$DEST/$f.part" "https://github.com/leoaviana/ConsolePortLK/releases/download/1.5.0-rc2/$f"
                ;;
            *)
                curl -fL --retry 3 -o "$DEST/$f.part" "$RELEASE_URL/$f"
                ;;
        esac
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    fi
done

(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")
