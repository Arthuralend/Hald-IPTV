#define AppName "IPTV Pro Max"
#define AppVersion GetEnv("APP_VERSION")

[Setup]
AppId={{6F1C6B0A-3E1D-4E8B-9C21-7A0F2B5D9E44}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Arthuralend
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayIcon={app}\HaldIPTV.exe
OutputDir=..\dist
OutputBaseFilename=IPTV-Pro-Max-Setup
SetupIconFile=HaldIPTV\Assets\app.ico
Compression=lzma2/max
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
WizardStyle=modern
PrivilegesRequiredOverridesAllowed=dialog

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\out\win\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\HaldIPTV.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\HaldIPTV.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\HaldIPTV.exe"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
