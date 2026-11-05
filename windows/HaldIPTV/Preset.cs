// Generated at build time. Do not edit.
namespace HaldIPTV;
public static class Preset
{
    public const string Code = "22184";
    static readonly byte[] K = { 220, 105, 157, 218, 41, 16, 130, 211, 36, 23, 159, 197, 121, 77, 96, 186 };
    static readonly byte[] D = {  };
    public static string? Url => D.Length == 0 ? null
        : System.Text.Encoding.UTF8.GetString(D.Select((b, i) => (byte)(b ^ K[i % K.Length])).ToArray());
}
