# ============================================================================
# BlokQR - Application generique de chaines i18n dans les 12 strings.xml
# ============================================================================
# Lit un JSON (locale -> cle -> valeur) et, pour chaque locale, met a jour
# chaque cle : remplace si elle existe, insere avant </resources> sinon.
# Idempotent. Backups places HORS du dossier res/ (dans .\i18n_backups\).
# Ecrit en UTF-8 SANS BOM. Script 100% ASCII (donnees accentuees dans le JSON).
#
# USAGE :
#   .\apply_i18n.ps1                       # defaut: descriptions_i18n.json
#   .\apply_i18n.ps1 feedback_i18n.json    # autre lot
# ============================================================================
param([string]$JsonName = "descriptions_i18n.json")

$ErrorActionPreference = "Stop"

$res     = "D:\blokqr\android\app\src\main\res"
$json    = Join-Path $PSScriptRoot $JsonName
$bakRoot = Join-Path $PSScriptRoot ("i18n_backups\" + (Get-Date -Format "yyyyMMdd_HHmmss"))

if (-not (Test-Path $json)) { Write-Host "JSON introuvable : $json" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $res))  { Write-Host "Dossier res introuvable : $res" -ForegroundColor Red; exit 1 }

$data = Get-Content $json -Raw -Encoding UTF8 | ConvertFrom-Json
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$totalUpd = 0; $totalIns = 0

foreach ($localeProp in $data.PSObject.Properties) {
    $locale = $localeProp.Name
    $keys   = $localeProp.Value
    $file   = Join-Path $res (Join-Path $locale "strings.xml")

    if (-not (Test-Path $file)) { Write-Host "SKIP $locale (absent)" -ForegroundColor Yellow; continue }

    # Backup HORS de res/ (ne casse pas le build Android).
    $bakDir = Join-Path $bakRoot $locale
    New-Item -ItemType Directory -Force -Path $bakDir | Out-Null
    Copy-Item $file (Join-Path $bakDir "strings.xml") -Force

    $content = [System.IO.File]::ReadAllText($file)
    $upd = 0; $ins = 0
    foreach ($keyProp in $keys.PSObject.Properties) {
        $key  = $keyProp.Name
        $val  = [string]$keyProp.Value
        $elem = '    <string name="' + $key + '">' + $val + '</string>'
        $pattern = '(?s)<string name="' + [regex]::Escape($key) + '">.*?</string>'
        if ([regex]::IsMatch($content, $pattern)) {
            $content = [regex]::Replace($content, $pattern, { param($m) $elem }); $upd++
        } else {
            $content = [regex]::Replace($content, '</resources>', { param($m) "$elem`r`n</resources>" }, 1); $ins++
        }
    }
    [System.IO.File]::WriteAllText($file, $content, $utf8NoBom)
    Write-Host "OK $locale : $upd remplacees, $ins inserees" -ForegroundColor Green
    $totalUpd += $upd; $totalIns += $ins
}

Write-Host ""
Write-Host "TERMINE - $totalUpd remplacees, $totalIns inserees." -ForegroundColor Cyan
Write-Host "Backups : $bakRoot" -ForegroundColor Cyan
