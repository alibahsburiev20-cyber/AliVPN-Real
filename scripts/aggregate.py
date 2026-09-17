from pathlib import Path
from urllib.request import Request, urlopen

sources_file = Path("sources/sources.txt")
out_file = Path("output/all.txt")
out_file.parent.mkdir(parents=True, exist_ok=True)

configs = []
for line in sources_file.read_text(encoding="utf-8").splitlines():
    url = line.strip()
    if not url or url.startswith("#"):
        continue
    try:
        req = Request(url, headers={"User-Agent": "AliVPN-Bot/0.2"})
        data = urlopen(req, timeout=30).read().decode("utf-8", "ignore")
        for item in data.splitlines():
            item = item.strip()
            if item and not item.startswith("#"):
                configs.append(item)
    except Exception as exc:
        print(f"source failed: {url}: {exc}")

unique = list(dict.fromkeys(configs))
out_file.write_text("\n".join(unique) + ("\n" if unique else ""), encoding="utf-8")
print(f"Collected {len(unique)} unique configs")
