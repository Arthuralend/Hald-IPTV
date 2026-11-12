"""Writes the hidden preset playlist into the app sources at build time.
The URL comes from the PRESET_URL secret and is never stored in the repository.
Usage: PRESET_URL=... python3 tools/gen_preset.py"""
import os

url = os.environ.get("PRESET_URL", "").strip()
if not url:
    print("PRESET_URL not set: keeping the preset already in the sources"); raise SystemExit(0)
key = os.urandom(16)
enc = bytes(b ^ key[i % len(key)] for i, b in enumerate(url.encode()))

def arr(b):
    return ", ".join(str(x) for x in b)

open("HaldIPTV/Sources/Preset.swift", "w").write(f"""// Generated at build time. Do not edit.
enum Preset {{
    static let code = "22184"
    private static let k: [UInt8] = [{arr(key)}]
    private static let d: [UInt8] = [{arr(enc)}]
    static var url: String? {{
        guard !d.isEmpty else {{ return nil }}
        return String(decoding: d.enumerated().map {{ $0.element ^ k[$0.offset % k.count] }}, as: UTF8.self)
    }}
}}
""")

open("windows/HaldIPTV/Preset.cs", "w").write(f"""// Generated at build time. Do not edit.
namespace HaldIPTV;
public static class Preset
{{
    public const string Code = "22184";
    static readonly byte[] K = {{ {arr(key)} }};
    static readonly byte[] D = {{ {arr(enc)} }};
    public static string? Url => D.Length == 0 ? null
        : System.Text.Encoding.UTF8.GetString(D.Select((b, i) => (byte)(b ^ K[i % K.Length])).ToArray());
}}
""")
print("preset:", "set" if url else "empty")
