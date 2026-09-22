#!/usr/bin/env python3
"""Re-scrape the SIH 2026 problem statement list from https://sih.gov.in/sih2026PS.

Each row of the live table embeds a hidden modal with the full problem statement.
Output: output/sih2026_YYYY-MM-DD.json (schema mirrors output/sih2026_today.json).
"""

import json
import re
import sys
from datetime import date
from pathlib import Path

from scrapling.fetchers import Fetcher
from scrapling.parser import Selector

URL = "https://sih.gov.in/sih2026PS"
OUTPUT_DIR = Path(__file__).resolve().parent


def clean(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def field(row: Selector, idx: int, total: int) -> str:
    """Get text of the idx-th plain (non-modal) td, counting from the end (1 = last)."""
    plain_tds = [td for td in row.css("td") if "colomn_border" not in td.attrib.get("class", "")]
    return clean(str(plain_tds[-idx].get_all_text(separator=" ", strip=True)))


def modal_field(modal: Selector, label: str) -> str:
    for tr in modal.css(".modal-body table tr"):
        ths = tr.css("th")
        if not ths:
            continue
        if clean(str(ths[0].css("::text").get())) == label:
            tds = tr.css("td")
            if not tds:
                return ""
            hrefs = tds[0].css("a::attr(href)").getall()
            if hrefs and str(hrefs[0]).strip():
                return clean(str(hrefs[0]))
            return clean(str(tds[0].get_all_text(separator=" ", strip=True)))
    return ""


def parse_page(page: Selector) -> dict:
    hardware = clean(str(page.css(".hard h3::text").get() or ""))
    software = clean(str(page.css(".soft h3::text").get() or ""))
    records = []
    for row in page.css("#dataTablePS > tbody > tr"):
        cells = row.css("td.colomn_border")
        if len(cells) < 3:
            continue
        title_cell = cells[2]
        link = title_cell.css("a[data-target]")
        if not link:
            continue
        ps_id = clean(str(link[0].attrib.get("data-target", ""))).replace("#ViewProblemStatement", "")
        plain_tds = [td for td in row.css("td") if "colomn_border" not in td.attrib.get("class", "")]
        if not plain_tds:
            continue
        total = len(plain_tds)
        # trailing five plain cells are stable across rows (the SIH26066-style row
        # embeds an extra data table, so count from the end)
        category = field(row, 5, total)
        ps_number = field(row, 4, total)
        submissions = field(row, 3, total)   # e.g. "120/500"
        theme = field(row, 2, total)
        deadline = field(row, 1, total)
        modal = title_cell.css(".modal")
        record = {
            "ps_id": f"SIH{ps_id}" if ps_id and not ps_id.startswith("SIH") else ps_id,
            "title": clean(str(link[0].css("::text").get() or "")),
            "organization": clean(str(cells[1].get_all_text(separator=" ", strip=True))),
            "category": category,
            "theme": theme,
            "submissions": submissions,
            "deadline": deadline,
            "has_youtube": False,
            "has_dataset": False,
        }
        if modal:
            record["description"] = modal_field(modal[0], "Description")
            record["department"] = modal_field(modal[0], "Department")
            youtube = modal_field(modal[0], "Youtube Link")
            dataset = modal_field(modal[0], "Dataset Link")
            record["youtube_link"] = youtube
            record["dataset_link"] = dataset
            record["has_youtube"] = bool(youtube)
            record["has_dataset"] = bool(dataset)
        else:
            record["description"] = ""
            record["department"] = ""
            record["youtube_link"] = ""
            record["dataset_link"] = ""
        records.append(record)

    return {
        "source": URL,
        "scraped_on": date.today().isoformat(),
        "total_ps": len(records),
        "hardware": hardware,
        "software": software,
        "theme_distribution": {},
        "records": records,
    }


def main() -> int:
    print(f"Fetching {URL} ...")
    page = Fetcher.get(URL, timeout=60, impersonate="chrome")
    if page.status != 200:
        print(f"ERROR: HTTP {page.status}", file=sys.stderr)
        return 1
    data = parse_page(page)
    if not data["records"]:
        print("ERROR: no problem statements parsed - page structure changed?", file=sys.stderr)
        return 1
    if data["total_ps"] < 200:
        print(f"WARNING: only {data['total_ps']} rows parsed", file=sys.stderr)

    submissions_total = 0
    themes: dict[str, int] = {}
    for rec in data["records"]:
        m = re.match(r"(\d+)\s*/\s*(\d+)", rec.get("submissions", ""))
        if m:
            submissions_total += int(m.group(1))
        t = rec.get("theme")
        if t:
            themes[t] = themes.get(t, 0) + 1
    data["theme_distribution"] = dict(sorted(themes.items(), key=lambda kv: -kv[1]))
    data["total_submissions"] = submissions_total
    data["deadline"] = next((r["deadline"] for r in data["records"] if r["deadline"]), "")
    most = max(data["records"], key=lambda r: int(re.match(r"(\d+)", r.get("submissions", "0")).group(1)) if re.match(r"(\d+)", r.get("submissions", "0")) else 0)
    data["most_submitted"] = most["ps_id"] if most else None

    out_path = OUTPUT_DIR / f"sih2026_{date.today().isoformat()}.json"
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
    print(f"Saved {out_path} ({data['total_ps']} problem statements, {submissions_total} total submissions)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
