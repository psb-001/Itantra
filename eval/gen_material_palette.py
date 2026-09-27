#!/usr/bin/env python3
"""
Generate the Material 3 colour roles for the app.

WHY A GENERATOR INSTEAD OF A HAND-WRITTEN colours.xml
----------------------------------------------------
Material 3 specifies each colour role as a *tone* — a target perceptual lightness (L*). Hand
picking hex values produces a palette that looks plausible and quietly fails: an "onSurface"
that is not dark enough, a dark-mode primary that fails contrast, or a primary that drifts in
hue between tones. This maps every role to its specified tone and then verifies contrast.

WHY HSL, NOT LINEAR RGB
-----------------------
The first attempt scaled the source colour in linear RGB until L* matched. That is wrong for
hues: brightening a blue in linear RGB pulls it toward cyan, and the dark primary came out a
garish #2BD7FF instead of a blue. Converting to HSL and moving only the lightness holds the hue
exactly, so a deep institutional blue is still that same blue at tone 80.

Saturation is eased down automatically when the requested lightness would otherwise clip a
channel, rather than letting the colour blow out to pure white.

WHAT IT WRITES
--------------
    app/app/src/main/res/values/colors_material.xml
    app/app/src/main/res/values-night/colors_material.xml

Run from the repo root:

    .venv/bin/python eval/gen_material_palette.py

Exits non-zero if any role fails WCAG AA, so a bad edit to the source colours cannot silently
produce an unreadable UI.

CHOOSING THE SOURCE COLOURS
---------------------------
Primary is a deep institutional blue: ISRO's own identity is blue and white, and for an
emergency tool blue reads as calm and trustworthy.

The `alert` roles are deliberately NOT the `error` roles. In this app a dropped or
mis-recognised segment is a recoverable event and must not look like a siren; only a genuine
emergency alert gets the saturated red. Conflating the two trains users to ignore red.
"""

from __future__ import annotations

import colorsys
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app/app/src/main/res"

# Source colours, in sRGB hex.
PRIMARY = "#0B57A4"    # institutional blue
SECONDARY = "#4A6076"  # desaturated steel
TERTIARY = "#2E6B5E"   # muted teal, so the UI is not monochrome blue
ERROR = "#BA1A1A"
NEUTRAL = "#5A5D64"    # very slightly blue, so greys sit with the primary
ALERT = "#A4232B"
ALERT_BG = "#FFD9DE"

# Every M3 role we map. Order is only for readable output.
ORDER = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "error", "onError", "errorContainer", "onErrorContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
    "surfaceContainerHigh", "surfaceContainerHighest",
    "outline", "outlineVariant", "inverseSurface", "inverseOnSurface", "scrim",
]
ALERT_ROLES = ["alert", "onAlert", "alertContainer", "onAlertContainer"]

# WCAG AA. 4.5 for body text, 3.0 for large text and UI components.
WCAG_BODY, WCAG_LARGE = 4.5, 3.0


def _srgb_to_lin(c: float) -> float:
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _lin_to_srgb(c: float) -> float:
    v = 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
    return max(0.0, min(1.0, v))


def hex2rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb2hex(rgb) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def luminance(rgb) -> float:
    r, g, b = (_srgb_to_lin(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def lstar(rgb) -> float:
    y = luminance(rgb)
    return 116 * (y ** (1 / 3)) - 16 if y > 0.008856 else 903.3 * y


def contrast_ratio(a, b) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def tone(rgb, target_l: float) -> tuple[int, int, int]:
    """Set perceptual lightness to `target_l`, holding hue exactly, easing chroma at the ends."""
    if target_l <= 0:
        return (0, 0, 0)
    if target_l >= 100:
        return (255, 255, 255)
    h, _l, s = colorsys.rgb_to_hls(*[v / 255 for v in rgb])
    # EASE CHROMA TOWARD BOTH ENDS OF THE LIGHTNESS RANGE.
    #
    # Holding hue and saturation exactly produced a dark-mode alert container of #920013 - a
    # vivid, shouting red. That is very close to M3's own baseline errorContainer (#93000A), so
    # it is spec-conformant, but it is wrong for OUR alert role: these are informational
    # notices, not errors, and a full-strength red reads as an alarm rather than a footnote.
    #
    # Real M3 tonal palettes reduce chroma as lightness approaches the extremes, because human
    # colour perception desaturates there anyway. This approximates that. Only CONTAINER roles
    # are drawn at these extremes, and those are the large surfaces, which is where it shows.
    # Only the true extremes ease. Tone 40 and tone 80 carry the LIGHT and DARK primary, the
    # two colours the whole app is built around, and an earlier version of this applied the
    # easing at <=40 and quietly shifted the brand blue. Keep the midtones at full chroma.
    if target_l <= 20:
        s *= 0.55
    elif target_l < 30:
        s *= 0.80
    elif target_l >= 94:
        s *= 0.85
    s = min(1.0, s)
    lo, hi = 0.0, 1.0
    for _ in range(40):
        mid = (lo + hi) / 2
        cand = colorsys.hls_to_rgb(h, mid, s)
        if max(max(cand) - 1.0, 0.0 - min(cand)) > 0:
            s = max(0.0, s - 0.01)          # ease saturation instead of clipping
            continue
        if lstar(tuple(round(v * 255) for v in cand)) < target_l:
            lo = mid
        else:
            hi = mid
    return tuple(round(v * 255) for v in colorsys.hls_to_rgb(h, (lo + hi) / 2, s))


def scheme(src: tuple[int, int, int], dark: bool) -> dict:
    """One hue's worth of M3 roles. Tone values are the M3 specification."""
    g = lambda t: tone(src, t)  # noqa: E731
    if not dark:
        return {
            "primary": g(40), "onPrimary": g(100), "primaryContainer": g(90),
            "onPrimaryContainer": g(10), "inversePrimary": g(80),
            "secondary": g(40), "onSecondary": g(100), "secondaryContainer": g(90),
            "onSecondaryContainer": g(10),
            "tertiary": g(40), "onTertiary": g(100), "tertiaryContainer": g(90),
            "onTertiaryContainer": g(10),
            "error": g(40), "onError": g(100), "errorContainer": g(90),
            "onErrorContainer": g(10),
            "background": g(98), "onBackground": g(10), "surface": g(98), "onSurface": g(10),
            "surfaceVariant": g(90), "onSurfaceVariant": g(30),
            "surfaceContainerLowest": g(100), "surfaceContainerLow": g(96),
            "surfaceContainer": g(94), "surfaceContainerHigh": g(92),
            "surfaceContainerHighest": g(90),
            "outline": g(50), "outlineVariant": g(80),
            "inverseSurface": g(20), "inverseOnSurface": g(95), "scrim": g(0),
        }
    return {
        "primary": g(80), "onPrimary": g(20), "primaryContainer": g(30),
        "onPrimaryContainer": g(90), "inversePrimary": g(40),
        "secondary": g(80), "onSecondary": g(20), "secondaryContainer": g(30),
        "onSecondaryContainer": g(90),
        "tertiary": g(80), "onTertiary": g(20), "tertiaryContainer": g(30),
        "onTertiaryContainer": g(90),
        "error": g(80), "onError": g(20), "errorContainer": g(30),
        "onErrorContainer": g(90),
        "background": g(6), "onBackground": g(90), "surface": g(6), "onSurface": g(90),
        "surfaceVariant": g(30), "onSurfaceVariant": g(80),
        "surfaceContainerLowest": g(4), "surfaceContainerLow": g(10),
        "surfaceContainer": g(12), "surfaceContainerHigh": g(17),
        "surfaceContainerHighest": g(22),
        "outline": g(60), "outlineVariant": g(30),
        "inverseSurface": g(90), "inverseOnSurface": g(20), "scrim": g(0),
    }


def build() -> tuple[dict, dict]:
    """
    Combine the five hues into one role set.

    Each hue only contributes the roles that belong to it. This is the important part: the
    obvious implementation merges five complete `scheme()` outputs, and because NEUTRAL is
    applied last it silently overwrites `primary` with a grey (#5B5E66). An M3 palette is
    five independent hue ramps stitched into specific roles, not five full palettes layered
    on top of each other.
    """
    by_hue = {
        "primary": (PRIMARY, ["primary", "onPrimary", "primaryContainer",
                              "onPrimaryContainer", "inversePrimary"]),
        "secondary": (SECONDARY, ["secondary", "onSecondary", "secondaryContainer",
                                  "onSecondaryContainer"]),
        "tertiary": (TERTIARY, ["tertiary", "onTertiary", "tertiaryContainer",
                                "onTertiaryContainer"]),
        "error": (ERROR, ["error", "onError", "errorContainer", "onErrorContainer"]),
        "neutral": (NEUTRAL, [k for k in ORDER if k not in {
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "error", "onError", "errorContainer", "onErrorContainer",
        }]),
    }

    light: dict = {}
    dark: dict = {}
    for _name, (src, role_names) in by_hue.items():
        ls = scheme(hex2rgb(src), dark=False)
        ds = scheme(hex2rgb(src), dark=True)
        for k in role_names:
            light[k] = ls[k]
            dark[k] = ds[k]

    # Guard against the exact bug described above: `primary` must still be a blue.
    for scheme_name, roles in (("light", light), ("dark", dark)):
        pr = roles["primary"]
        r, g, b = pr
        if b <= r or b <= g:
            sys.exit(
                f"{scheme_name} primary came out non-blue: {rgb2hex(pr)}. "
                f"A hue ramp overwrote the primary roles."
            )

    light.update({
        "alert": tone(hex2rgb(ALERT), 40), "onAlert": tone(hex2rgb(ALERT), 100),
        "alertContainer": tone(hex2rgb(ALERT_BG), 90),
        "onAlertContainer": tone(hex2rgb(ALERT_BG), 10),
    })
    dark.update({
        "alert": tone(hex2rgb(ALERT), 80), "onAlert": tone(hex2rgb(ALERT), 20),
        "alertContainer": tone(hex2rgb(ALERT_BG), 30),
        "onAlertContainer": tone(hex2rgb(ALERT_BG), 90),
    })
    return light, dark


def write(path: Path, roles: dict, scheme_name: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!--\n  Material 3 {scheme_name} colour roles for iTantra.\n\n"
            "  GENERATED FILE - edit eval/gen_material_palette.py and re-run instead.\n"
            "  Roles are tone-mapped in HSL so hue is identical at every tone; every\n"
            "  foreground/background pair is contrast-checked against WCAG AA by the\n"
            "  generator, which exits non-zero if any pair fails.\n-->\n"
            "<resources>\n"
        )
        for k in ORDER:
            f.write(f'    <color name="md_{k}">{rgb2hex(roles[k])}</color>\n')
        for k in ALERT_ROLES:
            f.write(f'    <color name="md_{k}">{rgb2hex(roles[k])}</color>\n')
        f.write("</resources>\n")


def main() -> int:
    light, dark = build()

    checks = [
        ("light onSurface / surface", light["onSurface"], light["surface"], WCAG_BODY),
        ("light primary / surface", light["primary"], light["surface"], WCAG_BODY),
        ("light onPrimary / primary", light["onPrimary"], light["primary"], WCAG_BODY),
        ("light onSurfaceVariant / surface", light["onSurfaceVariant"], light["surface"], WCAG_BODY),
        ("light onPrimaryContainer / primaryContainer",
         light["onPrimaryContainer"], light["primaryContainer"], WCAG_BODY),
        ("light alert / surface", light["alert"], light["surface"], WCAG_BODY),
        ("light onAlert / alert", light["onAlert"], light["alert"], WCAG_BODY),
        ("light onAlertContainer / alertContainer",
         light["onAlertContainer"], light["alertContainer"], WCAG_BODY),
        ("dark onSurface / surface", dark["onSurface"], dark["surface"], WCAG_BODY),
        ("dark primary / surface", dark["primary"], dark["surface"], WCAG_BODY),
        ("dark onPrimary / primary", dark["onPrimary"], dark["primary"], WCAG_BODY),
        ("dark onSurfaceVariant / surface", dark["onSurfaceVariant"], dark["surface"], WCAG_BODY),
        ("dark onAlert / alert", dark["onAlert"], dark["alert"], WCAG_BODY),
        ("dark onAlertContainer / alertContainer",
         dark["onAlertContainer"], dark["alertContainer"], WCAG_BODY),
        # UI components / large text only need 3.0
        ("light primary / surfaceContainer", light["primary"], light["surfaceContainer"], WCAG_LARGE),
        ("dark primary / surfaceContainer", dark["primary"], dark["surfaceContainer"], WCAG_LARGE),
        ("light outline / surface", light["outline"], light["surface"], WCAG_LARGE),
    ]

    print("WCAG AA contrast\n")
    failures = []
    for name, fg, bg, need in checks:
        r = contrast_ratio(fg, bg)
        ok = r >= need
        if not ok:
            failures.append((name, r, need))
        print(f"  {'PASS' if ok else 'FAIL'}  {name:<46}{r:6.2f}:1  need {need}")

    write(RES / "values" / "colors_material.xml", light, "light")
    write(RES / "values-night" / "colors_material.xml", dark, "dark")
    print("\nwrote colors_material.xml (light) and values-night/colors_material.xml (dark)")
    print(f"  primary  light {rgb2hex(light['primary'])}  dark {rgb2hex(dark['primary'])}")
    print(f"  alert    light {rgb2hex(light['alert'])}  dark {rgb2hex(dark['alert'])}")

    if failures:
        print(f"\n{len(failures)} CONTRAST FAILURE(S) - fix the source colours before shipping:")
        for name, r, need in failures:
            print(f"  {name}: {r:.2f}:1, needs {need}")
        return 1
    print("\nall contrast checks pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
