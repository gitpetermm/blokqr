# renommer_guides.ps1 — Prépare le dossier d'upload des guides BlokQR.
# Usage :
#   powershell -ExecutionPolicy Bypass -File .\renommer_guides.ps1 -Source "D:\blokqr\guides" -Dest "D:\blokqr\guides\upload"
# -Source : dossier contenant les 12 PDF livrés (noms longs).
# -Dest   : dossier créé avec les 12 fichiers renommés fr.pdf ... hi.pdf.
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Dest
)
$map = @{
    "BlokQR_Guide_Utilisateur_FR.pdf"    = "fr.pdf"
    "BlokQR_User_Guide_EN.pdf"           = "en.pdf"
    "BlokQR_Guia_de_uso_ES.pdf"          = "es.pdf"
    "BlokQR_Guia_de_utilizacao_PT.pdf"   = "pt.pdf"
    "BlokQR_Guida_uso_IT.pdf"            = "it.pdf"
    "BlokQR_Benutzerhandbuch_DE.pdf"     = "de.pdf"
    "BlokQR_Rukovodstvo_RU.pdf"          = "ru.pdf"
    "BlokQR_Kullanim_Kilavuzu_TR.pdf"    = "tr.pdf"
    "BlokQR_Shiyong_Zhinan_ZH.pdf"       = "zh.pdf"   # Locale Android "zh" (values-zh-rCN)
    "BlokQR_Tsukaikata_Guide_JA.pdf"     = "ja.pdf"
    "BlokQR_Dalil_Istikhdam_AR.pdf"      = "ar.pdf"
    "BlokQR_Upyogkarta_Guide_HI.pdf"     = "hi.pdf"
}
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$ok = 0; $missing = @()
foreach ($k in $map.Keys) {
    $src = Join-Path $Source $k
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $Dest $map[$k]) -Force
        $ok++
    } else {
        $missing += $k
    }
}
Write-Host "Copiés/renommés : $ok / 12 vers $Dest"
if ($missing.Count -gt 0) {
    Write-Host "MANQUANTS :" -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    exit 1
}
Write-Host "Upload ensuite (exemple) :"
Write-Host "  scp $Dest\*.pdf user@serveur:/var/www/blokqr/guide/"
