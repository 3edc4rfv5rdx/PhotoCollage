#!/usr/bin/env bash
#
# Expose the release APKs under an .apkx name in the project root, so they can
# be sent over Viber and the like without the messenger mangling an .apk
# attachment. One per ABI in LINK_ABIS — the same ones 19-LinkOut.sh put in OUT/.
#
# Taken straight out of OUT/, where 19-LinkOut.sh has already put the newest
# build under its own name — so there is nothing here to pick or compose. The
# link keeps that name and changes only the extension.
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

keep=()
for abi in $LINK_ABIS; do
    apk=$(pick_apk "$OUT_DIR" "*-$abi.apk") || {
        echo "ERROR: No $abi APK in $OUT_DIR. Run ./19-LinkOut.sh first."
        exit 1
    }
    dst="$(basename "${apk%.apk}").apkx"
    ln -sf "$apk" "$dst" 2>/dev/null || cp "$apk" "$dst"
    keep+=(! -name "$dst")
    echo "$(basename "$apk") -> $dst"
done

# Only the current build keeps its links: the earlier ones are stale the moment
# these are made, and dead ones point at APKs that were cleaned away. Plain .apkx
# files are left alone, they are copies someone made on purpose.
find . -maxdepth 1 -name '*.apkx' -type l "${keep[@]}" -delete

pause
