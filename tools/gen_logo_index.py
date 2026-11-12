"""Builds a compact channel-logo index from the open iptv-org database for the Android TV app.
Output: android/app/src/main/assets/logos.tsv   (normalized name <TAB> id1 url1 <TAB> id2 url2 ...)
Usage: python3 tools/gen_logo_index.py [channels.json logos.json]"""
import json, re, sys, os, urllib.request

TR = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ",
              ["a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","shch","","y","","e","yu","ya","i","yi","ye","g"]))
QUALITY = {"uhd", "fhd", "hd", "4k", "sd", "hevc", "orig", "original", "50", "60", "hq"}
COUNTRY = re.compile(r"[a-z]{2}")   # trailing country code: "France 24 HD FR"
GENERIC = {"телеканал", "канал", "tv", "тв", "channel"}

def words_of(s):
    s = s.lower().replace("ё", "е")
    s = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", s)
    s = re.sub(r"\s\+\s*\d+.*$", " ", s)               # "Россия 1 +2 (Тамбов)" -> "Россия 1"
    w = [x for x in re.split(r"[^\w+!]+", s) if x]
    w = [x.replace("+", "plus") if x == "+" else x for x in w]
    while len(w) > 1 and (w[-1] in QUALITY or COUNTRY.fullmatch(w[-1])): w.pop()
    if w and w[0] == "vf": w = w[1:] or w                # "VF ..." provider prefix
    return w

def norm(s):
    return re.sub(r"[^\w]", "", "".join(words_of(s)))

def variants(s):
    """name keys tried in order: exact, without generic words like 'TV'/'канал'"""
    w = words_of(s)
    out = [norm(s)]
    g = [x for x in w if x not in GENERIC]
    if g and g != w: out.append(re.sub(r"[^\w]", "", "".join(g)))
    return [k for k in out if k]

def translit(s): return "".join(TR.get(c, c) for c in s)

def load(name, path=None):
    if path: return json.load(open(path))
    for m in ("https://iptv-org.github.io/api/", "https://raw.githubusercontent.com/iptv-org/api/gh-pages/"):
        try: return json.load(urllib.request.urlopen(m + name, timeout=120))
        except Exception: pass
    raise SystemExit("cannot download " + name)

def build(channels, logos):
    urls = {}
    for l in logos:
        if l.get("in_use") is False: continue
        if not l.get("feed") or l["channel"] not in urls: urls[l["channel"]] = l["url"]
    idx = {}
    for c in channels:
        u = urls.get(c["id"])
        if not u: continue
        for n in [c["name"]] + (c.get("alt_names") or []):
            for key in {x for k in variants(n) for x in (k, translit(k))}:
                lst = idx.setdefault(key, [])
                if len(lst) < 12 and all(i != c["id"] for i, _ in lst): lst.append((c["id"], u))
    return idx

if __name__ == "__main__":
    a = sys.argv[1:]
    idx = build(load("channels.json", a[0] if a else None), load("logos.json", a[1] if len(a) > 1 else None))
    out = "android/app/src/main/assets/logos.tsv"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        for k in sorted(idx):
            f.write(k + "\t" + "\t".join(f"{i} {u}" for i, u in idx[k]) + "\n")
    print(len(idx), "keys,", os.path.getsize(out) // 1024, "KB")
