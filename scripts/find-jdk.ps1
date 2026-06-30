function Get-JavaMajor {
    param([string]$JavaExe)
    $out = & $JavaExe -version 2>&1 | Out-String
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    if ($out -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    return 0
}

$candidates = @()

$roots = @('C:\Program Files\JetBrains', 'C:\Program Files (x86)\JetBrains')
$preferred = @('GoLand', 'IntelliJ IDEA', 'RustRover', 'WebStorm', 'PyCharm', 'CLion', 'Rider')

foreach ($root in $roots) {
    if (-not (Test-Path $root)) { continue }
    Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $java = Join-Path $_.FullName 'jbr\bin\java.exe'
        if (-not (Test-Path $java)) { return }
        $major = Get-JavaMajor $java
        if ($major -ge 17 -and $major -le 21) {
            $idx = 999
            for ($i = 0; $i -lt $preferred.Count; $i++) {
                if ($_.Name -like ($preferred[$i] + '*')) { $idx = $i; break }
            }
            $candidates += [PSCustomObject]@{
                JavaHome = Join-Path $_.FullName 'jbr'
                Major    = $major
                Sort     = $idx
                Name     = $_.Name
            }
        }
    }
}

$gradleJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
if (Test-Path $gradleJdks) {
    Get-ChildItem $gradleJdks -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $java = Join-Path $_.FullName 'bin\java.exe'
        if (-not (Test-Path $java)) { return }
        $major = Get-JavaMajor $java
        if ($major -ge 17 -and $major -le 21) {
            $candidates += [PSCustomObject]@{
                JavaHome = $_.FullName
                Major    = $major
                Sort     = 1000
                Name     = $_.Name
            }
        }
    }
}

$best = $candidates |
    Sort-Object Sort, @{ Expression = { $_.Major }; Descending = $true }, @{ Expression = { $_.Name }; Descending = $true } |
    Select-Object -First 1

if ($best) {
    Write-Output $best.JavaHome
}