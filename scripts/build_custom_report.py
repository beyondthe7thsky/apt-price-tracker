from pathlib import Path
import datetime
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
PAGES = ROOT / "pages"
PAGES.mkdir(parents=True, exist_ok=True)
KST = datetime.timezone(datetime.timedelta(hours=9))


def as_int(v, default=0):
    try:
        return int(v)
    except Exception:
        return default


def as_float(v, default=0.0):
    try:
        return float(v)
    except Exception:
        return default


def parse_dt(v):
    s = str(v or "").strip()
    if not s:
        return None
    try:
        dt = datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=KST)
        return dt.astimezone(KST)
    except Exception:
        return None


def seen_at(item):
    return parse_dt(item.get("lastSeenAt")) or parse_dt(item.get("updatedAt"))


def format_price(price_man):
    p = as_int(price_man, 0)
    if p < 10_000:
        return f"{p}만"
    eok = p // 10_000
    man = p % 10_000
    return f"{eok}억" if man == 0 else f"{eok}억 {man}만"


def format_area_sqm(v):
    v = as_float(v, 0.0)
    if v <= 0:
        return "-"
    return f"{v:.2f}".rstrip("0").rstrip(".")


def format_area_pyeong(v):
    v = as_float(v, 0.0)
    if v <= 0:
        return "-"
    return f"{v / 3.3058:.1f}".rstrip("0").rstrip(".")


def derive_pyeong(item):
    supply = as_float(item.get("areaSupplySqm", 0.0), 0.0)
    if supply > 0:
        return supply / 3.3058
    return float(as_int(item.get("pyeong", 0), 0))


def price_band(price):
    if price < 80_000:
        return "8억 미만"
    if price < 90_000:
        return "8~9억대"
    if price < 100_000:
        return "9~10억대"
    if price < 120_000:
        return "10~12억대"
    if price < 150_000:
        return "12~15억"
    if price < 200_000:
        return "15~20억"
    if price < 300_000:
        return "20~30억"
    return "30억 이상"


def pyeong_band(p):
    if 20 <= p < 30:
        return "20평대"
    if 30 <= p < 40:
        return "30평대"
    return "기타"


def normalize_status(v):
    s = str(v or "ACTIVE").strip().upper()
    return s if s in {"ACTIVE", "RELISTED", "OFF_MARKET_CANDIDATE", "OFF_MARKET"} else "ACTIVE"


def status_label(s):
    return {
        "ACTIVE": "판매중",
        "RELISTED": "다시 등록된 매물(판매중)",
        "OFF_MARKET_CANDIDATE": "거래종결 후보",
        "OFF_MARKET": "거래종결 추정",
    }.get(s, "판매중")


def normalize_tags(v):
    if isinstance(v, list):
        vals = v
    elif isinstance(v, str):
        vals = [x.strip() for x in v.split(",")]
    else:
        vals = []
    out = []
    seen = set()
    for x in vals:
        t = str(x).strip()
        if t and t not in seen:
            seen.add(t)
            out.append(t)
    return out


files = sorted(DATA.glob("apt-listings-s*.json"))
if not files:
    primary = DATA / "apt-listings.json"
    files = [primary] if primary.exists() else []
if not files:
    raise SystemExit("No apt listing JSON files found")

raw = []
for src in files:
    loaded = json.loads(src.read_text(encoding="utf-8"))
    if isinstance(loaded, list):
        raw.extend(x for x in loaded if isinstance(x, dict))

merged = {}
no_key = []
for item in raw:
    article = str(item.get("articleNo", "")).strip()
    if not article:
        no_key.append(item)
        continue
    prev = merged.get(article)
    if prev is None or str(item.get("updatedAt", "")) >= str(prev.get("updatedAt", "")):
        merged[article] = item

items = list(merged.values()) + no_key
items = [x for x in items if 20.0 <= derive_pyeong(x) <= 39.999]
live = [x for x in items if normalize_status(x.get("status")) in {"ACTIVE", "RELISTED"}]
live.sort(key=lambda x: seen_at(x) or datetime.datetime.min.replace(tzinfo=KST), reverse=True)


def complex_key(item):
    region = str(item.get("regionName", "")).strip()
    hscp = str(item.get("hscpNo", "")).strip()
    title = str(item.get("title", "")).strip()
    return f"{region}::{hscp or title}"


price_map = {}
for it in live:
    p = as_int(it.get("price", 0), 0)
    if p > 0:
        price_map.setdefault(complex_key(it), []).append(p)

rows = []
for it in live:
    region = str(it.get("regionName", ""))
    city = region.split("_", 1)[0] if "_" in region else region
    price = as_int(it.get("price", 0), 0)
    supply = as_float(it.get("areaSupplySqm", 0.0), 0.0)
    exclusive = as_float(it.get("areaExclusiveSqm", it.get("areaSqm", 0.0)), 0.0)
    prices = price_map.get(complex_key(it), [])
    avg = round(sum(prices) / len(prices)) if prices else 0
    diff = ((price - avg) / avg * 100.0) if avg else 0.0
    signal = "급매" if diff <= -10 else ("저렴" if diff <= -5 else "보통")
    article_no = str(it.get("articleNo", "")).strip()
    hscp_no = str(it.get("hscpNo", "")).strip()
    tags = normalize_tags(it.get("tagList", []))
    last_seen = seen_at(it)
    rows.append({
        "city": city,
        "region": region,
        "title": str(it.get("title", ""))[:64],
        "featureDesc": str(it.get("featureDesc", ""))[:90],
        "tagText": " · ".join(tags[:4]) if tags else "-",
        "price": price,
        "priceText": format_price(price),
        "avgPrice": avg,
        "avgPriceText": format_price(avg) if avg else "-",
        "diffPct": round(diff, 2),
        "signal": signal,
        "statusLabel": status_label(normalize_status(it.get("status"))),
        "priceBand": price_band(price),
        "pyeongBand": pyeong_band(derive_pyeong(it)),
        "areaSupplySqmText": format_area_sqm(supply),
        "areaExclusiveSqmText": format_area_sqm(exclusive),
        "areaSupplyPyeong": (supply / 3.3058) if supply > 0 else 0.0,
        "areaSupplyPyeongText": format_area_pyeong(supply),
        "areaExclusivePyeongText": format_area_pyeong(exclusive),
        "floor": str(it.get("floor", "")),
        "articleNo": article_no,
        "hscpNo": hscp_no,
        "lastSeenAt": last_seen.isoformat() if last_seen else "",
        "url": f"https://m.land.naver.com/article/info/{article_no}" if article_no else "",
        "complexUrl": f"https://m.land.naver.com/complex/info/{hscp_no}?tradTpCd=A1&order=prc" if hscp_no else "",
    })

(PAGES / "report-data.json").write_text(
    json.dumps(rows, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)

index = PAGES / "index.html"
html = index.read_text(encoding="utf-8")
html = html.replace("https://saechimdaeki.github.io/apt-price-tracker/", "https://beyondthe7thsky.github.io/apt-price-tracker/")
html = html.replace(
    "            <option>12억 이상</option>",
    "            <option>12~15억</option>\n            <option>15~20억</option>\n            <option>20~30억</option>\n            <option>30억 이상</option>",
)
html = html.replace(
    '<option>12억 이상</option>',
    '<option>12~15억</option><option>15~20억</option><option>20~30억</option><option>30억 이상</option>',
)

old_full = "const fullUrl=(u)=>{const t=String(u??'').trim(); if(!t) return '#'; return t.startsWith('/') ? `https://m.land.naver.com${t}` : t;};"
new_full = "const fullUrl=(u)=>{const t=String(u??'').trim(); if(!t) return '#'; return t.startsWith('/') ? `https://m.land.naver.com${t}` : t;}; const complexUrl=(row)=>{const t=String(row?.complexUrl??'').trim(); if(t)return t; const h=String(row?.hscpNo??'').trim(); return h?`https://m.land.naver.com/complex/info/${h}?tradTpCd=A1&order=prc`:'#';};"
html = html.replace(old_full, new_full)

old_cell = '<td data-label="매물" class="link-col"><a href="${esc(fullUrl(it.url))}" target="_blank">상세보기</a></td><td data-label="지도" class="link-col"><a href="${esc(mapUrl(it))}" target="_blank">지도</a></td>'
new_cell = '<td data-label="매물" class="link-col"><a href="${esc(fullUrl(it.url))}" target="_blank" rel="noopener">상세보기</a><br><a href="${esc(complexUrl(it))}" target="_blank" rel="noopener">단지매물(매매·낮은가격순)</a><br><span class="dim" style="font-size:11px">No.${esc(it.articleNo||\'-\')}</span></td><td data-label="지도" class="link-col"><a href="${esc(mapUrl(it))}" target="_blank">지도</a></td>'
if old_cell not in html:
    raise SystemExit("listing link cell template not found")
html = html.replace(old_cell, new_cell, 1)

now_text = datetime.datetime.now(KST).strftime("%Y-%m-%d %H:%M KST")
html = re.sub(
    r"기준시각: [^/]+ / 노출 매물 [0-9,]+건 / 필터: [^<]+",
    f"기준시각: {now_text} / 노출 매물 {len(rows):,}건 / 필터: 없음",
    html,
    count=1,
)
index.write_text(html, encoding="utf-8")
print(f"custom report built: {len(rows)} rows with sale+price-order complex links")
