"""
Run Gemini Flash-Lite attribute extraction against real titles from your
PriceWatch dashboard, then check whether the extracted attributes
correctly separate the three iPhone 17 Pro Max entries (256GB, 512GB AED,
512GB GBP) — two should match, one shouldn't.

Run: uv run ./real_catalog_extraction_test.py
"""

import json
import os
import time
import requests
from dotenv import load_dotenv

load_dotenv()
API_KEY = os.getenv("Gemini_API_KEY")
MODEL = "gemini-2.5-flash-lite"

# Real titles pulled directly from your dashboard, unedited.
TITLES = [
    "Cuisinart® Chef's Classic™ Stainless Steel Cookware Set | 11-piece: Saucepans, Frying Pans, Stockpot, Steamer | Induction-Ready, Aluminium-encapsulated Base | Cool Grip™ Handles",
    "MCD Wrist Supports Adjustable, Lightweight Hand & Wrist Brace Support Wrist Compression Support Wrist Brace for Carpal Tunnel Wrist Support Right Hand & Left Hands for Arthritis, Gym, Sports, & Work",
    "Sony ZV-1F Compact Digital Camera 4K Video White",
    "LENOVO IdeaPad Slim 3 14\" Chromebook & Sleeve - Intel® Core™ i3, 128 GB eMMC, Storm Grey",
    "Apple iPhone 17 Pro Max 256 GB: 6.9-inch Display with ProMotion, A19 Pro Chip, Best Battery Life in Any iPhone Ever, Pro Fusion Camera System, Center Stage Front Camera; Cosmic Orange",
    "Adjustable iPad Stand Tablet Holder 360°Rotation for Desk, Black | Stability Metal Base, Fully Foldable, Easy to Store and Carry, Widely Compatibility, Fit for All 4-16\" Devices",
    "DREO Smart Humidifier for Bedroom Baby, 4L Cool Mist Humidifiers for Home | Top Fill, Quiet Sleep 28 dB, 36H Runtime for large room, Touch/APP/Voice Control, Nightlight, ideal for Baby, Plants, Nursery",
    "5 BANKERS BOX EXTRA LARGE Strong Moving Boxes, 100L SmoothMove Cardboard for Packing & Moving, Heavy Duty Double Wall for Moving House with Handles, 70cm x 44cm x 33cm (Pack of 5), Brown",
    "Tower Casserole Dish with Lid, Linear Collection with Easy Clean Non-Stick Ceramic Coating, Aluminium, Black and Rose Gold, 4 Litres",
    "Garmin Forerunner 165, 43mm GPS running smartwatch, lightweight, AMOLED touchscreen, advanced training, insights & features, safety & tracking features, up to 11 days battery life, Black",
    "Sony ZV-1F Compact Digital Camera 4K Video Black",
    "SteelSeries Aerox 3 Wireless Gen 2 - Super-Light 68g Gaming Mouse - 4K Polling - 26K DPI Optical Sensor - 200 Hour Battery - IP54 Water Resistant - 80m Click Durability - RGB - Shadow",
    "ALDO Rakersgrip mens Sneaker",
    "Adjustable Laptop Stand for Desk- Foldable Aluminum Laptop Stand Silver- Lightweight, Portable Laptop RiserDurable Laptop Holder Adjustable Compatible with MacBook, Dell, HP & More- 27x5.5x2.5cm",
    "EPOMAKER Ajazz AK820 Pro Wireless Mechanical Keyboard with TFT Display&Knob",
    "SONY WH-1000XM6 Flagship Noise Cancelling Over-Ear Wireless Bluetooth Headphones, Signature Hi-Res Sound, Comfort, Foldable Design, Durable Case, 30 HR Battery NC On, iOS & Android – Black",
    "TABWEE T60 PRO Android 16 Tablet with Keyboard, 13.4-Inch 2K 120Hz IPS Display, 24GB RAM 256GB Storage, 2TB Expandable, 2.2GHz Octa-Core, 10000mAh, Gemini AI, Stylus and Mouse Included",
    "Lacoste Womens NF2791AAAnna Clutch (pack of 1)",
    "MSI Stealth 16 AI - 16\" 240 Hz OLED Display - GeForce RTX 5060 Laptop GPU - Intel Core Ultra7-255H - 32GB DDR5 - 1TB NVMe SSD - Win 11 Home (Stealth 16 AI A2HWFG-041US )",
    "Sansui S55VOUG 55\" 4K Smart OLED TV",
    "FeelTek Elite Magnetic and Clip-on Cooler Smartphone Game Accessory",
    "Apple Watch SE 3 44 Smartwatch",
    "Apple iPhone 17 Pro Max 512 GB: 6.9-inch Display with ProMotion, A19 Pro Chip, Best Battery Life in Any iPhone Ever, Pro Fusion Camera System, Center Stage Front Camera; Cosmic Orange",
    "APPLE iPhone 17 Pro Max - 512 GB, Cosmic Orange",
]

PROMPT_TEMPLATE = """Extract product attributes from this title as a JSON object.
Only include fields that are ACTUALLY PRESENT in the title text — do not guess or infer a value that isn't there.

Fields to look for:
- brand: the manufacturer/brand name
- model: the specific model name/number (not the brand)
- capacity: any quantifiable spec — storage (GB/TB), volume (L/ml), weight (g/kg), count (e.g. "11-piece", "Pack of 5"), or similar
- size: physical dimensions, screen size, or clothing/shoe size if present
- color: the color/finish

Title: "{title}"

Respond with ONLY the raw JSON object. No markdown, no code fences, no explanation."""


def extract(title, api_key, retries=3):
    prompt = PROMPT_TEMPLATE.format(title=title)
    for attempt in range(retries):
        r = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={api_key}",
            json={"contents": [{"parts": [{"text": prompt}]}]},
        )
        if r.status_code == 200:
            text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
            # Strip markdown code fences if the model added them anyway
            text = text.strip()
            if text.startswith("```"):
                text = text.split("```")[1]
                if text.startswith("json"):
                    text = text[4:]
            try:
                return json.loads(text.strip())
            except json.JSONDecodeError:
                return {"_raw_unparseable": text}
        time.sleep(2 * (attempt + 1))
    return {"_error": f"{r.status_code} {r.text}"}


print(f"Extracting attributes from {len(TITLES)} real catalog titles...\n")

extracted = []
for i, title in enumerate(TITLES):
    attrs = extract(title, API_KEY)
    extracted.append((title, attrs))
    short = title[:60] + "..." if len(title) > 60 else title
    print(f"[{i+1}/{len(TITLES)}] {short}")
    print(f"    -> {json.dumps(attrs)}\n")
    time.sleep(0.5)

# ---------------------------------------------------------------------------
# The real test: do the three iPhone entries get correctly distinguished?
# ---------------------------------------------------------------------------
print("\n" + "=" * 70)
print("REAL-WORLD TEST: your three iPhone 17 Pro Max catalog entries")
print("=" * 70)

iphones = [(t, a) for t, a in extracted if "iPhone 17 Pro Max" in t]
for title, attrs in iphones:
    print(f"\n{title[:70]}...")
    print(f"  Extracted: {json.dumps(attrs)}")


def hard_mismatch(a, b):
    for key in ("capacity", "model"):
        va, vb = a.get(key), b.get(key)
        if va and vb and va.strip().lower() != vb.strip().lower():
            return True, key, va, vb
    return False, None, None, None


print("\n--- Pairwise comparison ---")
for i in range(len(iphones)):
    for j in range(i + 1, len(iphones)):
        title_i, attrs_i = iphones[i]
        title_j, attrs_j = iphones[j]
        mismatch, key, va, vb = hard_mismatch(attrs_i, attrs_j)
        verdict = f"REJECT (differ on {key}: {va!r} vs {vb!r})" if mismatch else "PASS (no attribute conflict — same product)"
        print(f"\n  [{i+1}] vs [{j+1}]: {verdict}")
        print(f"    [{i+1}] {title_i[:50]}...")
        print(f"    [{j+1}] {title_j[:50]}...")

print("\nExpected: 256GB entry should REJECT against both 512GB entries.")
print("Expected: the two 512GB entries (AED store, GBP/Currys store) should PASS.")
