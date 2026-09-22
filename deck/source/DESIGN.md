# DESIGN.md — iTantra, SIH26173

**Design constitution.** Extracted from the official SIH idea-submission template
(`SIH2026-IDEA-Presentation-.pptx`), not invented. The user instruction "faithful to the official
template" outranks the general design principles, so where the two disagree the template wins and
the deviation is declared below.

## 0. Declared deviations from the general principles

| General rule | What this deck does | Why |
|---|---|---|
| Gradients and shadows encouraged (§2.2) | **Flat fills only. No gradients, no shadows.** | The official template contains none. Adding them breaks template fidelity. |
| Body text 22–28px (§3.1) | **16px body** | The template's own body is ~12pt on a 540pt slide, i.e. 16px at our 720px canvas. It is a dense, information-first format — six slides carry the whole submission. |
| Colour pool ≤ 4 hex (§2) | **4 core hex + 3 template-inherited semantic hex** | The template's own slides use a red/green pair for the *Challenge/Risk* vs *Engineering Fix* chip row, and purple for the team badge oval. Reproducing them is required for fidelity. Declared explicitly below so nothing is undeclared. |
| Catalog ↔ section-divider contract (story §②) | **No catalog, no dividers** | The template's instruction page caps the deck at six slides including the title. There is no room for either. |

---

## 1. Canvas and master (A / B / C)

Canvas **1280 × 720 px** (16:9, matching the template's 960×540 pt). Page padding: 20px top/bottom,
40px left/right.

| Zone | Vertical | Height | Content |
|---|---|---|---|
| **A · Title block** | 0–120px | 120px | Section title 46px bold Times New Roman, black, centred. Optional subtitle 20px below it. Team badge oval pinned left; SIH logo pinned right at the same y on every page. |
| **B · Content** | 120–660px | 540px | Panels, tables, diagrams, cards. Never intrudes into A or C. |
| **C · Footer bar** | 660–720px | 60px | Full-width **#0070C0** strip. Centred white 13px text `@SIH Idea submission- Template`; page number right-aligned in white. |

Fixed across all six pages: team badge at (24, 18) · SIH logo at (1120, 10) sized 150×71 · footer bar
at y=660. Page 1 (cover) uses a custom variant — see §6.

---

## 2. Colour system

**Core pool (4):**

| Role | Hex | Used for |
|---|---|---|
| Background | `#FFFFFF` | Every page ground |
| Primary — navy | `#01467D` | Section chips, panel header bars, diagram node fills, panel borders |
| Secondary — blue | `#0070C0` | Footer bar, connector arrows, table header row, secondary chips |
| Accent — orange | `#E46C0A` | The single focus figure per page; accent word in the title; key numbers |

**Template-inherited semantic colours (3, declared):**

| Role | Hex | Used for |
|---|---|---|
| Risk red | `#C00000` | The *Potential challenges and risks* chip on page 4 only |
| Strategy green | `#00B050` | The *Strategies for overcoming these challenges* chip on page 4 only |
| Badge purple | `#7030A0` | Team badge oval outline, all pages |

**Neutrals:** text `#1A1A1A` · secondary text `#5F5E5A` · rules and light borders `#B4B2A9` ·
panel tint `#EAF1F8` (a flat tint, not rgba).

**Area allocation per page:**

| Colour role | Budget | Notes |
|---|---|---|
| Primary navy | ≤ 55% | Chips, headers, diagram fills |
| Secondary blue | ≤ 25% | Footer bar, arrows, table head |
| Accent orange | ≤ 10% (hero pages ≤ 18%) | One focus figure per page — never two |
| Neutrals | remainder | Text, borders, whitespace |

Pages 1, 2 and 4 are hero/peak pages and carry accent at 12–18%. Pages 3, 5, 6 are supporting and
hold accent ≤ 5%. At least two pages therefore differ materially in colour proportion.

**No gradients, no shadows, no opacity overlays.** Separation is achieved with 0.5px `#B4B2A9` rules
and flat `#EAF1F8` tints.

---

## 3. Type system

| Level | Font | Size | Weight | Colour | Used for |
|---|---|---|---|---|---|
| Cover title | Times New Roman | 56px | bold | `#01467D` | Page 1 only |
| Cover meta | Calibri | 17px | regular | `#1A1A1A` | The six PS metadata fields |
| Page title | Times New Roman | 46px | bold | `#1A1A1A` | A zone, every content page |
| Page subtitle | Times New Roman | 20px | regular | `#5F5E5A` | Under the page title |
| Section chip | Calibri | 19px | bold | `#FFFFFF` | On navy `#01467D` fill |
| Panel heading | Calibri | 19px | bold | `#01467D` | Inside panels |
| Body | Calibri | 16px | regular | `#1A1A1A` | Bullets and paragraphs, line-height 1.45 |
| Data figure | Calibri | 40px | bold | `#E46C0A` | Anchor number on hero pages |
| Table cell | Calibri | 15px | regular | `#1A1A1A` | Page 4 table |
| Caption | Calibri | 12.5px | regular | `#5F5E5A` | Source notes, table footnotes |
| Footer | Calibri | 13px | regular | `#FFFFFF` | C zone |

**Two font families only:** Times New Roman (titles) + Calibri (everything else). This mirrors the
template exactly and is the one place where "title and body use different personalities" is already
satisfied. The anchor data figure uses Calibri bold at 40px — a different weight and size from body,
so the anchor is visually distinct.

---

## 4. Density gates

| Page type | Min words | Min visuals | Whitespace cap |
|---|---|---|---|
| Cover | 20 | 1 (logo/mark) | 45% (cover exempt) |
| Content, single topic | 180 | 1 diagram or table | 35% |
| Content, card row | ≥100 per card | 0–N | 35% |
| Data page | 80 | 1 table | 35% |

Container fill: panel content occupies ≥ 85% of panel height; the last element in a panel is pinned
to its bottom edge. Sibling cards in a row align on title / body / tail on the same y (tolerance 16px).

---

## 5. Visual asset system

| Level | Asset | Placement | Min size |
|---|---|---|---|
| **L3 · Master mark** | `assets/sih_logo.png` | Top-right of every page, y=10, x=1120 | 150×71 |
| **L3 · Team badge** | Drawn oval, `#7030A0` outline | Top-left of every page, (24, 18) | 150×56 |
| **L1 · Page 2** | SVG diagram — voice→text→link→voice pipeline | Left 60% of B zone | 740×430 |
| **L1 · Page 3** | SVG diagram — six-stage pipeline, speaker → link → listener | Full width of B zone | 1180×300 |
| **L1 · Page 4** | Real table (10 languages × 3 metrics) | Left 62% of B zone | 780×430 |

**All diagrams are built from Box rows with inline-SVG arrow glyphs** — they are structural
(architecture, flow), which the principles explicitly permit as vector rather than generated art.
This also mirrors how the official template draws its own pipelines. **No ImageGen is used anywhere
in this deck.** The content is measurements and architecture; stock imagery would be decorative and
would misrepresent a technical submission. This is a declared choice, not an omission.

Icons: Font Awesome via `FAIcon`, restricted to names verified against the component reference
(`check-circle`, `info-circle`, `exclamation-circle`, `chart-bar`, `clock`, `users`, `briefcase`,
`share`, `cog`, `shield`, `dollar`, `arrow-right`, `chevron-right`). All icons 28×28 and all filled
from the core palette — no mixed icon weights.

`assets/sih_logo.png` is the official 2026 SIH mark, extracted from the provided template. The
template also shipped a second mark reading **2022** — it is deliberately **not** used.

---

## 6. Page map (contract with `slides/*.slide`)

| # | File | Type | Role | Layout | L1 asset | Words | Whitespace | Colour allocation | Key constraint |
|---|---|---|---|---|---|---|---|---|---|
| 01 | `01.slide` | cover | hero | 全屏视觉+大标题 | `assets/sih_logo.png` (large, right) | 120 | 42% | navy 35% + accent 12% + neutral | Six PS metadata fields present and correct; custom cover master, no C zone |
| 02 | `02.slide` | content | supporting | 非对称双栏 60:40 | SVG pipeline (left 60%) | 195 | 22% | navy 45% + blue 20% + accent 15% | 49–111 bytes must be the page's largest figure; kept prompt labels verbatim |
| 03 | `03.slide` | content | supporting | 左标题+右内容 32:68 | SVG 6-stage pipeline | 245 | 18% | navy 50% + blue 25% + accent 5% | Stack listed with real sizes; VAD not the hero; no adjacent double-column repeat |
| 04 | `04.slide` | content | hero | 图表+洞察 | Real table, 10 rows | 265 | 16% | navy 40% + red/green chips 12% + accent 10% | v2 numbers only; sample size stated; risks stated; no v1 value anywhere |
| 05 | `05.slide` | content | supporting | N卡片横排 | FAIcon list + audience strip | 215 | 25% | navy 40% + blue 20% + accent 5% | Measured and unmeasured never share a card; no claim of lives saved |
| 06 | `06.slide` | content | supporting | 上下分栏 | Source chips | 205 | 30% | navy 35% + blue 15% + neutral | Licence stated per component incl. espeak-ng GPL; no placeholder links |

**Layout adjacency check:** 1→2→3→4→5→6 all differ. `N卡片横排` used once (cap 2). Asymmetric pages
2, 3, 6 = 50% (floor 40%). `左大图+右侧文字` + `非对称双栏` = 1 page (cap 2.4). Hero pages 1 and 4,
non-adjacent, 33% of the deck.
