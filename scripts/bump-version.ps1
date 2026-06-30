param(
    [string]$PropertiesPath = (Join-Path (Get-Location) 'gradle.properties')
)

$lines = Get-Content $PropertiesPath
$match = $lines | Where-Object { $_ -match '^pluginVersion\s*=?\s*(\d+)\.(\d+)\.(\d+)\s*$' } | Select-Object -First 1
if (-not $match) {
    Write-Error 'pluginVersion not found in gradle.properties'
    exit 1
}

$m = [regex]::Match($match, '^pluginVersion\s*=?\s*(\d+)\.(\d+)\.(\d+)\s*$')
$major = $m.Groups[1].Value
$minor = $m.Groups[2].Value
$patch = [int]$m.Groups[3].Value + 1
$newVersion = "$major.$minor.$patch"

$updated = $lines | ForEach-Object {
    if ($_ -match '^pluginVersion\s*=?\s*') { "pluginVersion = $newVersion" } else { $_ }
}
$updated | Set-Content $PropertiesPath -Encoding UTF8
Write-Output $newVersion