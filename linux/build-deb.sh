#!/bin/sh
# Build trackeye_1.0.0_all.deb for Ubuntu 24.04
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/build/deb/trackeye"
rm -rf "$ROOT/build/deb"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/share/trackeye" "$PKG/usr/bin" \
  "$PKG/usr/share/applications" "$PKG/usr/share/icons/hicolor/scalable/apps"
cp "$ROOT/linux/control" "$PKG/DEBIAN/control"
cp "$ROOT/linux/trackeye.py" "$PKG/usr/share/trackeye/trackeye.py"
chmod 755 "$PKG/usr/share/trackeye/trackeye.py"
printf '#!/bin/sh\nexec python3 /usr/share/trackeye/trackeye.py "$@"\n' > "$PKG/usr/bin/trackeye"
chmod 755 "$PKG/usr/bin/trackeye"
cp "$ROOT/linux/trackeye.desktop" "$PKG/usr/share/applications/trackeye.desktop"
cp "$ROOT/linux/trackeye.svg" "$PKG/usr/share/icons/hicolor/scalable/apps/trackeye.svg"
dpkg-deb --build --root-owner-group "$PKG" "$ROOT/build/trackeye_1.0.0_all.deb"
echo "Built: $ROOT/build/trackeye_1.0.0_all.deb"
