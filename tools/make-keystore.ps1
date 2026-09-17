# Generates the self-signed release keystore.
#
# The key is not in source control, so this has to run once before `assembleRelease` produces an
# installable APK. Without it the release build still compiles, it is just left unsigned.
#
# Run from the repo root:  powershell -File tools/make-keystore.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $root "termfold-release.jks"

if (Test-Path $keystore) {
    Write-Host "Keystore already exists: $keystore"
    exit 0
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    Write-Error "JAVA_HOME is not set; point it at a JDK."
}

& "$javaHome\bin\keytool.exe" `
    -genkeypair `
    -keystore $keystore `
    -alias termfold `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -storepass termfold `
    -keypass termfold `
    -dname "CN=TermFold, OU=App, O=TermFold, L=Unknown, ST=Unknown, C=IN"

Write-Host "Wrote $keystore"
