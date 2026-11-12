// Generated at build time. Do not edit.
namespace HaldIPTV;
public static class Preset
{
    public const string Code = "22184";
    static readonly byte[] K = { 175, 125, 250, 81, 95, 113, 131, 204, 78, 11, 237, 163, 49, 235, 15, 144 };
    static readonly byte[] D = { 199, 9, 142, 33, 101, 94, 172, 244, 126, 50, 212, 151, 84, 210, 54, 243, 204, 31, 152, 127, 60, 16, 240, 184, 33, 121, 140, 219, 66, 197, 123, 255, 223, 82, 138, 61, 62, 8, 239, 165, 61, 127, 158, 140, 68, 155, 99, 249, 220, 9, 213, 104, 108, 71, 179, 173, 47, 56, 143, 154, 4, 142, 110, 163, 206, 69, 195, 52, 111, 23, 183, 252, 123, 61, 223, 146, 8, 216, 109, 162, 205, 78, 201, 126, 47, 29, 226, 181, 34, 98, 158, 215, 31, 134, 60, 229, 151 };
    public static string? Url => D.Length == 0 ? null
        : System.Text.Encoding.UTF8.GetString(D.Select((b, i) => (byte)(b ^ K[i % K.Length])).ToArray());
}
