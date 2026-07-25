#define MyAppName "نقل محلي Pro"
#define MyAppVersion "2.2.0"
#define MyAppPublisher "SendViaLocalNet"
#define MyAppExeName "SendViaLocalNet.exe"

[Setup]
AppId={{A6B2E4F5-790E-4B91-9AF5-2D2D46CC1C51}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
VersionInfoVersion=2.2.0.0
DefaultDirName={autopf}\SendViaLocalNet
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=release
OutputBaseFilename=SendViaLocalNet-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}
UsePreviousAppDir=yes
UsePreviousTasks=yes
CloseApplications=no
RestartApplications=no
RestartIfNeededByRun=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "إنشاء اختصار على سطح المكتب"; GroupDescription: "اختصارات إضافية:"; Flags: unchecked
Name: "startup"; Description: "تشغيل الاستقبال تلقائيًا عند تسجيل الدخول"; GroupDescription: "التشغيل التلقائي:"; Flags: unchecked

[Files]
Source: "dist\SendViaLocalNet.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "stop_running_app.ps1"; Flags: dontcopy

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{userstartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: startup

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "تشغيل {#MyAppName}"; Flags: nowait postinstall skipifsilent runascurrentuser

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  PowerShellPath: String;
  ScriptPath: String;
begin
  Result := '';
  ExtractTemporaryFile('stop_running_app.ps1');
  PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  ScriptPath := ExpandConstant('{tmp}\stop_running_app.ps1');

  Exec(
    PowerShellPath,
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + ScriptPath + '"',
    '',
    SW_HIDE,
    ewWaitUntilTerminated,
    ResultCode
  );

  Sleep(500);
end;
