# ============================================================================
# BlokQR - Application de la refonte descriptive dans les 12 strings.xml
# ============================================================================
# Lit descriptions_i18n.json (place dans le MEME dossier que ce script) et,
# pour chaque locale, met a jour les 8 cles :
#   - si la cle existe deja  -> remplace sa valeur (onb1_body, about_description...)
#   - si la cle est nouvelle -> insere avant </resources>
#     (about_feature_offline, privacy_s8_title, privacy_s8_body)
#
# Idempotent (re-executable sans creer de doublons). Sauvegarde chaque fichier
# en .bak_desc avant modification. Ecrit en UTF-8 SANS BOM.
#
# Script volontairement 100% ASCII (les valeurs accentuees sont dans le JSON,
# lu en UTF-8) pour eviter tout probleme d'encodage de PowerShell 5.1.
#
# USAGE :
#   1. Placer descriptions_i18n.json et ce script dans le meme dossier.
#   2. Adapter $res si besoin.
#   3. .\apply_descriptions_i18n.ps1
# ============================================================================

$ErrorActionPreference = "Stop"

$res  = "D:\blokqr\android\app\src\main\res"
$json = Join-Path $PSScriptRoot "descriptions_i18n.json"

if (-not (Test-Path $json)) { Write-Host "JSON introuvable : $json" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $res))  { Write-Host "Dossier res introuvable : $res" -ForegroundColor Red; exit 1 }

# Lecture des donnees (UTF-8).
$data = Get-Content $json -Raw -Encoding UTF8 | ConvertFrom-Json
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$totalUpd = 0
$totalIns = 0

foreach ($localeProp in $data.PSObject.Properties) {
    $locale = $localeProp.Name
    $keys   = $localeProp.Value
    $file   = Join-Path $res (Join-Path $locale "strings.xml")

    if (-not (Test-Path $file)) {
        Write-Host "SKIP $locale (strings.xml absent)" -ForegroundColor Yellow
        continue
    }

    $content = [System.IO.File]::ReadAllText($file)
    Copy-Item $file "$file.bak_desc" -Force

    $upd = 0; $ins = 0
    foreach ($keyProp in $keys.PSObject.Properties) {
        $key = $keyProp.Name
        $val = [string]$keyProp.Value
        $elem = '    <string name="' + $key + '">' + $val + '</string>'

        $pattern = '(?s)<string name="' + [regex]::Escape($key) + '">.*?</string>'

        if ([regex]::IsMatch($content, $pattern)) {
            $content = [regex]::Replace($content, $pattern, { param($m) $elem })
            $upd++
        } else {
            $content = [regex]::Replace($content, '</resources>', { param($m) "$elem`r`n</resources>" }, 1)
            $ins++
        }
    }

    [System.IO.File]::WriteAllText($file, $content, $utf8NoBom)
    Write-Host "OK $locale : $upd remplacees, $ins inserees" -ForegroundColor Green
    $totalUpd += $upd; $totalIns += $ins
}

Write-Host ""
Write-Host "TERMINE - $totalUpd remplacees, $totalIns inserees au total." -ForegroundColor Cyan
Write-Host "Sauvegardes : fichiers .bak_desc dans chaque dossier values." -ForegroundColor Cyan
