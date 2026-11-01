#!/usr/bin/env python3
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "brand"
SHARED = ROOT / "shared/src/commonMain/composeResources"
ANDROID = ROOT / "composeApp/src/main"
IOS = ROOT / "iosApp/Vezuna"


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


for name in ("lockup-color", "lockup-dark", "mark-color", "mark-dark"):
    copy(BRAND / f"logos/{name}.png", SHARED / f"drawable/vezuna_{name.replace('-', '_')}.png")
for name in ("NotoSans-Regular.ttf", "NotoSans-Bold.ttf"):
    copy(BRAND / f"fonts/{name}", SHARED / f"font/{name}")
    copy(BRAND / f"fonts/{name}", ANDROID / "res/font" / name.lower().replace("-", "_"))
    copy(BRAND / f"fonts/{name}", IOS / f"Fonts/{name}")
copy(BRAND / "fonts/NOTO-LICENSE.txt", SHARED / "files/NOTO-LICENSE.txt")
copy(BRAND / "fonts/NOTO-LICENSE.txt", ANDROID / "assets/NOTO-LICENSE.txt")
copy(BRAND / "fonts/NOTO-LICENSE.txt", IOS / "Fonts/NOTO-LICENSE.txt")

for source in (BRAND / "icons/android/res").rglob("*"):
    if source.is_file():
        target = ANDROID / "res" / source.relative_to(BRAND / "icons/android/res")
        copy(source, target)
copy(BRAND / "icons/android/res/drawable/ic_vezuna_monochrome.xml", ANDROID / "res/drawable/ic_notification.xml")
for source in (BRAND / "icons/ios/AppIcon.appiconset").rglob("*"):
    if source.is_file():
        copy(source, IOS / "Assets.xcassets/AppIcon.appiconset" / source.name)

print("VEZUNA_ASSETS_SYNCED")
